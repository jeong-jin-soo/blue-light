"""Solver → LayoutResult 어댑터.

CP-SAT 솔버의 `SolveResult` (placement dict, 0.1 mm 정수)를
기존 엔진의 `LayoutResult` (`PlacedComponent` + `PortConnection`,
mm float)로 변환한다.

이 어댑터가 있으면 기존 DXF/PDF/SVG 렌더링 파이프라인을 그대로
재사용해 솔버 출력으로 실제 SLD를 그려낼 수 있다.

매핑 규약
---------
- 솔버 box `column == "spine"`: 스파인 부품. 카탈로그 kind가 있으면
  해당 심볼로, 없으면 가상 PlacedComponent로 변환.
- `column == "sub_circuit"`: 서브회로 부품. 회로 ID/등급/케이블/부하 메타데이터를
  부착한다.
- `role == BUS`: 부스바 → `LayoutResult.busbar_y/start_x/end_x`로 기록 +
  PlacedComponent(`symbol_name="BUSBAR"`) 도 생성.
- `role == EARTH`: 어스바 → PlacedComponent(`symbol_name="EARTH"`, 기존 엔진 규약).
- `role == LABEL`: 텍스트 라벨. 부모 PlacedComponent에 흡수
  (rating, poles, label 등)되거나, 별도 LABEL 컴포넌트로 출력.
- PortConnection: 스파인 sequential 체인 + 각 sub-circuit drop을 자동 생성.
"""

from __future__ import annotations

import logging

from app.sld.catalog import ComponentDef, get_catalog
from app.sld.layout.models import (
    LayoutResult,
    PlacedComponent,
    PortConnection,
)
from app.sld.solver.boxes import Box, BoxRole, SolverScene, UNIT_PER_MM
from app.sld.solver.place import SolveResult

logger = logging.getLogger(__name__)


# 솔버에서 사용한 스파인 box 이름 → 기존 엔진 친화적 symbol_name 맵.
# catalog가 알지 못하는 가상 컴포넌트들은 LABEL/FLOW 등으로 흘려보낸다.
_NON_CATALOG_SPINE: dict[str, str] = {
    "supply": "FLOW_ARROW_UP",
    "incoming_cab": "LABEL",
    "outgoing_cab": "LABEL",
    "internal_cab": "LABEL",
    "meter_board": "LABEL",
    "ct_pre_fuse": "LABEL",
    "ct_metering": "LABEL",
    "unit_isolator": "LABEL",
}


# 트랙 B: 솔버가 단일 직사각형으로 둔 비카탈로그 합성 컴포넌트들
# (meter_board, ct_metering, ct_pre_fuse)를 실제 LEW 도면처럼 점선 박스
# + 내부 sub-component로 풀어 그리기 위한 마커.
# 키가 이 집합에 있으면 LABEL 플레이스홀더 대신 sub-component synth 분기를 탄다.
_BOX_SYNTH_SPINE: frozenset[str] = frozenset({
    "meter_board",
    "ct_metering",
    "ct_pre_fuse",
})


def _unit_to_mm(value: float) -> float:
    """0.1 mm 정수 → mm float."""
    return value / UNIT_PER_MM


def _placement_mm(p: dict) -> tuple[float, float, float, float]:
    """placement dict → (x, y, w, h) in mm."""
    return (
        _unit_to_mm(p["x"]),
        _unit_to_mm(p["y"]),
        _unit_to_mm(p["w"]),
        _unit_to_mm(p["h"]),
    )


def _build_ports(
    placement: dict, comp_def: ComponentDef | None,
) -> dict[str, tuple[float, float]]:
    """카탈로그 핀 offset → 절대 mm 좌표."""
    if comp_def is None:
        return {}
    x_mm, y_mm, _, _ = _placement_mm(placement)
    return {
        name: (x_mm + p.x, y_mm + p.y)
        for name, p in comp_def.pins.items()
    }


def _label_text_for(
    parent_box: Box, scene: SolverScene,
) -> str:
    """부모 box의 LABEL boxes 텍스트를 합쳐 반환."""
    parts = [
        b.text for b in scene.boxes
        if b.role == BoxRole.LABEL and b.parent == parent_box.name and b.text
    ]
    return "\n".join(parts)


