"""
SLD Layout section placement functions.

Each function places one section of the SLD diagram, reading/writing
the shared _LayoutContext. Functions are called sequentially by
compute_layout() in engine.py.

Section order (bottom-up):
1. _parse_requirements   — normalize inputs into ctx fields
2. _place_incoming_supply — AC symbol, phase lines (landlord only)
3. _place_meter_board     — horizontal meter board [ISO]-[KWH]-[MCB]
4. _place_unit_isolator   — unit isolator (ct_meter, landlord)
5. _place_main_breaker    — main circuit breaker
6. _place_elcb            — ELCB/RCCB inline (conditional)
7. _place_main_busbar     — busbar + DB info computation
8. _place_sub_circuits_rows — multi-row sub-circuit orchestrator
9. _place_db_box          — dashed DB box rectangle
10. _place_earth_bar      — earth bar + conductor
"""

from __future__ import annotations

import logging
import re
from typing import NamedTuple

from app.sld.layout.helpers import (
    _assign_circuit_ids,
    _get_circuit_poles,
    _next_standard_rating,
    _pad_spares_for_triplets,
    _place_sub_circuits_upward,
    _should_use_triplets,
    _split_into_rows,
)
from app.sld.layout.models import (
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    _LayoutContext,
    format_cable_spec,
)
from app.sld.layout.overlap import _compute_dynamic_spacing
from app.sld.locale import SG_LOCALE

logger = logging.getLogger(__name__)


def _parse_supply_config(ctx: _LayoutContext, requirements: dict) -> None:
    """Supply type, landlord, cable extension 파싱."""
    # -- Normalize input keys (handle alternative key names from agent) --
    supply_type = (requirements.get("supply_type")
                   or requirements.get("system_type")
                   or requirements.get("phase_config", "three_phase"))
    # Normalize: "single_phase" / "1-phase" / "single" → "single_phase"
    if "single" in str(supply_type).lower() or "1" in str(supply_type):
        supply_type = "single_phase"
    else:
        supply_type = "three_phase"

    ctx.supply_type = supply_type
    ctx.supply_source = requirements.get("supply_source", "sp_powergrid")
    ctx.is_cable_extension = requirements.get("is_cable_extension", False)
    # Cable extension uses landlord supply path but with distinct labeling
    if ctx.is_cable_extension and ctx.supply_source != "landlord":
        ctx.supply_source = "landlord"
    ctx.kva = requirements.get("kva", 0)
    ctx.voltage = 400 if supply_type == "three_phase" else 230
    ctx.incoming_cable = requirements.get("incoming_cable", "")

    ctx.result.supply_type = supply_type
    ctx.result.voltage = ctx.voltage


def _parse_main_breaker(ctx: _LayoutContext, requirements: dict) -> None:
    """Main breaker rating, type, poles, kA, characteristic 추출."""
    supply_type = ctx.supply_type

    main_breaker = requirements.get("main_breaker", {})
    ctx.breaker_type = str(main_breaker.get("type", "MCCB")).upper()
    ctx.breaker_rating = main_breaker.get("rating", 0) or main_breaker.get("rating_A", 0)
    # Fallback: parse db_rating string (e.g., "63A" → 63)
    if not ctx.breaker_rating:
        db_rating_str = str(requirements.get("db_rating", ""))
        m = re.match(r"(\d+)", db_rating_str)
        if m:
            ctx.breaker_rating = int(m.group(1))
    # Defensive: negative breaker rating → reset to 0
    if isinstance(ctx.breaker_rating, (int, float)) and ctx.breaker_rating < 0:
        logger.warning("breaker_rating is negative (%s), resetting to 0", ctx.breaker_rating)
        ctx.breaker_rating = 0

    ctx.breaker_poles = main_breaker.get("poles", "")
    ctx.breaker_fault_kA = main_breaker.get("fault_kA", 0)

    # Auto-determine poles if not specified (DP = Double Pole, TPN = Triple Pole + Neutral)
    if not ctx.breaker_poles:
        ctx.breaker_poles = "TPN" if supply_type == "three_phase" else "DP"

    # Auto-determine fault level if not specified
    if not ctx.breaker_fault_kA:
        from app.sld.standards import get_fault_level
        ctx.breaker_fault_kA = get_fault_level(ctx.breaker_type, ctx.kva)

    ctx.meter_poles = "DP" if supply_type == "single_phase" else "4P"

    # Main breaker characteristic (B/C/D) — IEC 60898-1 trip curve
    # Accept multiple key names: breaker_characteristic, characteristic, breaker_char, char
    ctx.main_breaker_char = str(
        main_breaker.get("breaker_characteristic", "")
        or main_breaker.get("characteristic", "")
        or main_breaker.get("breaker_char", "")
        or main_breaker.get("char", "")
    ).upper()

    # Metering type
    # Landlord supply can have PG KWH meter board (sp_meter).
    # Cable extension is landlord but has NO meter board.
    is_cable_ext = requirements.get("is_cable_extension", False)
    if is_cable_ext:
        ctx.metering = None
    else:
        raw_metering = requirements.get("metering", "sp_meter")
        # Normalize common variants: "ct_metered" → "ct_meter"
        if isinstance(raw_metering, str) and raw_metering.lower().replace("_", "").replace("-", "") in (
            "ctmetered", "ctmeter", "ct",
        ):
            raw_metering = "ct_meter"
        ctx.metering = raw_metering


def _parse_elcb_config(ctx: _LayoutContext, requirements: dict) -> None:
    """ELCB 설정 파싱 (dict/non-dict 처리)."""
    ctx.elcb_config = requirements.get("elcb", {})
    ctx.elcb_rating = ctx.elcb_config.get("rating", 0) if isinstance(ctx.elcb_config, dict) else 0
    ctx.elcb_ma = ctx.elcb_config.get("sensitivity_ma", 30) if isinstance(ctx.elcb_config, dict) else 30
    ctx.elcb_type_str = (
        ctx.elcb_config.get("type", "ELCB").upper()
        if isinstance(ctx.elcb_config, dict) else "ELCB"
    )

    # CT ratio parsing (e.g., "200/5A")
    # Accept: "ct": {"ratio": "100/5A"} or "ct": "100/5A" or "ct_ratio": "100/5A"
    ct_config = requirements.get("ct", {})
    if isinstance(ct_config, dict):
        ctx.ct_ratio = ct_config.get("ratio", "")
    elif isinstance(ct_config, str):
        ctx.ct_ratio = ct_config
    # Fallback: top-level "ct_ratio" key
    if not ctx.ct_ratio:
        ctx.ct_ratio = requirements.get("ct_ratio", "")

    # CT metering section details
    # Accept from "metering_config" sub-dict OR top-level keys as fallback.
    # metering_detail (from extraction) is lowest priority, metering_config overrides.
    metering_cfg = {**requirements.get("metering_detail", {}),
                    **requirements.get("metering_config", {})}
    if not isinstance(metering_cfg, dict):
        metering_cfg = {}
    # Protection CT: ratio is NOT shown by default (reference DWGs show only "CT\P<class>").
    # Only show ratio if explicitly provided in metering_config/metering_detail.
    ctx.protection_ct_ratio = metering_cfg.get("protection_ct_ratio", "")
    ctx.metering_ct_class = metering_cfg.get("metering_ct_class", "CL1 5VA")
    ctx.protection_ct_class = metering_cfg.get("protection_ct_class", "5P10 20VA")
    ctx.has_ammeter = metering_cfg.get(
        "has_ammeter", requirements.get("has_ammeter", True))
    ctx.has_voltmeter = metering_cfg.get(
        "has_voltmeter", requirements.get("has_voltmeter", True))
    ctx.has_elr = metering_cfg.get(
        "has_elr", requirements.get("has_elr", True))
    ctx.has_indicator_lights = metering_cfg.get("has_indicator_lights", True)
    # ELR spec: accept metering_config.elr_spec or top-level "elr" dict
    ctx.elr_spec = metering_cfg.get("elr_spec", "")
    if not ctx.elr_spec:
        elr_cfg = requirements.get("elr", {})
        if isinstance(elr_cfg, dict):
            parts = []
            if elr_cfg.get("rating"):
                parts.append(str(elr_cfg["rating"]))
            if elr_cfg.get("time_delay"):
                parts.append(str(elr_cfg["time_delay"]))
            ctx.elr_spec = " ".join(parts)
        elif isinstance(elr_cfg, str):
            ctx.elr_spec = elr_cfg
    ctx.voltmeter_range = metering_cfg.get("voltmeter_range", "")
    ctx.ammeter_range = metering_cfg.get("ammeter_range", "")


def _parse_incoming_cable(ctx: _LayoutContext, requirements: dict) -> None:
    """Auto-cable generation logic (INCOMING_SPEC 테이블 참조)."""
    supply_type = ctx.supply_type

    if not ctx.incoming_cable and ctx.breaker_rating:
        try:
            from app.sld.sld_spec import INCOMING_SPEC, INCOMING_SPEC_3PHASE
            spec_table = INCOMING_SPEC_3PHASE if supply_type == "three_phase" else INCOMING_SPEC
            spec = spec_table.get(ctx.breaker_rating)
            # Fallback: try the other table if rating not found
            if spec is None:
                spec = INCOMING_SPEC.get(ctx.breaker_rating)
            if spec:
                # Parse "4 X 1 CORE" → count=4, cores=1 / "1 X 4 CORE" → count=1, cores=4
                _m = re.match(r"(\d+)\s*X\s*(\d+)\s*CORE", spec.cable_cores)
                _count = int(_m.group(1)) if _m else 1
                _cores = int(_m.group(2)) if _m else 1
                # For single-core cables: SLD convention shows main conductors only
                # (CPC is specified separately as "+ Xsqmm CPC")
                # SP: L + N = 2 main conductors / 3P: L1 + L2 + L3 + N = 4
                if _cores == 1:
                    _count = 2 if supply_type == "single_phase" else 4
                ctx.incoming_cable = {
                    "count": _count,
                    "cores": _cores,
                    "size_mm2": spec.cable_size.split(" + ")[0].replace("mmsq E", "").strip(),
                    "type": spec.cable_type,
                    "cpc_mm2": spec.cable_size.split(" + ")[1].replace("mmsq E", "").strip()
                                if " + " in spec.cable_size else "",
                    "cpc_type": spec.cable_type.split("/")[-1] if "/" in spec.cable_type else "PVC",
                    "method": spec.method,
                }
        except Exception as exc:
            logger.warning("Incoming cable spec lookup failed: %s", exc)


