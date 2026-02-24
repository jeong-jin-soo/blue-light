"""
Drawing border and title block for SLD drawings.
Standard A3 landscape (420mm x 297mm).

Professional 7-cell title block layout matching Singapore engineering standards:
| CLIENT / ADDRESS | MAIN CONTRACTOR | ELEC. CONTRACTOR | DRAWING TITLE | LEW | CHECKED/DATE | DWG NO/REV |
"""

from __future__ import annotations

from datetime import date
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


def draw_border(backend: DrawingBackend, margin: float = 10) -> None:
    """Draw the drawing border rectangle."""
    w = 420 - margin
    h = 297 - margin
    backend.set_layer("SLD_TITLE_BLOCK")
    backend.add_lwpolyline(
        [(margin, margin), (w, margin), (w, h), (margin, h)],
        close=True,
    )


def draw_title_block(
    backend: DrawingBackend,
    project_name: str = "Electrical Installation",
    address: str = "",
    postal_code: str = "",
    kva: int = 0,
    drawing_number: str = "SLD-001",
    lew_name: str = "",
    lew_licence: str = "",
    lew_mobile: str = "",
    revision: str = "0",
    sld_only_mode: bool = False,
    client_name: str = "",
    main_contractor: str = "",
    elec_contractor: str = "LicenseKaki",
    elec_contractor_addr: str = "",
) -> None:
    """
    Draw a professional 7-cell title block in the bottom area.

    Layout (from left to right):
    Row 1: CLIENT/ADDRESS | MAIN CONTRACTOR | ELEC. CONTRACTOR | DRAWING TITLE | LEW | CHECKED/DATE
    Row 2: (compliance & AI notice span full width)                                      DWG NO / REV
    """
    # Title block boundaries
    tb_left = 10
    tb_right = 410
    tb_bottom = 10
    tb_top = 55
    tb_width = tb_right - tb_left

    # Column positions (6 columns)
    col1 = tb_left          # CLIENT/ADDRESS
    col2 = tb_left + 95     # MAIN CONTRACTOR
    col3 = tb_left + 175    # ELEC. CONTRACTOR
    col4 = tb_left + 255    # DRAWING TITLE
    col5 = tb_left + 330    # LEW
    col6 = tb_left + 330    # CHECKED (same x as LEW, different row)

    # Row positions
    row_top = tb_top         # 55
    row_mid = tb_top - 20    # 35  (header row bottom / data row top)
    row_low = tb_top - 35    # 20  (second sub-row)
    row_bot = tb_bottom      # 10

    backend.set_layer("SLD_TITLE_BLOCK")

    # -- Outer box --
    backend.add_lwpolyline(
        [(tb_left, tb_bottom), (tb_right, tb_bottom), (tb_right, tb_top), (tb_left, tb_top)],
        close=True,
    )

    # -- Horizontal dividers --
    backend.add_line((tb_left, row_mid), (tb_right, row_mid))      # Main horizontal split
    backend.add_line((col5, row_low), (tb_right, row_low))         # LEW area split

    # -- Vertical dividers (top row) --
    backend.add_line((col2, tb_top), (col2, row_mid))   # After CLIENT
    backend.add_line((col3, tb_top), (col3, row_mid))   # After MAIN CONTRACTOR
    backend.add_line((col4, tb_top), (col4, row_mid))   # After ELEC. CONTRACTOR
    backend.add_line((col5, tb_top), (col5, tb_bottom))  # Before LEW/DWG section

    # -- Vertical divider in LEW section (DWG NO / REV) --
    dwg_split_x = col5 + 50
    backend.add_line((dwg_split_x, row_low), (dwg_split_x, tb_bottom))

    # ==========================================
    # TEXT CONTENT
    # ==========================================
    backend.set_layer("SLD_ANNOTATIONS")

    # -- Cell 1: CLIENT / ADDRESS --
    backend.add_mtext(
        "CLIENT / ADDRESS :",
        insert=(col1 + 3, row_top - 2),
        char_height=2.0,
    )
    client_text = client_name or project_name
    backend.add_mtext(
        client_text,
        insert=(col1 + 3, row_top - 7),
        char_height=2.8,
    )
    if address:
        addr_text = address
        if postal_code:
            addr_text += f"\\PSingapore {postal_code}"
        backend.add_mtext(
            addr_text,
            insert=(col1 + 3, row_top - 12),
            char_height=2.2,
        )

    # -- Cell 2: MAIN CONTRACTOR --
    backend.add_mtext(
        "MAIN CONTRACTOR :",
        insert=(col2 + 3, row_top - 2),
        char_height=2.0,
    )
    if main_contractor:
        backend.add_mtext(
            main_contractor,
            insert=(col2 + 3, row_top - 7),
            char_height=2.8,
        )

    # -- Cell 3: ELECTRICAL CONTRACTOR --
    backend.add_mtext(
        "ELECTRICAL CONTRACTOR :",
        insert=(col3 + 3, row_top - 2),
        char_height=2.0,
    )
    backend.add_mtext(
        elec_contractor,
        insert=(col3 + 3, row_top - 7),
        char_height=2.8,
    )
    if elec_contractor_addr:
        backend.add_mtext(
            elec_contractor_addr,
            insert=(col3 + 3, row_top - 12),
            char_height=2.0,
        )

    # -- Cell 4: DRAWING TITLE --
    backend.add_mtext(
        "DRAWING TITLE :",
        insert=(col4 + 3, row_top - 2),
        char_height=2.0,
    )
    backend.add_mtext(
        "SINGLE LINE DIAGRAM\\P(SLD)",
        insert=(col4 + 3, row_top - 7),
        char_height=3.5,
    )

    # -- Cell 5: LEW --
    backend.add_mtext(
        "LEW :",
        insert=(col5 + 3, row_top - 2),
        char_height=2.0,
    )

    if sld_only_mode:
        backend.add_mtext(
            "(To be filled by LEW)",
            insert=(col5 + 3, row_top - 7),
            char_height=2.5,
        )
        backend.add_mtext(
            "EMA Licence No. : ____________",
            insert=(col5 + 3, row_top - 12),
            char_height=2.2,
        )
    else:
        if lew_name:
            backend.add_mtext(
                lew_name,
                insert=(col5 + 3, row_top - 7),
                char_height=2.8,
            )
        if lew_licence:
            backend.add_mtext(
                f"EMA Licence No. : {lew_licence}",
                insert=(col5 + 3, row_top - 12),
                char_height=2.2,
            )
        if lew_mobile:
            backend.add_mtext(
                f"Mobile : {lew_mobile}",
                insert=(col5 + 3, row_top - 16),
                char_height=2.0,
            )

    # -- Cell 6: CHECKED / DATE (below LEW divider, left) --
    backend.add_mtext(
        "CHECKED :",
        insert=(col5 + 3, row_low - 2),
        char_height=2.0,
    )
    backend.add_mtext(
        "DATE :",
        insert=(col5 + 3, row_low - 7),
        char_height=2.0,
    )
    backend.add_mtext(
        date.today().strftime("%d %b %Y").upper(),
        insert=(col5 + 18, row_low - 7),
        char_height=2.2,
    )

    # -- Cell 7: DRAWING NO / REV (below LEW divider, right) --
    backend.add_mtext(
        "DRAWING NO. :",
        insert=(dwg_split_x + 3, row_low - 2),
        char_height=2.0,
    )
    backend.add_mtext(
        drawing_number,
        insert=(dwg_split_x + 3, row_low - 7),
        char_height=2.8,
    )
    backend.add_mtext(
        "REV :",
        insert=(dwg_split_x + 35, row_low - 2),
        char_height=2.0,
    )
    backend.add_mtext(
        revision,
        insert=(dwg_split_x + 35, row_low - 7),
        char_height=3.5,
    )

    # -- Bottom row: Compliance & AI notice (spans full width below mid line) --
    backend.add_mtext(
        f"Approved Load: {kva} kVA at {400 if kva else 230}V  |  "
        f"Design in accordance with SS 638:2018, CP 5:2018, IEC 60617",
        insert=(tb_left + 3, row_mid - 3),
        char_height=2.0,
    )
    backend.add_mtext(
        "Generated by LicenseKaki AI -- Verify before submission to EMA",
        insert=(tb_left + 3, row_mid - 8),
        char_height=1.8,
    )
