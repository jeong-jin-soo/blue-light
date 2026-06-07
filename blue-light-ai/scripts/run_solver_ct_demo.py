"""Phase 2-B demo: CT metering scenario.

Same solver, different requirements — proves the metering branch is data,
not engine code.
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.sld.solver.place import place_layout
from app.sld.solver.render import render
from app.sld.solver.scenario import build_scene


def main() -> int:
    logging.basicConfig(level=logging.INFO,
                        format="%(levelname)s %(name)s: %(message)s")

    requirements = {
        "supply_type": "three_phase",
        "page": {"size": "A2"},
        "main_breaker": {
            "type": "MCCB", "rating": 200, "poles": "TPN",
            "fault_kA": 25, "characteristic": "Type B",
        },
        "elcb": {
            "type": "RCCB", "rating": 200, "poles": "4P",
            "sensitivity_mA": 100,
        },
        "metering": {"type": "ct_meter"},
        "incoming_cable": "4x70mm² PVC + 35mm² ECC",
        "internal_cable": "4x70mm² PVC + 35mm² ECC",
        "supply_label": "FROM LANDLORD SUPPLY",
        "ct_metering_label": ("200/5A CT\nELR\nASS / AMM\nVSS / VLM\n"
                              "kWh BY SP\nBI CONNECTOR"),
        "earth_label": "EARTH BAR 70mm² CPC",
        "sub_circuits": [
            {"id": f"C{i}", "type": "MCB",
             "rating": 20 if i % 3 else 32,
             "poles": "SPN", "cable": "2.5mm²" if i % 3 else "6mm²",
             "load": ["Lighting", "Socket", "Aircon"][i % 3]}
            for i in range(1, 13)   # 12 circuits
        ],
    }

    scene = build_scene(requirements)
    print(f"Boxes built: {len(scene.boxes)}")

    result = place_layout(scene, time_limit_s=30.0)
    print(f"Status:     {result.status}")
    print(f"Solve time: {result.solve_time_s:.3f}s")
    print(f"Branches:   {result.branches}")
    print(f"Placed:     {len(result.placements)}")
    print(f"Overlaps:   {result.overlaps}")

    if not result.ok:
        print("Solver failed.")
        return 1

    if result.label_anchors:
        print("Label anchors:")
        for name, anchor in sorted(result.label_anchors.items()):
            print(f"  {name:20s} → {anchor}")

    out = Path(__file__).resolve().parent.parent / "poc" / "cpsat_sld" / "phase2_ct_demo.png"
    render(scene, result, out_path=out,
           title="Phase 2 — CT metering (200A TPN, 12 sub-circuits)")
    print(f"Rendered: {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