def _parse_sub_circuits(ctx: _LayoutContext, requirements: dict, application_info: dict | None) -> None:
    """Sub-circuit 리스트 파싱 + busbar rating 결정."""
    raw_circuits = requirements.get("sub_circuits", []) or requirements.get("circuits", [])
    if not isinstance(raw_circuits, list):
        logger.warning("sub_circuits is not a list (%s), using empty list", type(raw_circuits).__name__)
        raw_circuits = []

    # Layer 1+2: Normalize inputs and resolve domain defaults
    premises_type = ""
    if application_info and isinstance(application_info, dict):
        premises_type = str(application_info.get("premises_type", "")).strip()
    try:
        from app.sld.circuit_normalizer import normalize_circuit
        from app.sld.circuit_types import resolve_circuit
        raw_circuits = [resolve_circuit(normalize_circuit(dict(sc)), premises_type) for sc in raw_circuits]
    except Exception as exc:
        logger.warning("Circuit normalization failed, using raw input: %s", exc)

    ctx.sub_circuits = raw_circuits
    ctx.premises_type = premises_type

    ctx.busbar_rating = requirements.get("busbar_rating", 0)
    if not ctx.busbar_rating:
        # Per SG standard: minimum 100A COMB BUSBAR for installations ≤ 100A
        ctx.busbar_rating = max(100, ctx.breaker_rating)


def _parse_requirements(ctx: _LayoutContext, requirements: dict, application_info: dict | None) -> None:
    """Parse and normalize all requirement inputs into ctx fields.

    Orchestrator that delegates to sub-functions for each parsing step.
    Includes defensive type checks for robustness against malformed input.
    """
    # -- Defensive type checks --
    if not isinstance(requirements, dict):
        logger.warning("requirements is not a dict (%s), using empty dict", type(requirements).__name__)
        requirements = {}

    _parse_supply_config(ctx, requirements)
    _parse_main_breaker(ctx, requirements)
    _parse_elcb_config(ctx, requirements)
    _parse_incoming_cable(ctx, requirements)
    _parse_sub_circuits(ctx, requirements, application_info)

    # Board name: from distribution_boards[0] or requirements top-level
    dbs = requirements.get("distribution_boards")
    if dbs and isinstance(dbs, list) and len(dbs) >= 1:
        ctx.board_name = dbs[0].get("name") or dbs[0].get("db_name") or ""
    if not ctx.board_name:
        ctx.board_name = requirements.get("db_name", "")


def _place_incoming_supply(ctx: _LayoutContext) -> None:
    """Place incoming supply label, AC symbol, phase lines, and cable annotation.

    For metered supply (SP PowerGrid): skip all visuals here.
    The meter board handles supply entry from the RIGHT side with its own
    INCOMING SUPPLY label. No AC symbol or phase lines needed at the bottom.

    For non-metered supply (landlord): place AC symbol, phase lines, and label.
    """
    result = ctx.result
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    supply_source = ctx.supply_source
    kva = ctx.kva
    voltage = ctx.voltage
    metering = ctx.metering

    # For SP metered supply, _place_meter_board() handles everything.
    # No AC symbol, phase lines, or labels needed at the bottom.
    # CT metered supply still needs the incoming section (supply label + cable)
    # because CT metering is placed inside the DB box, not at the bottom.
    if metering and metering != "ct_meter":
        ctx.y = y
        return

    result.sections_rendered["incoming_supply"] = True

    # --- Non-metered / CT-metered supply (landlord / building_riser) ---
    # Priority: user-specified label > cable extension > supply_source > default
    if ctx.requirements.get("incoming_label"):
        supply_label = ctx.requirements["incoming_label"]
    elif ctx.is_cable_extension:
        supply_label = SG_LOCALE.incoming.from_power_supply
    elif supply_source == "building_riser":
        supply_label = SG_LOCALE.incoming.from_building_riser
    elif ctx.requirements.get("supply_label_type") == "supply":
        supply_label = SG_LOCALE.incoming.from_landlord_supply
    else:
        supply_label = SG_LOCALE.incoming.from_landlord

    if supply_source in ("landlord", "building_riser"):
        # ── Landlord / building riser supply: label RIGHT + cable tick marks ──
        # Both use same visual style per LEW practice (enclosed isolator path).
        _h_tick_len = 5
        result.connections.append(((cx, y), (cx + _h_tick_len, y)))
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=cx + _h_tick_len + 2,
            y=y + 1.5,
            label=supply_label,
        ))

        # Vertical cable segment (extended for tick mark clearance)
        _seg_h = 14
        result.connections.append(((cx, y), (cx, y + _seg_h)))

        # Cable tick mark + annotation to the LEFT (reference: incoming cable = left side)
        incoming_cable = ctx.incoming_cable
        cable_text = format_cable_spec(incoming_cable, multiline=True)
        if cable_text:
            tick_y = y + _seg_h * 0.65
            tick_size = 1.25
            result.thick_connections.append((
                (cx - tick_size, tick_y - tick_size),
                (cx + tick_size, tick_y + tick_size),
            ))
            _leader_len = ctx.config.cable_leader_len
            result.connections.append(((cx, tick_y), (cx - _leader_len, tick_y)))
            # Right-align text to left of leader end (clamped to drawing boundary)
            # For multiline text (\P separator), use longest line for width calculation
            _label_ch = 2.3
            _char_w = _label_ch * 0.6
            _lines = cable_text.split("\\P")
            _longest = max(len(ln) for ln in _lines)
            _text_w = _longest * _char_w
            _text_gap = ctx.config.cable_leader_text_gap
            _text_x = cx - _leader_len - _text_gap - _text_w
            _text_x = max(_text_x, ctx.config.min_x + 2)  # clamp to left boundary
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=_text_x,
                y=tick_y + 1.5,
                label=cable_text,
            ))

        y += _seg_h
    else:
        # ── SP / cable extension: label LEFT + AC symbol + phase lines ──
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=cx - 80,
            y=y + 8,
            label=supply_label,
        ))

        # AC supply symbol "~" (wave sign at bottom — Singapore LEW convention)
        result.components.append(PlacedComponent(
            symbol_name="FLOW_ARROW_UP",
            x=cx,
            y=y - 3,
        ))

        # Phase lines with labels (at bottom, pointing upward) — compact layout
        ph_half = 3
        if supply_type == "three_phase":
            spacing = 4
            for offset, label in [(-spacing*1.5, "L1"), (-spacing*0.5, "L2"),
                                   (spacing*0.5, "L3"), (spacing*1.5, "N")]:
                result.connections.append(((cx + offset, y - ph_half), (cx + offset, y + ph_half)))
                result.components.append(PlacedComponent(
                    symbol_name="LABEL",
                    x=cx + offset - 2,
                    y=y - ph_half - 3,
                    label=label,
                ))
            result.connections.append(((cx - spacing * 1.5, y + ph_half), (cx + spacing * 1.5, y + ph_half)))
            result.connections.append(((cx, y + ph_half), (cx, y + ph_half + 4)))
        else:
            result.connections.append(((cx, y - ph_half), (cx, y + ph_half)))
            result.connections.append(((cx, y + ph_half), (cx, y + ph_half + 4)))
        y += ph_half + 4

        # Cable annotation (no tick mark for SP supply)
        incoming_cable = ctx.incoming_cable
        cable_text = format_cable_spec(incoming_cable)
        if cable_text:
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=cx + 12,
                y=y - 3,
                label=cable_text,
            ))

    ctx.y = y


class _MeterBoardGeom(NamedTuple):
    """Computed geometry for horizontal meter board layout."""
    # Component centers (horizontal)
    iso_cx: float
    kwh_cx: float
    mcb_cx: float
    mb_center_y: float
    # Horizontal extents (rotated 90°: symbol height → horizontal)
    iso_h_extent: float
    kwh_h_extent: float
    mcb_h_extent: float
    # Pin X positions
    iso_left_x: float
    iso_right_x: float
    mcb_left_x: float
    mcb_right_x: float
    # Box boundaries
    mb_box_left: float
    mb_box_right: float
    mb_box_top: float
    mb_box_bottom: float
    # Label Y positions
    kwh_label_y: float
    mb_label_y: float
    # Stub length
    stub: float


def _compute_meter_board_geom(config: LayoutConfig, cx: float, y: float) -> _MeterBoardGeom:
    """Compute all meter board geometry from config and position (pure function).

    Returns a _MeterBoardGeom with all coordinates needed for placement.
    """
    comp_spacing = config.meter_board_comp_spacing
    _stub = config.stub_len
    _mb_inset = config.meter_board_inset

    # Horizontal extents (symbol.height → h_extent when rotated 90°)
    iso_h_extent = config.isolator_h
    kwh_h_extent = config.kwh_rect_w
    mcb_h_extent = config.mcb_h

    # Vertical half-extents (symbol.width/2 → v_half when rotated 90°)
    iso_v_half = config.isolator_w / 2
    kwh_v_half = config.kwh_rect_h / 2
    mcb_v_half = config.mcb_w / 2
    max_v_half = max(iso_v_half, kwh_v_half, mcb_v_half)

    # Text sizes (must match generator.py)
    _comp_label_ch = 1.6
    _comp_label_lsp = 1.4
    _comp_label_lines = 2
    _comp_label_h = _comp_label_ch * _comp_label_lsp * _comp_label_lines
    _anno_label_ch = 2.8

    # Component centers
    iso_cx = cx + iso_h_extent / 2 + _stub + _mb_inset
    kwh_cx = iso_cx + comp_spacing
    mcb_cx = iso_cx + 2 * comp_spacing
    mb_center_y = y + 8

    # Pin positions — connection lines reach directly to component pin (body edge).
    # DXF blocks have pins at body edge; procedural symbols draw stubs that
    # extend outward from body edge, overlapping harmlessly with connection lines.
    iso_left_x = iso_cx - iso_h_extent / 2
    iso_right_x = iso_cx + iso_h_extent / 2
    mcb_left_x = mcb_cx - mcb_h_extent / 2
    mcb_right_x = mcb_cx + mcb_h_extent / 2

    # Vertical bands — above center
    _gap = config.meter_board_gap
    kwh_label_y = mb_center_y + max_v_half + _gap + _anno_label_ch
    mb_box_top = kwh_label_y + _gap

    # Vertical bands — below center
    comp_label_y = mb_center_y - max_v_half - _gap
    comp_label_bot = comp_label_y - _comp_label_h
    mb_label_y = comp_label_bot - 2
    mb_label_bot = mb_label_y - _anno_label_ch
    mb_box_bottom = mb_label_bot - 1

    # Box horizontal extent
    iso_body_left = iso_cx - iso_h_extent / 2
    mb_box_left = iso_body_left - 4
    mb_box_right = mcb_right_x + 4

    return _MeterBoardGeom(
        iso_cx=iso_cx, kwh_cx=kwh_cx, mcb_cx=mcb_cx,
        mb_center_y=mb_center_y,
        iso_h_extent=iso_h_extent, kwh_h_extent=kwh_h_extent, mcb_h_extent=mcb_h_extent,
        iso_left_x=iso_left_x, iso_right_x=iso_right_x,
        mcb_left_x=mcb_left_x, mcb_right_x=mcb_right_x,
        mb_box_left=mb_box_left, mb_box_right=mb_box_right,
        mb_box_top=mb_box_top, mb_box_bottom=mb_box_bottom,
        kwh_label_y=kwh_label_y, mb_label_y=mb_label_y,
        stub=_stub,
    )


