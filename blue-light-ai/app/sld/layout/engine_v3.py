"""SLD Layout engine v3 — 전문가 CAD 작업 순서 기반 4-step 오케스트레이션.

기존 engine.py의 섹션 함수들을 재사용하되, 배치 순서를 다음과 같이 변경:

  Step A: place_landmarks()     — 골격 (Busbar, DB box, Earth, Incoming entry)
  Step B: place_spine_components() — 채우기 (Main breaker, ELCB, Meter board, CT, Sub-circuits)
  Step C: adjust_spacing()      — 조정 (Overlap resolution, busbar/DB box fitting, centering)
  Step D: place_labels()        — 라벨 (모든 배치 완료 후 마지막에)

현재 v3은 Step B를 기존 순차 배치 방식으로 실행하고,
Step A/C/D를 기존 코드에서 분리하여 실행한다.
향후 각 step을 점진적으로 region 기반으로 전환한다.
"""

from __future__ import annotations

import logging
from typing import Any

from app.sld.layout.models import LayoutConfig, LayoutResult, _LayoutContext
from app.sld.layout.measure import measure_all_sections
from app.sld.layout.allocate import allocate
from app.sld.layout.overlap import (
    _add_cable_leader_lines,
    _add_isolator_device_symbols,
    _add_phase_fanout,
    resolve_overlaps,
)
from app.sld.layout.sections import (
    _parse_requirements,
    _place_db_box,
    _place_earth_bar,
    _place_sub_circuits_rows,
)
from app.sld.page_config import PageConfig

logger = logging.getLogger(__name__)


def compute_layout_v3(
    requirements: dict,
    config: LayoutConfig | None = None,
    application_info: dict | None = None,
    *,
    page_config: PageConfig | None = None,
    skip_validation: bool = False,
) -> LayoutResult:
    """v3 레이아웃 엔진 — 전문가 CAD 작업 순서 기반.

    Measure → Allocate → Place(4-step) 파이프라인.
    단일 DB 전용 (멀티 DB는 기존 engine.py 사용).

    API는 compute_layout()과 동일.
    """
    # -- Config setup (identical to v2) --
    if config is None:
        config = (
            LayoutConfig.from_reference(requirements, page_config)
            if requirements
            else LayoutConfig.from_page_config(page_config) if page_config else LayoutConfig()
        )

    # Apply requirements-level config overrides
    if requirements.get("default_wiring_method"):
        config.default_wiring_method = requirements["default_wiring_method"]
    if requirements.get("busbar_width_ratio"):
        config.busbar_width_ratio = float(requirements["busbar_width_ratio"])
    if requirements.get("db_width_ratios"):
        config.db_width_ratios = requirements["db_width_ratios"]
    if requirements.get("max_circuits_per_row"):
        config.max_circuits_per_row = int(requirements["max_circuits_per_row"])

    # Component scale
    from app.sld.layout.engine import _auto_component_scale, _expand_layout_for_scale
    s = float(requirements.get("component_scale", 0)) or _auto_component_scale(requirements)
    if s != 1.0:
        config.component_scale = s
        _expand_layout_for_scale(config, s)

    # Validation
    if not skip_validation:
        from app.sld.layout.engine import _validate_and_correct
        requirements = _validate_and_correct(requirements)

    # Compact spacing
    has_ct = requirements.get("metering") in ("ct_meter", "ct_metering")
    n_circuits = len(requirements.get("sub_circuits", []))
    if has_ct or n_circuits > 20:
        from app.sld.layout.engine import _apply_compact_spacing
        _apply_compact_spacing(config, [], requirements)

    # ══════════════════════════════════════════════════════════════
    # Phase 1: MEASURE — 그리지 않고 크기만 계산
    # ══════════════════════════════════════════════════════════════
    measures = measure_all_sections(requirements, config)

    # ══════════════════════════════════════════════════════════════
    # Phase 2: ALLOCATE — 전체 공간 배분, Scale 결정
    # ══════════════════════════════════════════════════════════════
    plan = allocate(measures, config)

    logger.info(
        "v3 engine: scale=%.2f, total=%.1fmm, %d sections",
        plan.scale, plan.total_height, len(plan.section_regions),
    )

    # ══════════════════════════════════════════════════════════════
    # Phase 3: PLACE — 전문가 순서 4-step
    # ══════════════════════════════════════════════════════════════
    result = LayoutResult()
    cx = config.start_x
    _start_margin = 15
    y = config.min_y + _start_margin

    ctx = _LayoutContext(
        result=result,
        config=config,
        cx=cx,
        y=y,
        requirements=requirements,
        application_info=application_info or {},
    )
    _parse_requirements(ctx, requirements, application_info)
    ctx.allocation_plan = plan

    # ── Step B: spine components (기존 순서 재사용) ──
    # 현재는 기존 section_registry 순서를 사용.
    # 향후 region 기반으로 전환 시 여기를 변경.
    from app.sld.layout.section_registry import get_section_sequence
    section_sequence = get_section_sequence(requirements)
    for section in section_sequence:
        section.execute(ctx)

    # Adjust label height
    from app.sld.layout.engine import _adjust_label_height_to_fit
    _adjust_label_height_to_fit(config, ctx)

    # Sub-circuits
    busbar_y_row = _place_sub_circuits_rows(ctx)

    # Store spine_x for overlap resolution
    ctx.result.spine_x = cx
    ctx.result.use_triplets = ctx.use_triplets

    # ── Step C: adjust spacing ──
    resolve_overlaps(ctx.result, ctx.config)
    _add_phase_fanout(ctx.result, ctx.config, ctx.supply_type)

    # ── Step D: labels (cable leader lines are label-like) ──
    _add_cable_leader_lines(ctx.result, ctx.config)
    _add_isolator_device_symbols(ctx.result, ctx.config)

    # ── Step A (deferred): landmarks that wrap content ──
    # DB box and earth bar are placed AFTER sub-circuits because they
    # enclose existing content. In future, these will move to Step A
    # when layout uses pre-allocated regions.
    db_box_right = _place_db_box(ctx, busbar_y_row)
    _place_earth_bar(ctx, db_box_right)

    # ── Post-processing ──
    from app.sld.layout.engine import _center_vertically, _detect_overflow
    _center_vertically(ctx.result, ctx.config)
    _detect_overflow(ctx.result, ctx.config)

    # Spine label validation
    from app.sld.layout.overlap import validate_spine_labels
    spine_warnings = validate_spine_labels(ctx.result, ctx.config)
    if spine_warnings:
        for w in spine_warnings:
            logger.warning("Label validation: %s", w)
        if ctx.result.overflow_metrics:
            ctx.result.overflow_metrics.warnings.extend(spine_warnings)

    # Audit
    from app.sld.layout.audit import audit_layout
    ctx.result.audit_report = audit_layout(ctx.result, ctx.config, requirements)

    ctx.result.config = ctx.config
    return ctx.result
