"""
Drawing border and title block for SLD drawings.
Standard A3 landscape (420mm x 297mm).

Professional title block matching real LEW (Licensed Electrical Worker) SLD format:

Single-row layout with 8 cells:
| CLIENT/ADDRESS | MAIN CONTRACTOR | ELEC. CONTRACTOR | DRAWING TITLE | LEW | CHECKED | DATE     |
|                |                 |                  |               |     |---------+----------|
|                |                 |                  |               |     | DWG NO. | REV      |

Based on analysis of 73 real LEW SLD samples (63A/100A/200A Single Phase DB & TPN).

The title block is split into two phases:
  1. draw_title_block_frame() — draws structure (borders, dividers, field labels only)
  2. fill_title_block_data()  — fills in actual data values
"""

from __future__ import annotations

from datetime import date
from typing import TYPE_CHECKING

from app.sld.locale import SG_LOCALE, SldLocale

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


# =============================================
# Title block coordinate constants
# Matching real LEW SLD single-row layout
# =============================================

TB_LEFT = 10
TB_RIGHT = 410
TB_BOTTOM = 10
TB_TOP = 42        # Single row, 32mm tall — compact like real LEW samples

# Column positions — proportioned from real LEW samples
COL1 = TB_LEFT            # 10   CLIENT / ADDRESS  (width ~80mm)
COL2 = COL1 + 80          # 90   MAIN CONTRACTOR   (width ~60mm)
COL3 = COL2 + 60          # 150  ELEC. CONTRACTOR  (width ~72mm)
COL4 = COL3 + 72          # 222  DRAWING TITLE     (width ~68mm)
COL5 = COL4 + 68          # 290  LEW               (width ~72mm)
COL6 = COL5 + 72          # 362  CHECKED/DATE + DWG NO/REV (width ~48mm)

# Right sub-columns for CHECKED/DATE and DWG NO/REV
COL6_MID = COL6 + 24      # 386  Split between CHECKED and DATE / DWG NO and REV

# Row positions
ROW_TOP = TB_TOP           # 45
ROW_MID = (TB_TOP + TB_BOTTOM) // 2 + 1  # ~28  Horizontal split in COL6 area
ROW_BOT = TB_BOTTOM        # 10


def draw_border(backend: DrawingBackend, margin: float = 10) -> None:
    """Draw the drawing border rectangle."""
    w = 420 - margin
    h = 297 - margin
    backend.set_layer("SLD_TITLE_BLOCK")
    backend.add_lwpolyline(
        [(margin, margin), (w, margin), (w, h), (margin, h)],
        close=True,
    )


def draw_title_block_frame(backend: DrawingBackend, locale: SldLocale = SG_LOCALE) -> None:
    """
    Draw the title block structure matching real LEW SLD format.
    Single-row layout with right-side 2x2 grid for CHECKED/DATE/DWG NO/REV.
    """
    backend.set_layer("SLD_TITLE_BLOCK")

    # -- Outer box --
    backend.add_lwpolyline(
        [(TB_LEFT, TB_BOTTOM), (TB_RIGHT, TB_BOTTOM), (TB_RIGHT, TB_TOP), (TB_LEFT, TB_TOP)],
        close=True,
    )

    # -- Vertical dividers (full height) --
    backend.add_line((COL2, TB_TOP), (COL2, TB_BOTTOM))   # After CLIENT
    backend.add_line((COL3, TB_TOP), (COL3, TB_BOTTOM))   # After MAIN CONTRACTOR
    backend.add_line((COL4, TB_TOP), (COL4, TB_BOTTOM))   # After ELEC. CONTRACTOR
    backend.add_line((COL5, TB_TOP), (COL5, TB_BOTTOM))   # After DRAWING TITLE
    backend.add_line((COL6, TB_TOP), (COL6, TB_BOTTOM))   # After LEW

    # -- Right 2x2 grid (CHECKED/DATE top, DWG NO/REV bottom) --
    backend.add_line((COL6, ROW_MID), (TB_RIGHT, ROW_MID))         # Horizontal split
    backend.add_line((COL6_MID, TB_TOP), (COL6_MID, TB_BOTTOM))    # Vertical split

    # ==========================================
    # FIELD LABELS (small header text)
    # ==========================================
    backend.set_layer("SLD_ANNOTATIONS")

    lbl_h = 1.8   # Label font size (small, like real samples)
    lbl_y = ROW_TOP - 2.5  # Y offset from top for labels

    tb = locale.title_block
    backend.add_mtext(tb.client_address, insert=(COL1 + 2, lbl_y), char_height=lbl_h)
    backend.add_mtext(tb.main_contractor, insert=(COL2 + 2, lbl_y), char_height=lbl_h)
    backend.add_mtext(tb.electrical_contractor, insert=(COL3 + 2, lbl_y), char_height=lbl_h)
    backend.add_mtext(tb.drawing_title, insert=(COL4 + 2, lbl_y), char_height=lbl_h)
    backend.add_mtext(tb.lew, insert=(COL5 + 2, lbl_y), char_height=lbl_h)

    # Right 2x2 grid labels
    backend.add_mtext(tb.checked, insert=(COL6 + 2, ROW_TOP - 2.5), char_height=lbl_h)
    backend.add_mtext(tb.date, insert=(COL6_MID + 2, ROW_TOP - 2.5), char_height=lbl_h)
    backend.add_mtext(tb.drawing_no, insert=(COL6 + 2, ROW_MID - 2.5), char_height=lbl_h)
    backend.add_mtext(tb.rev, insert=(COL6_MID + 2, ROW_MID - 2.5), char_height=lbl_h)