def _place_meter_board_symbols(
    ctx: _LayoutContext, g: _MeterBoardGeom,
) -> None:
    """Place ISO, CT, KWH, MCB symbols and inter-component wiring."""
    result = ctx.result

    # Spine → ISO routing
    if g.iso_left_x > ctx.cx:
        result.connections.append(((ctx.cx, g.mb_center_y), (g.iso_left_x, g.mb_center_y)))

    # ISOLATOR (left) — Reference uses procedural isolator symbol (2 circles + diagonal)
    result.components.append(PlacedComponent(
        symbol_name="ISOLATOR",
        x=g.iso_cx - g.iso_h_extent / 2,
        y=g.mb_center_y,
        label=f"{ctx.breaker_rating}A {ctx.meter_poles}",
        rating=SG_LOCALE.meter_board.isolator,
        rotation=90.0,
    ))
    result.symbols_used.add("ISOLATOR")

    # ISO → KWH wiring (connection reaches KWH left pin / body edge)
    kwh_left_x = g.kwh_cx - g.kwh_h_extent / 2
    result.connections.append(((g.iso_right_x, g.mb_center_y), (kwh_left_x, g.mb_center_y)))

    # CT (between ISO and KWH) — ct_meter + non-landlord only
    if ctx.metering == "ct_meter" and ctx.supply_source != "landlord":
        ct_mid_x = (g.iso_cx + g.kwh_cx) / 2
        ct_r = ctx.config.ct_size / 2
        ct_label = f"{ctx.ct_ratio} CT" if ctx.ct_ratio else SG_LOCALE.meter_board.ct_by_sp
        result.components.append(PlacedComponent(
            symbol_name="CT", x=ct_mid_x - ct_r, y=g.mb_center_y - ct_r, label=ct_label,
        ))
        result.symbols_used.add("CT")

    # KWH METER — x = left edge (same pattern as ISO/MCB for pin="left" alignment)
    result.components.append(PlacedComponent(
        symbol_name="KWH_METER", x=g.kwh_cx - g.kwh_h_extent / 2, y=g.mb_center_y, rotation=90.0,
    ))
    result.symbols_used.add("KWH_METER")

    # KWH label (above symbols, inside box)
    _kwh_label = ctx.requirements.get("kwh_label")
    if not _kwh_label:
        if ctx.supply_source == "landlord":
            _kwh_label = SG_LOCALE.meter_board.kwh_meter_pg
        else:
            _kwh_label = SG_LOCALE.meter_board.kwh_meter_by_sp
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=(g.iso_cx + g.mcb_cx) / 2 - 10,
        y=g.kwh_label_y,
        label=_kwh_label,
    ))

    # KWH → MCB wiring (connection reaches KWH right pin / body edge)
    kwh_right_x = g.kwh_cx + g.kwh_h_extent / 2
    result.connections.append(((kwh_right_x, g.mb_center_y), (g.mcb_left_x, g.mb_center_y)))

    # MCB (right)
    _mcb_poles = ctx.breaker_poles
    _mcb_char = ctx.main_breaker_char or "B"
    _mcb_ka = ctx.breaker_fault_kA or 10
    result.components.append(PlacedComponent(
        symbol_name="CB_MCB",
        x=g.mcb_cx - g.mcb_h_extent / 2,
        y=g.mb_center_y,
        label=f"{ctx.breaker_rating}A {_mcb_poles} MCB",
        rating=f"TYPE {_mcb_char} {_mcb_ka}kA",
        rotation=90.0,
    ))
    result.symbols_used.add("MCB")


def _add_incoming_supply_line(ctx: _LayoutContext, g: _MeterBoardGeom) -> None:
    """Add horizontal supply entry line, incoming label, and cable tick annotation."""
    result = ctx.result

    # Supply entry line from MCB rightward
    supply_end_x = g.mcb_right_x + 20
    result.connections.append(((g.mcb_right_x, g.mb_center_y), (supply_end_x, g.mb_center_y)))

    # Incoming label
    if ctx.requirements.get("incoming_label"):
        supply_label = ctx.requirements["incoming_label"]
    elif ctx.is_cable_extension:
        supply_label = SG_LOCALE.incoming.from_power_supply
    elif ctx.supply_source == "landlord":
        if ctx.requirements.get("supply_label_type") == "supply":
            supply_label = SG_LOCALE.incoming.from_landlord_supply
        else:
            supply_label = SG_LOCALE.incoming.from_landlord
    elif ctx.supply_source == "building_riser":
        supply_label = SG_LOCALE.incoming.from_building_riser
    else:
        supply_label = SG_LOCALE.incoming.incoming_hdb
    result.components.append(PlacedComponent(
        symbol_name="LABEL", x=supply_end_x + 3, y=g.mb_center_y + 3, label=supply_label,
    ))

    # Incoming cable tick + leader line
    # For landlord supply, the outgoing cable tick (DB→meter board) already labels
    # the same cable — skip here to avoid duplication (ref DXF has only 1 cable label)
    if ctx.supply_source == "landlord":
        return
    cable_text = format_cable_spec(ctx.incoming_cable, multiline=True)
    if cable_text:
        tick_x = g.mcb_right_x + 10
        tick_size = 1.5
        result.connections.append((
            (tick_x - tick_size, g.mb_center_y - tick_size),
            (tick_x + tick_size, g.mb_center_y + tick_size),
        ))
        leader_bottom_y = g.mb_center_y - 10
        result.connections.append(((tick_x, g.mb_center_y), (tick_x, leader_bottom_y)))
        shelf_len = 3
        result.connections.append(((tick_x, leader_bottom_y), (tick_x + shelf_len, leader_bottom_y)))
        _label_ch = 2.8
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=tick_x + shelf_len + 1,
            y=leader_bottom_y + _label_ch * 0.5,
            label=cable_text,
        ))


def _add_outgoing_cable_tick(
    ctx: _LayoutContext, g: _MeterBoardGeom, y_exit: float,
) -> None:
    """Add outgoing cable tick mark and annotation on vertical exit line."""
    result = ctx.result
    cx = ctx.cx
    outgoing_cable_text = format_cable_spec(ctx.incoming_cable, multiline=True)
    if not outgoing_cable_text:
        return

    tick_y = (g.mb_box_top + y_exit) / 2
    tick_size = 1.25
    result.thick_connections.append((
        (cx - tick_size, tick_y - tick_size),
        (cx + tick_size, tick_y + tick_size),
    ))
    _leader_len = 3
    result.connections.append(((cx, tick_y), (cx - _leader_len, tick_y)))

    _label_ch = 2.8
    _char_w = _label_ch * 0.6
    _lines = outgoing_cable_text.split("\\P")
    _max_line_len = max(len(ln) for ln in _lines) if _lines else 20
    _text_width = _max_line_len * _char_w
    _text_gap = ctx.config.cable_leader_text_gap
    _text_x = cx - _leader_len - _text_gap - _text_width
    result.components.append(PlacedComponent(
        symbol_name="LABEL", x=_text_x, y=tick_y + _label_ch * 0.5, label=outgoing_cable_text,
    ))


def _add_meter_board_box_and_earth(ctx: _LayoutContext, g: _MeterBoardGeom) -> None:
    """Draw dashed box, labels, and earth symbol (3-phase only)."""
    result = ctx.result
    config = ctx.config

    # Dashed box
    result.dashed_connections.append(((g.mb_box_left, g.mb_box_bottom), (g.mb_box_right, g.mb_box_bottom)))
    result.dashed_connections.append(((g.mb_box_left, g.mb_box_top), (g.mb_box_right, g.mb_box_top)))
    result.dashed_connections.append(((g.mb_box_left, g.mb_box_bottom), (g.mb_box_left, g.mb_box_top)))
    result.dashed_connections.append(((g.mb_box_right, g.mb_box_bottom), (g.mb_box_right, g.mb_box_top)))

    # "METER BOARD" label inside box
    result.components.append(PlacedComponent(
        symbol_name="LABEL", x=g.mb_box_left + 1, y=g.mb_label_y,
        label=SG_LOCALE.meter_board.meter_board,
    ))
    # "LOCATED AT METER COMPARTMENT" below box — only if no DB-level location text
    # Reference DXF: location info appears in DB box area only, not at meter board
    if not ctx.db_location_text:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=(g.mb_box_left + g.mb_box_right) / 2 - 18,
            y=g.mb_box_bottom - 3,
            label=SG_LOCALE.meter_board.located_meter_compartment,
        ))

    # Earth symbol — 3-phase only
    if ctx.supply_type != "single_phase":
        from app.sld.real_symbols import get_symbol_dimensions
        dims = get_symbol_dimensions("EARTH")
        ew, eh = dims["width_mm"], dims["height_mm"]
        earth_cx = g.mb_box_right + 4
        earth_x = earth_cx - ew / 2
        earth_top_pin_y = g.mb_box_bottom - config.earth_x_from_db / 2
        earth_y = earth_top_pin_y - eh
        junction_y = g.mb_box_bottom + 3

        result.connections.append(((g.mb_box_right, junction_y), (earth_cx, junction_y)))
        result.connections.append(((earth_cx, junction_y), (earth_cx, earth_top_pin_y)))
        result.junction_dots.append((g.mb_box_right, junction_y))
        result.components.append(PlacedComponent(
            symbol_name="EARTH", x=earth_x, y=earth_y, label="E",
        ))
        result.symbols_used.add("EARTH")


