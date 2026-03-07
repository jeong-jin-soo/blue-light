#!/usr/bin/env python3
"""Generate a Cable Extension SLD for visual verification."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.sld.generator import SldGenerator


def main():
    requirements = {
        "supply_type": "three_phase",
        "kva": 45,
        "supply_source": "landlord",
        "is_cable_extension": True,
        "main_breaker": {
            "type": "MCCB",
            "rating": 63,
            "poles": "TPN",
        },
        "sub_circuits": [
            {"name": "Aircon 1", "breaker": {"type": "MCB", "rating": 20}, "load": "Aircon"},
            {"name": "Aircon 2", "breaker": {"type": "MCB", "rating": 20}, "load": "Aircon"},
            {"name": "Lighting", "breaker": {"type": "MCB", "rating": 10}, "load": "Lighting"},
            {"name": "Power Socket", "breaker": {"type": "MCB", "rating": 16}, "load": "Socket"},
            {"name": "Spare", "breaker": {"type": "MCB", "rating": 20}, "load": "SPARE"},
        ],
    }

    application_info = {
        "client_name": "Cable Extension Test",
        "address": "123 Test Street, Singapore",
        "premises_type": "Commercial",
    }

    gen = SldGenerator()

    output_dir = Path(__file__).resolve().parent.parent / "output"
    output_dir.mkdir(exist_ok=True)

    pdf_path = output_dir / "test_cable_extension.pdf"
    svg_path = output_dir / "test_cable_extension.svg"

    result = gen.generate(
        requirements,
        application_info,
        pdf_output_path=str(pdf_path),
        svg_output_path=str(svg_path),
    )
    print(f"  Components: {result.get('component_count', '?')}")
    print(f"  PDF: {pdf_path}")
    print(f"  SVG: {svg_path}")


if __name__ == "__main__":
    main()
