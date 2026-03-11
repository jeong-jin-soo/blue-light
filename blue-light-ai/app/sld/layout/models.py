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
import re
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from app.sld.page_config import PageConfig

logger = logging.getLogger(__name__)


# -- Cable string normalizer (parse → canonical re-format) --

_UNIT_PAT = r'(?:mm[²2]|sqmm|mmsq)'

_CABLE_MAIN_RE = re.compile(
    r'^\s*'
    r'(?:(\d+)\s*x\s*)?'                       # group 1: count (optional)
    r'(\d+)\s*C\s+'                             # group 2: cores
    r'([\d.]+)\s*' + _UNIT_PAT + r'\s+'         # group 3: size + unit
    r'([\w/]+?)'                                # group 4: cable type (non-greedy)
    r'(?:\s+CABLE)?'                            # optional CABLE keyword
    r'\s*$',
    re.IGNORECASE,
)

_CPC_RE = re.compile(
    r'^\s*'
    r'([\d.]+)\s*' + _UNIT_PAT + r'\s*'         # group 1: CPC size + unit
    r'(?:(PVC|XLPE)\s+)?'                        # group 2: CPC type (optional)
    r'CPC'                                       # CPC keyword
    r'(?:\s+IN\s+(.+?))?'                        # group 3: method (optional)
    r'\s*$',
    re.IGNORECASE,
)


def _parse_cable_string(s: str) -> dict | None:
    """Parse cable spec string into dict for canonical re-formatting.

    Handles common Singapore LEW cable formats:
        "2 x 1C 1.5mm² PVC + 1.5mm² CPC"
        "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN TRUNKING/CONDUIT"
        "1C 2.5sqmm PVC/PVC + 2.5sqmm PVC CPC IN METAL TRUNKING"

    Returns None for non-standard formats (caller returns string as-is).
    """
    # Split on '+' to separate main cable from CPC section
    parts = s.split('+', 1)
    main_part = parts[0].strip()
    cpc_part = parts[1].strip() if len(parts) > 1 else ""

    # Strip DXF multiline marker (\P) from CPC part
    cpc_part = re.sub(r'^\\P\s*', '', cpc_part)

    m = _CABLE_MAIN_RE.match(main_part)
    if not m:
        return None

    result: dict = {
        'size_mm2': m.group(3),
        'cores': int(m.group(2)),
        'type': m.group(4),
    }
    if m.group(1):
        result['count'] = int(m.group(1))

    if cpc_part:
        cm = _CPC_RE.match(cpc_part)
        if cm:
            result['cpc_mm2'] = cm.group(1)
            if cm.group(2):
                result['cpc_type'] = cm.group(2).upper()
            if cm.group(3):
                result['method'] = cm.group(3).strip()

    return result


# -- Cable formatting helper --

def format_cable_spec(cable_input, multiline: bool = False) -> str:
    """
    Format cable specification into Singapore SLD standard format.

    Reference format (from real LEW DWG):
        "2 x 1C 25sqmm PVC/PVC + 10sqmm\\PPVC CPC IN METAL TRUNKING"

    Format breakdown:
        [{count} x] {cores}C {size}sqmm {type} + {cpc}sqmm
        {cpc_type} CPC IN {method}

    String inputs are parsed and re-formatted to canonical form so that
    identical cables from different sources (Excel, template DB, dict)
    produce the same output string — required for cable leader line grouping.

    Args:
        cable_input: str, dict, or None
        multiline: if True, split CPC info to second line (\\P) for incoming cables

    Handles:
    - str: parsed → canonical format (falls back to as-is if unparseable)
    - dict: formatted from keys {cores, type, size_mm2, cpc_mm2, cpc_type, method, count}
    - None/empty: returns empty string
    """
    if not cable_input:
        return ""

    if isinstance(cable_input, str):
        parsed = _parse_cable_string(cable_input)
        if parsed:
            return format_cable_spec(parsed, multiline=multiline)
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
            # Singapore LEW DWG standard:
            #   "2 x 1C 1.5sqmm PVC/PVC + 1.5sqmm PVC CPC IN METAL TRUNKING"
            if count and int(count) > 1:
                base = f"{count} x {cores}C {size}sqmm {cable_type}"
            else:
                base = f"{cores}C {size}sqmm {cable_type}"
            if cpc:
                sep = " +\\P" if multiline else " + "
                base += f"{sep}{cpc}sqmm {cpc_type} CPC"
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
    min_horizontal_spacing: float = 10  # Minimum spacing (ref ~12mm for 26 circuits)
    max_horizontal_spacing: float = 42  # Maximum spacing (prevents overly sparse layout)
    busbar_margin: float = 10         # Margin from edges of busbar (room for labels)

    # Sub-circuit row layout
    max_circuits_per_row: int = 30    # Single busbar row (reference: 26 circuits on 1 busbar)
    row_spacing: float = 40           # Vertical spacing between sub-circuit rows (reduced for 18/row fit)

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
    tail_length: float = 8.0              # Conductor tail minimum — dynamically extended past leader line
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

    # -- Consolidated text measurement constants (D1) --
    char_width_estimate: float = 1.8      # mm per char for bounding box estimation
    label_char_height: float = 2.8        # Default label text height (mm)
    char_advance: float = 1.7             # Vertical char advance for label wrapping
    preferred_max_label_chars: int = 25   # Preferred max chars before line wrapping

    # -- Meter board constants (D2) --
    meter_board_comp_spacing: float = 25.0  # Horizontal spacing between MB components
    meter_board_inset: float = 4.0          # Inset margin for meter board box
    meter_board_gap: float = 1.5            # Gap between MB and box top

    # -- Overlap resolution constants (D2) --
    overlap_group_gap: float = 3.0        # Gap between sub-circuit groups
    overlap_ditto_extent: float = 5.5     # Width for "ditto" (identical spec) circuits
    circuit_group_gap: float = 6.0        # Gap between circuit groups in helpers

    # -- Matching tolerances / margins (D3) --
    position_tolerance: float = 1.5       # X-axis matching tolerance
    overlap_margin: float = 2.0           # Margin in bounding box computation
    busbar_end_margin: float = 10.0       # Margin at busbar ends for final positions
    db_box_overlap_margin: float = 12.0   # DB box margin in overlap resolution

    @classmethod
    def from_page_config(
        cls,
        page_config: PageConfig | None = None,
        **overrides: Any,
    ) -> LayoutConfig:
        """Create LayoutConfig with page-boundary fields derived from PageConfig.

        Spacing and symbol dimensions remain at calibrated defaults.
        Only boundary-related fields (drawing_width/height, min/max_x/y,
        start_x/y) are derived from the page geometry.

        When called with no arguments, the result is identical to ``LayoutConfig()``
        for every boundary field (A3 landscape defaults).
        """
        from app.sld.page_config import A3_LANDSCAPE as _A3

        pc = page_config or _A3
        tb_top = pc.margin + pc.title_block_height  # 42 for A3

        derived: dict[str, Any] = {
            "drawing_width": pc.page_width - 2 * pc.margin - 20,   # 380 for A3
            "drawing_height": pc.page_height - pc.margin - tb_top - 5,  # 240 for A3
            "min_x": pc.margin + 15,                    # 25 for A3
            "max_x": pc.page_width - pc.margin - 15,    # 395 for A3
            "min_y": tb_top + 20,                       # 62 for A3
            "max_y": pc.page_height - pc.margin - 2,    # 285 for A3
            "start_x": pc.page_width / 2,              # 210 for A3
            "start_y": pc.page_height - pc.margin - 7,  # 280 for A3
        }
        # Overrides take precedence over derived values
        derived.update(overrides)

        return cls(**derived)

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
        except Exception as exc:
            logger.warning("Symbol dimension load from JSON failed, using defaults: %s", exc)


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

    # Symbols used (diagnostic — tracks which symbol types are placed)
    symbols_used: set[str] = field(default_factory=set)

    # Incoming supply spine x-coordinate (set by compute_layout)
    # Used by _identify_groups() for deterministic incoming chain detection
    spine_x: float = 0.0

    # Junction dot indices relocated by phase fanout (excluded from orphan validation)
    fanout_relocated_dots: set[int] = field(default_factory=set)

    # Overflow detection metrics (populated by _detect_overflow after centering)
    overflow_metrics: "OverflowMetrics | None" = None