def _place_meter_board(ctx: _LayoutContext) -> None:
    """Place meter board section HORIZONTALLY: [ISO]--[KWH]--[MCB] on same Y line.

    Horizontal layout matching professional LEW drawings.
    Delegates to sub-functions for geometry computation, symbol placement,
    cable annotations, and box/earth drawing.
    """
    y = ctx.y

    if ctx.metering:
        ctx.result.sections_rendered["meter_board"] = True
        g = _compute_meter_board_geom(ctx.config, ctx.cx, y)

        _place_meter_board_symbols(ctx, g)
        _add_incoming_supply_line(ctx, g)

        # Vertical exit line from spine
        outgoing_cable_text = format_cable_spec(ctx.incoming_cable, multiline=True)
        y_exit = g.mb_box_top + (16 if outgoing_cable_text else 8)
        ctx.result.connections.append(((ctx.cx, g.mb_center_y), (ctx.cx, y_exit)))

        _add_outgoing_cable_tick(ctx, g, y_exit)
        _add_meter_board_box_and_earth(ctx, g)

        y = y_exit

    ctx.y = y


# ---------------------------------------------------------------------------
# CT Metering Section — Vertical layout for ≥125A 3-phase installations
# ---------------------------------------------------------------------------

# ── Electrical Flow Specification (Singapore CT Metering) ──
# Defines the CORRECT order of spine components from supply (bottom) to
# load (top) in a CT metering installation (≥125A, 3-phase, non-landlord).
#
# Each entry: (symbol_name, role_description)
# This order is validated by test_spine_flow_order.py to prevent regressions.
CT_METERING_SPINE_ORDER: list[tuple[str, str]] = [
    # --- Supply side (bottom, lowest Y) ---
    ("CB_MCCB",         "Main breaker — overcurrent protection after supply"),
    ("CT",              "Protection CT — for Earth Leakage Relay (ELR)"),
    # ELR branches LEFT from Protection CT (not on spine)
    ("CT",              "Metering CT — for kWh meter, ammeter, voltmeter"),
    # ASS+Ammeter LEFT, VSS+Voltmeter RIGHT, KWH RIGHT (branches, not on spine)
    ("BI_CONNECTOR",    "Busbar Interconnect — connects metering to distribution"),
    # --- Load side (top, highest Y) ---
    #
    # NOTE: 2A POTENTIAL_FUSE is NOT on the spine. Per 150A/400A TPN reference
    # DWGs, fuses are horizontal branch elements that depart rightward from
    # T-junctions on the spine. The vertical spine LINE runs uninterrupted
    # past fuse junction points.
    # Ref: 150A DXF — FUSE INSERT at X≈23494 (1100 DU right of spine X≈22385),
    #      horizontal LINE (22385,6376)→(23230,6376) connects spine to fuse.
]


def _derive_ammeter_range(ct_ratio: str) -> str:
    """Derive ammeter range from CT ratio primary current.

    '100/5A' → '0-100A', '200/5A' → '0-200A'.
    Falls back to '0-500A' when ratio is absent or unparseable.
    """
    if not ct_ratio:
        return "0-500A"
    m = re.match(r"(\d+)/", ct_ratio)
    return f"0-{m.group(1)}A" if m else "0-500A"


def _place_ct_metering_section(ctx: _LayoutContext) -> None:
    """Place vertical CT metering section (≥125A 3-phase, non-landlord).

    Called AFTER _place_ct_pre_mccb_fuse and _place_main_breaker in engine.py.

    Correct flow order (bottom → top, supply → load):
      (Pre-MCCB fuse branch RIGHT + MCCB already placed by engine.py)
      → PROTECTION_CT [ELR LEFT]
      → METERING_CT
          [ASS+Ammeter LEFT, VSS+Voltmeter RIGHT — same height]
          [KWH RIGHT — below CT center]
      → 2A POTENTIAL_FUSE (RIGHT branch, NOT on spine)
      → BI_CONNECTOR (exit to ELCB/busbar)

    Components on the main vertical spine:
      CT (protection), CT (metering), BI_CONNECTOR
    Horizontal branches from spine:
      LEFT:  ELR (at Protection CT center), ASS→Ammeter (at Metering CT center)
      RIGHT: VSS→Voltmeter (at Metering CT center), KWH (below Metering CT),
             Pre-MCCB fuse (before MCCB), Post-CT fuse (after Metering CT)

    DXF reference: 2A FUSE is always a horizontal branch element. The vertical
    spine LINE runs uninterrupted past fuse junction points.
    Ref: CT_METERING_SPINE_ORDER constant, 150A/400A TPN reference DWGs.
    """
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y

    result.sections_rendered["ct_metering_section"] = True

    ct_ratio = ctx.ct_ratio  # may be empty — "CT BY SP" label used when empty
    metering_ct_class = ctx.metering_ct_class or "CL1 5VA"
    protection_ct_ratio = ctx.protection_ct_ratio or ct_ratio
    protection_ct_class = ctx.protection_ct_class or "5P10 20VA"

    # Symbol dimensions (from JSON)
    from app.sld.real_symbols import get_symbol_dimensions
    bi_dims = get_symbol_dimensions("BI_CONNECTOR")
    ct_dims = get_symbol_dimensions("CT")
    pf_dims = get_symbol_dimensions("POTENTIAL_FUSE")
    bi_w, bi_h = bi_dims["width_mm"], bi_dims["height_mm"]
    bi_stub = bi_dims["stub_mm"]
    ct_w, ct_h = ct_dims["width_mm"], ct_dims["height_mm"]
    ct_stub = ct_dims["stub_mm"]
    pf_w, pf_h = pf_dims["width_mm"], pf_dims["height_mm"]
    pf_stub = pf_dims["stub_mm"]

    # Spacing constants — centralized in LayoutConfig (Phase 4: magic number → config)
    entry_gap = ctx.config.ct_entry_gap
    ct_to_ct_gap = ctx.config.ct_to_ct_gap
    ct_to_branch_gap = ctx.config.ct_to_branch_gap
    branch_arm_len = 15.0    # horizontal arm from spine to first component
    branch_gap = 3.0         # gap between components on a branch
    ct_to_pf_gap = 1.0       # gap between metering CT and potential fuse
    pf_to_bi_gap = 1.0       # gap between potential fuse and BI connector

    # ═══════════════════════════════════════════════════════════════════════
    # Correct flow order (bottom → top, supply → load):
    #   Protection CT → Metering CT → BI_CONNECTOR
    # Ref: CT_METERING_SPINE_ORDER constant
    #
    # Drawing strategy: ONE straight spine line from MCCB exit to BI
    # Connector. All CT components and branches overlay on this backbone.
    # ═══════════════════════════════════════════════════════════════════════

    spine_bottom = y  # entry point (after MCCB)

    # --- 1. Calculate component positions along spine ---
    cursor = y

    # Protection CT (on spine, closest to supply/MCCB)
    prot_ct_y = cursor
    prot_ct_center_y = cursor + ct_h / 2
    if ctx.has_elr:
        cursor += ct_h + ct_stub + ct_to_ct_gap

    # Reserve space for KWH branch arm between Protection CT and Metering CT.
    # The arm exits the spine at _kwh_branch_y; metering CT bottom stub must
    # be above that line so the arm doesn't visually cross the metering CT.
    from app.sld.real_symbols import get_real_symbol as _get_kwh_pre
    _kwh_pre = _get_kwh_pre("KWH_METER")
    _kwh_rect_h_pre = getattr(_kwh_pre, '_rect_h', 3.9)
    _kwh_arm_y_pre = prot_ct_center_y + 3.0 + _kwh_rect_h_pre / 2 + 1.5
    _min_metering_start = _kwh_arm_y_pre + ct_stub + 1.0
    cursor = max(cursor, _min_metering_start)

    # Metering CT (on spine)
    metering_ct_y = cursor
    metering_ct_center_y = cursor + ct_h / 2
    cursor += ct_h

    # Advance past branches (VSS placed midway between ASS and BI)
    highest_branch = metering_ct_center_y + ct_to_branch_gap * 2 + 5
    cursor = max(cursor, highest_branch)
    cursor += ct_to_ct_gap

    # BI Connector (on spine, closest to distribution busbar/load)
    bi_y = cursor
    spine_top = bi_y + bi_h + bi_stub

    # --- 2. Spine backbone — ONE straight line, MCCB exit → BI top ---
    result.connections.append(((cx, spine_bottom), (cx, spine_top)))

    # --- 3. Place components on spine ---

    # Protection CT
    if ctx.has_elr:
        if protection_ct_ratio:
            prot_ct_label = f"{protection_ct_ratio} CT\\P{protection_ct_class}"
        else:
            prot_ct_label = f"CT\\P{protection_ct_class}"
        result.components.append(PlacedComponent(
            symbol_name="CT", x=cx - ct_w / 2, y=prot_ct_y, label=prot_ct_label,
        ))

        # Branch from Protection CT (LEFT): ELR
        # Label: "ELR" prefix + spec on next line (reference format: "ELR\P0-3A 0.2 SEC")
        if ctx.elr_spec:
            elr_label = f"ELR\\P{ctx.elr_spec}"
        else:
            elr_label = "ELR"
        _place_metering_branch(
            result, cx, prot_ct_center_y, direction="left",
            components=[("ELR", elr_label, 12.0)],
            arm_len=branch_arm_len, gap=branch_gap,
        )
        result.junction_arrows.append((cx, prot_ct_center_y, "left"))
        result.symbols_used.add("ELR")

        # ELR sensing line: bottom exit → down → right to MCCB contacts
        # Reference: line exits from ELR bottom center, not left side
        if ctx.main_breaker_arc_center_y:
            from app.sld.real_symbols import get_real_symbol
            elr_sym = get_real_symbol("ELR")
            elr_hp = elr_sym.horizontal_pins(0, 0)
            elr_stub = getattr(elr_sym, '_stub', 2.0)
            elr_body = elr_hp["right"][0] - elr_hp["left"][0] - 2 * elr_stub
            # ELR bottom center X
            elr_comp_x = cx - branch_arm_len - elr_body
            elr_bottom_cx = elr_comp_x + elr_sym.width / 2
            # ELR bottom stub tip Y
            elr_bottom_y = prot_ct_center_y - elr_sym.height / 2 - elr_stub
            mccb_y = ctx.main_breaker_arc_center_y
            # Vertical down from bottom stub tip
            result.connections.append(((elr_bottom_cx, elr_bottom_y), (elr_bottom_cx, mccb_y)))
            # Horizontal right — stops just before MCCB contact gap
            result.connections.append(((elr_bottom_cx, mccb_y), (cx - 1.0, mccb_y)))

    # Metering CT
    if ct_ratio:
        ct_label = f"{ct_ratio}\\P{metering_ct_class}"
    else:
        ct_label = SG_LOCALE.meter_board.ct_by_sp
    # --- 4. Branches from Metering CT ---
    branch_y = metering_ct_center_y
    ass_branch_y = branch_y + ct_to_branch_gap

    # Place Metering CT — label aligned with ASS branch height
    result.components.append(PlacedComponent(
        symbol_name="CT", x=cx - ct_w / 2, y=metering_ct_y, label=ct_label,
        label_y_override=ass_branch_y + 3,
    ))
    result.symbols_used.add("CT")

    # Branch 1 (LEFT): ASS → Ammeter → (A) instrument circle
    if ctx.has_ammeter:
        _place_metering_branch(
            result, cx, ass_branch_y, direction="left",
            components=[
                ("SELECTOR_SWITCH", "ASS", 8.0),
                ("AMMETER", ctx.ammeter_range or "0-500A", 7.6),
                ("METER_A", "A", 5.0),
            ],
            arm_len=branch_arm_len, gap=branch_gap,
        )
        result.junction_arrows.append((cx, ass_branch_y, "left"))
        result.symbols_used.add("METER_A")

    # Branch 2 (RIGHT): VSS → Voltmeter → (V) instrument circle
    # Placed midway between ASS branch and BI Connector — per reference DWG.
    if ctx.has_voltmeter:
        _branch_y = (ass_branch_y + bi_y) / 2
        _place_metering_branch(
            result, cx, _branch_y, direction="right",
            components=[
                ("SELECTOR_SWITCH", "VSS", 8.0),
                ("VOLTMETER", ctx.voltmeter_range or "0-500V", 7.6),
                ("METER_V", "V", 5.0),
            ],
            arm_len=branch_arm_len, gap=branch_gap,
        )
        result.junction_arrows.append((cx, _branch_y, "right"))
        result.symbols_used.add("METER_V")

    # Branch 3 (RIGHT): KWH Meter (no MCB, no right stub — per reference DWG)
    # Return line connects 3mm above ELR hook (prot_ct_center_y).
    from app.sld.real_symbols import get_real_symbol as _get_kwh_sym
    _kwh_sym = _get_kwh_sym("KWH_METER")
    _kwh_rect_w = getattr(_kwh_sym, '_rect_w', 7.8)
    _kwh_rect_h = getattr(_kwh_sym, '_rect_h', 3.9)
    _kwh_hp = _kwh_sym.horizontal_pins(0, 0)

    _kwh_return_y = prot_ct_center_y + 3.0      # 3mm above ELR hook
    # KWH branch Y: box bottom must clear return line by ≥1.5mm
    _kwh_branch_y = _kwh_return_y + _kwh_rect_h / 2 + 1.5

    _kwh_label = "SPPG\\PKWH METER" if ctx.supply_source != "landlord" else "KWH METER"

    # Place KWH component (right of spine, no right stub)
    _kwh_comp_x = cx + branch_arm_len  # rect left edge (same as _place_metering_branch)
    result.components.append(PlacedComponent(
        symbol_name="KWH_METER",
        x=_kwh_comp_x,
        y=_kwh_branch_y,
        label=_kwh_label,
        rotation=90.0,
        no_right_stub=True,
    ))
    result.symbols_used.add("KWH_METER")

    # Arm connection: spine → KWH left pin
    result.connections.append(((cx, _kwh_branch_y), (cx + branch_arm_len, _kwh_branch_y)))
    result.junction_arrows.append((cx, _kwh_branch_y, "right"))

    # KWH return connection: bottom of KWH box → down → left to spine.
    # Creates CT measurement loop per reference DWG (SP Group §6.9.6).
    _kwh_bottom_cx = _kwh_comp_x + _kwh_rect_w / 2  # bottom center X of rect
    _kwh_bottom_y = _kwh_branch_y - _kwh_rect_h / 2  # bottom edge Y of rect
    # Vertical: KWH bottom → down to return Y
    result.connections.append((
        (_kwh_bottom_cx, _kwh_bottom_y),
        (_kwh_bottom_cx, _kwh_return_y),
    ))
    # Horizontal: return point → left to spine
    result.connections.append((
        (_kwh_bottom_cx, _kwh_return_y),
        (cx, _kwh_return_y),
    ))

    # BI Connector — include isolator/busbar rating (e.g., "100A BI CONNECTOR")
    _bi_rating = ctx.busbar_rating or ctx.breaker_rating or 0
    _bi_label = f"{_bi_rating}A BI CONNECTOR" if _bi_rating else "BI CONNECTOR"
    result.components.append(PlacedComponent(
        symbol_name="BI_CONNECTOR", x=cx - bi_w / 2, y=bi_y, label=_bi_label,
    ))
    result.symbols_used.add("BI_CONNECTOR")

    ctx.y = spine_top


