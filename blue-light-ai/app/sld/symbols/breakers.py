"""
Circuit breaker symbols: ACB, MCCB, MCB, RCCB/ELCB.

IEC 60617 single-line diagram representation:
- Arc (bump) with contact arm (standard for SLD single-line notation)
- ACB: Larger arc + double-contact indicator (horizontal bar)
- MCCB: Standard arc (14x20mm bounding box)
- MCB: Smaller arc (10x16mm bounding box)
- RCCB/ELCB: Arc + toroid ring (residual current sensing coil)
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
    Generic circuit breaker symbol (arc + two contacts).
    IEC 60617 single-line diagram standard representation.
    Singapore SLD convention: RIGHT-facing arc on conductor.

    Visual (matches real SLD drawings):
        ┃  <- top stub (5mm)
        │  <- top connection line
        ○  <- top contact circle (fixed contact, 90° point)
        ╮
         )  <- arc (semicircle, bulging RIGHT)
        ╯
        ○  <- bottom contact circle (moving contact, 270° point)
        │
        ┃  <- bottom stub (5mm)
    """

    width: float = 14
    height: float = 20
    arc_radius: float = 4
    contact_radius: float = 1.0
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "arc": 0.7,
        "contact_circle": 0.7,
        "connection": 0.5,
        "stub": 0.5,
    }

    def __init__(self, breaker_type: str = "MCCB"):
        self.breaker_type = breaker_type
        self.name = f"CB_{breaker_type}"
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }
        # Text anchor points (relative to symbol origin)
        self.anchors = {
            "label_right": (self.width / 2 + self.arc_radius + 3, self.height / 2 + 4),
            "rating_below": (self.width / 2, -8),
            "label_above": (self.width / 2, self.height + 8),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height
        cx = x + w / 2
        ar = self.arc_radius
        cr = self.contact_radius

        backend.set_layer(self.layer)

        # Arc center at vertical midpoint of symbol
        arc_center_y = y + h / 2

        # -- Contact circles (IEC 60617: two contacts on conductor) --
        # Lines must NOT penetrate circles (real SLD convention).
        # Bottom contact (270° point of arc)
        contact_bottom_y = arc_center_y - ar
        backend.add_circle((cx, contact_bottom_y), radius=cr)

        # Top contact (90° point of arc)
        contact_top_y = arc_center_y + ar
        backend.add_circle((cx, contact_top_y), radius=cr)

        # -- Arc (RIGHT-facing semicircle: 270 -> 0 -> 90) --
        # Radius reduced by contact_radius so arc stops at inner circle edges
        backend.add_arc(
            center=(cx, arc_center_y),
            radius=ar - cr,
            start_angle=270,
            end_angle=90,
        )

        # -- Connection stubs (from outer edge of contacts outward) --
        # Per LEW reference: NO line between contacts — only arc + contacts.
        # Stubs extend from contact outer edges to pin positions.
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, contact_top_y + cr), (cx, y + h + 5))
        backend.add_line((cx, y - 5), (cx, contact_bottom_y - cr))


class ACB(CircuitBreaker):
    """
    Air Circuit Breaker (for >630A).
    Distinctive: larger arc + double-contact indicator (horizontal bar through center)
    per IEC 60617 distinction for withdrawable/air-break type.
    """

    width: float = 16
    height: float = 22
    arc_radius: float = 5

    def __init__(self):
        super().__init__("ACB")
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }
        self.anchors = {
            "label_right": (self.width / 2 + self.arc_radius + 4, self.height / 2 + 4),
            "rating_below": (self.width / 2, -8),
            "label_above": (self.width / 2, self.height + 8),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        super().draw(backend, x, y)

        w, h = self.width, self.height
        cx = x + w / 2

        # ACB distinctive: horizontal bar through center (double-contact indicator)
        backend.set_layer(self.layer)
        mid_y = y + h / 2
        backend.add_line((cx - self.arc_radius - 2, mid_y), (cx + self.arc_radius + 2, mid_y))


class MCCB(CircuitBreaker):
    """Moulded Case Circuit Breaker (100A-630A)."""

    def __init__(self):
        super().__init__("MCCB")


class MCB(CircuitBreaker):
    """Miniature Circuit Breaker (<100A)."""

    width: float = 10
    height: float = 16
    arc_radius: float = 3

    def __init__(self):
        super().__init__("MCB")
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }
        self.anchors = {
            "label_right": (self.width / 2 + self.arc_radius + 3, self.height / 2 + 4),
            "rating_below": (self.width / 2, -8),
            "label_above": (self.width / 2, self.height + 8),
        }


class RCCB(BaseSymbol):
    """
    Residual Current Circuit Breaker.
    Arc + contact arm (same as MCB/MCCB) plus a toroid ring
    representing the residual current sensing coil.
    Singapore SLD convention: RIGHT-facing arc, toroid to the RIGHT.
    """

    name: str = "CB_RCCB"
    width: float = 14
    height: float = 20
    arc_radius: float = 4
    contact_radius: float = 1.0
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "arc": 0.7,
        "toroid": 0.7,
        "connection": 0.5,
    }

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }
        self.anchors = {
            "label_right": (cx + self.arc_radius + 10, self.height / 2 + 4),
            "rating_below": (cx, -8),
            "label_above": (cx, self.height + 8),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height
        cx = x + w / 2
        ar = self.arc_radius
        cr = self.contact_radius

        backend.set_layer(self.layer)

        # Arc center at vertical midpoint
        arc_center_y = y + h / 2

        # -- Contact circles (lines must NOT penetrate) --
        # Bottom contact (270° point)
        contact_bottom_y = arc_center_y - ar
        backend.add_circle((cx, contact_bottom_y), radius=cr)

        # Top contact (90° point)
        contact_top_y = arc_center_y + ar
        backend.add_circle((cx, contact_top_y), radius=cr)

        # -- Arc (RIGHT-facing semicircle: 270 -> 0 -> 90) --
        # Radius reduced by contact_radius so arc stops at inner circle edges
        backend.add_arc(
            center=(cx, arc_center_y),
            radius=ar - cr,
            start_angle=270,
            end_angle=90,
        )

        # -- Top connection (from outer edge of top contact) --
        backend.add_line((cx, contact_top_y + cr), (cx, y + h))

        # -- Bottom connection (to outer edge of bottom contact) --
        backend.add_line((cx, y), (cx, contact_bottom_y - cr))

        # -- RCD sensing element (ㅓ shape: horizontal line + vertical bar) --
        # Matches real SLD practice (not a circle)
        bar_x = cx + ar + 4  # Position of vertical bar
        bar_half = 3  # Half-height of vertical bar

        # Horizontal line from arc's rightmost point (0°) to vertical bar
        backend.add_line((cx + ar - cr, arc_center_y), (bar_x, arc_center_y))

        # Vertical bar (residual current sensing element)
        backend.add_line((bar_x, arc_center_y - bar_half), (bar_x, arc_center_y + bar_half))

        # -- Connection stubs --
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))


class ELCB(RCCB):
    """
    Earth Leakage Circuit Breaker.
    Same visual symbol as RCCB per Singapore SLD convention.
    In real SLD practice, RCCB and ELCB use identical symbols —
    differentiated only by text label (e.g., "63A RCCB" vs "13A ELCB").
    Reference: "RCCB / ELCB" single entry in Basic symbol for SLD.pdf.
    """

    name: str = "CB_ELCB"
