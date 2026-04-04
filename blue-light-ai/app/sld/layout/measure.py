"""Phase 1 (Measure): 각 섹션의 공간 요구사항을 사전 측정.

설계 원칙:
- 컴포넌트를 배치하지 않고 크기만 계산한다.
- 모든 함수는 순수 함수 (no side effects, no ctx mutation).
- 입력: requirements dict + LayoutConfig
- 출력: list[SectionMeasure]
- 섹션 간 의존성은 exports dict로 순차 전달.
"""

from __future__ import annotations

import logging
from typing import Any

from app.sld.catalog import get_catalog
from app.sld.layout.models import LayoutConfig, SectionMeasure

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Section order constant — matches the electrical flow (supply → load)
# ---------------------------------------------------------------------------

SECTION_ORDER: list[str] = [
    "incoming_supply",
    "meter_board",
    "unit_isolator",
    "ct_gap_setup",
    "ct_pre_mccb_fuse",
    "main_breaker",
    "ct_metering",
    "elcb",
    "internal_cable",
    "main_busbar",
    "sub_circuits",
    "db_box",
    "earth_bar",
]


# ---------------------------------------------------------------------------
# Individual measure functions
# ---------------------------------------------------------------------------

def _measure_incoming_supply(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Incoming supply: cable entry + AC symbol + tick marks."""
    metering = req.get("metering")
    supply_source = req.get("supply_source", "sp_powergrid")

    # Skipped when SP meter board handles the supply entry
    if metering and metering != "ct_meter":
        return SectionMeasure(section_id="incoming_supply", height=0, present=False)

    if supply_source in ("landlord", "building_riser"):
        # Landlord/riser: segment_height(14) + cable_clearance(3)
        height = 17.0
    else:
        # SP powergrid / cable extension: phase_half(3) + gap(4)
        height = 7.0

    return SectionMeasure(section_id="incoming_supply", height=height)


def _measure_meter_board(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Meter board: ISO → KWH → MCB horizontal chain + box."""
    metering = req.get("metering")
    if not metering or metering in ("ct_meter", "ct_metering"):
        return SectionMeasure(section_id="meter_board", height=0, present=False)

    cat = get_catalog()
    iso_h = cat.get("ISOLATOR").height
    kwh_h = cat.get("KWH_METER").height
    mcb_h = cat.get("MCB").height

    # Meter board box: components + spacing + box margins
    comp_height = max(iso_h, kwh_h, mcb_h)
    box_padding = config.meter_board_inset * 2
    box_height = comp_height + box_padding + config.meter_board_gap

    # Exit from meter board: box top + outgoing cable annotation space
    # Cable text comes from outgoing_cable, incoming_cable, or auto-determined cable
    has_outgoing_cable = bool(
        req.get("outgoing_cable") or req.get("cable") or req.get("incoming_cable")
        or req.get("cable_size")
    )
    # If metering is set, there's always at least auto-determined cable
    if metering:
        has_outgoing_cable = True
    cable_annotation_space = 16.0 if has_outgoing_cable else 8.0
    total = box_height + cable_annotation_space

    return SectionMeasure(
        section_id="meter_board", height=total,
        exports={"meter_board_height": total},
    )


def _measure_unit_isolator(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Unit isolator: placed when no meter board."""
    metering = req.get("metering")
    supply_source = req.get("supply_source", "sp_powergrid")

    if metering:
        return SectionMeasure(section_id="unit_isolator", height=0, present=False)

    # Only for landlord, building_riser, or explicit SP powergrid
    if supply_source not in ("landlord", "building_riser", "sp_powergrid"):
        return SectionMeasure(section_id="unit_isolator", height=0, present=False)

    cat = get_catalog()
    iso = cat.get("ISOLATOR")
    # gap(2) + isolator body + stub + gap(2)
    height = 2.0 + iso.height + iso.stub + 4.0

    return SectionMeasure(section_id="unit_isolator", height=height)


def _measure_ct_gap_setup(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """CT metering isolator-to-DB gap (vertical connection before MCCB)."""
    metering = req.get("metering")
    if metering not in ("ct_meter", "ct_metering"):
        return SectionMeasure(section_id="ct_gap_setup", height=0, present=False)

    return SectionMeasure(section_id="ct_gap_setup", height=config.isolator_to_db_gap)


def _measure_ct_pre_mccb_fuse(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Pre-MCCB fuse branch for CT metering (horizontal T-junction)."""
    metering = req.get("metering")
    if metering not in ("ct_meter", "ct_metering"):
        return SectionMeasure(section_id="ct_pre_mccb_fuse", height=0, present=False)

    # T-junction branch: branch_y offset(4) + branch_clearance(1)
    height = 5.0

    return SectionMeasure(section_id="ct_pre_mccb_fuse", height=height)


def _measure_main_breaker(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Main breaker (MCCB/MCB/ACB) on spine."""
    breaker_type = req.get("main_breaker", {}).get("type", "MCCB")
    cat = get_catalog()

    if breaker_type in ("MCB", "MCCB", "ACB"):
        cb = cat.get(breaker_type)
    else:
        cb = cat.get("MCCB")

    # Pre-gap (isolator_to_db_gap) + breaker height + stubs + spine gap
    # CT path uses skip_gap=True (gap is provided by _CtGapAndSetup)
    metering = req.get("metering", "")
    skip_gap = metering in ("ct_meter", "ct_metering")
    pre_gap = 0 if skip_gap else config.isolator_to_db_gap
    component_height = cb.height + cb.stub * 2  # top + bottom stubs
    post_gap = config.spine_component_gap

    height = pre_gap + component_height + post_gap

    return SectionMeasure(
        section_id="main_breaker", height=height,
        exports={"arc_center_y_offset": cb.height / 2},
    )


def _measure_ct_metering(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """CT metering section: Protection CT + Metering CT + BI Connector + branches."""
    metering = req.get("metering")
    if metering not in ("ct_meter", "ct_metering"):
        return SectionMeasure(section_id="ct_metering", height=0, present=False)

    cat = get_catalog()
    ct = cat.get("CT")
    bi = cat.get("BI_CONNECTOR")

    # Spine components: 2× CT (protection + metering) + BI connector
    ct_total = (ct.height + ct.stub * 2) * 2
    ct_gap = config.ct_to_ct_gap * 2
    bi_total = bi.height + bi.stub * 2

    # Branch reserves (ASS/ammeter, VSS/voltmeter, ELR branches — horizontal, don't add height
    # but the spine needs clearance)
    branch_reserve = 20.0  # Conservative reserve for branch junctions

    height = ct_total + ct_gap + bi_total + branch_reserve

    return SectionMeasure(
        section_id="ct_metering", height=height,
        exports={"bi_center_offset": bi.height / 2},
    )


def _measure_elcb(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """ELCB/RCCB protection on spine."""
    elcb_config = req.get("elcb", {})
    if not elcb_config or not elcb_config.get("rating"):
        return SectionMeasure(section_id="elcb", height=0, present=False)

    cat = get_catalog()
    rccb = cat.get("RCCB")

    # RCCB + stubs + spine gap
    height = rccb.height + rccb.stub * 2 + config.spine_component_gap

    # Optional post-ELCB MCB (serial protection)
    post_mcb = elcb_config.get("post_elcb_mcb") or req.get("post_elcb_mcb")
    if post_mcb:
        mcb = cat.get("MCB")
        height += mcb.height + mcb.stub * 2 + config.spine_component_gap

    return SectionMeasure(section_id="elcb", height=height)


def _measure_internal_cable(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Internal cable annotation (no vertical advance)."""
    return SectionMeasure(section_id="internal_cable", height=0)


def _measure_main_busbar(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Main busbar (horizontal line, minimal vertical space)."""
    # Busbar itself is a reference line at current Y
    # DB info area below busbar
    height = 2.0  # busbar thickness representation
    return SectionMeasure(section_id="main_busbar", height=height)


def _measure_sub_circuits(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Sub-circuit rows below busbar."""
    sub_circuits = req.get("sub_circuits", [])
    if not sub_circuits:
        # Check distribution_boards for sub-circuits
        dbs = req.get("distribution_boards", [])
        if dbs:
            for db in dbs:
                sub_circuits.extend(db.get("sub_circuits", []))

    num_circuits = len(sub_circuits) if sub_circuits else 6  # minimum estimate
    cat = get_catalog()
    mcb = cat.get("MCB")

    # Per circuit: breaker height + stub + tail + label
    circuit_height = mcb.height + mcb.stub + config.tail_length + 10.0  # label space

    # Number of rows
    max_per_row = config.max_circuits_per_row
    num_rows = max(1, (num_circuits + max_per_row - 1) // max_per_row)

    # Total height = first row height + additional rows × row_spacing
    height = circuit_height + (num_rows - 1) * config.row_spacing

    # Busbar width estimation (used by Allocate phase)
    from app.sld.layout.overlap import _compute_dynamic_spacing
    spacing = _compute_dynamic_spacing(min(num_circuits, max_per_row), config)
    total_width = spacing * min(num_circuits, max_per_row)

    return SectionMeasure(
        section_id="sub_circuits", height=height,
        min_width=total_width,
        exports={"total_circuit_width": total_width, "num_rows": float(num_rows)},
    )


def _measure_db_box(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """DB box enclosure (margins above/below content)."""
    # DB box is drawn around existing components
    # Top margin (above busbar) + bottom margin (above sub-circuits)
    height = config.db_box_busbar_margin + config.db_box_tail_margin

    return SectionMeasure(section_id="db_box", height=height)


def _measure_earth_bar(
    req: dict, config: LayoutConfig, exports: dict[str, float],
) -> SectionMeasure:
    """Earth bar below DB box."""
    cat = get_catalog()
    earth = cat.get("EARTH")

    # Earth drop + symbol height + cable gap
    height = 3.0 + earth.height + 5.0  # drop + symbol + bottom clearance

    return SectionMeasure(section_id="earth_bar", height=height)


# ---------------------------------------------------------------------------
# Registry & orchestrator
# ---------------------------------------------------------------------------

_MEASURE_REGISTRY: dict[str, Any] = {
    "incoming_supply": _measure_incoming_supply,
    "meter_board": _measure_meter_board,
    "unit_isolator": _measure_unit_isolator,
    "ct_gap_setup": _measure_ct_gap_setup,
    "ct_pre_mccb_fuse": _measure_ct_pre_mccb_fuse,
    "main_breaker": _measure_main_breaker,
    "ct_metering": _measure_ct_metering,
    "elcb": _measure_elcb,
    "internal_cable": _measure_internal_cable,
    "main_busbar": _measure_main_busbar,
    "sub_circuits": _measure_sub_circuits,
    "db_box": _measure_db_box,
    "earth_bar": _measure_earth_bar,
}


def measure_all_sections(
    requirements: dict,
    config: LayoutConfig,
) -> list[SectionMeasure]:
    """Phase 1: 모든 섹션의 공간 요구사항을 측정.

    Args:
        requirements: SLD 생성 요청 dict
        config: 레이아웃 설정

    Returns:
        섹션별 SectionMeasure 리스트 (SECTION_ORDER 순서)
    """
    measures: list[SectionMeasure] = []
    accumulated_exports: dict[str, float] = {}

    for section_id in SECTION_ORDER:
        fn = _MEASURE_REGISTRY[section_id]
        m = fn(requirements, config, accumulated_exports)
        measures.append(m)
        accumulated_exports.update(m.exports)

    total_height = sum(m.height for m in measures if m.present)
    logger.info(
        "Phase 1 Measure: %d sections, total_height=%.1fmm, "
        "present=%d, skipped=%d",
        len(measures), total_height,
        sum(1 for m in measures if m.present),
        sum(1 for m in measures if not m.present),
    )
    return measures