def _parse_subcircuit_meta(label_lines: list[str]) -> dict[str, str]:
    """sub-circuit 라벨 라인(예: ['C1', '20A', 'SPN/MCB', '2.5mm²', 'Lighting'])
    → 구조화된 메타데이터 dict.

    솔버 builder의 _sub_label 호출 규약과 1:1 대응한다.
    """
    out: dict[str, str] = {}
    if len(label_lines) >= 1:
        out["circuit_id"] = label_lines[0]
    if len(label_lines) >= 2:
        out["rating"] = label_lines[1]
    if len(label_lines) >= 3:
        poles_type = label_lines[2]
        if "/" in poles_type:
            poles, btype = poles_type.split("/", 1)
            out["poles"] = poles.strip()
            out["breaker_type"] = btype.strip()
        else:
            out["breaker_type"] = poles_type.strip()
    if len(label_lines) >= 4:
        out["cable"] = label_lines[3]
    if len(label_lines) >= 5:
        out["load"] = label_lines[4]
    return out


# 솔버 스파인 순서(섹션 1→14, 부스바 직전까지).
# 기존 14-section 흐름과 동일.
_SPINE_CHAIN_ORDER: tuple[str, ...] = (
    "supply",
    "incoming_cab",
    "meter_board",
    "unit_isolator",
    "outgoing_cab",
    "ct_pre_fuse",
    "main_breaker",
    "ct_metering",
    "elcb",
    "internal_cab",
    "busbar",
)


def _is_chain_node(box: Box) -> bool:
    """Return True if this box should be an endpoint for spine wires.

    Wire 정책: 컴포넌트 본체가 *실제로 그려지는* 컴포넌트들만 wire
    체인의 노드로 삼는다. LABEL로 매핑된 비카탈로그 spine 박스
    (cable / meter_board / ct_metering / ct_pre_fuse / unit_isolator)는
    본체도 내부 wire도 그리지 않으므로, 그것을 끝점으로 잡으면
    그 만큼 spine 선이 끊겨 보인다 (예: ct_metering 54 mm 갭).
    이런 LABEL 박스들은 chain에서 빼서 wire가 그 영역을 *통과*하는
    하나의 직선이 되도록 한다.
    """
    if box.role == BoxRole.BUS:
        return True  # busbar
    if box.role == BoxRole.EARTH:
        return False  # earth_bar는 별도 처리
    if box.symbol_kind:
        return True  # catalog 심볼 (MCCB/RCCB/ELCB/ACB/MCB)
    if box.name == "supply":
        return True  # FLOW_ARROW_UP — 실제 AC 심볼이 그려짐
    return False


def _center_placements_in_drawing_area(
    scene: SolverScene, placements: dict[str, dict],
) -> dict[str, dict]:
    """Place output을 사용 가능한 그리기 영역의 중심으로 평행 이동.

    솔버는 (max_top - min_bot) + (max_right - min_left)을 최소화하므로
    bbox가 페이지의 한쪽 모서리에 몰리는 경향이 있다. 사용 가능
    영역(margin 안쪽, 타이틀블록 제외)의 중심에 bbox 중심을 맞춘다.

    placements dict는 in-place로 변형하지 않고 새 dict를 반환한다.
    """
    if not placements:
        return placements

    # bbox in solver units (0.1 mm 정수).
    xs = [p["x"] for p in placements.values()]
    ys = [p["y"] for p in placements.values()]
    rights = [p["x"] + p["w"] for p in placements.values()]
    tops = [p["y"] + p["h"] for p in placements.values()]
    bbox_xmin, bbox_xmax = min(xs), max(rights)
    bbox_ymin, bbox_ymax = min(ys), max(tops)

    area_xmin = scene.margin
    area_xmax = scene.page_w - scene.margin
    area_ymin = scene.effective_margin_bottom
    area_ymax = scene.page_h - scene.margin

    # 중심 정렬에 필요한 평행 이동량.
    dx = ((area_xmin + area_xmax) - (bbox_xmin + bbox_xmax)) // 2
    dy = ((area_ymin + area_ymax) - (bbox_ymin + bbox_ymax)) // 2

    # 경계 클램프: 시프트로 영역을 벗어나지 않게.
    dx = max(area_xmin - bbox_xmin, min(area_xmax - bbox_xmax, dx))
    dy = max(area_ymin - bbox_ymin, min(area_ymax - bbox_ymax, dy))

    shifted: dict[str, dict] = {}
    for name, p in placements.items():
        shifted[name] = {**p, "x": p["x"] + dx, "y": p["y"] + dy}
    return shifted


