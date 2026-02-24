"""
Drawing border and title block for SLD drawings.
Standard A3 landscape (420mm x 297mm).

Professional 7-cell title block layout matching Singapore engineering standards:
| CLIENT / ADDRESS | MAIN CONTRACTOR | ELEC. CONTRACTOR | DRAWING TITLE | LEW | CHECKED/DATE | DWG NO/REV |

The title block is split into two phases:
  1. draw_title_block_frame() — draws structure (borders, dividers, field labels only)
  2. fill_title_block_data()  — fills in actual data values

This separation allows generating a blank template (frame only) or a complete title block.
"""

from __future__ import annotations

from datetime import date
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


# =============================================
# Title block coordinate constants
# =============================================

TB_LEFT = 10
TB_RIGHT = 410
TB_BOTTOM = 10
TB_TOP = 55

# Column positions (6 columns)
COL1 = TB_LEFT           # CLIENT / ADDRESS
COL2 = TB_LEFT + 95      # MAIN CONTRACTOR
COL3 = TB_LEFT + 175     # ELEC. CONTRACTOR
COL4 = TB_LEFT + 255     # DRAWING TITLE
COL5 = TB_LEFT + 330     # LEW

# Row positions
ROW_TOP = TB_TOP          # 55
ROW_MID = TB_TOP - 20     # 35  (header / data boundary)
ROW_LOW = TB_TOP - 35     # 20  (CHECKED / DWG NO split)
ROW_BOT = TB_BOTTOM       # 10

# LEW sub-area vertical split
DWG_SPLIT_X = COL5 + 50   # 380  (CHECKED+DATE / DWG NO+REV)


def draw_border(backend: DrawingBackend, margin: float = 10) -> None:
    """Draw the drawing border rectangle."""
    w = 420 - margin
    h = 297 - margin
    backend.set_layer("SLD_TITLE_BLOCK")
    backend.add_lwpolyline(
        [(margin, margin), (w, margin), (w, h), (margin, h)],
        close=True,
    )


def draw_title_block_frame(backend: DrawingBackend) -> None:
    """
    Draw the title block structure: borders, divider lines, and field labels.
    No data values are filled — this is the blank template.
    """
    backend.set_layer("SLD_TITLE_BLOCK")

    # -- Outer box --
    backend.add_lwpolyline(
        [(TB_LEFT, TB_BOTTOM), (TB_RIGHT, TB_BOTTOM), (TB_RIGHT, TB_TOP), (TB_LEFT, TB_TOP)],
        close=True,
    )

    # -- Horizontal dividers --
    backend.add_line((TB_LEFT, ROW_MID), (TB_RIGHT, ROW_MID))      # Main horizontal split
    backend.add_line((COL5, ROW_LOW), (TB_RIGHT, ROW_LOW))          # LEW area split

    # -- Vertical dividers (top row) --
    backend.add_line((COL2, TB_TOP), (COL2, ROW_MID))   # After CLIENT
    backend.add_line((COL3, TB_TOP), (COL3, ROW_MID))   # After MAIN CONTRACTOR
    backend.add_line((COL4, TB_TOP), (COL4, ROW_MID))   # After ELEC. CONTRACTOR
    backend.add_line((COL5, TB_TOP), (COL5, TB_BOTTOM))  # Before LEW/DWG section

    # -- Vertical divider in CHECKED / DWG NO area --
    backend.add_line((DWG_SPLIT_X, ROW_LOW), (DWG_SPLIT_X, TB_BOTTOM))

    # ==========================================
    # FIELD LABELS (header text in each cell)
    # ==========================================
    backend.set_layer("SLD_ANNOTATIONS")

    # Cell 1: CLIENT / ADDRESS
    backend.add_mtext("CLIENT / ADDRESS :", insert=(COL1 + 3, ROW_TOP - 2), char_height=2.0)

    # Cell 2: MAIN CONTRACTOR
    backend.add_mtext("MAIN CONTRACTOR :", insert=(COL2 + 3, ROW_TOP - 2), char_height=2.0)

    # Cell 3: ELECTRICAL CONTRACTOR
    backend.add_mtext("ELECTRICAL CONTRACTOR :", insert=(COL3 + 3, ROW_TOP - 2), char_height=2.0)

    # Cell 4: DRAWING TITLE
    backend.add_mtext("DRAWING TITLE :", insert=(COL4 + 3, ROW_TOP - 2), char_height=2.0)

    # Cell 5: LEW
    backend.add_mtext("LEW :", insert=(COL5 + 3, ROW_TOP - 2), char_height=2.0)

    # Cell 6: CHECKED / DATE
    backend.add_mtext("CHECKED :", insert=(COL5 + 3, ROW_LOW - 2), char_height=2.0)
    backend.add_mtext("DATE :", insert=(COL5 + 3, ROW_LOW - 7), char_height=2.0)

    # Cell 7: DRAWING NO / REV
    backend.add_mtext("DRAWING NO. :", insert=(DWG_SPLIT_X + 3, ROW_LOW - 2), char_height=2.0)
    backend.add_mtext("REV :", insert=(DWG_SPLIT_X + 35, ROW_LOW - 2), char_height=2.0)

    # SCALE & SHEET
    backend.add_mtext("SCALE : NTS", insert=(DWG_SPLIT_X + 3, ROW_BOT + 6), char_height=2.0)
    backend.add_mtext("SHEET : 1 OF 1", insert=(DWG_SPLIT_X + 35, ROW_BOT + 6), char_height=2.0)


