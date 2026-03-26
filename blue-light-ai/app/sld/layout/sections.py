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
        raw_metering = requirements.get("metering", "")
        # Normalize common variants: "ct_metered" → "ct_meter"
        if isinstance(raw_metering, str) and raw_metering.lower().replace("_", "").replace("-", "") in (
            "ctmetered", "ctmeter", "ct",
        ):
            raw_metering = "ct_meter"
        ctx.metering = raw_metering


def _parse_elcb_config(ctx: _LayoutContext, requirements: dict) -> None:
    """ELCB 설정 파싱 (dict/non-dict 처리)."""
    ctx.elcb_config = requirements.get("elcb", {})
    if isinstance(ctx.elcb_config, dict):
        ctx.elcb_rating = ctx.elcb_config.get("rating", 0) or ctx.elcb_config.get("rating_A", 0)
        ctx.elcb_ma = ctx.elcb_config.get("sensitivity_ma", 0) or ctx.elcb_config.get("sensitivity_mA", 0) or 30
    else:
        ctx.elcb_rating = 0
        ctx.elcb_ma = 30
    ctx.elcb_type_str = (
        ctx.elcb_config.get("type", "ELCB").upper()
        if isinstance(ctx.elcb_config, dict) else "ELCB"
    )

    # Post-ELCB MCB (RCCB+MCB serial structure, e.g., 63A RCCB → 63A MCB Type B)
    ctx.post_elcb_mcb = requirements.get("post_elcb_mcb", {})

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
    # Auto-derive ammeter range from CT ratio when not explicitly provided.
    # e.g., CT ratio "100/5A" → ammeter range "0-100A" (primary current).
    _explicit_ammeter = metering_cfg.get("ammeter_range", "")
    ctx.ammeter_range = _explicit_ammeter or _derive_ammeter_range(ctx.ct_ratio)


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

    # Meter board location text (optional, only shown when explicitly provided)
    ctx.meter_board_location_text = requirements.get("meter_board_location_text", "")


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
    # Priority: supply_source locale > user-specified label > cable extension > default
    # Known supply_source types always use locale labels (Gemini-extracted labels
    # may omit the "SUPPLY" prefix — e.g. "FROM BUILDING RISER" instead of
    # "SUPPLY FROM BUILDING RISER").
    if ctx.is_cable_extension:
        supply_label = SG_LOCALE.incoming.from_power_supply
    elif supply_source == "building_riser":
        supply_label = SG_LOCALE.incoming.from_building_riser
    elif supply_source == "landlord":
        if ctx.requirements.get("supply_label_type") == "supply":
            supply_label = SG_LOCALE.incoming.from_landlord_supply
        else:
            supply_label = SG_LOCALE.incoming.from_landlord
    elif ctx.requirements.get("incoming_label"):
        supply_label = ctx.requirements["incoming_label"]
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


# ---------------------------------------------------------------------------
# Meter Board Section — extracted to meter_board.py, re-exported here
# ---------------------------------------------------------------------------
from app.sld.layout.meter_board import (  # noqa: E402
    _MeterBoardGeom,
    _add_incoming_supply_line,
    _add_meter_board_box_and_earth,
    _add_outgoing_cable_tick,
    _compute_meter_board_geom,
    _place_meter_board,
    _place_meter_board_symbols,
)


# (old meter board functions removed — now in meter_board.py)


