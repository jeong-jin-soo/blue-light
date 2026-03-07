"""
Calibrated electrical symbols matching real LEW SLD proportions.

Dimensions extracted from 26 DWG files via libredwg (dwg2dxf) + ezdxf analysis.
Block definitions (MCCB, RCCB) and inline entities (MCB) used for exact proportions.

Key DWG findings vs previous PDF-based calibration:
- MCB arc: 151.4° sweep (was 180° semicircle), start=283.8°, end=75.2°
- MCCB arc: 125.8° sweep (from LWPOLYLINE bulge=0.611 in block definitions)
- Contact radius: ~0.20 × arc_radius (was ~0.48) — much smaller contacts
- RCCB RCD bar: h_len=0.43×arc_r, v_len=0.78×arc_r (from 18 block definitions)
- KWH meter: rectangle w/h ratio = 2.0 (from 31 polyline rectangles)

All symbols implement the DrawingBackend protocol and can render to DXF/PDF/SVG.
"""

from __future__ import annotations

import json
import logging
import math
from pathlib import Path
from typing import TYPE_CHECKING

from app.sld.base_symbol import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend

logger = logging.getLogger(__name__)

# Path to calibrated symbol dimensions
_SYMBOL_DATA_PATH = Path(__file__).parent.parent.parent / "data" / "templates" / "real_symbol_paths.json"

# Cached symbol data
_symbol_data: dict | None = None


def _load_symbol_data() -> dict:
    """Load symbol dimensions from JSON file."""
    global _symbol_data
    if _symbol_data is None:
        if not _SYMBOL_DATA_PATH.exists():
            raise FileNotFoundError(
                f"Symbol data file not found: {_SYMBOL_DATA_PATH} — "
                "ensure data/templates/real_symbol_paths.json is present"
            )
        with open(_SYMBOL_DATA_PATH) as f:
            _symbol_data = json.load(f)
    return _symbol_data


def get_symbol_dimensions(symbol_type: str) -> dict:
    """Get calibrated dimensions for a symbol type."""
    data = _load_symbol_data()
    if symbol_type not in data:
        raise ValueError(f"Unknown symbol type: {symbol_type}. Available: {[k for k in data if not k.startswith('_')]}")
    return data[symbol_type]


# ---------------------------------------------------------------------------
# Calibrated circuit breaker symbols (from DWG block/entity analysis)
# ---------------------------------------------------------------------------

