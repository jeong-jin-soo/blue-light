"""
Transformer symbols: Power Transformer, CT, PT.

IEC 60617 standard representation:
- Two overlapping circles for power transformer
- Two overlapping circles (smaller) for CT (current transformer)
- Rectangle with 'PT' text for potential transformer (metering)

Reference: Basic symbol for SLD.pdf, MSB Standard Installation section.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class PowerTransformer(BaseSymbol):
    """
    Power Transformer symbol -- two overlapping circles.
    Primary coil (top) and secondary coil (bottom).
    """

    name: str = "TRANSFORMER"
    width: float = 16
    height: float = 28
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "coil": 0.7,
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
            "label_above": (cx, self.height + 8),
            "rating_below": (cx, -8),
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
    """
    Current Transformer (CT) -- two overlapping circles (IEC standard).

    Reference: MSB Standard Installation in Basic symbol for SLD.pdf
    Shows CT as two overlapping small circles on the conductor,
    NOT as a circle with "CT" text.

    Visual:
        │     <- top connection
       ⊙⊙    <- two overlapping circles (primary + secondary windings)
        │     <- bottom connection
    """

    name: str = "CT"
    width: float = 12
    height: float = 12
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "coil": 0.7,
        "connection": 0.5,
    }

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
        }
        self.anchors = {
            "label_right": (self.width + 2, self.height / 2 + 2),
            "ratio_right": (self.width + 2, self.height / 2 - 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = 4  # Radius of each CT coil circle

        backend.set_layer(self.layer)

        # Two overlapping circles (IEC 60617 CT symbol)
        # Primary winding (top circle, slightly above center)
        backend.add_circle((cx, cy + r * 0.4), radius=r)
        # Secondary winding (bottom circle, slightly below center)
        backend.add_circle((cx, cy - r * 0.4), radius=r)

        # Connection stubs (through the CT)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + r + r * 0.4), (cx, y + self.height + 3))
        backend.add_line((cx, cy - r - r * 0.4), (cx, y - 3))


class PotentialTransformer(BaseSymbol):
    """
    Potential Transformer (PT) / Voltage Transformer (VT) -- rectangle with 'PT' text.

    Used in metering sections of larger installations alongside CT.
    Typically connected through a 2A fuse for voltage measurement.

    Seen in real SLD: "3-Phase incoming tap Single Phase DB 1"
    (Herb & Tea, Futurology Pte Ltd) — positioned between 2A Fuse and CT
    in the metering/protection section.

    Visual:
        ┌────┐
        │ PT │
        └────┘
    """

    name: str = "PT"
    width: float = 14
    height: float = 10
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "outline": 0.7,
        "text": 0.35,
        "connection": 0.5,
    }

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
            "left": (0, self.height / 2),
            "right": (self.width, self.height / 2),
        }
        self.anchors = {
            "label_right": (self.width + 3, self.height / 2 + 2),
            "label_above": (cx, self.height + 5),
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

        # "PT" text centered
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("PT", insert=(cx - 3, cy + 2), char_height=4)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 3))
        backend.add_line((cx, y), (cx, y - 3))