def _place_metering_branch(
    result: LayoutResult, cx: float, branch_y: float,
    direction: str,
    components: list[tuple[str, str, float]],
    arm_len: float = 15.0,
    gap: float = 3.0,
) -> None:
    """Place a horizontal branch from the main spine.

    Components occupy their own space (no line through them).
    Connection lines bridge: spine→arm→comp1→gap→comp2→...
    Symbol widths are resolved from the registry to guarantee connectivity.

    Args:
        result: LayoutResult to add components/connections to.
        cx: spine X coordinate.
        branch_y: Y coordinate of the branch.
        direction: 'left' or 'right'.
        components: list of (symbol_name, label, width_hint) tuples.
            width_hint is used as fallback only if the symbol is not found
            in the registry; otherwise the real symbol width is used.
        arm_len: length of the initial arm from spine to first component.
        gap: gap between components on the branch.
    """
    from app.sld.real_symbols import get_real_symbol

    sign = -1 if direction == "left" else 1
    x = cx

    # Initial arm from spine to first component
    x_next = x + sign * arm_len
    result.connections.append(((x, branch_y), (x_next, branch_y)))
    x = x_next

    for i, (symbol_name, label, w_hint) in enumerate(components):
        # Resolve actual horizontal body extent from pin positions.
        # Most symbols: h_extent == height (rotated 90°).
        # Some (ELR, KWH_METER, INDICATOR_LIGHTS) use a different extent.
        try:
            sym = get_real_symbol(symbol_name)
            hp = sym.horizontal_pins(0, 0)
            stub = getattr(sym, '_stub', 3.0)
            w = hp["right"][0] - hp["left"][0] - 2 * stub
        except (ValueError, KeyError):
            w = w_hint

        # Place symbol (horizontal orientation)
        if sign > 0:  # right
            comp_x = x
        else:  # left: symbol extends leftward
            comp_x = x - w

        result.components.append(PlacedComponent(
            symbol_name=symbol_name,
            x=comp_x,
            y=branch_y,
            label=label,
            rotation=90.0,
        ))
        result.symbols_used.add(symbol_name.replace("CB_", ""))

        # Advance past this component
        x = x + sign * w

        # Gap connection to next component (if not last)
        if i < len(components) - 1:
            x_next = x + sign * gap
            result.connections.append(((x, branch_y), (x_next, branch_y)))
            x = x_next


def _place_ct_pre_mccb_fuse(ctx: _LayoutContext) -> None:
    """Place 2A potential fuse + indicator lights as horizontal RIGHT branch.

    Called from engine.py BEFORE _place_main_breaker when CT metering is active.
    This fuse isolates the CT voltage sensing circuits from supply.
    Indicator lights (○-○-○) show R/Y/B incoming phase status.

    DXF reference: 2A FUSE is a horizontal branch element, NOT on the vertical
    spine. LED IND LTG block (3 circles) follows the fuse on the branch.
    Ref: 150A/400A TPN DWGs — FUSE at X≈23494, LED IND LTG at X≈24250.
    """
    if not ctx._ct_pre_mccb_fuse:
        return

    ctx.result.sections_rendered["ct_pre_mccb_fuse"] = True
    from app.sld.real_symbols import get_symbol_dimensions
    pf_dims = get_symbol_dimensions("POTENTIAL_FUSE")
    pf_h = pf_dims["height_mm"]  # 8mm — horizontal extent when rotated

    result = ctx.result
    cx = ctx.cx
    y = ctx.y

    # Build branch components: fuse + optional indicator lights
    components: list[tuple[str, str, float]] = [
        ("POTENTIAL_FUSE", "2A", pf_h),
    ]
    if ctx.has_indicator_lights:
        il_dims = get_symbol_dimensions("INDICATOR_LIGHTS")
        components.append(("INDICATOR_LIGHTS", "", il_dims["width_mm"]))

    # Place as horizontal RIGHT branch (T-junction from spine)
    # DB box bottom is at ~(y - 1), so offset +4 gives 5mm clearance.
    branch_y = y + 4.0
    _place_metering_branch(
        result, cx, branch_y, direction="right",
        components=components,
        arm_len=8.0,
        gap=3.0,
    )
    result.junction_dots.append((cx, branch_y))
    result.symbols_used.add("POTENTIAL_FUSE")
    if ctx.has_indicator_lights:
        result.symbols_used.add("INDICATOR_LIGHTS")

    # Spine continues — fuse is a branch, not on the main vertical path.
    # Spine segment covers entry (y) through branch junction and a bit beyond.
    junction_end = branch_y + 1.0
    result.connections.append(((cx, y), (cx, junction_end)))
    ctx.y = junction_end


