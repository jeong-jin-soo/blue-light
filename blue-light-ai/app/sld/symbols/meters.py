"""
Metering symbols: kWh Meter, Ammeter, Voltmeter.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class KwhMeter(BaseSymbol):
    """kWh Meter symbol — circle with 'kWh'."""

    name: str = "KWH_METER"
    width: float = 16
    height: float = 16
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
        backend.add_circle((cx, cy), radius=8)

        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("kWh", insert=(cx - 4, cy + 1.5), char_height=3.5)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + 8), (cx, y + self.height + 3))
        backend.add_line((cx, cy - 8), (cx, y - 3))


class Ammeter(BaseSymbol):
    """Ammeter symbol — circle with 'A'."""

    name: str = "AMMETER"
    width: float = 12
    height: float = 12
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
        backend.add_circle((cx, cy), radius=6)

        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("A", insert=(cx - 2, cy + 2.5), char_height=5)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + 6), (cx, y + self.height + 3))
        backend.add_line((cx, cy - 6), (cx, y - 3))
