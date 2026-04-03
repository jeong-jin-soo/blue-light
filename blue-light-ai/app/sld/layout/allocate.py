"""Phase 2 (Allocate): 전체 공간을 배분하고 Scale을 결정.

설계 원칙:
- Phase 1 (Measure) 결과 + 페이지 크기를 기반으로 결정
- 각 섹션에 절대 Y 영역(SectionRegionV2)을 할당
- Busbar 폭과 서킷 행 분배를 사전 결정
- Scale factor를 렌더링 전에 확정

CAD 전문가의 2번째 단계에 해당:
  "적합한 템플릿/레이아웃 스타일을 선택한다"
"""

from __future__ import annotations

import logging
import math
from typing import Any

from app.sld.layout.models import (
    AllocationPlan,
    LayoutConfig,
    SectionMeasure,
    SectionRegionV2,
)

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Page specification
# ---------------------------------------------------------------------------

class PageSpec:
    """Drawing area specification (excludes margins and title block)."""

    def __init__(
        self,
        *,
        drawing_y_start: float = 62.0,    # Bottom of drawing area (above title block)
        drawing_y_end: float = 285.0,      # Top of drawing area
        drawing_x_start: float = 25.0,     # Left margin
        drawing_x_end: float = 395.0,      # Right margin
    ):
        self.drawing_y_start = drawing_y_start
        self.drawing_y_end = drawing_y_end
        self.drawing_x_start = drawing_x_start
        self.drawing_x_end = drawing_x_end

    @property
    def drawing_height(self) -> float:
        return self.drawing_y_end - self.drawing_y_start

    @property
    def drawing_width(self) -> float:
        return self.drawing_x_end - self.drawing_x_start

    @property
    def center_x(self) -> float:
        return (self.drawing_x_start + self.drawing_x_end) / 2

    @property
    def center_y(self) -> float:
        return (self.drawing_y_start + self.drawing_y_end) / 2


# A3 landscape default
A3_LANDSCAPE = PageSpec()


# ---------------------------------------------------------------------------
# Allocation algorithm
# ---------------------------------------------------------------------------

def allocate(
    measures: list[SectionMeasure],
    config: LayoutConfig,
    page: PageSpec | None = None,
) -> AllocationPlan:
    """Phase 2: 측정 결과를 기반으로 전체 레이아웃을 배분.

    알고리즘:
    1. 총 필요 높이 산정
    2. Scale factor 결정 (페이지에 맞추기)
    3. 섹션별 Y 구간 할당 (bottom → top, DXF 좌표계)
    4. Busbar 폭 결정
    5. 서킷 행 분배

    Args:
        measures: Phase 1 결과 (SectionMeasure 리스트)
        config: 레이아웃 설정
        page: 페이지 사양 (None → A3 landscape)

    Returns:
        AllocationPlan with section regions, scale, busbar extent
    """
    if page is None:
        page = A3_LANDSCAPE

    # ── 1. 총 필요 높이 ──
    present_measures = [m for m in measures if m.present and m.height > 0]
    total_needed = sum(m.height for m in present_measures)

    if total_needed <= 0:
        logger.warning("Allocate: no sections with positive height")
        return AllocationPlan(scale=1.0, spine_x=page.center_x)

    # ── 2. Scale 결정 ──
    available = page.drawing_height
    if total_needed > available:
        scale = available / total_needed
        # Round down to nearest 0.05 for predictability
        scale = math.floor(scale * 20) / 20
        scale = max(0.7, scale)  # 0.7 미만은 너무 작음
    else:
        scale = 1.0

    # ── 3. 섹션별 Y 구간 할당 ──
    # DXF 좌표계: Y가 위로 증가. Supply(top) → Load(bottom).
    # 배치는 top-down: 가장 큰 Y부터 아래로 할당.
    scaled_total = total_needed * scale
    # 중앙 정렬: 남는 공간을 위아래로 균등 배분
    vertical_margin = (available - scaled_total) / 2
    y_cursor = page.drawing_y_end - vertical_margin  # Start from top

    section_regions: dict[str, SectionRegionV2] = {}

    for m in measures:
        if not m.present or m.height <= 0:
            continue

        h = m.height * scale
        y_top = y_cursor
        y_bottom = y_cursor - h

        section_regions[m.section_id] = SectionRegionV2(
            section_id=m.section_id,
            y_start=y_bottom,
            y_end=y_top,
            x_center=page.center_x,
            available_width=page.drawing_width,
        )
        y_cursor = y_bottom

    # ── 4. Busbar 폭 결정 ──
    # sub_circuits 측정에서 total_circuit_width를 가져옴
    circuit_width = 200.0  # default
    for m in measures:
        if m.section_id == "sub_circuits" and m.present:
            circuit_width = m.exports.get("total_circuit_width", m.min_width or 200.0)
            break

    # Busbar: circuit_width + 양쪽 마진
    busbar_width = circuit_width + config.busbar_margin * 2
    busbar_width = min(busbar_width, page.drawing_width - 20)  # 페이지 초과 방지
    busbar_cx = page.center_x
    busbar_x_range = (
        busbar_cx - busbar_width / 2,
        busbar_cx + busbar_width / 2,
    )

    # ── 5. 서킷 행 분배 ──
    num_rows = 1
    for m in measures:
        if m.section_id == "sub_circuits" and m.present:
            num_rows = int(m.exports.get("num_rows", 1))
            break

    # Simple sequential distribution (can be refined later)
    circuits_per_row: list[list[int]] = []
    # Placeholder — actual circuit indices assigned during Place phase

    plan = AllocationPlan(
        scale=scale,
        section_regions=section_regions,
        busbar_x_range=busbar_x_range,
        circuits_per_row=circuits_per_row,
        total_height=scaled_total,
        spine_x=page.center_x,
    )

    logger.info(
        "Phase 2 Allocate: scale=%.2f, total=%.1fmm (of %.1fmm available), "
        "busbar=[%.1f, %.1f], %d section regions",
        scale, scaled_total, available,
        busbar_x_range[0], busbar_x_range[1],
        len(section_regions),
    )

    return plan
