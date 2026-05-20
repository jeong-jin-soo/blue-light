"""Build a SolverScene from a requirements dict + the component catalog.

Phase 1 supports the simplest case end-to-end:
  - 1-phase or 3-phase supply
  - SP-meter (no CT metering) — sections 1, 2, 3, 5, 7, 9, 10, 11, 12, 14
  - N sub-circuits with optional rotated multi-line labels
  - Earth bar at the bottom

CT metering and multi-DB layouts are explicitly out of scope for Phase 1;
they will be added by extending this builder in Phase 2 / 3.
"""

from __future__ import annotations

from app.sld.catalog import get_catalog
from app.sld.solver.boxes import Box, BoxRole, SolverScene, mm


# Component-name → catalog key
_KIND_FROM_REQ = {
    "MCB": "MCB", "MCCB": "MCCB", "ACB": "ACB",
    "RCCB": "RCCB", "ELCB": "ELCB",
}


def _catalog_box(name: str, kind: str, *, section: int, column: str,
                 padding_w: int = 0, padding_h: int = 0) -> Box:
    """Build a Box from a catalog ComponentDef, with optional padding."""
    comp = get_catalog().get(kind)
    w = mm(comp.width) + padding_w
    h = mm(comp.height) + padding_h
    pins = {pn: (mm(p.x), mm(p.y)) for pn, p in comp.pins.items()}
    return Box(
        name=name, w=w, h=h, role=BoxRole.SYMBOL,
        section=section, column=column,
        symbol_kind=kind, pins=pins,
    )


def _spine_label(parent: str, *, section: int, text: str,
                 lines: int, max_line_chars: int,
                 anchors: list[str] | None = None) -> Box:
    """Label tied to a spine symbol. Default anchor = right only.

    Performance note: each extra anchor candidate spawns a Boolean variable
    plus reified linear constraints, multiplying the search space.  Spine
    labels almost always sit on the same side in real LEW drawings, so a
    single fixed anchor keeps the solver fast.  Override with the `anchors`
    parameter for components whose orientation genuinely varies (e.g.
    busbar, earth bar).
    """
    w = mm(max_line_chars * 1.8 + 2)
    h = mm(lines * 3.6 + 1)
    return Box(
        name=f"{parent}_lbl", w=w, h=h, role=BoxRole.LABEL,
        section=section, column="spine_label",
        parent=parent, text=text,
        label_anchors=anchors or ["right"],
    )


def _sub_label(parent: str, *, lines: list[str]) -> Box:
    """Sub-circuit label — rotated 90°, above or below the sub-circuit."""
    max_chars = max(len(line) for line in lines) if lines else 1
    w = mm(max(len(lines) * 3.6 + 1, 8))   # rotated bbox width on page
    h = mm(max_chars * 1.8 + 1)             # rotated bbox height on page
    return Box(
        name=f"{parent}_lbl", w=w, h=h, role=BoxRole.LABEL,
        section=12, column="sub_label",
        parent=parent, text="\n".join(lines), rotated=True,
        label_anchors=["top"],  # always above sub-circuit, between sub and busbar
        label_gap=40,
    )


