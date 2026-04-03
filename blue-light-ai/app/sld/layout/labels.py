"""Step D: 케이블 라벨 후배치 — 모든 배치 완료 후 충돌 없는 위치에 배치.

전문가 CAD 작업 순서의 마지막 단계:
1. 모든 선과 심볼이 그려진 후
2. 기존 요소들의 위치를 확인하고
3. 겹치지 않는 위치에 케이블 사양 텍스트를 배치

섹션 함수들은 텍스트를 직접 배치하지 않고 deferred_cable_labels에 등록한다.
이 모듈의 place_deferred_cable_labels()가 최종 위치를 결정한다.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent

logger = logging.getLogger(__name__)


@dataclass
class _OccupiedRect:
    """배치된 요소의 경계 사각형."""
    x_min: float
    y_min: float
    x_max: float
    y_max: float

    def overlaps(self, other: "_OccupiedRect", margin: float = 1.0) -> bool:
        """두 사각형이 margin 이내로 겹치는지 확인."""
        return not (
            self.x_max + margin <= other.x_min
            or other.x_max + margin <= self.x_min
            or self.y_max + margin <= other.y_min
            or other.y_max + margin <= self.y_min
        )


def _collect_occupied_rects(
    result: LayoutResult, config: LayoutConfig,
) -> list[_OccupiedRect]:
    """현재 배치된 모든 요소의 경계 사각형을 수집."""
    rects: list[_OccupiedRect] = []

    # Connections (spine lines, busbar, etc.)
    for collection in (result.connections, result.thick_connections,
                       result.leader_connections, result.fixed_connections,
                       result.thick_fixed_connections):
        for (x1, y1), (x2, y2) in collection:
            rects.append(_OccupiedRect(
                x_min=min(x1, x2) - 0.5,
                y_min=min(y1, y2) - 0.5,
                x_max=max(x1, x2) + 0.5,
                y_max=max(y1, y2) + 0.5,
            ))

    # Components (symbols)
    char_w = getattr(config, "char_w_label", 1.8)
    for comp in result.components:
        if comp.symbol_name == "LABEL":
            lines = comp.label.replace("\\P", "\n").split("\n")
            max_len = max((len(ln) for ln in lines), default=0)
            num_lines = len(lines)
            ch = getattr(config, "label_char_height", 2.8)
            if abs(comp.rotation - 90.0) < 0.1:
                w = num_lines * ch
                h = max_len * char_w
            else:
                w = max_len * char_w
                h = num_lines * ch
            rects.append(_OccupiedRect(
                x_min=comp.x, y_min=comp.y,
                x_max=comp.x + w, y_max=comp.y + h,
            ))
        elif comp.symbol_name not in ("BUSBAR", "DB_INFO_BOX", "CIRCUIT_ID_BOX"):
            # Simple symbol bbox estimate
            rects.append(_OccupiedRect(
                x_min=comp.x - 1, y_min=comp.y - 1,
                x_max=comp.x + 8, y_max=comp.y + 10,
            ))

    return rects


def _estimate_text_rect(
    text: str, x: float, y: float, char_height: float,
) -> _OccupiedRect:
    """텍스트의 경계 사각형을 추정."""
    char_w = char_height * 0.7  # proportional font width ratio
    lines = text.replace("\\P", "\n").split("\n")
    max_len = max((len(ln) for ln in lines), default=0)
    num_lines = len(lines)
    w = max_len * char_w
    h = num_lines * (char_height + 0.5)
    return _OccupiedRect(x_min=x, y_min=y, x_max=x + w, y_max=y + h)


def place_deferred_cable_labels(
    result: LayoutResult, config: LayoutConfig,
) -> None:
    """Step D: deferred cable labels를 충돌 없는 위치에 배치.

    각 deferred label에 대해:
    1. 기본 위치를 계산 (leader line 끝에서 텍스트 배치)
    2. 기존 요소와 겹치는지 확인
    3. 겹치면 Y 방향으로 조금씩 이동하여 빈 공간 탐색
    4. 최종 위치에 LABEL 컴포넌트 추가
    """
    if not result.deferred_cable_labels:
        return

    # 현재 배치된 모든 요소 수집
    occupied = _collect_occupied_rects(result, config)

    for label_info in result.deferred_cable_labels:
        text = label_info["text"]
        tick_x = label_info["tick_x"]
        tick_y = label_info["tick_y"]
        side = label_info["side"]
        leader_len = label_info["leader_len"]
        ch = label_info.get("char_height", 2.8)

        # 기본 위치 계산
        char_w = ch * 0.7
        lines = text.replace("\\P", "\n").split("\n")
        max_line_len = max((len(ln) for ln in lines), default=0)
        text_width = max_line_len * char_w
        num_lines = len(lines)
        text_height = num_lines * (ch + 0.5)

        # 텍스트 Y: 리더선과 같은 높이.
        # DXF: Y↑ 증가, MTEXT attachment_point=1 (TOP_LEFT).
        # 텍스트 상단이 리더선 위 ch/2에 오면, 첫 줄 중앙 = 리더선 Y.
        text_y = tick_y + ch * 0.5

        if side == "left":
            # 리더선 끝(tick_x - leader_len)에서 텍스트 우측까지 충분한 간격.
            # DXF MTEXT 가변폭 폰트 실제 폭은 char_w 추정치의 ~130%.
            # "CABLE +" 같은 대문자+기호가 넓으므로 넉넉히 잡는다.
            text_right_gap = 12.0  # 리더선 끝 ↔ 텍스트 우측 간격
            leader_end_x = tick_x - leader_len
            text_x = leader_end_x - text_right_gap - text_width
            text_x = max(text_x, getattr(config, "min_x", 25) + 2)
        else:
            gap = 8.0 if leader_len == 0 else 2.0
            text_x = tick_x + leader_len + gap

        # 충돌 검사 — X 방향으로만 이동 (리더선과 텍스트의 Y 정렬 유지)
        candidate_rect = _OccupiedRect(
            x_min=text_x, y_min=text_y - text_height,
            x_max=text_x + text_width, y_max=text_y,
        )

        overlap_count = _count_overlaps(candidate_rect, occupied)
        extra_shift = 0.0
        while overlap_count > 0 and extra_shift < 40:
            extra_shift += 3.0
            if side == "left":
                trial_x = text_x - extra_shift
                trial_x = max(trial_x, getattr(config, "min_x", 25) + 2)
            else:
                trial_x = text_x + extra_shift
            trial_rect = _OccupiedRect(
                x_min=trial_x, y_min=text_y - text_height,
                x_max=trial_x + text_width, y_max=text_y,
            )
            overlap_count = _count_overlaps(trial_rect, occupied)
            if overlap_count == 0:
                text_x = trial_x
                break

        if overlap_count > 0:
            logger.warning(
                "Cable label '%s' at (%.1f, %.1f) still has %d overlaps",
                text[:30], text_x, text_y, overlap_count,
            )

        # 최종 배치
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=text_x,
            y=text_y,
            label=text,
        ))

        # 배치한 텍스트를 occupied에 추가
        occupied.append(_OccupiedRect(
            x_min=text_x, y_min=text_y - text_height,
            x_max=text_x + text_width, y_max=text_y,
        ))

    logger.info(
        "Step D: placed %d deferred cable labels",
        len(result.deferred_cable_labels),
    )


def _count_overlaps(rect: _OccupiedRect, occupied: list[_OccupiedRect]) -> int:
    """rect와 겹치는 occupied 사각형 수를 센다."""
    return sum(1 for r in occupied if rect.overlaps(r))
