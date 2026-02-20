"""
Circuit breaker symbols: ACB, MCCB, MCB, RCCB/ELCB.

IEC 60617 standard representation:
- Rectangle with X cross pattern
- Connection pins at top and bottom center
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

    width: float = 10
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    def __init__(self, breaker_type: str = "MCCB"):
        self.breaker_type = breaker_type
        self.name = f"CB_{breaker_type}"
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
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
        backend.add_line((cx, y + h), (cx, y + h + 3))
        backend.add_line((cx, y), (cx, y - 3))


class ACB(CircuitBreaker):
    """Air Circuit Breaker (for >630A)."""

    def __init__(self):
        super().__init__("ACB")


class MCCB(CircuitBreaker):
    """Moulded Case Circuit Breaker (100A-630A)."""

    def __init__(self):
        super().__init__("MCCB")


class MCB(CircuitBreaker):
    """Miniature Circuit Breaker (<100A)."""

    width: float = 8
    height: float = 12

    def __init__(self):
        super().__init__("MCB")
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
        }


class RCCB(BaseSymbol):
    """
    Residual Current Circuit Breaker (ELCB).
    Rectangle with X plus a curved arrow indicating earth leakage detection.
    """

    name: str = "CB_RCCB"
    width: float = 10
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
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
            center=(x + w + 3, y + h / 2),
            radius=3,
            start_angle=120,
            end_angle=240,
        )

        # Connection stubs
        cx = x + w / 2
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 3))
        backend.add_line((cx, y), (cx, y - 3))
