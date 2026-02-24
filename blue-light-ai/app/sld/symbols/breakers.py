"""
Circuit breaker symbols: ACB, MCCB, MCB, RCCB/ELCB.

IEC 60617 standard representation:
- Rectangle with X cross pattern (common to all breakers)
- ACB: Larger with double-contact indicator (horizontal bar)
- MCCB: Standard size (14x20mm)
- MCB: Smaller size (10x16mm)
- Connection pins at top and bottom center

Sizes scaled for professional A3 engineering drawings.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class CircuitBreaker(BaseSymbol):
    """
    Generic circuit breaker symbol (rectangle with X).
    Used as base for ACB, MCCB, MCB.
    """

    width: float = 14
    height: float = 20
    layer: str = "SLD_SYMBOLS"

    def __init__(self, breaker_type: str = "MCCB"):
        self.breaker_type = breaker_type
        self.name = f"CB_{breaker_type}"
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height

        backend.set_layer(self.layer)

        # Rectangle
        backend.add_lwpolyline(
            [(x, y), (x + w, y), (x + w, y + h), (x, y + h)],
            close=True,
        )

        # X cross
        backend.add_line((x, y), (x + w, y + h))
        backend.add_line((x + w, y), (x, y + h))

        # Connection stubs (top and bottom)
        cx = x + w / 2
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))


class ACB(CircuitBreaker):
    """
    Air Circuit Breaker (for >630A).
    Distinctive: larger body + double-contact indicator (horizontal bar through center)
    per IEC 60617 distinction for withdrawable/air-break type.
    """

    width: float = 16
    height: float = 22

    def __init__(self):
        super().__init__("ACB")
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height

        backend.set_layer(self.layer)

        # Rectangle (slightly thicker for ACB)
        backend.add_lwpolyline(
            [(x, y), (x + w, y), (x + w, y + h), (x, y + h)],
            close=True,
        )

        # X cross
        backend.add_line((x, y), (x + w, y + h))
        backend.add_line((x + w, y), (x, y + h))

        # ACB distinctive: double-contact indicator (horizontal bar through center)
        # This differentiates ACB from MCCB per IEC 60617
        mid_y = y + h / 2
        backend.add_line((x - 2, mid_y), (x + w + 2, mid_y))

        # Connection stubs (top and bottom)
        cx = x + w / 2
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))


class MCCB(CircuitBreaker):
    """Moulded Case Circuit Breaker (100A-630A)."""

    def __init__(self):
        super().__init__("MCCB")


class MCB(CircuitBreaker):
    """Miniature Circuit Breaker (<100A)."""

    width: float = 10
    height: float = 16

    def __init__(self):
        super().__init__("MCB")
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }


class RCCB(BaseSymbol):
    """
    Residual Current Circuit Breaker (ELCB).
    Rectangle with X plus a curved arrow indicating earth leakage detection.
    """

    name: str = "CB_RCCB"
    width: float = 14
    height: float = 20
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height

        backend.set_layer(self.layer)

        # Rectangle
        backend.add_lwpolyline(
            [(x, y), (x + w, y), (x + w, y + h), (x, y + h)],
            close=True,
        )

        # X cross
        backend.add_line((x, y), (x + w, y + h))
        backend.add_line((x + w, y), (x, y + h))

        # Earth leakage indicator (small arc on the right side)
        backend.add_arc(
            center=(x + w + 4, y + h / 2),
            radius=4,
            start_angle=120,
            end_angle=240,
        )

        # Connection stubs
        cx = x + w / 2
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))


class ELCB(BaseSymbol):
    """
    Earth Leakage Circuit Breaker.
    Similar to RCCB but with specific ELCB marking and earth indicator.
    Used for sub-circuit group protection (e.g., 100A 4P ELCB 30mA).
    """

    name: str = "CB_ELCB"
    width: float = 14
    height: float = 20
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height

        backend.set_layer(self.layer)

        # Rectangle
        backend.add_lwpolyline(
            [(x, y), (x + w, y), (x + w, y + h), (x, y + h)],
            close=True,
        )

        # X cross
        backend.add_line((x, y), (x + w, y + h))
        backend.add_line((x + w, y), (x, y + h))

        # Earth leakage indicator (arc on right side)
        backend.add_arc(
            center=(x + w + 4, y + h / 2),
            radius=4,
            start_angle=120,
            end_angle=240,
        )

        # Small earth symbol indicator (arrow down from arc)
        arrow_x = x + w + 4
        arrow_y = y + h / 2 - 6
        backend.add_line((arrow_x, arrow_y), (arrow_x, arrow_y - 3))
        backend.add_line((arrow_x - 2, arrow_y - 3), (arrow_x + 2, arrow_y - 3))

        # Connection stubs
        cx = x + w / 2
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))
