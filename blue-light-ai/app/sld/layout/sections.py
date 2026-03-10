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
    if ctx.supply_source == "landlord":
        ctx.metering = requirements.get("metering", None)
    else:
        ctx.metering = requirements.get("metering", "sp_meter")


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
    ct_config = requirements.get("ct", {})
    if isinstance(ct_config, dict):
        ctx.ct_ratio = ct_config.get("ratio", "")
    elif isinstance(ct_config, str):
        ctx.ct_ratio = ct_config


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

    # For metered supply, _place_meter_board() handles everything.
    # No AC symbol, phase lines, or labels needed at the bottom.
    if metering:
        ctx.y = y
        return

    # --- Non-metered supply (landlord / cable extension) only below ---
    # Priority: user-specified label > cable extension > supply_label_type > default
    if ctx.requirements.get("incoming_label"):
        supply_label = ctx.requirements["incoming_label"]
    elif ctx.is_cable_extension:
        supply_label = SG_LOCALE.incoming.from_power_supply
    elif ctx.requirements.get("supply_label_type") == "supply":
        supply_label = SG_LOCALE.incoming.from_landlord_supply
    else:
        supply_label = SG_LOCALE.incoming.from_landlord
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

    # Incoming cable annotation
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

    # Pin positions
    iso_left_x = cx + _mb_inset
    iso_right_x = iso_cx + iso_h_extent / 2 + _stub
    mcb_left_x = mcb_cx - mcb_h_extent / 2 - _stub
    mcb_right_x = mcb_cx + mcb_h_extent / 2 + _stub

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

    # ISOLATOR (left)
    result.components.append(PlacedComponent(
        symbol_name="ISOLATOR",
        x=g.iso_cx - g.iso_h_extent / 2,
        y=g.mb_center_y,
        label=f"{ctx.breaker_rating}A {ctx.meter_poles}",
        rating=SG_LOCALE.meter_board.isolator,
        rotation=90.0,
    ))
    result.symbols_used.add("ISOLATOR")

    # ISO → KWH wiring
    kwh_left_x = g.kwh_cx - g.kwh_h_extent / 2 - g.stub
    result.connections.append(((g.iso_right_x, g.mb_center_y), (kwh_left_x, g.mb_center_y)))

    # CT (between ISO and KWH) — ct_meter + non-landlord only
    if ctx.metering == "ct_meter" and ctx.supply_source != "landlord":
        ct_mid_x = (g.iso_cx + g.kwh_cx) / 2
        ct_r = ctx.config.ct_size / 2
        ct_label = f"CT {ctx.ct_ratio}" if ctx.ct_ratio else SG_LOCALE.meter_board.ct_by_sp
        result.components.append(PlacedComponent(
            symbol_name="CT", x=ct_mid_x - ct_r, y=g.mb_center_y - ct_r, label=ct_label,
        ))
        result.symbols_used.add("CT")

    # KWH METER (center)
    result.components.append(PlacedComponent(
        symbol_name="KWH_METER", x=g.kwh_cx, y=g.mb_center_y, rotation=90.0,
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

    # KWH → MCB wiring
    kwh_right_x = g.kwh_cx + g.kwh_h_extent / 2 + g.stub
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
        supply_label = SG_LOCALE.incoming.from_landlord
    else:
        supply_label = SG_LOCALE.incoming.incoming_hdb
    result.components.append(PlacedComponent(
        symbol_name="LABEL", x=supply_end_x + 3, y=g.mb_center_y + 3, label=supply_label,
    ))

    # Incoming cable tick + leader line
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
    _text_x = cx - _leader_len - 1 - _text_width
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
    # "LOCATED AT METER COMPARTMENT" below box
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

    _iso_w = 8.0  # Isolator symbol width (from real_symbols) — needed for centering
    isolator_rating = requirements.get("isolator_rating", 0)
    isolator_label_extra = requirements.get("isolator_label", "")

    # Landlord supply: include isolator only when requires_isolator is True (125A+)
    # Exception: cable extension SLDs skip the isolator
    _requires_iso = requirements.get("requires_isolator", False)
    if supply_source == "landlord" and not ctx.is_cable_extension and _requires_iso:
        if not isolator_rating and breaker_rating:
            isolator_rating = breaker_rating  # Same rating as main breaker
        if not isolator_label_extra:
            # Include unit number if available (e.g., "LOCATED INSIDE UNIT #01-36")
            unit_number = ""
            if ctx.application_info:
                unit_number = str(ctx.application_info.get("unit_number", "")).strip()
            base_label = SG_LOCALE.meter_board.located_inside_unit
            isolator_label_extra = f"{base_label} {unit_number}" if unit_number else base_label
    elif not isolator_rating and metering == "ct_meter" and supply_source != "landlord":
        # Non-landlord ct_meter: unit isolator sized to next standard rating
        if breaker_rating:
            isolator_rating = _next_standard_rating(breaker_rating)

    if isolator_rating:
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        iso_main_label = f"{isolator_rating}A {meter_poles} {SG_LOCALE.meter_board.isolator}"
        iso_rating_text = (
            f"({isolator_label_extra})" if isolator_label_extra else SG_LOCALE.meter_board.isolator
        )
        result.components.append(PlacedComponent(
            symbol_name="ISOLATOR",
            x=cx - _iso_w / 2,  # Center horizontally using width (not height!)
            y=y,
            label=iso_main_label,
            rating=iso_rating_text,
        ))
        y += config.isolator_h + 2
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        result.symbols_used.add("ISOLATOR")

    ctx.y = y


def _place_main_breaker(ctx: _LayoutContext) -> None:
    """Place main circuit breaker and set db_box_start_y."""
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    breaker_type = ctx.breaker_type
    breaker_rating = ctx.breaker_rating
    breaker_poles = ctx.breaker_poles
    breaker_fault_kA = ctx.breaker_fault_kA
    main_breaker_char = ctx.main_breaker_char

    # -- 4. Main Circuit Breaker --
    # Add gap so outgoing cable annotation (tick mark + text) from meter board
    # stays OUTSIDE (below) the DB dashed box
    result.connections.append(((cx, y), (cx, y + 10)))
    y += 10
    ctx.db_box_start_y = y - 1  # Track DB box bottom (below main breaker, above cable annotation)

    if breaker_type == "ACB":
        cb_w, cb_h = 16, 22
    elif breaker_type == "MCB":
        cb_w, cb_h = config.mcb_w, config.mcb_h
    else:
        cb_w, cb_h = config.breaker_w, config.breaker_h

    cb_symbol = f"CB_{breaker_type}"
    # Singapore SLD format:
    #   Line 1: "63A DP MCB"  (rating + poles + type)
    #   Line 2: "TYPE B 10kA" (characteristic + fault level)
    main_label = f"{breaker_rating}A {breaker_poles} {breaker_type}"
    if main_breaker_char:
        main_rating = f"TYPE {main_breaker_char} {breaker_fault_kA}kA"
    else:
        main_rating = f"{breaker_fault_kA}kA"
    result.components.append(PlacedComponent(
        symbol_name=cb_symbol,
        x=cx - cb_w / 2,
        y=y,
        label=main_label,
        rating=main_rating,
    ))
    y += cb_h + config.stub_len  # height + stub — continuous connection to next component
    result.connections.append(((cx, y), (cx, y + 3)))
    y += 3
    result.symbols_used.add(breaker_type)

    ctx.y = y


def _place_elcb(ctx: _LayoutContext) -> None:
    """Place ELCB/RCCB inline between main breaker and busbar (conditional)."""
    if not ctx.elcb_rating:
        return

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
        label=f"{elcb_rating}A {elcb_poles_str} {elcb_type_str}",
        rating=f"({elcb_ma}mA)",
    ))
    y += elcb_h + config.stub_len  # height + stub — continuous connection to next component
    result.connections.append(((cx, y), (cx, y + 3)))
    y += 3
    result.symbols_used.add(elcb_type_str)

    ctx.y = y


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

    # -- 5. Main Busbar --
    num_circuits = max(len(sub_circuits), 1)
    effective_count = min(num_circuits, config.max_circuits_per_row)

    h_spacing = _compute_dynamic_spacing(num_circuits, config)
    desired_width = effective_count * h_spacing + 2 * config.busbar_margin

    bus_width = max(desired_width, 140)
    bus_start_x = cx - bus_width / 2
    bus_end_x = cx + bus_width / 2

    if bus_start_x < config.min_x:
        bus_start_x = config.min_x
        bus_end_x = bus_start_x + bus_width
    if bus_end_x > config.max_x:
        bus_end_x = config.max_x
        bus_start_x = bus_end_x - bus_width

    result.busbar_y = y
    result.busbar_start_x = bus_start_x
    result.busbar_end_x = bus_end_x

    busbar_label = (
        f"{busbar_rating}A {SG_LOCALE.circuit.comb_busbar}"
        if busbar_rating <= 500
        else f"{busbar_rating}A {SG_LOCALE.circuit.busbar}"
    )
    result.components.append(PlacedComponent(
        symbol_name="BUSBAR",
        x=bus_start_x,
        y=y,
        label=f"{breaker_rating}A {SG_LOCALE.circuit.db}",
        rating="",
    ))
    # -- DB Info: compute text, defer placement to _place_db_box() --
    if kva:
        approved_kva = kva
    elif supply_type == "three_phase":
        approved_kva = round(breaker_rating * voltage * 1.732 / 1000, 1)
    else:
        approved_kva = round(breaker_rating * voltage / 1000, 1)

    # Location text: prefer unit_number for concise label, fallback to address
    unit_number = ""
    if application_info:
        unit_number = str(application_info.get("unit_number", "")).strip()

    db_info_text = f"{SG_LOCALE.incoming.approved_load}: {approved_kva}KVA AT {voltage}V"

    # Location text — placed BELOW the DB box (outside), per LEW guide Rule 9
    db_location_text = ""
    if unit_number:
        db_location_text = f"({SG_LOCALE.meter_board.located_inside_unit} {unit_number})"
    elif application_info and application_info.get("address"):
        db_location_text = f"(LOCATED AT {application_info['address']})"

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

    # Connection from main breaker to busbar
    result.connections.append(((cx, y - 3), (cx, y)))


