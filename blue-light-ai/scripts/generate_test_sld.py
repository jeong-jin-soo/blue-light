#!/usr/bin/env python3
"""Generate a test SLD based on user requirements: Residential 40A Single Phase."""

import sys
from pathlib import Path

# Add project root to path
project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from app.sld.generator import SldPipeline

# User requirements:
# - Single phase 40A residential SLD
# - Meter board: 40A DP MCB Isolator → SP kWh meter → Type-C 40A MCB 10KA
# - Main breaker: Type-B 40A MCB 10KA
# - RCCB: 40A RCCB 30mA
# - 8 sub-circuits total

requirements = {
    "supply_type": "single_phase",
    "kva": 9.2,  # 40A x 230V = 9.2 kVA
    "voltage": 230,
    "phase_config": "DP",

    # Main breaker (in DB - after meter board)
    "main_breaker": {
        "type": "MCB",
        "rating": 40,
        "poles": "DP",
        "fault_kA": 10,
        "breaker_characteristic": "B",
    },

    # Incoming cable (40A single phase)
    "incoming_cable": {
        "size_mm2": 10,
        "earth_mm2": 10,
        "type": "PVC",
        "cores": 2,
        "count": 1,
        "cpc_type": "PVC",
        "method": "METAL TRUNKING",
    },

    # RCCB after main breaker
    "elcb": {
        "type": "RCCB",
        "rating": 40,
        "sensitivity_ma": 30,
        "poles": 2,
    },

    # Busbar
    "busbar_rating": 40,

    # Metering
    "metering": "sp_meter",
    "supply_source": "sp_powergrid",

    # Meter board config
    "meter_board": {
        "isolator_rating": 40,
        "isolator_type": "DP MCB",
        "meter_type": "SP",
        "outgoing_breaker": {
            "type": "MCB",
            "rating": 40,
            "characteristic": "C",
            "fault_kA": 10,
        },
    },

    # 8 Sub-circuits
    "sub_circuits": [
        # S1: Type-B 10A MCB 6KA → 8 lighting points
        {
            "name": "Lighting (8 nos)",
            "id": "S1",
            "breaker_type": "MCB",
            "breaker_rating": 10,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "lighting",
            "qty": 8,
            "cable": {
                "cores": 2,
                "size_mm2": "1.5",
                "cpc_mm2": "1.5",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            },
        },
        # S2: Type-B 10A MCB 6KA → 8 lighting points (ditto)
        {
            "name": "Lighting (8 nos)",
            "id": "S2",
            "breaker_type": "MCB",
            "breaker_rating": 10,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "lighting",
            "qty": 8,
            "cable": {
                "cores": 2,
                "size_mm2": "1.5",
                "cpc_mm2": "1.5",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            },
        },
        # P1: Type-B 20A MCB 6KA → 6 SSO (Bedroom 1)
        {
            "name": "6 nos Twin SSO",
            "id": "P1",
            "room": "Bedroom 1",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "power",
            "qty": 6,
            "cable": {
                "cores": 2,
                "size_mm2": "2.5",
                "cpc_mm2": "2.5",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            },
        },
        # P2: Type-B 20A MCB 6KA → 6 SSO (Bedroom 2) - ditto
        {
            "name": "6 nos Twin SSO",
            "id": "P2",
            "room": "Bedroom 2",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "power",
            "qty": 6,
            "cable": {
                "cores": 2,
                "size_mm2": "2.5",
                "cpc_mm2": "2.5",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            },
        },
        # P3: Type-B 20A MCB 6KA → 3 SSO (Kitchen)
        {
            "name": "3 nos Twin SSO",
            "id": "P3",
            "room": "Kitchen",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "power",
            "qty": 3,
            "cable": {
                "cores": 2,
                "size_mm2": "2.5",
                "cpc_mm2": "2.5",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            },
        },
        # P4: Type-B 20A MCB 6KA → 3 SSO (Kitchen) - ditto
        {
            "name": "3 nos Twin SSO",
            "id": "P4",
            "room": "Kitchen",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "power",
            "qty": 3,
            "cable": {
                "cores": 2,
                "size_mm2": "2.5",
                "cpc_mm2": "2.5",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            },
        },
        # H5: Type-B 20A MCB 6KA → 2 heater points (4mm² cable for 3-group test)
        {
            "name": "Heater Point (2 nos)",
            "id": "H5",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "power",
            "qty": 2,
            "cable": {
                "cores": 2,
                "size_mm2": "4",
                "cpc_mm2": "4",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            },
        },
        # H6: Type-B 20A MCB 6KA → 2 heater points - ditto (4mm²)
        {
            "name": "Heater Point (2 nos)",
            "id": "H6",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "power",
            "qty": 2,
            "cable": {
                "cores": 2,
                "size_mm2": "4",
                "cpc_mm2": "4",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            },
        },
    ],
}

application_info = {
    "address": "Residential Unit",
    "postalCode": "",
    "clientName": "",
    "sld_only_mode": True,  # No LEW info (SLD-only mode)
    "drawing_number": "SLD-001",
}

# Output paths
output_dir = project_root / "output"
output_dir.mkdir(exist_ok=True)
pdf_path = str(output_dir / "test_residential_40a.pdf")
svg_path = str(output_dir / "test_residential_40a.svg")

print(f"Generating SLD...")
print(f"  Supply: Single Phase 40A, {requirements['kva']} kVA")
print(f"  Main breaker: Type-B 40A MCB 10kA")
print(f"  RCCB: 40A 30mA")
print(f"  Sub-circuits: {len(requirements['sub_circuits'])}")
print()

result = SldPipeline().run(requirements, application_info=application_info)
result.save(pdf_path, svg_path, pdf_path.replace(".pdf", ".dxf"))

print(f"SLD Generated Successfully!")
print(f"  Components: {result.component_count}")
print(f"  PDF: {pdf_path}")
print(f"  SVG: {svg_path}")
