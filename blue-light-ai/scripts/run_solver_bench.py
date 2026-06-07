"""Solver benchmark across scenario sizes.

Runs the solver on a fixed schema with varying circuit counts and metering
types, prints a table of solve time vs problem size.  Useful for finding
the practical limit before search-space explosion.
"""

from __future__ import annotations

import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.sld.solver.place import place_layout
from app.sld.solver.scenario import build_scene


def make_circuit(i: int) -> dict:
    return {
        "id": f"C{i}", "type": "MCB",
        "rating": 20 if i % 3 else 32,
        "poles": "SPN", "cable": "2.5mm²" if i % 3 else "6mm²",
        "load": ["Lighting", "Socket", "Aircon"][i % 3],
    }


def run_case(metering: str, circuits: int, page_size: str = "A3") -> dict:
    req = {
        "supply_type": "three_phase",
        "page": {"size": page_size},
        "main_breaker": {"type": "MCCB", "rating": 200 if metering == "ct_meter" else 63,
                         "poles": "TPN", "fault_kA": 25, "characteristic": "B"},
        "elcb": {"type": "RCCB", "rating": 200 if metering == "ct_meter" else 63,
                 "poles": "4P", "sensitivity_mA": 100 if metering == "ct_meter" else 30},
        "metering": {"type": metering},
        "incoming_cable": "4x25mm² PVC + 16mm² ECC",
        "internal_cable": "4x16mm² PVC + 10mm² ECC",
        "sub_circuits": [make_circuit(i) for i in range(1, circuits + 1)],
    }
    scene = build_scene(req)
    t0 = time.perf_counter()
    result = place_layout(scene, time_limit_s=60.0)
    wall = time.perf_counter() - t0
    return {
        "metering": metering, "circuits": circuits, "page": page_size,
        "boxes": len(scene.boxes),
        "status": result.status,
        "solve_time": result.solve_time_s,
        "wall_time": wall,
        "branches": result.branches,
        "overlaps": result.overlaps,
    }


def main() -> int:
    rows = []
    cases = [
        ("sp_meter",  4,  "A3"),
        ("sp_meter",  8,  "A3"),
        ("sp_meter",  16, "A2"),
        ("sp_meter",  24, "A2"),
        ("ct_meter",  8,  "A2"),
        ("ct_meter",  12, "A2"),
        ("ct_meter",  18, "A2"),
        ("ct_meter",  24, "A2"),
        ("non_meter", 8,  "A3"),
    ]
    print(f"{'metering':<10} {'N':>3} {'page':<4} {'boxes':>5} "
          f"{'status':<10} {'solve_s':>8} {'wall_s':>8} {'branches':>10} {'overlaps':>8}")
    print("-" * 80)
    for m, n, p in cases:
        r = run_case(m, n, p)
        rows.append(r)
        print(f"{r['metering']:<10} {r['circuits']:>3} {r['page']:<4} "
              f"{r['boxes']:>5} {r['status']:<10} "
              f"{r['solve_time']:>8.3f} {r['wall_time']:>8.3f} "
              f"{r['branches']:>10} {r['overlaps']:>8}")

    bad = [r for r in rows if r["status"] not in ("OPTIMAL", "FEASIBLE") or r["overlaps"] != 0]
    if bad:
        print(f"\nFAILURES: {len(bad)}")
        return 1
    slow = [r for r in rows if r["solve_time"] > 10.0]
    if slow:
        print(f"\nSLOW (>10s): {len(slow)} — performance work needed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
