#!/usr/bin/env python3
"""Generate SLD from I2R-ETR-NLB-SLD_Formatted.xlsx — Multi-DB (MSB + DB2)."""

import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.sld.generator import SldPipeline

# === MSB (DB1) — 15 outgoing circuits + 1 spare ===
msb_circuits = [
    # R phase — Lighting
    {"circuit_id": "RL1", "phase": "R", "name": "3 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "RL2", "phase": "R", "name": "3 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "RL3", "phase": "R", "name": "3 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    # R phase — Sockets
    {"circuit_id": "RS1", "phase": "R", "name": "2 Nos 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "RS2", "phase": "R", "name": "3 Nos 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    # Y phase — Lighting
    {"circuit_id": "YL1", "phase": "Y", "name": "3 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    # Y phase — Socket
    {"circuit_id": "YS1", "phase": "Y", "name": "2 Nos 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    # Y phase — Isolator 32A
    {"circuit_id": "YISO1", "phase": "Y", "name": "1 No. 32A DP ISOLATOR", "breaker_type": "MCB", "breaker_rating": 32, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 6sqmm PVC + 6sqmm CPC IN METAL TRUNKING"},
    # Y phase — Heater
    {"circuit_id": "YH3", "phase": "Y", "name": "1 No. HEATER POINT", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 4sqmm PVC + 4sqmm CPC IN METAL TRUNKING"},
    # B phase — Lighting
    {"circuit_id": "BL1", "phase": "B", "name": "3 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "BL2", "phase": "B", "name": "2 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    # B phase — Isolators
    {"circuit_id": "BISO3", "phase": "B", "name": "1 No. 20A DP ISOLATOR", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 4sqmm PVC + 4sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "BISO4", "phase": "B", "name": "1 No. 32A DP ISOLATOR", "breaker_type": "MCB", "breaker_rating": 32, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 6sqmm PVC + 6sqmm CPC IN METAL TRUNKING"},
    # 3-phase — Isolator
    {"circuit_id": "RYBISO5", "phase": "RYB", "name": "1 No. 20A TPN ISOLATOR", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "TPN", "fault_kA": 6, "cable": "4 x 1C 4sqmm PVC + 4sqmm CPC IN METAL TRUNKING"},
    # Spare
    {"circuit_id": "SPARE", "name": "SPARE", "breaker_type": "SPARE"},
]

# === DB2 — 18 outgoing circuits ===
db2_circuits = [
    # L1 phase
    {"circuit_id": "L1S1", "phase": "L1", "name": "2 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L1P1", "phase": "L1", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L1P2", "phase": "L1", "name": "2 Nos 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L1P3", "phase": "L1", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L1P4", "phase": "L1", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L1P5", "phase": "L1", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    # L2 phase
    {"circuit_id": "L2S1", "phase": "L2", "name": "2 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L2P1", "phase": "L2", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L2P2", "phase": "L2", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L2P3", "phase": "L2", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L2P4", "phase": "L2", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L2P5", "phase": "L2", "name": "2 Nos 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    # L3 phase
    {"circuit_id": "L3S1", "phase": "L3", "name": "2 Nos LIGHTING POINTS", "breaker_type": "MCB", "breaker_rating": 10, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L3P1", "phase": "L3", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L3P2", "phase": "L3", "name": "2 Nos 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L3P3", "phase": "L3", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L3P4", "phase": "L3", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
    {"circuit_id": "L3P5", "phase": "L3", "name": "1 No. 13A DOUBLE S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "SPN", "fault_kA": 6, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN METAL TRUNKING"},
]

requirements = {
    "supply_type": "three_phase",
    "kva": 69.282,
    "voltage": 400,
    "supply_source": "building_riser",
    "incoming_cable": "4 x 50mm² PVC/PVC CABLE + 50mm² CPC IN METAL TRUNKING",
    "metering": "ct_meter",
    "ct_ratio": "100/5A",
    "metering_detail": {
        "ct_ratio": "100/5A",
        "protection_ct_ratio": "100/5A",
        "protection_ct_class": "5P10 20VA",
        "metering_ct_class": "CL1 5VA",
        "has_ammeter": True,
        "has_voltmeter": True,
        "has_elr": True,
        "elr_spec": "0-3A 0.2 SEC",
        "ammeter_range": "0-100A",
        "voltmeter_range": "0-500V",
    },
    "main_breaker": {
        "type": "MCCB",
        "rating": 100,
        "poles": "TPN",
        "fault_kA": 35,
    },
    # [문제 4] RCCB+MCB 직렬 구조
    "elcb": {
        "type": "RCCB",
        "rating": 63,
        "sensitivity_ma": 30,
        "poles": 4,
    },
    "post_elcb_mcb": {
        "type": "MCB",
        "rating": 63,
        "poles": "TPN",
        "breaker_characteristic": "B",
        "fault_kA": 10,
    },
    # [문제 6] Internal cable (MCCB→busbar 구간)
    "internal_cable": "4 x 35mm² PVC CABLE + 50mm² CPC IN CABLE TRAY",
    "busbar_rating": 100,
    "busbar_width_ratio": 0.70,
    "isolator": {
        "rating": 100,
        "type": "4P",
        "location_text": "LOCATED INSIDE UNIT",
    },
    "distribution_boards": [
        {
            "db_name": "MSB",
            "kva": 69.282,
            "sub_circuits": msb_circuits,
            "location_text": "LOCATED INSIDE UNIT #05-26",
            # [문제 5] Feeder MCB to DB2
            "feeder_breaker": {
                "type": "MCB",
                "rating": 63,
                "poles": "TPN",
                "breaker_characteristic": "C",
                "fault_kA": 10,
            },
            "feeder_cable": "4 x 16mm² PVC CABLE + 50mm² CPC IN CABLE TRAY",
        },
        {
            "db_name": "DB2",
            # [문제 2] fed_from → 자동 hierarchical 감지
            "fed_from": "MSB",
            # [문제 1] incoming_breaker 키 → 이제 소비됨
            "incoming_breaker": {
                "type": "MCB",
                "rating": 40,
                "poles": "TPN",
                "breaker_characteristic": "B",
                "fault_kA": 6,
            },
            "busbar_rating": 80,
            # DB2 per-phase RCCB protection groups
            "protection_groups": [
                {
                    "phase": "L1",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "busbar_rating": 80,
                    "circuits": [c for c in db2_circuits if c.get("phase") == "L1"],
                },
                {
                    "phase": "L2",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "busbar_rating": 80,
                    "circuits": [c for c in db2_circuits if c.get("phase") == "L2"],
                },
                {
                    "phase": "L3",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "busbar_rating": 80,
                    "circuits": [c for c in db2_circuits if c.get("phase") == "L3"],
                },
            ],
            "sub_circuits": [],  # circuits are in protection_groups
        },
    ],
    "sub_circuits": msb_circuits,
}

application_info = {
    "clientName": "EASYTENTAGE.COM PTE LTD",
    "address": "NORTH LINK BUILDING #05-26, 10 ADMIRALTY ST, SINGAPORE 757695",
    "drawing_number": "I2R-ETR-NLB-SLD",
    "sld_only_mode": True,
}

output_dir = os.path.join(os.path.dirname(__file__), "..", "output")
os.makedirs(output_dir, exist_ok=True)

pdf_path = os.path.join(output_dir, "I2R_ETR_NLB_SLD.pdf")
svg_path = os.path.join(output_dir, "I2R_ETR_NLB_SLD.svg")

result = SldPipeline().run(requirements, application_info=application_info)
result.save(pdf_path, svg_path, pdf_path.replace(".pdf", ".dxf"))

print(f"\nPDF: {pdf_path}")
print(f"Components: {result.component_count}")
print("Done!")
