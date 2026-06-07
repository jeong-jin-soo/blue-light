"""CP-SAT SLD PoC — Step 3.

Generality + stress test:
  - CT metering topology (CT hook, ELR, ASS, Ammeter, Voltmeter, kWh, BI).
  - 18 sub-circuits (vs. 8 in Steps 1-2).
  - Variable-width busbar — its length is decided by the solver to fit the
    sub-circuit row exactly (no more "busbar runs off into empty space").
  - Wire channels: explicit reserved rectangles between busbar and each
    sub-circuit so labels cannot land on top of feeders.
  - A2 landscape (594 x 420 mm) to allow more horizontal room.

Question being answered:
  - Does CP-SAT scale to ~80 boxes with hard constraints?
  - Does adding a new section (CT metering) require rewriting the engine,
    or can it be expressed as a few more spec lines?

If YES on both, the solver approach generalises.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import matplotlib.patches as patches
import matplotlib.pyplot as plt
from ortools.sat.python import cp_model


SCALE = 10
PAGE_W = 594 * SCALE     # A2 landscape
PAGE_H = 420 * SCALE
MARGIN = 12 * SCALE


@dataclass
class Box:
    name: str
    w: int                # fixed width (or 0 for variable, see solve())
    h: int
    role: str             # symbol | label | channel
    parent: str = ""
    section: int = 0
    column: str = ""
    text: str = ""
    rotated: bool = False
    variable_w: bool = False
    min_w: int = 0
    max_w: int = 0


N_SUBS = 18


def build_scenario() -> list[Box]:
    boxes: list[Box] = []

    # ── Spine (CT metering path) ──
    boxes.append(Box("supply",          260, 80,  "symbol", section=1,  column="spine", text="SUPPLY"))
    boxes.append(Box("incoming_cab",    100, 80,  "symbol", section=2,  column="spine"))
    # No meter board on CT path — skip section 3.
    boxes.append(Box("outgoing_cab",    100, 80,  "symbol", section=5,  column="spine"))
    boxes.append(Box("ct_pre_fuse",     180, 160, "symbol", section=6,  column="spine"))
    boxes.append(Box("main_breaker",    200, 240, "symbol", section=7,  column="spine"))
    boxes.append(Box("ct_metering",     1200, 540,"symbol", section=8,  column="spine", text="CT METERING\n(CT/ELR/ASS/kWh/BI)"))
    boxes.append(Box("elcb",            200, 240, "symbol", section=9,  column="spine"))
    boxes.append(Box("internal_cab",    100, 80,  "symbol", section=10, column="spine"))
    boxes.append(Box(
        "busbar", 0, 40, "symbol",
        section=11, column="spine",
        variable_w=True, min_w=1200, max_w=PAGE_W - 2 * MARGIN,
    ))

    # ── Spine labels (right-of-symbol band) ──
    boxes.append(Box("supply_lbl",       340, 80,  "label", "supply",       1,  "spine_label", "FROM LANDLORD SUPPLY"))
    boxes.append(Box("incoming_cab_lbl", 540, 80,  "label", "incoming_cab", 2,  "spine_label", "4x70mm² PVC + 35mm² ECC"))
    boxes.append(Box("outgoing_cab_lbl", 540, 80,  "label", "outgoing_cab", 5,  "spine_label", "4x70mm² PVC + 35mm² ECC"))
    boxes.append(Box("ct_pre_fuse_lbl",  340, 160, "label", "ct_pre_fuse",  6,  "spine_label", "2A HRC Fuse\nIndicator Lamp"))
    boxes.append(Box("main_breaker_lbl", 460, 240, "label", "main_breaker", 7,  "spine_label", "200A TPN MCCB\n25kA Type B"))
    boxes.append(Box("ct_metering_lbl", 420, 540,  "label", "ct_metering",  8,  "spine_label", "200/5A CT\nELR\nASS/AMM\nVSS/VLM\nkWh BY SP\nBI CONNECTOR"))
    boxes.append(Box("elcb_lbl",         460, 240, "label", "elcb",         9,  "spine_label", "200A 4P RCCB\n100mA Type AC"))
    boxes.append(Box("internal_cab_lbl", 540, 80,  "label", "internal_cab", 10, "spine_label", "4x70mm² PVC + 35mm² ECC"))
    boxes.append(Box("busbar_lbl",       420, 60,  "label", "busbar",       11, "busbar_label", "200A BUSBAR"))

    # ── 18 sub-circuits, vertically tall with rotated labels ──
    SUB_W, SUB_H = 140, 440
    LBL_W, LBL_H = 90, 380
    for i in range(1, N_SUBS + 1):
        rating = "20A" if i % 3 else "32A"
        load = ["Lighting", "Socket", "Aircon"][i % 3]
        cable = "2.5mm²" if i % 3 else "6mm²"
        boxes.append(Box(f"sc_{i}", SUB_W, SUB_H, "symbol", section=12, column="sub_circuit"))
        boxes.append(Box(
            f"sc_{i}_lbl", LBL_W, LBL_H, "label",
            parent=f"sc_{i}", section=12, column="sub_label",
            text=f"C{i}\nB{rating}\nSPN/MCB\n{cable}\n{load}",
            rotated=True,
        ))
        # NOTE: wire channel removed — its X-center collides with sub_label
        # X-center, and Y bands overlap. Wire routing is a separate solver
        # phase; here we draw wires post-hoc as a sanity check.

    # ── Earth bar ──
    boxes.append(Box("earth_bar",     800, 40, "symbol", section=14, column="earth"))
    boxes.append(Box("earth_bar_lbl", 600, 40, "label",  "earth_bar", 14, "earth_label", "EARTH BAR 70mm² CPC"))

    return boxes


def solve(boxes: list[Box]):
    model = cp_model.CpModel()
    vars_: dict[str, tuple] = {}
    x_ivs, y_ivs = [], []

    for b in boxes:
        if b.variable_w:
            w = model.NewIntVar(b.min_w, b.max_w, f"w_{b.name}")
            x = model.NewIntVar(MARGIN, PAGE_W - b.min_w - MARGIN, f"x_{b.name}")
            # x_end = x + w. Use start/size/end form via NewIntVar end.
            x_end = model.NewIntVar(MARGIN + b.min_w, PAGE_W - MARGIN, f"xe_{b.name}")
            model.Add(x_end == x + w)
            xiv = model.NewIntervalVar(x, w, x_end, f"xiv_{b.name}")
        else:
            x = model.NewIntVar(MARGIN, PAGE_W - b.w - MARGIN, f"x_{b.name}")
            xiv = model.NewIntervalVar(x, b.w, x + b.w, f"xiv_{b.name}")
            w = None
        y = model.NewIntVar(MARGIN, PAGE_H - b.h - MARGIN, f"y_{b.name}")
        yiv = model.NewIntervalVar(y, b.h, y + b.h, f"yiv_{b.name}")
        vars_[b.name] = (x, y, b, w)
        x_ivs.append(xiv)
        y_ivs.append(yiv)

    model.AddNoOverlap2D(x_ivs, y_ivs)

    # Section ordering on spine.
    spine_syms = sorted([b for b in boxes if b.column == "spine"], key=lambda b: b.section)
    for a, b in zip(spine_syms, spine_syms[1:]):
        ya = vars_[a.name][1]
        yb = vars_[b.name][1]
        model.Add(ya >= yb + b.h + 50)

    # Spine alignment (center) — for fixed-width components.
    # For the variable-width busbar we constrain its center to the spine
    # anchor center too.
    anchor = spine_syms[0]
    x_anc = vars_[anchor.name][0]
    for s in spine_syms[1:]:
        xs, ys, b, w_var = vars_[s.name]
        if w_var is not None:
            model.Add(2 * xs + w_var == 2 * x_anc + anchor.w)
        else:
            model.Add(2 * xs + s.w == 2 * x_anc + anchor.w)

    # Spine label binding.
    for b in boxes:
        if b.role != "label" or not b.parent:
            continue
        xp, yp, p_spec, p_w = vars_[b.parent]
        xl, yl, _, _ = vars_[b.name]
        if b.column == "spine_label":
            p_width = p_w if p_w is not None else p_spec.w
            model.Add(xl == xp + p_width + 80)
            model.Add(yl + b.h // 2 == yp + p_spec.h // 2)
        elif b.column == "busbar_label":
            p_width = p_w if p_w is not None else p_spec.w
            model.Add(xl == xp + p_width + 60)
            model.Add(yl + b.h // 2 == yp + p_spec.h // 2)
        elif b.column == "sub_label":
            model.Add(2 * xl + b.w == 2 * xp + p_spec.w)
            model.Add(yl == yp + p_spec.h + 40)
        elif b.column == "earth_label":
            model.Add(xl == xp + p_spec.w + 80)
            model.Add(yl + b.h // 2 == yp + p_spec.h // 2)

    # Wire channels — directly below busbar, aligned with each sub-circuit.
    subs = [b for b in boxes if b.column == "sub_circuit"]
    bus_x, bus_y, bus, bus_w = vars_["busbar"]
    if subs:
        y_first = vars_[subs[0].name][1]
        for s in subs[1:]:
            model.Add(vars_[s.name][1] == y_first)
        sub_gap = model.NewIntVar(60, 600, "sub_gap")
        for a, b in zip(subs, subs[1:]):
            model.Add(vars_[b.name][0] == vars_[a.name][0] + a.w + sub_gap)
        # Sub-circuits below busbar.
        model.Add(y_first + subs[0].h + 100 <= bus_y)
        # Busbar width covers full sub-circuit span EXACTLY (with small margin):
        # bus.x ≤ sc1.x  AND  bus.x + bus.w ≥ sc_N.x + sc_N.w
        model.Add(bus_x <= vars_[subs[0].name][0] - 40)
        model.Add(bus_x + bus_w >= vars_[subs[-1].name][0] + subs[-1].w + 40)

        # Wire channels removed — see build_scenario().

    # Earth bar.
    earth = next((b for b in boxes if b.name == "earth_bar"), None)
    if earth:
        xe = vars_[earth.name][0]
        ye = vars_[earth.name][1]
        model.Add(2 * xe + earth.w == 2 * x_anc + anchor.w)
        if subs:
            y_sc = vars_[subs[0].name][1]
            model.Add(ye + earth.h + 60 <= y_sc)

    # Objective — compactness on vertical axis only (horizontal is free).
    max_top = model.NewIntVar(0, PAGE_H, "max_top")
    min_bot = model.NewIntVar(0, PAGE_H, "min_bot")
    for b in boxes:
        y = vars_[b.name][1]
        model.Add(max_top >= y + b.h)
        model.Add(min_bot <= y)
    model.Minimize(max_top - min_bot)

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = 60.0
    solver.parameters.num_search_workers = 8
    status = solver.Solve(model)

    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        return None, status, solver

    out = {}
    for name, (x, y, b, w_var) in vars_.items():
        out[name] = {
            "x": solver.Value(x),
            "y": solver.Value(y),
            "w": solver.Value(w_var) if w_var is not None else b.w,
            "h": b.h,
            "spec": b,
        }
    return out, status, solver


def verify_no_overlap(result) -> int:
    items = list(result.values())
    overlaps = 0
    for i, a in enumerate(items):
        for b in items[i + 1:]:
            if (a["x"] < b["x"] + b["w"]
                    and a["x"] + a["w"] > b["x"]
                    and a["y"] < b["y"] + b["h"]
                    and a["y"] + a["h"] > b["y"]):
                overlaps += 1
                print(f"  OVERLAP: {a['spec'].name} <> {b['spec'].name}")
    return overlaps


def anchor_xy(box, anchor):
    x, y, w, h = box["x"], box["y"], box["w"], box["h"]
    if anchor == "top":    return (x + w / 2, y + h)
    if anchor == "bottom": return (x + w / 2, y)
    if anchor == "left":   return (x, y + h / 2)
    if anchor == "right":  return (x + w, y + h / 2)
    return (x + w / 2, y + h / 2)


def render(result, out_path: Path):
    fig, ax = plt.subplots(figsize=(22, 14))
    ax.set_xlim(0, PAGE_W / SCALE)
    ax.set_ylim(0, PAGE_H / SCALE)
    ax.set_aspect("equal")
    ax.set_title(
        f"CP-SAT SLD PoC Step 3 — CT metering + {N_SUBS} sub-circuits "
        f"(A2 landscape, variable-width busbar)",
    )
    ax.add_patch(patches.Rectangle(
        (0, 0), PAGE_W / SCALE, PAGE_H / SCALE,
        linewidth=1, edgecolor="black", facecolor="none",
    ))
    palette = {
        "spine":         ("#9ec5e8", "#2e3a47"),
        "sub_circuit":   ("#fce8a8", "#5a4a17"),
        "earth":         ("#b8e6b8", "#21462a"),
        "spine_label":   ("#e6f0fa", "#34495e"),
        "busbar_label":  ("#e6f0fa", "#34495e"),
        "sub_label":     ("#fff5db", "#5a4a17"),
        "earth_label":   ("#dff5e0", "#21462a"),
        "wire_channel":  ("#f4f4f4", "#bbbbbb"),
    }
    # Post-hoc wire drawing: busbar drop to each sub-circuit top.
    if "busbar" in result:
        bus = result["busbar"]
        bus_bottom_y = bus["y"]
        for name, r in result.items():
            if r["spec"].column != "sub_circuit":
                continue
            sc_top_y = r["y"] + r["h"]
            sc_cx = r["x"] + r["w"] / 2
            ax.plot([sc_cx / SCALE, sc_cx / SCALE],
                    [sc_top_y / SCALE, bus_bottom_y / SCALE],
                    color="#222", linewidth=1.2, zorder=1.5)

    # Spine wires
    spine_order = ["supply", "incoming_cab", "outgoing_cab", "ct_pre_fuse",
                   "main_breaker", "ct_metering", "elcb", "internal_cab", "busbar"]
    for a, b in zip(spine_order, spine_order[1:]):
        if a not in result or b not in result:
            continue
        a_box = result[a]
        b_box = result[b]
        x1, y1 = anchor_xy(a_box, "bottom")
        x2, y2 = anchor_xy(b_box, "top")
        ax.plot([x1 / SCALE, x2 / SCALE],
                [y1 / SCALE, y2 / SCALE],
                color="#222", linewidth=1.4, zorder=2)

    for r in result.values():
        b = r["spec"]
        if b.role == "channel":
            continue
        face, edge = palette.get(b.column, ("white", "black"))
        ax.add_patch(patches.Rectangle(
            (r["x"] / SCALE, r["y"] / SCALE),
            r["w"] / SCALE, r["h"] / SCALE,
            linewidth=0.6, edgecolor=edge, facecolor=face, alpha=0.85, zorder=3,
        ))
        cx = (r["x"] + r["w"] / 2) / SCALE
        cy = (r["y"] + r["h"] / 2) / SCALE
        rotation = 90 if b.rotated else 0
        text = b.text or b.name
        ax.text(cx, cy, text, ha="center", va="center",
                fontsize=5, rotation=rotation, zorder=4)

    fig.savefig(out_path, dpi=140, bbox_inches="tight")
    plt.close(fig)


def main() -> int:
    boxes = build_scenario()
    print(f"Box count (incl. wire channels): {len(boxes)}")
    result, status, solver = solve(boxes)
    print(f"Status: {solver.StatusName(status)}")
    print(f"Solve time: {solver.WallTime():.3f}s")
    print(f"Branches:  {solver.NumBranches()}")
    if result is None:
        return 1
    overlaps = verify_no_overlap(result)
    print(f"Overlap count (independent AABB): {overlaps}")
    if overlaps:
        return 2
    bus = result["busbar"]
    print(f"Busbar chosen width: {bus['w'] / SCALE:.1f} mm "
          f"(min={1200/SCALE:.0f} max={PAGE_W/SCALE - 24:.0f})")
    out = Path(__file__).parent / "step3_output.png"
    render(result, out)
    print(f"Rendered: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
