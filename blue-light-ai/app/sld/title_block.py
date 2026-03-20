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

from dataclasses import dataclass
from datetime import date
from typing import TYPE_CHECKING

from app.sld.locale import SG_LOCALE, SldLocale
from app.sld.page_config import A3_LANDSCAPE, PageConfig

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


# =============================================
# TitleBlockConfig — computed geometry from PageConfig
# =============================================

@dataclass(frozen=True)
class TitleBlockConfig:
    """Computed title block geometry from PageConfig.

    Column proportions from real LEW SLD samples: 80:60:72:68:72:48 = 400mm total.
    """

    left: float
    right: float
    bottom: float
    top: float
    col1: float
    col2: float
    col3: float
    col4: float
    col5: float
    col6: float
    col6_mid: float
    row_top: float
    row_mid: float
    row_bot: float

    @classmethod
    def from_page_config(cls, pc: PageConfig | None = None) -> "TitleBlockConfig":
        pc = pc or A3_LANDSCAPE
        left = pc.margin
        right = pc.page_width - pc.margin
        bottom = pc.margin
        top = bottom + pc.title_block_height
        w = right - left  # 400 for A3
        return cls(
            left=left, right=right, bottom=bottom, top=top,
            col1=left,
            col2=left + w * 0.200,     # 80/400
            col3=left + w * 0.350,     # (80+60)/400
            col4=left + w * 0.530,     # (80+60+72)/400
            col5=left + w * 0.700,     # (80+60+72+68)/400
            col6=left + w * 0.880,     # (80+60+72+68+72)/400
            col6_mid=left + w * 0.940,  # (80+60+72+68+72+24)/400
            row_top=top,
            row_mid=(top + bottom) // 2 + 1,
            row_bot=bottom,
        )


_DEFAULT_TB = TitleBlockConfig.from_page_config(A3_LANDSCAPE)


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


def draw_border(backend: DrawingBackend, margin: float = 10, page_config: PageConfig | None = None) -> None:
    """Draw the drawing border rectangle."""
    pc = page_config or A3_LANDSCAPE
    w = pc.page_width - pc.margin
    h = pc.page_height - pc.margin
    backend.set_layer("SLD_FRAME")
    backend.add_lwpolyline(
        [(pc.margin, pc.margin), (w, pc.margin), (w, h), (pc.margin, h)],
        close=True,
    )


