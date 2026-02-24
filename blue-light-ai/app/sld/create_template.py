"""
Generate a blank SLD template PDF and SVG.

Usage:
    cd blue-light-ai
    python -m app.sld.create_template

Outputs:
    data/templates/sld_template_blank.pdf
    data/templates/sld_template_blank.svg
"""

import os
from pathlib import Path

from app.sld.pdf_backend import PdfBackend
from app.sld.svg_backend import SvgBackend
from app.sld.title_block import draw_border, draw_title_block_frame


def create_blank_template() -> None:
    """Generate blank SLD template with border + title block frame only."""
    output_dir = Path(__file__).resolve().parent.parent.parent / "data" / "templates"
    output_dir.mkdir(parents=True, exist_ok=True)

    pdf_path = str(output_dir / "sld_template_blank.pdf")
    svg_path = str(output_dir / "sld_template_blank.svg")

    # Generate PDF template
    pdf = PdfBackend(pdf_path)
    draw_border(pdf)
    draw_title_block_frame(pdf)
    pdf.save()
    print(f"PDF template saved: {pdf_path}")

    # Generate SVG template
    svg = SvgBackend()
    draw_border(svg)
    draw_title_block_frame(svg)
    svg_string = svg.get_svg_string()
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write(svg_string)
    print(f"SVG template saved: {svg_path}")

    # Print file sizes
    pdf_size = os.path.getsize(pdf_path)
    svg_size = os.path.getsize(svg_path)
    print(f"\nTemplate files generated:")
    print(f"  PDF: {pdf_size / 1024:.1f} KB")
    print(f"  SVG: {svg_size / 1024:.1f} KB")


if __name__ == "__main__":
    create_blank_template()