def _emit_dashed_box(
    layout: LayoutResult,
    x: float, y: float, w: float, h: float,
) -> None:
    """비카탈로그 합성 컴포넌트의 점선 외곽선을 PortConnection 4개로 emit."""
    left, right, bot, top = x, x + w, y, y + h
    edges = [
        ((left, bot), (right, bot)),
        ((left, top), (right, top)),
        ((left, bot), (left, top)),
        ((right, bot), (right, top)),
    ]
    for a, b in edges:
        layout.port_connections.append(PortConnection(
            from_xy=a, to_xy=b, style="dashed",
        ))


def _emit_meter_board_subs(
    layout: LayoutResult, box: Box,
    x_mm: float, y_mm: float, w_mm: float, h_mm: float,
) -> None:
    """sp_meter meter_board 박스 내부에 ISO/KWH/MCB를 세로로 배치.

    솔버 박스 cx = spine x 이므로, sub-component를 cx에 가운데 정렬하면
    spine wire가 각 심볼의 top/bottom 핀을 통과하면서 자연스럽게 연결돼 보인다.
    흐름은 페이지 위→아래(supply→main_breaker)이므로 박스 위쪽에 ISO,
    중간에 KWH, 아래쪽에 MCB.
    """
    cx = x_mm + w_mm / 2
    iso_w, iso_h = 5.5, 7.0
    kwh_w, kwh_h = 14.0, 10.0
    mcb_w, mcb_h = 5.0, 8.0
    pad = 4.0
    gap = max(((h_mm - 2 * pad) - (iso_h + kwh_h + mcb_h)) / 2, 2.0)

    mcb_y = y_mm + pad
    kwh_y = mcb_y + mcb_h + gap
    iso_y = kwh_y + kwh_h + gap

    layout.components.append(PlacedComponent(
        symbol_name="MCB", x=cx - mcb_w / 2, y=mcb_y,
        id=f"sub_{box.name}_mcb",
        ports={
            "top": (cx, mcb_y + mcb_h), "bottom": (cx, mcb_y),
            "left": (cx - mcb_w / 2, mcb_y + mcb_h / 2),
            "right": (cx + mcb_w / 2, mcb_y + mcb_h / 2),
        },
    ))
    layout.symbols_used.add("MCB")

    layout.components.append(PlacedComponent(
        symbol_name="KWH_METER", x=cx - kwh_w / 2, y=kwh_y,
        id=f"sub_{box.name}_kwh",
        ports={
            "top": (cx, kwh_y + kwh_h), "bottom": (cx, kwh_y),
            "left": (cx - kwh_w / 2, kwh_y + kwh_h / 2),
            "right": (cx + kwh_w / 2, kwh_y + kwh_h / 2),
        },
        # native-horizontal block의 90° 회전으로 "KWH" 텍스트가 세로로 그려지는 것을 방지.
        force_procedural=True,
    ))
    layout.symbols_used.add("KWH_METER")

    layout.components.append(PlacedComponent(
        symbol_name="ISOLATOR", x=cx - iso_w / 2, y=iso_y,
        id=f"sub_{box.name}_iso",
        ports={
            "top": (cx, iso_y + iso_h), "bottom": (cx, iso_y),
            "left": (cx - iso_w / 2, iso_y + iso_h / 2),
            "right": (cx + iso_w / 2, iso_y + iso_h / 2),
        },
    ))
    layout.symbols_used.add("ISOLATOR")

    _emit_dashed_box(layout, x_mm, y_mm, w_mm, h_mm)


