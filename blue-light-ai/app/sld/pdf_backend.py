"""
PDF drawing backend using ReportLab.

Implements DrawingBackend protocol to generate A3 landscape PDF files
for SLD drawings submitted to EMA.
"""

from __future__ import annotations

import io
import math
from pathlib import Path

from reportlab.lib.pagesizes import A3, landscape
from reportlab.lib.units import mm
from reportlab.pdfgen.canvas import Canvas


# Layer → color mapping (PDF is on white paper, so use dark colors)
_LAYER_COLORS: dict[str, tuple[float, float, float]] = {
    "SLD_SYMBOLS": (0.0, 0.0, 0.0),       # Black
    "SLD_CONNECTIONS": (0.0, 0.0, 0.0),    # Black
    "SLD_ANNOTATIONS": (0.2, 0.2, 0.2),    # Dark gray
    "SLD_TITLE_BLOCK": (0.0, 0.0, 0.0),    # Black
}

# Default line widths per layer (in points)
_LAYER_LINE_WIDTHS: dict[str, float] = {
    "SLD_SYMBOLS": 0.5,
    "SLD_CONNECTIONS": 0.35,
    "SLD_ANNOTATIONS": 0.25,
    "SLD_TITLE_BLOCK": 0.5,
}


class PdfBackend:
    """
    ReportLab-based PDF drawing backend.

    Coordinate system: bottom-left origin, same as DXF.
    All input coordinates are in mm (matching the symbol library).
    Internally converts to points for ReportLab.
    """

    def __init__(self, output_path: str | None = None):
        """
        Args:
            output_path: File path for the PDF. If None, writes to in-memory buffer.
        """
        self._output_path = output_path
        self._page_size = landscape(A3)  # (1190.55pt, 841.89pt) = (420mm, 297mm)

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

    # ── Layer management ──────────────────────────────

    def set_layer(self, layer_name: str) -> None:
        self._current_layer = layer_name
        self._apply_layer_style()

    def _apply_layer_style(self) -> None:
        """Apply color and line width for the current layer."""
        color = _LAYER_COLORS.get(self._current_layer, (0, 0, 0))
        width = _LAYER_LINE_WIDTHS.get(self._current_layer, 0.5)
        self._canvas.setStrokeColorRGB(*color)
        self._canvas.setFillColorRGB(*color)
        self._canvas.setLineWidth(width)

    # ── Drawing primitives ────────────────────────────

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
    ) -> None:
        """
        Draw multiline text.

        Input insert is top-left anchor (DXF convention).
        ReportLab drawString anchors at baseline-left, so we offset by char_height.
        '\\P' is the DXF line break marker.
        """
        c = self._canvas
        font_size = char_height * mm  # Convert mm to points

        c.saveState()
        # Use annotation color for text
        color = _LAYER_COLORS.get(self._current_layer, (0.2, 0.2, 0.2))
        c.setFillColorRGB(*color)
        c.setFont("Helvetica", font_size)

        lines = text.split("\\P")
        line_spacing = char_height * 1.4  # 1.4x line height

        x_pt = insert[0] * mm
        # First line: offset down by char_height from top (insert is top-left)
        y_pt = insert[1] * mm - font_size

        for line in lines:
            c.drawString(x_pt, y_pt, line)
            y_pt -= line_spacing * mm

        c.restoreState()

    # ── Output ────────────────────────────────────────

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
