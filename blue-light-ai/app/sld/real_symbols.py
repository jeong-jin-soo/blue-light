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
    """Current Transformer — two interlocking rings (chain-link style).

    Reference DWG: two small arcs on the conductor, NOT concentric circles.
    The arcs protrude to the right of the conductor and interlock vertically.
    """

    name: str = "CT"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("CT")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._ring_r = dims["ring_radius_mm"]
        self._ring_offset = dims["ring_offset_mm"]
        self._stub = dims["stub_mm"]

        cx = self.width / 2
        half_d = self._ring_offset / 2
        top_extent = half_d + self._ring_r
        self.pins = {
            "top": (cx, self.height / 2 + top_extent + self._stub),
            "bottom": (cx, self.height / 2 - top_extent - self._stub),
        }
        self.anchors = {
            "label_right": (self.width / 2 + self._ring_r + 2, self.height / 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = self._ring_r
        d = self._ring_offset / 2  # half offset from center

        # CT arcs are rendered via junction_arrows at the branch junction
        # points, so the symbol itself only draws connection stubs.
        backend.set_layer("SLD_CONNECTIONS")
        top_y = cy - d - r
        bottom_y = cy + d + r
        backend.add_line((cx, top_y), (cx, top_y - self._stub))
        backend.add_line((cx, bottom_y), (cx, bottom_y + self._stub))


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

    def draw(self, backend: DrawingBackend, x: float, y: float,
             enclosed: bool = False) -> None:
        cx = x + self.width / 2
        h = self.height
        cr = self._contact_r

        backend.set_layer(self.layer)

        # Contact separation matches MCCB (2 × arc_radius)
        contact_sep = 2 * get_symbol_dimensions("MCCB")["arc_radius_mm"]
        center_y = y + h / 2
        contact_top_y = center_y + contact_sep / 2
        contact_bottom_y = center_y - contact_sep / 2

        # Enclosure box — IEC symbol for enclosed isolator (standalone unit)
        # Used for landlord supply where isolator has its own housing
        if enclosed:
            pad = 1.5  # padding around switch symbol
            box_x = cx - self.width / 2 - pad
            box_y = y - pad
            box_w = self.width + pad * 2
            box_h = h + pad * 2
            backend.add_lwpolyline([
                (box_x, box_y),
                (box_x + box_w, box_y),
                (box_x + box_w, box_y + box_h),
                (box_x, box_y + box_h),
            ], close=True)

        # Top fixed contact (circle) — near busbar / DB box (high Y side)
        backend.add_circle((cx, contact_top_y), radius=cr)

        # Moving blade (angled line from top contact downward to the right)
        blade_offset = contact_sep * 0.5  # horizontal offset for disconnect angle
        backend.add_line(
            (cx, contact_top_y - cr),
            (cx + blade_offset, contact_bottom_y),
        )

        # Bottom fixed contact (circle) — near supply (low Y side)
        # IEC standard: both contacts of an isolator are shown as open circles
        backend.add_circle((cx, contact_bottom_y), radius=cr)

        # Connection lines (contact edges to symbol bounds — same as MCCB)
        backend.add_line((cx, contact_top_y + cr), (cx, y + h))
        backend.add_line((cx, y), (cx, contact_bottom_y - cr))

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
        h_extent = self.height  # horizontal span (was vertical height)
        cy = y  # vertical center line
        cr = self._contact_r

        backend.set_layer(self.layer)

        # Contact separation matches MCCB (2 × arc_radius)
        contact_sep = 2 * get_symbol_dimensions("MCCB")["arc_radius_mm"]
        center_x = x + h_extent / 2
        contact_left_x = center_x - contact_sep / 2
        contact_right_x = center_x + contact_sep / 2

        # Left fixed contact (circle) — was bottom
        backend.add_circle((contact_left_x, cy), radius=cr)

        # Moving blade (angled line from left contact rightward and upward)
        blade_offset = contact_sep * 0.5  # vertical offset for disconnect angle
        backend.add_line(
            (contact_left_x + cr, cy),
            (contact_right_x, cy + blade_offset),
        )

        # Right fixed contact (circle) — IEC standard: both contacts as open circles
        backend.add_circle((contact_right_x, cy), radius=cr)

        # Connection lines (contact edges to symbol bounds — same as MCCB)
        backend.add_line((contact_right_x + cr, cy), (x + h_extent, cy))  # right
        backend.add_line((x, cy), (contact_left_x - cr, cy))  # left

        # Stubs (horizontal)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((x + h_extent, cy), (x + h_extent + self._stub, cy))  # right stub
        backend.add_line((x, cy), (x - self._stub, cy))  # left stub


class RealBIConnector(BaseSymbol):
    """BI Connector at real proportions (rectangular block on conductor).

    DXF reference: 12.9×11.9mm rectangular block.
    Vertical mode: sits on main conductor, stubs top/bottom.
    """

    name: str = "BI_CONNECTOR"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("BI_CONNECTOR")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
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

        backend.set_layer(self.layer)
        # Rectangular block
        backend.add_lwpolyline(
            [(x, y), (x + self.width, y),
             (x + self.width, y + self.height), (x, y + self.height)],
            close=True,
        )

        # Connection stubs (vertical)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + self.height), (cx, y + self.height + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))


class RealMeter(BaseSymbol):
    """Ammeter / Voltmeter at real proportions (circle with letter).

    DXF reference: r=3.81mm circle with "A" or "V" centered.
    Parameterized by letter to share implementation.
    Horizontal mode: used on branches with left/right connections.
    """

    layer: str = "SLD_SYMBOLS"

    def __init__(self, letter: str = "A"):
        self._letter = letter
        self.name = "AMMETER" if letter == "A" else "VOLTMETER"
        key = self.name
        dims = get_symbol_dimensions(key)
        self._radius = dims["radius_mm"]
        self._stub = dims["stub_mm"]
        diameter = self._radius * 2
        self.width = diameter
        self.height = diameter

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height / 2 + self._radius + self._stub),
            "bottom": (cx, self.height / 2 - self._radius - self._stub),
        }
        self.anchors = {
            "label_right": (self.width + 2, self.height / 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Draw vertically — connections top/bottom."""
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = self._radius

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=r)

        # Letter centered inside circle
        backend.set_layer("SLD_ANNOTATIONS")
        ch = r * 1.2  # char height proportional to radius
        backend.add_mtext(self._letter, insert=(cx - ch * 0.3, cy + ch * 0.4), char_height=ch)

        # Connection stubs (vertical)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + r), (cx, cy + r + self._stub))
        backend.add_line((cx, cy - r), (cx, cy - r - self._stub))

    def draw_horizontal(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Draw horizontally — connections left/right.

        x: leftmost point of the horizontal extent (circle left edge - stub)
        y: vertical center line of the branch.
        """
        r = self._radius
        # Center of circle: stub + radius from left edge
        cx = x + self._stub + r
        cy = y

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=r)

        # Letter centered inside circle
        backend.set_layer("SLD_ANNOTATIONS")
        ch = r * 1.2
        backend.add_mtext(self._letter, insert=(cx - ch * 0.3, cy + ch * 0.4), char_height=ch)

        # Connection stubs (horizontal)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx - r, cy), (cx - r - self._stub, cy))  # left stub
        backend.add_line((cx + r, cy), (cx + r + self._stub, cy))  # right stub


class RealSelectorSwitch(BaseSymbol):
    """Selector Switch (ASS/VSS) at real proportions.

    DXF reference: two opposing arcs, r=0.85mm, sweep 304°→56°.
    IEC 60617 symbol for ammeter/voltmeter selector switch.
    Horizontal mode: used on branches with left/right connections.
    """

    name: str = "SELECTOR_SWITCH"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("SELECTOR_SWITCH")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._arc_r = dims["arc_radius_mm"]
        self._arc_start = dims["arc_start_deg"]
        self._arc_end = dims["arc_end_deg"]
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
        """Draw vertically — connections top/bottom."""
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = self._arc_r
        gap = 1.5  # gap between the two arc centers

        backend.set_layer(self.layer)

        # Top arc (opening upward)
        backend.add_arc(
            center=(cx, cy + gap / 2),
            radius=r,
            start_angle=self._arc_start,
            end_angle=self._arc_end,
        )
        # Bottom arc (opening downward — mirrored)
        backend.add_arc(
            center=(cx, cy - gap / 2),
            radius=r,
            start_angle=(self._arc_start + 180) % 360,
            end_angle=(self._arc_end + 180) % 360,
        )

        # Vertical connection lines through the switch
        backend.add_line((cx, y), (cx, cy - gap / 2 - r))
        backend.add_line((cx, cy + gap / 2 + r), (cx, y + self.height))

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + self.height), (cx, y + self.height + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))

    def draw_horizontal(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Draw horizontally — connections left/right.

        x: leftmost point of the horizontal extent
        y: vertical center line of the branch.
        """
        h_extent = self.height  # use height as horizontal span
        cx = x + h_extent / 2
        cy = y
        r = self._arc_r
        gap = 1.5

        backend.set_layer(self.layer)

        # Left arc (opening left)
        backend.add_arc(
            center=(cx - gap / 2, cy),
            radius=r,
            start_angle=self._arc_start + 90,
            end_angle=self._arc_end + 90,
        )
        # Right arc (opening right — mirrored)
        backend.add_arc(
            center=(cx + gap / 2, cy),
            radius=r,
            start_angle=(self._arc_start + 90 + 180) % 360,
            end_angle=(self._arc_end + 90 + 180) % 360,
        )

        # Horizontal connection lines through the switch
        backend.add_line((x, cy), (cx - gap / 2 - r, cy))
        backend.add_line((cx + gap / 2 + r, cy), (x + h_extent, cy))

        # Connection stubs (horizontal)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((x + h_extent, cy), (x + h_extent + self._stub, cy))
        backend.add_line((x, cy), (x - self._stub, cy))


class RealELR(BaseSymbol):
    """Earth Leakage Relay at real proportions (rectangular device block).

    DXF reference: 20.1×11.1mm rectangle with "ELR" text.
    Horizontal mode: used on branch from protection CT.
    """

    name: str = "ELR"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("ELR")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
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
        """Draw vertically — connections top/bottom."""
        cx = x + self.width / 2

        backend.set_layer(self.layer)
        backend.add_lwpolyline(
            [(x, y), (x + self.width, y),
             (x + self.width, y + self.height), (x, y + self.height)],
            close=True,
        )

        # "ELR" text centered (scaled to box size)
        backend.set_layer("SLD_ANNOTATIONS")
        ch = min(self.height * 0.35, 2.5)
        backend.add_mtext("ELR", insert=(cx - ch * 0.9, y + self.height / 2 + ch * 0.4), char_height=ch)

        # Connection stubs (vertical)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + self.height), (cx, y + self.height + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))

    def draw_horizontal(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Draw horizontally — connections left/right.

        x: leftmost point of the horizontal extent
        y: vertical center line of the branch.
        """
        h_extent = self.width   # use width as horizontal span
        v_extent = self.height  # use height as vertical extent
        cy = y

        backend.set_layer(self.layer)
        backend.add_lwpolyline(
            [(x, cy - v_extent / 2), (x + h_extent, cy - v_extent / 2),
             (x + h_extent, cy + v_extent / 2), (x, cy + v_extent / 2)],
            close=True,
        )

        # "ELR" text centered (scaled to box size)
        backend.set_layer("SLD_ANNOTATIONS")
        ch = min(v_extent * 0.35, 2.5)
        mid_x = x + h_extent / 2
        backend.add_mtext("ELR", insert=(mid_x - ch * 0.9, cy + ch * 0.4), char_height=ch)

        # Connection stubs (horizontal)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((x + h_extent, cy), (x + h_extent + self._stub, cy))
        backend.add_line((x, cy), (x - self._stub, cy))


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


class RealPotentialFuse(BaseSymbol):
    """Potential fuse / 2A fuse link (○×○) on conductor for CT metering.

    DXF '2A FUSE' block pattern: two circles connected by diagonal cross lines.
    Previous style was ○-○-○ (3 circles); updated to match reference DWG files.
    """

    name: str = "POTENTIAL_FUSE"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("POTENTIAL_FUSE")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._circle_r = dims["circle_radius_mm"]
        self._stub = dims["stub_mm"]
        self._cross_gap = dims.get("cross_gap_mm", 3.0)

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
        mid_y = y + self.height / 2

        # Two circles: top and bottom, separated by cross_gap
        top_cy = mid_y + self._cross_gap / 2 + self._circle_r
        bot_cy = mid_y - self._cross_gap / 2 - self._circle_r

        backend.set_layer(self.layer)
        backend.add_circle((cx, top_cy), radius=self._circle_r)
        backend.add_circle((cx, bot_cy), radius=self._circle_r)

        # Diagonal cross lines between circles (○×○ pattern)
        cross_top = mid_y + self._cross_gap / 2
        cross_bot = mid_y - self._cross_gap / 2
        cross_hw = self._circle_r * 0.8  # half-width of cross
        backend.add_line((cx - cross_hw, cross_top), (cx + cross_hw, cross_bot))
        backend.add_line((cx + cross_hw, cross_top), (cx - cross_hw, cross_bot))

        # Stubs (connections)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + self.height), (cx, y + self.height + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))

    def draw_horizontal(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Draw horizontally — connections left/right.

        x: leftmost point of the horizontal extent
        y: vertical center line of the branch.

        DXF reference: 2A FUSE is always a horizontal branch element
        (never on the vertical spine). The fuse branches RIGHT from
        a T-junction on the spine, protecting voltage sensing circuits.
        Ref: 150A/400A TPN DWGs — FUSE INSERT at X≈23494 (right of spine).
        """
        # In horizontal orientation, the "height" dimension (8mm) becomes
        # the horizontal extent, and "width" (4mm) becomes the vertical extent.
        h_extent = self.height
        mid_x = x + h_extent / 2
        cy = y

        # Two circles: left and right, separated by cross_gap
        left_cx = mid_x - self._cross_gap / 2 - self._circle_r
        right_cx = mid_x + self._cross_gap / 2 + self._circle_r

        backend.set_layer(self.layer)
        backend.add_circle((left_cx, cy), radius=self._circle_r)
        backend.add_circle((right_cx, cy), radius=self._circle_r)

        # Diagonal cross lines between circles (○×○ pattern)
        cross_left = mid_x - self._cross_gap / 2
        cross_right = mid_x + self._cross_gap / 2
        cross_hh = self._circle_r * 0.8  # half-height of cross
        backend.add_line((cross_left, cy - cross_hh), (cross_right, cy + cross_hh))
        backend.add_line((cross_left, cy + cross_hh), (cross_right, cy - cross_hh))

        # Internal lines connecting boundaries to circle edges
        # (fill gaps between component extent and circle perimeters)
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((x, cy), (left_cx - self._circle_r, cy))
        backend.add_line((right_cx + self._circle_r, cy), (x + h_extent, cy))

        # Stubs (horizontal connections to adjacent components)
        backend.add_line((x + h_extent, cy), (x + h_extent + self._stub, cy))
        backend.add_line((x, cy), (x - self._stub, cy))


class RealIndicatorLights(BaseSymbol):
    """3-phase indicator lights for CT metering fuse branches.

    Each indicator light is a circle with 4 radial lines extending outward
    at 45° angles (NE/NW/SE/SW), representing a lamp/light symbol.
    Adjacent circles are connected by horizontal lines.
    Always placed horizontally after the 2A potential fuse on a branch.
    Ref: 150A/400A TPN DWGs — 'LED IND LTG' block at X≈24250.
    """

    name: str = "INDICATOR_LIGHTS"
    layer: str = "SLD_SYMBOLS"

    # Length of each radial ray extending outward from the circle perimeter
    _ray_len: float = 0.8

    def __init__(self):
        dims = get_symbol_dimensions("INDICATOR_LIGHTS")
        self.width = dims["width_mm"]       # 13.2mm total horizontal extent
        self.height = dims["height_mm"]     # 4mm vertical extent
        self._circle_r = dims["circle_radius_mm"]   # 1.2mm
        self._count = dims.get("circle_count", 3)
        self._spacing = dims.get("circle_spacing_mm", 3.0)

        # Pins for connection (horizontal orientation only)
        self.pins = {
            "left": (0, self.height / 2),
            "right": (self.width, self.height / 2),
        }
        self.anchors = {}

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Vertical draw — delegates to horizontal (indicator lights are always horizontal)."""
        self.draw_horizontal(backend, x, y + self.height / 2)

    def draw_horizontal(self, backend: DrawingBackend, x: float, y: float) -> None:
        """Draw indicator lights horizontally with radial rays and inter-connections.

        Each light: circle with 4 diagonal lines extending outward (lamp symbol).
        Adjacent lights are connected by horizontal lines.

        x: leftmost point of the horizontal extent.
        y: vertical center line of the branch.
        """
        r = self._circle_r
        d = self._ray_len  # ray length beyond circle perimeter
        cos45 = 0.7071  # cos(45°) = sin(45°)

        backend.set_layer(self.layer)
        centers = []
        for i in range(self._count):
            cx = x + r + i * self._spacing
            centers.append(cx)

            # Circle
            backend.add_circle((cx, y), radius=r)

            # 4 radial rays at 45° angles (NE, NW, SE, SW)
            for dx_sign, dy_sign in [(1, 1), (-1, 1), (1, -1), (-1, -1)]:
                # Ray starts at circle perimeter, extends outward by d
                start_x = cx + dx_sign * r * cos45
                start_y = y + dy_sign * r * cos45
                end_x = cx + dx_sign * (r + d) * cos45
                end_y = y + dy_sign * (r + d) * cos45
                backend.add_line((start_x, start_y), (end_x, end_y))

        # Horizontal connections between adjacent circles
        backend.set_layer("SLD_CONNECTIONS")
        for i in range(len(centers) - 1):
            # Connect right edge of circle i to left edge of circle i+1
            backend.add_line(
                (centers[i] + r, y),
                (centers[i + 1] - r, y),
            )

        # Left connection stub (to fuse); no right stub per reference DWG
        backend.add_line((x, y), (x - 2.0, y))


# ---------------------------------------------------------------------------
# Symbol registry — maps type names to real-proportion symbol classes
# ---------------------------------------------------------------------------

REAL_SYMBOL_MAP: dict[str, type | object] = {
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
    "POTENTIAL_FUSE": RealPotentialFuse,
    "BI_CONNECTOR": RealBIConnector,
    "SELECTOR_SWITCH": RealSelectorSwitch,
    "ELR": RealELR,
    "INDICATOR_LIGHTS": RealIndicatorLights,
}

# Meter symbols are instances (parameterized by letter), not classes
_METER_INSTANCES: dict[str, BaseSymbol] = {
    "AMMETER": RealMeter("A"),
    "VOLTMETER": RealMeter("V"),
}


def get_real_symbol(symbol_type: str) -> BaseSymbol:
    """Get a real-proportion symbol instance by type name."""
    # Meter symbols are pre-built instances (parameterized by letter)
    if symbol_type in _METER_INSTANCES:
        return _METER_INSTANCES[symbol_type]
    if symbol_type in REAL_SYMBOL_MAP:
        return REAL_SYMBOL_MAP[symbol_type]()
    # Fallback: check CB_ prefix
    if symbol_type.startswith("CB_"):
        breaker_type = symbol_type[3:]
        if breaker_type in REAL_SYMBOL_MAP:
            return REAL_SYMBOL_MAP[breaker_type]()
    raise ValueError(f"Unknown real symbol type: {symbol_type}")