def _emit_ct_metering_subs(
    layout: LayoutResult, box: Box,
    x_mm: float, y_mm: float, w_mm: float, h_mm: float,
) -> None:
    """ct_meter ct_metering 박스(120w×54h) 내부 LEW-style 합성.

    레퍼런스(200A TPN SLD 등): CT hook → ASS+AMM, VSS+VLM, KWH, ELR, BI connector.
    스파인 wire(cx 통과)는 CT(상단 hook) → BI_CONNECTOR(하단)을 지나며 secondary는
    좌우 영역에 배치된 계측기로 분기된다. 솔버 박스 안에서는 다음 6개 instrument를
    좌-우 균형 잡힌 2×3 grid로 배치하고 CT hook은 junction_arrows로 시각화한다.

    - 좌 상단 : AMM + ASS (ammeter + selector)
    - 좌 하단 : VLM + VSS (voltmeter + selector)
    - 우 상단 : KWH (proc로 강제 — native-horizontal block의 텍스트 회전 회피)
    - 우 중단 : ELR
    - 스파인  : CT hook (junction_arrows) → BI_CONNECTOR
    """
    cx = x_mm + w_mm / 2
    pad = 4.0

    # ── 스파인 컴포넌트 ──
    # BI_CONNECTOR — 하단, spine wire 통과
    bi_w, bi_h = 7.0, 5.5
    bi_y = y_mm + pad + 2  # 약간 띄워 라벨 공간 확보
    layout.components.append(PlacedComponent(
        symbol_name="BI_CONNECTOR", x=cx - bi_w / 2, y=bi_y,
        id=f"sub_{box.name}_bi",
        ports={"top": (cx, bi_y + bi_h), "bottom": (cx, bi_y)},
    ))
    layout.symbols_used.add("BI_CONNECTOR")

    # CT hook — junction_arrows로 시각화 (CT 심볼 본체는 stub만 그리므로 거의 안 보임).
    # 상단 중앙, BI 위쪽에 배치.
    ct_cy = y_mm + h_mm - pad - 4
    layout.junction_arrows.append((cx, ct_cy, "left"))

    # ── Off-spine 계측기 (우측 영역) ──
    right_band_x = x_mm + w_mm - pad
    kwh_w, kwh_h = 14.0, 10.0
    elr_w, elr_h = 12.0, 6.0

    # KWH 우측 상단
    kwh_x = right_band_x - kwh_w
    kwh_y = y_mm + h_mm - pad - kwh_h
    layout.components.append(PlacedComponent(
        symbol_name="KWH_METER", x=kwh_x, y=kwh_y,
        id=f"sub_{box.name}_kwh",
        force_procedural=True,
    ))
    layout.symbols_used.add("KWH_METER")

    # ELR 우측 하단 (BI 옆쪽 높이)
    elr_x = right_band_x - elr_w
    elr_y = bi_y + (bi_h - elr_h) / 2
    layout.components.append(PlacedComponent(
        symbol_name="ELR", x=elr_x, y=elr_y,
        id=f"sub_{box.name}_elr",
    ))
    layout.symbols_used.add("ELR")

    # ── Off-spine 계측기 (좌측 영역) — AMM/ASS, VLM/VSS pair ──
    meter_size = 4.0  # AMMETER/VOLTMETER/SELECTOR_SWITCH 모두 4×4
    pair_gap = 2.0
    left_band_x = x_mm + pad

    # 상단 pair: AMM + ASS
    pair_top_y = y_mm + h_mm - pad - meter_size
    layout.components.append(PlacedComponent(
        symbol_name="AMMETER", x=left_band_x, y=pair_top_y,
        id=f"sub_{box.name}_amm",
    ))
    layout.symbols_used.add("AMMETER")
    layout.components.append(PlacedComponent(
        symbol_name="SELECTOR_SWITCH",
        x=left_band_x + meter_size + pair_gap, y=pair_top_y,
        id=f"sub_{box.name}_ass",
    ))
    layout.symbols_used.add("SELECTOR_SWITCH")

    # 하단 pair: VLM + VSS
    pair_bot_y = bi_y + (bi_h - meter_size) / 2
    layout.components.append(PlacedComponent(
        symbol_name="VOLTMETER", x=left_band_x, y=pair_bot_y,
        id=f"sub_{box.name}_vlm",
    ))
    layout.symbols_used.add("VOLTMETER")
    layout.components.append(PlacedComponent(
        symbol_name="SELECTOR_SWITCH",
        x=left_band_x + meter_size + pair_gap, y=pair_bot_y,
        id=f"sub_{box.name}_vss",
    ))

    _emit_dashed_box(layout, x_mm, y_mm, w_mm, h_mm)


