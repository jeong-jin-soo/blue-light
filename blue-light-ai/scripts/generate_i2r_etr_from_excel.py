#!/usr/bin/env python3
"""Generate SLD from I2R-ETR-NLB-SLD_Formatted.xlsx — direct Excel parsing (no Gemini)."""

import re
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import openpyxl
from app.sld.generator import SldGenerator

XLSX = "/Users/ringo/Downloads/I2R-ETR-NLB-SLD_Formatted.xlsx"
wb = openpyxl.load_workbook(XLSX, read_only=True, data_only=True)


def _cell_val(ws, col, row):
    v = ws.cell(row=row, column=col).value
    return str(v).strip() if v is not None else ""


def _parse_circuits(ws, start_row):
    """Parse circuit rows starting from start_row until empty."""
    circuits = []
    phase_groups = {}  # protection_group_name -> list of circuits
    r = start_row
    while True:
        cid = _cell_val(ws, 1, r)
        if not cid:
            break
        phase = _cell_val(ws, 2, r)
        rating_str = _cell_val(ws, 3, r)
        breaker_type = _cell_val(ws, 4, r)
        ka_str = _cell_val(ws, 5, r)
        desc = _cell_val(ws, 6, r)
        cable = _cell_val(ws, 7, r)
        pgroup = _cell_val(ws, 8, r)

        rating = int(re.search(r"(\d+)", rating_str).group(1)) if re.search(r"(\d+)", rating_str) else 0
        ka = int(re.search(r"(\d+)", ka_str).group(1)) if re.search(r"(\d+)", ka_str) else 6

        # Parse breaker type and poles
        bt = "MCB"
        poles = "SPN"
        if "MCCB" in breaker_type.upper():
            bt = "MCCB"
        if "TPN" in breaker_type.upper():
            poles = "TPN"
        elif "DP" in breaker_type.upper():
            poles = "DP"

        # Detect ISOLATOR circuits by circuit ID (e.g., YISO1, BISO3, RYBISO5)
        # or by description containing "Isolator"
        if "ISO" in cid.upper() or "isolator" in desc.lower():
            bt = "ISOLATOR"
            # Extract poles from description (e.g., "1 No. 32A DP Isolator" → DP)
            if "TPN" in desc.upper() or "3P" in desc.upper() or "4P" in desc.upper():
                poles = "TPN"
            elif "DP" in desc.upper() or "2P" in desc.upper():
                poles = "DP"

        circ = {
            "circuit_id": cid,
            "phase": phase,
            "name": desc,
            "breaker_type": bt if cid.upper() != "SPARE" else "SPARE",
            "breaker_rating": rating,
            "breaker_poles": poles,
            "fault_kA": ka,
            "cable": cable,
        }

        if pgroup:
            phase_groups.setdefault(pgroup, []).append(circ)
        else:
            circuits.append(circ)
        r += 1
    return circuits, phase_groups


# === Parse MSB ===
ws_msb = wb["MSB_DB1"]
# Find OUTGOING LOAD DESCRIPTION header row
msb_data_start = None
for r in range(1, 40):
    if _cell_val(ws_msb, 1, r) == "Circuit No":
        msb_data_start = r + 1
        break

msb_circuits, msb_pgroups = _parse_circuits(ws_msb, msb_data_start)

# MSB protection group circuits should be in the main circuit list (not separated)
# e.g., RYBISO5 with pgroup="Feeder / 3-phase" is a regular MSB circuit
for pg_name in sorted(msb_pgroups.keys()):
    msb_circuits.extend(msb_pgroups[pg_name])

# === Parse DB2 ===
ws_db2 = wb["DB2"]
db2_data_start = None
for r in range(1, 40):
    if _cell_val(ws_db2, 1, r) == "Circuit No":
        db2_data_start = r + 1
        break

db2_circuits, db2_pgroups = _parse_circuits(ws_db2, db2_data_start)
wb.close()

# Build protection groups for DB2
db2_protection_groups = []
# Phase normalization: R→L1, Y→L2, B→L3
PHASE_NORM = {"RCCB L1": "L1", "RCCB L2": "L2", "RCCB L3": "L3"}
for pg_name in sorted(db2_pgroups.keys()):
    phase = PHASE_NORM.get(pg_name, pg_name.replace("RCCB ", ""))
    db2_protection_groups.append({
        "phase": phase,
        "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
        "busbar_rating": 80,
        "circuits": db2_pgroups[pg_name],
    })

print(f"MSB: {len(msb_circuits)} circuits, {len(msb_pgroups)} protection groups")
print(f"DB2: {len(db2_circuits)} circuits, {len(db2_pgroups)} protection groups → {len(db2_protection_groups)} PGs")

requirements = {
    "supply_type": "three_phase",
    "kva": 69.282,
    "voltage": 400,
    "supply_source": "building_riser",
    "incoming_cable": "4 x 50mm² PVC/PVC cable + 50mm² CPC in metal trunking",
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
    "internal_cable": "4 x 35mm² PVC cable + 50mm² CPC in cable tray",
    "busbar_rating": 100,
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
            "feeder_breaker": {
                "type": "MCB",
                "rating": 63,
                "poles": "TPN",
                "breaker_characteristic": "C",
                "fault_kA": 10,
            },
            "feeder_cable": "4 x 16mm² PVC cable + 50mm² CPC in cable tray",
        },
        {
            "db_name": "DB2",
            "fed_from": "MSB",
            "kva": 27.7,
            "incoming_breaker": {
                "type": "MCB",
                "rating": 40,
                "poles": "TPN",
                "breaker_characteristic": "B",
                "fault_kA": 6,
            },
            "busbar_rating": 80,
            "protection_groups": db2_protection_groups,
            "sub_circuits": db2_circuits,
            # No location_text for sub-DB — LEW convention shows location only on main DB
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

pdf_path = os.path.join(output_dir, "I2R_ETR_NLB_SLD_from_excel.pdf")
svg_path = os.path.join(output_dir, "I2R_ETR_NLB_SLD_from_excel.svg")

generator = SldGenerator()
result = generator.generate(
    requirements=requirements,
    application_info=application_info,
    pdf_output_path=pdf_path,
    svg_output_path=svg_path,
    backend_type="dxf",
)

print(f"\nPDF: {result['pdf_path']}")
print(f"Components: {result['component_count']}")
print("Done!")