@dataclass
class OverflowMetrics:
    """Post-layout overflow detection metrics.

    Measures how well the SLD content fits within the drawing area.
    All distances in mm. Populated by _detect_overflow() after _center_vertically().
    """

    # Content extents (actual min/max of all layout elements)
    content_min_x: float = 0.0
    content_max_x: float = 0.0
    content_min_y: float = 0.0
    content_max_y: float = 0.0

    # Overflow amounts (positive = overflow beyond boundary, 0 = no overflow)
    overflow_left: float = 0.0
    overflow_right: float = 0.0
    overflow_top: float = 0.0
    overflow_bottom: float = 0.0

    # Circuit spacing metrics
    circuit_count: int = 0
    actual_min_spacing: float = 0.0
    ideal_spacing: float = 0.0
    horizontal_compression_ratio: float = 1.0

    # Warnings generated
    warnings: list[str] = field(default_factory=list)

    @property
    def has_overflow(self) -> bool:
        """True if content exceeds any drawing boundary by more than 0.5mm."""
        return (
            self.overflow_left > 0.5
            or self.overflow_right > 0.5
            or self.overflow_top > 0.5
            or self.overflow_bottom > 0.5
        )

    @property
    def is_compressed(self) -> bool:
        """True if horizontal compression was applied (ratio < 95%)."""
        return self.horizontal_compression_ratio < 0.95

    @property
    def quality_score(self) -> float:
        """Layout quality score 0.0–1.0 (1.0 = perfect fit)."""
        if self.has_overflow:
            total_overflow = (
                self.overflow_left + self.overflow_right
                + self.overflow_top + self.overflow_bottom
            )
            return max(0.0, 1.0 - total_overflow / 50.0)
        if self.is_compressed:
            return max(0.5, self.horizontal_compression_ratio)
        return 1.0

    def to_dict(self) -> dict:
        """Serialize to dict for API response."""
        d: dict = {
            "has_overflow": self.has_overflow,
            "quality_score": round(self.quality_score, 2),
        }
        if self.warnings:
            d["layout_warnings"] = self.warnings
        if self.has_overflow:
            d["overflow"] = {
                "left": round(self.overflow_left, 1),
                "right": round(self.overflow_right, 1),
                "top": round(self.overflow_top, 1),
                "bottom": round(self.overflow_bottom, 1),
            }
        if self.is_compressed:
            d["compression"] = {
                "ratio": round(self.horizontal_compression_ratio, 2),
                "circuit_count": self.circuit_count,
                "actual_min_spacing_mm": round(self.actual_min_spacing, 1),
                "ideal_spacing_mm": round(self.ideal_spacing, 1),
            }
        return d


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
    db_location_text: str = ""   # e.g. "LOCATED AT BLK 824 ..." (below DB box)

    # Cable extension mode (no meter board / no isolator)
    is_cable_extension: bool = False

    # CT ratio (e.g., "200/5A")
    ct_ratio: str = ""

    # Raw inputs (for sections that need full access)
    requirements: dict = field(default_factory=dict)
    application_info: dict = field(default_factory=dict)