def _emit_ct_pre_fuse_subs(
    layout: LayoutResult, box: Box,
    x_mm: float, y_mm: float, w_mm: float, h_mm: float,
) -> None:
    """ct_meter ct_pre_fuse 박스(36×28) 내부: 2A FUSE + INCOMING/OUTGOING INDICATOR_LIGHTS.

    레퍼런스(200A TPN SLD): "2A TP MCB" 보호 차단기 양측에 incoming/outgoing
    indicator light 세트(3 lamp + 2A 표시등). 솔버 vertical spine에서는:
    - FUSE — 스파인 중심, top/bottom 핀으로 wire 통과
    - INDICATOR_LIGHTS — 좌/우 핀만 있어 스파인 통과 불가. 우측에 incoming(상)/
      outgoing(하)으로 배치, 스파인에서 짧은 분기 wire로 각 IND의 left 핀에 연결.
    """
    cx = x_mm + w_mm / 2
    pad = 3.0

    # FUSE on spine, 박스 세로 중앙
    fuse_w, fuse_h = 3.5, 8.0
    fuse_x = cx - fuse_w / 2
    fuse_y = y_mm + (h_mm - fuse_h) / 2
    layout.components.append(PlacedComponent(
        symbol_name="FUSE", x=fuse_x, y=fuse_y,
        id=f"sub_{box.name}_fuse",
        ports={"top": (cx, fuse_y + fuse_h), "bottom": (cx, fuse_y)},
    ))
    layout.symbols_used.add("FUSE")

    # INDICATOR_LIGHTS dimensions — procedural symbol width 13.2 mm, height 4 mm.
    ind_w, ind_h = 13.2, 4.0
    ind_anchor_x = cx + 3.0  # 스파인 우측 3mm 갭
    branch_len = ind_anchor_x - cx  # 분기 wire 길이

    # INCOMING IND — FUSE 위쪽, 박스 상단 근처.
    # force_procedural=True: INDICATOR_LIGHTS_CUSTOM block은 native horizontal
    # (1062×265 DXF unit)이라 BlockSymbol이 vertical 배치 시 90° 회전 → 3개의 lamp가
    # 세로로 스택됨. 절차적 심볼은 정상 수평 배치.
    inc_y = y_mm + h_mm - pad - ind_h
    layout.components.append(PlacedComponent(
        symbol_name="INDICATOR_LIGHTS", x=ind_anchor_x, y=inc_y,
        id=f"sub_{box.name}_inc_ind",
        ports={
            "left": (ind_anchor_x, inc_y + ind_h / 2),
            "right": (ind_anchor_x + ind_w, inc_y + ind_h / 2),
        },
        force_procedural=True,
    ))
    layout.symbols_used.add("INDICATOR_LIGHTS")
    # 스파인 → INCOMING IND 좌측 핀 분기 wire
    inc_pin_y = inc_y + ind_h / 2
    layout.port_connections.append(PortConnection(
        from_xy=(cx, inc_pin_y), to_xy=(ind_anchor_x, inc_pin_y),
        style="normal",
    ))

    # OUTGOING IND — FUSE 아래쪽, 박스 하단 근처
    out_y = y_mm + pad
    layout.components.append(PlacedComponent(
        symbol_name="INDICATOR_LIGHTS", x=ind_anchor_x, y=out_y,
        id=f"sub_{box.name}_out_ind",
        ports={
            "left": (ind_anchor_x, out_y + ind_h / 2),
            "right": (ind_anchor_x + ind_w, out_y + ind_h / 2),
        },
        force_procedural=True,
    ))
    out_pin_y = out_y + ind_h / 2
    layout.port_connections.append(PortConnection(
        from_xy=(cx, out_pin_y), to_xy=(ind_anchor_x, out_pin_y),
        style="normal",
    ))

    _emit_dashed_box(layout, x_mm, y_mm, w_mm, h_mm)


