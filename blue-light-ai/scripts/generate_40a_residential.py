#!/usr/bin/env python3
"""Generate SLD for a common Residential Single-Phase 40A installation.

User request:
- Single phase 40A residential
- Meter board: 40A DP MCB isolator → SP KWH meter → 40A DP MCB Type C 10kA
- Main breaker: 40A DP MCB Type B 10kA
- RCCB: 40A 30mA
- 8 sub-circuits:
  S1, S2: B10A MCB 6kA — 8 nos lighting points each
  P1, P2: B20A MCB 6kA — 6 nos 13A Twin SSO (Bedroom 1 & 2)
  P3, P4: B20A MCB 6kA — 3 nos 13A Twin SSO (Kitchen)
  H5, H6: B20A MCB 6kA — 2 nos heater point each
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.sld.generator import SldPipeline


def build_circuits() -> list[dict]:
    """Build 8 sub-circuits matching user's specification."""
    return [
        # Lighting circuits (S1, S2) — B10A MCB 6kA
        {
            "name": "8 Nos Lighting Points",
            "load": "8 Nos Lighting Points",
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
            "name": "8 Nos Lighting Points",
            "load": "8 Nos Lighting Points",
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
        # Power circuits (P1, P2) — B20A MCB 6kA — Bedroom 1 & 2
        {
            "name": "6 Nos 13A Twin S/S/O",
            "load": "6 Nos 13A Twin S/S/O",
            "room": "BEDROOM 1",
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
            "name": "6 Nos 13A Twin S/S/O",
            "load": "6 Nos 13A Twin S/S/O",
            "room": "BEDROOM 2",
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
        # Power circuits (P3, P4) — B20A MCB 6kA — Kitchen
        {
            "name": "3 Nos 13A Twin S/S/O",
            "load": "3 Nos 13A Twin S/S/O",
            "room": "KITCHEN",
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
            "name": "3 Nos 13A Twin S/S/O",
            "load": "3 Nos 13A Twin S/S/O",
            "room": "KITCHEN",
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
        # Heater circuits (H5, H6) — B20A MCB 6kA
        {
            "name": "2 Nos Heater Point",
            "load": "2 Nos Heater Point",
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
        {
            "name": "2 Nos Heater Point",
            "load": "2 Nos Heater Point",
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
    ]


def main():
    requirements = {
        "supply_type": "single_phase",
        "kva": 9.2,  # 40A × 230V = 9.2kVA
        "supply_source": "sp_powergrid",
        "metering": "sp_meter",  # SP KWH Meter in meter board
        "main_breaker": {
            "type": "MCB",
            "rating": 40,
            "poles": "DP",
            "fault_kA": 10,
            "breaker_characteristic": "B",
        },
        "elcb": {
            "type": "RCCB",
            "rating": 40,
            "sensitivity_ma": 30,
            "poles": "DP",
        },
        "busbar_rating": 100,
        "incoming_cable": {
            "cores": 2,
            "size_mm2": "10",
            "type": "PVC/PVC",
            "cpc_mm2": "4",
            "cpc_type": "PVC",
            "method": "PVC CONDUIT",
        },
        "sub_circuits": build_circuits(),
    }

    application_info = {
        "client_name": "RESIDENTIAL OWNER",
        "client_address": "BLK 123 TAMPINES ST 45 #08-123",
        "unit_number": "#08-123",
        "postalCode": "520123",
        "premises_type": "residential",
        "drawing_no": "SLD-RES-001",
    }

    output_dir = Path(__file__).resolve().parent.parent / "output"
    output_dir.mkdir(exist_ok=True)

    pdf_path = str(output_dir / "test_40a_residential.pdf")
    svg_path = str(output_dir / "test_40a_residential.svg")

    print(f"Generating Residential SLD with {len(requirements['sub_circuits'])} circuits...")
    result = SldPipeline().run(requirements, application_info=application_info)
    result.save(pdf_path, svg_path, pdf_path.replace(".pdf", ".dxf"))
    print(f"  Components: {result.component_count}")
    print(f"  PDF: {pdf_path}")
    print(f"  SVG: {svg_path}")
    print("Done!")


if __name__ == "__main__":
    main()
