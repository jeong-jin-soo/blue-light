"""
Lightweight SVG drawing backend for SLD preview.

Generates an SVG string directly without external dependencies.

Line weight standards calibrated from real LEW SLD samples:
- All layers: 0.25mm (uniform)
- SLD_POWER_MAIN: 0.50mm (busbar only)
- Color: 100% black
"""

from __future__ import annotations

import math
from xml.sax.saxutils import escape


# A3 landscape dimensions in mm
_PAGE_WIDTH = 420.0
_PAGE_HEIGHT = 297.0

# Layer -> stroke color (all black, matching real LEW SLD samples)
_LAYER_COLORS: dict[str, str] = {
    "SLD_SYMBOLS": "#000000",
    "SLD_CONNECTIONS": "#000000",
    "SLD_POWER_MAIN": "#000000",
    "SLD_ANNOTATIONS": "#000000",
    "SLD_TITLE_BLOCK": "#000000",
}

# Layer -> stroke width calibrated from real LEW SLD samples (0.25mm uniform)
_LAYER_STROKE_WIDTHS: dict[str, float] = {
    "SLD_SYMBOLS": 0.25,
    "SLD_CONNECTIONS": 0.25,
    "SLD_POWER_MAIN": 0.50,
    "SLD_ANNOTATIONS": 0.25,
    "SLD_TITLE_BLOCK": 0.25,
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
        return _LAYER_COLORS.get(self._current_layer, "#262626")

    # -- Layer management --

    def set_layer(self, layer_name: str) -> None:
        self._current_layer = layer_name

    # -- Drawing primitives --

    def add_line(
        self,
        start: tuple[float, float],
        end: tuple[float, float],
        *,
        lineweight: int | None = None,
    ) -> None:
        x1, y1 = start[0], self._flip_y(start[1])
        x2, y2 = end[0], self._flip_y(end[1])

        if lineweight is not None:
            w = lineweight / 100.0  # hundredths of mm -> mm
            color = _LAYER_COLORS.get(self._current_layer, "#000000")
            self._elements.append(
                f'<line x1="{x1:.2f}" y1="{y1:.2f}" x2="{x2:.2f}" y2="{y2:.2f}" '
                f'stroke="{color}" stroke-width="{w:.2f}" fill="none" />'
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

    def add_filled_circle(
        self,
        center: tuple[float, float],
        radius: float,
        *,
        fill_color: tuple[float, float, float] | str = (0.0, 0.0, 0.0),
    ) -> None:
        """Draw a filled circle (junction dot, etc.)."""
        cx = center[0]
        cy = self._flip_y(center[1])
        if isinstance(fill_color, str):
            fill = fill_color
        else:
            r, g, b = fill_color
            fill = f"rgb({int(r * 255)},{int(g * 255)},{int(b * 255)})"
        self._elements.append(
            f'<circle cx="{cx:.2f}" cy="{cy:.2f}" r="{radius:.2f}" '
            f'fill="{fill}" stroke="none" />'
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
        sa_rad = math.radians(start_angle)
        ea_rad = math.radians(end_angle)

        sx = center[0] + radius * math.cos(sa_rad)
        sy = center[1] + radius * math.sin(sa_rad)
        ex = center[0] + radius * math.cos(ea_rad)
        ey = center[1] + radius * math.sin(ea_rad)

        sy_svg = self._flip_y(sy)
        ey_svg = self._flip_y(ey)

        extent = end_angle - start_angle
        if extent < 0:
            extent += 360
        large_arc = 1 if extent > 180 else 0
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
        rotation: float = 0.0,
        center_across: bool = False,
    ) -> None:
        """
        Draw multiline text with optional rotation.
        Insert is top-left anchor (DXF convention).
        '\\P' is the line break marker.

        Args:
            rotation: Degrees CCW. 90 = vertical text (bottom-to-top).
        """
        if not isinstance(text, str):
            text = str(text)
        lines = text.split("\\P")
        x = insert[0]
        base_y = self._flip_y(insert[1]) + char_height * 0.8
        color = self._text_color()
        line_spacing = char_height * 1.4

        # Build transform attribute for rotation
        transform = ""
        if abs(rotation) > 0.1:
            svg_x = x
            svg_y = self._flip_y(insert[1])
            transform = f' transform="rotate({-rotation},{svg_x:.2f},{svg_y:.2f})"'

        if len(lines) == 1:
            escaped = escape(lines[0])
            self._elements.append(
                f'<text x="{x:.2f}" y="{base_y:.2f}" '
                f'font-family="Arial, Helvetica, sans-serif" font-size="{char_height:.1f}" '
                f'fill="{color}"{transform}>{escaped}</text>'
            )
        elif abs(rotation) > 0.1:
            # Rotated multiline: <tspan dy> offsets are applied in the
            # rotated coordinate system, so dy shifts horizontally instead
            # of vertically when rotation=90°.  Fix by emitting separate
            # <text> elements with pre-rotated absolute coordinates.
            rot_rad = math.radians(-rotation)
            cos_r = math.cos(rot_rad)
            sin_r = math.sin(rot_rad)
            font_attr = (
                f'font-family="Arial, Helvetica, sans-serif" '
                f'font-size="{char_height:.1f}" fill="{color}"'
            )
            for i, line_text in enumerate(lines):
                escaped = escape(line_text)
                # Local offset along the unrotated Y axis
                local_dy = i * line_spacing
                # Rotate the offset vector (0, local_dy) by the text rotation
                ox = -local_dy * sin_r
                oy = local_dy * cos_r
                lx = x + ox
                ly = base_y + oy
                self._elements.append(
                    f'<text x="{lx:.2f}" y="{ly:.2f}" {font_attr} '
                    f'transform="rotate({-rotation},{lx:.2f},{ly:.2f})">'
                    f'{escaped}</text>'
                )
        else:
            # Non-rotated multiline: <tspan dy> works correctly
            parts = [
                f'<text x="{x:.2f}" y="{base_y:.2f}" '
                f'font-family="Arial, Helvetica, sans-serif" font-size="{char_height:.1f}" '
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
        svg_y = self._flip_y(y + height)

        if isinstance(fill_color, str):
            fill = fill_color
        else:
            r, g, b = fill_color
            fill = f"rgb({int(r*255)},{int(g*255)},{int(b*255)})"

        self._elements.append(
            f'<rect x="{x:.2f}" y="{svg_y:.2f}" width="{width:.2f}" height="{height:.2f}" '
            f'fill="{fill}" stroke="#000000" stroke-width="0.35" />'
        )

    # -- Output --

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
