"""
PDF drawing backend using ReportLab.

Implements DrawingBackend protocol to generate A3 landscape PDF files
for SLD drawings submitted to EMA.

Line weight standards calibrated from 73 real LEW SLD samples:
- All layers: 0.25mm (uniform, matching real samples)
- SLD_POWER_MAIN: 0.50mm (busbar only, slightly bolder)
- Color: 100% black (no gray)
- Font: Arial (matching ArialMT in real samples)
"""

from __future__ import annotations

import io
import math
from pathlib import Path

from reportlab.lib.pagesizes import A3, landscape
from reportlab.lib.units import mm
from reportlab.pdfgen.canvas import Canvas

from app.sld.page_config import A3_LANDSCAPE, PageConfig


# Layer -> color mapping (all black, matching real LEW SLD samples)
_LAYER_COLORS: dict[str, tuple[float, float, float]] = {
    "SLD_SYMBOLS": (0.0, 0.0, 0.0),
    "SLD_CONNECTIONS": (0.0, 0.0, 0.0),
    "SLD_POWER_MAIN": (0.0, 0.0, 0.0),
    "SLD_ANNOTATIONS": (0.0, 0.0, 0.0),
    "SLD_TITLE_BLOCK": (0.0, 0.0, 0.0),
}

# Line widths per layer calibrated from real LEW SLD samples (0.25mm uniform)
_LAYER_LINE_WIDTHS_MM: dict[str, float] = {
    "SLD_SYMBOLS": 0.25,
    "SLD_CONNECTIONS": 0.25,
    "SLD_POWER_MAIN": 0.50,
    "SLD_ANNOTATIONS": 0.25,
    "SLD_TITLE_BLOCK": 0.25,
}


