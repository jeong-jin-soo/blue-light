"""
Switch symbols: Isolator, IsolatorForMachine, DoublePoleSwitch,
                ATS (Automatic Transfer Switch), BIConnector.
Scaled for professional A3 engineering drawings.

Reference: Basic symbol for SLD.pdf (Singapore SLD standard).
"""

from __future__ import annotations

import math
from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class Isolator(BaseSymbol):
    """
    Isolator/Disconnect switch for DB.
    Two contact circles with a diagonal blade between them.
    Reference: "ISOLATOR FOR DB" in Basic symbol for SLD.pdf
    """

    name: str = "ISOLATOR"
    width: float = 12
    height: float = 18
    contact_radius: float = 1.2
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "blade": 0.7,
        "contact": 0.7,
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
        cx = x + self.width / 2
        cr = self.contact_radius

        backend.set_layer(self.layer)

        # Bottom contact circle
        bottom_cy = y + 4
        backend.add_circle((cx, bottom_cy), radius=cr)
        # Connection line stops at bottom edge of bottom contact
        backend.add_line((cx, y), (cx, bottom_cy - cr))

        # Top contact circle
        top_cy = y + 14
        backend.add_circle((cx, top_cy), radius=cr)
        # Connection line starts at top edge of top contact
        backend.add_line((cx, top_cy + cr), (cx, y + self.height))

        # Diagonal blade (from edge of bottom contact toward top-right)
        blade_ex, blade_ey = cx + 5, top_cy
        dx = blade_ex - cx
        dy = blade_ey - bottom_cy
        blade_len = math.sqrt(dx * dx + dy * dy)
        ux, uy = dx / blade_len, dy / blade_len
        # Start from top-right edge of bottom contact circle
        backend.add_line(
            (cx + cr * ux, bottom_cy + cr * uy),
            (blade_ex, blade_ey),
        )

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + self.height), (cx, y + self.height + 5))
        backend.add_line((cx, y), (cx, y - 5))


class IsolatorForMachine(BaseSymbol):
    """
    Isolator for Machine -- rectangle with internal diagonal blade.
    Reference: "ISOLATOR FOR MACHINE" in Basic symbol for SLD.pdf
    A rectangular enclosure with a diagonal switch blade inside.
    """

    name: str = "ISOLATOR_MACHINE"
    width: float = 14
    height: float = 18
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "enclosure": 0.7,
        "blade": 0.7,
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

        backend.set_layer(self.layer)

        # Rectangle enclosure
        rect_y_bottom = y + 3
        rect_y_top = y + h - 3
        rect_x_left = x + 2
        rect_x_right = x + w - 2
        backend.add_lwpolyline(
            [
                (rect_x_left, rect_y_bottom),
                (rect_x_right, rect_y_bottom),
                (rect_x_right, rect_y_top),
                (rect_x_left, rect_y_top),
            ],
            close=True,
        )

        # Internal diagonal blade (bottom-center to top-right)
        backend.add_line((cx, rect_y_bottom), (cx + 4, rect_y_top))

        # Vertical connections through enclosure
        backend.add_line((cx, y), (cx, rect_y_bottom))
        backend.add_line((cx, rect_y_top), (cx, y + h))

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))


class DoublePoleSwitch(BaseSymbol):
    """
    Double Pole Switch -- two parallel switch blades.
    Reference: "DOUBLE POLE SWITCH" in Basic symbol for SLD.pdf
    Two diagonal lines representing two ganged switch blades,
    with a dashed mechanical coupling line between them.
    """

    name: str = "DOUBLE_POLE_SWITCH"
    width: float = 16
    height: float = 18
    contact_radius: float = 1.0
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "blade": 0.7,
        "coupling": 0.35,
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
        cr = self.contact_radius

        backend.set_layer(self.layer)

        # Left blade (switch pole 1)
        blade1_x = cx - 3
        bottom_y = y + 3
        top_y = y + h - 3

        # Blade direction (same for both poles)
        blade_dx, blade_dy = 4.0, top_y - bottom_y
        blade_len = math.sqrt(blade_dx * blade_dx + blade_dy * blade_dy)
        bux, buy = blade_dx / blade_len, blade_dy / blade_len

        # Bottom contact points (fixed)
        backend.add_circle((blade1_x, bottom_y), radius=cr)
        # Top contact points (movable, slightly offset)
        backend.add_circle((blade1_x, top_y), radius=cr)
        # Diagonal blade 1 (starts from edge of bottom contact)
        backend.add_line(
            (blade1_x + cr * bux, bottom_y + cr * buy),
            (blade1_x + 4, top_y),
        )

        # Right blade (switch pole 2)
        blade2_x = cx + 3
        backend.add_circle((blade2_x, bottom_y), radius=cr)
        backend.add_circle((blade2_x, top_y), radius=cr)
        # Diagonal blade 2 (starts from edge of bottom contact)
        backend.add_line(
            (blade2_x + cr * bux, bottom_y + cr * buy),
            (blade2_x + 4, top_y),
        )

        # Mechanical coupling (dashed horizontal line between blades at midpoint)
        mid_y = y + h / 2
        # Draw dashed line as short segments
        dash_len = 1.0
        gap_len = 1.0
        seg_x = blade1_x + 2
        end_x = blade2_x + 2
        while seg_x < end_x:
            seg_end = min(seg_x + dash_len, end_x)
            backend.add_line((seg_x, mid_y), (seg_end, mid_y))
            seg_x = seg_end + gap_len

        # Vertical connections (center conductor)
        backend.add_line((cx, y), (cx, bottom_y))
        backend.add_line((cx, top_y), (cx, y + h))

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))


