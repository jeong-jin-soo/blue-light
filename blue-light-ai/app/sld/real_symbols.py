"""
Calibrated electrical symbols matching real LEW SLD proportions.

Dimensions from DXF block analysis of 26 real Singapore SLD templates,
cross-validated with PyMuPDF vector analysis of 73 SLD samples.
All symbols implement the DrawingBackend protocol and can render to DXF/PDF/SVG.

DXF Block Geometry (IEC 60617):
- Circuit breakers (MCB/MCCB/ACB): two terminal circles + blade line (offset from center)
- RCCB/ELCB: same as MCCB + horizontal arm + vertical T-bar (RCD sensing element)
- Blade offset extracted from DXF MCCB block: 0.97mm at 7.5mm height

Key dimensions (calibrated from real LEW SLDs):
- MCB: 3.6x6.5mm, blade_offset 0.84mm, contact_r 0.54mm
- MCCB: 4.2x7.5mm, blade_offset 0.97mm, contact_r 0.62mm
- ACB: 5.0x8.5mm, blade_offset 1.10mm, contact_r 0.70mm
- Stub length: 2.0mm (uniform)
"""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

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
# Calibrated circuit breaker symbols
# ---------------------------------------------------------------------------

class RealCircuitBreaker(BaseSymbol):
    """
    Circuit breaker symbol at real LEW SLD proportions.

    IEC 60617 style extracted from DXF blocks:
    - Two terminal circles (contacts) on the vertical conductor
    - Blade line offset to the right (moving contact indicator)
    - Connection stubs top and bottom

    DXF reference: MCCB block in slds-dxf/*.dxf — 2 CIRCLE + 1 LWPOLYLINE.
    """

    layer: str = "SLD_SYMBOLS"

    def __init__(self, breaker_type: str = "MCB"):
        self.breaker_type = breaker_type
        self.name = f"CB_{breaker_type}"

        dims = get_symbol_dimensions(breaker_type)
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._arc_r = dims["arc_radius_mm"]  # kept for pin/anchor spacing
        self._blade_offset = dims.get("blade_offset_mm", dims["arc_radius_mm"] * 0.6)
        self._contact_r = dims["contact_radius_mm"]
        self._stub = dims["stub_mm"]

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + self._stub),
            "bottom": (cx, -self._stub),
        }
        self.anchors = {
            "label_right": (self.width / 2 + self._blade_offset + 2, self.height / 2 + 2),
            "rating_below": (self.width / 2, -self._stub - 2),
            "label_above": (self.width / 2, self.height + self._stub + 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height
        cx = x + w / 2
        cr = self._contact_r
        blade_x = cx + self._blade_offset

        backend.set_layer(self.layer)

        # Contact circles — positioned symmetrically around vertical center
        # Spacing derived from DXF: contacts at ~10% and ~90% of symbol height
        contact_gap = h * 0.8  # distance between contact centers
        contact_bottom_y = y + (h - contact_gap) / 2
        contact_top_y = y + (h + contact_gap) / 2

        backend.add_circle((cx, contact_bottom_y), radius=cr)
        backend.add_circle((cx, contact_top_y), radius=cr)

        # Blade line — vertical line offset to the right (DXF: LWPOLYLINE)
        # Represents the moving contact / break mechanism
        backend.add_line((blade_x, contact_bottom_y), (blade_x, contact_top_y))

        # Connection lines (from contact outer edges to symbol bounds)
        backend.add_line((cx, contact_top_y + cr), (cx, y + h))
        backend.add_line((cx, y), (cx, contact_bottom_y - cr))

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))


class RealMCB(RealCircuitBreaker):
    """Miniature Circuit Breaker (<100A) at real proportions."""

    def __init__(self):
        super().__init__("MCB")


class RealMCCB(RealCircuitBreaker):
    """Moulded Case Circuit Breaker (100A-630A) at real proportions."""

    def __init__(self):
        super().__init__("MCCB")