class PdfBackend:
    """
    ReportLab-based PDF drawing backend.

    Coordinate system: bottom-left origin, same as DXF.
    All input coordinates are in mm (matching the symbol library).
    Internally converts to points for ReportLab.
    """

    def __init__(self, output_path: str | None = None, page_config: PageConfig | None = None):
        """
        Args:
            output_path: File path for the PDF. If None, writes to in-memory buffer.
            page_config: Page dimensions. Defaults to A3 landscape.
        """
        self._output_path = output_path
        pc = page_config or A3_LANDSCAPE
        self._page_config = pc
        self._page_size = (pc.page_width * mm, pc.page_height * mm)

        if output_path:
            self._canvas = Canvas(output_path, pagesize=self._page_size)
        else:
            self._buffer = io.BytesIO()
            self._canvas = Canvas(self._buffer, pagesize=self._page_size)

        self._current_layer = "SLD_SYMBOLS"
        self._apply_layer_style()

    @property
    def canvas(self) -> Canvas:
        return self._canvas

    # -- Layer management --

    def set_layer(self, layer_name: str) -> None:
        self._current_layer = layer_name
        self._apply_layer_style()

    def _apply_layer_style(self) -> None:
        """Apply color and line width for the current layer."""
        color = _LAYER_COLORS.get(self._current_layer, (0, 0, 0))
        width_mm = _LAYER_LINE_WIDTHS_MM.get(self._current_layer, 0.5)
        self._canvas.setStrokeColorRGB(*color)
        self._canvas.setFillColorRGB(*color)
        self._canvas.setLineWidth(width_mm * mm)

    # -- Drawing primitives --

    def add_line(
        self,
        start: tuple[float, float],
        end: tuple[float, float],
        *,
        lineweight: int | None = None,
    ) -> None:
        c = self._canvas
        if lineweight is not None:
            # lineweight is in hundredths of mm (DXF convention)
            c.saveState()
            c.setLineWidth(lineweight / 100.0 * mm)
            c.line(start[0] * mm, start[1] * mm, end[0] * mm, end[1] * mm)
            c.restoreState()
        else:
            c.line(start[0] * mm, start[1] * mm, end[0] * mm, end[1] * mm)

    def add_lwpolyline(
        self,
        points: list[tuple[float, float]],
        *,
        close: bool = False,
        lineweight: int | None = None,
    ) -> None:
        if len(points) < 2:
            return

        c = self._canvas
        p = c.beginPath()
        p.moveTo(points[0][0] * mm, points[0][1] * mm)
        for pt in points[1:]:
            p.lineTo(pt[0] * mm, pt[1] * mm)
        if close:
            p.close()

        if lineweight is not None:
            c.saveState()
            c.setLineWidth(lineweight / 100.0 * mm)
            c.drawPath(p, stroke=1, fill=0)
            c.restoreState()
        else:
            c.drawPath(p, stroke=1, fill=0)

    def add_circle(
        self,
        center: tuple[float, float],
        radius: float,
    ) -> None:
        self._canvas.circle(
            center[0] * mm,
            center[1] * mm,
            radius * mm,
            stroke=1,
            fill=0,
        )

    def add_filled_circle(
        self,
        center: tuple[float, float],
        radius: float,
        *,
        fill_color: tuple[float, float, float] | str = (0.0, 0.0, 0.0),
    ) -> None:
        """Draw a filled circle (junction dot, etc.)."""
        c = self._canvas
        c.saveState()
        if isinstance(fill_color, str):
            h = fill_color.lstrip("#")
            c.setFillColorRGB(
                int(h[0:2], 16) / 255.0,
                int(h[2:4], 16) / 255.0,
                int(h[4:6], 16) / 255.0,
            )
        else:
            c.setFillColorRGB(*fill_color)
        c.circle(center[0] * mm, center[1] * mm, radius * mm, stroke=0, fill=1)
        c.restoreState()

    def add_arc(
        self,
        center: tuple[float, float],
        radius: float,
        start_angle: float,
        end_angle: float,
    ) -> None:
        """
        Draw an arc. Angles in degrees, CCW from positive X-axis.

        ReportLab's canvas.arc() expects a bounding box (x1, y1, x2, y2)
        and start_angle + extent.
        """
        cx, cy = center[0] * mm, center[1] * mm
        r = radius * mm

        x1, y1 = cx - r, cy - r
        x2, y2 = cx + r, cy + r

        # Calculate extent (handle wrapping)
        extent = end_angle - start_angle
        if extent < 0:
            extent += 360

        self._canvas.arc(x1, y1, x2, y2, start_angle, extent)

    def add_mtext(
        self,
        text: str,
        *,
        insert: tuple[float, float],
        char_height: float = 3.0,
        rotation: float = 0.0,
        center_across: bool = False,
    ) -> None:
        """
        Draw multiline text with optional rotation.

        Handles non-string input (e.g. dict from AI) by converting to str.
        Input insert is top-left anchor (DXF convention).
        ReportLab drawString anchors at baseline-left, so we offset by char_height.
        '\\P' is the DXF line break marker.

        Args:
            rotation: Degrees CCW. 90 = vertical text (bottom-to-top).
            center_across: If True, center text block perpendicular to text
                direction (matches DXF MIDDLE_LEFT attachment_point).
        """
        c = self._canvas
        font_size = char_height * mm  # Convert mm to points

        c.saveState()
        # Use annotation color for text
        color = _LAYER_COLORS.get(self._current_layer, (0.15, 0.15, 0.15))
        c.setFillColorRGB(*color)
        c.setFont("Helvetica", font_size)  # ReportLab maps Helvetica ≈ Arial

        if not isinstance(text, str):
            text = str(text)
        lines = text.split("\\P")
        line_spacing = char_height * 1.4  # 1.4x line height

        x_pt = insert[0] * mm
        y_pt = insert[1] * mm

        if abs(rotation) > 0.1:
            # Rotate around the insert point
            c.translate(x_pt, y_pt)
            c.rotate(rotation)

            ls_pt = line_spacing * mm
            if center_across:
                # Center text block across rotation axis (MIDDLE_LEFT equivalent).
                # Total block extent in local_y: from first baseline-font_size to
                # last baseline. N lines: first at -fs, last at -fs-(N-1)*ls.
                # Block spans [-fs, -(N-1)*ls] with size = fs + (N-1)*ls.
                # Center offset = block_size/2 from -fs → start at -fs + block/2.
                n = len(lines)
                block = font_size + (n - 1) * ls_pt
                local_y = -font_size + block / 2
            else:
                local_y = -font_size  # TOP_LEFT: first line offset
            for line in lines:
                c.drawString(0, local_y, line)
                local_y -= ls_pt
        else:
            # Non-rotated (fast path)
            y_draw = y_pt - font_size
            for line in lines:
                c.drawString(x_pt, y_draw, line)
                y_draw -= line_spacing * mm

        c.restoreState()

    def add_filled_rect(
        self,
        x: float,
        y: float,
        width: float,
        height: float,
        *,
        fill_color: tuple[float, float, float] | str = (0.0, 0.0, 0.0),
    ) -> None:
        """Draw a filled rectangle."""
        c = self._canvas
        c.saveState()

        if isinstance(fill_color, str):
            # Parse hex color
            hex_str = fill_color.lstrip("#")
            r = int(hex_str[0:2], 16) / 255.0
            g = int(hex_str[2:4], 16) / 255.0
            b = int(hex_str[4:6], 16) / 255.0
            c.setFillColorRGB(r, g, b)
        else:
            c.setFillColorRGB(*fill_color)

        c.setStrokeColorRGB(0, 0, 0)
        c.rect(x * mm, y * mm, width * mm, height * mm, stroke=1, fill=1)
        c.restoreState()

    # -- Output --

    def save(self) -> None:
        """Save the PDF document."""
        self._canvas.save()

    def get_bytes(self) -> bytes:
        """Get the PDF as bytes (only works with in-memory buffer)."""
        self._canvas.save()
        if hasattr(self, "_buffer"):
            return self._buffer.getvalue()
        else:
            # Read from file
            with open(self._output_path, "rb") as f:
                return f.read()