class BIConnector(BaseSymbol):
    """
    Bus Isolator / BI Connector.
    Used to connect busbar sections together in multi-DB installations.
    Drawn as two opposing arrowheads (triangles pointing at each other).
    Reference: "BI CONNECTOR" in Basic symbol for SLD.pdf
    """

    name: str = "BI_CONNECTOR"
    width: float = 16
    height: float = 10
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "arrowhead": 0.7,
        "bar": 0.7,
        "connection": 0.5,
    }

    def __init__(self):
        cy = self.height / 2
        cx = self.width / 2
        self.pins = {
            "left": (0, cy),
            "right": (self.width, cy),
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }
        self.anchors = {
            "label_below": (cx, -3),
            "label_above": (cx, self.height + 3),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float,
             crossbar_extend: float = 0) -> None:
        """Draw BI Connector symbol.

        Args:
            crossbar_extend: if > 0, draw an extended horizontal crossbar
                through the center (right-biased), matching reference DWG style
                where a busbar-like line passes through the BI connector.
        """
        w, h = self.width, self.height
        cy = y + h / 2

        backend.set_layer(self.layer)

        # Left arrowhead (pointing right)
        backend.add_lwpolyline(
            [
                (x + 1, cy + 3),
                (x + 6, cy),
                (x + 1, cy - 3),
            ],
            close=True,
        )

        # Right arrowhead (pointing left)
        backend.add_lwpolyline(
            [
                (x + w - 1, cy + 3),
                (x + w - 6, cy),
                (x + w - 1, cy - 3),
            ],
            close=True,
        )

        # Connecting bar between arrowheads
        backend.add_line((x + 6, cy), (x + w - 6, cy))

        # Extended crossbar through center (reference: long horizontal line
        # through BI connector, slightly right-biased)
        if crossbar_extend > 0:
            bar_left = x - 3                       # slight left extension
            bar_right = x + w + crossbar_extend    # right-biased extension
            backend.add_line((bar_left, cy), (x + 1, cy))        # left extension
            backend.add_line((x + w - 1, cy), (bar_right, cy))   # right extension

        # Connection stubs (left and right for horizontal use)
        backend.set_layer("SLD_CONNECTIONS")
        if crossbar_extend == 0:
            # Standard stubs only when no crossbar (avoid doubling)
            backend.add_line((x, cy), (x + 1, cy))
            backend.add_line((x + w - 1, cy), (x + w, cy))

        # Vertical stubs (for optional vertical use)
        cx = x + w / 2
        backend.add_line((cx, y + h), (cx, y + h + 5))
        backend.add_line((cx, y), (cx, y - 5))


class ATS(BaseSymbol):
    """
    Automatic Transfer Switch.
    Two incoming lines converging to one output with a switch symbol.
    """

    name: str = "ATS"
    width: float = 30
    height: float = 20
    layer: str = "SLD_SYMBOLS"

    lineweights: dict[str, float] = {
        "enclosure": 0.7,
        "text": 0.35,
        "connection": 0.5,
    }

    def __init__(self):
        self.pins = {
            "input_1": (8, self.height + 5),
            "input_2": (22, self.height + 5),
            "output": (15, -5),
        }
        self.anchors = {
            "label_right": (self.width + 3, self.height / 2 + 2),
            "label_above": (15, self.height + 8),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        backend.set_layer(self.layer)

        # Rectangle enclosure
        backend.add_lwpolyline(
            [
                (x, y),
                (x + self.width, y),
                (x + self.width, y + self.height),
                (x, y + self.height),
            ],
            close=True,
        )

        # ATS label
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("ATS", insert=(x + 10, y + 14), char_height=5)

        # Two input stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((x + 8, y + self.height), (x + 8, y + self.height + 5))
        backend.add_line((x + 22, y + self.height), (x + 22, y + self.height + 5))

        # One output stub
        backend.add_line((x + 15, y), (x + 15, y - 5))