def _place_unit_isolator(ctx: _LayoutContext) -> None:
    """Place unit isolator (for ct_meter, landlord supply, or explicitly specified).

    IMPORTANT: When metering is set (meter board is drawn), the meter board
    already includes an internal ISOLATOR symbol. Adding a second unit
    isolator on the vertical cable would duplicate it. Skip in that case.
    Reference: 63A TPN SLD 14 — meter board has 63A 4P ISOLATOR inside,
    no extra isolator on the cable to DB box.
    """
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_source = ctx.supply_source
    breaker_rating = ctx.breaker_rating
    meter_poles = ctx.meter_poles
    metering = ctx.metering
    requirements = ctx.requirements

    # -- 3. Unit Isolator (for ct_meter, landlord supply, or explicitly specified) --
    # When meter board is drawn (metering is set), it already contains an
    # internal ISOLATOR. Do NOT add a second one on the cable above.
    if metering:
        ctx.y = y
        return

    _iso_w = config.isolator_w  # Isolator symbol width — needed for centering

    # Flatten nested isolator dict → top-level keys (same as _parse_board_requirements)
    _iso_dict = requirements.get("isolator", {})
    if isinstance(_iso_dict, dict) and _iso_dict:
        if "isolator_rating" not in requirements:
            requirements["isolator_rating"] = _iso_dict.get("rating", 0)
        if "isolator_label" not in requirements:
            requirements["isolator_label"] = _iso_dict.get("location_text", "")

    isolator_rating = requirements.get("isolator_rating", 0)
    isolator_label_extra = requirements.get("isolator_label", "")

    # Landlord / building riser: ALWAYS needs unit isolator (disconnect switch)
    # Exception: cable extension SLDs skip the isolator
    if supply_source in ("landlord", "building_riser") and not ctx.is_cable_extension:
        if not isolator_rating and breaker_rating:
            isolator_rating = breaker_rating  # Same rating as main breaker
        if not isolator_label_extra:
            # Unit number is shown on the DB box label, not on the isolator
            isolator_label_extra = SG_LOCALE.meter_board.located_inside_unit
    elif not isolator_rating and supply_source not in ("landlord", "building_riser"):
        # Other supply sources (sp_powergrid, etc.): unit isolator sized to
        # next standard rating.  Note: metering is temporarily cleared by
        # engine.py before calling this function for ct_meter, so we check
        # supply_source instead of metering flag.
        if breaker_rating:
            isolator_rating = _next_standard_rating(breaker_rating)

    if isolator_rating:
        result.sections_rendered["unit_isolator"] = True
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        iso_main_label = f"{isolator_rating}A {meter_poles} {SG_LOCALE.meter_board.isolator}"
        iso_rating_text = (
            f"({isolator_label_extra})" if isolator_label_extra else SG_LOCALE.meter_board.isolator
        )

        if supply_source in ("landlord", "building_riser"):
            # Landlord / building riser — enclosed isolator with labels to the LEFT
            result.components.append(PlacedComponent(
                symbol_name="ISOLATOR",
                x=cx - _iso_w / 2,
                y=y,
                label="",   # labels placed separately to the left
                rating="",
                enclosed=True,  # IEC enclosed isolator (standalone unit with housing)
            ))
            # Position label text right-aligned to the left of enclosure box
            _label_ch = 2.3
            _char_w = _label_ch * 0.6
            _combined = f"{iso_main_label}\\P{iso_rating_text}"
            _lines = _combined.split("\\P")
            _longest = max(len(ln) for ln in _lines)
            _text_w = _longest * _char_w
            # Gap measured from enclosure box outer edge (pad=1.5 same as RealIsolator.draw)
            _enclosure_pad = 1.5
            _text_x = cx - _iso_w / 2 - _enclosure_pad - config.isolator_label_gap - _text_w
            _text_y = y + config.isolator_h * 0.55
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=_text_x,
                y=_text_y,
                label=_combined,
            ))
        else:
            # Default: labels to the RIGHT (via symbol's built-in rendering)
            result.components.append(PlacedComponent(
                symbol_name="ISOLATOR",
                x=cx - _iso_w / 2,
                y=y,
                label=iso_main_label,
                rating=iso_rating_text,
            ))

        y += config.isolator_h + 2
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        result.symbols_used.add("ISOLATOR")

        # Outgoing cable annotation with tick mark (after isolator → DB)
        outgoing_cable = requirements.get("outgoing_cable", "")
        out_cable_text = format_cable_spec(outgoing_cable, multiline=True) if outgoing_cable else ""
        if out_cable_text:
            # Position tick between isolator enclosure box top and DB box bottom.
            # Place tick between isolator box top and DB box bottom.
            # Position at 2/3 from isolator (closer to DB box / higher up)
            # to leave vertical space for the location text below the DB box.
            _enclosure_pad = 1.5  # same as RealIsolator.draw()
            _db_info_height = config.db_info_height("x")  # 1 info line estimate
            _iso_box_top = y - (4 - _enclosure_pad)
            _db_box_bottom = y + config.isolator_to_db_gap - 1 - _db_info_height
            tick_y = _iso_box_top + (_db_box_bottom - _iso_box_top) * 0.67
            tick_size = 1.25
            result.thick_connections.append((
                (cx - tick_size, tick_y - tick_size),
                (cx + tick_size, tick_y + tick_size),
            ))
            _leader_len = config.cable_leader_len
            result.connections.append(((cx, tick_y), (cx + _leader_len, tick_y)))
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=cx + _leader_len + config.cable_leader_text_gap,
                y=tick_y + 1.5,
                label=out_cable_text,
            ))

    ctx.y = y


def _place_main_breaker(ctx: _LayoutContext, *, skip_gap: bool = False) -> None:
    """Place main circuit breaker and set db_box_start_y.

    Args:
        skip_gap: If True, skip the isolator-to-DB gap (already added by caller,
                  e.g. when CT metering section is placed before the breaker).
    """
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    breaker_type = ctx.breaker_type
    breaker_rating = ctx.breaker_rating
    breaker_poles = ctx.breaker_poles
    breaker_fault_kA = ctx.breaker_fault_kA
    main_breaker_char = ctx.main_breaker_char

    result.sections_rendered["main_breaker"] = True
    # -- 4. Main Circuit Breaker --
    # Add gap so outgoing cable annotation (tick mark + text) from isolator/meter board
    # stays OUTSIDE (below) the DB dashed box.
    if not skip_gap:
        _gap = config.isolator_to_db_gap
        result.connections.append(((cx, y), (cx, y + _gap)))
        y += _gap
    ctx.db_box_start_y = y - 1  # Track DB box bottom (below main breaker, above cable annotation)

    if breaker_type == "ACB":
        from app.sld.real_symbols import get_symbol_dimensions as _get_acb_dims
        _acb = _get_acb_dims("ACB")
        cb_w, cb_h = _acb["width_mm"], _acb["height_mm"]
    elif breaker_type == "MCB":
        cb_w, cb_h = config.mcb_w, config.mcb_h
    else:
        cb_w, cb_h = config.breaker_w, config.breaker_h

    cb_symbol = f"CB_{breaker_type}"
    # Singapore SLD format (matching reference DXF MTEXT):
    #   "63A TPN MCB 6kA TYPE B" or "100A TPN MCCB (35kA)"
    # Always lowercase "kA" — verified from DXF original text.
    _ka_suffix = "kA"
    if main_breaker_char:
        main_label = f"{breaker_rating}A {breaker_poles} {breaker_type} {breaker_fault_kA}{_ka_suffix} TYPE {main_breaker_char}"
    else:
        main_label = f"{breaker_rating}A {breaker_poles} {breaker_type} ({breaker_fault_kA}{_ka_suffix})"
    result.components.append(PlacedComponent(
        symbol_name=cb_symbol,
        x=cx - cb_w / 2,
        y=y,
        label=main_label,
    ))
    ctx.main_breaker_arc_center_y = y + cb_h / 2  # between contacts
    y += cb_h + config.stub_len  # height + stub — symbol draws stub beyond height
    # No extra connection gap — symbol stubs of adjacent components overlap for continuity
    result.symbols_used.add(breaker_type)

    ctx.y = y


def _place_elcb(ctx: _LayoutContext) -> None:
    """Place ELCB/RCCB inline between main breaker and busbar (conditional)."""
    if not ctx.elcb_rating:
        return

    ctx.result.sections_rendered["elcb"] = True

    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    elcb_config = ctx.elcb_config
    elcb_rating = ctx.elcb_rating
    elcb_ma = ctx.elcb_ma
    elcb_type_str = ctx.elcb_type_str

    # -- 4a. ELCB/RCCB (inline between Main Breaker and Busbar per LEW guide) --
    elcb_symbol = "CB_RCCB" if elcb_type_str == "RCCB" else "CB_ELCB"
    elcb_w, elcb_h = config.rccb_w, config.rccb_h  # ELCB/RCCB dims (wider due to RCD bar)
    elcb_poles_raw = elcb_config.get("poles", "") if isinstance(elcb_config, dict) else ""
    if isinstance(elcb_poles_raw, str) and elcb_poles_raw.upper() in ("DP", "SP", "TPN", "4P"):
        elcb_poles_str = elcb_poles_raw.upper()
    elif isinstance(elcb_poles_raw, int):
        elcb_poles_str = {1: "SP", 2: "DP", 3: "TPN", 4: "4P"}.get(elcb_poles_raw, "DP")
    else:
        # Default based on supply type: DP for single-phase, 4P for three-phase
        elcb_poles_str = "DP" if supply_type == "single_phase" else "4P"

    result.components.append(PlacedComponent(
        symbol_name=elcb_symbol,
        x=cx - elcb_w / 2,
        y=y,
        label=f"{elcb_rating}A {elcb_poles_str}\\P{elcb_type_str} \\P({elcb_ma}mA)",
    ))
    y += elcb_h + config.stub_len  # height + stub — symbol draws stub beyond height
    # No extra connection gap — symbol stubs of adjacent components overlap for continuity
    result.symbols_used.add(elcb_type_str)

    # Post-ELCB MCB: RCCB+MCB serial structure (e.g., 63A RCCB → 63A MCB Type B)
    post_mcb = ctx.post_elcb_mcb
    if post_mcb:
        mcb_type = post_mcb.get("type", "MCB")
        mcb_symbol = f"CB_{mcb_type}"
        mcb_w, mcb_h = config.mcb_w, config.mcb_h
        mcb_rating = post_mcb.get("rating", 0)
        mcb_poles = post_mcb.get("poles", "TPN")
        mcb_char = post_mcb.get("breaker_characteristic", "")
        mcb_fault = post_mcb.get("fault_kA", 10)

        if mcb_char:
            mcb_label = f"{mcb_rating}A {mcb_poles} Type {mcb_char} {mcb_type} ({mcb_fault}KA)"
        else:
            mcb_label = f"{mcb_rating}A {mcb_poles} {mcb_type} ({mcb_fault}KA)"

        result.components.append(PlacedComponent(
            symbol_name=mcb_symbol,
            x=cx - mcb_w / 2,
            y=y,
            label=mcb_label,
            rating=f"{mcb_rating}A",
            poles=mcb_poles,
            breaker_type_str=mcb_type,
        ))
        y += mcb_h + config.stub_len
        result.symbols_used.add(mcb_type)

    ctx.y = y


