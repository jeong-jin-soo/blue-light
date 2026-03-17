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

def _cable_smart_case(s: str) -> str:
    """Uppercase cable text but keep measurement units lowercase.

    Singapore LEW reference convention:
        "4 x 50mm² PVC/PVC CABLE + 50mm² CPC IN METAL TRUNKING"
        ───────── lowercase ──────  ─── UPPERCASE words ───────
    Units (sqmm, mm², x between numbers) stay lowercase;
    words (PVC, CABLE, CPC, IN, METAL TRUNKING, CONDUIT) are uppercased.
    """
    s = s.upper()
    # "50SQMM" → "50sqmm"
    s = re.sub(r'(\d)SQMM', r'\1sqmm', s)
    # "50MM²" → "50mm²"  (also MM2 → mm²)
    s = re.sub(r'(\d)MM²', r'\1mm²', s)
    s = re.sub(r'(\d)MM2\b', r'\1mm²', s)
    # "4 X 1C" → "4 x 1C"  (multiplier x between digits)
    s = re.sub(r'(\d)\s*X\s+(\d)', r'\1 x \2', s)
    return s


def format_cable_spec(cable_input, multiline: bool = False) -> str:
    """
    Format cable specification into Singapore SLD standard format.

    Reference format (from real LEW DWG):
        "2 x 1C 25sqmm PVC/PVC + 10sqmm\\PPVC CPC IN METAL TRUNKING"

    Format breakdown:
        [{count} x] {cores}C {size}sqmm {type} + {cpc}sqmm
        {cpc_type} CPC IN {method}

    Case convention (from reference drawings):
        - Units stay lowercase: sqmm, mm², x (multiplier)
        - Words are uppercase: PVC, CABLE, CPC, IN, METAL TRUNKING

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
        result = _cable_smart_case(cable_input)
        # Apply multiline split at "+" boundary even for unparseable strings
        if multiline and " + " in result:
            result = result.replace(" + ", " +\\P", 1)
        return result

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
            # Singapore LEW DWG standard (units lowercase, words uppercase):
            #   "2 x 1C 1.5sqmm PVC/PVC + 1.5sqmm PVC CPC IN METAL TRUNKING"
            if count and int(count) > 1:
                base = f"{count} x {cores}C {size}sqmm {cable_type.upper()}"
            else:
                base = f"{cores}C {size}sqmm {cable_type.upper()}"
            if cpc:
                sep = " +\\P" if multiline else " + "
                base += f"{sep}{cpc}sqmm {cpc_type.upper()} CPC"
                if method:
                    base += f" IN {method.upper()}"
            elif method:
                base += f" IN {method.upper()}"
            return base
        return f"{cores}C {cable_type.upper()}"

    result = _cable_smart_case(str(cable_input))
    if multiline and " + " in result:
        result = result.replace(" + ", " +\\P", 1)
    return result


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
    # Defaults match JSON values (C4 fix); __post_init__ re-reads from JSON to stay current
    breaker_w: float = 5.5           # MCCB width (real_symbol_paths.json)
    breaker_h: float = 9.0           # MCCB height
    mcb_w: float = 5.0              # MCB width
    mcb_h: float = 8.0              # MCB height
    rccb_w: float = 6.5             # RCCB/ELCB width
    rccb_h: float = 9.0             # RCCB/ELCB height
    meter_size: float = 16.0         # kWh meter overall size
    isolator_w: float = 5.5          # Isolator width
    isolator_h: float = 7.0          # Isolator height
    ct_size: float = 2.5             # CT diameter
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

    # -- Isolator section spacing --
    isolator_to_db_gap: float = 14.0      # Gap from isolator section end to main breaker (was 18)
    isolator_label_gap: float = 5.0       # Gap between isolator enclosure box edge and label text

    # -- Cable annotation constants --
    cable_leader_len: float = 9.0         # Cable tick leader line length (was hardcoded 3mm)
    cable_leader_text_gap: float = 3.0    # Gap between leader line end and cable text (was 1mm)

    # Earth bar offsets
    earth_y_below_busbar: float = 25.0    # Earth bar Y below busbar
    earth_x_from_db: float = 5.0          # Earth bar X right of DB box

    # -- Consolidated text measurement constants (D1) --
    # Character width estimates (mm per character) for different text sizes.
    # Used throughout layout, overlap, and centering for bounding-box estimation.
    char_w_label: float = 1.8        # mm/char for label text (char_height=2.8, bounding boxes)
    char_w_info: float = 1.55        # mm/char for info text (char_height=2.0~2.3, centering/overflow)
    char_width_estimate: float = 1.8      # mm per char (legacy alias, prefer char_w_label)
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
    circuit_group_gap: float = 3.0        # Gap between circuit groups (LEW ref: ~3mm extra at phase transitions)

    # -- Matching tolerances / margins (D3) --
    position_tolerance: float = 1.5       # X-axis matching tolerance
    overlap_margin: float = 2.0           # Margin in bounding box computation
    busbar_end_margin: float = 10.0       # Margin at busbar ends for final positions
    db_box_overlap_margin: float = 12.0   # DB box margin in overlap resolution

    # -- CT metering spine spacing (calibrated from DXF reference) --
    ct_entry_gap: float = 0.5        # Gap from spine entry point to first CT component
    ct_to_ct_gap: float = 3.0        # Gap between protection CT and metering CT
    ct_to_branch_gap: float = 3.0    # Vertical offset from CT center to horizontal branch

    # -- DB info text layout (used by _place_db_box / _place_multi_db_boxes) --
    db_info_title_h: float = 4.0     # Title line height (char_height=3.0 + gap)
    db_info_line_h: float = 3.0      # Per info line height (char_height=1.8 + gap)
    db_info_pad: float = 2.0         # Bottom padding

    def db_info_height(self, info_text: str) -> float:
        """Compute DB info area height from text content.

        Formula: title_h + lines × line_h + pad
        Previously duplicated as ``4 + lines * 3 + 2`` in sections.py and engine.py.
        """
        lines = info_text.count("\\P") + 1 if info_text else 0
        return self.db_info_title_h + lines * self.db_info_line_h + self.db_info_pad

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
    """A component placed at a specific position in the layout.

    Coordinate Contract (A2):
        x : left edge of the symbol body bounding box (mm).
        y : bottom edge of the symbol body bounding box (mm).

        The body bbox excludes connection stubs (the short lines that
        attach the symbol to the busbar/spine).  In concrete terms:

            +--- body top  = y + symbol.height
            |  [symbol]
            +--- body bottom = y          ← comp.y
            |  (stub line, not part of body)
            +--- pin tip   = y - stub

        comp.x is the left edge:  center_x = comp.x + symbol.width / 2

        This anchor convention is used consistently by:
        - Layout code (sections.py, engine.py): sets x, y
        - Symbol.render(): interprets x, y for drawing
        - Generator labels: offsets from x, y for text placement

    See also: Symbol.render() docstring in symbol.py.
    """

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
    no_ditto: bool = False  # True = always show full label (e.g., per-phase RCCB in protection groups)
    enclosed: bool = False  # True = draw enclosure box around symbol (e.g., landlord unit isolator)
    label_y_override: float | None = None  # Absolute Y for label (bypasses default calculation)
    no_right_stub: bool = False  # True = skip right connection stub (e.g., KWH in CT metering)
    # -- DB_INFO_BOX sub-anchors (layout determines, renderer uses as-is) --
    rating_offset_y: float = -4.0    # Y offset for rating text relative to title
    title_char_height: float = 3.0   # Title text char_height (mm)
    rating_char_height: float = 1.8  # Rating text char_height (mm)


@dataclass
class LayoutResult:
    """Result of the layout computation."""

    components: list[PlacedComponent] = field(default_factory=list)
    connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    thick_connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    dashed_connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    junction_dots: list[tuple[float, float]] = field(default_factory=list)
    # CT branch junction arrows: (x, y, direction) — triangular connectors at CT branch points
    # direction: "left" or "right" (branch direction from spine)
    junction_arrows: list[tuple[float, float, str]] = field(default_factory=list)
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

    # Triplet rendering flag (per-board, propagated for post-processing).
    # False = phase-grouped layout; skip fan-out, triplet padding, row rounding.
    use_triplets: bool = True

    # Symbols used (diagnostic — tracks which symbol types are placed)
    symbols_used: set[str] = field(default_factory=set)

    # Sections rendered (diagnostic — tracks which layout sections actually placed components).
    # Keys: "incoming_supply", "meter_board", "unit_isolator", "ct_pre_mccb_fuse",
    #        "main_breaker", "ct_metering_section", "elcb", "internal_cable",
    #        "main_busbar", "sub_circuits", "db_box", "earth_bar"
    # Value: True if section placed components, absent/False if skipped.
    sections_rendered: dict[str, bool] = field(default_factory=dict)

    # Incoming supply spine x-coordinate (set by compute_layout)
    # Used by _identify_groups() for deterministic incoming chain detection
    spine_x: float = 0.0

    # Junction dot indices relocated by phase fanout (excluded from orphan validation)
    fanout_relocated_dots: set[int] = field(default_factory=set)

    # Phase fan-out groups: list of (center_x, busbar_y, side_xs: list[float])
    # Used by generator to render fan-out as DXF blocks or procedural lines
    fanout_groups: list[tuple[float, float, list[float]]] = field(default_factory=list)

    # Overflow detection metrics (populated by _detect_overflow after centering)
    overflow_metrics: "OverflowMetrics | None" = None

    # Multi-DB tracking
    db_count: int = 1  # Number of distribution boards (1=single, 2+=multi)
    db_box_ranges: list[dict] = field(default_factory=list)  # Per-DB: {start_y, end_y, left, right}

    # Per-row busbar extents for multi-DB overlap resolution.
    # Maps busbar Y coordinate → (start_x, end_x) so that resolve_overlaps
    # can constrain each row's circuits to the correct busbar extent.
    busbar_x_per_row: dict[float, tuple[float, float]] = field(default_factory=dict)

    # Layout regions for multi-DB overlap resolution.
    # When set, resolve_overlaps splits groups by X region and processes
    # each region independently. This prevents circuits from one DB
    # being repositioned across another DB's region.
    layout_regions: list["LayoutRegion"] = field(default_factory=list)


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


# -- Layout Planning dataclasses (pre-computation before placement) --

@dataclass
class LayoutRegion:
    """Strict horizontal bounding region for a distribution board.

    Computed by _plan_layout() BEFORE any component placement.
    All placement functions MUST constrain output to [min_x, max_x].

    Design rationale (Walker/Reingold-Tilford + Flexbox hybrid):
    - Bottom-up measurement: leaf nodes (circuits) → groups → DBs → total
    - Top-down allocation: total → DBs → groups → circuits
    - Each DB gets a non-overlapping horizontal strip
    - Y coordinates flow naturally (top-to-bottom) and don't need strict bounds

    Backward compatibility: When active_region is None (single-DB), all
    placement functions use full page width as before.
    """
    min_x: float
    max_x: float
    name: str = ""

    @property
    def width(self) -> float:
        return self.max_x - self.min_x

    @property
    def cx(self) -> float:
        return (self.min_x + self.max_x) / 2


@dataclass
class ProtectionGroupPlan:
    """Pre-computed dimensions for a single per-phase RCCB group.

    Used by _plan_layout() to estimate horizontal space for DB2-style
    boards where each phase (L1/L2/L3) has its own RCCB + busbar + circuits.
    """
    phase: str                    # "L1" / "L2" / "L3"
    rccb: dict = field(default_factory=dict)  # {rating, sensitivity_ma, poles, type}
    circuit_count: int = 0
    estimated_width: float = 0.0  # circuits × spacing (mm)
    busbar_rating: int = 0


@dataclass
class DBPlan:
    """Pre-computed dimensions for a single Distribution Board.

    Estimated before sequential placement so that global layout
    can allocate horizontal regions and detect A3 overflow early.
    """
    name: str = ""
    circuit_count: int = 0
    estimated_width: float = 0.0   # total DB horizontal span (mm)
    estimated_height: float = 0.0  # total DB vertical span (mm)
    has_elcb: bool = False
    protection_groups: list[ProtectionGroupPlan] = field(default_factory=list)
    num_rows: int = 1              # number of circuit rows (multi-row when region too narrow)
    circuits_per_row: int = 0      # max circuits in widest row


@dataclass
class LayoutPlan:
    """Global SLD layout plan computed before sequential placement.

    Analogous to CSS box model / TeX first-pass: measures all DBs,
    allocates horizontal regions, and determines if scaling is needed
    to fit within A3 drawing area.

    When ctx.plan is None, the engine uses the existing single-DB path
    (100% backward compatible).
    """
    total_width: float = 0.0           # all DBs combined width (mm)
    total_height: float = 0.0          # incoming → topmost sub-circuit (mm)
    db_plans: list[DBPlan] = field(default_factory=list)
    main_section_height: float = 0.0   # incoming ~ main busbar height (mm)
    scale_factor: float = 1.0          # <1.0 if A3 width exceeded
    db_cx_positions: list[float] = field(default_factory=list)  # center X per DB
    db_regions: list[LayoutRegion] = field(default_factory=list)  # strict X bounds per DB

    # Hierarchical topology fields
    topology: str = "parallel"         # "parallel" or "hierarchical"
    root_db_idx: int = 0               # index of root board (fed_from=None)
    incoming_cx: float = 0.0           # cx for incoming section (root board center)


@dataclass
class BoardResult:
    """Independent layout result for a single distribution board.

    Coordinates are in page space (render_board sets cx=region.cx, so
    section functions already output in page coordinates).
    For single-DB backward compatibility, this is a simple wrapper.
    """
    layout: LayoutResult
    board_spec: dict = field(default_factory=dict)
    board_idx: int = 0
    board_name: str = ""
    effective_supply_type: str = ""
    spine_x: float = 0.0
    region: "LayoutRegion | None" = None
    busbar_y: float = 0.0
    busbar_start_x: float = 0.0
    busbar_end_x: float = 0.0
    db_box_start_y: float = 0.0
    topmost_busbar_y: float = 0.0
    db_info_label: str = ""
    db_info_text: str = ""
    db_location_text: str = ""
    breaker_rating: int = 0


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
    post_elcb_mcb: dict = field(default_factory=dict)  # MCB after RCCB (serial)

    # Feeder connection (hierarchical topology)
    feeder_breaker: dict = field(default_factory=dict)
    feeder_cable: str = ""

    # Internal cable (MCCB→busbar segment)
    internal_cable: str = ""

    # Meter board label
    meter_board_label: str = ""

    # Premises type (residential, commercial, industrial, etc.)
    premises_type: str = ""

    # Metering
    metering: str | None = None

    # Sub-circuits
    sub_circuits: list = field(default_factory=list)
    busbar_rating: int = 0

    # Tracking (set by sections, read by later sections)
    main_breaker_arc_center_y: float = 0  # MCCB arc center Y (between contacts)
    db_box_start_y: float = 0
    board_name: str = ""         # e.g. "MSB", "DB1" — displayed inside DB box
    db_info_label: str = ""      # e.g. "40A DB"
    db_info_text: str = ""       # e.g. "APPROVED LOAD: 9.2KVA AT 230V"
    db_location_text: str = ""   # e.g. "LOCATED AT BLK 824 ..." (below DB box)

    # Cable extension mode (no meter board / no isolator)
    is_cable_extension: bool = False

    # CT ratio (e.g., "200/5A")
    ct_ratio: str = ""

    # CT metering section details (vertical layout for ≥125A 3-phase)
    protection_ct_ratio: str = ""
    protection_ct_class: str = ""
    metering_ct_class: str = ""
    has_ammeter: bool = True
    has_voltmeter: bool = True
    has_elr: bool = True
    has_indicator_lights: bool = True  # 3-phase indicator lights on fuse branches (per ref DWGs)
    elr_spec: str = ""
    voltmeter_range: str = ""
    ammeter_range: str = ""
    _ct_pre_mccb_fuse: bool = False  # Flag: place pre-MCCB fuse as horizontal branch

    # Raw inputs (for sections that need full access)
    requirements: dict = field(default_factory=dict)
    application_info: dict = field(default_factory=dict)

    # Multi-DB tracking
    distribution_boards: list[dict] = field(default_factory=list)  # Input DB array
    current_db_idx: int = -1  # Current DB being placed (-1=main/single)
    db_spine_xs: list[float] = field(default_factory=list)  # Per-DB spine X positions

    # Layout plan (None = single-DB backward-compatible path)
    plan: LayoutPlan | None = None

    # Per-DB constrained width (mm).  When set, _compute_dynamic_spacing()
    # and _place_main_busbar() use this instead of full page width.
    # Set per-board by render_board() via region.width.
    constrained_width: float | None = None

    # Active layout region (strict X bounds for current DB).
    # When set, all placement functions MUST constrain output to this region.
    # None = full page width (single-DB backward-compatible path).
    active_region: LayoutRegion | None = None

    # DB topology: "parallel" (default, all DBs branch from shared main busbar)
    # or "hierarchical" (root DB has incoming, child DBs fed from root via feeder).
    db_topology: str = "parallel"

    # Phase arrangement: False = phase-grouped, skip triplet rendering.
    use_triplets: bool = True

    # Skip BI connector between multi-row busbars (protection groups don't use BI connectors)
    skip_row_bi_connector: bool = False