class RealCircuitBreaker(BaseSymbol):
    """
    Circuit breaker symbol at real LEW SLD proportions.

    DWG analysis (26 files):
    - Arc is NOT a semicircle — MCB uses 151.4° sweep, MCCB uses 125.8°
    - Contact circles are small: ~0.20 × arc_radius
    - Arc and contacts are separate visual elements on the conductor
    """

    layer: str = "SLD_SYMBOLS"

    def __init__(self, breaker_type: str = "MCB"):
        self.breaker_type = breaker_type
        self.name = f"CB_{breaker_type}"

        dims = get_symbol_dimensions(breaker_type)
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._arc_r = dims["arc_radius_mm"]
        self._contact_r = dims["contact_radius_mm"]
        self._stub = dims["stub_mm"]
        # DWG-calibrated arc angles (not 270→90 semicircle!)
        self._arc_start = dims.get("arc_start_deg", 283.8)
        self._arc_end = dims.get("arc_end_deg", 75.2)

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + self._stub),
            "bottom": (cx, -self._stub),
        }
        self.anchors = {
            "label_right": (self.width / 2 + self._arc_r + 2, self.height / 2 + 2),
            "rating_below": (self.width / 2, -self._stub - 2),
            "label_above": (self.width / 2, self.height + self._stub + 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float,
             skip_trip_arrow: bool = False) -> None:
        w, h = self.width, self.height
        cx = x + w / 2
        ar = self._arc_r
        cr = self._contact_r

        backend.set_layer(self.layer)

        arc_center_y = y + h / 2

        # Contact circles (small, ~0.20 × arc_r — from DWG data)
        contact_bottom_y = arc_center_y - ar
        backend.add_circle((cx, contact_bottom_y), radius=cr)

        contact_top_y = arc_center_y + ar
        backend.add_circle((cx, contact_top_y), radius=cr)

        # Arc — DWG-calibrated angles (NOT 180° semicircle)
        # MCB: 283.8° → 75.2° (151.4° sweep, right-facing)
        # MCCB: 297.1° → 62.9° (125.8° sweep, tighter right-facing)
        backend.add_arc(
            center=(cx, arc_center_y),
            radius=ar,  # Full arc radius (not ar-cr like before)
            start_angle=self._arc_start,
            end_angle=self._arc_end,
        )

        # Trip mechanism arrow at arc midpoint (IEC 60617 — automatic release indicator)
        # Arrow points TOWARD the arc — arrowhead tip touches arc surface
        # Skipped for ditto circuits (chain arrow drawn by generator instead)
        if not skip_trip_arrow:
            sweep = (self._arc_end - self._arc_start) % 360
            mid_angle_deg = self._arc_start + sweep / 2
            mid_angle_rad = math.radians(mid_angle_deg)
            arc_mx = cx + ar * math.cos(mid_angle_rad)
            arc_my = arc_center_y + ar * math.sin(mid_angle_rad)
            dx = cx - arc_mx
            dy = arc_center_y - arc_my
            dist = math.sqrt(dx * dx + dy * dy)
            if dist > 0:
                dx /= dist
                dy /= dist
            arrow_shaft = 6.0
            start_x = arc_mx + dx * arrow_shaft
            start_y = arc_my + dy * arrow_shaft
            backend.add_line((start_x, start_y), (arc_mx, arc_my))
            head_len = 1.2
            px, py = -dy, dx
            backend.add_line(
                (arc_mx, arc_my),
                (arc_mx + dx * head_len + px * 0.6, arc_my + dy * head_len + py * 0.6),
            )
            backend.add_line(
                (arc_mx, arc_my),
                (arc_mx + dx * head_len - px * 0.6, arc_my + dy * head_len - py * 0.6),
            )

        # Connection lines (from contact outer edges to symbol bounds)
        backend.add_line((cx, contact_top_y + cr), (cx, y + h))
        backend.add_line((cx, y), (cx, contact_bottom_y - cr))

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))

    def draw_horizontal(self, backend: DrawingBackend, x: float, y: float,
                         skip_trip_arrow: bool = False) -> None:
        """Draw circuit breaker rotated 90 degrees — connection points at LEFT and RIGHT.

        For horizontal meter board layout. The arc opens upward instead of rightward.
        Left pin at (x - stub, cy), Right pin at (x + h_extent + stub, cy).
        """
        h_extent = self.height  # horizontal span (was vertical height)
        cy = y  # vertical center
        ar = self._arc_r
        cr = self._contact_r

        backend.set_layer(self.layer)

        arc_center_x = x + h_extent / 2

        # Contact circles (left and right of arc center)
        contact_left_x = arc_center_x - ar
        backend.add_circle((contact_left_x, cy), radius=cr)

        contact_right_x = arc_center_x + ar
        backend.add_circle((contact_right_x, cy), radius=cr)

        # Arc — rotated 90 degrees (subtract 90 from original angles)
        # Original: right-facing arc. Rotated: upward-facing arc
        rot_start = self._arc_start + 90
        rot_end = self._arc_end + 90
        backend.add_arc(
            center=(arc_center_x, cy),
            radius=ar,
            start_angle=rot_start,
            end_angle=rot_end,
        )

        # Trip mechanism arrow at arc midpoint (IEC 60617 — rotated 90°)
        # Skipped for ditto circuits (chain arrow drawn by generator instead)
        if not skip_trip_arrow:
            sweep = (rot_end - rot_start) % 360
            mid_angle_deg = rot_start + sweep / 2
            mid_angle_rad = math.radians(mid_angle_deg)
            arc_mx = arc_center_x + ar * math.cos(mid_angle_rad)
            arc_my = cy + ar * math.sin(mid_angle_rad)
            dx = arc_center_x - arc_mx
            dy = cy - arc_my
            dist = math.sqrt(dx * dx + dy * dy)
            if dist > 0:
                dx /= dist
                dy /= dist
            arrow_shaft = 6.0
            start_x = arc_mx + dx * arrow_shaft
            start_y = arc_my + dy * arrow_shaft
            backend.add_line((start_x, start_y), (arc_mx, arc_my))
            head_len = 1.2
            px, py = -dy, dx
            backend.add_line(
                (arc_mx, arc_my),
                (arc_mx + dx * head_len + px * 0.6, arc_my + dy * head_len + py * 0.6),
            )
            backend.add_line(
                (arc_mx, arc_my),
                (arc_mx + dx * head_len - px * 0.6, arc_my + dy * head_len - py * 0.6),
            )

        # Connection lines (from contact outer edges to symbol horizontal bounds)
        backend.add_line((contact_right_x + cr, cy), (x + h_extent, cy))
        backend.add_line((x, cy), (contact_left_x - cr, cy))

        # Connection stubs (horizontal)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((x + h_extent, cy), (x + h_extent + self._stub, cy))
        backend.add_line((x, cy), (x - self._stub, cy))