# 합성 분기 dispatch — box.name → 핸들러.
_SYNTH_DISPATCH = {
    "meter_board": _emit_meter_board_subs,
    "ct_metering": _emit_ct_metering_subs,
    "ct_pre_fuse": _emit_ct_pre_fuse_subs,
}


def adapt_to_layout_result(
    scene: SolverScene,
    result: SolveResult,
    requirements: dict | None = None,
    *,
    center_in_drawing_area: bool = True,
) -> LayoutResult:
    """SolveResult + SolverScene → LayoutResult.

    실패한 solve (status != OPTIMAL/FEASIBLE)이면 비어 있는 LayoutResult를 반환한다.

    Args:
        scene: 솔버 입력 (Box 그래프).
        result: 솔버 출력 (placements).
        requirements: 원본 requirements dict (supply_type, voltage 등 메타 추출).
        center_in_drawing_area: True면 placements를 페이지 사용 영역 중심으로
            평행 이동한다. 회귀 테스트에서 좌표를 고정하고 싶을 때만 False.

    Returns:
        components/port_connections/busbar 좌표가 채워진 LayoutResult.
    """
    layout = LayoutResult()
    if not result.ok or not result.placements:
        return layout

    req = requirements or {}
    layout.supply_type = req.get("supply_type", "three_phase")
    layout.voltage = req.get("voltage", 400)

    catalog = get_catalog()
    box_by_name = {b.name: b for b in scene.boxes}
    placement_by_name = (
        _center_placements_in_drawing_area(scene, result.placements)
        if center_in_drawing_area else result.placements
    )

    sections_rendered: dict[str, bool] = {}

    # ── PlacedComponent 1차: 스파인 / 서브회로 / 부스바 / 어스바 ──
    for name, placement in placement_by_name.items():
        box: Box = placement["box"]
        if box.role == BoxRole.LABEL:
            # 라벨 흡수 vs 별도 emit 정책:
            #   - 부모가 catalog 심볼(MCB/MCCB/RCCB/ELCB/ACB)이면 breaker_block
            #     스타일로 부모에 흡수 (기존 엔진의 _draw_breaker_block_label이
            #     적절한 위치로 그려준다).
            #   - 부모가 비카탈로그(cable/meter_board/supply/busbar/earth_bar 등)
            #     이거나 자유 anchor(앵커 2개 이상) 라벨이면 솔버가 결정한
            #     위치를 살려 별도 LABEL 컴포넌트로 emit.
            parent_box = box_by_name.get(box.parent)
            parent_is_breaker = bool(parent_box and parent_box.symbol_kind)
            free_anchor = len(box.label_anchors) >= 2
            if box.text and (not parent_is_breaker or free_anchor):
                bx, by, bw, bh = _placement_mm(placement)
                layout.components.append(PlacedComponent(
                    symbol_name="LABEL",
                    x=bx, y=by + bh / 2,
                    id=f"lbl_{box.name}",
                    label=box.text,
                ))
            continue

        x_mm, y_mm, w_mm, h_mm = _placement_mm(placement)

        # 카탈로그 백업이 있는 부품 (MCB/MCCB/RCCB/ELCB/ACB ...)
        if box.symbol_kind:
            try:
                comp_def = catalog.get(box.symbol_kind)
            except KeyError:
                comp_def = None
            ports = _build_ports(placement, comp_def)

            if box.column == "sub_circuit":
                meta = _parse_subcircuit_meta(
                    _label_text_for(box, scene).split("\n")
                )
                placed = PlacedComponent(
                    symbol_name=box.symbol_kind,
                    x=x_mm,
                    y=y_mm,
                    id=f"sub_{box.name}",
                    ports=ports,
                    label=meta.get("circuit_id", ""),
                    rating=meta.get("rating", ""),
                    poles=meta.get("poles", ""),
                    breaker_type_str=meta.get("breaker_type", box.symbol_kind),
                    cable_annotation=meta.get("cable", ""),
                    circuit_id=meta.get("circuit_id", ""),
                    load_info=meta.get("load", ""),
                    rotation=90.0,  # 서브회로 라벨은 항상 vertical
                    label_style="breaker_block",  # LEW-style 다행 라벨
                )
            else:
                # 스파인 부품 (main_breaker, elcb, ...)
                label_text = _label_text_for(box, scene)
                placed = PlacedComponent(
                    symbol_name=box.symbol_kind,
                    x=x_mm,
                    y=y_mm,
                    id=f"spine_{box.name}",
                    ports=ports,
                    label=label_text,
                    breaker_type_str=box.symbol_kind,
                )
            layout.components.append(placed)
            layout.symbols_used.add(box.symbol_kind)
            sections_rendered[box.name] = True
            continue

        # 부스바: LayoutResult 좌표 필드 + BUSBAR 컴포넌트
        if box.role == BoxRole.BUS:
            cy = y_mm + h_mm / 2
            layout.busbar_y = cy
            layout.busbar_start_x = x_mm
            layout.busbar_end_x = x_mm + w_mm
            layout.busbar_full_end_x = x_mm + w_mm
            layout.busbar_visual_end_x = x_mm + w_mm
            layout.busbar_y_per_row = [cy]
            layout.busbar_x_per_row = {cy: (x_mm, x_mm + w_mm)}
            # 부스바 라벨은 자식 LABEL 박스(label_anchors=top/bottom)가
            # 자유 anchor 분기에서 별도 emit되므로 BUSBAR 컴포넌트의 rating은
            # 비워둔다 (그렇지 않으면 라벨이 두 번 그려진다).
            layout.components.append(PlacedComponent(
                symbol_name="BUSBAR",
                x=x_mm,
                y=cy,            # 부스바는 중심 Y로 그린다
                id="busbar",
                rating="",
                ports={
                    "left": (x_mm, cy),
                    "right": (x_mm + w_mm, cy),
                    "center": (x_mm + w_mm / 2, cy),
                },
            ))
            sections_rendered["main_busbar"] = True
            continue

        # 어스바: 솔버 박스를 EARTH 심볼 본체(약 10×8 mm)에 1:1 대응.
        # "EARTH BAR 35mm² CPC" 같은 도체 라벨은 별도 LABEL 박스로 솔버가
        # 자유 anchor 안에서 배치하고, 어댑터의 LABEL 흡수 분기에서 emit된다.
        if box.role == BoxRole.EARTH:
            cy = y_mm + h_mm / 2
            layout.components.append(PlacedComponent(
                symbol_name="EARTH",
                x=x_mm,
                y=y_mm,
                id="earth_bar",
                label="E",
                ports={
                    "top": (x_mm + w_mm / 2, y_mm + h_mm),
                    "left": (x_mm, cy),
                    "right": (x_mm + w_mm, cy),
                    "center": (x_mm + w_mm / 2, cy),
                },
            ))
            sections_rendered["earth_bar"] = True
            continue

        # 카탈로그 외 스파인 부품 (supply, cables, meter_board ...).
        # 라벨 텍스트는 자식 LABEL 박스에서 별도로 emit되므로 여기서는
        # *chain 포트만* 가진 invisible LABEL 컴포넌트로 등록한다.
        # 예외: supply는 FLOW_ARROW_UP (AC 공급 심볼)로 시각 렌더.
        # 트랙 B 예외: meter_board / ct_metering / ct_pre_fuse는 단일 LABEL
        # 대신 점선 박스 + 내부 sub-component로 풀어 그린다.
        if box.name in _SYNTH_DISPATCH:
            _SYNTH_DISPATCH[box.name](layout, box, x_mm, y_mm, w_mm, h_mm)
            sections_rendered[box.name] = True
            continue

        symbol_name = _NON_CATALOG_SPINE.get(box.name, "LABEL")
        cx = x_mm + w_mm / 2
        cy = y_mm + h_mm / 2
        anchor_x = cx if symbol_name in ("FLOW_ARROW", "FLOW_ARROW_UP") else x_mm
        anchor_y = cy if symbol_name in ("FLOW_ARROW", "FLOW_ARROW_UP") else y_mm
        layout.components.append(PlacedComponent(
            symbol_name=symbol_name,
            x=anchor_x,
            y=anchor_y,
            id=f"spine_{box.name}",
            # supply는 라벨이 별도 LABEL 박스로 처리되므로 빈 label.
            # 그 외 placeholder도 마찬가지.
            label="",
            ports={
                "top": (cx, y_mm + h_mm),
                "bottom": (cx, y_mm),
                "center": (cx, cy),
            },
        ))
        sections_rendered[box.name] = True

    layout.sections_rendered = sections_rendered

    # ── PortConnection: 스파인 sequential 체인 ──
    # LABEL-only spine 박스(cable/meter_board/ct_metering/ct_pre_fuse/
    # unit_isolator)는 본체·내부 wire가 없어 wire chain의 끝점으로 잡으면
    # 박스 높이만큼 spine 선이 끊겨 보인다. `_is_chain_node`가 본체가
    # 실제로 그려지는 컴포넌트만 통과시키도록 필터링 → wire는 LABEL
    # 영역을 자연스럽게 가로지르는 단일 직선이 된다.
    chain_ids = []
    for box_name in _SPINE_CHAIN_ORDER:
        if box_name not in placement_by_name:
            continue
        box = box_by_name.get(box_name)
        if box is None or not _is_chain_node(box):
            continue
        comp_id = "busbar" if box_name == "busbar" else f"spine_{box_name}"
        chain_ids.append((box_name, comp_id))

    for (a_name, a_id), (b_name, b_id) in zip(chain_ids, chain_ids[1:]):
        a_box = box_by_name.get(a_name)
        b_box = box_by_name.get(b_name)
        if a_box is None or b_box is None:
            continue
        # 부스바는 위쪽에서 들어오는 흐름의 종점. 부스바 PlacedComponent의
        # 포트는 left/right/center뿐이므로 anonymous from_xy로 종단한다.
        if b_box.role == BoxRole.BUS:
            placement = placement_by_name[b_box.name]
            bx, by, bw, bh = _placement_mm(placement)
            tap_xy = (bx + bw / 2, by + bh / 2)
            layout.port_connections.append(PortConnection(
                from_id=a_id, from_port=_pick_outgoing_port(a_box),
                to_id="", to_port="", to_xy=tap_xy,
                style="normal",
            ))
            continue
        a_port = _pick_outgoing_port(a_box)
        b_port = _pick_incoming_port(b_box)
        layout.port_connections.append(PortConnection(
            from_id=a_id, from_port=a_port,
            to_id=b_id, to_port=b_port,
            style="normal",
        ))

    # ── PortConnection: 부스바 → 각 서브회로 (drop) ──
    if "busbar" in placement_by_name:
        for box in scene.boxes:
            if box.column != "sub_circuit":
                continue
            sub_id = f"sub_{box.name}"
            top_port = _pick_incoming_port(box)
            # 부스바 측은 좌표 고정 fallback이 깔끔하다.
            sub_placement = placement_by_name[box.name]
            sx_mm, sy_mm, sw_mm, _ = _placement_mm(sub_placement)
            tap_x = sx_mm + sw_mm / 2  # sub-circuit 중심 X
            tap_y = layout.busbar_y
            layout.port_connections.append(PortConnection(
                from_id="", from_port="",
                from_xy=(tap_x, tap_y),
                to_id=sub_id, to_port=top_port,
                style="normal",
            ))

    # 컴포넌트 인덱스 캐시 초기화 (resolve_port_connection 안전성).
    layout.invalidate_component_index()
    return layout


def _pick_outgoing_port(box: Box) -> str:
    """스파인에서 다음 단으로 빠져나가는 포트 이름. bottom > center."""
    if "bottom" in box.pins:
        return "bottom"
    if "center" in box.pins:
        return "center"
    return "bottom"


def _pick_incoming_port(box: Box) -> str:
    """위쪽에서 들어오는 포트 이름. top > center."""
    if "top" in box.pins:
        return "top"
    if "center" in box.pins:
        return "center"
    return "top"