def _place_sub_circuits_rows(ctx: _LayoutContext) -> float:
    """Place sub-circuit rows branching upward from busbar. Returns busbar_y_row."""
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    sub_circuits = ctx.sub_circuits

    # -- 6. Sub-circuits (branching UPWARD) --
    # Auto-pad SPAREs to complete 3-phase triplets (before ID assignment)
    sub_circuits = _pad_spares_for_triplets(sub_circuits, supply_type)
    ctx.sub_circuits = sub_circuits  # Update context for downstream use

    # Pre-assign circuit IDs (S/P for single-phase, L1P1/L2P1 for 3-phase)
    circuit_ids = _assign_circuit_ids(sub_circuits, supply_type)

    num_circuits = max(len(sub_circuits), 1)
    h_spacing = _compute_dynamic_spacing(num_circuits, config)

    bus_start_x = result.busbar_start_x
    bus_end_x = result.busbar_end_x

    rows = _split_into_rows(sub_circuits, config.max_circuits_per_row)

    busbar_y_row = y  # Default for single-row case

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

            result.components.append(PlacedComponent(
                symbol_name="BUSBAR",
                x=row_bus_start,
                y=busbar_y_row,
                label="",
                rating="",
            ))
            result.busbar_start_x = min(result.busbar_start_x, row_bus_start)
            result.busbar_end_x = max(result.busbar_end_x, row_bus_end)

            # BI Connector between rows (replacing plain vertical line)
            bi_w = 16   # BIConnector symbol width
            bi_h = 10   # BIConnector symbol height
            prev_busbar_y = result.busbar_y_per_row[row_idx - 1]
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

        _place_sub_circuits_upward(
            result, row_circuits, row_idx, row_count,
            busbar_y_row, sc_bus_start, row_bus_end,
            h_spacing, config, sub_circuits, supply_type, circuit_ids,
        )

    return busbar_y_row


