"""
Protection symbols: Fuse, Earth, Surge Protector.
Scaled for professional A3 engineering drawings.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class Fuse(BaseSymbol):
    """Fuse symbol -- narrow rectangle."""

    name: str = "FUSE"
    width: float = 8
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height
        cx = x + w / 2

        backend.set_layer(self.layer)

        # Narrow rectangle
        backend.add_lwpolyline(
            [(x, y + 2), (x + w, y + 2), (x + w, y + h - 2), (x, y + h - 2)],
            close=True,
        )

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h - 2), (cx, y + self.height + 5))
        backend.add_line((cx, y + 2), (cx, y - 5))


class EarthSymbol(BaseSymbol):
    """
    Earth/Ground symbol -- descending horizontal lines.
    Standard IEC 60617 representation.
    """

    name: str = "EARTH"
    width: float = 16
    height: float = 18
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2

        backend.set_layer(self.layer)

        # Vertical line from top
        backend.add_line((cx, y + self.height), (cx, y + 10))

        # Three descending horizontal lines
        backend.add_line((x, y + 10), (x + self.width, y + 10))
        backend.add_line((x + 3, y + 6), (x + self.width - 3, y + 6))
        backend.add_line((x + 6, y + 2), (x + self.width - 6, y + 2))


class SurgeProtector(BaseSymbol):
    """Surge Protection Device (SPD)."""

    name: str = "SPD"
    width: float = 12
    height: float = 18
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height
        cx = x + w / 2

        backend.set_layer(self.layer)

        # Rectangle
        backend.add_lwpolyline(
            [(x, y), (x + w, y), (x + w, y + h), (x, y + h)],
            close=True,
        )

        # Lightning bolt (zigzag)
        backend.add_lwpolyline(
            [
                (cx, y + h - 3),
                (cx + 3, y + h / 2 + 1),
                (cx - 3, y + h / 2 - 1),
                (cx, y + 3),
            ],
        )

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))
