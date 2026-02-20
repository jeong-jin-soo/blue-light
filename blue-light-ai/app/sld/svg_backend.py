"""
Lightweight SVG drawing backend for SLD preview.

Generates an SVG string directly without external dependencies.
Replaces the ezdxf SVG rendering pipeline.
"""

from __future__ import annotations

import math
from xml.sax.saxutils import escape


# A3 landscape dimensions in mm
_PAGE_WIDTH = 420.0
_PAGE_HEIGHT = 297.0

# Layer → stroke color mapping (SVG on white background)
_LAYER_COLORS: dict[str, str] = {
    "SLD_SYMBOLS": "#000000",
    "SLD_CONNECTIONS": "#000000",
    "SLD_ANNOTATIONS": "#333333",
    "SLD_TITLE_BLOCK": "#000000",
}

# Layer → stroke width in mm
_LAYER_STROKE_WIDTHS: dict[str, float] = {
    "SLD_SYMBOLS": 0.5,
    "SLD_CONNECTIONS": 0.35,
    "SLD_ANNOTATIONS": 0.25,
    "SLD_TITLE_BLOCK": 0.5,
}


class SvgBackend:
    """
    Lightweight SVG string builder implementing the DrawingBackend protocol.

    Coordinate transformation:
    - Input: bottom-left origin (matching DXF/PDF), coordinates in mm
    - SVG: top-left origin, so Y is flipped: y_svg = PAGE_HEIGHT - y_mm
    """

    def __init__(
        self,
        page_width: float = _PAGE_WIDTH,
        page_height: float = _PAGE_HEIGHT,
    ):
        self._page_width = page_width
        self._page_height = page_height
        self._elements: list[str] = []
        self._current_layer = "SLD_SYMBOLS"

    def _flip_y(self, y: float) -> float:
        """Convert bottom-left Y to SVG top-left Y."""
        return self._page_height - y

    def _stroke(self) -> str:
        color = _LAYER_COLORS.get(self._current_layer, "#000000")
        width = _LAYER_STROKE_WIDTHS.get(self._current_layer, 0.5)
        return f'stroke="{color}" stroke-width="{width}" fill="none"'

    def _text_color(self) -> str:
        return _LAYER_COLORS.get(self._current_layer, "#333333")

    # ── Layer management ──────────────────────────────

    def set_layer(self, layer_name: str) -> None:
        self._current_layer = layer_name

    # ── Drawing primitives ────────────────────────────

    def add_line(
        self,
        start: tuple[float, float],
        end: tuple[float, float],
        *,
        lineweight: int | None = None,
    ) -> None:
        x1, y1 = start[0], self._flip_y(start[1])
        x2, y2 = end[0], self._flip_y(end[1])

        extra = ""
        if lineweight is not None:
            w = lineweight / 100.0  # hundredths of mm → mm
            color = _LAYER_COLORS.get(self._current_layer, "#000000")
            extra = f' stroke="{color}" stroke-width="{w:.2f}"'
            self._elements.append(
                f'<line x1="{x1:.2f}" y1="{y1:.2f}" x2="{x2:.2f}" y2="{y2:.2f}"{extra} fill="none" />'
            )
        else:
            self._elements.append(
                f'<line x1="{x1:.2f}" y1="{y1:.2f}" x2="{x2:.2f}" y2="{y2:.2f}" {self._stroke()} />'
            )

    def add_lwpolyline(
        self,
        points: list[tuple[float, float]],
        *,
        close: bool = False,
        lineweight: int | None = None,
    ) -> None:
        if len(points) < 2:
            return

        pts_str = " ".join(
            f"{p[0]:.2f},{self._flip_y(p[1]):.2f}" for p in points
        )

        stroke_attr = self._stroke()
        if lineweight is not None:
            w = lineweight / 100.0
            color = _LAYER_COLORS.get(self._current_layer, "#000000")
            stroke_attr = f'stroke="{color}" stroke-width="{w:.2f}" fill="none"'

        if close:
            self._elements.append(f'<polygon points="{pts_str}" {stroke_attr} />')
        else:
            self._elements.append(f'<polyline points="{pts_str}" {stroke_attr} />')

    def add_circle(
        self,
        center: tuple[float, float],
        radius: float,
    ) -> None:
        cx = center[0]
        cy = self._flip_y(center[1])
        self._elements.append(
            f'<circle cx="{cx:.2f}" cy="{cy:.2f}" r="{radius:.2f}" {self._stroke()} />'
        )

    def add_arc(
        self,
        center: tuple[float, float],
        radius: float,
        start_angle: float,
        end_angle: float,
    ) -> None:
        """
        Draw an arc using SVG path arc commands.

        Angles: degrees, CCW from positive X-axis (DXF convention).
        SVG arcs use CW angles, so we negate the Y components.
        """
        # Convert angles to radians
        sa_rad = math.radians(start_angle)
        ea_rad = math.radians(end_angle)

        # Start and end points on the arc (in input coordinate space)
        sx = center[0] + radius * math.cos(sa_rad)
        sy = center[1] + radius * math.sin(sa_rad)
        ex = center[0] + radius * math.cos(ea_rad)
        ey = center[1] + radius * math.sin(ea_rad)

        # Flip Y for SVG
        sy_svg = self._flip_y(sy)
        ey_svg = self._flip_y(ey)

        # Determine arc sweep
        extent = end_angle - start_angle
        if extent < 0:
            extent += 360
        large_arc = 1 if extent > 180 else 0
        # SVG sweep flag: 0 = CCW in SVG coords (which is CW in math coords due to Y flip)
        sweep = 0

        self._elements.append(
            f'<path d="M {sx:.2f},{sy_svg:.2f} A {radius:.2f},{radius:.2f} 0 {large_arc},{sweep} {ex:.2f},{ey_svg:.2f}" {self._stroke()} />'
        )

    def add_mtext(
        self,
        text: str,
        *,
        insert: tuple[float, float],
        char_height: float = 3.0,
    ) -> None:
        """
        Draw multiline text. Insert is top-left anchor (DXF convention).
        '\\P' is the line break marker.
        """
        lines = text.split("\\P")
        x = insert[0]
        # First line: top-left → SVG text anchor at baseline
        base_y = self._flip_y(insert[1]) + char_height * 0.8  # approximate baseline offset
        color = self._text_color()
        line_spacing = char_height * 1.4

        if len(lines) == 1:
            escaped = escape(lines[0])
            self._elements.append(
                f'<text x="{x:.2f}" y="{base_y:.2f}" '
                f'font-family="Helvetica, Arial, sans-serif" font-size="{char_height:.1f}" '
                f'fill="{color}">{escaped}</text>'
            )
        else:
            parts = [
                f'<text x="{x:.2f}" y="{base_y:.2f}" '
                f'font-family="Helvetica, Arial, sans-serif" font-size="{char_height:.1f}" '
                f'fill="{color}">'
            ]
            for i, line in enumerate(lines):
                escaped = escape(line)
                if i == 0:
                    parts.append(f'<tspan x="{x:.2f}">{escaped}</tspan>')
                else:
                    parts.append(
                        f'<tspan x="{x:.2f}" dy="{line_spacing:.1f}">{escaped}</tspan>'
                    )
            parts.append("</text>")
            self._elements.append("".join(parts))

    # ── Output ────────────────────────────────────────

    def get_svg_string(self) -> str:
        """Build and return the complete SVG string."""
        elements = "\n  ".join(self._elements)
        return (
            f'<svg xmlns="http://www.w3.org/2000/svg" '
            f'viewBox="0 0 {self._page_width} {self._page_height}" '
            f'width="{self._page_width}mm" height="{self._page_height}mm">\n'
            f'  <rect width="{self._page_width}" height="{self._page_height}" fill="white" />\n'
            f"  {elements}\n"
            f"</svg>"
        )
