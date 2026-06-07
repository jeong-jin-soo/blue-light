"""Phase 1 end-to-end demo for the CP-SAT solver.

Builds a representative single-DB requirements dict, invokes
`app.sld.solver.place_layout`, verifies global non-overlap, and renders
the result to PNG so it can be eyeballed.

Usage:
    python scripts/run_solver_demo.py
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path

# Allow running directly: PYTHONPATH=. python scripts/run_solver_demo.py
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.sld.solver.place import place_layout
from app.sld.solver.render import render
from app.sld.solver.scenario import build_scene


def main() -> int:
    logging.basicConfig(level=logging.INFO,
                        format="%(levelname)s %(name)s: %(message)s")

    requirements = {
        "supply_type": "three_phase",
        "main_breaker": {
            "type": "MCCB", "rating": 63, "poles": "TPN",
            "fault_kA": 10, "characteristic": "Type B",
        },
        "elcb": {
            "type": "RCCB", "rating": 63, "poles": "4P",
            "sensitivity_mA": 30,
        },
        "metering": {"type": "sp_meter"},
        "incoming_cable": "4x25mm² PVC + 16mm² ECC",
        "internal_cable": "4x16mm² PVC + 10mm² ECC",
        "supply_label": "FROM LANDLORD SUPPLY",
        "meter_label": "KWH METER BY SP",
        "earth_label": "EARTH BAR 35mm² CPC",
        "sub_circuits": [
            {"id": "C1", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Lighting"},
            {"id": "C2", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Lighting"},
            {"id": "C3", "type": "MCB", "rating": 32, "poles": "SPN",
             "cable": "6mm²", "load": "Aircon"},
            {"id": "C4", "type": "MCB", "rating": 32, "poles": "SPN",
             "cable": "6mm²", "load": "Aircon"},
            {"id": "C5", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Socket"},
            {"id": "C6", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Socket"},
            {"id": "C7", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "WaterHeater"},
            {"id": "C8", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "SPARE"},
        ],
    }

    scene = build_scene(requirements)
    print(f"Boxes built: {len(scene.boxes)}")

    result = place_layout(scene, time_limit_s=15.0)
    print(f"Status:     {result.status}")
    print(f"Solve time: {result.solve_time_s:.3f}s")
    print(f"Branches:   {result.branches}")
    print(f"Placed:     {len(result.placements)}")
    print(f"Overlaps:   {result.overlaps}")

    if not result.ok:
        print("Solver failed.")
        return 1

    if result.label_anchors:
        print("Label anchor choices:")
        for name, anchor in sorted(result.label_anchors.items()):
            print(f"  {name:20s} → {anchor}")

    out = Path(__file__).resolve().parent.parent / "poc" / "cpsat_sld" / "phase1_demo.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    render(scene, result,
           out_path=out,
           title="Phase 1 — solver layout (63A TPN SP-meter, 8 circuits)")
    print(f"Rendered: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
