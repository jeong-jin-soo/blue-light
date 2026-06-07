#!/usr/bin/env python3
"""Generate SLD from 63A_DB_complete_schedule.xlsx data — 23 circuits + 3 SPARE.

Excel input uses interleaved circuit ordering (L1S1, L2S1, L3S1, L1S2, ...),
matching function-based layout (lighting group → power group per triplet).
"""
import sys, os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.sld.generator import SldPipeline

requirements = {
    "supply_type": "three_phase",
    "kva": 69.282,
    "voltage": 400,
    "supply_source": "landlord",
    "incoming_cable": "4 x 16mm² 1C PVC/PVC CABLE + 16mm² CPC IN METAL TRUNKING",
    "metering": "sp_meter",
    "meter_board": {
        "isolator_rating": 63,
        "isolator_type": "4P",
        "meter_type": "KWH",
        "outgoing_breaker": {
            "type": "MCB",
            "rating": 63,
            "poles": "TPN",
            "characteristic": "B",
            "fault_kA": 10,
        },
    },
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
        "poles": 4,
    },
    "busbar_rating": 100,
    "sub_circuits": [
        # === Interleaved order matching Excel (L1S1,L2S1,L3S1,L1S2,...) ===
        # --- Lighting triplet 1 ---
        {"circuit_id": "L1S1", "phase": "L1", "name": "1 No. LIGHTING POINT",
         "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        {"circuit_id": "L2S1", "phase": "L2", "name": "2 Nos LIGHTING POINTS + 1 No. EMERGENCY LED",
         "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        {"circuit_id": "L3S1", "phase": "L3", "name": "2 Nos EMERGENCY LIGHTS + 1 No. EXIT LIGHT",
         "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        # --- Lighting triplet 2 ---
        {"circuit_id": "L1S2", "phase": "L1", "name": "4 Nos LIGHTING POINTS + 1 No. EMERGENCY LED",
         "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        {"circuit_id": "L2S2", "phase": "L2", "name": "2 Nos LIGHTING POINTS + 2 Nos FANS + 1 No. EMERGENCY LED",
         "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        {"circuit_id": "L3S2", "phase": "L3", "name": "2 Nos LIGHTING POINTS + 1 No. EMERGENCY LED",
         "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        # --- Lighting single (L1 only) ---
        {"circuit_id": "L1S3", "phase": "L1", "name": "11 Nos LIGHTING POINTS + COVE LED + EXIT LIGHT + SIGNAGE",
         "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        # --- Power triplet 1 ---
        {"circuit_id": "L1P1", "phase": "L1", "name": "1 No. 13A SINGLE S/S/O + 2 Nos 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "L2P1", "phase": "L2", "name": "1 No. 13A SINGLE S/S/O + 1 No. 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "L3P1", "phase": "L3", "name": "1 No. 13A SINGLE S/S/O + 1 No. 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        # --- Power: ISOL1 + L2P2 + L3P2 ---
        {"circuit_id": "ISOL1", "phase": "L1", "name": "1 No. 20A DP ISOLATOR",
         "breaker_type": "ISOLATOR", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "L2P2", "phase": "L2", "name": "1 No. 13A SINGLE S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "L3P2", "phase": "L3", "name": "1 No. 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        # --- Power triplet 3 ---
        {"circuit_id": "L1P3", "phase": "L1", "name": "2 Nos 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "L2P3", "phase": "L2", "name": "1 No. 13A SINGLE S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "ISOL2", "phase": "L3", "name": "1 No. 20A DP ISOLATOR",
         "breaker_type": "ISOLATOR", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        # --- Power triplet 4 ---
        {"circuit_id": "L1P4", "phase": "L1", "name": "1 No. 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "L2P4", "phase": "L2", "name": "2 Nos 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "ISOL3", "phase": "L3", "name": "1 No. 20A DP ISOLATOR",
         "breaker_type": "ISOLATOR", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        # --- Power triplet 5 ---
        {"circuit_id": "L1P5", "phase": "L1", "name": "2 Nos 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "L2P5", "phase": "L2", "name": "2 Nos 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"circuit_id": "L3P5", "phase": "L3", "name": "1 No. 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        # --- Power single (L1 only) ---
        {"circuit_id": "L1P6", "phase": "L1", "name": "4 Nos 13A TWIN S/S/O",
         "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN",
         "fault_kA": 6, "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
    ],
}

application_info = {
    "clientName": "63A TPN Distribution Board",
    "address": "",
    "unit_number": "",
    "drawing_number": "63A-DB-COMPLETE",
    "mainContractor": "",
    "electricalContractor": "",
    "sld_only_mode": True,
}

if __name__ == "__main__":
    output_dir = os.path.join(os.path.dirname(__file__), "..", "output")
    os.makedirs(output_dir, exist_ok=True)

    pdf_path = os.path.join(output_dir, "63A_DB_complete.pdf")
    svg_path = os.path.join(output_dir, "63A_DB_complete.svg")
    dxf_path = os.path.join(output_dir, "63A_DB_complete.dxf")

    result = SldPipeline().run(requirements, application_info=application_info)
    result.save(pdf_path, svg_path, dxf_path)

    print(f"\nPDF: {pdf_path}")
    print(f"SVG: {svg_path}")
    print(f"DXF: {dxf_path}")
    print(f"Components: {result.component_count}")
    if result.layout_warnings:
        print(f"Warnings: {result.layout_warnings}")
    print("Done!")
