#!/usr/bin/env python3
"""Generate 9-case comparison matrix: 3 SLD types × 3 circuit counts.

Validates Phase 3 reference matcher integration by generating SLDs and
reporting the matched reference profile + spacing applied.
"""

import logging
import sys
from pathlib import Path

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

logging.basicConfig(level=logging.INFO, format="%(name)s %(message)s")

from app.sld.generator import SldPipeline
from app.sld.reference_matcher import get_reference_spacing


def _make_circuits(n: int, phase: str = "three_phase") -> list[dict]:
    """Generate n placeholder sub-circuits with balanced phase distribution."""
    phases = ["L1", "L2", "L3"] if phase == "three_phase" else ["L1"]
    circuits = []
    for i in range(n):
        ph = phases[i % len(phases)]
        is_light = i < n // 3
        cid = f"{ph}{'S' if is_light else 'P'}{i // len(phases) + 1}"
        circuits.append({
            "circuit_id": cid,
            "phase": ph,
            "name": f"{'LIGHTS' if is_light else '13A TWIN S/S/O'} #{i+1}",
            "breaker_type": "MCB",
            "breaker_rating": 10 if is_light else 20,
            "breaker_poles": "SPN" if phase == "three_phase" else "SP",
            "fault_kA": 6,
            "breaker_characteristic": "B",
            "cable": f"2 x 1C {'1.5' if is_light else '2.5'}sqmm PVC + {'1.5' if is_light else '2.5'}sqmm PVC CPC IN METAL TRUNKING",
        })
    return circuits


MATRIX = [
    # (label, supply_type, metering, kva, breaker, n_circuits)
    ("1ph_8ckt",   "single_phase", "sp_meter",    9.2,  {"type": "MCB",  "rating": 40,  "poles": "DP",  "fault_kA": 10, "breaker_characteristic": "B"}, 8),
    ("1ph_20ckt",  "single_phase", "sp_meter",    23,   {"type": "MCB",  "rating": 100, "poles": "DP",  "fault_kA": 10, "breaker_characteristic": "C"}, 20),
    ("1ph_40ckt",  "single_phase", "sp_meter",    23,   {"type": "MCCB", "rating": 100, "poles": "DP",  "fault_kA": 25}, 40),
    ("3ph_12ckt",  "three_phase",  "sp_meter",    24,   {"type": "MCCB", "rating": 63,  "poles": "TPN", "fault_kA": 25}, 12),
    ("3ph_30ckt",  "three_phase",  "sp_meter",    55,   {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25}, 30),
    ("3ph_50ckt",  "three_phase",  "sp_meter",    72,   {"type": "MCCB", "rating": 125, "poles": "TPN", "fault_kA": 25}, 50),
    ("ct_20ckt",   "three_phase",  "ct_meter",    87,   {"type": "MCCB", "rating": 150, "poles": "TPN", "fault_kA": 36}, 20),
    ("ct_40ckt",   "three_phase",  "ct_meter",    115,  {"type": "MCCB", "rating": 200, "poles": "TPN", "fault_kA": 36}, 40),
    ("ct_60ckt",   "three_phase",  "ct_meter",    230,  {"type": "MCCB", "rating": 400, "poles": "TPN", "fault_kA": 50}, 60),
]

output_dir = project_root / "output" / "comparison_matrix"
output_dir.mkdir(parents=True, exist_ok=True)

print("=" * 90)
print(f"{'Case':<16} {'Type':<14} {'Ckts':>4}  {'Matched Ref':<35} {'Score':>5}  {'H-Spc':>5}  {'B→R':>5}  {'R→B':>5}")
print("=" * 90)

pipeline = SldPipeline()

for label, supply, metering, kva, breaker, n_ckts in MATRIX:
    requirements = {
        "supply_type": supply,
        "kva": kva,
        "voltage": 230 if supply == "single_phase" else 400,
        "phase_config": "DP" if supply == "single_phase" else "TPN",
        "main_breaker": breaker,
        "incoming_cable": {
            "size_mm2": 10, "earth_mm2": 10, "type": "PVC",
            "cores": 2 if supply == "single_phase" else 4,
            "count": 1, "cpc_type": "PVC", "method": "METAL TRUNKING",
        },
        "elcb": {"type": "RCCB", "rating": breaker["rating"], "sensitivity_ma": 30, "poles": breaker["poles"]},
        "sub_circuits": _make_circuits(n_ckts, supply),
        "metering": metering,
        "earth_conductor_mm2": 10,
    }
    if metering == "ct_meter":
        requirements["ct_ratio"] = f"{breaker['rating']}/5"

    # Query reference matcher
    matched = get_reference_spacing(requirements)
    if matched:
        overrides = matched.to_overrides()
        ref_name = matched.reference_file[:33]
        score = f"{matched.match_score:.2f}"
        h_spc = f"{overrides.get('horizontal_spacing', '-'):>5}" if 'horizontal_spacing' in overrides else "  def"
        b2r = f"{overrides.get('ref_breaker_to_rccb_gap', '-'):>5}" if 'ref_breaker_to_rccb_gap' in overrides else "    -"
        r2b = f"{overrides.get('ref_rccb_to_busbar_gap', '-'):>5}" if 'ref_rccb_to_busbar_gap' in overrides else "    -"
    else:
        ref_name, score, h_spc, b2r, r2b = "(no match)", "  -", "  def", "    -", "    -"

    # Generate SLD
    app_info = {"address": f"Test {label}", "postalCode": "", "clientName": "", "sld_only_mode": True, "drawing_number": f"CMP-{label}"}
    try:
        result = pipeline.run(requirements, application_info=app_info)
        pdf_path = str(output_dir / f"{label}.pdf")
        svg_path = str(output_dir / f"{label}.svg")
        dxf_path = str(output_dir / f"{label}.dxf")
        result.save(pdf_path, svg_path, dxf_path)
        print(f"{label:<16} {metering:<14} {n_ckts:>4}  {ref_name:<35} {score:>5}  {h_spc:>5}  {b2r:>5}  {r2b:>5}  OK")
    except Exception as e:
        print(f"{label:<16} {metering:<14} {n_ckts:>4}  {ref_name:<35} {score:>5}  {h_spc:>5}  {b2r:>5}  {r2b:>5}  FAIL: {e}")

print("=" * 90)
print(f"\nOutput: {output_dir}")
