"""
Metering symbols: kWh Meter, Ammeter, Voltmeter.
Scaled for professional A3 engineering drawings.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class KwhMeter(BaseSymbol):
    """kWh Meter symbol -- circle with 'kWh'."""

    name: str = "KWH_METER"
    width: float = 20
    height: float = 20
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = 10

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=r)

        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("kWh", insert=(cx - 5, cy + 2), char_height=4)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + r), (cx, y + self.height + 5))
        backend.add_line((cx, cy - r), (cx, y - 5))


class Ammeter(BaseSymbol):
    """Ammeter symbol -- circle with 'A'."""

    name: str = "AMMETER"
    width: float = 16
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = 8

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=r)

        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("A", insert=(cx - 2.5, cy + 3), char_height=6)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + r), (cx, y + self.height + 5))
        backend.add_line((cx, cy - r), (cx, y - 5))
