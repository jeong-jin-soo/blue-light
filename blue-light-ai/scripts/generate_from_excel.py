"""Generate SLD directly from 63A_DB_complete_schedule.xlsx."""

import sys
import os
import re

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import openpyxl
from app.sld.generator import SldPipeline


def parse_breaker(breaker_str: str, circuit_id: str, description: str):
    """Parse breaker string like '10A SPN MCB, 6kA, Type B' into dict fields."""
    result = {}

    # Detect ISOLATOR from circuit_id or description
    if circuit_id.upper().startswith("ISOL") or "isolator" in description.lower():
        result["breaker_type"] = "ISOLATOR"
        m = re.search(r"(\d+)A", description)
        if m:
            result["breaker_rating"] = int(m.group(1))
        # Extract poles from description (e.g., "DP" = 2P)
        if "DP" in description.upper():
            result["breaker_poles"] = "DP"
        return result

    # Rating
    m = re.match(r"(\d+)A", breaker_str)
    if m:
        result["breaker_rating"] = int(m.group(1))

    # Poles
    if "TPN" in breaker_str:
        result["breaker_poles"] = "TPN"
    elif "SPN" in breaker_str:
        result["breaker_poles"] = "SPN"

    # Type
    if "MCB" in breaker_str:
        result["breaker_type"] = "MCB"
    elif "MCCB" in breaker_str:
        result["breaker_type"] = "MCCB"

    # Fault rating
    m = re.search(r"(\d+)kA", breaker_str)
    if m:
        result["fault_kA"] = int(m.group(1))

    # Characteristic
    m = re.search(r"Type\s+([A-Z])", breaker_str)
    if m:
        result["breaker_characteristic"] = m.group(1)

    return result


def format_load_description(desc: str) -> str:
    """Format load description to match SLD standard.

    '1 light' → '1 Nos LIGHTS'
    '2 lights + 1 emergency LED' → '2 Nos LIGHTS + 1 Nos EMERGENCY LED'
    """
    desc = desc.strip()

    # Common replacements
    replacements = [
        (r"(\d+)\s+lights?\b", lambda m: f"{m.group(1)} Nos LIGHTS"),
        (r"(\d+)\s+fans?\b", lambda m: f"{m.group(1)} Nos FANS"),
        (r"(\d+)\s+emergency\s+LED", lambda m: f"{m.group(1)} Nos EMERGENCY CABINET LED"),
        (r"(\d+)\s+emergency\s+lights?\b", lambda m: f"{m.group(1)} Nos EMERGENCY LIGHTS"),
        (r"(\d+)\s+exit\s+lights?\b", lambda m: f"{m.group(1)} Nos EXIT LIGHT"),
        (r"(\d+)\s+single\s+SSO", lambda m: f"{m.group(1)} Nos 13A SINGLE S/S/O"),
        (r"(\d+)\s+twin\s+SSO", lambda m: f"{m.group(1)} Nos 13A TWIN S/S/O"),
        (r"\bcove\s+LED\b", "COVE LED"),
        (r"\bexit\s+light\b", "EXIT LIGHT"),
        (r"\bsignage\b", "1 Nos SIGNAGE"),
    ]

    for pattern, repl in replacements:
        desc = re.sub(pattern, repl, desc, flags=re.IGNORECASE)

    return desc.upper() if not any(c.isupper() for c in desc) else desc


def format_cable(cable_str: str) -> str:
    """Pass cable string through, adding conduit info if missing."""
    cable = cable_str.strip()
    # Add conduit type if not present
    if "IN " not in cable.upper():
        cable += " IN PVC CONDUIT"
    return cable


# --- Read Excel ---
xlsx_path = "/Users/ringo/Downloads/63A_DB_complete_schedule.xlsx"
wb = openpyxl.load_workbook(xlsx_path)

# Parse circuit schedule
ws = wb["Circuit Schedule"]
sub_circuits = []
for row in ws.iter_rows(min_row=4, values_only=True):  # Skip header rows
    cid, desc, breaker_str, cable, section = row[:5]
    if not cid:
        continue

    sc = {
        "circuit_id": str(cid).strip(),
        "name": format_load_description(str(desc)),
        "cable": format_cable(str(cable)),
    }
    sc.update(parse_breaker(str(breaker_str), str(cid), str(desc)))
    sub_circuits.append(sc)

print(f"Parsed {len(sub_circuits)} circuits from Excel")
for sc in sub_circuits:
    print(f"  {sc['circuit_id']}: {sc['name']} | {sc.get('breaker_type','')} {sc.get('breaker_rating','')}A")

# --- Build requirements ---
requirements = {
    "supply_type": "three_phase",
    "kva": 69.282,
    "voltage": 400,
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
    "metering": "sp_meter",
    "supply_source": "landlord",
    "incoming_cable": "4 x 16mm² 1C PVC/PVC CABLE + 16mm² CPC IN METAL TRUNKING",
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
    "sub_circuits": sub_circuits,
}

application_info = {
    "clientName": "ULTIMED HEALTHCARE CLINICS",
    "address": "BLK 824 TAMPINES STREET 81 #01-36",
    "mainContractor": "FIRE SOLUTIONS ENGINEERING PTE LTD",
    "electrical_contractor": "NEWSPACE INTERIOR PTE LTD",
    "contractor_address": "6D MANDAI ESTATE, #09-06 SINGAPORE 729938",
    "drawing_number": "NSI_UHC_TAM_01",
    "sld_only_mode": True,
}

output_dir = os.path.join(os.path.dirname(__file__), "..", "output")
os.makedirs(output_dir, exist_ok=True)

pdf_path = os.path.join(output_dir, "63A_from_excel.pdf")
svg_path = os.path.join(output_dir, "63A_from_excel.svg")

result = SldPipeline().run(requirements, application_info=application_info)
result.save(pdf_path, svg_path, pdf_path.replace(".pdf", ".dxf"))

print(f"\nPDF: {pdf_path}")
print(f"Components: {result.component_count}")
print("Done!")
