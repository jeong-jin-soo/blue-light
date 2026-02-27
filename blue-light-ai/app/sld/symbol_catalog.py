"""
SLD Symbol Catalog Generator.

Generates SVG files for visual verification of all electrical symbols:
- Individual SVG for each symbol (with pin/anchor markers)
- Combined grid SVG showing all symbols on one page

Usage:
    cd blue-light-ai
    venv/bin/python -m app.sld.symbol_catalog

Output:
    data/templates/sld_symbol_catalog.svg   -- Full catalog (grid view)
    data/templates/symbols/{name}.svg       -- Individual symbol SVGs
"""

from __future__ import annotations

import os
from pathlib import Path
from xml.sax.saxutils import escape

from app.sld.svg_backend import SvgBackend
from app.sld.symbols.base import BaseSymbol
from app.sld.symbols.breakers import ACB, ELCB, MCB, MCCB, RCCB
from app.sld.symbols.busbars import Busbar
from app.sld.symbols.loads import IndustrialSocket, Timer, TimerWithBypass
from app.sld.symbols.meters import Ammeter, KwhMeter, Voltmeter
from app.sld.symbols.motors import Generator, Motor
from app.sld.symbols.msb_components import IndicatorLight, ProtectionRelay, ShuntTrip
from app.sld.symbols.protection import EarthSymbol, Fuse, SurgeProtector
from app.sld.symbols.switches import (
    ATS,
    BIConnector,
    DoublePoleSwitch,
    Isolator,
    IsolatorForMachine,
)
from app.sld.symbols.transformers import CurrentTransformer, PotentialTransformer, PowerTransformer


def _get_all_symbols() -> list[tuple[str, BaseSymbol]]:
    """Return all symbol instances for catalog generation."""
    return [
        # Breakers
        ("MCB", MCB()),
        ("MCCB", MCCB()),
        ("ACB", ACB()),
        ("RCCB", RCCB()),
        ("ELCB", ELCB()),
        # Meters
        ("KWH Meter", KwhMeter()),
        ("Ammeter", Ammeter()),
        ("Voltmeter", Voltmeter()),
        # Switches
        ("Isolator (DB)", Isolator()),
        ("Isolator (Machine)", IsolatorForMachine()),
        ("Double Pole Switch", DoublePoleSwitch()),
        ("BI Connector", BIConnector()),
        ("ATS", ATS()),
        # Protection
        ("Fuse", Fuse()),
        ("Earth", EarthSymbol()),
        ("SPD", SurgeProtector()),
        # Transformers
        ("Power Transformer", PowerTransformer()),
        ("CT", CurrentTransformer()),
        ("PT", PotentialTransformer()),
        # Motors
        ("Motor", Motor()),
        ("Generator", Generator()),
        # Loads
        ("Industrial Socket", IndustrialSocket()),
        ("Timer", Timer()),
        ("Timer w/ Bypass", TimerWithBypass()),
        # MSB Components
        ("Shunt Trip", ShuntTrip()),
        ("Indicator Light", IndicatorLight("L1")),
        ("Protection Relay", ProtectionRelay()),
        # Busbar (small width for catalog)
        ("Busbar", Busbar(bus_width=60)),
    ]


def generate_individual_svgs(output_dir: str) -> list[str]:
    """
    Generate individual SVG files for each symbol.

    Args:
        output_dir: Directory to write SVG files to.

    Returns:
        List of generated file paths.
    """
    os.makedirs(output_dir, exist_ok=True)
    generated = []

    for label, symbol in _get_all_symbols():
        svg_str = symbol.to_svg(padding=12, show_pins=True, show_anchors=True)
        filename = f"{symbol.name.lower().replace(' ', '_')}.svg"
        filepath = os.path.join(output_dir, filename)
        with open(filepath, "w", encoding="utf-8") as f:
            f.write(svg_str)
        generated.append(filepath)
        print(f"  Generated: {filepath}")

    return generated


