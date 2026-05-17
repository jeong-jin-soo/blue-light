#!/usr/bin/env python3
"""Generate multi-row SLD test cases to verify layout with 14+, 15+, 29+ circuits."""

import sys
from pathlib import Path

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from app.sld.generator import SldPipeline


def _make_circuit(idx: int, cable_size: str = "2.5", cpc_size: str = "2.5",
                  name: str | None = None, room: str | None = None,
                  rating: int = 20, char: str = "B", fault_ka: int = 6,
                  load_type: str = "power", qty: int = 4) -> dict:
    """Helper to create a sub-circuit definition."""
    sc_name = name or f"SSO ({qty} nos)"
    sc = {
        "name": sc_name,
        "id": f"C{idx}",
        "breaker_type": "MCB",
        "breaker_rating": rating,
        "breaker_characteristic": char,
        "breaker_poles": "SPN",
        "fault_kA": fault_ka,
        "load_type": load_type,
        "qty": qty,
        "cable": {
            "cores": 2,
            "size_mm2": cable_size,
            "cpc_mm2": cpc_size,
            "type": "PVC",
            "cpc_type": "PVC",
            "method": "METAL TRUNKING",
        },
    }
    if room:
        sc["room"] = room
    return sc


def _make_requirements(num_circuits: int) -> tuple[dict, list[dict]]:
    """Generate requirements with the given number of sub-circuits."""
    rooms = ["Bedroom 1", "Bedroom 2", "Bedroom 3", "Kitchen", "Living Room",
             "Bathroom 1", "Bathroom 2", "Store", "Yard", "Corridor"]

    circuits = []
    for i in range(num_circuits):
        if i < 2:
            # Lighting circuits (1.5mm²)
            circuits.append(_make_circuit(
                i + 1, cable_size="1.5", cpc_size="1.5",
                name="Lighting (8 nos)", rating=10, load_type="lighting", qty=8,
            ))
        elif i < num_circuits - 2:
            # Power circuits (2.5mm²)
            room = rooms[i % len(rooms)]
            circuits.append(_make_circuit(
                i + 1, cable_size="2.5", cpc_size="2.5",
                name="Twin SSO (4 nos)", room=room, qty=4,
            ))
        else:
            # Heater circuits (4mm²)
            circuits.append(_make_circuit(
                i + 1, cable_size="4", cpc_size="4",
                name="Heater Point (2 nos)", rating=20, qty=2,
            ))

    # Add 1 spare at end
    circuits.append({
        "name": "SPARE",
        "id": f"SP{num_circuits + 1}",
        "breaker_type": "MCB",
        "breaker_rating": 20,
        "breaker_characteristic": "B",
        "breaker_poles": "SPN",
        "fault_kA": 6,
        "is_spare": True,
    })

    reqs = {
        "supply_type": "single_phase",
        "kva": 9.2,
        "voltage": 230,
        "phase_config": "DP",
        "main_breaker": {
            "type": "MCB", "rating": 40, "poles": "DP",
            "fault_kA": 10, "breaker_characteristic": "B",
        },
        "incoming_cable": {
            "size_mm2": 10, "earth_mm2": 10, "type": "PVC",
            "cores": 2, "count": 1, "cpc_type": "PVC",
            "method": "METAL TRUNKING",
        },
        "elcb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
        "busbar_rating": 40,
        "metering": "sp_meter",
        "supply_source": "sp_powergrid",
        "meter_board": {
            "isolator_rating": 40, "isolator_type": "DP MCB",
            "meter_type": "SP",
            "outgoing_breaker": {
                "type": "MCB", "rating": 40, "characteristic": "C", "fault_kA": 10,
            },
        },
        "sub_circuits": circuits,
    }
    return reqs, circuits


application_info = {
    "address": "Residential Unit",
    "postalCode": "",
    "clientName": "",
    "sld_only_mode": True,
    "drawing_number": "SLD-MULTI",
}

output_dir = project_root / "output"
output_dir.mkdir(exist_ok=True)
# Test cases: (num_circuits, description)
test_cases = [
    (14, "14 circuits — exactly 1 full row"),
    (15, "15 circuits — 2 rows (14+1)"),
    (29, "29 circuits — 3 rows (14+14+1)"),
]

for num, desc in test_cases:
    reqs, circuits = _make_requirements(num)
    # circuits list has num+1 entries (including spare)
    actual_count = len(reqs["sub_circuits"])
    pdf_name = f"test_multirow_{num}c.pdf"
    svg_name = f"test_multirow_{num}c.svg"
    pdf_path = str(output_dir / pdf_name)
    svg_path = str(output_dir / svg_name)

    print(f"\n{'='*60}")
    print(f"  {desc}")
    print(f"  Sub-circuits: {actual_count} (including spare)")
    print(f"{'='*60}")

    try:
        result = SldPipeline().run(reqs, application_info=application_info)
        result.save(pdf_path, svg_path, pdf_path.replace(".pdf", ".dxf"))
        print(f"  OK  Components: {result.component_count}")
        print(f"      PDF: {pdf_path}")
    except Exception as e:
        print(f"  FAIL  {e}")
        import traceback
        traceback.print_exc()

print(f"\nDone.")