class RealMCB(RealCircuitBreaker):
    """Miniature Circuit Breaker (≤100A) at real proportions."""

    def __init__(self):
        super().__init__("MCB")


class RealMCCB(RealCircuitBreaker):
    """Moulded Case Circuit Breaker (125A-630A) at real proportions."""

    def __init__(self):
        super().__init__("MCCB")


class RealACB(RealCircuitBreaker):
    """Air Circuit Breaker (>630A) with horizontal crossbar."""

    def __init__(self):
        super().__init__("ACB")
        self._crossbar_extend = get_symbol_dimensions("ACB").get("crossbar_extend_mm", 1.0)

    def draw(self, backend: DrawingBackend, x: float, y: float,
             skip_trip_arrow: bool = False) -> None:
        super().draw(backend, x, y, skip_trip_arrow=skip_trip_arrow)
        # ACB distinctive: horizontal bar through center
        backend.set_layer(self.layer)
        cx = x + self.width / 2
        mid_y = y + self.height / 2
        ext = self._arc_r + self._crossbar_extend
        backend.add_line((cx - ext, mid_y), (cx + ext, mid_y))


class RealRCCB(BaseSymbol):
    """
    Residual Current Circuit Breaker at real proportions.

    DWG block analysis (18 identical blocks across files):
    - Same arc as MCCB (125.8° sweep)
    - RCD sensing bar: horizontal line (0.43×arc_r) + vertical bar (0.78×arc_r)
    """

    name: str = "CB_RCCB"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("RCCB")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._arc_r = dims["arc_radius_mm"]
        self._contact_r = dims["contact_radius_mm"]
        self._stub = dims["stub_mm"]
        self._arc_start = dims.get("arc_start_deg", 297.1)
        self._arc_end = dims.get("arc_end_deg", 62.9)
        # RCD bar proportions from DWG block definitions
        self._rcd_h_ratio = dims.get("rcd_h_ratio", 0.4298)
        self._rcd_v_ratio = dims.get("rcd_v_ratio", 0.7777)

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + self._stub),
            "bottom": (cx, -self._stub),
        }
        self.anchors = {
            "label_right": (cx + self._arc_r + self._rcd_h_ratio * self._arc_r + 4, self.height / 2 + 2),
            "rating_below": (cx, -self._stub - 2),
            "label_above": (cx, self.height + self._stub + 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height
        cx = x + w / 2
        ar = self._arc_r
        cr = self._contact_r

        backend.set_layer(self.layer)

        arc_center_y = y + h / 2

        # Contact circles (small, from DWG data)
        contact_bottom_y = arc_center_y - ar
        backend.add_circle((cx, contact_bottom_y), radius=cr)
        contact_top_y = arc_center_y + ar
        backend.add_circle((cx, contact_top_y), radius=cr)

        # Arc — DWG block definition angles (125.8° sweep)
        backend.add_arc(
            center=(cx, arc_center_y),
            radius=ar,  # Full arc radius
            start_angle=self._arc_start,
            end_angle=self._arc_end,
        )

        # Connection lines
        backend.add_line((cx, contact_top_y + cr), (cx, y + h))
        backend.add_line((cx, y), (cx, contact_bottom_y - cr))

        # RCD sensing element — from DWG block: horizontal line + vertical bar
        # Horizontal line extends from arc right edge to bar position
        rcd_h_len = ar * self._rcd_h_ratio
        rcd_v_len = ar * self._rcd_v_ratio
        # Compute arc rightmost point at 0° (= arc center x + arc_r * cos(0) - but
        # the arc doesn't reach 0°; use the effective right extent)
        # For the arc center at cx, the rightmost extent of the arc:
        arc_right_x = cx + ar  # approximate (the actual max-x depends on sweep)
        bar_x = arc_right_x + rcd_h_len

        backend.add_line((arc_right_x, arc_center_y), (bar_x, arc_center_y))
        backend.add_line((bar_x, arc_center_y - rcd_v_len / 2),
                         (bar_x, arc_center_y + rcd_v_len / 2))

        # Stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))


class RealELCB(RealRCCB):
    """Earth Leakage Circuit Breaker (same symbol as RCCB)."""

    name: str = "CB_ELCB"


class RealKwhMeter(BaseSymbol):
    """kWh Meter at real proportions (rectangle with KWH label).

    DWG analysis: 31 rectangles across files, consistent w/h ratio = 2.0.
    Real LEW SLDs use a rectangular box for kWh meters, not a circle.
    """

    name: str = "KWH_METER"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("KWH_METER")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._circle_r = dims["circle_radius_mm"]  # used for stub offset
        self._stub = dims["stub_mm"]
        # Rectangle dimensions — DWG-calibrated ratio of 2.0
        rect_ratio = dims.get("rect_ratio", 2.0)
        self._rect_h = self.height * 0.6  # ~6.0mm tall
        self._rect_w = self._rect_h * rect_ratio  # ~12.0mm wide (ratio 2:1)

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height / 2 + self._rect_h / 2 + self._stub),
            "bottom": (cx, self.height / 2 - self._rect_h / 2 - self._stub),
        }
        self.anchors = {
            "label_right": (self.width / 2 + self._rect_w / 2 + 2, self.height / 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        rw = self._rect_w
        rh = self._rect_h

        backend.set_layer(self.layer)
        # Draw rectangle (matching real LEW SLD kWh meter box)
        backend.add_lwpolyline([
            (cx - rw / 2, cy - rh / 2),
            (cx + rw / 2, cy - rh / 2),
            (cx + rw / 2, cy + rh / 2),
            (cx - rw / 2, cy + rh / 2),
        ], close=True)

        # "KWH" label inside (uppercase, matching real samples)
        backend.set_layer("SLD_ANNOTATIONS")
        label_size = min(rw * 0.35, 3.5)  # Scale text to fit box
        backend.add_mtext("KWH", insert=(cx - rw * 0.35, cy + label_size * 0.5), char_height=label_size)

        # Connection lines
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + rh / 2), (cx, cy + rh / 2 + self._stub))
        backend.add_line((cx, cy - rh / 2), (cx, cy - rh / 2 - self._stub))

    def draw_horizontal(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Draw KWH meter rotated 90 degrees — connection points at LEFT and RIGHT.

        For horizontal meter board layout. The rectangle is drawn as a HORIZONTAL box:
        wider than tall, matching reference LEW drawings.
        """
        # Horizontal orientation: wide rectangle (landscape)
        # rect_w (12.0) becomes horizontal width, rect_h (6.0) becomes vertical height
        hrw = self._rect_w  # horizontal rect width (wide dimension)
        hrh = self._rect_h  # horizontal rect height (narrow dimension)
        cx = x  # horizontal center
        cy = y  # vertical center

        backend.set_layer(self.layer)
        # Draw rectangle (rotated 90 degrees)
        backend.add_lwpolyline([
            (cx - hrw / 2, cy - hrh / 2),
            (cx + hrw / 2, cy - hrh / 2),
            (cx + hrw / 2, cy + hrh / 2),
            (cx - hrw / 2, cy + hrh / 2),
        ], close=True)

        # "KWH" label inside (uppercase, matching real samples)
        backend.set_layer("SLD_ANNOTATIONS")
        label_size = min(hrw * 0.35, 3.5)  # Scale text to fit box
        backend.add_mtext("KWH", insert=(cx - hrw * 0.35, cy + label_size * 0.5), char_height=label_size)

        # Connection lines (horizontal: left and right)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx + hrw / 2, cy), (cx + hrw / 2 + self._stub, cy))  # right
        backend.add_line((cx - hrw / 2, cy), (cx - hrw / 2 - self._stub, cy))  # left


class RealCT(BaseSymbol):
    """Current Transformer at real proportions (two concentric circles)."""

    name: str = "CT"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("CT")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._outer_r = dims["outer_radius_mm"]
        self._inner_r = dims["inner_radius_mm"]
        self._stub = dims["stub_mm"]

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height / 2 + self._outer_r + self._stub),
            "bottom": (cx, self.height / 2 - self._outer_r - self._stub),
        }
        self.anchors = {
            "label_right": (self.width / 2 + self._outer_r + 2, self.height / 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=self._outer_r)
        backend.add_circle((cx, cy), radius=self._inner_r)

        # Connection lines through outer circle
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + self._outer_r), (cx, cy + self._outer_r + self._stub))
        backend.add_line((cx, cy - self._outer_r), (cx, cy - self._outer_r - self._stub))


class RealIsolator(BaseSymbol):
    """Isolator / Disconnect Switch at real proportions.

    DWG block 'DP ISOL': Rectangle + L-shaped stem.
    """

    name: str = "ISOLATOR"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("ISOLATOR")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._blade = dims["blade_length_mm"]
        self._contact_r = dims["contact_radius_mm"]
        self._stub = dims["stub_mm"]

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + self._stub),
            "bottom": (cx, -self._stub),
        }
        self.anchors = {
            "label_right": (self.width + 2, self.height / 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        h = self.height

        backend.set_layer(self.layer)

        # Bottom fixed contact (circle)
        backend.add_circle((cx, y + 1), radius=self._contact_r)

        # Moving blade (angled line from bottom contact upward to the right)
        blade_top_x = cx + self._blade * 0.3
        blade_top_y = y + h - 1
        backend.add_line((cx, y + 1 + self._contact_r), (blade_top_x, blade_top_y))

        # Top fixed contact (short horizontal line = open gap indicator)
        backend.add_line((cx - 0.5, y + h - 1), (cx + 0.5, y + h - 1))

        # Stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))

    def draw_horizontal(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Draw isolator rotated 90 degrees — connection points at LEFT and RIGHT.

        For horizontal meter board layout. The symbol is drawn with:
        - Left pin at (x - stub, cy)
        - Right pin at (x + h_extent + stub, cy)
        where h_extent is the original height used as the horizontal span.
        """
        # In horizontal mode: original height becomes horizontal span, original width becomes vertical span
        h_extent = self.height  # horizontal span (was vertical height)
        cy = y  # vertical center line

        backend.set_layer(self.layer)

        # Left fixed contact (circle) — was bottom
        backend.add_circle((x + 1, cy), radius=self._contact_r)

        # Moving blade (angled line from left contact rightward and upward)
        blade_tip_x = x + h_extent - 1
        blade_tip_y = cy + self._blade * 0.3
        backend.add_line((x + 1 + self._contact_r, cy), (blade_tip_x, blade_tip_y))

        # Right fixed contact (short vertical line = open gap indicator) — was top
        backend.add_line((x + h_extent - 1, cy - 0.5), (x + h_extent - 1, cy + 0.5))

        # Stubs (horizontal)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((x + h_extent, cy), (x + h_extent + self._stub, cy))  # right stub
        backend.add_line((x, cy), (x - self._stub, cy))  # left stub


class RealEarth(BaseSymbol):
    """Earth/Ground symbol at real proportions."""

    name: str = "EARTH"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("EARTH")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height),
        }
        self.anchors = {
            "label_right": (self.width + 1, self.height / 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        w = self.width

        backend.set_layer(self.layer)

        # Vertical line from top
        backend.add_line((cx, y + self.height), (cx, y + self.height * 0.6))

        # Three horizontal lines (decreasing width)
        y1 = y + self.height * 0.6
        backend.add_line((cx - w / 2, y1), (cx + w / 2, y1))

        y2 = y + self.height * 0.35
        backend.add_line((cx - w / 3, y2), (cx + w / 3, y2))

        y3 = y + self.height * 0.1
        backend.add_line((cx - w / 6, y3), (cx + w / 6, y3))


class RealFuse(BaseSymbol):
    """Fuse symbol at real proportions."""

    name: str = "FUSE"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("FUSE")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._rect_w = dims["rect_width_mm"]
        self._rect_h = dims["rect_height_mm"]
        self._stub = dims["stub_mm"]

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + self._stub),
            "bottom": (cx, -self._stub),
        }
        self.anchors = {
            "label_right": (self.width + 2, self.height / 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        rect_y = y + (self.height - self._rect_h) / 2

        backend.set_layer(self.layer)
        backend.add_filled_rect(
            cx - self._rect_w / 2, rect_y,
            self._rect_w, self._rect_h,
        )

        # Stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + self.height), (cx, y + self.height + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))


# ---------------------------------------------------------------------------
# Symbol registry — maps type names to real-proportion symbol classes
# ---------------------------------------------------------------------------

REAL_SYMBOL_MAP: dict[str, type] = {
    "MCB": RealMCB,
    "MCCB": RealMCCB,
    "ACB": RealACB,
    "RCCB": RealRCCB,
    "ELCB": RealELCB,
    "KWH_METER": RealKwhMeter,
    "CT": RealCT,
    "ISOLATOR": RealIsolator,
    "EARTH": RealEarth,
    "FUSE": RealFuse,
}

# Register additional symbols from app.sld.symbols package
try:
    from app.sld.symbols.switches import BIConnector
    REAL_SYMBOL_MAP["BI_CONNECTOR"] = BIConnector
except ImportError:
    pass


def get_real_symbol(symbol_type: str) -> BaseSymbol:
    """Get a real-proportion symbol instance by type name."""
    if symbol_type in REAL_SYMBOL_MAP:
        return REAL_SYMBOL_MAP[symbol_type]()
    # Fallback: check CB_ prefix
    if symbol_type.startswith("CB_"):
        breaker_type = symbol_type[3:]
        if breaker_type in REAL_SYMBOL_MAP:
            return REAL_SYMBOL_MAP[breaker_type]()
    raise ValueError(f"Unknown real symbol type: {symbol_type}")