def generate_catalog_svg(output_path: str) -> str:
    """
    Generate a combined catalog SVG showing all symbols in a grid.

    Uses a single SvgBackend with normal bottom-left coordinate system.
    All symbols are drawn directly at computed positions — no group
    transforms or _flip_y hacks needed.

    Args:
        output_path: Path to write the catalog SVG.

    Returns:
        Path to the generated file.
    """
    symbols = _get_all_symbols()

    # Grid layout parameters
    cols = 5
    cell_w = 80.0  # mm per cell
    cell_h = 60.0  # mm per cell
    rows = (len(symbols) + cols - 1) // cols

    margin = 10.0
    title_area = 20.0  # mm for title at top

    page_w = cols * cell_w + 2 * margin
    page_h = rows * cell_h + title_area + 2 * margin

    # Single backend — all drawing in normal bottom-left coordinate system
    backend = SvgBackend(page_width=page_w, page_height=page_h)

    # Title (at top of page = high Y in bottom-left coords)
    backend.set_layer("SLD_TITLE_BLOCK")
    backend.add_mtext(
        "SLD Symbol Library Catalog (IEC 60617 / Singapore Standard)",
        insert=(page_w / 2 - 60, page_h - margin + 2),
        char_height=5,
    )
    backend.set_layer("SLD_ANNOTATIONS")
    backend.add_mtext(
        "Red dots = pins  |  Blue dots = anchors  |  Dashed box = bounding box",
        insert=(page_w / 2 - 50, page_h - margin - 5),
        char_height=3,
    )

    # Grid origin: bottom-left of grid area
    grid_bottom = margin
    grid_top = page_h - margin - title_area

    for idx, (label, symbol) in enumerate(symbols):
        col = idx % cols
        row = idx // cols

        # Cell position in bottom-left coordinates
        # Row 0 is the TOP row (highest Y), row N is the bottom
        cell_x = margin + col * cell_w
        cell_y = grid_top - (row + 1) * cell_h  # top-row first

        # -- Cell border (drawn directly via backend elements) --
        # Use raw SVG element since backend doesn't have dashed rect
        svg_cell_x = cell_x
        svg_cell_y = backend._flip_y(cell_y + cell_h)  # top-left in SVG
        backend._elements.append(
            f'<rect x="{svg_cell_x:.1f}" y="{svg_cell_y:.1f}" '
            f'width="{cell_w:.1f}" height="{cell_h:.1f}" '
            f'fill="none" stroke="#ddd" stroke-width="0.3" />'
        )

        # -- Cell label (near top of cell) --
        backend.set_layer("SLD_ANNOTATIONS")
        label_y = cell_y + cell_h - 3
        backend.add_mtext(
            label,
            insert=(cell_x + cell_w / 2 - len(label) * 1.2, label_y),
            char_height=3,
        )

        # -- Symbol dimensions text --
        dim_text = f"{symbol.name} ({symbol.width:.0f}x{symbol.height:.0f}mm)"
        dim_y = cell_y + cell_h - 8
        backend.add_mtext(
            dim_text,
            insert=(cell_x + cell_w / 2 - len(dim_text) * 0.8, dim_y),
            char_height=2,
        )

        # -- Draw symbol centered in cell --
        # Available drawing area: cell interior below labels
        draw_margin = 5.0
        draw_bottom = cell_y + draw_margin
        draw_top = cell_y + cell_h - 14  # leave room for labels
        draw_left = cell_x + draw_margin
        draw_right = cell_x + cell_w - draw_margin

        draw_w = draw_right - draw_left
        draw_h = draw_top - draw_bottom

        # Center symbol in drawing area (no scaling — 1:1 mm)
        sym_x = draw_left + (draw_w - symbol.width) / 2
        sym_y = draw_bottom + (draw_h - symbol.height) / 2

        # Draw the symbol using normal backend
        symbol.draw(backend, sym_x, sym_y)

        # -- Bounding box (dashed) --
        bb_svg_x = sym_x
        bb_svg_y = backend._flip_y(sym_y + symbol.height)
        backend._elements.append(
            f'<rect x="{bb_svg_x:.1f}" y="{bb_svg_y:.1f}" '
            f'width="{symbol.width:.1f}" height="{symbol.height:.1f}" '
            f'fill="none" stroke="#999" stroke-width="0.2" stroke-dasharray="2,2" />'
        )

        # -- Pin markers (red dots) --
        for pin_name, (px, py) in symbol.pins.items():
            abs_x = sym_x + px
            abs_y = sym_y + py
            svg_px = abs_x
            svg_py = backend._flip_y(abs_y)
            backend._elements.append(
                f'<circle cx="{svg_px:.1f}" cy="{svg_py:.1f}" r="1.0" '
                f'fill="red" opacity="0.7" />'
            )

        # -- Anchor markers (blue dots) --
        for anc_name, (ax, ay) in symbol.anchors.items():
            abs_x = sym_x + ax
            abs_y = sym_y + ay
            svg_ax = abs_x
            svg_ay = backend._flip_y(abs_y)
            backend._elements.append(
                f'<circle cx="{svg_ax:.1f}" cy="{svg_ay:.1f}" r="0.8" '
                f'fill="blue" opacity="0.5" />'
            )

    # Write output
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    svg_str = backend.get_svg_string()

    with open(output_path, "w", encoding="utf-8") as f:
        f.write(svg_str)

    print(f"\nCatalog generated: {output_path}")
    return output_path


def main():
    """Generate all symbol catalog files."""
    base_dir = Path(__file__).resolve().parent.parent.parent / "data" / "templates"

    print("=" * 60)
    print("SLD Symbol Library Catalog Generator")
    print("=" * 60)

    # Individual SVGs
    print("\n[1/2] Generating individual symbol SVGs...")
    symbols_dir = str(base_dir / "symbols")
    generate_individual_svgs(symbols_dir)

    # Combined catalog
    print("\n[2/2] Generating combined catalog SVG...")
    catalog_path = str(base_dir / "sld_symbol_catalog.svg")
    generate_catalog_svg(catalog_path)

    print("\n" + "=" * 60)
    print("Done! Open the SVG files in a browser to verify symbols.")
    print("=" * 60)


if __name__ == "__main__":
    main()
