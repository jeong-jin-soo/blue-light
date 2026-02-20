"""
Motor and Generator symbols.

IEC 60617 standard: Circle with M/G designation.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class Motor(BaseSymbol):
    """Motor symbol — circle with 'M'."""

    name: str = "MOTOR"
    width: float = 16
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=8)

        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("M", insert=(cx - 2, cy + 3), char_height=6)

        # Connection stub (top)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + 8), (cx, y + self.height + 3))


class Generator(BaseSymbol):
    """Generator symbol — circle with 'G'."""

    name: str = "GENERATOR"
    width: float = 20
    height: float = 20
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=10)

        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("G", insert=(cx - 3, cy + 4), char_height=8)

        # Connection stub (top)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + 10), (cx, y + self.height + 5))
