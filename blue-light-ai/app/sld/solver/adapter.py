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


def adapt_to_layout_result(
    scene: SolverScene,
    result: SolveResult,
    requirements: dict | None = None,
) -> LayoutResult:
    """SolveResult + SolverScene → LayoutResult.

    실패한 solve (status != OPTIMAL/FEASIBLE)이면 비어 있는 LayoutResult를 반환한다.

    Args:
        scene: 솔버 입력 (Box 그래프).
        result: 솔버 출력 (placements).
        requirements: 원본 requirements dict (supply_type, voltage 등 메타 추출).

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
    placement_by_name = result.placements

    sections_rendered: dict[str, bool] = {}

    # ── PlacedComponent 1차: 스파인 / 서브회로 / 부스바 / 어스바 ──
    for name, placement in placement_by_name.items():
        box: Box = placement["box"]
        if box.role == BoxRole.LABEL:
            continue  # 라벨은 부모에 흡수

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
            layout.components.append(PlacedComponent(
                symbol_name="BUSBAR",
                x=x_mm,
                y=cy,            # 부스바는 중심 Y로 그린다
                id="busbar",
                rating=_label_text_for(box, scene),
                ports={
                    "left": (x_mm, cy),
                    "right": (x_mm + w_mm, cy),
                    "center": (x_mm + w_mm / 2, cy),
                },
            ))
            sections_rendered["main_busbar"] = True
            continue

        # 어스바
        if box.role == BoxRole.EARTH:
            cy = y_mm + h_mm / 2
            # 기존 엔진은 EARTH 심볼을 컴팩트하게 한 곳에 두고 도체 라인으로
            # 부스바와 연결하는 방식이다. 솔버는 어스바를 폭이 넓은 박스로
            # 모델링하므로 박스 좌측에 EARTH 심볼 placement만 등록하고 라벨은
            # 별도 LABEL 컴포넌트로 분리한다.
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
            earth_text = _label_text_for(box, scene)
            if earth_text:
                layout.components.append(PlacedComponent(
                    symbol_name="LABEL",
                    x=x_mm + w_mm + 2.0,
                    y=cy,
                    id="earth_bar_lbl",
                    label=earth_text,
                ))
            sections_rendered["earth_bar"] = True
            continue

        # 카탈로그 외 스파인 부품 (supply, cables, meter_board ...)
        symbol_name = _NON_CATALOG_SPINE.get(box.name, "LABEL")
        text = box.text or _label_text_for(box, scene)
        cx = x_mm + w_mm / 2
        cy = y_mm + h_mm / 2
        # FLOW_ARROW_UP는 center anchor, 그 외 LABEL은 left-bottom anchor.
        anchor_x = cx if symbol_name in ("FLOW_ARROW", "FLOW_ARROW_UP") else x_mm
        anchor_y = cy if symbol_name in ("FLOW_ARROW", "FLOW_ARROW_UP") else y_mm
        layout.components.append(PlacedComponent(
            symbol_name=symbol_name,
            x=anchor_x,
            y=anchor_y,
            id=f"spine_{box.name}",
            label=text,
            ports={
                "top": (cx, y_mm + h_mm),
                "bottom": (cx, y_mm),
                "center": (cx, cy),
            },
        ))
        sections_rendered[box.name] = True

    layout.sections_rendered = sections_rendered

    # ── PortConnection: 스파인 sequential 체인 ──
    chain_ids = []
    for box_name in _SPINE_CHAIN_ORDER:
        if box_name not in placement_by_name:
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
