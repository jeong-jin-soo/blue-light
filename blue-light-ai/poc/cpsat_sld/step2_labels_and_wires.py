"""CP-SAT SLD PoC — Step 2.

Step 1 placed symbols only. The hard problem in the existing engine is text
collision (root cause C: bounding-box estimation skips multi-line rotated
labels). Step 2 models EVERY label as an independent box and lets the
solver avoid both label↔label and label↔symbol overlap globally.

Additional:
  - 90°-rotated multi-line labels on sub-circuits (the failure case from
    overlap.py:180).
  - Wire connections drawn after solving (single-line traces).
  - Stress: deliberately long labels (B20A/SPN/MCB/6kA Type B) — the case
    that triggers false-negative in the current engine.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

import matplotlib.patches as patches
import matplotlib.pyplot as plt
from ortools.sat.python import cp_model


SCALE = 10
PAGE_W = 420 * SCALE
PAGE_H = 297 * SCALE
MARGIN = 10 * SCALE


@dataclass
class Box:
    name: str
    w: int
    h: int
    role: str           # symbol | label
    parent: str = ""    # for labels: the symbol they describe
    section: int = 0
    column: str = ""
    text: str = ""
    rotated: bool = False  # informational only — w/h already account for rotation


@dataclass
class Wire:
    """Drawn after the solver runs; connects two named boxes."""
    from_name: str
    to_name: str
    from_anchor: str = "center"   # center | top | bottom | left | right
    to_anchor: str = "center"


def build_scenario() -> tuple[list[Box], list[Wire]]:
    boxes: list[Box] = []

    # ── Spine symbols (mm * 10) ─────────────────────────────────
    boxes.append(Box("supply",       260, 80,  "symbol", section=1,  column="spine", text="SUPPLY"))
    boxes.append(Box("incoming_cab", 80,  60,  "symbol", section=2,  column="spine"))
    boxes.append(Box("meter_board",  600, 500, "symbol", section=3,  column="spine", text="METER BOARD"))
    boxes.append(Box("outgoing_cab", 80,  60,  "symbol", section=5,  column="spine"))
    boxes.append(Box("main_breaker", 180, 200, "symbol", section=7,  column="spine"))
    boxes.append(Box("elcb",         180, 200, "symbol", section=9,  column="spine"))
    boxes.append(Box("internal_cab", 80,  60,  "symbol", section=10, column="spine"))
    boxes.append(Box("busbar",       2200,40,  "symbol", section=11, column="spine"))

    # ── Spine labels (placed on the RIGHT of each symbol) ───────
    # Multi-line labels — widest line drives w, h scales with line count.
    boxes.append(Box("supply_lbl",       360, 80, "label", "supply",       1,  "spine_label", "FROM LANDLORD SUPPLY"))
    boxes.append(Box("incoming_cab_lbl", 500, 80, "label", "incoming_cab", 2,  "spine_label", "4x25mm² PVC + 16mm² ECC"))
    boxes.append(Box("outgoing_cab_lbl", 500, 80, "label", "outgoing_cab", 5,  "spine_label", "4x25mm² PVC + 16mm² ECC"))
    boxes.append(Box("main_breaker_lbl", 420, 220,"label", "main_breaker", 7,  "spine_label", "63A TPN MCCB\n10kA Type B\nIc=10kA"))
    boxes.append(Box("elcb_lbl",         420, 200,"label", "elcb",         9,  "spine_label", "63A 4P RCCB\n30mA Type AC"))
    boxes.append(Box("internal_cab_lbl", 500, 80, "label", "internal_cab", 10, "spine_label", "4x16mm² PVC + 10mm² ECC"))
    boxes.append(Box("busbar_lbl",       420, 60, "label", "busbar",       11, "spine_label", "63A 4-WAY BUSBAR"))

    # ── Sub-circuits (8) with rotated multi-line labels ─────────
    SUB_W, SUB_H = 140, 420                  # narrow vertical column
    LBL_W, LBL_H = 90, 360                   # rotated label (text reads bottom-to-top)
    for i in range(1, 9):
        boxes.append(Box(f"sc_{i}", SUB_W, SUB_H, "symbol", section=12, column="sub_circuit"))
        boxes.append(Box(
            f"sc_{i}_lbl", LBL_W, LBL_H, "label",
            parent=f"sc_{i}", section=12, column="sub_label",
            text=f"C{i}\nB20A\nSPN/MCB\n2.5mm²\nLighting",
            rotated=True,
        ))

    # ── Earth bar ───────────────────────────────────────────────
    boxes.append(Box("earth_bar",     800, 40,  "symbol", section=14, column="earth"))
    boxes.append(Box("earth_bar_lbl", 600, 40,  "label",  "earth_bar", 14, "earth_label", "EARTH BAR 35mm² CPC"))

    wires: list[Wire] = [
        Wire("supply", "incoming_cab", "bottom", "top"),
        Wire("incoming_cab", "meter_board", "bottom", "top"),
        Wire("meter_board", "outgoing_cab", "bottom", "top"),
        Wire("outgoing_cab", "main_breaker", "bottom", "top"),
        Wire("main_breaker", "elcb", "bottom", "top"),
        Wire("elcb", "internal_cab", "bottom", "top"),
        Wire("internal_cab", "busbar", "bottom", "top"),
    ]
    for i in range(1, 9):
        wires.append(Wire("busbar", f"sc_{i}", "bottom", "top"))
    return boxes, wires


def solve(boxes: list[Box]):
    model = cp_model.CpModel()
    vars_: dict[str, tuple] = {}
    x_ivs, y_ivs = [], []

    for b in boxes:
        x = model.NewIntVar(MARGIN, PAGE_W - b.w - MARGIN, f"x_{b.name}")
        y = model.NewIntVar(MARGIN, PAGE_H - b.h - MARGIN, f"y_{b.name}")
        xiv = model.NewIntervalVar(x, b.w, x + b.w, f"xiv_{b.name}")
        yiv = model.NewIntervalVar(y, b.h, y + b.h, f"yiv_{b.name}")
        vars_[b.name] = (x, y, b)
        x_ivs.append(xiv)
        y_ivs.append(yiv)

    # Global non-overlap covers EVERYTHING — symbols, labels, rotated labels.
    model.AddNoOverlap2D(x_ivs, y_ivs)

    # Spine section ordering.
    spine_syms = sorted([b for b in boxes if b.column == "spine"],
                        key=lambda b: b.section)
    for a, b in zip(spine_syms, spine_syms[1:]):
        ya = vars_[a.name][1]
        yb = vars_[b.name][1]
        model.Add(ya >= yb + b.h + 50)  # 5 mm min gap

    # Spine alignment — share horizontal center.
    if spine_syms:
        anchor = spine_syms[0]
        x_anc = vars_[anchor.name][0]
        for s in spine_syms[1:]:
            xs = vars_[s.name][0]
            model.Add(2 * xs + s.w == 2 * x_anc + anchor.w)

    # ── Label proximity: each spine label sits to the RIGHT of its symbol,
    #    same vertical band. The solver chooses exact X/Y but with binding
    #    relative constraints, so the label "follows" its symbol.
    for b in boxes:
        if b.role != "label" or not b.parent:
            continue
        if b.column == "spine_label":
            xp, yp, p = vars_[b.parent]
            xl, yl, _ = vars_[b.name]
            # label.x_left = symbol.x_right + small_gap (right of symbol)
            model.Add(xl == xp + p.w + 80)  # 8 mm gap
            # label vertical band overlaps symbol band (label centered on symbol)
            model.Add(yl + b.h // 2 == yp + p.h // 2)
        elif b.column == "sub_label":
            # Rotated label sits directly above its sub-circuit (same x).
            xp, yp, p = vars_[b.parent]
            xl, yl, _ = vars_[b.name]
            # Center label x with symbol x
            model.Add(2 * xl + b.w == 2 * xp + p.w)
            # Label top edge above symbol (label y > symbol y + symbol height)
            model.Add(yl == yp + p.h + 50)
        elif b.column == "earth_label":
            xp, yp, p = vars_[b.parent]
            xl, yl, _ = vars_[b.name]
            model.Add(xl == xp + p.w + 80)
            model.Add(yl + b.h // 2 == yp + p.h // 2)

    # Sub-circuits: same Y, equal spacing, under busbar span.
    subs = [b for b in boxes if b.column == "sub_circuit"]
    if subs:
        y_first = vars_[subs[0].name][1]
        for s in subs[1:]:
            model.Add(vars_[s.name][1] == y_first)
        sub_gap = model.NewIntVar(80, 600, "sub_gap")  # 8..60 mm
        for a, b in zip(subs, subs[1:]):
            model.Add(vars_[b.name][0] == vars_[a.name][0] + a.w + sub_gap)
        bus_x, bus_y, bus = vars_["busbar"]
        model.Add(vars_[subs[0].name][0] >= bus_x)
        model.Add(vars_[subs[-1].name][0] + subs[-1].w <= bus_x + bus.w)
        # Sub-circuits below busbar.
        model.Add(y_first + subs[0].h + 100 <= bus_y)

    # Earth bar at bottom — below the sub-label band.
    earth = next((b for b in boxes if b.name == "earth_bar"), None)
    earth_lbl = next((b for b in boxes if b.name == "earth_bar_lbl"), None)
    sc1_lbl = next((b for b in boxes if b.name == "sc_1_lbl"), None)
    if earth and earth_lbl:
        xe = vars_["earth_bar"][0]
        ye = vars_["earth_bar"][1]
        x_anc = vars_[spine_syms[0].name][0]
        anchor_w = spine_syms[0].w
        model.Add(2 * xe + earth.w == 2 * x_anc + anchor_w)
        # Below all sub-circuits AND sub labels.
        if subs:
            y_sc = vars_[subs[0].name][1]
            model.Add(ye + earth.h + 60 <= y_sc)

    # Objective: minimize vertical footprint (compactness).
    max_top = model.NewIntVar(0, PAGE_H, "max_top")
    min_bot = model.NewIntVar(0, PAGE_H, "min_bot")
    for b in boxes:
        y = vars_[b.name][1]
        model.Add(max_top >= y + b.h)
        model.Add(min_bot <= y)
    model.Minimize(max_top - min_bot)

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = 30.0
    solver.parameters.num_search_workers = 8
    status = solver.Solve(model)

    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        return None, status, solver

    out = {
        name: {"x": solver.Value(x), "y": solver.Value(y),
               "w": b.w, "h": b.h, "spec": b}
        for name, (x, y, b) in vars_.items()
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
    x = box["x"]
    y = box["y"]
    w = box["w"]
    h = box["h"]
    if anchor == "center": return (x + w / 2, y + h / 2)
    if anchor == "top":    return (x + w / 2, y + h)
    if anchor == "bottom": return (x + w / 2, y)
    if anchor == "left":   return (x, y + h / 2)
    if anchor == "right":  return (x + w, y + h / 2)
    return (x + w / 2, y + h / 2)


def render(result, wires, out_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(18, 12))
    ax.set_xlim(0, PAGE_W / SCALE)
    ax.set_ylim(0, PAGE_H / SCALE)
    ax.set_aspect("equal")
    ax.set_title("CP-SAT SLD PoC Step 2 — Symbols + Labels (rotated multi-line) + Wires")
    ax.add_patch(patches.Rectangle(
        (0, 0), PAGE_W / SCALE, PAGE_H / SCALE,
        linewidth=1, edgecolor="black", facecolor="none",
    ))
    palette = {
        "spine":       ("#9ec5e8", "#2e3a47"),
        "sub_circuit": ("#fce8a8", "#5a4a17"),
        "earth":       ("#b8e6b8", "#21462a"),
        "spine_label": ("#e6f0fa", "#34495e"),
        "sub_label":   ("#fff5db", "#5a4a17"),
        "earth_label": ("#dff5e0", "#21462a"),
    }

    # Wires first (so symbols overlay)
    for w in wires:
        a = result[w.from_name]
        b = result[w.to_name]
        (ax1, ay1) = anchor_xy(a, w.from_anchor)
        (ax2, ay2) = anchor_xy(b, w.to_anchor)
        ax.plot([ax1 / SCALE, ax2 / SCALE],
                [ay1 / SCALE, ay2 / SCALE],
                color="#222", linewidth=1.4, zorder=1)

    for r in result.values():
        b = r["spec"]
        face, edge = palette.get(b.column, ("white", "black"))
        ax.add_patch(patches.Rectangle(
            (r["x"] / SCALE, r["y"] / SCALE),
            r["w"] / SCALE, r["h"] / SCALE,
            linewidth=0.7, edgecolor=edge,
            facecolor=face, alpha=0.85, zorder=2,
        ))
        cx = (r["x"] + r["w"] / 2) / SCALE
        cy = (r["y"] + r["h"] / 2) / SCALE
        rotation = 90 if b.rotated else 0
        label = b.text or b.name
        ax.text(cx, cy, label, ha="center", va="center",
                fontsize=5.5, rotation=rotation, zorder=3)

    fig.savefig(out_path, dpi=140, bbox_inches="tight")
    plt.close(fig)


def main() -> int:
    boxes, wires = build_scenario()
    result, status, solver = solve(boxes)
    print(f"Status: {solver.StatusName(status)}")
    print(f"Solve time: {solver.WallTime():.3f}s")
    print(f"Branches:  {solver.NumBranches()}")
    print(f"Box count: {len(boxes)} (symbols + labels)")
    if result is None:
        print("UNSAT / INFEASIBLE")
        return 1
    overlaps = verify_no_overlap(result)
    print(f"Overlap count (independent AABB check): {overlaps}")
    if overlaps:
        return 2
    out = Path(__file__).parent / "step2_output.png"
    render(result, wires, out)
    print(f"Rendered: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
