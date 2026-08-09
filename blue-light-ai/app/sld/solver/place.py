"""CP-SAT solver — turns a SolverScene into absolute box positions.

The model encodes:
  - Global non-overlap on ALL boxes (AddNoOverlap2D).
  - Singapore 14-section ordering on the spine: higher section# = lower Y.
  - Spine center alignment.
  - Spine labels right-of-symbol with shared vertical band.
  - Sub-circuit row: shared Y, equal X spacing, span within busbar.
  - Variable-width busbar that fits the sub-circuit row exactly.
  - Sub-circuit rotated labels above their parent symbol.
  - Earth bar below sub-circuits.

Objective: minimize vertical footprint (page-height usage).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

from ortools.sat.python import cp_model

from app.sld.solver.boxes import Box, BoxRole, SolverScene

logger = logging.getLogger(__name__)


@dataclass
class SolveResult:
    status: str
    solve_time_s: float
    branches: int
    placements: dict[str, dict] = field(default_factory=dict)
    label_anchors: dict[str, str] = field(default_factory=dict)
    overlaps: int = 0

    @property
    def ok(self) -> bool:
        return self.status in ("OPTIMAL", "FEASIBLE") and self.overlaps == 0


def _verify_no_overlap(placements: dict[str, dict]) -> int:
    """Independent AABB check — should always return 0 if model is correct."""
    items = list(placements.values())
    n = 0
    for i, a in enumerate(items):
        for b in items[i + 1:]:
            if (a["x"] < b["x"] + b["w"]
                    and a["x"] + a["w"] > b["x"]
                    and a["y"] < b["y"] + b["h"]
                    and a["y"] + a["h"] > b["y"]):
                logger.warning("OVERLAP %s <> %s", a["name"], b["name"])
                n += 1
    return n


def place_layout(scene: SolverScene, *, time_limit_s: float = 15.0) -> SolveResult:
    model = cp_model.CpModel()
    vars_: dict[str, tuple] = {}   # name -> (x, y, w_var_or_None, box)
    x_ivs, y_ivs = [], []

    page_w = scene.page_w
    page_h = scene.page_h
    margin = scene.margin
    margin_bottom = scene.effective_margin_bottom

    for b in scene.boxes:
        if b.variable_w:
            w = model.NewIntVar(b.min_w, b.max_w, f"w_{b.name}")
            x = model.NewIntVar(margin, page_w - b.min_w - margin, f"x_{b.name}")
            x_end = model.NewIntVar(margin + b.min_w, page_w - margin, f"xe_{b.name}")
            model.Add(x_end == x + w)
            xiv = model.NewIntervalVar(x, w, x_end, f"xiv_{b.name}")
        else:
            x = model.NewIntVar(margin, page_w - b.w - margin, f"x_{b.name}")
            xiv = model.NewIntervalVar(x, b.w, x + b.w, f"xiv_{b.name}")
            w = None
        # 비대칭 마진: 하단은 타이틀블록 영역만큼 별도 차감.
        y = model.NewIntVar(margin_bottom, page_h - b.h - margin, f"y_{b.name}")
        yiv = model.NewIntervalVar(y, b.h, y + b.h, f"yiv_{b.name}")
        vars_[b.name] = (x, y, w, b)
        x_ivs.append(xiv)
        y_ivs.append(yiv)

    # Mathematical guarantee: no two boxes overlap.
    model.AddNoOverlap2D(x_ivs, y_ivs)

    # ── 14-section ordering on the spine ──
    # SG LEW 관례 (sg-sld-domain-knowledge.md): 전원(§1)이 페이지 하단,
    # 부하(부스바·서브회로)가 상단 — 흐름은 아래→위.
    # 즉 section 번호가 클수록 y가 크다(위쪽).
    spine = sorted(
        [b for b in scene.boxes if b.column == "spine"],
        key=lambda b: b.section,
    )
    # Minimum gap between consecutive spine boxes. Scenario builder sizes
    # this to fill the usable page height; floor is 5 mm.
    SECTION_GAP = max(50, scene.section_gap)
    for a, b in zip(spine, spine[1:]):
        ya = vars_[a.name][1]
        yb = vars_[b.name][1]
        model.Add(yb >= ya + a.h + SECTION_GAP)

    # ── Spine center alignment (with ±1 unit slack for parity safety) ──
    # CP-SAT integers cannot express center alignment exactly when widths
    # have mismatched parity (e.g. MCCB=55, MCB=50). A ±0.1mm slack avoids
    # INFEASIBLE without any visible misalignment.
    ALIGN_SLACK = 1  # 0.1 mm
    if spine:
        anchor = spine[0]
        x_anc = vars_[anchor.name][0]
        for s in spine[1:]:
            xs, _ys, wv, _ = vars_[s.name]
            sw = wv if wv is not None else s.w
            # |2*xs + sw - 2*x_anc - anchor.w| <= ALIGN_SLACK
            model.Add(2 * xs + sw - 2 * x_anc - anchor.w <= ALIGN_SLACK)
            model.Add(2 * xs + sw - 2 * x_anc - anchor.w >= -ALIGN_SLACK)

    # ── Label binding ─────────────────────────────────────────────
    # Each label declares 1..4 candidate anchors relative to its parent.
    # The solver picks exactly one via reified booleans.
    label_anchor_choice: dict[str, dict[str, "cp_model.IntVar"]] = {}
    for b in scene.boxes:
        if b.role != BoxRole.LABEL or not b.parent or b.parent not in vars_:
            continue
        xp, yp, wp_var, parent_box = vars_[b.parent]
        xl, yl, _, _ = vars_[b.name]
        parent_w = wp_var if wp_var is not None else parent_box.w
        gap = b.label_gap

        # Collect boolean variables, one per candidate anchor.
        anchors = b.label_anchors or ["right"]
        choice_vars = {}
        for anchor in anchors:
            bv = model.NewBoolVar(f"lbl_{b.name}_{anchor}")
            choice_vars[anchor] = bv

            if anchor == "right":
                model.Add(xl == xp + parent_w + gap).OnlyEnforceIf(bv)
                model.Add(2 * yl + b.h - 2 * yp - parent_box.h <= ALIGN_SLACK).OnlyEnforceIf(bv)
                model.Add(2 * yl + b.h - 2 * yp - parent_box.h >= -ALIGN_SLACK).OnlyEnforceIf(bv)
            elif anchor == "left":
                model.Add(xl + b.w + gap == xp).OnlyEnforceIf(bv)
                model.Add(2 * yl + b.h - 2 * yp - parent_box.h <= ALIGN_SLACK).OnlyEnforceIf(bv)
                model.Add(2 * yl + b.h - 2 * yp - parent_box.h >= -ALIGN_SLACK).OnlyEnforceIf(bv)
            elif anchor == "top":
                # Above parent: label.y >= parent.y + parent.h + gap
                model.Add(yl == yp + parent_box.h + gap).OnlyEnforceIf(bv)
                model.Add(2 * xl + b.w - 2 * xp - parent_w <= ALIGN_SLACK).OnlyEnforceIf(bv)
                model.Add(2 * xl + b.w - 2 * xp - parent_w >= -ALIGN_SLACK).OnlyEnforceIf(bv)
            elif anchor == "bottom":
                # Below parent: label.y + label.h + gap == parent.y
                model.Add(yl + b.h + gap == yp).OnlyEnforceIf(bv)
                model.Add(2 * xl + b.w - 2 * xp - parent_w <= ALIGN_SLACK).OnlyEnforceIf(bv)
                model.Add(2 * xl + b.w - 2 * xp - parent_w >= -ALIGN_SLACK).OnlyEnforceIf(bv)
            elif anchor == "top-left":
                # Above parent, label *left edge* aligned to parent left edge.
                # 가운데 정렬을 피해 spine wire(=parent.cx)가 라벨 박스 외부에 위치하게.
                # 부스바·어스바처럼 wire가 parent 중심을 통과하는 컴포넌트에 사용.
                model.Add(yl == yp + parent_box.h + gap).OnlyEnforceIf(bv)
                model.Add(xl == xp).OnlyEnforceIf(bv)
            elif anchor == "top-right":
                # Above parent, label *right edge* aligned to parent right edge.
                model.Add(yl == yp + parent_box.h + gap).OnlyEnforceIf(bv)
                model.Add(xl + b.w == xp + parent_w).OnlyEnforceIf(bv)
            elif anchor == "bottom-left":
                model.Add(yl + b.h + gap == yp).OnlyEnforceIf(bv)
                model.Add(xl == xp).OnlyEnforceIf(bv)
            elif anchor == "bottom-right":
                model.Add(yl + b.h + gap == yp).OnlyEnforceIf(bv)
                model.Add(xl + b.w == xp + parent_w).OnlyEnforceIf(bv)
            elif anchor == "top-shift-right":
                # 라벨을 parent 우측 옆 + 위쪽으로 이동.
                # 부모가 라벨보다 좁아 top·top-right로도 wire가 라벨을
                # 통과하는 경우(예: sub_label vs drop wire) 라벨을 parent의
                # *옆*으로 완전히 빼낸다. NoOverlap2D 때문에 인접 sub-circuit
                # 간격이 라벨 폭만큼 자동으로 확장된다.
                model.Add(yl == yp + parent_box.h + gap).OnlyEnforceIf(bv)
                model.Add(xl == xp + parent_w + gap).OnlyEnforceIf(bv)

        if choice_vars:
            model.AddExactlyOne(list(choice_vars.values()))
            label_anchor_choice[b.name] = choice_vars

    # ── Sub-circuit row ──
    subs = [b for b in scene.boxes if b.column == "sub_circuit"]
    if subs:
        y_first = vars_[subs[0].name][1]
        for s in subs[1:]:
            model.Add(vars_[s.name][1] == y_first)
        sub_gap = model.NewIntVar(60, 600, "sub_gap")
        for a, b in zip(subs, subs[1:]):
            model.Add(vars_[b.name][0] == vars_[a.name][0] + a.w + sub_gap)
        # Above busbar — 부스바에서 위로 분기해 상단 부하로 향한다 (SG 관례).
        if "busbar" in vars_:
            bus_y = vars_["busbar"][1]
            bus_box = next(b for b in scene.boxes if b.name == "busbar")
            model.Add(y_first >= bus_y + bus_box.h + 80)
            # Busbar spans the full sub-circuit row.
            bus_x, _, bus_w_var, _ = vars_["busbar"]
            model.Add(bus_x <= vars_[subs[0].name][0] - 40)
            model.Add(bus_x + bus_w_var >= vars_[subs[-1].name][0] + subs[-1].w + 40)

    # ── Earth bar: 도면 최하단 (spine 최저 섹션인 supply 아래) ──
    earth = next((b for b in scene.boxes if b.name == "earth_bar"), None)
    if earth and subs and spine:
        xe = vars_["earth_bar"][0]
        ye = vars_["earth_bar"][1]
        x_anc = vars_[spine[0].name][0]
        model.Add(2 * xe + earth.w - 2 * x_anc - spine[0].w <= ALIGN_SLACK)
        model.Add(2 * xe + earth.w - 2 * x_anc - spine[0].w >= -ALIGN_SLACK)
        y_spine_bottom = vars_[spine[0].name][1]
        model.Add(ye + earth.h + 60 <= y_spine_bottom)

    # ── Objective: minimise total drawing footprint (height + width). ──
    # Including the horizontal axis prevents the sub-circuit row from
    # drifting against a page margin and dragging the busbar with it.
    max_top = model.NewIntVar(0, page_h, "max_top")
    min_bot = model.NewIntVar(0, page_h, "min_bot")
    max_right = model.NewIntVar(0, page_w, "max_right")
    min_left = model.NewIntVar(0, page_w, "min_left")
    for b in scene.boxes:
        x, y, w_var, _ = vars_[b.name]
        sw = w_var if w_var is not None else b.w
        model.Add(max_top >= y + b.h)
        model.Add(min_bot <= y)
        # x + sw via auxiliary expression
        model.Add(max_right >= x + sw)
        model.Add(min_left <= x)
    model.Minimize((max_top - min_bot) + (max_right - min_left))

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = time_limit_s
    solver.parameters.num_search_workers = 8
    # 멀티워커 탐색은 동비용 최적해 중 어느 것을 고를지 비결정적이다.
    # 드물게 wire가 심볼 본체를 스치는 대체해가 선택될 수 있어(2026-08-09
    # full-suite에서 1회 관측) 시드를 고정해 해 선택 변동성을 줄인다.
    # 완전한 결정성은 workers=1이 필요하지만 속도 손실이 커서 채택하지 않음.
    solver.parameters.random_seed = 20260809
    status_code = solver.Solve(model)
    status_name = solver.StatusName(status_code)

    placements: dict[str, dict] = {}
    if status_code in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        for name, (x, y, w_var, b) in vars_.items():
            placements[name] = {
                "name": name,
                "x": solver.Value(x),
                "y": solver.Value(y),
                "w": solver.Value(w_var) if w_var is not None else b.w,
                "h": b.h,
                "box": b,
            }

    result = SolveResult(
        status=status_name,
        solve_time_s=solver.WallTime(),
        branches=solver.NumBranches(),
        placements=placements,
    )
    if placements:
        result.overlaps = _verify_no_overlap(placements)
        # Record which anchor each label landed on.
        for lbl_name, choices in label_anchor_choice.items():
            for anchor, bv in choices.items():
                if solver.BooleanValue(bv):
                    result.label_anchors[lbl_name] = anchor
                    break
    return result
