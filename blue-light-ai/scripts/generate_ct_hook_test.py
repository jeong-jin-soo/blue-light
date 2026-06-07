#!/usr/bin/env python3
"""Generate a test SLD with CT metering to verify hook junction rendering."""

import sys
from pathlib import Path

project_root = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(project_root))

from app.sld.generator import SldPipeline

requirements = {
    "supply_type": "three_phase",
    "kva": 69,
    "voltage": 415,
    "phase_config": "TPN",

    "main_breaker": {
        "type": "MCCB",
        "rating": 100,
        "poles": "TPN",
        "fault_kA": 25,
    },

    "incoming_cable": {
        "size_mm2": 35,
        "earth_mm2": 16,
        "type": "XLPE",
        "cores": 4,
        "count": 1,
        "cpc_type": "PVC",
        "method": "CABLE TRAY",
    },

    "busbar_rating": 100,

    # CT metering (triggers CT metering section)
    "metering": "ct_meter",
    "supply_source": "sp_powergrid",
    "ct_ratio": "100/5A",
    "has_elr": True,
    "elr_spec": "ELR",

    "sub_circuits": [
        {
            "name": "Lighting",
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
        {
            "name": "Socket Outlet",
            "id": "P1",
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
        {
            "name": "Aircon",
            "id": "A1",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "breaker_poles": "SPN",
            "fault_kA": 6,
            "load_type": "power",
            "qty": 1,
            "cable": {
                "cores": 2,
                "size_mm2": "2.5",
                "cpc_mm2": "2.5",
                "type": "PVC",
                "cpc_type": "PVC",
                "method": "PVC CONDUIT",
            },
        },
    ],
}

application_info = {
    "address": "CT Hook Test",
    "postalCode": "",
    "clientName": "",
    "sld_only_mode": True,
    "drawing_number": "CT-HOOK-TEST",
}

output_dir = Path("/Users/ringo/Downloads/sld_test_output")
output_dir.mkdir(exist_ok=True)
pdf_path = str(output_dir / "ct_hook_test.pdf")
svg_path = str(output_dir / "ct_hook_test.svg")

print("Generating CT hook test SLD...")
print(f"  metering={requirements['metering']}, supply_source={requirements['supply_source']}")
print(f"  has_elr={requirements['has_elr']}")

result = SldPipeline().run(requirements, application_info=application_info)
result.save(pdf_path, svg_path, pdf_path.replace(".pdf", ".dxf"))

print(f"\nGenerated!")
print(f"  Components: {result.component_count}")
print(f"  PDF: {pdf_path}")
print(f"  SVG: {svg_path}")