def draw_title_block_frame(
    backend: DrawingBackend,
    locale: SldLocale = SG_LOCALE,
    tb_config: TitleBlockConfig | None = None,
) -> None:
    """
    Draw the title block structure matching real LEW SLD format.
    Single-row layout with right-side 2x2 grid for CHECKED/DATE/DWG NO/REV.
    """
    tbc = tb_config or _DEFAULT_TB

    backend.set_layer("SLD_TITLE_BLOCK")

    # -- Outer box --
    backend.add_lwpolyline(
        [(tbc.left, tbc.bottom), (tbc.right, tbc.bottom), (tbc.right, tbc.top), (tbc.left, tbc.top)],
        close=True,
    )

    # -- Vertical dividers (full height) --
    backend.add_line((tbc.col2, tbc.top), (tbc.col2, tbc.bottom))   # After CLIENT
    backend.add_line((tbc.col3, tbc.top), (tbc.col3, tbc.bottom))   # After MAIN CONTRACTOR
    backend.add_line((tbc.col4, tbc.top), (tbc.col4, tbc.bottom))   # After ELEC. CONTRACTOR
    backend.add_line((tbc.col5, tbc.top), (tbc.col5, tbc.bottom))   # After DRAWING TITLE
    backend.add_line((tbc.col6, tbc.top), (tbc.col6, tbc.bottom))   # After LEW

    # -- Right 2x2 grid (CHECKED/DATE top, DWG NO/REV bottom) --
    backend.add_line((tbc.col6, tbc.row_mid), (tbc.right, tbc.row_mid))         # Horizontal split
    backend.add_line((tbc.col6_mid, tbc.top), (tbc.col6_mid, tbc.bottom))    # Vertical split

    # ==========================================
    # FIELD LABELS (small header text)
    # ==========================================
    backend.set_layer("SLD_ANNOTATIONS")

    lbl_h = 1.8   # Label font size (small, like real samples)
    lbl_y = tbc.row_top - 2.5  # Y offset from top for labels

    tb = locale.title_block
    backend.add_mtext(tb.client_address, insert=(tbc.col1 + 2, lbl_y), char_height=lbl_h)
    backend.add_mtext(tb.main_contractor, insert=(tbc.col2 + 2, lbl_y), char_height=lbl_h)
    backend.add_mtext(tb.electrical_contractor, insert=(tbc.col3 + 2, lbl_y), char_height=lbl_h)
    backend.add_mtext(tb.drawing_title, insert=(tbc.col4 + 2, lbl_y), char_height=lbl_h)
    backend.add_mtext(tb.lew, insert=(tbc.col5 + 2, lbl_y), char_height=lbl_h)

    # Right 2x2 grid labels
    backend.add_mtext(tb.checked, insert=(tbc.col6 + 2, tbc.row_top - 2.5), char_height=lbl_h)
    backend.add_mtext(tb.date, insert=(tbc.col6_mid + 2, tbc.row_top - 2.5), char_height=lbl_h)
    backend.add_mtext(tb.drawing_no, insert=(tbc.col6 + 2, tbc.row_mid - 2.5), char_height=lbl_h)
    backend.add_mtext(tb.rev, insert=(tbc.col6_mid + 2, tbc.row_mid - 2.5), char_height=lbl_h)


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
    tb_config: TitleBlockConfig | None = None,
    **kwargs,
) -> None:
    """
    Fill data values into the title block cells.
    Assumes draw_title_block_frame() has already been called.

    Address fields support multi-line via \\P separator (DXF/PDF line break).
    """
    tbc = tb_config or _DEFAULT_TB

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

    data_y = tbc.row_top - 7      # Start Y for data text (below label)
    data_h = 2.5              # Data font size
    data_h_lg = 3.0           # Large data font (company names, drawing title)
    line_sp = 3.2             # Line spacing for multi-line text

    # -- Cell 1: CLIENT / ADDRESS --
    client_text = client_name or project_name
    # Auto-shrink long text to fit cell width (COL1->COL2 = 80mm, padding 6mm)
    c1_h = _fit_font_size(client_text, cell_width=74, max_height=data_h_lg)
    backend.add_mtext(client_text, insert=(tbc.col1 + 3, data_y), char_height=c1_h)

    if address:
        # Split address into multi-line for readability (real samples show 3-4 lines)
        addr_lines = _split_address(address, postal_code)
        addr_text = "\\P".join(addr_lines)
        backend.add_mtext(addr_text, insert=(tbc.col1 + 3, data_y - line_sp - 1), char_height=2.0)

    # -- Cell 2: MAIN CONTRACTOR --
    if main_contractor:
        # Auto-shrink for cell width (COL2->COL3 = 60mm, padding 6mm)
        c2_h = _fit_font_size(main_contractor, cell_width=54, max_height=data_h_lg)
        backend.add_mtext(main_contractor, insert=(tbc.col2 + 3, data_y), char_height=c2_h)

    # -- Cell 3: ELECTRICAL CONTRACTOR --
    # Auto-shrink for cell width (COL3->COL4 = 72mm, padding 6mm)
    c3_h = _fit_font_size(elec_contractor, cell_width=66, max_height=data_h_lg)
    backend.add_mtext(elec_contractor, insert=(tbc.col3 + 3, data_y), char_height=c3_h)
    ec_lines = []
    if elec_contractor_addr:
        # Split contractor address into multi-line
        ec_addr_lines = _split_address(elec_contractor_addr, "")
        ec_lines.extend(ec_addr_lines)
    if elec_contractor_tel:
        ec_lines.append(f"TEL: {elec_contractor_tel}")
    if ec_lines:
        ec_text = "\\P".join(ec_lines)
        backend.add_mtext(ec_text, insert=(tbc.col3 + 3, data_y - line_sp - 1), char_height=2.0)

    # -- Cell 4: DRAWING TITLE --
    tb = locale.title_block
    backend.add_mtext(
        tb.sld_title,
        insert=(tbc.col4 + 3, data_y + 1),
        char_height=3.5,
    )

    # -- Cell 5: LEW --
    if sld_only_mode:
        backend.add_mtext(
            tb.to_be_filled, insert=(tbc.col5 + 3, data_y), char_height=data_h,
        )
        backend.add_mtext(
            f"{tb.ema_licence}____________",
            insert=(tbc.col5 + 3, data_y - line_sp),
            char_height=2.0,
        )
        backend.add_mtext(
            f"{tb.mobile_number}____________",
            insert=(tbc.col5 + 3, data_y - line_sp * 2),
            char_height=2.0,
        )
    else:
        if lew_name:
            backend.add_mtext(lew_name, insert=(tbc.col5 + 3, data_y), char_height=data_h_lg)
        if lew_licence:
            backend.add_mtext(
                f"{tb.ema_licence}{lew_licence_display}",
                insert=(tbc.col5 + 3, data_y - line_sp - 1),
                char_height=2.0,
            )
        if lew_mobile:
            backend.add_mtext(
                f"{tb.mobile_number}{lew_mobile}",
                insert=(tbc.col5 + 3, data_y - line_sp * 2 - 1),
                char_height=2.0,
            )

    # -- Cell 6: CHECKED (top-left of 2x2) --
    # Left blank for LEW to sign after review

    # -- Cell 7: DATE (top-right of 2x2) --
    backend.add_mtext(
        date.today().strftime("%d %b %Y").upper(),
        insert=(tbc.col6_mid + 3, tbc.row_top - 9),
        char_height=2.5,
    )

    # -- Cell 8: DRAWING NO (bottom-left of 2x2) --
    backend.add_mtext(
        drawing_number,
        insert=(tbc.col6 + 3, tbc.row_mid - 6),
        char_height=2.5,
    )

    # -- Cell 9: REV (bottom-right of 2x2) --
    backend.add_mtext(
        revision,
        insert=(tbc.col6_mid + 8, tbc.row_mid - 6),
        char_height=3.5,
    )

    # -- SCALE & SHEET (inside DWG NO cell, small text at bottom) --
    backend.add_mtext(tb.scale_nts, insert=(tbc.col6 + 3, tbc.row_bot + 2), char_height=1.6)
    backend.add_mtext(tb.sheet_1of1, insert=(tbc.col6_mid + 3, tbc.row_bot + 2), char_height=1.6)


def _fit_font_size(text: str, cell_width: float, max_height: float, min_height: float = 1.8) -> float:
    """Auto-shrink font size so text fits within cell width.

    Estimates text width as len(text) * char_height * 0.6 (approximate for
    typical engineering drawing fonts). Returns max_height if text fits,
    otherwise shrinks down to min_height.

    Args:
        text: The text string to measure.
        cell_width: Available cell width in mm.
        max_height: Preferred (maximum) font height.
        min_height: Minimum readable font height.
    """
    if not text:
        return max_height
    # Approximate: each character occupies ~0.6 * char_height in width
    est_width = len(text) * max_height * 0.6
    if est_width <= cell_width:
        return max_height
    # Shrink proportionally, clamped to min_height
    shrunk = cell_width / (len(text) * 0.6)
    return max(min_height, shrunk)


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
