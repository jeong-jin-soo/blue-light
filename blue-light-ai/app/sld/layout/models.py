"""
SLD Layout data models and cable formatting utility.

Contains all dataclasses used by the layout engine:
- LayoutConfig: calibrated layout parameters
- PlacedComponent: positioned component with metadata
- LayoutResult: complete layout output
- _LayoutContext: mutable state shared between section methods
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


# -- Cable formatting helper --

def format_cable_spec(cable_input, multiline: bool = False) -> str:
    """
    Format cable specification into Singapore SLD standard format.

    Reference format (from real LEW DWG):
        "2 x 1C 25sqmm PVC/PVC + 10sqmm\\PPVC CPC IN METAL TRUNKING"

    Format breakdown:
        [{count} x] {cores}C {size}sqmm {type} + {cpc}sqmm
        {cpc_type} CPC IN {method}

    Args:
        cable_input: str, dict, or None
        multiline: if True, split CPC info to second line (\\P) for incoming cables

    Handles:
    - str: returned as-is
    - dict: formatted from keys {cores, type, size_mm2, cpc_mm2, cpc_type, method, count}
    - None/empty: returns empty string
    """
    if not cable_input:
        return ""

    if isinstance(cable_input, str):
        return cable_input

    if isinstance(cable_input, dict):
        count = cable_input.get("count", cable_input.get("runs", 1))
        cores = cable_input.get("cores", 2)
        cable_type = cable_input.get("type", "PVC/PVC")
        size = cable_input.get("size_mm2", cable_input.get("size", ""))
        # CPC (Circuit Protective Conductor) — accept both key names
        cpc = (cable_input.get("cpc_mm2", "")
               or cable_input.get("earth_mm2", "")
               or cable_input.get("cpc", ""))
        cpc_type = cable_input.get("cpc_type", "PVC")
        method = cable_input.get("method", "")
        if size:
            # Singapore LEW standard: "4x16mm²/1C PVC/PVC CABLE + 16mm² CPC IN METAL TRUNKING"
            if count and int(count) > 1:
                base = f"{count}x{size}mm²/{cores}C {cable_type} CABLE"
            else:
                base = f"{cores}C {size}mm² {cable_type} CABLE"
            if cpc:
                sep = " +\\P" if multiline else " + "
                base += f"{sep}{cpc}mm² CPC"
                if method:
                    base += f" IN {method}"
            elif method:
                base += f" IN {method}"
            return base
        return f"{cores}C {cable_type}"

    return str(cable_input)


# -- Data classes --

@dataclass
class LayoutConfig:
    """Layout configuration parameters (all in mm).

    Calibrated from 73 real LEW SLD samples to match professional proportions.
    """

    # Drawing area (A3 landscape minus margins and title block)
    drawing_width: float = 380
    drawing_height: float = 240

    # Component spacing calibrated from real LEW SLD samples
    # Real samples use generous spacing — diagram fills 70-80% of page
    vertical_spacing: float = 22      # Between components vertically (increased for clarity)
    horizontal_spacing: float = 26    # Between sub-circuits (base — real samples ~25-35mm per circuit column)
    min_horizontal_spacing: float = 20  # Minimum spacing between sub-circuits
    max_horizontal_spacing: float = 42  # Maximum spacing (prevents overly sparse layout)
    busbar_margin: float = 20         # Margin from edges of busbar (room for labels)

    # Sub-circuit row layout
    max_circuits_per_row: int = 14    # Fewer per row for better readability
    row_spacing: float = 50           # Vertical spacing between sub-circuit rows

    # Starting position
    start_x: float = 210             # Center of drawing
    start_y: float = 280             # Top of drawing (below margin)

    # Drawing boundaries (A3 landscape with margin + title block reserve)
    min_x: float = 25               # Left margin 25mm (real samples ~25-35mm)
    max_x: float = 395              # Right margin 25mm

    # Cable schedule reserve — space kept free on right side for cable schedule table
    cable_schedule_reserve: float = 120  # Reserve 120mm for cable schedule (0 = disabled)
    min_y: float = 62               # Title block occupies bottom ~55mm
    max_y: float = 285              # Top drawing border (297 - 10mm margin - 2mm buffer)

    # Symbol dimension references — auto-synced from real_symbol_paths.json
    # These are default fallback values; __post_init__ overwrites from JSON source
    breaker_w: float = 8.4           # MCCB width
    breaker_h: float = 15.0          # MCCB height
    mcb_w: float = 7.2              # MCB width
    mcb_h: float = 13.0             # MCB height
    rccb_w: float = 10.0            # RCCB/ELCB width
    rccb_h: float = 15.0            # RCCB/ELCB height
    meter_size: float = 14.0         # kWh meter overall size
    isolator_w: float = 8.0          # Isolator width
    isolator_h: float = 14.0         # Isolator height
    ct_size: float = 12.0            # CT diameter
    stub_len: float = 3.0            # Connection stub length
    # KWH meter rectangle dimensions (for horizontal meter board layout)
    kwh_rect_w: float = 12.0         # KWH inner rectangle width (horizontal span)
    kwh_rect_h: float = 6.0          # KWH inner rectangle height (vertical span)

    # Sub-circuit vertical offsets (derived from real LEW samples)
    busbar_to_breaker_gap: float = 12.0   # Gap from busbar to sub-circuit breaker bottom
    tail_length: float = 30.0             # Conductor tail above breaker top (fills page)
    db_box_busbar_margin: float = 8.0     # DB box edge offset above busbar
    db_box_tail_margin: float = 4.0       # DB box extends above breaker+stub by this
    db_box_label_margin: float = 8.0      # DB box extends above tail for label area

    # Cable leader line offsets
    leader_margin_above_db: float = 10.0  # Leader line gap above DB box top
    leader_extension: float = 10.0        # Horizontal extension beyond outermost conductor
    leader_bend_height: float = 5.0       # Vertical L-bend height at leader end

    # Earth bar offsets
    earth_y_below_busbar: float = 25.0    # Earth bar Y below busbar
    earth_x_from_db: float = 5.0          # Earth bar X right of DB box

    def __post_init__(self):
        """Sync symbol dimensions from real_symbol_paths.json (single source of truth)."""
        try:
            from app.sld.real_symbols import get_symbol_dimensions
            # MCCB
            mccb = get_symbol_dimensions("MCCB")
            self.breaker_w = mccb["width_mm"]
            self.breaker_h = mccb["height_mm"]
            # MCB
            mcb = get_symbol_dimensions("MCB")
            self.mcb_w = mcb["width_mm"]
            self.mcb_h = mcb["height_mm"]
            # RCCB
            rccb = get_symbol_dimensions("RCCB")
            self.rccb_w = rccb["width_mm"]
            self.rccb_h = rccb["height_mm"]
            # Isolator
            iso = get_symbol_dimensions("ISOLATOR")
            self.isolator_w = iso["width_mm"]
            self.isolator_h = iso["height_mm"]
            self.stub_len = iso["stub_mm"]
            # KWH Meter
            kwh = get_symbol_dimensions("KWH_METER")
            self.meter_size = kwh["width_mm"]
            kwh_rect_h = kwh["height_mm"] * 0.6           # rect_h = height * 0.6
            kwh_rect_w = kwh_rect_h * kwh.get("rect_ratio", 2.0)  # rect_w = rect_h * ratio
            self.kwh_rect_w = kwh_rect_w
            self.kwh_rect_h = kwh_rect_h
            # CT
            ct = get_symbol_dimensions("CT")
            self.ct_size = ct["width_mm"]
        except Exception:
            pass  # Use default fallback values if JSON unavailable


@dataclass
class PlacedComponent:
    """A component placed at a specific position in the layout."""

    symbol_name: str
    x: float
    y: float
    label: str = ""
    rating: str = ""
    cable_annotation: str = ""
    circuit_id: str = ""     # e.g., "CB-01", "LS1"
    load_info: str = ""      # e.g., "15kW / 21.7A"
    rotation: float = 0.0    # Text rotation for vertical labels (90 = vertical)
    # -- LEW-style breaker block fields --
    poles: str = ""              # e.g., "SPN", "TPN", "4P"
    breaker_type_str: str = ""   # e.g., "MCB", "MCCB"
    fault_kA: int = 0            # e.g., 6, 10, 25
    label_style: str = "default" # "default" | "breaker_block"
    breaker_characteristic: str = ""  # e.g., "B", "C", "D" (IEC 60898-1 trip curve)


@dataclass
class LayoutResult:
    """Result of the layout computation."""

    components: list[PlacedComponent] = field(default_factory=list)
    connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    thick_connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    dashed_connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    junction_dots: list[tuple[float, float]] = field(default_factory=list)
    solid_boxes: list[tuple[float, float, float, float]] = field(default_factory=list)
    arrow_points: list[tuple[float, float]] = field(default_factory=list)
    busbar_y: float = 0
    busbar_start_x: float = 0
    busbar_end_x: float = 0
    busbar_y_per_row: list[float] = field(default_factory=list)  # Per-row busbar Y values

    # DB box dashed line indices (for updating after busbar changes)
    db_box_dashed_indices: list[int] = field(default_factory=list)
    db_box_start_y: float = 0
    db_box_end_y: float = 0

    # Supply info for rendering
    supply_type: str = "three_phase"
    voltage: int = 400

    # Symbols used -- for dynamic legend generation
    symbols_used: set[str] = field(default_factory=set)

    # v6: rendering flags (cable schedule & legend disabled by default)
    render_cable_schedule: bool = False
    render_legend: bool = False

    # Incoming supply spine x-coordinate (set by compute_layout)
    # Used by _identify_groups() for deterministic incoming chain detection
    spine_x: float = 0.0


from app.sld.locale import SG_LOCALE, SldLocale


@dataclass
class _LayoutContext:
    """Shared mutable context passed between layout section methods.

    Holds all state that flows between sections during compute_layout().
    Each section method reads/writes this context instead of using local variables
    scattered across a 500+ line function.
    """
    result: LayoutResult
    config: LayoutConfig
    cx: float
    y: float

    # -- Parsed from requirements --
    supply_type: str = ""
    voltage: int = 400
    kva: int = 0
    supply_source: str = "sp_powergrid"
    incoming_cable: Any = ""

    # Main breaker
    breaker_type: str = "MCCB"
    breaker_rating: int = 0
    breaker_poles: str = ""
    breaker_fault_kA: int = 0
    main_breaker_char: str = ""
    meter_poles: str = "DP"

    # ELCB / RCCB
    elcb_config: dict = field(default_factory=dict)
    elcb_rating: int = 0
    elcb_ma: int = 30
    elcb_type_str: str = "ELCB"

    # Metering
    metering: str | None = None

    # Sub-circuits
    sub_circuits: list = field(default_factory=list)
    busbar_rating: int = 0

    # Tracking (set by sections, read by later sections)
    db_box_start_y: float = 0
    db_info_label: str = ""      # e.g. "40A DB"
    db_info_text: str = ""       # e.g. "APPROVED LOAD: 9.2KVA AT 230V"

    # Cable extension mode (no meter board / no isolator)
    is_cable_extension: bool = False

    # CT ratio (e.g., "200/5A")
    ct_ratio: str = ""

    # Raw inputs (for sections that need full access)
    requirements: dict = field(default_factory=dict)
    application_info: dict = field(default_factory=dict)