def _place_internal_cable(ctx: _LayoutContext) -> None:
    """Place internal cable annotation between MCCB/ELCB and busbar (conditional)."""
    if not ctx.internal_cable:
        return
    ctx.result.sections_rendered["internal_cable"] = True
    result = ctx.result
    cx = ctx.cx
    y = ctx.y
    cable_text = format_cable_spec(ctx.internal_cable, multiline=False)
    # Cable annotation label — placed to the right of the spine, just below busbar.
    # Use y - 2 (closer to busbar) to avoid collision with feeder MCB labels
    # that are placed further down (at connect_y level).
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=cx + 8,
        y=y - 2,
        label=cable_text,
        label_style="cable_annotation",
    ))


def _place_main_busbar(ctx: _LayoutContext) -> None:
    """Place main busbar, DB info box, and busbar rating label."""
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    voltage = ctx.voltage
    kva = ctx.kva
    breaker_rating = ctx.breaker_rating
    elcb_rating = ctx.elcb_rating
    busbar_rating = ctx.busbar_rating
    sub_circuits = ctx.sub_circuits
    application_info = ctx.application_info

    result.sections_rendered["main_busbar"] = True
    # -- 5. Main Busbar --
    num_circuits = max(len(sub_circuits), 1)
    effective_count = min(num_circuits, config.max_circuits_per_row)

    h_spacing = _compute_dynamic_spacing(
        num_circuits, config,
        available_width=ctx.constrained_width,
    )
    desired_width = effective_count * h_spacing + 2 * config.busbar_margin

    # In constrained mode (multi-DB), don't enforce a 140mm minimum
    min_bus_width = 40 if ctx.constrained_width else 140
    bus_width = max(desired_width, min_bus_width)
    bus_start_x = cx - bus_width / 2
    bus_end_x = cx + bus_width / 2

    # ── Region constraint: strict clamping to active_region ──
    # This is the critical fix for multi-DB: each DB's busbar MUST stay
    # within its allocated region to prevent cross-DB overlap.
    if ctx.active_region:
        region = ctx.active_region
        bus_start_x = max(bus_start_x, region.min_x)
        bus_end_x = min(bus_end_x, region.max_x)
        # If busbar is narrower than minimum, center within region
        if bus_end_x - bus_start_x < min_bus_width:
            bus_start_x = max(region.min_x, region.cx - min_bus_width / 2)
            bus_end_x = min(region.max_x, region.cx + min_bus_width / 2)
    else:
        # Page-level clamping (single-DB backward compat)
        if bus_start_x < config.min_x:
            bus_start_x = config.min_x
            bus_end_x = bus_start_x + bus_width
        if bus_end_x > config.max_x:
            bus_end_x = config.max_x
            bus_start_x = bus_end_x - bus_width

    result.busbar_y = y
    result.busbar_start_x = bus_start_x
    result.busbar_end_x = bus_end_x

    # Always use "BUSBAR" label regardless of rating (LEW convention)
    busbar_label = f"{busbar_rating}A {SG_LOCALE.circuit.busbar}"
    result.components.append(PlacedComponent(
        symbol_name="BUSBAR",
        x=bus_start_x,
        y=y,
        label=f"{breaker_rating}A {SG_LOCALE.circuit.db}",
        rating="",
        cable_annotation=f"{bus_end_x:.1f}",  # encode bus_end_x for renderer
    ))
    # -- DB Info: compute text, defer placement to _place_db_box() --
    # Reference format: "APPROVED LOAD: 69.282 kVA" (raw value, space + kVA, no voltage)
    if kva:
        # User-specified kVA: use as-is (e.g., 69.282)
        approved_kva = kva
    elif supply_type == "three_phase":
        approved_kva = round(breaker_rating * voltage * 1.732 / 1000, 1)
    else:
        approved_kva = round(breaker_rating * voltage / 1000, 1)

    # Location text: prefer unit_number for concise label, fallback to address
    unit_number = ""
    if application_info:
        unit_number = str(application_info.get("unit_number", "")).strip()
        # Auto-extract unit number from address if not explicitly provided
        if not unit_number and application_info.get("address"):
            _addr = application_info["address"]
            _m = re.search(r"#\d{2,}-\d{2,}", _addr)
            if _m:
                unit_number = _m.group(0)

    db_info_text = f"{SG_LOCALE.incoming.approved_load}: {approved_kva} kVA"

    # Location text — placed BELOW the DB box (outside), per LEW guide Rule 9
    # For landlord supply, DB is always inside the tenant's unit.
    # Sub-boards (fed_from is set) should NOT show location text — only the root
    # board (MSB) shows it.
    db_location_text = ""
    _current_board = (
        ctx.distribution_boards[ctx.current_db_idx]
        if ctx.distribution_boards and 0 <= ctx.current_db_idx < len(ctx.distribution_boards)
        else {}
    )
    _is_sub_board = bool(_current_board.get("fed_from"))
    if not _is_sub_board:
        if unit_number:
            db_location_text = f"{SG_LOCALE.meter_board.located_inside_unit} {unit_number}"
        elif application_info and application_info.get("address"):
            db_location_text = f"LOCATED AT {application_info['address']}"
        elif ctx.supply_source == "landlord":
            # Landlord supply → DB is inside tenant's unit (no specific unit number)
            db_location_text = SG_LOCALE.meter_board.located_inside_unit

    # Store in ctx — will be placed at DB box bottom-left by _place_db_box()
    ctx.db_info_label = f"{breaker_rating}A {SG_LOCALE.circuit.db}"
    ctx.db_info_text = db_info_text
    ctx.db_location_text = db_location_text

    # Busbar rating label — left-aligned below busbar (per reference DWG)
    busbar_label_x = bus_start_x + 3  # 3mm from busbar left edge
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=busbar_label_x,
        y=y - 3,
        label=busbar_label,
    ))

    # Connection from last spine component (main breaker or ELCB) body top to busbar.
    # y already includes component height + stub_len, so body top = y - stub_len.
    result.connections.append(((cx, y - config.stub_len), (cx, y)))