def fill_title_block_data(
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
    Fill data values into the title block cells.
    Assumes draw_title_block_frame() has already been called.
    """
    backend.set_layer("SLD_ANNOTATIONS")

    # -- Cell 1: CLIENT / ADDRESS data --
    client_text = client_name or project_name
    backend.add_mtext(client_text, insert=(COL1 + 3, ROW_TOP - 7), char_height=2.8)
    if address:
        addr_text = address
        if postal_code:
            addr_text += f"\\PSingapore {postal_code}"
        backend.add_mtext(addr_text, insert=(COL1 + 3, ROW_TOP - 12), char_height=2.2)

    # -- Cell 2: MAIN CONTRACTOR data --
    if main_contractor:
        backend.add_mtext(main_contractor, insert=(COL2 + 3, ROW_TOP - 7), char_height=2.8)

    # -- Cell 3: ELECTRICAL CONTRACTOR data --
    backend.add_mtext(elec_contractor, insert=(COL3 + 3, ROW_TOP - 7), char_height=2.8)
    if elec_contractor_addr:
        backend.add_mtext(elec_contractor_addr, insert=(COL3 + 3, ROW_TOP - 12), char_height=2.0)

    # -- Cell 4: DRAWING TITLE data --
    backend.add_mtext("SINGLE LINE DIAGRAM\\P(SLD)", insert=(COL4 + 3, ROW_TOP - 7), char_height=3.5)

    # -- Cell 5: LEW data --
    if sld_only_mode:
        backend.add_mtext(
            "(To be filled by LEW)", insert=(COL5 + 3, ROW_TOP - 7), char_height=2.5,
        )
        backend.add_mtext(
            "EMA Licence No. : ____________", insert=(COL5 + 3, ROW_TOP - 12), char_height=2.2,
        )
        backend.add_mtext(
            "Mobile Number. : ____________", insert=(COL5 + 3, ROW_TOP - 16), char_height=2.0,
        )
    else:
        if lew_name:
            backend.add_mtext(lew_name, insert=(COL5 + 3, ROW_TOP - 7), char_height=2.8)
        if lew_licence:
            backend.add_mtext(
                f"EMA Licence No. : {lew_licence}", insert=(COL5 + 3, ROW_TOP - 12), char_height=2.2,
            )
        if lew_mobile:
            backend.add_mtext(
                f"Mobile Number. : {lew_mobile}", insert=(COL5 + 3, ROW_TOP - 16), char_height=2.0,
            )

    # -- Cell 6: CHECKED / DATE data --
    backend.add_mtext(
        date.today().strftime("%d %b %Y").upper(),
        insert=(COL5 + 18, ROW_LOW - 7),
        char_height=2.2,
    )

    # -- Cell 7: DRAWING NO / REV data --
    backend.add_mtext(drawing_number, insert=(DWG_SPLIT_X + 3, ROW_LOW - 7), char_height=2.8)
    backend.add_mtext(revision, insert=(DWG_SPLIT_X + 35, ROW_LOW - 7), char_height=3.5)

    # -- Bottom row: Compliance & AI notice --
    backend.add_mtext(
        f"Approved Load: {kva} kVA at {400 if kva else 230}V  |  "
        f"Design in accordance with SS 638:2018, CP 5:2018, IEC 60617",
        insert=(TB_LEFT + 3, ROW_MID - 3),
        char_height=2.0,
    )
    backend.add_mtext(
        "Generated by LicenseKaki AI -- Verify before submission to EMA",
        insert=(TB_LEFT + 3, ROW_MID - 8),
        char_height=1.8,
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
    Draw the complete title block (frame + data).
    Backward-compatible wrapper that calls frame + fill in sequence.
    """
    draw_title_block_frame(backend)
    fill_title_block_data(
        backend,
        project_name=project_name,
        address=address,
        postal_code=postal_code,
        kva=kva,
        drawing_number=drawing_number,
        lew_name=lew_name,
        lew_licence=lew_licence,
        lew_mobile=lew_mobile,
        revision=revision,
        sld_only_mode=sld_only_mode,
        client_name=client_name,
        main_contractor=main_contractor,
        elec_contractor=elec_contractor,
        elec_contractor_addr=elec_contractor_addr,
    )
