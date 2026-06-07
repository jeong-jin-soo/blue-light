"""Wire-vs-box crossing 검사 — 트랙 C 회귀 가드.

배경 (메모리 sld-cpsat-next-session.md 트랙 C):
  orthogonal wire routing 이 필요한지 판단하기 위해 9개 벤치 시나리오에서
  wire 가 컴포넌트 박스를 가로지르는지 실측한 결과 —
  **컴포넌트 본체(심볼) vs wire 충돌 0건**, 라벨 텍스트 박스 충돌만 존재.
  → orthogonal routing 불필요. 본 모듈은 그 결론(본체 충돌 0)이 회귀하지
     않도록 솔버 placements + 어댑터 wire 를 대조하는 검사를 제공한다.

설계:
- 박스 좌표 소스는 솔버 `SolveResult.placements` ({x,y,w,h} 명시, 0.1mm 단위).
- wire 는 어댑터 `LayoutResult.port_connections` resolve 결과 (직선 start→end, mm).
- 본체 박스(_lbl 아님) 와 라벨 박스(_lbl) 를 분리 카운트.
- 의도적으로 wire 가 통과하도록 설계된 LABEL spine 박스(_is_chain_node 제외 대상)
  와 busbar/earth 는 passthrough 로 제외.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.sld.solver.boxes import UNIT_PER_MM


# wire 가 통과해도 정상인 박스 (LABEL spine — _is_chain_node 제외 대상 + 가로 막대).
WIRE_PASSTHROUGH_NAMES: frozenset[str] = frozenset({
    "incoming_cable", "outgoing_cable", "internal_cable",
    "unit_isolator", "meter_board", "ct_metering", "ct_pre_fuse",
    "busbar", "earth_bar",
})


@dataclass
class CrossingReport:
    """wire-vs-box 충돌 측정 결과."""
    body_crossings: list[str]
    label_crossings: list[str]
    n_body_boxes: int
    n_label_boxes: int
    n_wires: int

    @property
    def n_body_crossings(self) -> int:
        return len(self.body_crossings)

    @property
    def n_label_crossings(self) -> int:
        return len(self.label_crossings)


def seg_intersects_rect(
    p1: tuple[float, float], p2: tuple[float, float],
    left: float, bottom: float, right: float, top: float,
    margin: float = 0.5,
) -> bool:
    """선분 p1-p2 가 사각형 (left,bottom,right,top) 내부를 의미 있게 가로지르는지.

    margin 만큼 사각형을 수축해 끝점이 변에 닿는 정상 연결을 충돌로 오판하지 않는다.
    Liang-Barsky 선분 클리핑.
    """
    l, b = left + margin, bottom + margin
    r, t = right - margin, top - margin
    if l >= r or b >= t:
        return False  # 수축 후 빈 사각형 (얇은 박스) → 판정 안 함

    x1, y1 = p1
    x2, y2 = p2
    dx, dy = x2 - x1, y2 - y1

    t_min, t_max = 0.0, 1.0
    for p, q in ((-dx, x1 - l), (dx, r - x1), (-dy, y1 - b), (dy, t - y1)):
        if abs(p) < 1e-9:
            if q < 0:
                return False  # 평행하고 경계 밖
        else:
            tt = q / p
            if p < 0:
                if tt > t_max:
                    return False
                if tt > t_min:
                    t_min = tt
            else:
                if tt < t_min:
                    return False
                if tt < t_max:
                    t_max = tt
    return (t_max - t_min) > 1e-6


def measure_crossings(scene, result, layout, margin: float = 0.5) -> CrossingReport:
    """솔버 placements + 어댑터 LayoutResult 를 대조해 wire-vs-box 충돌을 측정.

    Args:
        scene: build_scene 결과 (미사용 — 호환 위해 받음)
        result: place_layout 결과 (SolveResult, .placements 사용)
        layout: adapt_to_layout_result 결과 (LayoutResult, .port_connections 사용)
        margin: 박스 수축 여유 (mm)

    Returns:
        CrossingReport — 본체/라벨 충돌 분리 카운트.
    """
    body_boxes: list[tuple[str, float, float, float, float]] = []
    label_boxes: list[tuple[str, float, float, float, float]] = []
    for name, p in result.placements.items():
        if name in WIRE_PASSTHROUGH_NAMES:
            continue
        x = p["x"] / UNIT_PER_MM
        y = p["y"] / UNIT_PER_MM
        w = p["w"] / UNIT_PER_MM
        h = p["h"] / UNIT_PER_MM
        entry = (name, x, y, x + w, y + h)
        if name.endswith("_lbl"):
            label_boxes.append(entry)
        else:
            body_boxes.append(entry)

    body_crossings: list[str] = []
    label_crossings: list[str] = []
    n_wires = 0
    for pc in layout.port_connections:
        if pc.style in ("dashed", "short_dashed"):
            continue  # 점선 박스 외곽 — wire 아님
        n_wires += 1
        start, end = layout.resolve_port_connection(pc)
        if start is None or end is None:
            continue
        for (name, l, b, r, t) in body_boxes:
            if pc.from_id == name or pc.to_id == name:
                continue
            if seg_intersects_rect(start, end, l, b, r, t, margin):
                body_crossings.append(
                    f"wire ({start[0]:.0f},{start[1]:.0f})->({end[0]:.0f},{end[1]:.0f}) "
                    f"crosses BODY '{name}' [{l:.0f},{b:.0f},{r:.0f},{t:.0f}]"
                )
        for (name, l, b, r, t) in label_boxes:
            if pc.from_id == name or pc.to_id == name:
                continue
            if seg_intersects_rect(start, end, l, b, r, t, margin):
                label_crossings.append(
                    f"wire ({start[0]:.0f},{start[1]:.0f})->({end[0]:.0f},{end[1]:.0f}) "
                    f"crosses LABEL '{name}' [{l:.0f},{b:.0f},{r:.0f},{t:.0f}]"
                )

    return CrossingReport(
        body_crossings=body_crossings,
        label_crossings=label_crossings,
        n_body_boxes=len(body_boxes),
        n_label_boxes=len(label_boxes),
        n_wires=n_wires,
    )
