"""
Transformer symbols: Power Transformer, CT, PT.

IEC 60617 standard representation:
- Two overlapping circles for power transformer
- Single circle with designation for CT/PT
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class PowerTransformer(BaseSymbol):
    """
    Power Transformer symbol — two overlapping circles.
    Primary coil (top) and secondary coil (bottom).
    """

    name: str = "TRANSFORMER"
    width: float = 16
    height: float = 28
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        r = 8  # Radius of each coil circle
        cx = x + self.width / 2

        backend.set_layer(self.layer)

        # Primary coil (top circle)
        backend.add_circle((cx, y + r + 6), radius=r)

        # Secondary coil (bottom circle, overlapping)
        backend.add_circle((cx, y + r - 2), radius=r)

        # Connection lines
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + r + 6 + r), (cx, y + self.height + 5))
        backend.add_line((cx, y + r - 2 - r), (cx, y - 5))


class CurrentTransformer(BaseSymbol):
    """Current Transformer (CT) — circle with 'CT' text."""

    name: str = "CT"
    width: float = 10
    height: float = 10
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=5)

        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("CT", insert=(cx - 2.5, cy + 1.5), char_height=3)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + 5), (cx, y + self.height + 3))
        backend.add_line((cx, cy - 5), (cx, y - 3))
