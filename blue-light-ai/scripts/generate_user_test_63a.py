#!/usr/bin/env python3
"""Generate SLD from user-provided 63A TPN requirements for comparison with reference."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.sld.generator import SldPipeline
from app.sld.layout import compute_layout


def main():
    # User-provided requirements (simplified 8-circuit version)
    # NOTE: Reference PDF shows 69.282 kVA / 63A DB — using 43 kVA to match 63A TPN spec
    # (69.282 kVA actually requires 100A, which is a discrepancy in the reference)
    requirements = {
        "supply_type": "three_phase",
        "kva": 43,
        "supply_source": "landlord",
        "metering": "sp_meter",
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
        "sub_circuits": [
            {
                "name": "Lighting",
                "breaker": {"type": "MCB", "rating": 10, "breaker_characteristic": "B"},
                "cable": {"cores": 2, "size_mm2": "1.5", "type": "PVC", "cpc_mm2": "1.5"},
                "load": "1 Nos LIGHTS",
            },
            {
                "name": "Lighting",
                "breaker": {"type": "MCB", "rating": 10, "breaker_characteristic": "B"},
                "cable": {"cores": 2, "size_mm2": "1.5", "type": "PVC", "cpc_mm2": "1.5"},
                "load": "1 Nos LIGHTS",
            },
            {
                "name": "Lighting",
                "breaker": {"type": "MCB", "rating": 10, "breaker_characteristic": "B"},
                "cable": {"cores": 2, "size_mm2": "1.5", "type": "PVC", "cpc_mm2": "1.5"},
                "load": "1 Nos LIGHTS",
            },
            {
                "name": "Lighting",
                "breaker": {"type": "MCB", "rating": 10, "breaker_characteristic": "B"},
                "cable": {"cores": 2, "size_mm2": "1.5", "type": "PVC", "cpc_mm2": "1.5"},
                "load": "1 Nos LIGHTS",
            },
            {
                "name": "Socket Outlet",
                "breaker": {"type": "MCB", "rating": 20, "breaker_characteristic": "B"},
                "cable": {"cores": 2, "size_mm2": "2.5", "type": "PVC", "cpc_mm2": "2.5"},
                "load": "1 Nos SOCKET OUTLET",
            },
            {
                "name": "Socket Outlet",
                "breaker": {"type": "MCB", "rating": 20, "breaker_characteristic": "B"},
                "cable": {"cores": 2, "size_mm2": "2.5", "type": "PVC", "cpc_mm2": "2.5"},
                "load": "1 Nos SOCKET OUTLET",
            },
            {
                "name": "Air Conditioning",
                "breaker": {"type": "MCB", "rating": 20, "breaker_characteristic": "B"},
                "cable": {"cores": 2, "size_mm2": "4", "type": "PVC", "cpc_mm2": "4"},
                "load": "1 Nos AIR CON",
            },
            {
                "name": "Spare",
                "breaker": {"type": "MCB", "rating": 20},
                "load": "SPARE",
            },
        ],
    }

    application_info = {
        "client_name": "ULTIMED HEALTHCARE CLINICS",
        "address": "BLK 824 TAMPINES STREET 81 #01-36",
        "premises_type": "Commercial",
        "drawing_no": "NSI_UHC_TAM_01",
        "main_contractor": "FIRE SOLUTIONS ENGINEERING PTE LTD",
        "electrical_contractor": "NEWSPACE INTERIOR PTE LTD\n6D MANDAI ESTATE, #09-06\nSINGAPORE 729938",
    }

    output_dir = Path(__file__).resolve().parent.parent / "output"
    output_dir.mkdir(exist_ok=True)

    pdf_path = str(output_dir / "test_user_63a_tpn.pdf")
    svg_path = str(output_dir / "test_user_63a_tpn.svg")

    result = SldPipeline().run(requirements, application_info=application_info)
    result.save(pdf_path, svg_path, pdf_path.replace(".pdf", ".dxf"))
    print(f"  Components: {result.component_count}")
    print(f"  PDF: {pdf_path}")
    print(f"  SVG: {svg_path}")


if __name__ == "__main__":
    main()
