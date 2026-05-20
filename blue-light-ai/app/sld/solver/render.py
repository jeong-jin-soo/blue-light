"""Lightweight matplotlib renderer for solver output.

Phase 1 deliberately ships a SEPARATE renderer (rather than reusing the
DXF backend) so the solver's geometry can be evaluated visually without
touching legacy code.  Phase 2/3 will replace this with a real DXF/SVG
backend that uses the catalog's symbol definitions.
"""

from __future__ import annotations

from pathlib import Path

import matplotlib.patches as patches
import matplotlib.pyplot as plt

from app.sld.solver.boxes import BoxRole, SolverScene, UNIT_PER_MM
from app.sld.solver.place import SolveResult


_PALETTE = {
    "spine":        ("#9ec5e8", "#2e3a47"),
    "sub_circuit":  ("#fce8a8", "#5a4a17"),
    "earth":        ("#b8e6b8", "#21462a"),
    "spine_label":  ("#e6f0fa", "#34495e"),
    "busbar_label": ("#e6f0fa", "#34495e"),
    "sub_label":    ("#fff5db", "#5a4a17"),
    "earth_label":  ("#dff5e0", "#21462a"),
}


def render(scene: SolverScene, result: SolveResult, out_path: Path,
           *, title: str = "CP-SAT Solver Layout") -> None:
    page_w_mm = scene.page_w / UNIT_PER_MM
    page_h_mm = scene.page_h / UNIT_PER_MM

    fig, ax = plt.subplots(figsize=(page_w_mm / 30, page_h_mm / 30))
    ax.set_xlim(0, page_w_mm)
    ax.set_ylim(0, page_h_mm)
    ax.set_aspect("equal")
    ax.set_title(title)
    ax.add_patch(patches.Rectangle(
        (0, 0), page_w_mm, page_h_mm,
        linewidth=1, edgecolor="black", facecolor="none",
    ))

    # Spine wires (busbar drop ↔ each sub-circuit).
    if "busbar" in result.placements:
        bus = result.placements["busbar"]
        bus_bottom = bus["y"] / UNIT_PER_MM
        for r in result.placements.values():
            b = r["box"]
            if b.column != "sub_circuit":
                continue
            sc_top_y = (r["y"] + r["h"]) / UNIT_PER_MM
            sc_cx = (r["x"] + r["w"] / 2) / UNIT_PER_MM
            ax.plot([sc_cx, sc_cx], [sc_top_y, bus_bottom],
                    color="#222", linewidth=1.2, zorder=1.5)

    # Spine sequential wires (covers SP-meter, CT-meter, non-meter paths).
    spine_names = [
        "supply", "incoming_cab",
        "meter_board",     # SP-meter
        "unit_isolator",   # non-meter
        "outgoing_cab",    # SP-meter
        "ct_pre_fuse",     # CT-meter
        "main_breaker",
        "ct_metering",     # CT-meter
        "elcb", "internal_cab", "busbar",
    ]
    chain = [n for n in spine_names if n in result.placements]
    for a, b in zip(chain, chain[1:]):
        ra = result.placements[a]
        rb = result.placements[b]
        x1 = (ra["x"] + ra["w"] / 2) / UNIT_PER_MM
        y1 = ra["y"] / UNIT_PER_MM
        x2 = (rb["x"] + rb["w"] / 2) / UNIT_PER_MM
        y2 = (rb["y"] + rb["h"]) / UNIT_PER_MM
        ax.plot([x1, x2], [y1, y2], color="#222", linewidth=1.4, zorder=2)

    for r in result.placements.values():
        b = r["box"]
        face, edge = _PALETTE.get(b.column, ("white", "black"))
        ax.add_patch(patches.Rectangle(
            (r["x"] / UNIT_PER_MM, r["y"] / UNIT_PER_MM),
            r["w"] / UNIT_PER_MM, r["h"] / UNIT_PER_MM,
            linewidth=0.7, edgecolor=edge, facecolor=face, alpha=0.85, zorder=3,
        ))
        cx = (r["x"] + r["w"] / 2) / UNIT_PER_MM
        cy = (r["y"] + r["h"] / 2) / UNIT_PER_MM
        rotation = 90 if b.rotated else 0
        text = b.text or b.name
        ax.text(cx, cy, text, ha="center", va="center",
                fontsize=5, rotation=rotation, zorder=4)

    fig.savefig(out_path, dpi=150, bbox_inches="tight")
    plt.close(fig)