def fill_title_block_data(
    backend: DrawingBackend,
    project_name: str = "Electrical Installation",
    address: str = "",
    postal_code: str = "",
    kva: int = 0,
    voltage: int = 0,
    supply_type: str = "",
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
    contractor_address: str = "",
    elec_contractor_tel: str = "",
    locale: SldLocale = SG_LOCALE,
    **kwargs,
) -> None:
    """
    Fill data values into the title block cells.
    Assumes draw_title_block_frame() has already been called.

    Address fields support multi-line via \\P separator (DXF/PDF line break).
    """
    if not elec_contractor_addr and contractor_address:
        elec_contractor_addr = contractor_address

    # Normalize LEW licence — real samples show just the number (e.g., "8/33613")
    if lew_licence:
        import re
        # Strip prefix like "EMA/LEW/" leaving just "8/33613"
        lew_licence_display = re.sub(r'^EMA/?LEW/?', '', lew_licence)
    else:
        lew_licence_display = ""

    backend.set_layer("SLD_ANNOTATIONS")

    data_y = ROW_TOP - 7      # Start Y for data text (below label)
    data_h = 2.5              # Data font size
    data_h_lg = 3.0           # Large data font (company names, drawing title)
    line_sp = 3.2             # Line spacing for multi-line text

    # -- Cell 1: CLIENT / ADDRESS --
    client_text = client_name or project_name
    backend.add_mtext(client_text, insert=(COL1 + 3, data_y), char_height=data_h_lg)

    if address:
        # Split address into multi-line for readability (real samples show 3-4 lines)
        addr_lines = _split_address(address, postal_code)
        addr_text = "\\P".join(addr_lines)
        backend.add_mtext(addr_text, insert=(COL1 + 3, data_y - line_sp - 1), char_height=2.0)

    # -- Cell 2: MAIN CONTRACTOR --
    if main_contractor:
        backend.add_mtext(main_contractor, insert=(COL2 + 3, data_y), char_height=data_h_lg)

    # -- Cell 3: ELECTRICAL CONTRACTOR --
    backend.add_mtext(elec_contractor, insert=(COL3 + 3, data_y), char_height=data_h_lg)
    ec_lines = []
    if elec_contractor_addr:
        # Split contractor address into multi-line
        ec_addr_lines = _split_address(elec_contractor_addr, "")
        ec_lines.extend(ec_addr_lines)
    if elec_contractor_tel:
        ec_lines.append(f"TEL: {elec_contractor_tel}")
    if ec_lines:
        ec_text = "\\P".join(ec_lines)
        backend.add_mtext(ec_text, insert=(COL3 + 3, data_y - line_sp - 1), char_height=2.0)

    # -- Cell 4: DRAWING TITLE --
    tb = locale.title_block
    backend.add_mtext(
        tb.sld_title,
        insert=(COL4 + 3, data_y + 1),
        char_height=3.5,
    )

    # -- Cell 5: LEW --
    if sld_only_mode:
        backend.add_mtext(
            tb.to_be_filled, insert=(COL5 + 3, data_y), char_height=data_h,
        )
        backend.add_mtext(
            f"{tb.ema_licence}____________",
            insert=(COL5 + 3, data_y - line_sp),
            char_height=2.0,
        )
        backend.add_mtext(
            f"{tb.mobile_number}____________",
            insert=(COL5 + 3, data_y - line_sp * 2),
            char_height=2.0,
        )
    else:
        if lew_name:
            backend.add_mtext(lew_name, insert=(COL5 + 3, data_y), char_height=data_h_lg)
        if lew_licence:
            backend.add_mtext(
                f"{tb.ema_licence}{lew_licence_display}",
                insert=(COL5 + 3, data_y - line_sp - 1),
                char_height=2.0,
            )
        if lew_mobile:
            backend.add_mtext(
                f"{tb.mobile_number}{lew_mobile}",
                insert=(COL5 + 3, data_y - line_sp * 2 - 1),
                char_height=2.0,
            )

    # -- Cell 6: CHECKED (top-left of 2x2) --
    # Left blank for LEW to sign after review

    # -- Cell 7: DATE (top-right of 2x2) --
    backend.add_mtext(
        date.today().strftime("%d %b %Y").upper(),
        insert=(COL6_MID + 3, ROW_TOP - 9),
        char_height=2.5,
    )

    # -- Cell 8: DRAWING NO (bottom-left of 2x2) --
    backend.add_mtext(
        drawing_number,
        insert=(COL6 + 3, ROW_MID - 6),
        char_height=2.5,
    )

    # -- Cell 9: REV (bottom-right of 2x2) --
    backend.add_mtext(
        revision,
        insert=(COL6_MID + 8, ROW_MID - 6),
        char_height=3.5,
    )

    # -- SCALE & SHEET (inside DWG NO cell, small text at bottom) --
    backend.add_mtext(tb.scale_nts, insert=(COL6 + 3, ROW_BOT + 2), char_height=1.6)
    backend.add_mtext(tb.sheet_1of1, insert=(COL6_MID + 3, ROW_BOT + 2), char_height=1.6)