def _place_sub_circuits_rows(ctx: _LayoutContext) -> float:
    """Place sub-circuit rows branching upward from busbar. Returns busbar_y_row."""
    ctx.result.sections_rendered["sub_circuits"] = True
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    sub_circuits = ctx.sub_circuits

    # Detect phase arrangement before triplet-specific processing.
    # Must run BEFORE padding/ID assignment since those depend on the result.
    # Skip re-detection when caller already set use_triplets=False (e.g. PG boards
    # where each group is single-phase and triplets never apply).
    if ctx.use_triplets:
        ctx.use_triplets = _should_use_triplets(sub_circuits, supply_type)

    # -- 6. Sub-circuits (branching UPWARD) --
    # Auto-pad SPAREs to complete 3-phase triplets (before ID assignment)
    sub_circuits = _pad_spares_for_triplets(sub_circuits, supply_type, use_triplets=ctx.use_triplets)
    ctx.sub_circuits = sub_circuits  # Update context for downstream use

    # Pre-assign circuit IDs (S/P for single-phase, L1P1/L2P1 for 3-phase)
    circuit_ids = _assign_circuit_ids(sub_circuits, supply_type, use_triplets=ctx.use_triplets)

    num_circuits = max(len(sub_circuits), 1)

    bus_start_x = result.busbar_start_x
    bus_end_x = result.busbar_end_x

    # ── Region-aware row splitting ──
    # When constrained to a region (multi-DB or protection group), compute
    # max circuits per row based on available width. The region already
    # represents the usable area (margins were accounted for during allocation).
    #
    # Row-splitting threshold: circuits_per_row × TARGET_SPACING ≤ region_width
    # TARGET_SPACING is the comfortable inter-circuit spacing (12mm).
    # If that doesn't fit, try MIN_SPACING (8mm) before going multi-row.
    TARGET_SPACING = 12.0  # mm — comfortable label spacing
    MIN_SPACING = 8.0      # mm — minimum readable spacing
    effective_max_per_row = config.max_circuits_per_row

    if ctx.active_region or ctx.constrained_width:
        avail = ctx.active_region.width if ctx.active_region else ctx.constrained_width
        # Region already has margins built in — use nearly full width.
        # Only subtract a tiny margin (2mm per side) for busbar end caps.
        usable = avail - 4  # 2mm per side for busbar end caps

        # Can all circuits fit at target spacing?
        if num_circuits * TARGET_SPACING <= usable:
            max_per_row = num_circuits  # Fits in one row at comfortable spacing
        elif num_circuits * MIN_SPACING <= usable:
            max_per_row = num_circuits  # Fits in one row at minimum spacing
        else:
            # Need multi-row: compute max per row at minimum readable spacing
            max_per_row = max(3, int(usable / MIN_SPACING))

        # Use un-rounded value for multi-row decision (triplet rounding can
        # drop a fit: 16 circuits at 8mm fits 160mm, but rounding 16→15
        # triggers multi-row unnecessarily).
        can_fit_single_row = num_circuits <= max_per_row

        # Round down to triplets for 3-phase interleaved (for split sizing only)
        if ctx.use_triplets and supply_type == "three_phase" and max_per_row % 3 != 0:
            max_per_row = max(3, (max_per_row // 3) * 3)
        effective_max_per_row = min(effective_max_per_row, max_per_row)

        # If all circuits fit without triplet rounding, force single row
        if can_fit_single_row:
            effective_max_per_row = max(effective_max_per_row, num_circuits)

        # Balanced split: distribute circuits evenly across rows
        if num_circuits > effective_max_per_row:
            num_rows = (num_circuits + effective_max_per_row - 1) // effective_max_per_row
            balanced = (num_circuits + num_rows - 1) // num_rows
            if ctx.use_triplets and supply_type == "three_phase" and balanced % 3 != 0:
                balanced = ((balanced + 2) // 3) * 3
            effective_max_per_row = max(3, balanced)

    rows = _split_into_rows(sub_circuits, effective_max_per_row)

    # Use region width for spacing computation (more accurate than constrained_width)
    spacing_avail = None
    if ctx.active_region:
        spacing_avail = ctx.active_region.width
    elif ctx.constrained_width:
        spacing_avail = ctx.constrained_width
    h_spacing = _compute_dynamic_spacing(
        min(num_circuits, effective_max_per_row), config,
        available_width=spacing_avail,
    )

    busbar_y_row = y  # Default for single-row case
    cumulative_idx = 0  # Track cumulative circuit index across rows

    for row_idx, row_circuits in enumerate(rows):
        row_count = len(row_circuits)
        if row_idx == 0:
            busbar_y_row = y
            row_bus_start = bus_start_x
            row_bus_end = bus_end_x
        else:
            busbar_y_row = y + (row_idx) * config.row_spacing
            row_bus_width = row_count * h_spacing + 2 * config.busbar_margin
            row_bus_start = cx - row_bus_width / 2
            row_bus_end = cx + row_bus_width / 2

            # Region constraint: clamp secondary busbars to active region
            if ctx.active_region:
                row_bus_start = max(row_bus_start, ctx.active_region.min_x)
                row_bus_end = min(row_bus_end, ctx.active_region.max_x)

            result.components.append(PlacedComponent(
                symbol_name="BUSBAR",
                x=row_bus_start,
                y=busbar_y_row,
                label="",
                rating="",
                cable_annotation=f"{row_bus_end:.1f}",  # encode bus_end_x for renderer
            ))
            result.busbar_start_x = min(result.busbar_start_x, row_bus_start)
            result.busbar_end_x = max(result.busbar_end_x, row_bus_end)

            prev_busbar_y = result.busbar_y_per_row[row_idx - 1]
            if getattr(ctx, 'skip_row_bi_connector', False):
                # Protection groups: plain vertical line between rows (no BI connector)
                result.connections.append(((cx, prev_busbar_y + 2), (cx, busbar_y_row)))
            else:
                # BI Connector between rows (replacing plain vertical line)
                bi_w = 16   # BIConnector symbol width
                bi_h = 10   # BIConnector symbol height
                bi_y = (prev_busbar_y + busbar_y_row) / 2 - bi_h / 2

                result.components.append(PlacedComponent(
                    symbol_name="BI_CONNECTOR",
                    x=cx - bi_w / 2,
                    y=bi_y,
                    label="BI CONN.",
                ))
                result.symbols_used.add("BI_CONNECTOR")

                # Connection lines: prev busbar → BI top, BI bottom → new busbar
                result.connections.append(((cx, prev_busbar_y + 2), (cx, bi_y)))
                result.connections.append(((cx, bi_y + bi_h), (cx, busbar_y_row)))

        sc_bus_start = row_bus_start
        result.busbar_y_per_row.append(busbar_y_row)

        # Store per-row busbar extents for region-aware overlap resolution
        result.busbar_x_per_row[busbar_y_row] = (row_bus_start, row_bus_end)

        _place_sub_circuits_upward(
            result, row_circuits, row_idx, row_count,
            busbar_y_row, sc_bus_start, row_bus_end,
            h_spacing, config, sub_circuits, supply_type, circuit_ids,
            use_triplets=ctx.use_triplets,
            row_start_idx=cumulative_idx,
        )
        cumulative_idx += row_count

    return busbar_y_row


def _emit_db_box_rect_and_labels(
    result: LayoutResult,
    config: LayoutConfig,
    *,
    box_start_y: float,
    box_end_y: float,
    box_left: float,
    box_right: float,
    text_anchor_y: float,
    display_label: str,
    info_text: str,
    location_text: str,
    has_ct_metering: bool = False,
) -> None:
    """Emit dashed DB box rectangle, info text, and location label.

    Shared between single-DB _place_db_box() and multi-DB _place_multi_db_boxes().
    Callers compute box extents and pass them in; this function only draws.
    """
    # Dashed rectangle (4 sides: bottom, top, left, right)
    result.dashed_connections.extend([
        ((box_left, box_start_y), (box_right, box_start_y)),
        ((box_left, box_end_y), (box_right, box_end_y)),
        ((box_left, box_start_y), (box_left, box_end_y)),
        ((box_right, box_start_y), (box_right, box_end_y)),
    ])

    # DB info: board name + approved load inside box bottom-left
    if display_label:
        if has_ct_metering:
            info_y = text_anchor_y + 7  # title at +7, rating at +3 — both above dashed line
        else:
            info_y = text_anchor_y - 1  # Just below separator line (inside info area)
        result.components.append(PlacedComponent(
            symbol_name="DB_INFO_BOX",
            x=box_left + 6,  # 6mm = 3mm box inset + 3mm text inset
            y=info_y,
            label=display_label,
            rating=info_text,
        ))

    # Location text — BELOW the DB box (outside)
    if location_text:
        loc_y = box_start_y - 3 if has_ct_metering else box_start_y - 5
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=box_left + 6,
            y=loc_y,
            label=location_text,
        ))


def _place_db_box(ctx: _LayoutContext, busbar_y_row: float) -> float:
    """Place DB box (dashed rectangle around distribution board). Returns db_box_right."""
    ctx.result.sections_rendered["db_box"] = True
    result = ctx.result
    config = ctx.config
    text_anchor_y = ctx.db_box_start_y

    # DB box vertical extents
    db_box_start_y = text_anchor_y - config.db_info_height(ctx.db_info_text)
    db_box_end_y = (busbar_y_row + config.db_box_busbar_margin
                    + config.mcb_h + config.stub_len
                    + config.db_box_tail_margin + config.db_box_label_margin)

    # DB box horizontal extents
    db_box_left = max(result.busbar_start_x - 10, config.min_x + 2)
    db_box_right = min(result.busbar_end_x + 10, config.max_x - 2)

    # Store for later update by resolve_overlaps
    result.db_box_start_y = db_box_start_y
    result.db_box_end_y = db_box_end_y

    # Store dashed line indices for resolve_overlaps to update
    base_idx = len(result.dashed_connections)

    db_display_label = ctx.board_name if ctx.board_name else ctx.db_info_label
    _emit_db_box_rect_and_labels(
        result, config,
        box_start_y=db_box_start_y, box_end_y=db_box_end_y,
        box_left=db_box_left, box_right=db_box_right,
        text_anchor_y=text_anchor_y,
        display_label=db_display_label,
        info_text=ctx.db_info_text,
        location_text=ctx.db_location_text,
    )

    result.db_box_dashed_indices = [base_idx, base_idx + 1, base_idx + 2, base_idx + 3]

    return db_box_right


def _place_earth_bar(ctx: _LayoutContext, db_box_right: float) -> None:
    """Place earth bar symbol, conductor label, and connections."""
    ctx.result.sections_rendered["earth_bar"] = True
    result = ctx.result
    config = ctx.config
    requirements = ctx.requirements

    # -- 7. Earth Bar (outside DB box, right side) --
    # RealEarth symbol dimensions: width=12mm, height=10mm (from real_symbol_paths.json)
    from app.sld.real_symbols import get_symbol_dimensions
    _earth_dims = get_symbol_dimensions("EARTH")
    _earth_w = _earth_dims["width_mm"]   # 12
    _earth_h = _earth_dims["height_mm"]  # 10

    earth_x = db_box_right + config.earth_x_from_db
    earth_y = result.busbar_y - config.earth_y_below_busbar

    # Earth conductor size annotation (calculate early for boundary check)
    earth_conductor_mm2 = requirements.get("earth_conductor_mm2", 0)
    if not earth_conductor_mm2:
        inc_cable = requirements.get("incoming_cable", {})
        if isinstance(inc_cable, dict):
            inc_size = inc_cable.get("size_mm2", 0)
            # Ensure numeric type (user may pass "16" as string)
            try:
                inc_size = float(inc_size) if inc_size else 0
            except (ValueError, TypeError) as exc:
                logger.debug("Cable size float conversion failed: %r → %s", inc_size, exc)
                inc_size = 0
        elif isinstance(inc_cable, str) and inc_cable:
            # Parse cable size from string like "4 x 50mm² PVC/PVC cable + 50mm² CPC"
            import re
            _m = re.search(r'(\d+(?:\.\d+)?)\s*(?:mm²|sqmm|mm2)', inc_cable)
            try:
                inc_size = float(_m.group(1)) if _m else 0
            except (ValueError, TypeError):
                inc_size = 0
        else:
            inc_size = 0
        if inc_size:
            from app.sld.standards import get_earth_conductor_size
            earth_conductor_mm2 = get_earth_conductor_size(inc_size)

    # -- Boundary check: ensure earth + labels fit within drawing border --
    earth_label_right = earth_x + _earth_w + 3 + 2  # symbol + gap + "E" text width
    _earth_cond = SG_LOCALE.circuit.earth_conductor
    if earth_conductor_mm2:
        conductor_label = f"1 x {earth_conductor_mm2}sqmm {_earth_cond}"
        conductor_label_right = earth_x + len(conductor_label) * config.char_w_label
        earth_rightmost = max(earth_label_right, conductor_label_right)
    else:
        earth_rightmost = earth_label_right

    border_right = config.max_x  # Strict drawing boundary
    if earth_rightmost > border_right - 1:
        # Shift earth left, possibly overlapping into DB box area
        shift = earth_rightmost - (border_right - 1)
        earth_x = earth_x - shift
        # Allow earth bar inside DB box if no space outside
        earth_x = max(earth_x, db_box_right - _earth_w - 2)

    result.components.append(PlacedComponent(
        symbol_name="EARTH",
        x=earth_x,
        y=earth_y,
        label="E",
    ))
    result.symbols_used.add("EARTH")

    if earth_conductor_mm2:
        conductor_label = f"1 x {earth_conductor_mm2}sqmm {_earth_cond}"
        label_width = len(conductor_label) * config.char_w_label
        label_x = earth_x
        # Ensure label doesn't exceed right drawing border
        border_right_abs = config.max_x
        if label_x + label_width > border_right_abs - 2:
            # Try shifting label left (can overlap into DB box area since it's below)
            label_x = border_right_abs - 2 - label_width
            label_x = max(label_x, config.min_x)
            # If still too wide, wrap to 2 lines at "CU/GRN-YEL" boundary
            recalc_width = len(conductor_label) * config.char_w_label
            if label_x + recalc_width > border_right_abs - 2:
                conductor_label = f"1 x {earth_conductor_mm2}sqmm\\P{_earth_cond}"
                label_x = earth_x  # Reset x since text is now shorter per line
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=label_x,
            y=earth_y - 5,
            label=conductor_label,
        ))

    # Solid earth conductor -- from DB box right wall to earth bar (outside DB box)
    earth_cx = earth_x + _earth_w / 2  # Center of earth symbol
    earth_top_pin_y = earth_y + _earth_h  # top pin at y + height
    # Horizontal: DB box right wall → earth bar center X (at earth bar top pin level)
    result.connections.append(((db_box_right, earth_top_pin_y),
                               (earth_cx, earth_top_pin_y)))
    # Junction dot at DB box right wall (connection point indicator)
    result.junction_dots.append((db_box_right, earth_top_pin_y))
