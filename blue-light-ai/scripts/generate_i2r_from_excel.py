#!/usr/bin/env python3
"""Generate SLD from I2R-ETR-NLB-SLD_Formatted.xlsx requirements.

Reads the Excel specification and builds a multi-DB requirements dict
with CT metering configuration, then generates PDF/SVG/DXF.
"""
import json
import os
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

OUTPUT_DIR = Path("/Users/ringo/Downloads/sld_test_output")
OUTPUT_DIR.mkdir(exist_ok=True)


def build_requirements() -> dict:
    """Build multi-DB requirements dict from Excel data."""

    # Phase mapping: R→L1, Y→L2, B→L3
    PHASE_MAP = {"R": "L1", "Y": "L2", "B": "L3"}

    def map_phase(phase_str: str) -> str:
        return PHASE_MAP.get(phase_str, "")

    # ── MSB (DB1) circuits ──
    msb_circuits = [
        # R-phase (L1)
        {"name": "3 Nos Lighting Points", "circuit_id": "RL1", "breaker_type": "MCB", "breaker_rating": 10, "fault_kA": 6, "cable": "2 x 1.5sqmm PVC cable + 1.5sqmm CPC in metal trunking / conduit", "phase": "L1"},
        {"name": "3 Nos Lighting Points", "circuit_id": "RL2", "breaker_type": "MCB", "breaker_rating": 10, "fault_kA": 6, "cable": "2 x 1.5sqmm PVC cable + 1.5sqmm CPC in metal trunking / conduit", "phase": "L1"},
        {"name": "3 Nos Lighting Points", "circuit_id": "RL3", "breaker_type": "MCB", "breaker_rating": 10, "fault_kA": 6, "cable": "2 x 1.5sqmm PVC cable + 1.5sqmm CPC in metal trunking / conduit", "phase": "L1"},
        {"name": "2 Nos 13A Double S/S/O", "circuit_id": "RS1", "breaker_type": "MCB", "breaker_rating": 20, "fault_kA": 6, "cable": "2 x 2.5sqmm PVC cable + 2.5sqmm CPC in metal trunking / conduit", "phase": "L1"},
        {"name": "3 Nos 13A Double S/S/O", "circuit_id": "RS2", "breaker_type": "MCB", "breaker_rating": 20, "fault_kA": 6, "cable": "2 x 2.5sqmm PVC cable + 2.5sqmm CPC in metal trunking / conduit", "phase": "L1"},
        # Y-phase (L2)
        {"name": "3 Nos Lighting Points", "circuit_id": "YL1", "breaker_type": "MCB", "breaker_rating": 10, "fault_kA": 6, "cable": "2 x 1.5sqmm PVC cable + 1.5sqmm CPC in metal trunking / conduit", "phase": "L2"},
        {"name": "2 Nos 13A Double S/S/O", "circuit_id": "YS1", "breaker_type": "MCB", "breaker_rating": 20, "fault_kA": 6, "cable": "2 x 2.5sqmm PVC cable + 2.5sqmm CPC in metal trunking / conduit", "phase": "L2"},
        {"name": "1 No. 32A DP Isolator", "circuit_id": "YISO1", "breaker_type": "MCB", "breaker_rating": 32, "fault_kA": 6, "cable": "2 x 6sqmm PVC cable + 6sqmm CPC in metal trunking / conduit", "phase": "L2"},
        {"name": "1 No. Heater Point", "circuit_id": "YH3", "breaker_type": "MCB", "breaker_rating": 20, "fault_kA": 6, "cable": "2 x 4sqmm PVC cable + 4sqmm CPC in metal trunking / conduit", "phase": "L2"},
        # B-phase (L3)
        {"name": "3 Nos Lighting Points", "circuit_id": "BL1", "breaker_type": "MCB", "breaker_rating": 10, "fault_kA": 6, "cable": "2 x 1.5sqmm PVC cable + 1.5sqmm CPC in metal trunking / conduit", "phase": "L3"},
        {"name": "2 Nos Lighting Points", "circuit_id": "BL2", "breaker_type": "MCB", "breaker_rating": 10, "fault_kA": 6, "cable": "2 x 1.5sqmm PVC cable + 1.5sqmm CPC in metal trunking / conduit", "phase": "L3"},
        {"name": "1 No. 20A DP Isolator", "circuit_id": "BISO3", "breaker_type": "MCB", "breaker_rating": 20, "fault_kA": 6, "cable": "2 x 4sqmm PVC cable + 4sqmm CPC in metal trunking / conduit", "phase": "L3"},
        {"name": "1 No. 32A DP Isolator", "circuit_id": "BISO4", "breaker_type": "MCB", "breaker_rating": 32, "fault_kA": 6, "cable": "2 x 6sqmm PVC cable + 6sqmm CPC in metal trunking / conduit", "phase": "L3"},
        # 3-phase circuit
        {"name": "1 No. 20A TPN Isolator", "circuit_id": "RYBISO5", "breaker_type": "MCB", "breaker_rating": 20, "breaker_poles": "TPN", "fault_kA": 6, "cable": "4 x 4sqmm PVC cable + 4sqmm CPC in metal trunking / conduit"},
        # Spare
        {"name": "Spare", "circuit_id": "SPARE", "breaker_type": "MCB", "breaker_rating": 0, "fault_kA": 6, "cable": ""},
        # Feeder to DB2
        {"name": "Feeder to DB2", "circuit_id": "DB2 Feeder", "breaker_type": "MCB", "breaker_rating": 63, "breaker_characteristic": "C", "fault_kA": 10, "cable": "4 x 16mm² PVC cable + 50mm² CPC", "_is_feeder": True, "_feeds_db": "DB2"},
    ]

    # ── DB2 circuits (with protection groups) ──
    def make_circuit(name, cid, rating, cable):
        return {"name": name, "circuit_id": cid, "breaker_type": "MCB", "breaker_rating": rating, "fault_kA": 6, "cable": cable}

    cable_15 = "2 x 1.5sqmm PVC cable + 1.5sqmm CPC in metal trunking / conduit"
    cable_25 = "2 x 2.5sqmm PVC cable + 2.5sqmm CPC in metal trunking / conduit"

    db2_protection_groups = [
        {
            "phase": "L1",
            "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
            "circuits": [
                make_circuit("2 Nos Lighting Points", "L1S1", 10, cable_15),
                make_circuit("1 No. 13A Double S/S/O", "L1P1", 20, cable_25),
                make_circuit("2 Nos 13A Double S/S/O", "L1P2", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L1P3", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L1P4", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L1P5", 20, cable_25),
            ],
        },
        {
            "phase": "L2",
            "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
            "circuits": [
                make_circuit("2 Nos Lighting Points", "L2S1", 10, cable_15),
                make_circuit("1 No. 13A Double S/S/O", "L2P1", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L2P2", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L2P3", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L2P4", 20, cable_25),
                make_circuit("2 Nos 13A Double S/S/O", "L2P5", 20, cable_25),
            ],
        },
        {
            "phase": "L3",
            "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
            "circuits": [
                make_circuit("2 Nos Lighting Points", "L3S1", 10, cable_15),
                make_circuit("1 No. 13A Double S/S/O", "L3P1", 20, cable_25),
                make_circuit("2 Nos 13A Double S/S/O", "L3P2", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L3P3", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L3P4", 20, cable_25),
                make_circuit("1 No. 13A Double S/S/O", "L3P5", 20, cable_25),
            ],
        },
    ]

    # ── Full requirements dict ──
    requirements = {
        "supply_type": "three_phase",
        "kva": 69.282,
        "voltage": 400,
        "supply_source": "landlord",
        "incoming_cable": "4 x 50mm² PVC/PVC cable + 50mm² CPC in metal trunking",
        "outgoing_cable": "4 x 35mm² PVC cable + 50mm² CPC in cable tray",
        "main_breaker": {
            "type": "MCCB",
            "rating": 100,
            "poles": "TPN",
            "fault_kA": 35,
        },
        "busbar_rating": 100,
        "earth_protection": True,

        # CT Metering — key addition for Phase 8
        "metering": "ct_meter",
        "metering_config": {
            "type": "ct_meter",
            "ct_ratio": "100/5A",
            "isolator_rating_a": 100,
            "metering_ct_class": "CL1 5VA",
            "protection_ct_ratio": "100/5A",
            "protection_ct_class": "5P10 20VA",
            "has_ammeter": True,
            "has_voltmeter": True,
            "has_elr": True,
            "elr_spec": "0-3A 0.2sec",
            "voltmeter_range": "0-500V",
            "ammeter_range": "0-100A",
        },

        "distribution_boards": [
            {
                "name": "MSB",
                "breaker": {
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
                "busbar_rating": 100,
                "sub_circuits": msb_circuits,
            },
            {
                "name": "DB2",
                "fed_from": "MSB",
                "breaker": {
                    "type": "MCB",
                    "rating": 40,
                    "poles": "TPN",
                    "fault_kA": 6,
                    "breaker_characteristic": "B",
                },
                "busbar_rating": 80,
                "protection_groups": db2_protection_groups,
            },
        ],
        "db_topology": "hierarchical",
    }

    return requirements


def main():
    print("=" * 60)
    print("I2R-ETR-NLB SLD Generation from Excel")
    print("=" * 60)

    # Build requirements
    print("\n[1/3] Building requirements from Excel data...")
    requirements = build_requirements()

    # Save requirements JSON for reference
    req_path = OUTPUT_DIR / "03_requirements_from_excel.json"
    with open(req_path, "w") as f:
        json.dump(requirements, f, indent=2, ensure_ascii=False, default=str)
    print(f"  Requirements saved: {req_path}")

    dbs = requirements.get("distribution_boards", [])
    print(f"  Supply: {requirements['supply_type']} {requirements['kva']} kVA")
    print(f"  Metering: {requirements['metering']}")
    print(f"  CT ratio: {requirements['metering_config']['ct_ratio']}")
    print(f"  Distribution boards: {len(dbs)}")
    for db in dbs:
        subs = db.get("sub_circuits", [])
        pgs = db.get("protection_groups", [])
        total = len(subs) + sum(len(pg.get("circuits", [])) for pg in pgs)
        print(f"    - {db['name']}: {total} circuits, {len(pgs)} protection groups")

    # Generate SLD
    print("\n[2/3] Generating SLD (PDF + SVG + DXF)...")

    application_info = {
        "clientName": "Easytentage.com Pte Ltd",
        "address": "North Link Building #05-26, 10 Admiralty St, Singapore 757695",
        "unit_number": "#05-26",
        "drawing_number": "I2R-ETR-NLB-SLD",
        "sld_only_mode": True,
    }

    from app.sld.generator import SldGenerator

    try:
        pdf_bytes, svg_string, dxf_bytes = SldGenerator.generate_pdf_bytes(
            requirements=requirements,
            application_info=application_info,
        )
    except Exception as e:
        print(f"  Generation failed: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

    # Save outputs
    print("\n[3/3] Saving output files...")

    pdf_path = OUTPUT_DIR / "I2R-ETR-NLB_from_excel.pdf"
    pdf_path.write_bytes(pdf_bytes)
    print(f"  PDF: {pdf_path} ({len(pdf_bytes):,} bytes)")

    svg_path = OUTPUT_DIR / "I2R-ETR-NLB_from_excel.svg"
    svg_path.write_text(svg_string, encoding="utf-8")
    print(f"  SVG: {svg_path} ({len(svg_string):,} chars)")

    if dxf_bytes:
        dxf_path = OUTPUT_DIR / "I2R-ETR-NLB_from_excel.dxf"
        dxf_path.write_bytes(dxf_bytes)
        print(f"  DXF: {dxf_path} ({len(dxf_bytes):,} bytes)")

    print("\n" + "=" * 60)
    print("DONE! Open to verify:")
    print(f'  open "{pdf_path}"')
    print("=" * 60)


if __name__ == "__main__":
    main()
