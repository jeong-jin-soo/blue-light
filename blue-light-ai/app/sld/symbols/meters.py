"""
Metering symbols: kWh Meter, Ammeter, Voltmeter.
Scaled for professional A3 engineering drawings.

KWH Meter: Rectangle with "KWH" text (per Singapore SLD standard / Basic symbol PDF).
Ammeter/Voltmeter: Circle with letter label (IEC 60617).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class KwhMeter(BaseSymbol):
    """
    kWh Meter symbol -- rectangle with 'KWH' text.
    Singapore SLD standard: rectangular box (not circle).
    """

    name: str = "KWH_METER"
    width: float = 20
    height: float = 14
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "outline": 0.7,
        "text": 0.35,
        "connection": 0.5,
    }

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }
        self.anchors = {
            "label_right": (self.width + 3, self.height / 2 + 2),
            "rating_below": (cx, -8),
            "label_above": (cx, self.height + 8),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height
        cx = x + w / 2
        cy = y + h / 2

        backend.set_layer(self.layer)

        # Rectangle outline
        backend.add_lwpolyline(
            [(x, y), (x + w, y), (x + w, y + h), (x, y + h)],
            close=True,
        )

        # "KWH" text centered inside
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("KWH", insert=(cx - 6, cy + 2), char_height=4)

        # Connection stubs (top and bottom from center)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))


class Ammeter(BaseSymbol):
    """Ammeter symbol -- circle with 'A'."""

    name: str = "AMMETER"
    width: float = 16
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "circle": 0.7,
        "text": 0.35,
        "connection": 0.5,
    }

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }
        self.anchors = {
            "label_right": (self.width + 3, self.height / 2 + 2),
            "range_left": (-3, self.height / 2),
            "label_above": (cx, self.height + 8),
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


class Voltmeter(BaseSymbol):
    """Voltmeter symbol -- circle with 'V'."""

    name: str = "VOLTMETER"
    width: float = 16
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "circle": 0.7,
        "text": 0.35,
        "connection": 0.5,
    }

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }
        self.anchors = {
            "label_right": (self.width + 3, self.height / 2 + 2),
            "range_left": (-3, self.height / 2),
            "label_above": (cx, self.height + 8),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = 8

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=r)

        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("V", insert=(cx - 2.5, cy + 3), char_height=6)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + r), (cx, y + self.height + 5))
        backend.add_line((cx, cy - r), (cx, y - 5))
