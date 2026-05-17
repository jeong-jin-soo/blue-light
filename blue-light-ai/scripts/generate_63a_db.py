#!/usr/bin/env python3
"""Generate SLD from 63A_DB_complete_schedule.xlsx — Single DB, Landlord supply."""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.sld.generator import SldPipeline

circuits = [
    # Lighting — L1
    {"circuit_id": "L1S1", "phase": "L1", "name": "1 Nos LIGHTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Lighting — L2
    {"circuit_id": "L2S1", "phase": "L2", "name": "2 Nos LIGHTS + 1 Nos EMERGENCY CABINET LED", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Lighting — L3
    {"circuit_id": "L3S1", "phase": "L3", "name": "2 Nos EMERGENCY LIGHT + 1 Nos EXIT LIGHT", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Lighting — L1
    {"circuit_id": "L1S2", "phase": "L1", "name": "4 Nos LIGHTS + 1 Nos EMERGENCY CABINET LED", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Lighting — L2
    {"circuit_id": "L2S2", "phase": "L2", "name": "2 Nos LIGHTS + 2 Nos FAN + 1 Nos EMERGENCY CABINET LED", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Lighting — L3
    {"circuit_id": "L3S2", "phase": "L3", "name": "2 Nos LIGHTS + 1 Nos EMERGENCY CABINET LED", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Lighting — L1 (extra)
    {"circuit_id": "L1S3", "phase": "L1", "name": "11 Nos LIGHTS + COVE LED + 1 Nos EXIT LIGHT + 1 Nos SIGNAGE", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L1
    {"circuit_id": "L1P1", "phase": "L1", "name": "1 Nos 13A SINGLE S/S/O + 2 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L2
    {"circuit_id": "L2P1", "phase": "L2", "name": "1 Nos 13A SINGLE S/S/O + 1 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L3
    {"circuit_id": "L3P1", "phase": "L3", "name": "1 Nos 13A SINGLE S/S/O + 1 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Isolator — ISOL 1
    {"circuit_id": "ISOL 1", "phase": "L1", "name": "1 Nos 20A DP ISOLATOR", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L2
    {"circuit_id": "L2P2", "phase": "L2", "name": "1 Nos 13A SINGLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L3
    {"circuit_id": "L3P2", "phase": "L3", "name": "1 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L1
    {"circuit_id": "L1P3", "phase": "L1", "name": "2 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L2
    {"circuit_id": "L2P3", "phase": "L2", "name": "1 Nos 13A SINGLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Isolator — ISOL 2
    {"circuit_id": "ISOL 2", "phase": "L3", "name": "1 Nos 20A DP ISOLATOR", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L1
    {"circuit_id": "L1P4", "phase": "L1", "name": "1 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L2
    {"circuit_id": "L2P4", "phase": "L2", "name": "2 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Isolator — ISOL 3
    {"circuit_id": "ISOL 3", "phase": "L3", "name": "1 Nos 20A DP ISOLATOR", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L1
    {"circuit_id": "L1P5", "phase": "L1", "name": "2 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L2
    {"circuit_id": "L2P5", "phase": "L2", "name": "2 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L3
    {"circuit_id": "L3P5", "phase": "L3", "name": "1 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
    # Power — L1
    {"circuit_id": "L1P6", "phase": "L1", "name": "4 Nos 13A TWIN S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B", "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING/G.I. CONDUIT"},
]

requirements = {
    "supply_type": "three_phase",
    "kva": 69.282,
    "voltage": 400,
    "supply_source": "landlord",
    "incoming_cable": "4x16mm²/1C PVC/PVC CABLE + 16mm² CPC IN METAL TRUNKING",
    # Meter board main breaker (same as main incomer)
    "main_breaker": {
        "type": "MCB",
        "rating": 63,
        "poles": "TPN",
        "fault_kA": 10,
        "breaker_characteristic": "B",
    },
    # ELCB
    "elcb": {
        "type": "ELCB",
        "rating": 63,
        "sensitivity_ma": 30,
        "poles": 4,
    },
    "busbar_rating": 100,
    "sub_circuits": circuits,
    "db_name": "63A DB",
}

application_info = {
    "clientName": "ULTIMED HEALTHCARE CLINICS",
    "address": "BLK 824 TAMPINES STREET 81 #01-36",
    "unit_number": "#01-36",
    "drawing_number": "NSI_UHC_TAM_01",
    "mainContractor": "FIRE SOLUTIONS ENGINEERING PTE LTD",
    "electricalContractor": "NEWSPACE INTERIOR PTE LTD",
    "sld_only_mode": True,
}

output_dir = os.path.join(os.path.dirname(__file__), "..", "output")
os.makedirs(output_dir, exist_ok=True)

pdf_path = os.path.join(output_dir, "63A_DB_SLD.pdf")
svg_path = os.path.join(output_dir, "63A_DB_SLD.svg")

result = SldPipeline().run(requirements, application_info=application_info)
result.save(pdf_path, svg_path, pdf_path.replace(".pdf", ".dxf"))

print(f"\nPDF: {pdf_path}")
print(f"Components: {result.component_count}")
print("Done!")
