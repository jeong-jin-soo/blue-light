"""
MSB (Main Switchboard) component symbols: Shunt Trip, Indicator Light, Protection Relay.
Scaled for professional A3 engineering drawings.

Reference: MSB Standard Installation section in Basic symbol for SLD.pdf.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class ShuntTrip(BaseSymbol):
    """
    Shunt Trip device -- rectangle with 'ST' text.
    Reference: MSB Standard Installation in Basic symbol for SLD.pdf
    Used with MCCB for remote tripping capability.

    Visual:
        ┌────┐
        │ ST │
        └────┘
    """

    name: str = "SHUNT_TRIP"
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

        # "ST" text centered
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("ST", insert=(cx - 3, cy + 2), char_height=4)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 3))
        backend.add_line((cx, y), (cx, y - 3))


class IndicatorLight(BaseSymbol):
    """
    Indicator Light -- small circle with optional phase label.
    Reference: MSB Standard Installation in Basic symbol for SLD.pdf
    Used for L1/L2/L3 indicator lights on switchboards.

    Visual:
        ○──  <- small open circle with connection line
    """

    name: str = "INDICATOR_LIGHT"
    width: float = 8
    height: float = 8
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "circle": 0.7,
        "connection": 0.5,
    }

    def __init__(self, phase_label: str = ""):
        self.phase_label = phase_label
        if phase_label:
            self.name = f"IND_LIGHT_{phase_label}"
        cx = self.width / 2
        cy = self.height / 2
        self.pins = {
            "left": (0, cy),
            "right": (self.width, cy),
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
        }
        self.anchors = {
            "label_above": (cx, self.height + 3),
            "label_below": (cx, -3),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = 3  # Small circle radius

        backend.set_layer(self.layer)

        # Open circle (indicator light)
        backend.add_circle((cx, cy), radius=r)

        # Cross inside circle to show it's a light
        backend.add_line((cx - 2, cy - 2), (cx + 2, cy + 2))
        backend.add_line((cx - 2, cy + 2), (cx + 2, cy - 2))

        # Phase label if provided
        if self.phase_label:
            backend.set_layer("SLD_ANNOTATIONS")
            backend.add_mtext(
                self.phase_label,
                insert=(cx - 1.5, y + self.height + 4),
                char_height=2.5,
            )

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx - r, cy), (x, cy))
        backend.add_line((cx + r, cy), (x + self.width, cy))


class ProtectionRelay(BaseSymbol):
    """
    Protection Relay (O/C E/F) -- rectangle with relay function text.
    Reference: MSB metering/protection section in real 3-Phase/TPN SLDs
    (150A+, 200A, 500A installations).

    Sits between Protection CT and Shunt Trip in the MSB protection chain:
    PCT → O/C E/F Relay → ST → MCCB

    Common relay designations:
    - O/C E/F  (Over-Current & Earth-Fault)
    - GMRL DTL (Ground Fault Relay, Definite Time Lag)
    - O/C EMTL (Over-Current, Extremely Inverse Time Lag)
    - E/F DTL  (Earth-Fault, Definite Time Lag)

    Visual:
        ┌───────┐
        │ O/C   │
        │ E/F   │
        └───────┘
    """

    name: str = "PROTECTION_RELAY"
    width: float = 16
    height: float = 12
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

        # "O/C" on top line, "E/F" on bottom line (centered)
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("O/C", insert=(cx - 3.5, cy + 4.5), char_height=3.5)
        backend.add_mtext("E/F", insert=(cx - 3, cy + 0.5), char_height=3.5)

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 3))
        backend.add_line((cx, y), (cx, y - 3))
