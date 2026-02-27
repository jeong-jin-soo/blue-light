"""
Load symbols: Industrial Socket, Timer, Timer with Bypass Switch.
Scaled for professional A3 engineering drawings.

Reference: Basic symbol for SLD.pdf (Singapore SLD standard).
"""

from __future__ import annotations

import math
from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class IndustrialSocket(BaseSymbol):
    """
    Industrial Socket (CEE-Form) symbol.
    Circle with 3 pin dots arranged in a triangle pattern.
    Reference: "16A 3-PIN 1phi Cee-Form INDUSTRIAL SOCKET" in Basic symbol for SLD.pdf

    Visual:
        ┌───────┐
        │  *    │   <- 3 dots in triangle
        │ * *   │
        └───────┘
    """

    name: str = "INDUSTRIAL_SOCKET"
    width: float = 16
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "circle": 0.7,
        "pins": 0.7,
        "connection": 0.5,
    }

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
        }
        self.anchors = {
            "label_right": (self.width + 3, self.height / 2 + 2),
            "label_below": (cx, -3),
            "rating_below": (cx, -6),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = 7  # Circle radius

        backend.set_layer(self.layer)

        # Outer circle
        backend.add_circle((cx, cy), radius=r)

        # 3 pin dots in triangle arrangement (120 degrees apart)
        pin_r = 3.0  # Distance from center to each pin
        for i in range(3):
            angle = math.radians(90 + i * 120)  # Start from top, 120 apart
            px = cx + pin_r * math.cos(angle)
            py = cy + pin_r * math.sin(angle)
            # Filled dot (small circle)
            backend.add_circle((px, py), radius=1.0)

        # Connection stub (top)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + r), (cx, y + self.height + 5))


class Timer(BaseSymbol):
    """
    Simple Timer / Time Switch symbol.
    Circle with 'T' text — controls circuits based on time schedule.
    Used inline on branch circuits (e.g., track light, signage lighting).
    Seen in real SLD: "40A DB 6 - Promiss, White Sands Mall" on S2 track light circuit.

    Visual:
        │
        ○T  <- circle with "T"
        │
    """

    name: str = "TIMER"
    width: float = 12
    height: float = 12
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
            "label_above": (cx, self.height + 8),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = 5

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=r)

        # "T" text centered inside
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("T", insert=(cx - 2, cy + 2.5), char_height=4)

        # Connection stubs (top and bottom)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + r), (cx, y + self.height + 5))
        backend.add_line((cx, cy - r), (cx, y - 5))


class TimerWithBypass(BaseSymbol):
    """
    Timer with Bypass Switch.
    Timer (circle with 'T') connected to a bypass contactor (rectangle with 'C').
    Reference: "TIMER WITH BY-PASS SWITCH" in Basic symbol for SLD.pdf

    Visual:
        │
        ┌──┐
        │T │ <- Timer (circle)
        └──┘
        │  ╲── ┌──┐
        │      │C │ <- Bypass contactor
        │  ╱── └──┘
        │
    """

    name: str = "TIMER_BYPASS"
    width: float = 28
    height: float = 24
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "timer": 0.7,
        "contactor": 0.7,
        "connection": 0.5,
        "text": 0.35,
    }

    def __init__(self):
        self.pins = {
            "top": (6, self.height + 5),
            "bottom": (6, -5),
        }
        self.anchors = {
            "label_right": (self.width + 3, self.height / 2 + 2),
            "label_above": (6, self.height + 8),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        h = self.height

        backend.set_layer(self.layer)

        # Timer (T) - circle on the left, upper portion
        timer_cx = x + 6
        timer_cy = y + h - 6
        timer_r = 5
        backend.add_circle((timer_cx, timer_cy), radius=timer_r)

        # "T" text in timer
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("T", insert=(timer_cx - 2, timer_cy + 2.5), char_height=4)

        backend.set_layer(self.layer)

        # Bypass contactor (C) - rectangle on the right
        cont_x = x + 16
        cont_y = y + h / 2 - 5
        cont_w = 10
        cont_h = 10
        backend.add_lwpolyline(
            [
                (cont_x, cont_y),
                (cont_x + cont_w, cont_y),
                (cont_x + cont_w, cont_y + cont_h),
                (cont_x, cont_y + cont_h),
            ],
            close=True,
        )

        # "C" text in contactor
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("C", insert=(cont_x + 3, cont_y + cont_h / 2 + 2.5), char_height=4)

        backend.set_layer(self.layer)

        # Connection lines between timer and contactor
        # Timer bottom to split point
        split_y = y + h / 2
        backend.add_line((timer_cx, timer_cy - timer_r), (timer_cx, split_y + 3))

        # Branch to contactor top
        backend.add_line((timer_cx, split_y + 3), (cont_x, cont_y + cont_h))
        # Branch to contactor bottom
        backend.add_line((timer_cx, split_y - 3), (cont_x, cont_y))

        # Main conductor continues down
        backend.add_line((timer_cx, split_y - 3), (timer_cx, y))

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((timer_cx, timer_cy + timer_r), (timer_cx, y + h + 5))
        backend.add_line((timer_cx, y), (timer_cx, y - 5))