def _place_db_box(ctx: _LayoutContext, busbar_y_row: float) -> float:
    """Place DB box (dashed rectangle around distribution board). Returns db_box_right."""
    result = ctx.result
    config = ctx.config
    # Original DB box bottom (at main breaker level)
    text_anchor_y = ctx.db_box_start_y

    # Expand DB box bottom to accommodate DB info text below the main breaker.
    # Text layout (top→bottom): "40A DB" (char 3.0→~4mm) + gap(1) + info lines (char 1.8→~3mm each)
    db_info_lines = ctx.db_info_text.count("\\P") + 1 if ctx.db_info_text else 0
    db_info_height = 5 + db_info_lines * 3  # title(5mm) + each info line(3mm)
    db_box_start_y = text_anchor_y - db_info_height

    # -- 6a. DB Box (DASHED rectangle around distribution board per reference DWG) --
    # Encompasses: main breaker, ELCB/RCCB, busbar, and all sub-circuit breakers
    db_box_end_y = (busbar_y_row + config.db_box_busbar_margin
                    + config.mcb_h + config.stub_len
                    + config.db_box_tail_margin + config.db_box_label_margin)
    db_box_left = result.busbar_start_x - 10   # Extra margin for leftmost circuit labels
    db_box_right = result.busbar_end_x + 10    # Extra margin for rightmost circuit labels
    # Clamp to drawing bounds
    db_box_left = max(db_box_left, config.min_x + 2)
    db_box_right = min(db_box_right, config.max_x - 2)

    # Store DB box y-range for later update by resolve_overlaps
    result.db_box_start_y = db_box_start_y
    result.db_box_end_y = db_box_end_y

    # DB Box — DASHED rectangle (matching reference DWG CENTER linetype)
    # Four sides of dashed rectangle — store indices for later update
    base_idx = len(result.dashed_connections)
    result.dashed_connections.append(((db_box_left, db_box_start_y), (db_box_right, db_box_start_y)))
    result.dashed_connections.append(((db_box_left, db_box_end_y), (db_box_right, db_box_end_y)))
    result.dashed_connections.append(((db_box_left, db_box_start_y), (db_box_left, db_box_end_y)))
    result.dashed_connections.append(((db_box_right, db_box_start_y), (db_box_right, db_box_end_y)))
    result.db_box_dashed_indices = [base_idx, base_idx + 1, base_idx + 2, base_idx + 3]

    # "40A DB" + "APPROVED LOAD" labels — bottom-left inside DB box (per reference DWG)
    # Text anchored to ORIGINAL main breaker position so it stays above the expanded box bottom
    if ctx.db_info_label:
        result.components.append(PlacedComponent(
            symbol_name="DB_INFO_BOX",
            x=db_box_left + 3,
            y=text_anchor_y + 8,  # Fixed to original position, not expanded box bottom
            label=ctx.db_info_label,
            rating=ctx.db_info_text,
        ))

    # Unit location text — BELOW the DB box (outside), per LEW guide Rule 9
    if ctx.db_location_text:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=db_box_left + 6,
            y=db_box_start_y - 3,  # 3mm below DB box bottom edge
            label=ctx.db_location_text,
        ))

    return db_box_right


