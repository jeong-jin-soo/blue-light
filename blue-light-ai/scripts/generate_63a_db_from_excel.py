#!/usr/bin/env python3
"""Generate SLD from 63A_DB_complete_schedule.xlsx.

63A TPN DB — direct metering (SP meter board), 25 outgoing circuits.
Lighting (7) + Power (16) + Spare (2) grouped by L1/L2/L3 phase.
"""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.sld.generator import SldPipeline

# === Circuit data from Excel ===

# Phase assignment from circuit IDs:
# L1xx = L1 phase, L2xx = L2 phase, L3xx = L3 phase
# ISOLx circuits have MCB breakers in the schedule

circuits = [
    # --- Lighting (Section: Lighting) ---
    # L1S1: 1 light
    {"circuit_id": "L1S1", "phase": "L1", "name": "1 Nos LIGHTS",
     "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 1.5mm² PVC + 1.5mm² CPC"},
    # L2S1: 2 lights + 1 emergency LED
    {"circuit_id": "L2S1", "phase": "L2", "name": "2 Nos LIGHTS + 1 Nos EMERGENCY LED",
     "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 1.5mm² PVC + 1.5mm² CPC"},
    # L3S1: 2 emergency lights + 1 exit light
    {"circuit_id": "L3S1", "phase": "L3", "name": "2 Nos EMERGENCY LIGHT + 1 Nos EXIT LIGHT",
     "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 1.5mm² PVC + 1.5mm² CPC"},
    # L1S2: 4 lights + 1 emergency LED
    {"circuit_id": "L1S2", "phase": "L1", "name": "4 Nos LIGHTS + 1 Nos EMERGENCY LED",
     "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 1.5mm² PVC + 1.5mm² CPC"},
    # L2S2: 2 lights + 2 fans + 1 emergency LED
    {"circuit_id": "L2S2", "phase": "L2", "name": "2 Nos LIGHTS + 2 Nos FAN + 1 Nos EMERGENCY LED",
     "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 1.5mm² PVC + 1.5mm² CPC"},
    # L3S2: 2 lights + 1 emergency LED  (was L3S2 in Excel but only 6 lighting circuits visible,
    # Excel shows row 9 as L3S2)
    {"circuit_id": "L3S2", "phase": "L3", "name": "2 Nos LIGHTS + 1 Nos EMERGENCY LED",
     "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 1.5mm² PVC + 1.5mm² CPC"},
    # L1S3: 11 lights + cove LED + exit light + signage
    {"circuit_id": "L1S3", "phase": "L1", "name": "11 Nos LIGHTS + COVE LED + EXIT LIGHT + SIGNAGE",
     "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 1.5mm² PVC + 1.5mm² CPC"},

    # --- Power (Section: Power) ---
    # L1P1: 1 single SSO + 2 twin SSO
    {"circuit_id": "L1P1", "phase": "L1", "name": "1 Nos 13A SINGLE S/S/O + 2 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L2P1: 1 single SSO + 1 twin SSO
    {"circuit_id": "L2P1", "phase": "L2", "name": "1 Nos 13A SINGLE S/S/O + 1 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L3P1: 1 single SSO + 1 twin SSO
    {"circuit_id": "L3P1", "phase": "L3", "name": "1 Nos 13A SINGLE S/S/O + 1 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # ISOL1: 20A DP isolator
    {"circuit_id": "ISOL1", "phase": "L1", "name": "1 Nos 20A DP ISOLATOR",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L2P2: 1 single SSO
    {"circuit_id": "L2P2", "phase": "L2", "name": "1 Nos 13A SINGLE S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L3P2: 1 twin SSO
    {"circuit_id": "L3P2", "phase": "L3", "name": "1 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L1P3: 2 twin SSO
    {"circuit_id": "L1P3", "phase": "L1", "name": "2 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L2P3: 1 single SSO
    {"circuit_id": "L2P3", "phase": "L2", "name": "1 Nos 13A SINGLE S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # ISOL2: 20A DP isolator
    {"circuit_id": "ISOL2", "phase": "L2", "name": "1 Nos 20A DP ISOLATOR",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L1P4: 1 twin SSO
    {"circuit_id": "L1P4", "phase": "L1", "name": "1 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L2P4: 2 twin SSO
    {"circuit_id": "L2P4", "phase": "L2", "name": "2 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # ISOL3: 20A DP isolator
    {"circuit_id": "ISOL3", "phase": "L3", "name": "1 Nos 20A DP ISOLATOR",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L1P5: 2 twin SSO
    {"circuit_id": "L1P5", "phase": "L1", "name": "2 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L2P5: 2 twin SSO
    {"circuit_id": "L2P5", "phase": "L2", "name": "2 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L3P5: 1 twin SSO
    {"circuit_id": "L3P5", "phase": "L3", "name": "1 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},
    # L1P6: 4 twin SSO
    {"circuit_id": "L1P6", "phase": "L1", "name": "4 Nos 13A TWIN S/S/O",
     "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
     "breaker_characteristic": "B", "fault_kA": 6,
     "cable": "2 x 1C 2.5mm² PVC + 2.5mm² CPC"},

    # --- Spare ---
    {"circuit_id": "SPARE1", "name": "SPARE", "breaker_type": "SPARE"},
    {"circuit_id": "SPARE2", "name": "SPARE", "breaker_type": "SPARE"},
]

requirements = {
    "supply_type": "three_phase",
    "kva": 69.282,
    "voltage": 400,
    "supply_source": "landlord_riser",
    "incoming_cable": "4 x 16mm² 1C PVC/PVC CABLE + 16mm² CPC IN METAL TRUNKING",
    "metering": "sp_meter",
    # sub_circuits at top level for single-DB path
    "sub_circuits": circuits,
    "db_name": "63A DB",
    "isolator": {
        "rating": 63,
        "type": "4P",
        "location_text": "LOCATED AT METER BOARD",
    },
    "meter_board": {
        "isolator_rating": 63,
        "isolator_type": "4P",
        "mcb_rating": 63,
        "mcb_poles": "TPN",
        "mcb_characteristic": "B",
        "mcb_kA": 10,
    },
    "main_breaker": {
        "type": "MCB",
        "rating": 63,
        "poles": "TPN",
        "breaker_characteristic": "B",
        "fault_kA": 10,
    },
    "elcb": {
        "type": "ELCB",
        "rating": 63,
        "sensitivity_ma": 30,
        "poles": 4,
    },
    "busbar_rating": 100,
    "busbar_label": "100A COMB BAR",
    "distribution_boards": [
        {
            "db_name": "63A DB",
            "kva": 69.282,
            "sub_circuits": circuits,
            "location_text": "LOCATED INSIDE UNIT #01-36",
        },
    ],
}

application_info = {
    "project_title": "63A TPN Distribution Board",
    "address": "Unit #01-36",
    "lew_name": "SLD AI Generator",
    "lew_licence_no": "---",
}


def main():
    pipeline = SldPipeline()
    result = pipeline.run(
        requirements,
        application_info,
        backend_type="dxf",
    )

    out_dir = os.path.join(os.path.dirname(__file__), "..", "output")
    os.makedirs(out_dir, exist_ok=True)

    # Save PDF
    pdf_path = os.path.join(out_dir, "63A_DB_from_excel.pdf")
    with open(pdf_path, "wb") as f:
        f.write(result.pdf_bytes)
    print(f"PDF: {pdf_path}")

    # Save SVG
    if result.svg_string:
        svg_path = os.path.join(out_dir, "63A_DB_from_excel.svg")
        with open(svg_path, "w") as f:
            f.write(result.svg_string)
        print(f"SVG: {svg_path}")

    # Save DXF
    if result.dxf_bytes:
        dxf_path = os.path.join(out_dir, "63A_DB_from_excel.dxf")
        with open(dxf_path, "wb") as f:
            f.write(result.dxf_bytes)
        print(f"DXF: {dxf_path}")

    print(f"\nComponents: {result.component_count}")
    if hasattr(result, "overflow") and result.overflow:
        print("WARNING: Page overflow detected!")


if __name__ == "__main__":
    main()
