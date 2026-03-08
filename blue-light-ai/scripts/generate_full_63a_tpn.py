#!/usr/bin/env python3
"""Generate SLD from reference '63A TPN SLD 14.pdf' (26 circuits).

This reproduces the reference SLD layout:
- Left group: 8 lighting/emergency circuits (B10A SPN MCB 6kA)
- Right group: 18 socket/isolator circuits (B20A SPN MCB 6kA)
- Total: 26 circuits on 1 busbar

DB Summary:
- DB: 63A DB
- Approved load: 69.282 kVA at 400V
- Main incomer: 63A TPN MCB, 10kA, Type B
- ELCB: 63A 4P ELCB, 30mA
- Submain cable: 4 x 16mm² 1C PVC/PVC + 16mm² CPC in metal trunking
- Busbar: 100A comb bar
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.sld.generator import SldGenerator


def _lighting_circuit(name: str, load: str) -> dict:
    """Create a lighting circuit (B10A, 1.5mm² cable)."""
    return {
        "name": name,
        "load": load,
        "breaker": {"type": "MCB", "rating": 10, "characteristic": "B", "poles": "SPN"},
        "fault_kA": 6,
        "cable": {
            "cores": 1,
            "count": 2,
            "size_mm2": "1.5",
            "type": "PVC",
            "cpc_mm2": "1.5",
            "cpc_type": "PVC",
            "method": "METAL TRUNKING / G.I. CONDUIT",
        },
    }


def _socket_circuit(name: str, load: str) -> dict:
    """Create a socket outlet circuit (B20A, 2.5mm² cable)."""
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
    """Build the 26-circuit list matching reference '63A TPN SLD 14.pdf'.

    Left group (B10A): 7 lighting/emergency + 1 spare = 8
    Right group (B20A): 13 socket + 3 isolator + 2 spare = 18
    Total: 26
    """
    # Left group — Lighting/Emergency (B10A SPN MCB 6kA, 1.5mm² cable)
    left_group = [
        _lighting_circuit("L1S", "1 no. LIGHTS"),
        _lighting_circuit("L2S", "2 nos. EMERGENCY CABINET LED"),
        _lighting_circuit("L3S", "1 no. BATTERY UNIT"),
        _lighting_circuit("L2S", "2 nos. EMERGENCY CABINET (LED)"),
        _lighting_circuit("L3S", "1 no. EMERGENCY CABINET (LED)"),
        _lighting_circuit("L2S", "2 nos. LIGHTS - CORE LED"),
        _lighting_circuit("L3S", "11 nos. LIGHTS - CORE LED, DIRT LIGHT + 1 no. SIGNAGE"),
        _spare_circuit(),
    ]

    # Right group — Socket/Isolator (B20A SPN MCB 6kA, 2.5mm² cable)
    right_group = [
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

    return left_group + right_group


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
        "unit_number": "#01-36",
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