def _place_earth_bar(ctx: _LayoutContext, db_box_right: float) -> None:
    """Place earth bar symbol, conductor label, and connections."""
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
        else:
            inc_size = 0
        if inc_size:
            from app.sld.standards import get_earth_conductor_size
            earth_conductor_mm2 = get_earth_conductor_size(inc_size)

    # -- Boundary check: ensure earth + labels fit within drawing border --
    _CHAR_W = 1.8  # Approximate character width in mm at char_height 2.3
    earth_label_right = earth_x + _earth_w + 3 + 2  # symbol + gap + "E" text width
    _earth_cond = SG_LOCALE.circuit.earth_conductor
    if earth_conductor_mm2:
        conductor_label = f"1 x {earth_conductor_mm2}sqmm {_earth_cond}"
        conductor_label_right = earth_x + len(conductor_label) * _CHAR_W
        earth_rightmost = max(earth_label_right, conductor_label_right)
    else:
        earth_rightmost = earth_label_right

    border_right = config.max_x + 10  # Drawing border at A3 width - margin (~410mm)
    if earth_rightmost > border_right - 3:
        shift = earth_rightmost - (border_right - 3)
        earth_x = earth_x - shift
        # Maintain minimum 3mm gap from DB box
        earth_x = max(earth_x, db_box_right + config.earth_x_from_db - 2)

    result.components.append(PlacedComponent(
        symbol_name="EARTH",
        x=earth_x,
        y=earth_y,
        label="E",
    ))
    result.symbols_used.add("EARTH")

    if earth_conductor_mm2:
        conductor_label = f"1 x {earth_conductor_mm2}sqmm {_earth_cond}"
        _CHAR_W_LABEL = 1.8  # Approximate char width at char_height 2.8
        label_width = len(conductor_label) * _CHAR_W_LABEL
        label_x = earth_x
        # Ensure label doesn't exceed right drawing border (A3: 420 - 10mm margin)
        border_right_abs = config.max_x + 15  # 395 + 15 = 410 for A3
        if label_x + label_width > border_right_abs - 2:
            # Try shifting label left (can overlap into DB box area since it's below)
            label_x = border_right_abs - 2 - label_width
            label_x = max(label_x, config.min_x)
            # If still too wide, wrap to 2 lines at "CU/GRN-YEL" boundary
            recalc_width = len(conductor_label) * _CHAR_W_LABEL
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
