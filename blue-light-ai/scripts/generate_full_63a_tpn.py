#!/usr/bin/env python3
"""Generate SLD from SG team's full 63A TPN circuit table (36 circuits).

This reproduces the reference SLD '63A TPN SLD 14.pdf' using the complete
circuit table provided by the Singapore team on 2026-03-08.

DB Summary:
- DB: 63A DB
- Approved load: 69.282 kVA at 400V
- Main incomer: 63A TPN MCB, 10kA, Type B
- ELCB: 63A 4P ELCB, 30mA
- Submain cable: 4 x 16mm² 1C PVC/PVC + 16mm² CPC in metal trunking
- Busbar: 100A comb bar

Circuit Table:
- 2 sets of 18 circuits each (total 36)
- Each set: 16 active socket/isolator circuits + 2 spare
- All MCBs: 20A SPN MCB, 6kA, Type B
- All cables: 2 x 1C 2.5mm² PVC + 2.5mm² CPC in G.I. conduit / metal trunking
- 3 ISOLATORs per set: 20A DP isolator
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.sld.generator import SldGenerator


def _socket_circuit(name: str, load: str) -> dict:
    """Create a socket outlet circuit with SG standard specs."""
    return {
        "name": name,
        "load": load,
        "breaker": {"type": "MCB", "rating": 20, "characteristic": "B", "poles": "SPN"},
        "fault_kA": 6,
        "cable": {
            "cores": 1,
            "count": 2,
            "size_mm2": "2.5",
            "type": "PVC",
            "cpc_mm2": "2.5",
            "cpc_type": "PVC",
            "method": "G.I. CONDUIT / METAL TRUNKING",
        },
    }


def _isolator_circuit(name: str) -> dict:
    """Create a 20A DP isolator circuit."""
    return {
        "name": name,
        "load": "1 no. 20A DP isolator",
        "breaker": {"type": "ISOLATOR", "rating": 20, "poles": "DP"},
        "cable": {
            "cores": 1,
            "count": 2,
            "size_mm2": "2.5",
            "type": "PVC",
            "cpc_mm2": "2.5",
            "cpc_type": "PVC",
            "method": "G.I. CONDUIT / METAL TRUNKING",
        },
    }


def _spare_circuit() -> dict:
    """Create a spare circuit."""
    return {
        "name": "Spare",
        "load": "Spare",
    }


def build_circuits() -> list[dict]:
    """Build the full 36-circuit list from SG team's table."""
    # Each set has 18 circuits (identical top and bottom sets)
    def one_set() -> list[dict]:
        return [
            # Group P1
            _socket_circuit("L1P1", "1 no. 13A single switched socket outlet + 2 nos. 13A twin switched socket outlets"),
            _socket_circuit("L2P1", "1 no. 13A single switched socket outlet + 1 no. 13A twin switched socket outlet"),
            _socket_circuit("L3P1", "1 no. 13A single switched socket outlet + 1 no. 13A twin switched socket outlet"),
            # ISOL 1
            _isolator_circuit("ISOL 1"),
            # Group P2
            _socket_circuit("L2P2", "1 no. 13A single switched socket outlet"),
            _socket_circuit("L3P2", "1 no. 13A twin switched socket outlet"),
            # Group P3
            _socket_circuit("L1P3", "2 nos. 13A twin switched socket outlets"),
            _socket_circuit("L2P3", "1 no. 13A single switched socket outlet"),
            # ISOL 2
            _isolator_circuit("ISOL 2"),
            # Group P4
            _socket_circuit("L1P4", "1 no. 13A twin switched socket outlet"),
            _socket_circuit("L2P4", "2 nos. 13A twin switched socket outlets"),
            # ISOL 3
            _isolator_circuit("ISOL 3"),
            # Group P5
            _socket_circuit("L1P5", "2 nos. 13A twin switched socket outlets"),
            _socket_circuit("L2P5", "2 nos. 13A twin switched socket outlets"),
            _socket_circuit("L3P5", "1 no. 13A twin switched socket outlet"),
            # Group P6
            _socket_circuit("L1P6", "4 nos. 13A twin switched socket outlets"),
            # Spares
            _spare_circuit(),
            _spare_circuit(),
        ]

    # Full DB: two sets (row 1 + row 2, matching reference PDF layout)
    return one_set() + one_set()


def main():
    requirements = {
        "supply_type": "three_phase",
        "kva": 69.282,
        "supply_source": "landlord",
        "metering": "ct_meter",
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
            "poles": "4P",
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
        "sub_circuits": build_circuits(),
    }

    application_info = {
        "client_name": "ULTIMED HEALTHCARE CLINICS",
        "client_address": "BLK 824 TAMPINES STREET 81 #01-36",
        "unit_number": "#01-36",  # Unit number for isolator/DB labels
        "postalCode": "",
        "premises_type": "commercial",
        "drawing_no": "NSI_UHC_TAM_01",
        "main_contractor": "FIRE SOLUTIONS ENGINEERING PTE LTD",
        "electrical_contractor": "NEWSPACE INTERIOR PTE LTD\n6D MANDAI ESTATE, #09-06\nSINGAPORE 729938",
    }

    gen = SldGenerator()

    output_dir = Path(__file__).resolve().parent.parent / "output"
    output_dir.mkdir(exist_ok=True)

    pdf_path = output_dir / "test_full_63a_tpn.pdf"
    svg_path = output_dir / "test_full_63a_tpn.svg"

    print(f"Generating SLD with {len(requirements['sub_circuits'])} circuits...")
    result = gen.generate(
        requirements,
        application_info,
        pdf_output_path=str(pdf_path),
        svg_output_path=str(svg_path),
    )
    print(f"  Components: {result.get('component_count', '?')}")
    print(f"  PDF: {pdf_path}")
    print(f"  SVG: {svg_path}")
    if result.get("dxf_path"):
        print(f"  DXF: {result['dxf_path']}")
    print("Done!")


if __name__ == "__main__":
    main()
