"""
Base class for all SLD electrical symbols.

Each symbol draws itself onto a DrawingBackend at a given (x, y) offset,
using standardized connection pins for layout integration.

Enhanced with:
- anchors: Named text anchor points for label positioning
- lineweights: Per-element line thickness specifications (mm)
- to_svg(): Standalone SVG export for verification
"""

from __future__ import annotations

import math
from abc import ABC, abstractmethod
from typing import TYPE_CHECKING
from xml.sax.saxutils import escape

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class BaseSymbol(ABC):
    """
    Abstract base class for electrical symbols in the SLD.

    Each symbol defines:
    - name: Unique identifier for this symbol type
    - width/height: Bounding box dimensions (mm)
    - pins: Named connection points relative to the symbol origin (0, 0)
            e.g., {"top": (5, 16), "bottom": (5, 0)}
    - anchors: Named text anchor points relative to the symbol origin
            e.g., {"label_right": (18, 14), "rating_below": (7, -8)}
    - lineweights: Per-element line thickness in mm
            e.g., {"arc": 0.7, "connection": 0.5}
    """

    name: str = ""
    width: float = 0
    height: float = 0
    pins: dict[str, tuple[float, float]] = {}

    # Text anchor points (relative to symbol origin)
    anchors: dict[str, tuple[float, float]] = {}

    # Per-element line weights in mm
    lineweights: dict[str, float] = {}

    # Default drawing layer for this symbol type
    layer: str = "SLD_SYMBOLS"

    @abstractmethod
    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        """
        Draw the symbol onto the backend at position (x, y).

        Subclasses implement this to define their shape.
        All internal coordinates should be offset by (x, y).
        """
        pass

    def get_pin(self, pin_name: str) -> tuple[float, float]:
        """Get the position of a named connection pin (relative to origin)."""
        if pin_name not in self.pins:
            raise ValueError(
                f"Symbol '{self.name}' has no pin '{pin_name}'. "
                f"Available: {list(self.pins.keys())}"
            )
        return self.pins[pin_name]

    def get_pin_absolute(self, pin_name: str, x: float, y: float) -> tuple[float, float]:
        """Get the absolute position of a named connection pin."""
        px, py = self.get_pin(pin_name)
        return (x + px, y + py)

    def get_anchor(self, anchor_name: str, x: float = 0, y: float = 0) -> tuple[float, float]:
        """
        Get absolute position of a named text anchor point.

        Args:
            anchor_name: Name of the anchor (e.g., "label_right", "rating_below")
            x, y: Symbol placement offset

        Returns:
            Absolute (x, y) position for text placement.

        Raises:
            ValueError: If anchor_name not found.
        """
        if anchor_name not in self.anchors:
            raise ValueError(
                f"Symbol '{self.name}' has no anchor '{anchor_name}'. "
                f"Available: {list(self.anchors.keys())}"
            )
        ax, ay = self.anchors[anchor_name]
        return (x + ax, y + ay)

    def horizontal_pins(self, x: float, y: float) -> dict[str, tuple[float, float]]:
        """Absolute pin positions for horizontal placement (rotation=90°).

        Default: h_extent = self.height, stubs at both ends.
        Override in symbols where h_extent differs (e.g. ELR uses width).

        Args:
            x: Left edge of horizontal extent (comp.x).
            y: Vertical center line (comp.y).
        Returns:
            {"left": (abs_x, abs_y), "right": (abs_x, abs_y)}
        """
        stub = getattr(self, '_stub', 3.0)
        return {"left": (x - stub, y), "right": (x + self.height + stub, y)}

    def vertical_pins(self, x: float, y: float) -> dict[str, tuple[float, float]]:
        """Absolute pin positions for vertical placement (rotation=0°).

        Uses the existing ``pins`` dict offset by (x, y).

        Args:
            x: Left edge of component (comp.x).
            y: Bottom edge of component (comp.y).
        Returns:
            Dict of pin_name -> (abs_x, abs_y).
        """
        return {name: (x + px, y + py) for name, (px, py) in self.pins.items()}

    def center(self) -> tuple[float, float]:
        """Get the center point of the symbol bounding box (relative to origin)."""
        return (self.width / 2, self.height / 2)

    def to_svg(self, padding: float = 10, show_pins: bool = True, show_anchors: bool = True) -> str:
        """
        Export this symbol as a standalone SVG string for verification.

        Args:
            padding: Padding around the symbol in mm
            show_pins: If True, draw red dots at pin positions
            show_anchors: If True, draw blue dots at anchor positions with labels

        Returns:
            Complete SVG string.
        """
        from app.sld.svg_backend import SvgBackend

        # Calculate SVG viewport to fit symbol + stubs + padding
        # pins can extend beyond bounding box (stubs are typically 5mm)
        min_x, min_y = 0, 0
        max_x, max_y = self.width, self.height
        for px, py in self.pins.values():
            min_x = min(min_x, px)
            min_y = min(min_y, py)
            max_x = max(max_x, px)
            max_y = max(max_y, py)
        for ax, ay in self.anchors.values():
            min_x = min(min_x, ax)
            min_y = min(min_y, ay)
            max_x = max(max_x, ax + 20)  # room for anchor labels
            max_y = max(max_y, ay)

        svg_w = (max_x - min_x) + 2 * padding
        svg_h = (max_y - min_y) + 2 * padding

        # Offset so symbol is centered in viewport
        offset_x = padding - min_x
        offset_y = padding - min_y

        backend = SvgBackend(page_width=svg_w, page_height=svg_h)
        self.draw(backend, offset_x, offset_y)

        # Draw bounding box (dashed)
        elements = backend._elements

        # Bounding box (gray dashed)
        bb_y_top = svg_h - (offset_y + self.height)
        elements.append(
            f'<rect x="{offset_x:.2f}" y="{bb_y_top:.2f}" '
            f'width="{self.width:.2f}" height="{self.height:.2f}" '
            f'fill="none" stroke="#999999" stroke-width="0.2" stroke-dasharray="2,2" />'
        )

        # Pin markers (red dots)
        if show_pins:
            for pin_name, (px, py) in self.pins.items():
                sx = offset_x + px
                sy = svg_h - (offset_y + py)
                elements.append(
                    f'<circle cx="{sx:.2f}" cy="{sy:.2f}" r="1.2" '
                    f'fill="red" stroke="none" opacity="0.8" />'
                )
                elements.append(
                    f'<text x="{sx + 2:.2f}" y="{sy - 1:.2f}" '
                    f'font-size="2" fill="red" font-family="monospace">'
                    f'{escape(pin_name)}</text>'
                )

        # Anchor markers (blue dots)
        if show_anchors and self.anchors:
            for anc_name, (ax, ay) in self.anchors.items():
                sx = offset_x + ax
                sy = svg_h - (offset_y + ay)
                elements.append(
                    f'<circle cx="{sx:.2f}" cy="{sy:.2f}" r="1.0" '
                    f'fill="blue" stroke="none" opacity="0.7" />'
                )
                elements.append(
                    f'<text x="{sx + 2:.2f}" y="{sy - 1:.2f}" '
                    f'font-size="1.8" fill="blue" font-family="monospace">'
                    f'{escape(anc_name)}</text>'
                )

        # Title
        title_y = svg_h - 2
        elements.append(
            f'<text x="{svg_w / 2:.2f}" y="{title_y:.2f}" '
            f'text-anchor="middle" font-size="3" fill="#333" font-family="sans-serif">'
            f'{escape(self.name)} ({self.width:.0f}x{self.height:.0f}mm)</text>'
        )

        return backend.get_svg_string()