def _split_address(address: str, postal_code: str) -> list[str]:
    """
    Split a single-line address string into multi-line format.
    Real LEW samples show addresses as 3-4 separate lines:
      Line 1: Street number + name
      Line 2: Unit/floor + Singapore postal code  (e.g., "#01-K1 Singapore 760709")
      Line 3: (or separate Singapore XXXXXX line if postal_code provided separately)

    Handles formats:
      "709 Yishun Avenue 5, #01-K1"
      "4 Jalan Mat Jambol\\P#B01-01"
      "709 Yishun Avenue 5 #01-K1 Singapore 760709"
    """
    import re
    lines = []

    # If address already contains \P (DXF line break) or newlines, respect them
    if "\\P" in address:
        parts = [p.strip() for p in address.split("\\P") if p.strip()]
    elif "\n" in address:
        parts = [p.strip() for p in address.split("\n") if p.strip()]
    else:
        # Split by comma, then check for unit numbers
        raw_parts = [p.strip() for p in address.split(",") if p.strip()]
        if len(raw_parts) >= 2:
            # Already comma-separated (e.g., "709 Yishun Ave 5, #01-K1")
            parts = raw_parts
        else:
            # Single string — try to split at unit number
            remaining = address.strip()
            unit_match = re.search(r'(#\S+)', remaining)
            if unit_match:
                before = remaining[:unit_match.start()].strip()
                after_unit = remaining[unit_match.start():].strip()
                parts = []
                if before:
                    parts.append(before)
                parts.append(after_unit)  # Keep unit + anything after together
            else:
                parts = [remaining]

    lines.extend(parts)

    # Add postal code as last line (unless already in address)
    if postal_code and f"Singapore {postal_code}" not in address and postal_code not in address:
        lines.append(f"Singapore {postal_code}")

    return lines


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
    **kwargs,
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
        **kwargs,
    )