class RealACB(RealCircuitBreaker):
    """Air Circuit Breaker (>630A) with horizontal crossbar."""

    def __init__(self):
        super().__init__("ACB")
        self._crossbar_extend = get_symbol_dimensions("ACB").get("crossbar_extend_mm", 1.0)

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        super().draw(backend, x, y)
        # ACB distinctive: horizontal bar through center
        backend.set_layer(self.layer)
        cx = x + self.width / 2
        mid_y = y + self.height / 2
        ext = self._blade_offset + self._crossbar_extend
        backend.add_line((cx - ext, mid_y), (cx + ext, mid_y))


class RealRCCB(BaseSymbol):
    """
    Residual Current Circuit Breaker at real proportions.

    DXF reference: RCCB block — same as MCCB (2 circles + blade) plus
    horizontal arm + vertical T-bar for RCD sensing element.
    """

    name: str = "CB_RCCB"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("RCCB")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._arc_r = dims["arc_radius_mm"]  # kept for spacing reference
        self._blade_offset = dims.get("blade_offset_mm", dims["arc_radius_mm"] * 0.6)
        self._contact_r = dims["contact_radius_mm"]
        self._stub = dims["stub_mm"]
        self._rcd_offset = dims.get("rcd_bar_offset_mm", 2.5)
        self._rcd_half = dims.get("rcd_bar_half_mm", 1.37)

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + self._stub),
            "bottom": (cx, -self._stub),
        }
        self.anchors = {
            "label_right": (cx + self._blade_offset + self._rcd_offset + 2, self.height / 2 + 2),
            "rating_below": (cx, -self._stub - 2),
            "label_above": (cx, self.height + self._stub + 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        w, h = self.width, self.height
        cx = x + w / 2
        cr = self._contact_r
        blade_x = cx + self._blade_offset

        backend.set_layer(self.layer)

        mid_y = y + h / 2

        # Contact circles — symmetrically spaced around center
        contact_gap = h * 0.8
        contact_bottom_y = y + (h - contact_gap) / 2
        contact_top_y = y + (h + contact_gap) / 2

        backend.add_circle((cx, contact_bottom_y), radius=cr)
        backend.add_circle((cx, contact_top_y), radius=cr)

        # Blade line (DXF: LWPOLYLINE offset to the right)
        backend.add_line((blade_x, contact_bottom_y), (blade_x, contact_top_y))

        # Connection lines
        backend.add_line((cx, contact_top_y + cr), (cx, y + h))
        backend.add_line((cx, y), (cx, contact_bottom_y - cr))

        # RCD sensing element (DXF: horizontal LINE + vertical LINE forming T-bar)
        bar_x = cx + self._blade_offset + self._rcd_offset
        backend.add_line((blade_x, mid_y), (bar_x, mid_y))
        backend.add_line((bar_x, mid_y - self._rcd_half), (bar_x, mid_y + self._rcd_half))

        # Stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + h), (cx, y + h + self._stub))
        backend.add_line((cx, y), (cx, y - self._stub))


class RealELCB(RealRCCB):
    """Earth Leakage Circuit Breaker (same symbol as RCCB)."""

    name: str = "CB_ELCB"


class RealKwhMeter(BaseSymbol):
    """kWh Meter at real proportions (circle with kWh label)."""

    name: str = "KWH_METER"
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        dims = get_symbol_dimensions("KWH_METER")
        self.width = dims["width_mm"]
        self.height = dims["height_mm"]
        self._circle_r = dims["circle_radius_mm"]
        self._stub = dims["stub_mm"]

        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height / 2 + self._circle_r + self._stub),
            "bottom": (cx, self.height / 2 - self._circle_r - self._stub),
        }
        self.anchors = {
            "label_right": (self.width / 2 + self._circle_r + 2, self.height / 2),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2
        cy = y + self.height / 2
        r = self._circle_r

        backend.set_layer(self.layer)
        backend.add_circle((cx, cy), radius=r)

        # "kWh" label inside
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("kWh", insert=(cx - 2, cy + 1), char_height=2.0)

        # Connection lines
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, cy + r), (cx, cy + r + self._stub))
        backend.add_line((cx, cy - r), (cx, cy - r - self._stub))


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
    """Isolator / Disconnect Switch at real proportions."""

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
