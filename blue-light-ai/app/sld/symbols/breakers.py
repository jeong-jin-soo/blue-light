"""
Circuit breaker symbols: ACB, MCCB, MCB, RCCB/ELCB.

IEC 60617 standard representation:
- Rectangle with X cross pattern
- Connection pins at top and bottom center
"""

from app.sld.symbols.base import BaseSymbol

import ezdxf


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

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        w, h = self.width, self.height
        attribs = {"layer": self.layer}

        # Rectangle
        block.add_lwpolyline(
            [(0, 0), (w, 0), (w, h), (0, h)],
            close=True,
            dxfattribs=attribs,
        )

        # X cross
        block.add_line((0, 0), (w, h), dxfattribs=attribs)
        block.add_line((w, 0), (0, h), dxfattribs=attribs)

        # Connection stubs (top and bottom)
        cx = w / 2
        block.add_line((cx, h), (cx, h + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((cx, 0), (cx, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})


class ACB(CircuitBreaker):
    """Air Circuit Breaker (for >630A)."""

    def __init__(self):
        super().__init__("ACB")


class MCCB(CircuitBreaker):
    """Moulded Case Circuit Breaker (100A–630A)."""

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

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        w, h = self.width, self.height
        attribs = {"layer": self.layer}

        # Rectangle
        block.add_lwpolyline(
            [(0, 0), (w, 0), (w, h), (0, h)],
            close=True,
            dxfattribs=attribs,
        )

        # X cross
        block.add_line((0, 0), (w, h), dxfattribs=attribs)
        block.add_line((w, 0), (0, h), dxfattribs=attribs)

        # Earth leakage indicator (small arc on the right side)
        block.add_arc(
            center=(w + 3, h / 2),
            radius=3,
            start_angle=120,
            end_angle=240,
            dxfattribs=attribs,
        )

        # Connection stubs
        cx = w / 2
        block.add_line((cx, h), (cx, h + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((cx, 0), (cx, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})