def build_scene(requirements: dict) -> SolverScene:
    """Translate a requirements dict to a SolverScene."""
    scene = SolverScene()
    page = requirements.get("page", {})
    if page.get("size") == "A2":
        scene.page_w = mm(594)
        scene.page_h = mm(420)

    # 타이틀블록은 페이지 하단 띠를 차지한다 (PageConfig.title_block_height=32 mm).
    # 솔버 박스가 타이틀블록 영역으로 침범하지 않도록 별도의 하단 마진을 확보.
    # 32 mm + 8 mm 안전 여유 = 40 mm. PageConfig 기본값(margin=10)과 합치면
    # 페이지 좌표 y=10..42가 타이틀블록 → 솔버는 y ≥ ~40에 배치된다.
    title_block_clearance = mm(40)
    scene.margin_bottom = max(scene.margin, title_block_clearance)

    main = requirements.get("main_breaker", {}) or {}
    elcb = requirements.get("elcb", {}) or {}
    metering = (requirements.get("metering") or {}).get("type", "sp_meter")
    sub_circuits = requirements.get("sub_circuits", [])

    # ── Section 1: supply ──
    scene.boxes.append(Box(
        name="supply", w=mm(26), h=mm(10), role=BoxRole.SYMBOL,
        section=1, column="spine", text="SUPPLY",
    ))
    scene.boxes.append(Box(
        name="supply_lbl", w=mm(38), h=mm(8), role=BoxRole.LABEL,
        section=1, column="spine_label", parent="supply",
        text=requirements.get("supply_label", "FROM LANDLORD SUPPLY"),
    ))

    # ── Section 2: incoming cable ──
    in_cable = str(requirements.get("incoming_cable", "4x25mm² PVC + 16mm² ECC"))
    scene.boxes.append(Box(
        name="incoming_cab", w=mm(10), h=mm(8), role=BoxRole.SYMBOL,
        section=2, column="spine",
    ))
    scene.boxes.append(_spine_label(
        "incoming_cab", section=2, text=in_cable,
        lines=1, max_line_chars=len(in_cable),
    ))

    # ── Sections 3/4/6/8 depend on metering topology ──
    if metering == "sp_meter":
        # Section 3: SP meter board (ISO → KWH → MCB)
        scene.boxes.append(Box(
            name="meter_board", w=mm(60), h=mm(50), role=BoxRole.SYMBOL,
            section=3, column="spine", text="ISO → KWH → MCB",
        ))
        scene.boxes.append(_spine_label(
            "meter_board", section=3,
            text=requirements.get("meter_label", "KWH METER BY SP"),
            lines=1, max_line_chars=20,
        ))

        # Section 5: outgoing cable from meter board to main breaker
        scene.boxes.append(Box(
            name="outgoing_cab", w=mm(10), h=mm(8), role=BoxRole.SYMBOL,
            section=5, column="spine",
        ))
        scene.boxes.append(_spine_label(
            "outgoing_cab", section=5, text=in_cable,
            lines=1, max_line_chars=len(in_cable),
        ))

    elif metering == "ct_meter":
        # Section 4: unit isolator skipped (CT installations typically
        # have isolation in the CT panel itself)
        # Section 6: CT pre-MCCB fuse + indicator lamp
        scene.boxes.append(Box(
            name="ct_pre_fuse", w=mm(18), h=mm(16), role=BoxRole.SYMBOL,
            section=6, column="spine", text="2A FUSE",
        ))
        scene.boxes.append(_spine_label(
            "ct_pre_fuse", section=6, text="2A HRC Fuse\nIndicator Lamp",
            lines=2, max_line_chars=16,
        ))

    elif metering == "non_meter":
        # Section 4: unit isolator (no meter — typical landlord installation)
        scene.boxes.append(_catalog_box(
            "unit_isolator", "RCCB",  # placeholder kind; LEW supplies the real one
            section=4, column="spine", padding_h=mm(2),
        ))
        scene.boxes.append(_spine_label(
            "unit_isolator", section=4,
            text=requirements.get("isolator_label", "63A 4P UNIT ISOLATOR"),
            lines=1, max_line_chars=20,
        ))

    # ── Section 7: main breaker ──
    main_kind = main.get("type", "MCCB")
    scene.boxes.append(_catalog_box(
        "main_breaker", main_kind,
        section=7, column="spine", padding_h=mm(2),
    ))
    main_label_lines = [
        f"{main.get('rating', 63)}A {main.get('poles', 'TPN')} {main_kind}",
        f"{main.get('characteristic', 'Type B')}, {main.get('fault_kA', 10)}kA",
    ]
    scene.boxes.append(_spine_label(
        "main_breaker", section=7, text="\n".join(main_label_lines),
        lines=len(main_label_lines),
        max_line_chars=max(len(l) for l in main_label_lines),
    ))

    # ── Section 8: CT metering module (only on ct_meter path) ──
    if metering == "ct_meter":
        # CT metering is a composite block: CT hooks, ELR, ASS/AMM, VSS/VLM,
        # kWh meter, BI connector. Phase 2 models it as a single rectangle;
        # Phase 3 will decompose into sub-modules with internal layout.
        ct_w = mm(120)
        ct_h = mm(54)
        scene.boxes.append(Box(
            name="ct_metering", w=ct_w, h=ct_h, role=BoxRole.SYMBOL,
            section=8, column="spine",
            text="CT METERING\n(CT/ELR/ASS/kWh/BI)",
        ))
        ct_label = requirements.get("ct_metering_label", "200/5A CT\nELR / ASS / AMM\nVSS / VLM\nkWh BY SP\nBI CONNECTOR")
        ct_lines = ct_label.split("\n")
        scene.boxes.append(_spine_label(
            "ct_metering", section=8, text=ct_label,
            lines=len(ct_lines),
            max_line_chars=max(len(l) for l in ct_lines),
        ))

    # ── Section 9: ELCB / RCCB ──
    elcb_kind = elcb.get("type", "RCCB")
    scene.boxes.append(_catalog_box(
        "elcb", elcb_kind,
        section=9, column="spine", padding_h=mm(2),
    ))
    elcb_label_lines = [
        f"{elcb.get('rating', 63)}A {elcb.get('poles', '4P')} {elcb_kind}",
        f"{elcb.get('sensitivity_mA', 30)}mA",
    ]
    scene.boxes.append(_spine_label(
        "elcb", section=9, text="\n".join(elcb_label_lines),
        lines=len(elcb_label_lines),
        max_line_chars=max(len(l) for l in elcb_label_lines),
    ))

    # ── Section 10: internal cable ──
    int_cable = str(requirements.get("internal_cable", "4x16mm² PVC + 10mm² ECC"))
    scene.boxes.append(Box(
        name="internal_cab", w=mm(10), h=mm(8), role=BoxRole.SYMBOL,
        section=10, column="spine",
    ))
    scene.boxes.append(_spine_label(
        "internal_cab", section=10, text=int_cable,
        lines=1, max_line_chars=len(int_cable),
    ))

    # ── Section 11: busbar (variable width) ──
    scene.boxes.append(Box(
        name="busbar", w=0, h=mm(4), role=BoxRole.BUS,
        section=11, column="spine",
        text=f"{main.get('rating', 63)}A {len(sub_circuits)}-WAY BUSBAR",
        variable_w=True, min_w=mm(80), max_w=scene.page_w - 2 * scene.margin,
    ))
    bus_label_text = f"{main.get('rating', 63)}A BUSBAR"
    scene.boxes.append(Box(
        name="busbar_lbl",
        w=mm(len(bus_label_text) * 1.8 + 2), h=mm(5),
        role=BoxRole.LABEL,
        section=11, column="spine_label",
        parent="busbar", text=bus_label_text,
        # Busbar is genuinely horizontal — top/bottom are the natural slots.
        label_anchors=["top", "bottom"],
        label_gap=20,
    ))

    # ── Section 12: sub-circuits ──
    for i, sc in enumerate(sub_circuits, start=1):
        kind = sc.get("type", "MCB")
        rating = sc.get("rating", 20)
        poles = sc.get("poles", "SPN")
        cable = sc.get("cable", "2.5mm²")
        load = sc.get("load", "Lighting")
        circuit_id = sc.get("id", f"C{i}")

        scene.boxes.append(_catalog_box(
            f"sc_{i}", kind,
            section=12, column="sub_circuit", padding_h=mm(2),
        ))
        scene.boxes.append(_sub_label(
            f"sc_{i}",
            lines=[circuit_id, f"{rating}A", f"{poles}/{kind}", cable, load],
        ))

    # ── Section 14: earth bar ──
    # LEW 관습: EARTH 심볼은 점(약 8 mm 정도) 1개, DB 우측 하단에 배치.
    # 솔버 박스를 80 mm로 잡으면 시각적으로 부스바와 같은 가로폭의 점선처럼
    # 보여 어색하므로 심볼 크기에 맞춘 작은 박스(10×8 mm)로 모델링한다.
    scene.boxes.append(Box(
        name="earth_bar", w=mm(10), h=mm(8), role=BoxRole.EARTH,
        section=14, column="earth", text="EARTH",
    ))
    earth_text = str(requirements.get("earth_label", "EARTH BAR 35mm² CPC"))
    scene.boxes.append(Box(
        name="earth_bar_lbl",
        w=mm(len(earth_text) * 1.8 + 2), h=mm(4),
        role=BoxRole.LABEL,
        section=14, column="spine_label", parent="earth_bar",
        text=earth_text,
        # Earth bar prefers top (right above the conductor), with right as fallback.
        label_anchors=["top", "right"],
        label_gap=20,
    ))

    return scene
