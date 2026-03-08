#!/usr/bin/env python3
"""Generate SLD from user's 63A TPN request (8 circuits).

Reference: 63A TPN SLD 14.pdf
- Main Supply: 400V 3 Phase
- Main Breaker: 63A TPN MCB 10kA Type B
- ELCB: 63A 30mA
- Busbar: 100A Comb Busbar
- Incoming Cable: 4x16mm² PVC/PVC + 16mm² CPC in Metal Trunking
- Meter: KWH Meter (Landlord Riser)
- Approved Load: 69.282 kVA

Circuits:
  1-4: 10A SP MCB Type B — Lighting (1.5mm² + CPC)
  5-6: 20A SP MCB Type B — Socket Outlet (2.5mm² + CPC)
  7:   20A SP MCB Type B — Air Conditioning (4mm² + CPC)
  8:   Spare — Future Expansion
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.sld.generator import SldGenerator


def build_circuits() -> list[dict]:
    """Build 8 circuits from user's specification."""
    return [
        # Circuit 1-4: Lighting — 10A SP MCB Type B, 1.5mm²
        {
            "name": "Lighting",
            "load": "Lighting",
            "breaker_type": "MCB",
            "breaker_rating": 10,
            "breaker_characteristic": "B",
            "cable": {
                "cores": 2,
                "size_mm2": "1.5",
                "type": "PVC/PVC",
                "cpc_mm2": "1.5",
                "cpc_type": "PVC",
                "method": "PVC CONDUIT",
            },
        },
        {
            "name": "Lighting",
            "load": "Lighting",
            "breaker_type": "MCB",
            "breaker_rating": 10,
            "breaker_characteristic": "B",
            "cable": {
                "cores": 2,
                "size_mm2": "1.5",
                "type": "PVC/PVC",
                "cpc_mm2": "1.5",
                "cpc_type": "PVC",
                "method": "PVC CONDUIT",
            },
        },
        {
            "name": "Lighting",
            "load": "Lighting",
            "breaker_type": "MCB",
            "breaker_rating": 10,
            "breaker_characteristic": "B",
            "cable": {
                "cores": 2,
                "size_mm2": "1.5",
                "type": "PVC/PVC",
                "cpc_mm2": "1.5",
                "cpc_type": "PVC",
                "method": "PVC CONDUIT",
            },
        },
        {
            "name": "Lighting",
            "load": "Lighting",
            "breaker_type": "MCB",
            "breaker_rating": 10,
            "breaker_characteristic": "B",
            "cable": {
                "cores": 2,
                "size_mm2": "1.5",
                "type": "PVC/PVC",
                "cpc_mm2": "1.5",
                "cpc_type": "PVC",
                "method": "PVC CONDUIT",
            },
        },
        # Circuit 5-6: Socket Outlet — 20A SP MCB Type B, 2.5mm²
        {
            "name": "Socket Outlet",
            "load": "Socket Outlet",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "cable": {
                "cores": 2,
                "size_mm2": "2.5",
                "type": "PVC/PVC",
                "cpc_mm2": "2.5",
                "cpc_type": "PVC",
                "method": "PVC CONDUIT",
            },
        },
        {
            "name": "Socket Outlet",
            "load": "Socket Outlet",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "cable": {
                "cores": 2,
                "size_mm2": "2.5",
                "type": "PVC/PVC",
                "cpc_mm2": "2.5",
                "cpc_type": "PVC",
                "method": "PVC CONDUIT",
            },
        },
        # Circuit 7: Air Conditioning — 20A SP MCB Type B, 4mm²
        {
            "name": "Air Conditioning",
            "load": "Air Conditioning",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "cable": {
                "cores": 2,
                "size_mm2": "4",
                "type": "PVC/PVC",
                "cpc_mm2": "2.5",
                "cpc_type": "PVC",
                "method": "PVC CONDUIT",
            },
        },
        # Circuit 8: Spare — Future Expansion
        {
            "name": "Spare",
            "load": "Spare",
        },
    ]


def main():
    requirements = {
        "supply_type": "three_phase",
        "kva": 69.282,
        "supply_source": "landlord",
        "metering": "sp_meter",  # SP KWH Meter (CT not needed for 63A)
        "main_breaker": {
            "type": "MCB",
            "rating": 63,
            "poles": "TPN",
            "fault_kA": 10,
            "breaker_characteristic": "B",
        },
        "elcb": {
            "type": "ELCB",
            "rating": 63,
            "sensitivity_ma": 30,
            "poles": "4P",
        },
        "busbar_rating": 100,
        "incoming_cable": {
            "count": 4,
            "cores": 1,
            "size_mm2": "16",
            "type": "PVC/PVC",
            "cpc_mm2": "16",
            "cpc_type": "PVC",
            "method": "METAL TRUNKING",
        },
        "sub_circuits": build_circuits(),
    }

    application_info = {
        "client_name": "",
        "client_address": "",
        "premises_type": "commercial",
        "drawing_no": "",
    }

    gen = SldGenerator()

    output_dir = Path(__file__).resolve().parent.parent / "output"
    output_dir.mkdir(exist_ok=True)

    pdf_path = output_dir / "test_63a_user_request.pdf"
    svg_path = output_dir / "test_63a_user_request.svg"

    print(f"Generating SLD with {len(requirements['sub_circuits'])} circuits...")
    result = gen.generate(
        requirements,
        application_info,
        pdf_output_path=str(pdf_path),
        svg_output_path=str(svg_path),
    )
    print(f"  Components: {result.get('component_count', '?')}")
    print(f"  PDF: {pdf_path}")
    print(f"  SVG: {svg_path}")
    if result.get("dxf_path"):
        print(f"  DXF: {result['dxf_path']}")
    print("Done!")


if __name__ == "__main__":
    main()