# ---------------------------------------------------------------------------
# CT Metering Section — extracted to ct_metering.py, re-exported here
# ---------------------------------------------------------------------------
from app.sld.layout.ct_metering import (  # noqa: E402
    CT_METERING_SPINE_ORDER,
    _derive_ammeter_range,
    _place_bi_crossbar_circuits,
    _place_ct_metering_section,
    _place_metering_branch,
)


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
        gap=6.0,
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

    from app.sld.catalog import get_catalog
    _cat = get_catalog()
    _iso_def = _cat.get("ISOLATOR")
    _iso_w = _iso_def.width  # Isolator symbol width — needed for centering

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
            # Include unit number from application_info if available
            # Reference: "LOCATED INSIDE UNIT #05-26"
            _unit_no = ""
            if ctx.application_info:
                _unit_no = str(ctx.application_info.get("unit_number", "")).strip()
                if not _unit_no and ctx.application_info.get("address"):
                    import re as _re
                    _m = _re.search(r"#\d{2,}-\d{2,}", ctx.application_info["address"])
                    if _m:
                        _unit_no = _m.group(0)
            if _unit_no:
                isolator_label_extra = f"{SG_LOCALE.meter_board.located_inside_unit} {_unit_no}"
            else:
                isolator_label_extra = SG_LOCALE.meter_board.located_inside_unit
    elif not isolator_rating and supply_source not in ("landlord", "building_riser"):
        # Other supply sources (sp_powergrid, etc.): unit isolator sized to
        # next standard rating.  Note: metering is temporarily cleared by
        # engine.py before calling this function for ct_meter, so we check
        # supply_source instead of metering flag.
        if breaker_rating:
            isolator_rating = _next_standard_rating(breaker_rating)

    if isolator_rating:
        from app.sld.layout.section_base import FunctionSection

        result.sections_rendered["unit_isolator"] = True
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        iso_main_label = f"{isolator_rating}A {meter_poles} {SG_LOCALE.meter_board.isolator}"
        iso_rating_text = (
            f"({isolator_label_extra})" if isolator_label_extra else SG_LOCALE.meter_board.isolator
        )

        if supply_source in ("landlord", "building_riser"):
            # Landlord / building riser — enclosed isolator with labels to the LEFT
            # Manual placement (enclosed + separate label = can't use place_on_spine)
            result.components.append(PlacedComponent(
                symbol_name="ISOLATOR",
                x=cx - _iso_def.center_x(),
                y=y,
                label="",
                rating="",
                enclosed=True,
            ))
            # Position label text right-aligned to the left of enclosure box
            _label_ch = 2.3
            _char_w = _label_ch * 0.6
            _combined = f"{iso_main_label}\\P{iso_rating_text}"
            _lines = _combined.split("\\P")
            _longest = max(len(ln) for ln in _lines)
            _text_w = _longest * _char_w
            _enclosure_pad = 1.5
            _text_x = cx - _iso_def.center_x() - _enclosure_pad - config.isolator_label_gap - _text_w
            _text_y = y + _iso_def.height * 0.55
            result.components.append(PlacedComponent(
                symbol_name="LABEL", x=_text_x, y=_text_y, label=_combined,
            ))
            # Advance cursor past isolator body + gap
            y += _iso_def.height + 2
        else:
            # Default: place_on_spine handles centering + cursor advance
            ctx.y = y
            FunctionSection.place_on_spine(
                ctx, "ISOLATOR", label=iso_main_label, rating=iso_rating_text,
            )
            y = ctx.y
            # place_on_spine advances past stub; add extra gap
            result.connections.append(((cx, y), (cx, y + 2)))
            y += 2
            # Skip the old y += height + 2 + connection + 2 pattern
            result.symbols_used.add("ISOLATOR")

        if supply_source in ("landlord", "building_riser"):
            result.connections.append(((cx, y), (cx, y + 2)))
            y += 2
            result.symbols_used.add("ISOLATOR")

        # Outgoing cable annotation with tick mark (after isolator → DB)
        outgoing_cable = requirements.get("outgoing_cable", "")
        out_cable_text = format_cable_spec(outgoing_cable, multiline=True) if outgoing_cable else ""
        if out_cable_text:
            # Position tick between isolator enclosure box top and DB box bottom.
            # Must be clearly above the isolator enclosure (min 3mm clearance).
            _enclosure_pad = 1.5  # same as RealIsolator.draw()
            _iso_box_top = y - (4 - _enclosure_pad)
            _db_box_bottom = y + config.isolator_to_db_gap - 1
            # Place tick at midpoint between isolator top and DB box bottom,
            # but never closer than 3mm above isolator enclosure.
            _mid = (_iso_box_top + _db_box_bottom) / 2
            tick_y = max(_mid, _iso_box_top + 3.0)
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

    cb_symbol = f"CB_{breaker_type}"
    # Singapore SLD format (matching reference DXF MTEXT):
    #   "63A TPN MCB 6KA TYPE B" or "100A TPN MCCB (35KA)"
    _ka_suffix = "KA"
    if main_breaker_char:
        main_label = f"{breaker_rating}A {breaker_poles} {breaker_type} {breaker_fault_kA}{_ka_suffix} TYPE {main_breaker_char}"
    else:
        main_label = f"{breaker_rating}A {breaker_poles} {breaker_type} ({breaker_fault_kA}{_ka_suffix})"

    # Place via place_on_spine — catalog pins handle centering + cursor advance
    ctx.y = y  # sync cursor before helper call
    from app.sld.layout.section_base import FunctionSection, _comp_def
    comp_y, _, exit_y = FunctionSection.place_on_spine(ctx, cb_symbol, label=main_label)

    # Side-effects that depend on component geometry
    _cb_def = _comp_def(cb_symbol)
    ctx.main_breaker_arc_center_y = comp_y + _cb_def.height / 2  # between contacts
    result.symbols_used.add(breaker_type)

    # Extra gap with connection line for visual spacing
    # Use reference-matched breaker→RCCB gap for direct metering only (Phase 3).
    # CT metering path has CT sections between breaker and RCCB, so the ref gap
    # (which measures the total distance) would over-allocate space.
    is_ct = ctx.metering in ("ct_metering", "ct_meter")
    gap = (config.ref_breaker_to_rccb_gap or config.spine_component_gap) if not is_ct else config.spine_component_gap
    FunctionSection.spine_connection(ctx, gap)

    y = ctx.y  # sync back


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
    from app.sld.layout.section_base import FunctionSection

    elcb_symbol = "CB_RCCB" if elcb_type_str == "RCCB" else "CB_ELCB"
    elcb_poles_raw = elcb_config.get("poles", "") if isinstance(elcb_config, dict) else ""
    if isinstance(elcb_poles_raw, str) and elcb_poles_raw.upper() in ("DP", "SP", "TPN", "4P"):
        elcb_poles_str = elcb_poles_raw.upper()
    elif isinstance(elcb_poles_raw, int):
        elcb_poles_str = {1: "SP", 2: "DP", 3: "TPN", 4: "4P"}.get(elcb_poles_raw, "DP")
    else:
        elcb_poles_str = "DP" if supply_type == "single_phase" else "4P"

    elcb_label = f"{elcb_rating}A {elcb_poles_str}\\P{elcb_type_str} \\P({elcb_ma}mA)"

    # CT metering reference order (bottom→top): MCB → RCCB → busbar
    # Direct metering reference order (bottom→top): RCCB → MCB → busbar
    is_ct = ctx.metering in ("ct_metering", "ct_meter")
    post_mcb = ctx.post_elcb_mcb

    if is_ct and post_mcb:
        # CT path: place MCB first (closer to BI connector), then RCCB
        mcb_type = post_mcb.get("type", "MCB")
        mcb_symbol = f"CB_{mcb_type}"
        mcb_rating = post_mcb.get("rating", 0)
        mcb_poles = post_mcb.get("poles", "TPN")
        mcb_char = post_mcb.get("breaker_characteristic", "")
        mcb_fault = post_mcb.get("fault_kA", 10)

        # Stacked label: "63A\PTPN\PTYPE B\PMCB" (no kA, per reference DWG)
        _mcb_parts = [f"{mcb_rating}A"]
        if mcb_poles:
            _mcb_parts.append(mcb_poles)
        if mcb_char:
            _mcb_parts.append(f"TYPE {mcb_char}")
        _mcb_parts.append(mcb_type)
        mcb_label = "\\P".join(_mcb_parts)

        ctx.y = y
        FunctionSection.place_on_spine(
            ctx, mcb_symbol, label=mcb_label,
            label_side="left",
            no_trip_arrow=True,  # CT spine: no trip arrow per reference DWG
        )
        result.symbols_used.add(mcb_type)
        FunctionSection.spine_connection(ctx, config.spine_component_gap)

        # RCCB stacked label: "63A 4P\PRCCB\P(30mA)" (per reference DWG)
        _rccb_parts = [f"{elcb_rating}A {elcb_poles_str}"]
        _rccb_parts.append(elcb_type_str)
        if elcb_ma:
            _rccb_parts.append(f"({elcb_ma}mA)")
        _rccb_label = "\\P".join(_rccb_parts)
        FunctionSection.place_on_spine(ctx, elcb_symbol, label=_rccb_label,
                                       label_side="left")
        result.symbols_used.add(elcb_type_str)
        FunctionSection.spine_connection(ctx, config.spine_component_gap)
    else:
        # Direct metering path: RCCB first, then optional MCB
        ctx.y = y
        FunctionSection.place_on_spine(ctx, elcb_symbol, label=elcb_label)
        result.symbols_used.add(elcb_type_str)

        # Use reference-matched RCCB→busbar gap for direct metering only (Phase 3).
        rccb_gap = config.ref_rccb_to_busbar_gap or config.spine_component_gap
        FunctionSection.spine_connection(ctx, rccb_gap)

        if post_mcb:
            mcb_type = post_mcb.get("type", "MCB")
            mcb_symbol = f"CB_{mcb_type}"
            mcb_rating = post_mcb.get("rating", 0)
            mcb_poles = post_mcb.get("poles", "TPN")
            mcb_char = post_mcb.get("breaker_characteristic", "")
            mcb_fault = post_mcb.get("fault_kA", 10)

            if mcb_char:
                mcb_label = f"{mcb_rating}A {mcb_poles} Type {mcb_char} {mcb_type} ({mcb_fault}KA)"
            else:
                mcb_label = f"{mcb_rating}A {mcb_poles} {mcb_type} ({mcb_fault}KA)"

            FunctionSection.place_on_spine(
                ctx, mcb_symbol, label=mcb_label,
                rating=f"{mcb_rating}A", poles=mcb_poles, breaker_type_str=mcb_type,
            )
            result.symbols_used.add(mcb_type)
            FunctionSection.spine_connection(ctx, config.spine_component_gap)

    y = ctx.y


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

    # Store full busbar extent before ratio adjustment (for crossbar reference)
    result.busbar_full_end_x = bus_end_x

    # ── BI crossbar circuit clearance ──
    # When CT metering is active, SPARE + feeder circuits branch upward from
    # the BI Connector crossbar.  Their vertical lines must NOT pass through
    # the busbar.  Shrink the VISUAL busbar right edge only; circuit tap
    # positions still use the full bus_end_x so sub-circuit spacing is not
    # compressed.
    _xbar_ckts = getattr(ctx, "bi_crossbar_circuits", [])
    _visual_bus_end_x = bus_end_x  # default: same as logical
    if _xbar_ckts:
        _xbar_spacing = 15.0  # must match spacing used in _place_bi_crossbar_circuits
        _xbar_clearance = len(_xbar_ckts) * _xbar_spacing + _xbar_spacing / 2
        _visual_bus_end_x = bus_end_x - _xbar_clearance

    # Apply busbar width ratio (shrink from right side, left edge fixed)
    if config.busbar_width_ratio < 1.0:
        full_width = bus_end_x - bus_start_x
        bus_end_x = bus_start_x + full_width * config.busbar_width_ratio
        _visual_bus_end_x = min(_visual_bus_end_x, bus_end_x)

    result.busbar_y = y
    result.busbar_start_x = bus_start_x
    result.busbar_end_x = bus_end_x
    result.busbar_visual_end_x = _visual_bus_end_x

    # Busbar label: requirements can override with busbar_label_type
    # Default "BUSBAR" (matches majority of real LEW DWGs).
    # Legacy "COMB BAR" available via busbar_label_type="COMB BAR".
    _custom_type = ctx.requirements.get("busbar_label_type", "")
    if _custom_type:
        busbar_type = _custom_type
    else:
        busbar_type = SG_LOCALE.circuit.busbar  # "BUSBAR"
    busbar_label = f"{busbar_rating}A {busbar_type}"
    result.components.append(PlacedComponent(
        symbol_name="BUSBAR",
        x=bus_start_x,
        y=y,
        label=f"{breaker_rating}A {SG_LOCALE.circuit.db}",
        rating="",
        cable_annotation=f"{_visual_bus_end_x:.1f}",  # encode visual bus_end_x for renderer
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

    # Reference format: "APPROVED LOAD: 69.282 KVA AT 400V"
    db_info_text = f"{SG_LOCALE.incoming.approved_load}: {approved_kva} KVA AT {voltage}V"

    # Location text — placed BELOW the DB box (outside), per LEW guide Rule 9
    # For landlord supply, DB is always inside the tenant's unit.
    # Location text — ALL boards (root and sub-boards) show location.
    # Reference: I2R multi-DB has "(LOCATED INSIDE UNIT #05-26)" below both MSB and DB2.
    db_location_text = ""
    if unit_number:
        # Reference format: "(LOCATED INSIDE UNIT #01-36)"
        db_location_text = f"({SG_LOCALE.meter_board.located_inside_unit} {unit_number})"
    elif application_info and application_info.get("address"):
        db_location_text = f"(LOCATED AT {application_info['address']})"
    elif ctx.supply_source == "landlord":
        # Landlord supply → DB is inside tenant's unit (no specific unit number)
        db_location_text = f"({SG_LOCALE.meter_board.located_inside_unit})"

    # Store in ctx — will be placed at DB box bottom-left by _place_db_box()
    # DB info label: "{rating}A DB" — poles (TPN/SPN) not shown in DB name per LEW convention
    # If board_name is provided, use it; otherwise generate from rating
    if ctx.board_name:
        # Remove poles from board_name if present (e.g., "63A TPN DB" → "63A DB")
        _db_name_clean = re.sub(r'\s+(TPN|SPN|DP|4P)\s+', ' ', ctx.board_name).strip()
        ctx.db_info_label = _db_name_clean
    else:
        ctx.db_info_label = f"{breaker_rating}A {SG_LOCALE.circuit.db}"
    ctx.db_info_text = db_info_text
    ctx.db_location_text = db_location_text

    # Busbar rating label — left-aligned below busbar (per reference DWG)
    # Suppress label when board has per-group RCCBs: sub-busbars carry the label instead.
    if not getattr(ctx, '_suppress_busbar_label', False):
        busbar_label_x = bus_start_x + 3  # 3mm from busbar left edge
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=busbar_label_x,
            y=y - 3,
            label=busbar_label,
        ))

    # Note: Connection from last spine component to busbar is already handled by
    # place_on_spine() stub drawing — no manual connection needed here.


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
    # Use visual busbar end when available (shortened for BI crossbar clearance)
    # so sub-circuits sit directly above the drawn busbar line.
    bus_end_x = result.busbar_visual_end_x if result.busbar_visual_end_x else result.busbar_end_x
    _visual_bus_width = bus_end_x - bus_start_x  # effective width for circuit placement
    _busbar_shortened = (result.busbar_visual_end_x
                         and result.busbar_end_x
                         and result.busbar_visual_end_x < result.busbar_end_x - 1)

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
        # When BI crossbar clearance shortened the busbar, use the visual width
        if _busbar_shortened and _visual_bus_width < avail:
            avail = _visual_bus_width
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

    # Use visual busbar width for spacing computation when BI crossbar clearance
    # actually shortened the busbar; otherwise fall back to region width.
    spacing_avail = None
    if _busbar_shortened and _visual_bus_width > 0:
        spacing_avail = _visual_bus_width
    elif ctx.active_region:
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
    from app.sld.catalog import get_catalog as _gc
    _mcb_def = _gc().get("MCB")
    db_box_end_y = (busbar_y_row + config.db_box_busbar_margin
                    + _mcb_def.height + _mcb_def.stub
                    + config.db_box_tail_margin + config.db_box_label_margin)

    # DB box horizontal extents
    # Reserve space to the right for earth bar (symbol + gap + label)
    from app.sld.real_symbols import get_symbol_dimensions as _gsd
    _earth_w = _gsd("EARTH")["width_mm"]
    earth_reserve = config.earth_x_from_db + _earth_w + 5  # gap + symbol + E label
    db_box_left = max(result.busbar_start_x - 10, config.min_x + 2)
    db_box_right = min(result.busbar_end_x + 10, config.max_x - earth_reserve)

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

    # Position earth bar BELOW the DB box, slightly left of the right edge.
    # Connection is a straight vertical line from DB box bottom to earth top pin.
    db_box_bottom = getattr(result, "db_box_start_y", None)
    earth_drop = 3.0  # mm below DB box bottom to earth top pin
    # Place earth bar at ~90% along the DB box bottom (right-biased)
    db_box_left = max(result.busbar_start_x - 10, config.min_x + 2)
    db_box_width = db_box_right - db_box_left
    earth_cx = db_box_left + db_box_width * 0.95  # 95% position
    earth_x = earth_cx - _earth_w / 2
    if db_box_bottom is not None:
        earth_y = db_box_bottom - earth_drop - _earth_h
    else:
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
        # Shift earth left but NEVER into the DB box
        shift_amt = earth_rightmost - (border_right - 1)
        earth_x = earth_x - shift_amt
        # Earth bar is now below the DB box (not beside it), so x inside
        # the DB box horizontal range is fine — the vertical position keeps
        # it outside the box.

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

    # Solid earth conductor -- straight vertical from DB box bottom to earth bar
    earth_cx = earth_x + _earth_w / 2  # Center of earth symbol
    earth_top_pin_y = earth_y + _earth_h  # top pin at y + height
    db_box_bottom_y = getattr(result, "db_box_start_y", earth_top_pin_y)

    # Single vertical line: DB box bottom → earth bar top pin
    result.connections.append(((earth_cx, db_box_bottom_y),
                               (earth_cx, earth_top_pin_y)))
    # Junction dot at DB box bottom edge (connection point on box border)
    result.junction_dots.append((earth_cx, db_box_bottom_y))
