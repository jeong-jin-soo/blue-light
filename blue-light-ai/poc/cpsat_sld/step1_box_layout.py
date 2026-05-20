"""CP-SAT SLD PoC — Step 1.

Goal: prove that OR-tools CP-SAT can place SLD components without overlap
while respecting Singapore 14-section flow order.

Scenario: 63A TPN SP Meter with 8 sub-circuits.

Convention:
  - Page Y axis increases upward (matplotlib default).
  - 14-section ordering: section 1 (supply) at top of page → section 12
    (sub-circuits) at bottom. Higher section number => lower Y.
  - Earth bar drawn last (lowest Y), separated from spine column.

Outputs:
  - PNG render of solver result.
  - Console: solve time, branches, overlap-check, achieved compactness.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import matplotlib.patches as patches
import matplotlib.pyplot as plt
from ortools.sat.python import cp_model


# ---------------------------------------------------------------------------
# Scale: 1 unit = 0.1 mm. A3 landscape = 4200 x 2970 units.
# ---------------------------------------------------------------------------
SCALE = 10
PAGE_W = 420 * SCALE
PAGE_H = 297 * SCALE
MARGIN = 10 * SCALE


@dataclass
class CompSpec:
    name: str
    w: int          # width in scaled units
    h: int          # height in scaled units
    section: int    # 1..14 (Singapore SLD section flow)
    column: str     # spine | sub_circuit | earth
    text: str = ""


def build_spec() -> list[CompSpec]:
    """63A TPN SP Meter — 8 sub-circuits."""
    specs: list[CompSpec] = [
        CompSpec("supply",        300, 80,  1,  "spine", "FROM LANDLORD SUPPLY"),
        CompSpec("incoming_cab",  400, 60,  2,  "spine", "4x25mm² + 16E"),
        CompSpec("meter_board",   600, 500, 3,  "spine", "ISO → KWH → MCB"),
        CompSpec("outgoing_cab",  400, 60,  5,  "spine", "4x25mm² + 16E"),
        CompSpec("main_breaker",  220, 220, 7,  "spine", "63A TPN MCCB"),
        CompSpec("elcb",          220, 220, 9,  "spine", "63A RCCB 300mA"),
        CompSpec("internal_cab",  400, 60,  10, "spine", "4x16mm²"),
        CompSpec("busbar",        2200, 40, 11, "spine", "63A 4-WAY BUSBAR"),
    ]
    for i in range(1, 9):
        specs.append(CompSpec(f"sc_{i}", 160, 400, 12, "sub_circuit", f"C{i}: 20A SP MCB"))
    specs.append(CompSpec("earth_bar", 800, 40, 14, "earth", "EARTH BAR 35mm² CPC"))
    return specs


def solve(specs: list[CompSpec]):
    model = cp_model.CpModel()

    boxes: dict[str, tuple] = {}
    x_intervals = []
    y_intervals = []

    for s in specs:
        x = model.NewIntVar(MARGIN, PAGE_W - s.w - MARGIN, f"x_{s.name}")
        y = model.NewIntVar(MARGIN, PAGE_H - s.h - MARGIN, f"y_{s.name}")
        xiv = model.NewIntervalVar(x, s.w, x + s.w, f"xiv_{s.name}")
        yiv = model.NewIntervalVar(y, s.h, y + s.h, f"yiv_{s.name}")
        boxes[s.name] = (x, y, s)
        x_intervals.append(xiv)
        y_intervals.append(yiv)

    # Global non-overlap (mathematical guarantee).
    model.AddNoOverlap2D(x_intervals, y_intervals)

    # Section ordering: higher section# => lower Y (supply at top of page).
    spine_specs = [s for s in specs if s.column == "spine"]
    spine_sorted = sorted(spine_specs, key=lambda s: s.section)
    SECTION_GAP = 50  # 5 mm minimum gap between spine components
    for a, b in zip(spine_sorted, spine_sorted[1:]):
        ya = boxes[a.name][1]
        yb = boxes[b.name][1]
        # a has lower section# (higher on page); b is below it.
        model.Add(ya >= yb + b.h + SECTION_GAP)

    # Spine alignment: all spine boxes share horizontal center.
    if spine_sorted:
        anchor = spine_sorted[0]
        x_anchor = boxes[anchor.name][0]
        for s in spine_sorted[1:]:
            xs = boxes[s.name][0]
            # 2x + w == 2*xa + wa  ⇒ shared center.
            model.Add(2 * xs + s.w == 2 * x_anchor + anchor.w)

    # Sub-circuits: same row (same Y), equal X spacing, attached below busbar.
    sub_specs = [s for s in specs if s.column == "sub_circuit"]
    if sub_specs:
        y_first = boxes[sub_specs[0].name][1]
        for s in sub_specs[1:]:
            model.Add(boxes[s.name][1] == y_first)

        sub_gap = model.NewIntVar(40, 400, "sub_gap")
        for a, b in zip(sub_specs, sub_specs[1:]):
            xa = boxes[a.name][0]
            xb = boxes[b.name][0]
            model.Add(xb == xa + a.w + sub_gap)

        # Sub-circuit row must fit under busbar span.
        bus_x, bus_y, bus_spec = boxes["busbar"]
        model.Add(boxes[sub_specs[0].name][0] >= bus_x)
        model.Add(
            boxes[sub_specs[-1].name][0] + sub_specs[-1].w
            <= bus_x + bus_spec.w,
        )
        # Sub-circuits below busbar (their Y < busbar.Y).
        model.Add(y_first + sub_specs[0].h + 40 <= bus_y)

    # Earth bar: bottom of the diagram, centered horizontally.
    earth = next((s for s in specs if s.column == "earth"), None)
    if earth:
        xe = boxes[earth.name][0]
        ye = boxes[earth.name][1]
        # Center align horizontally with spine.
        x_anchor_var = boxes[spine_sorted[0].name][0]
        anchor_w = spine_sorted[0].w
        model.Add(2 * xe + earth.w == 2 * x_anchor_var + anchor_w)
        # Below all sub-circuits.
        y_first = boxes[sub_specs[0].name][1]
        model.Add(ye + earth.h + 80 <= y_first)

    # Objective: minimize total vertical footprint
    max_top = model.NewIntVar(0, PAGE_H, "max_top")
    min_bottom = model.NewIntVar(0, PAGE_H, "min_bottom")
    for s in specs:
        y = boxes[s.name][1]
        model.Add(max_top >= y + s.h)
        model.Add(min_bottom <= y)
    model.Minimize(max_top - min_bottom)

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = 15.0
    solver.parameters.num_search_workers = 4
    status = solver.Solve(model)

    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        return None, status, solver

    result = {
        name: {
            "x": solver.Value(x), "y": solver.Value(y),
            "w": s.w, "h": s.h, "spec": s,
        }
        for name, (x, y, s) in boxes.items()
    }
    return result, status, solver


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


def render(result, out_path: Path) -> None:
    fig, ax = plt.subplots(figsize=(16, 11))
    ax.set_xlim(0, PAGE_W / SCALE)
    ax.set_ylim(0, PAGE_H / SCALE)
    ax.set_aspect("equal")
    ax.set_title("CP-SAT SLD PoC Step 1 — 63A TPN SP Meter, 8 sub-circuits")
    ax.add_patch(patches.Rectangle(
        (0, 0), PAGE_W / SCALE, PAGE_H / SCALE,
        linewidth=1, edgecolor="black", facecolor="none",
    ))
    palette = {"spine": "#9ec5e8", "sub_circuit": "#fce8a8", "earth": "#b8e6b8"}
    for r in result.values():
        s = r["spec"]
        ax.add_patch(patches.Rectangle(
            (r["x"] / SCALE, r["y"] / SCALE),
            r["w"] / SCALE, r["h"] / SCALE,
            linewidth=1, edgecolor="black",
            facecolor=palette.get(s.column, "white"), alpha=0.7,
        ))
        ax.text(
            (r["x"] + r["w"] / 2) / SCALE,
            (r["y"] + r["h"] / 2) / SCALE,
            f"{s.name}\n{s.text}",
            ha="center", va="center", fontsize=6,
        )
    fig.savefig(out_path, dpi=120, bbox_inches="tight")
    plt.close(fig)


def main() -> int:
    specs = build_spec()
    result, status, solver = solve(specs)
    print(f"Status: {solver.StatusName(status)}")
    print(f"Solve time: {solver.WallTime():.3f}s")
    print(f"Branches:  {solver.NumBranches()}")
    if result is None:
        print("UNSAT / INFEASIBLE — constraint formulation needs revision.")
        return 1
    print(f"Components placed: {len(result)}")
    overlaps = verify_no_overlap(result)
    print(f"Overlap count (independent AABB check): {overlaps}")
    if overlaps:
        print("FAIL: overlaps present despite NoOverlap2D — bug in modeling.")
        return 2
    out = Path(__file__).parent / "step1_output.png"
    render(result, out)
    print(f"Rendered: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
