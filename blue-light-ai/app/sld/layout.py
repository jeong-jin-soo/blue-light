"""
SLD Layout Engine -- automatic component placement (v6 LEW-style).

Bottom-up layout matching real LEW (Licensed Electrical Worker) SLD conventions:
1. Incoming supply at the BOTTOM (just above title block)
   with current flow direction arrow pointing upward
2. Isolator (disconnect switch) for CT-metered installations or landlord supply
3. CT Metering for ct_meter installations (Current Transformer + kWh Meter)
4. SP kWh Meter (direct metering for sp_meter installations)
5. Main breaker above metering (with kA fault rating & pole configuration)
6. Main busbar horizontally (double-line professional representation)
7. ELCB/RCCB inline in main chain (between main breaker and busbar per LEW guide)
8. Sub-circuit breakers branching UPWARD from busbar
   with vertical text labels and multi-line breaker blocks
9. Earth bar at bottom-left with dashed conductor connections

Key v6 changes from v5:
- Layout direction inverted (incoming at bottom, circuits branch upward)
- Vertical text (90-degree rotation) for circuit descriptions + cable specs
- Breaker label block format (rating / poles / type / kA as separate lines)
- Cable schedule table removed (inline annotations instead)
- Legend removed (standard symbols are self-explanatory to LEWs)
- Dense packing: horizontal_spacing 16mm (calibrated from real samples), max 24 circuits per row
"""

from __future__ import annotations

from dataclasses import dataclass, field


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
            # Singapore LEW standard: "2 x 1C 25sqmm PVC/PVC + 10sqmm PVC CPC IN METAL TRUNKING"
            if count and int(count) > 1:
                base = f"{count} x {cores}C {size}sqmm {cable_type}"
            else:
                base = f"{cores}C {size}sqmm {cable_type}"
            if cpc:
                base += f" + {cpc}sqmm"
                cpc_suffix = f"{cpc_type} CPC" if cpc_type else "CPC"
                if method:
                    cpc_suffix += f" IN {method}"
                if multiline:
                    base += f"\\P{cpc_suffix}"
                else:
                    base += f" {cpc_suffix}"
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
    horizontal_spacing: float = 26    # Between sub-circuits (increased — real samples ~25-35mm per circuit column)
    min_horizontal_spacing: float = 20  # Minimum spacing between sub-circuits
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
    min_y: float = 62               # Title block occupies bottom ~55mm

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


from typing import Any


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

    # Raw inputs (for sections that need full access)
    requirements: dict = field(default_factory=dict)
    application_info: dict = field(default_factory=dict)


# =============================================
# Overlap Resolution System
# =============================================

@dataclass
class BoundingBox:
    """Axis-aligned bounding box for overlap detection (mm coordinates)."""

    x: float       # Left edge
    y: float       # Bottom edge
    width: float
    height: float

    @property
    def right(self) -> float:
        return self.x + self.width

    @property
    def top(self) -> float:
        return self.y + self.height

    def overlaps(self, other: "BoundingBox") -> bool:
        """Check if this bounding box overlaps with another (strict, not touching)."""
        if self.x >= other.right or other.x >= self.right:
            return False
        if self.y >= other.top or other.y >= self.top:
            return False
        return True

    def overlap_area(self, other: "BoundingBox") -> float:
        """Calculate the area of overlap between two bounding boxes."""
        ox = max(0.0, min(self.right, other.right) - max(self.x, other.x))
        oy = max(0.0, min(self.top, other.top) - max(self.y, other.y))
        return ox * oy


# Symbol dimensions: (width_mm, height_mm) — must match generator.py rendering
_SYMBOL_DIMS: dict[str, tuple[float, float]] = {
    "CB_MCB": (7.2, 13.0),
    "CB_MCCB": (8.4, 15.0),
    "CB_ACB": (10, 17),
    "CB_ELCB": (10.0, 15.0),
    "CB_RCCB": (10.0, 15.0),
    "ISOLATOR": (8, 14),
    "KWH_METER": (14, 10),
    "CT": (12, 12),
    "EARTH": (12, 10),
    "CIRCUIT_ID_BOX": (8, 5),
    "DB_INFO_BOX": (80, 18),
    "FLOW_ARROW_UP": (8, 10),
}

_CHAR_W = 1.8    # Approximate mm per character
_LABEL_CHAR_H = 2.8  # Default label char height (mm)


def _compute_bounding_box(comp: PlacedComponent) -> BoundingBox | None:
    """
    Compute the bounding box for a placed component.

    Returns None for structural elements (BUSBAR) that should never be moved.
    Handles text sizing and 90-degree rotation for labels.
    """
    name = comp.symbol_name

    # Structural — skip
    if name == "BUSBAR":
        return None

    # Special: CIRCUIT_ID_BOX — plain text label centered on tap_x (no box)
    if name == "CIRCUIT_ID_BOX":
        text_w = len(comp.circuit_id or "") * _CHAR_W + 2
        return BoundingBox(x=comp.x - text_w / 2, y=comp.y, width=text_w, height=3)

    # Special: DB_INFO_BOX extends downward from comp.y
    if name == "DB_INFO_BOX":
        return BoundingBox(x=comp.x, y=comp.y - 18, width=80, height=18)

    # Breaker with breaker_block label style (sub-circuit breakers)
    if name.startswith("CB_") and comp.label_style == "breaker_block":
        # Symbol dimensions
        sym_w, sym_h = _SYMBOL_DIMS.get(name, (10, 16))

        # Height: breaker + stub + tail + name label (vertical)
        total_h = sym_h + 5 + 10 + 10  # breaker + stub + tail + name label

        if abs(comp.rotation - 90.0) < 0.1:
            # Rotation=90°: labels are drawn to the LEFT of the MCB
            # (base_x = comp.x - 12 in generator). Only symbol on the right.
            # Labels are short text (~6mm wide), so actual right edge ≈ comp.x - 6
            label_left = 7.0   # covers label text extent (compact)
            right_extent = sym_w + 1.0  # symbol right edge + small margin
            return BoundingBox(
                x=comp.x - label_left,
                y=comp.y,
                width=label_left + right_extent,
                height=total_h,
            )
        else:
            # Rotation=0°: labels are drawn to the RIGHT of the MCB
            if comp.breaker_type_str in ("MCCB", "ACB"):
                base_offset = 16
            else:
                base_offset = 12  # MCB
            num_info = sum([
                bool(comp.rating),
                bool(comp.poles),
                bool(comp.breaker_type_str),
                bool(comp.fault_kA),
            ])
            label_right = base_offset + num_info * 3.5  # 3.5mm column gap
            left_extent = 2.0
            return BoundingBox(
                x=comp.x - left_extent,
                y=comp.y,
                width=left_extent + label_right,
                height=total_h,
            )

    # Standard symbol (non-breaker-block)
    if name in _SYMBOL_DIMS:
        w, h = _SYMBOL_DIMS[name]
        return BoundingBox(x=comp.x, y=comp.y, width=w, height=h)

    # Text LABEL
    if name == "LABEL":
        text = comp.label or ""
        lines = text.replace("\\P", "\n").split("\n")
        max_line_len = max((len(line) for line in lines), default=0)
        num_lines = len(lines)
        char_h = _LABEL_CHAR_H

        if abs(comp.rotation - 90.0) < 0.1:
            # Rotated 90°: width↔height swap; text runs upward
            return BoundingBox(
                x=comp.x,
                y=comp.y,
                width=num_lines * char_h,
                height=max_line_len * _CHAR_W,
            )
        else:
            return BoundingBox(
                x=comp.x,
                y=comp.y,
                width=max_line_len * _CHAR_W,
                height=num_lines * char_h,
            )

    # Default: small generic box
    return BoundingBox(x=comp.x, y=comp.y, width=5, height=5)


@dataclass
class SubCircuitGroup:
    """All layout elements belonging to one sub-circuit column."""
    tap_x: float                         # Original tap_x (breaker center)
    breaker_idx: int | None = None       # Index in components list
    circuit_id_idx: int | None = None    # CIRCUIT_ID_BOX index
    name_label_idx: int | None = None    # Vertical circuit name LABEL index
    spare_label_idx: int | None = None   # SPARE label index (if spare circuit)
    connection_indices: list[int] = field(default_factory=list)  # Indices in connections list
    junction_dot_idx: int | None = None   # Index in junction_dots list
    arrow_point_idx: int | None = None    # Index in arrow_points list
    is_spare: bool = False
    is_ditto: bool = False               # True if this MCB is ditto (no breaker labels)
    min_width: float = 25.0              # Minimum horizontal space needed (mm)
    left_extent: float = 12.5            # Distance from tap_x to BB left edge (mm)
    right_extent: float = 12.5           # Distance from tap_x to BB right edge (mm)
    gap_before: float = 0.0              # Extra gap (mm) before this group (category break)


def _breaker_half_width(comp: PlacedComponent) -> float:
    """Return half the breaker symbol width for tap_x calculation.

    Values synced with real_symbol_paths.json (DWG-calibrated).
    """
    if comp.symbol_name == "CB_MCB":
        return 3.6   # 7.2mm / 2
    elif comp.symbol_name == "CB_MCCB":
        return 4.2   # 8.4mm / 2
    elif comp.symbol_name in ("CB_RCCB", "CB_ELCB"):
        return 5.0   # 10.0mm / 2
    elif comp.symbol_name == "CB_ACB":
        return 5.0   # 10.0mm / 2
    return 4.2


def _identify_groups(
    layout_result: LayoutResult,
) -> tuple[list[SubCircuitGroup], float]:
    """
    Identify sub-circuit groups by scanning components and connections.

    Each breaker_block or SPARE label anchors a group. Associated
    CIRCUIT_ID_BOX, circuit name LABEL, and vertical connections are
    matched by proximity to the group's tap_x.

    Returns:
        groups: list of SubCircuitGroup sorted by tap_x ascending
        incoming_chain_x: x-coordinate of the incoming supply chain
    """
    components = layout_result.components
    connections = layout_result.connections
    _TOL = 1.5  # coordinate matching tolerance (mm)

    groups: list[SubCircuitGroup] = []

    # Step 1: Create groups from breaker_block components
    for i, comp in enumerate(components):
        if comp.label_style == "breaker_block" and comp.symbol_name.startswith("CB_"):
            tap_x = comp.x + _breaker_half_width(comp)
            g = SubCircuitGroup(tap_x=tap_x, breaker_idx=i)
            groups.append(g)

    # Step 2: Create groups from SPARE labels
    for i, comp in enumerate(components):
        if (comp.symbol_name == "LABEL"
                and abs(comp.rotation - 90.0) < 0.1
                and comp.label.strip().upper() == "SPARE"):
            tap_x = comp.x  # SPARE label placed at tap_x
            g = SubCircuitGroup(tap_x=tap_x, spare_label_idx=i, is_spare=True)
            groups.append(g)

    if not groups:
        return [], 0.0

    # Sort by tap_x
    groups.sort(key=lambda g: g.tap_x)
    tap_xs_set = {g.tap_x for g in groups}

    # Step 3: Determine incoming chain x (most common vertical connection x
    # that is NOT a sub-circuit tap)
    vert_conn_x_count: dict[float, int] = {}
    for ci, ((sx, sy), (ex, ey)) in enumerate(connections):
        if abs(sx - ex) < 0.5:  # Vertical connection
            x_val = round(sx, 1)
            vert_conn_x_count[x_val] = vert_conn_x_count.get(x_val, 0) + 1

    # incoming chain x = x with most connections that isn't a tap_x
    incoming_chain_x = 0.0
    max_count = 0
    for x_val, count in vert_conn_x_count.items():
        is_tap = any(abs(x_val - g.tap_x) < _TOL for g in groups)
        if not is_tap and count > max_count:
            max_count = count
            incoming_chain_x = x_val

    # Step 4: Match CIRCUIT_ID_BOX to groups
    for i, comp in enumerate(components):
        if comp.symbol_name != "CIRCUIT_ID_BOX":
            continue
        for g in groups:
            if abs(comp.x - g.tap_x) < _TOL and g.circuit_id_idx is None:
                g.circuit_id_idx = i
                break

    # Step 5: Match vertical circuit name LABELs (at tap_x, rotation=90)
    for i, comp in enumerate(components):
        if not (comp.symbol_name == "LABEL" and abs(comp.rotation - 90.0) < 0.1):
            continue
        if comp.label.strip().upper() == "SPARE":
            continue  # Already handled as group anchor
        for g in groups:
            if g.is_spare:
                continue
            if abs(comp.x - g.tap_x) < 6.0 and g.name_label_idx is None:
                g.name_label_idx = i
                break

    # Step 6: Match vertical connections to groups (exclude incoming chain)
    for ci, ((sx, sy), (ex, ey)) in enumerate(connections):
        if abs(sx - ex) > 0.5:
            continue  # Not vertical
        conn_x = sx
        # Skip incoming chain connections
        if abs(conn_x - incoming_chain_x) < _TOL:
            continue
        for g in groups:
            if abs(conn_x - g.tap_x) < _TOL:
                g.connection_indices.append(ci)
                break

    # Step 7: Match junction_dots to groups
    for di, (dx, dy) in enumerate(layout_result.junction_dots):
        for g in groups:
            if abs(dx - g.tap_x) < _TOL and g.junction_dot_idx is None:
                g.junction_dot_idx = di
                break

    # Step 8: Match arrow_points to groups
    for ai, (ax, ay) in enumerate(layout_result.arrow_points):
        for g in groups:
            if abs(ax - g.tap_x) < _TOL and g.arrow_point_idx is None:
                g.arrow_point_idx = ai
                break

    return groups, incoming_chain_x


def _compute_group_width(
    group: SubCircuitGroup,
    components: list[PlacedComponent],
) -> float:
    """
    Compute minimum horizontal space (mm) for a sub-circuit group.

    Also sets group.left_extent and group.right_extent for asymmetric spacing.
    """
    _MARGIN = 2.0  # mm inter-group safety margin per side

    if group.is_spare:
        group.left_extent = 7.5
        group.right_extent = 7.5
        return 15.0  # Spare circuits need minimal space

    if group.breaker_idx is not None:
        comp = components[group.breaker_idx]
        bb = _compute_bounding_box(comp)
        if bb is not None:
            tap_x = group.tap_x
            group.left_extent = (tap_x - bb.x) + _MARGIN
            group.right_extent = (bb.right - tap_x) + _MARGIN
            return group.left_extent + group.right_extent

    group.left_extent = 12.5
    group.right_extent = 12.5
    return 25.0  # Default fallback


def _determine_final_positions(
    groups: list[SubCircuitGroup],
    components: list[PlacedComponent],
    layout_result: LayoutResult,
    config: LayoutConfig,
    incoming_chain_x: float = 0.0,
) -> list[float]:
    """
    Single-pass left-to-right layout of sub-circuit groups.

    Ensures groups don't overlap by spacing them according to their
    computed minimum widths. Fits within drawing bounds, expanding
    the busbar if necessary. Centers groups on the busbar span
    that includes the incoming supply chain connection.

    Returns:
        new_tap_xs: list of final tap_x positions (same order as groups)
    """
    if not groups:
        return []

    _MARGIN = 10.0  # busbar end margin
    _BOUND_MARGIN = 20.0  # distance from drawing edge

    sc_bus_start = layout_result.busbar_start_x + _MARGIN
    sc_bus_end = layout_result.busbar_end_x - _MARGIN

    # Compute total needed span using asymmetric extents:
    # span = left_extent[0] + sum(right_extent[i] + left_extent[i+1]) + right_extent[-1]
    n = len(groups)
    left_exts = [g.left_extent for g in groups]
    right_exts = [g.right_extent for g in groups]
    gap_befores = [g.gap_before for g in groups]

    # Total span = first group's left + all inter-group gaps + last group's right
    total_needed = left_exts[0] + right_exts[-1]
    for i in range(n - 1):
        total_needed += right_exts[i] + left_exts[i + 1]
    # Add category group gaps
    total_needed += sum(gap_befores)

    available = sc_bus_end - sc_bus_start

    # Case B: If needed > available, try expanding busbar
    if total_needed > available:
        # Expand busbar rightward up to drawing bound
        max_bus_end = config.max_x - 15.0
        if layout_result.busbar_end_x < max_bus_end:
            layout_result.busbar_end_x = max_bus_end
            sc_bus_end = max_bus_end - _MARGIN
            available = sc_bus_end - sc_bus_start

        # Also try expanding leftward
        if total_needed > available:
            min_bus_start = config.min_x + 15.0
            if layout_result.busbar_start_x > min_bus_start:
                layout_result.busbar_start_x = min_bus_start
                sc_bus_start = min_bus_start + _MARGIN
                available = sc_bus_end - sc_bus_start

    # If still too tight after expansion, apply proportional compression
    if total_needed > available and total_needed > 0:
        scale = available / total_needed
        left_exts = [e * scale for e in left_exts]
        right_exts = [e * scale for e in right_exts]
        # Enforce minimum 6mm per extent
        left_exts = [max(e, 6.0) for e in left_exts]
        right_exts = [max(e, 6.0) for e in right_exts]

    # Place groups left-to-right using asymmetric extents
    new_tap_xs: list[float] = []

    if n == 1:
        # Single circuit: center on available space
        tap_x = (sc_bus_start + sc_bus_end) / 2
        new_tap_xs.append(tap_x)
    else:
        # First tap: left edge + left_extent[0]
        cursor = sc_bus_start + left_exts[0]
        new_tap_xs.append(cursor)
        # Subsequent taps: gap = right_extent[i] + left_extent[i+1] + category gap
        for i in range(1, n):
            cursor += right_exts[i - 1] + left_exts[i] + gap_befores[i]
            new_tap_xs.append(cursor)

    # Center BUSBAR on the incoming chain x, so the incoming supply
    # enters at the busbar center (don't move the incoming chain itself).
    # Account for asymmetric extents: busbar_center = groups_center + (R - L) / 2
    # where L = leftmost_extent, R = rightmost_extent.
    # So: groups_center = incoming_chain_x - (R - L) / 2 = incoming_chain_x + (L - R) / 2
    if n > 1 and incoming_chain_x:
        groups_center = (new_tap_xs[0] + new_tap_xs[-1]) / 2
        extent_bias = (left_exts[0] - right_exts[-1]) / 2
        target_groups_center = incoming_chain_x + extent_bias
        offset = target_groups_center - groups_center
        if abs(offset) > 0.1:
            new_tap_xs = [t + offset for t in new_tap_xs]

    # Clamp all positions to drawing bounds
    min_tap = config.min_x + _BOUND_MARGIN
    max_tap = config.max_x - _BOUND_MARGIN
    new_tap_xs = [max(min_tap, min(t, max_tap)) for t in new_tap_xs]

    return new_tap_xs


def _rebuild_from_positions(
    groups: list[SubCircuitGroup],
    new_tap_xs: list[float],
    layout_result: LayoutResult,
) -> None:
    """
    Move all group elements to their final positions using stored indices.

    Pure index-based repositioning — no coordinate matching needed.
    Each element is set to its absolute final position, preventing
    double-move bugs entirely.
    """
    components = layout_result.components
    connections = layout_result.connections

    for group, new_tap_x in zip(groups, new_tap_xs):
        delta_x = new_tap_x - group.tap_x

        if abs(delta_x) < 0.01:
            continue  # No movement needed

        # Breaker: shift by delta_x (maintains half_width offset)
        if group.breaker_idx is not None:
            components[group.breaker_idx].x += delta_x

        # CIRCUIT_ID_BOX: set absolute position (centered on tap)
        if group.circuit_id_idx is not None:
            components[group.circuit_id_idx].x = new_tap_x

        # Circuit name LABEL: set absolute position (at tap_x)
        if group.name_label_idx is not None:
            components[group.name_label_idx].x = new_tap_x

        # SPARE LABEL: set absolute position (at tap_x)
        if group.spare_label_idx is not None:
            components[group.spare_label_idx].x = new_tap_x

        # Connections: set both endpoints x to new_tap_x (vertical wires)
        for conn_idx in group.connection_indices:
            (sx, sy), (ex, ey) = connections[conn_idx]
            connections[conn_idx] = ((new_tap_x, sy), (new_tap_x, ey))

        # Junction dot: set x to new_tap_x (busbar tap dot)
        if group.junction_dot_idx is not None:
            _, jy = layout_result.junction_dots[group.junction_dot_idx]
            layout_result.junction_dots[group.junction_dot_idx] = (new_tap_x, jy)

        # Arrow point: set x to new_tap_x (tail end arrow)
        if group.arrow_point_idx is not None:
            _, ay = layout_result.arrow_points[group.arrow_point_idx]
            layout_result.arrow_points[group.arrow_point_idx] = (new_tap_x, ay)


def _fit_busbar_to_groups(
    groups: list[SubCircuitGroup],
    new_tap_xs: list[float],
    layout_result: LayoutResult,
    components: list[PlacedComponent],
    config: LayoutConfig,
    incoming_chain_x: float = 0.0,
) -> None:
    """
    Fit busbar to tightly cover all tap points and the incoming supply chain.

    Both extends AND shrinks the busbar to match the actual span needed.
    Also updates BUSBAR component position and busbar rating LABEL.
    """
    if not new_tap_xs:
        return

    _MARGIN = 2.0  # mm padding beyond outermost element edge

    # Use group extents (already accounts for ditto/non-ditto)
    leftmost_extent = groups[0].left_extent if groups else 10.0
    rightmost_extent = groups[-1].right_extent if groups else 10.0

    # Sub-circuit span including label extents
    sub_start = min(new_tap_xs) - leftmost_extent
    sub_end = max(new_tap_xs) + rightmost_extent

    # Add small margin around sub-circuit span
    needed_start = sub_start - _MARGIN
    needed_end = sub_end + _MARGIN

    # Also ensure incoming chain x is covered (must be on the busbar)
    if incoming_chain_x:
        needed_start = min(needed_start, incoming_chain_x - _MARGIN)
        needed_end = max(needed_end, incoming_chain_x + _MARGIN)

    # Clamp to drawing bounds
    needed_start = max(needed_start, config.min_x)
    needed_end = min(needed_end, config.max_x)

    start_changed = abs(needed_start - layout_result.busbar_start_x) > 0.1
    end_changed = abs(needed_end - layout_result.busbar_end_x) > 0.1

    if not (start_changed or end_changed):
        return

    layout_result.busbar_start_x = needed_start
    layout_result.busbar_end_x = needed_end

    # Update BUSBAR component x to match new start
    if start_changed:
        for comp in components:
            if comp.symbol_name == "BUSBAR" and comp.label:
                comp.x = layout_result.busbar_start_x
                break

    # Update busbar rating LABEL position — left-aligned below busbar
    for comp in components:
        if (comp.symbol_name == "LABEL"
                and "BUSBAR" in (comp.label or "").upper()
                and abs(comp.rotation) < 0.1):
            comp.x = layout_result.busbar_start_x + 3
            break

    # Update DB box dashed connections to match new busbar
    _DB_BOX_MARGIN = 12.0  # enough to cover RCCB + Earth symbol
    new_db_left = max(needed_start - _DB_BOX_MARGIN, config.min_x + 2)
    new_db_right = min(needed_end + _DB_BOX_MARGIN, config.max_x - 2)

    if layout_result.db_box_dashed_indices:
        sy = layout_result.db_box_start_y
        ey = layout_result.db_box_end_y
        dc = layout_result.dashed_connections
        idx = layout_result.db_box_dashed_indices
        # bottom horizontal, top horizontal, left vertical, right vertical
        dc[idx[0]] = ((new_db_left, sy), (new_db_right, sy))
        dc[idx[1]] = ((new_db_left, ey), (new_db_right, ey))
        dc[idx[2]] = ((new_db_left, sy), (new_db_left, ey))
        dc[idx[3]] = ((new_db_right, sy), (new_db_right, ey))

    # Update DB_INFO_BOX position to match new DB box left
    for comp in components:
        if comp.symbol_name == "DB_INFO_BOX":
            comp.x = new_db_left + 3
            break


def _add_cable_leader_lines(
    layout_result: LayoutResult,
    config: LayoutConfig,
) -> None:
    """Add cable spec leader lines AFTER resolve_overlaps.

    Groups sub-circuits by cable_annotation per row, draws a shared horizontal
    leader line with ticker marks at each conductor intersection, and places
    the cable spec text at one end.  Uses final (post-resolve_overlaps)
    positions from SubCircuitGroup.tap_x.

    Multi-row support: each row gets its own leader lines at the correct Y.

    Reference DWG pattern:
      left-most cable group  → text at left end of leader
      right-most cable group → text at right end of leader
    """
    import re
    from collections import OrderedDict

    groups, _ = _identify_groups(layout_result)
    if not groups:
        return

    # DB box top offset from any busbar Y
    db_box_top_offset = (config.db_box_busbar_margin + config.mcb_h + config.stub_len
                         + config.db_box_tail_margin + config.db_box_label_margin)

    # Determine which row each group belongs to (by breaker Y proximity to busbar)
    busbar_ys = layout_result.busbar_y_per_row or [layout_result.busbar_y]

    def _find_row_busbar_y(comp_y: float) -> float:
        """Find the busbar Y closest to this component's Y position."""
        best = busbar_ys[0]
        best_dist = abs(comp_y - best)
        for by in busbar_ys[1:]:
            d = abs(comp_y - by)
            if d < best_dist:
                best = by
                best_dist = d
        return best

    # Collect non-spare groups with cable annotation, tagged by row busbar_y
    cable_entries: list[tuple[float, float, str]] = []  # (row_busbar_y, tap_x, cable_spec)
    for g in groups:
        if g.is_spare or g.breaker_idx is None:
            continue
        comp = layout_result.components[g.breaker_idx]
        if comp.cable_annotation:
            row_by = _find_row_busbar_y(comp.y)
            cable_entries.append((row_by, g.tap_x, comp.cable_annotation))

    if not cable_entries:
        return

    # Group entries by row, then by cable spec within each row
    rows_map: OrderedDict[float, list[tuple[float, str]]] = OrderedDict()
    for row_by, tap_x, cable_spec in cable_entries:
        rows_map.setdefault(row_by, []).append((tap_x, cable_spec))

    tick_size = 1.25  # Half-length of diagonal tick (matches meter board)

    for row_busbar_y, row_entries in rows_map.items():
        # Leader Y for this row
        leader_y = row_busbar_y + db_box_top_offset + config.leader_margin_above_db

        # Group by cable spec within this row
        cable_groups: OrderedDict[str, list[float]] = OrderedDict()
        for tap_x, cable_spec in row_entries:
            cable_groups.setdefault(cable_spec, []).append(tap_x)

        group_keys = list(cable_groups.keys())

        for gi, (cable_spec, tap_xs) in enumerate(cable_groups.items()):
            tap_xs.sort()
            leftmost_x = tap_xs[0]
            rightmost_x = tap_xs[-1]

            # Determine text placement direction
            # First group → left, last group → right,
            # middle groups → alternate (even index left, odd right)
            if gi == 0:
                text_on_left = True
            elif gi == len(group_keys) - 1:
                text_on_left = False
            else:
                text_on_left = (gi % 2 == 0)

            leader_extension = config.leader_extension
            bend_height = config.leader_bend_height

            if text_on_left:
                leader_start_x = leftmost_x - leader_extension
                leader_end_x = rightmost_x
            else:
                leader_start_x = leftmost_x
                leader_end_x = rightmost_x + leader_extension

            # Horizontal leader line
            layout_result.connections.append((
                (leader_start_x, leader_y),
                (leader_end_x, leader_y),
            ))

            # Ticker marks at each conductor intersection
            for tx in tap_xs:
                layout_result.thick_connections.append((
                    (tx - tick_size, leader_y - tick_size),
                    (tx + tick_size, leader_y + tick_size),
                ))

            # L-shaped bend + cable spec text at leader end
            cable_text = cable_spec
            # Split long cable text into 2 lines to avoid exceeding drawing border
            m = re.search(r'\s+(PVC\s+CPC|CPC)\s+IN\s+', cable_text)
            if m:
                cable_text = cable_text[:m.start()] + "\\P" + cable_text[m.start() + 1:]
            if text_on_left:
                bend_top_y = leader_y + bend_height
                layout_result.connections.append((
                    (leader_start_x, leader_y),
                    (leader_start_x, bend_top_y),
                ))
                layout_result.components.append(PlacedComponent(
                    symbol_name="LABEL",
                    x=leader_start_x - 3,
                    y=bend_top_y + 1,
                    label=cable_text,
                    rotation=90.0,
                ))
            else:
                bend_top_y = leader_y + bend_height
                layout_result.connections.append((
                    (leader_end_x, leader_y),
                    (leader_end_x, bend_top_y),
                ))
                layout_result.components.append(PlacedComponent(
                    symbol_name="LABEL",
                    x=leader_end_x,
                    y=bend_top_y + 1,
                    label=cable_text,
                    rotation=90.0,
                ))


def resolve_overlaps(
    layout_result: LayoutResult,
    config: LayoutConfig | None = None,
) -> LayoutResult:
    """
    Post-layout overlap resolution using determine-then-rebuild approach.

    Pipeline:
      1. _identify_groups()          — classify components/connections by sub-circuit
      2. _compute_group_width()      — bounding box based minimum widths
      3. _determine_final_positions() — single-pass left-to-right with bounds fit
      4. _rebuild_from_positions()    — index-based absolute repositioning
      5. _fit_busbar_to_groups() — extent-aware busbar fitting (extend or shrink)

    Guarantees:
      - No text/symbol overlaps between adjacent sub-circuits
      - All connections stay aligned with their breaker symbols
      - All tap points remain within the busbar extent
      - Incoming chain connections are never moved
      - Deterministic: same input → same output
    """
    if config is None:
        config = LayoutConfig()

    # Step 1: Identify sub-circuit groups
    groups, incoming_chain_x = _identify_groups(layout_result)

    if not groups:
        return layout_result

    # Step 1b: Detect category group breaks (S→P, P→H, etc.) for extra spacing
    import re as _re
    _GROUP_GAP = 3.0  # Extra mm between circuit category groups (compact)
    if len(groups) > 1:
        prev_prefix = ""
        for gi, g in enumerate(groups):
            cid = ""
            if g.breaker_idx is not None:
                comp = layout_result.components[g.breaker_idx]
                cid = comp.circuit_id or ""
            cur_match = _re.match(r"[A-Za-z]+", cid)
            cur_prefix = cur_match.group() if cur_match else ""
            if gi > 0 and cur_prefix and prev_prefix and cur_prefix != prev_prefix:
                g.gap_before = _GROUP_GAP
            prev_prefix = cur_prefix

    # Step 1c: Detect ditto groups (identical breaker specs within same category)
    # Ditto MCBs have no breaker labels → compact horizontal extent
    _DITTO_EXTENT = 5.5  # mm — symbol half-width (3.6) + small margin
    breaker_spec_sigs: dict[str, list[int]] = {}  # spec signature → group indices
    for gi, g in enumerate(groups):
        if g.breaker_idx is not None:
            comp = layout_result.components[g.breaker_idx]
            cid = comp.circuit_id or ""
            pfx_match = _re.match(r"[A-Za-z]+", cid)
            pfx = pfx_match.group() if pfx_match else "X"
            sig = f"{pfx}|{comp.breaker_characteristic}|{comp.rating}|{comp.poles}|{comp.breaker_type_str}|{comp.fault_kA}"
            breaker_spec_sigs.setdefault(sig, []).append(gi)
    for sig, gindices in breaker_spec_sigs.items():
        if len(gindices) >= 2:
            for k in range(1, len(gindices)):
                groups[gindices[k]].is_ditto = True

    # Step 2: Compute minimum widths per group
    for g in groups:
        g.min_width = _compute_group_width(g, layout_result.components)

    # Step 2b: Override extents for ditto groups (no labels → compact)
    for g in groups:
        if g.is_ditto:
            g.left_extent = _DITTO_EXTENT
            g.right_extent = _DITTO_EXTENT
            g.min_width = g.left_extent + g.right_extent

    # Step 3: Determine final tap positions
    new_tap_xs = _determine_final_positions(
        groups, layout_result.components, layout_result, config,
        incoming_chain_x=incoming_chain_x,
    )

    # Step 4: Rebuild all positions (index-based, no coordinate matching)
    _rebuild_from_positions(groups, new_tap_xs, layout_result)

    # Step 5: Fit busbar to actual span (extend or shrink)
    _fit_busbar_to_groups(
        groups, new_tap_xs, layout_result, layout_result.components, config,
        incoming_chain_x=incoming_chain_x,
    )

    return layout_result


def compute_layout(requirements: dict, config: LayoutConfig | None = None, application_info: dict | None = None) -> LayoutResult:
    """
    Compute the layout for an SLD based on requirements.

    v6: Bottom-up layout -- incoming supply at bottom, sub-circuits branch upward.

    Args:
        requirements: SLD requirements dict with keys:
            - supply_type: "single_phase" or "three_phase"
            - kva: int
            - main_breaker: {"type": str, "rating"|"rating_A": int,
                             "poles": str, "fault_kA": int}
            - busbar_rating: int
            - sub_circuits: [{"name": str, "breaker_type": str, "breaker_rating": int,
                              "cable": str|dict, "load_kw": float, "phase": str}]
            - metering: str (optional)
            - earth_protection: str (optional)
            - incoming_cable: str|dict (optional)
            - isolator_rating: int (optional)
            - elcb: {"rating": int, "sensitivity_ma": int} (optional)
            - earth_conductor_mm2: float (optional)
    """
    if config is None:
        config = LayoutConfig()

    result = LayoutResult()
    cx = config.start_x
    # Start from BOTTOM -- above title block with clearance for supply label
    y = config.min_y + 15  # ~77mm (extra clearance for 3-line supply label)

    ctx = _LayoutContext(
        result=result,
        config=config,
        cx=cx,
        y=y,
        requirements=requirements,
        application_info=application_info or {},
    )

    _parse_requirements(ctx, requirements, application_info)
    _place_incoming_supply(ctx)
    _place_meter_board(ctx)
    _place_unit_isolator(ctx)
    _place_main_breaker(ctx)
    _place_elcb(ctx)
    _place_main_busbar(ctx)
    busbar_y_row = _place_sub_circuits_rows(ctx)
    resolve_overlaps(ctx.result, ctx.config)
    _add_cable_leader_lines(ctx.result, ctx.config)
    db_box_right = _place_db_box(ctx, busbar_y_row)
    _place_earth_bar(ctx, db_box_right)

    return ctx.result


# -- Section methods for compute_layout() --


def _parse_requirements(ctx: _LayoutContext, requirements: dict, application_info: dict | None) -> None:
    """Parse and normalize all requirement inputs into ctx fields."""
    # -- Normalize input keys (handle alternative key names from agent) --
    supply_type = (requirements.get("supply_type")
                   or requirements.get("system_type")
                   or requirements.get("phase_config", "three_phase"))
    # Normalize: "single_phase" / "1-phase" / "single" → "single_phase"
    if "single" in str(supply_type).lower() or "1" in str(supply_type):
        supply_type = "single_phase"
    else:
        supply_type = "three_phase"

    ctx.supply_type = supply_type
    ctx.supply_source = requirements.get("supply_source", "sp_powergrid")
    ctx.kva = requirements.get("kva", 0)
    ctx.voltage = 400 if supply_type == "three_phase" else 230
    ctx.incoming_cable = requirements.get("incoming_cable", "")

    ctx.result.supply_type = supply_type
    ctx.result.voltage = ctx.voltage

    # -- Read main breaker info early (needed for meter board components) --
    main_breaker = requirements.get("main_breaker", {})
    ctx.breaker_type = str(main_breaker.get("type", "MCCB")).upper()
    ctx.breaker_rating = main_breaker.get("rating", 0) or main_breaker.get("rating_A", 0)
    # Fallback: parse db_rating string (e.g., "63A" → 63)
    if not ctx.breaker_rating:
        db_rating_str = str(requirements.get("db_rating", ""))
        import re
        m = re.match(r"(\d+)", db_rating_str)
        if m:
            ctx.breaker_rating = int(m.group(1))
    ctx.breaker_poles = main_breaker.get("poles", "")
    ctx.breaker_fault_kA = main_breaker.get("fault_kA", 0)

    # Auto-determine poles if not specified (DP = Double Pole, TPN = Triple Pole + Neutral)
    if not ctx.breaker_poles:
        ctx.breaker_poles = "TPN" if supply_type == "three_phase" else "DP"

    # Auto-determine fault level if not specified
    if not ctx.breaker_fault_kA:
        from app.sld.standards import get_fault_level
        ctx.breaker_fault_kA = get_fault_level(ctx.breaker_type, ctx.kva)

    # Auto-determine incoming cable if not specified
    # Uses INCOMING_SPEC / INCOMING_SPEC_3PHASE tables (same pattern as fault_kA)
    if not ctx.incoming_cable and ctx.breaker_rating:
        try:
            import re as _re
            from app.sld.sld_spec import INCOMING_SPEC, INCOMING_SPEC_3PHASE
            spec_table = INCOMING_SPEC_3PHASE if supply_type == "three_phase" else INCOMING_SPEC
            spec = spec_table.get(ctx.breaker_rating)
            # Fallback: try the other table if rating not found
            if spec is None:
                spec = INCOMING_SPEC.get(ctx.breaker_rating)
            if spec:
                # Parse "4 X 1 CORE" → count=4, cores=1 / "1 X 4 CORE" → count=1, cores=4
                _m = _re.match(r"(\d+)\s*X\s*(\d+)\s*CORE", spec.cable_cores)
                _count = int(_m.group(1)) if _m else 1
                _cores = int(_m.group(2)) if _m else 1
                # For single-core cables: SLD convention shows main conductors only
                # (CPC is specified separately as "+ Xsqmm CPC")
                # SP: L + N = 2 main conductors / 3P: L1 + L2 + L3 + N = 4
                if _cores == 1:
                    _count = 2 if supply_type == "single_phase" else 4
                ctx.incoming_cable = {
                    "count": _count,
                    "cores": _cores,
                    "size_mm2": spec.cable_size.split(" + ")[0].replace("mmsq E", "").strip(),
                    "type": spec.cable_type,
                    "cpc_mm2": spec.cable_size.split(" + ")[1].replace("mmsq E", "").strip()
                                if " + " in spec.cable_size else "",
                    "cpc_type": spec.cable_type.split("/")[-1] if "/" in spec.cable_type else "PVC",
                }
        except Exception:
            pass  # Graceful fallback — cable annotation simply won't appear

    ctx.meter_poles = "DP" if supply_type == "single_phase" else "TPN"

    # Main breaker characteristic (B/C/D) — IEC 60898-1 trip curve
    # Accept multiple key names: breaker_characteristic, characteristic, breaker_char, char
    ctx.main_breaker_char = str(
        main_breaker.get("breaker_characteristic", "")
        or main_breaker.get("characteristic", "")
        or main_breaker.get("breaker_char", "")
        or main_breaker.get("char", "")
    ).upper()

    # Metering type
    if ctx.supply_source == "landlord":
        ctx.metering = requirements.get("metering", None)
    else:
        ctx.metering = requirements.get("metering", "sp_meter")

    # Read ELCB config early (needed for inline placement before busbar)
    ctx.elcb_config = requirements.get("elcb", {})
    ctx.elcb_rating = ctx.elcb_config.get("rating", 0) if isinstance(ctx.elcb_config, dict) else 0
    ctx.elcb_ma = ctx.elcb_config.get("sensitivity_ma", 30) if isinstance(ctx.elcb_config, dict) else 30
    ctx.elcb_type_str = (
        ctx.elcb_config.get("type", "ELCB").upper()
        if isinstance(ctx.elcb_config, dict) else "ELCB"
    )

    # Sub-circuits and busbar rating
    ctx.sub_circuits = requirements.get("sub_circuits", []) or requirements.get("circuits", [])
    ctx.busbar_rating = requirements.get("busbar_rating", 0)
    if not ctx.busbar_rating:
        # Per SG standard: minimum 100A COMB BUSBAR for installations ≤ 100A
        ctx.busbar_rating = max(100, ctx.breaker_rating)


def _place_incoming_supply(ctx: _LayoutContext) -> None:
    """Place incoming supply label, AC symbol, phase lines, and cable annotation.

    For metered supply (SP PowerGrid): skip all visuals here.
    The meter board handles supply entry from the RIGHT side with its own
    INCOMING SUPPLY label. No AC symbol or phase lines needed at the bottom.

    For non-metered supply (landlord): place AC symbol, phase lines, and label.
    """
    result = ctx.result
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    supply_source = ctx.supply_source
    kva = ctx.kva
    voltage = ctx.voltage
    metering = ctx.metering

    # For metered supply, _place_meter_board() handles everything.
    # No AC symbol, phase lines, or labels needed at the bottom.
    if metering:
        ctx.y = y
        return

    # --- Non-metered supply (landlord) only below ---
    supply_label = "FROM LANDLORD SUPPLY"
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=cx - 80,
        y=y + 8,
        label=supply_label,
    ))

    # AC supply symbol "~" (wave sign at bottom — Singapore LEW convention)
    result.components.append(PlacedComponent(
        symbol_name="FLOW_ARROW_UP",
        x=cx,
        y=y - 3,
    ))

    # Phase lines with labels (at bottom, pointing upward) — compact layout
    ph_half = 3
    if supply_type == "three_phase":
        spacing = 4
        for offset, label in [(-spacing*1.5, "L1"), (-spacing*0.5, "L2"),
                               (spacing*0.5, "L3"), (spacing*1.5, "N")]:
            result.connections.append(((cx + offset, y - ph_half), (cx + offset, y + ph_half)))
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=cx + offset - 2,
                y=y - ph_half - 3,
                label=label,
            ))
        result.connections.append(((cx - spacing * 1.5, y + ph_half), (cx + spacing * 1.5, y + ph_half)))
        result.connections.append(((cx, y + ph_half), (cx, y + ph_half + 4)))
    else:
        result.connections.append(((cx, y - ph_half), (cx, y + ph_half)))
        result.connections.append(((cx, y + ph_half), (cx, y + ph_half + 4)))
    y += ph_half + 4

    # Incoming cable annotation
    incoming_cable = ctx.incoming_cable
    cable_text = format_cable_spec(incoming_cable)
    if cable_text:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=cx + 12,
            y=y - 3,
            label=cable_text,
        ))

    ctx.y = y


def _place_meter_board(ctx: _LayoutContext) -> None:
    """Place meter board section HORIZONTALLY: [ISO]--[KWH]--[MCB] on same Y line.

    Horizontal layout matching professional LEW drawings:
    - All three components on the SAME horizontal line (same Y center)
    - Isolator on the LEFT, KWH in CENTER, MCB on the RIGHT
    - Connected by horizontal line segments between each component
    - Dashed box around the whole meter board
    - "METER BOARD / LOCATED AT / METER COMPARTMENT" label below the box

    Routing from the main vertical spine (cx):
    - From (cx, y) down-left to Isolator input (left side)
    - Through Isolator -> horizontal to KWH -> horizontal to MCB
    - From MCB output (right side) up-right back to cx
    - Continue up from cx

    Layout diagram:
                cx
                |
      +---------+  <- MCB output routes back to cx
      |         |
      [ISO]--[KWH]--[MCB]    <- horizontal meter board
      |         |
      +---------+  <- cx routes to ISO input
                |
           (from below)
    """
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    metering = ctx.metering
    breaker_rating = ctx.breaker_rating
    meter_poles = ctx.meter_poles

    # -- 2. Meter Board Section (SP PowerGrid standard) --
    # Contains: Meter Isolator + [CT for ct_meter] + KWH Meter + Meter MCB TYPE C
    # Located at the building's meter compartment
    # Skipped for landlord supply (no SP metering required)

    if metering:
        # ================================================================
        # METER BOARD — Horizontal layout: [ISO]--[KWH]--[MCB]
        #
        # PRINCIPLE: NO element may overlap another.
        # Vertical bands are calculated explicitly, bottom-up:
        #
        #   Band 7: ---- box top line ----          mb_box_top
        #   Band 6: "KWH METER BY SP" label         kwh_label_y (text top)
        #   Band 5: (gap 1.5mm)
        #   Band 4: Symbol tops (ISO ±4.0)           mb_center_y + iso_v_half
        #   Band 3: === SYMBOLS at mb_center_y ===   mb_center_y
        #   Band 2: Symbol bottoms                   mb_center_y - iso_v_half
        #   Band 1: (gap 1.5mm)
        #   Band 0: Component labels (2 lines)       comp_label_y (text top)
        #   ---- (gap 2mm) ----
        #   "METER BOARD" label                      mb_label_y (text top)
        #   ---- (gap 1mm) ----
        #   ---- box bottom line ----               mb_box_bottom
        # ================================================================

        # -- Horizontal layout parameters --
        comp_spacing = 25  # 25mm between component centers
        _stub = config.stub_len  # Synced from real_symbol_paths.json (single source of truth)
        _mb_inset = 4      # Extra inset to push components away from spine (cx)

        # -- Component horizontal extents (symbol.height → h_extent when rotated 90°) --
        iso_h_extent = config.isolator_h       # from JSON: ISOLATOR.height_mm
        kwh_h_extent = config.kwh_rect_w       # from JSON: KWH rect width (horizontal span)
        mcb_h_extent = config.mcb_h            # from JSON: MCB.height_mm

        # -- Component vertical half-extents (symbol.width/2 → v_half when rotated 90°) --
        iso_v_half = config.isolator_w / 2     # from JSON: ISOLATOR.width_mm / 2
        kwh_v_half = config.kwh_rect_h / 2     # from JSON: KWH rect height / 2
        mcb_v_half = config.mcb_w / 2          # from JSON: MCB.width_mm / 2
        max_v_half = max(iso_v_half, kwh_v_half, mcb_v_half)

        # -- Text sizes (must match generator.py) --
        _comp_label_ch = 1.6     # Horizontal component label char_height
        _comp_label_lines = 2    # Most labels are 2 lines (e.g. "40A DP\nISOLATOR")
        _comp_label_lsp = 1.4    # Line spacing factor
        _comp_label_h = _comp_label_ch * _comp_label_lsp * _comp_label_lines  # ~4.5mm
        _anno_label_ch = 2.8     # LABEL component char_height (generator.py)

        # -- Component centers (horizontal positions) --
        iso_cx = cx + iso_h_extent / 2 + _stub + _mb_inset  # Pushed right for box padding
        kwh_cx = iso_cx + comp_spacing             # KWH in center
        mcb_cx = iso_cx + 2 * comp_spacing         # MCB on the right

        # -- Vertical center --
        mb_center_y = y + 8

        # -- X pin positions --
        iso_left_x = cx + _mb_inset  # ISO left pin (shifted right by inset)
        iso_right_x = iso_cx + iso_h_extent / 2 + _stub
        mcb_left_x = mcb_cx - mcb_h_extent / 2 - _stub
        mcb_right_x = mcb_cx + mcb_h_extent / 2 + _stub

        # ================================================================
        # VERTICAL BAND CALCULATION (no overlaps guaranteed)
        # ================================================================

        # ABOVE center: symbol top → gap → KWH label → gap → box top
        _gap = 1.5
        kwh_label_y = mb_center_y + max_v_half + _gap + _anno_label_ch  # text TOP
        mb_box_top = kwh_label_y + _gap

        # BELOW center: symbol bottom → gap → comp labels → gap → MB label → gap → box bottom
        comp_label_y = mb_center_y - max_v_half - _gap   # text TOP (extends down)
        comp_label_bot = comp_label_y - _comp_label_h     # text BOTTOM
        mb_label_y = comp_label_bot - 2                   # "METER BOARD" text TOP (2mm gap)
        mb_label_bot = mb_label_y - _anno_label_ch        # text BOTTOM
        mb_box_bottom = mb_label_bot - 1                  # 1mm padding below

        # -- Box horizontal extent (wraps components only, not spine at cx) --
        iso_body_left = iso_cx - iso_h_extent / 2  # ISO body left edge
        mb_box_left = iso_body_left - 4            # 4mm padding left of ISO body
        mb_box_right = mcb_right_x + 4             # 4mm padding right of MCB

        # ====== ROUTING: Spine connection at meter board level ======
        # Non-metered (landlord): entry from below connects to spine
        if not ctx.metering:
            result.connections.append(((cx, y), (cx, mb_center_y)))
        # Horizontal branch from spine to ISO left pin (if gap exists)
        if iso_left_x > cx:
            result.connections.append(((cx, mb_center_y), (iso_left_x, mb_center_y)))

        # ====== Place ISOLATOR on LEFT ======
        result.components.append(PlacedComponent(
            symbol_name="ISOLATOR",
            x=iso_cx - iso_h_extent / 2,
            y=mb_center_y,
            label=f"{breaker_rating}A {meter_poles}",
            rating="ISOLATOR",
            rotation=90.0,
        ))
        result.symbols_used.add("ISOLATOR")

        # ====== Connection: ISO right -> KWH left ======
        kwh_left_x = kwh_cx - kwh_h_extent / 2 - _stub
        result.connections.append(((iso_right_x, mb_center_y), (kwh_left_x, mb_center_y)))

        # ====== CT metering (between ISO and KWH) — only for ct_meter ======
        if metering == "ct_meter":
            ct_mid_x = (iso_cx + kwh_cx) / 2
            ct_r = config.ct_size / 2
            result.components.append(PlacedComponent(
                symbol_name="CT",
                x=ct_mid_x - ct_r,
                y=mb_center_y - ct_r,
                label="CT BY SP",
            ))
            result.symbols_used.add("CT")

        # ====== Place KWH METER in CENTER ======
        result.components.append(PlacedComponent(
            symbol_name="KWH_METER",
            x=kwh_cx,
            y=mb_center_y,
            rotation=90.0,
        ))
        result.symbols_used.add("KWH_METER")

        # ====== "KWH METER BY SP" label — above symbols, inside box ======
        kwh_label_x = (iso_cx + mcb_cx) / 2 - 10
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=kwh_label_x,
            y=kwh_label_y,
            label="KWH METER BY SP",
        ))

        # ====== Connection: KWH right -> MCB left ======
        kwh_right_x = kwh_cx + kwh_h_extent / 2 + _stub
        result.connections.append(((kwh_right_x, mb_center_y), (mcb_left_x, mb_center_y)))

        # ====== Place MCB on RIGHT ======
        result.components.append(PlacedComponent(
            symbol_name="CB_MCB",
            x=mcb_cx - mcb_h_extent / 2,
            y=mb_center_y,
            label=f"{breaker_rating}A {meter_poles} MCB",
            rating="10kA TYPE C",
            rotation=90.0,
        ))
        result.symbols_used.add("MCB")

        # ====== ROUTING: Supply entry from RIGHT ======
        # Horizontal supply line from MCB to entry point
        supply_ext = 20
        supply_end_x = mcb_right_x + supply_ext
        result.connections.append(((mcb_right_x, mb_center_y), (supply_end_x, mb_center_y)))

        # INCOMING label — reference format: "INCOMING FROM HDB ELECTRICAL RISER"
        supply_label = ctx.requirements.get(
            "incoming_label", "INCOMING FROM HDB ELECTRICAL RISER"
        )
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=supply_end_x + 3,
            y=mb_center_y + 3,
            label=supply_label,
        ))

        # Cable annotation with tick mark + leader line (matching reference)
        # Pattern:  ──╱──  tick mark on the supply line
        #             |    leader line going down from tick center
        #             └── cable spec text
        incoming_cable = ctx.incoming_cable
        cable_text = format_cable_spec(incoming_cable, multiline=True)
        if cable_text:
            # Tick mark position: midpoint of supply line
            tick_x = (mcb_right_x + supply_end_x) / 2
            tick_size = 1.5  # Half-length of diagonal tick (thinner, shorter)
            # Diagonal tick mark crossing the supply line (~45 degrees)
            # RIGHT side incoming: thinner tick (regular connections)
            result.connections.append((
                (tick_x - tick_size, mb_center_y - tick_size),
                (tick_x + tick_size, mb_center_y + tick_size),
            ))
            # Leader line going DOWN from tick center (on supply line)
            leader_len = 10
            leader_bottom_y = mb_center_y - leader_len
            result.connections.append((
                (tick_x, mb_center_y),
                (tick_x, leader_bottom_y),
            ))
            # Horizontal shelf to the right
            shelf_len = 3
            result.connections.append((
                (tick_x, leader_bottom_y),
                (tick_x + shelf_len, leader_bottom_y),
            ))
            # Cable spec text at end of shelf
            # mtext insert = top-left; center first line on shelf
            _label_ch = 2.8
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=tick_x + shelf_len + 1,
                y=leader_bottom_y + _label_ch * 0.5,
                label=cable_text,
            ))

        # ====== ROUTING: Exit — straight up to MCCB ======
        # Outgoing cable annotation (meter board → DB) — LEFT side tick mark
        outgoing_cable = ctx.incoming_cable
        outgoing_cable_text = format_cable_spec(outgoing_cable, multiline=True)

        if outgoing_cable_text:
            y_exit = mb_box_top + 16  # Extra room for cable annotation
        else:
            y_exit = mb_box_top + 8   # Normal gap
        result.connections.append(((cx, mb_center_y), (cx, y_exit)))

        # Cable annotation on outgoing vertical line (meter board → DB)
        # Reference: tick mark on vertical wire + leader LEFT + cable spec text
        # LEFT side outgoing tick: THICKER than incoming tick (standard cable tick style)
        # This thick tick style is the standard for all non-incoming cables
        if outgoing_cable_text:
            # Tick mark position: midpoint of gap above meter board box
            tick_y = (mb_box_top + y_exit) / 2
            tick_size = 1.25  # Standard cable tick (half-length of diagonal)
            # Diagonal tick crossing vertical line (/ shape)
            # Use thick_connections for heavier line weight
            result.thick_connections.append((
                (cx - tick_size, tick_y - tick_size),
                (cx + tick_size, tick_y + tick_size),
            ))
            # Leader line going LEFT from tick center
            _leader_len = 3
            result.connections.append((
                (cx, tick_y),
                (cx - _leader_len, tick_y),
            ))
            # Cable spec text — positioned to the LEFT of leader
            # Text is LEFT-aligned (TOP_LEFT), so offset start position
            # to the left by approximate text width
            _label_ch = 2.8
            _char_w = _label_ch * 0.6  # Approximate Helvetica char width
            _lines = outgoing_cable_text.split("\\P")
            _max_line_len = max(len(ln) for ln in _lines) if _lines else 20
            _text_width = _max_line_len * _char_w
            _text_x = cx - _leader_len - 1 - _text_width
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=_text_x,
                y=tick_y + _label_ch * 0.5,
                label=outgoing_cable_text,
            ))

        # ====== Dashed box ======
        result.dashed_connections.append(((mb_box_left, mb_box_bottom), (mb_box_right, mb_box_bottom)))
        result.dashed_connections.append(((mb_box_left, mb_box_top), (mb_box_right, mb_box_top)))
        result.dashed_connections.append(((mb_box_left, mb_box_bottom), (mb_box_left, mb_box_top)))
        result.dashed_connections.append(((mb_box_right, mb_box_bottom), (mb_box_right, mb_box_top)))

        # ====== "METER BOARD" label — inside box, bottom-left ======
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=mb_box_left + 1,
            y=mb_label_y,
            label="METER BOARD",
        ))
        # "LOCATED AT METER COMPARTMENT" — below the box
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=(mb_box_left + mb_box_right) / 2 - 18,
            y=mb_box_bottom - 3,
            label="LOCATED AT METER COMPARTMENT",
        ))

        y = y_exit

    ctx.y = y


def _place_unit_isolator(ctx: _LayoutContext) -> None:
    """Place unit isolator (for ct_meter, landlord supply, or explicitly specified)."""
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_source = ctx.supply_source
    breaker_rating = ctx.breaker_rating
    meter_poles = ctx.meter_poles
    metering = ctx.metering
    requirements = ctx.requirements

    # -- 3. Unit Isolator (for ct_meter, landlord supply, or explicitly specified) --
    _iso_w = 8.0  # Isolator symbol width (from real_symbols) — needed for centering
    isolator_rating = requirements.get("isolator_rating", 0)
    isolator_label_extra = requirements.get("isolator_label", "")

    # Landlord supply: always include isolator (regardless of kVA)
    if supply_source == "landlord":
        if not isolator_rating and breaker_rating:
            isolator_rating = breaker_rating  # Same rating as main breaker
        if not isolator_label_extra:
            isolator_label_extra = "LOCATED INSIDE UNIT"
    elif not isolator_rating and metering == "ct_meter":
        if breaker_rating:
            isolator_rating = _next_standard_rating(breaker_rating)

    if isolator_rating:
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        iso_main_label = f"{isolator_rating}A {meter_poles} ISOLATOR"
        iso_rating_text = (
            f"({isolator_label_extra})" if isolator_label_extra else "ISOLATOR"
        )
        result.components.append(PlacedComponent(
            symbol_name="ISOLATOR",
            x=cx - _iso_w / 2,  # Center horizontally using width (not height!)
            y=y,
            label=iso_main_label,
            rating=iso_rating_text,
        ))
        y += config.isolator_h + 2
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        result.symbols_used.add("ISOLATOR")

    ctx.y = y


def _place_main_breaker(ctx: _LayoutContext) -> None:
    """Place main circuit breaker and set db_box_start_y."""
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    breaker_type = ctx.breaker_type
    breaker_rating = ctx.breaker_rating
    breaker_poles = ctx.breaker_poles
    breaker_fault_kA = ctx.breaker_fault_kA
    main_breaker_char = ctx.main_breaker_char

    # -- 4. Main Circuit Breaker --
    # Add gap so outgoing cable annotation (tick mark + text) from meter board
    # stays OUTSIDE (below) the DB dashed box
    result.connections.append(((cx, y), (cx, y + 10)))
    y += 10
    ctx.db_box_start_y = y - 1  # Track DB box bottom (below main breaker, above cable annotation)

    if breaker_type == "ACB":
        cb_w, cb_h = 16, 22
    elif breaker_type == "MCB":
        cb_w, cb_h = config.mcb_w, config.mcb_h
    else:
        cb_w, cb_h = config.breaker_w, config.breaker_h

    cb_symbol = f"CB_{breaker_type}"
    # Singapore SLD format:
    #   Line 1: "63A DP MCB"  (rating + poles + type)
    #   Line 2: "TYPE B 10kA" (characteristic + fault level)
    main_label = f"{breaker_rating}A {breaker_poles} {breaker_type}"
    if main_breaker_char:
        main_rating = f"TYPE {main_breaker_char} {breaker_fault_kA}kA"
    else:
        main_rating = f"{breaker_fault_kA}kA"
    result.components.append(PlacedComponent(
        symbol_name=cb_symbol,
        x=cx - cb_w / 2,
        y=y,
        label=main_label,
        rating=main_rating,
    ))
    y += cb_h + config.stub_len  # height + stub — continuous connection to next component
    result.connections.append(((cx, y), (cx, y + 3)))
    y += 3
    result.symbols_used.add(breaker_type)

    ctx.y = y


def _place_elcb(ctx: _LayoutContext) -> None:
    """Place ELCB/RCCB inline between main breaker and busbar (conditional)."""
    if not ctx.elcb_rating:
        return

    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    elcb_config = ctx.elcb_config
    elcb_rating = ctx.elcb_rating
    elcb_ma = ctx.elcb_ma
    elcb_type_str = ctx.elcb_type_str

    # -- 4a. ELCB/RCCB (inline between Main Breaker and Busbar per LEW guide) --
    elcb_symbol = "CB_RCCB" if elcb_type_str == "RCCB" else "CB_ELCB"
    elcb_w, elcb_h = config.rccb_w, config.rccb_h  # ELCB/RCCB dims (wider due to RCD bar)
    elcb_poles_raw = elcb_config.get("poles", "") if isinstance(elcb_config, dict) else ""
    if isinstance(elcb_poles_raw, str) and elcb_poles_raw.upper() in ("DP", "SP", "TPN", "4P"):
        elcb_poles_str = elcb_poles_raw.upper()
    elif isinstance(elcb_poles_raw, int):
        elcb_poles_str = {1: "SP", 2: "DP", 3: "TPN", 4: "4P"}.get(elcb_poles_raw, "DP")
    else:
        # Default based on supply type: DP for single-phase, 4P for three-phase
        elcb_poles_str = "DP" if supply_type == "single_phase" else "4P"

    result.components.append(PlacedComponent(
        symbol_name=elcb_symbol,
        x=cx - elcb_w / 2,
        y=y,
        label=f"{elcb_rating}A {elcb_poles_str} {elcb_type_str}",
        rating=f"({elcb_ma}mA)",
    ))
    y += elcb_h + config.stub_len  # height + stub — continuous connection to next component
    result.connections.append(((cx, y), (cx, y + 3)))
    y += 3
    result.symbols_used.add(elcb_type_str)

    ctx.y = y


def _place_main_busbar(ctx: _LayoutContext) -> None:
    """Place main busbar, DB info box, and busbar rating label."""
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    voltage = ctx.voltage
    kva = ctx.kva
    breaker_rating = ctx.breaker_rating
    elcb_rating = ctx.elcb_rating
    busbar_rating = ctx.busbar_rating
    sub_circuits = ctx.sub_circuits
    application_info = ctx.application_info

    # -- 5. Main Busbar --
    num_circuits = max(len(sub_circuits), 1)

    h_spacing = config.horizontal_spacing
    if num_circuits > config.max_circuits_per_row:
        effective_count = config.max_circuits_per_row
    else:
        effective_count = num_circuits

    max_bus_width = config.max_x - config.min_x - 40
    desired_width = effective_count * h_spacing + 2 * config.busbar_margin
    if desired_width > max_bus_width:
        h_spacing = max((max_bus_width - 2 * config.busbar_margin) / effective_count,
                        config.min_horizontal_spacing)
        desired_width = effective_count * h_spacing + 2 * config.busbar_margin

    bus_width = max(desired_width, 140)
    bus_start_x = cx - bus_width / 2
    bus_end_x = cx + bus_width / 2

    if bus_start_x < config.min_x:
        bus_start_x = config.min_x
        bus_end_x = bus_start_x + bus_width
    if bus_end_x > config.max_x:
        bus_end_x = config.max_x
        bus_start_x = bus_end_x - bus_width

    result.busbar_y = y
    result.busbar_start_x = bus_start_x
    result.busbar_end_x = bus_end_x

    busbar_label = (
        f"{busbar_rating}A COMB BUSBAR"
        if busbar_rating <= 500
        else f"{busbar_rating}A BUSBAR"
    )
    result.components.append(PlacedComponent(
        symbol_name="BUSBAR",
        x=bus_start_x,
        y=y,
        label=f"{breaker_rating}A DB",
        rating="",
    ))
    # -- DB Info: compute text, defer placement to _place_db_box() --
    if kva:
        approved_kva = kva
    elif supply_type == "three_phase":
        approved_kva = round(breaker_rating * voltage * 1.732 / 1000, 1)
    else:
        approved_kva = round(breaker_rating * voltage / 1000, 1)

    premises_addr = ""
    if application_info:
        premises_addr = application_info.get("address", "")

    db_info_text = f"APPROVED LOAD: {approved_kva}KVA AT {voltage}V"
    if premises_addr:
        db_info_text += f"\\PLOCATED AT {premises_addr}"

    # Store in ctx — will be placed at DB box bottom-left by _place_db_box()
    ctx.db_info_label = f"{breaker_rating}A DB"
    ctx.db_info_text = db_info_text

    # Busbar rating label — left-aligned below busbar (per reference DWG)
    busbar_label_x = bus_start_x + 3  # 3mm from busbar left edge
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=busbar_label_x,
        y=y - 3,
        label=busbar_label,
    ))

    # Connection from main breaker to busbar
    result.connections.append(((cx, y - 3), (cx, y)))


def _place_sub_circuits_rows(ctx: _LayoutContext) -> float:
    """Place sub-circuit rows branching upward from busbar. Returns busbar_y_row."""
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y
    supply_type = ctx.supply_type
    sub_circuits = ctx.sub_circuits

    # -- 6. Sub-circuits (branching UPWARD) --
    # Pre-assign circuit IDs (S/P for single-phase, L1P1/L2P1 for 3-phase)
    circuit_ids = _assign_circuit_ids(sub_circuits, supply_type)

    num_circuits = max(len(sub_circuits), 1)
    h_spacing = config.horizontal_spacing
    if num_circuits > config.max_circuits_per_row:
        effective_count = config.max_circuits_per_row
    else:
        effective_count = num_circuits

    max_bus_width = config.max_x - config.min_x - 40
    desired_width = effective_count * h_spacing + 2 * config.busbar_margin
    if desired_width > max_bus_width:
        h_spacing = max((max_bus_width - 2 * config.busbar_margin) / effective_count,
                        config.min_horizontal_spacing)

    bus_start_x = result.busbar_start_x
    bus_end_x = result.busbar_end_x

    rows = _split_into_rows(sub_circuits, config.max_circuits_per_row)

    busbar_y_row = y  # Default for single-row case

    for row_idx, row_circuits in enumerate(rows):
        row_count = len(row_circuits)
        if row_idx == 0:
            busbar_y_row = y
            row_bus_start = bus_start_x
            row_bus_end = bus_end_x
        else:
            busbar_y_row = y + (row_idx) * config.row_spacing
            row_bus_width = row_count * h_spacing + 2 * config.busbar_margin
            row_bus_start = cx - row_bus_width / 2
            row_bus_end = cx + row_bus_width / 2

            result.components.append(PlacedComponent(
                symbol_name="BUSBAR",
                x=row_bus_start,
                y=busbar_y_row,
                label="",
                rating="",
            ))
            result.busbar_start_x = min(result.busbar_start_x, row_bus_start)
            result.busbar_end_x = max(result.busbar_end_x, row_bus_end)
            # Vertical connection between rows
            result.connections.append(((cx, y + 2), (cx, busbar_y_row)))

        sc_bus_start = row_bus_start
        result.busbar_y_per_row.append(busbar_y_row)

        _place_sub_circuits_upward(
            result, row_circuits, row_idx, row_count,
            busbar_y_row, sc_bus_start, row_bus_end,
            h_spacing, config, sub_circuits, supply_type, circuit_ids,
        )

    return busbar_y_row


def _place_db_box(ctx: _LayoutContext, busbar_y_row: float) -> float:
    """Place DB box (dashed rectangle around distribution board). Returns db_box_right."""
    result = ctx.result
    config = ctx.config
    # Original DB box bottom (at main breaker level)
    text_anchor_y = ctx.db_box_start_y

    # Expand DB box bottom to accommodate DB info text below the main breaker.
    # Text layout (top→bottom): "40A DB" (char 3.0→~4mm) + gap(1) + info lines (char 1.8→~3mm each)
    db_info_lines = ctx.db_info_text.count("\\P") + 1 if ctx.db_info_text else 0
    db_info_height = 5 + db_info_lines * 3  # title(5mm) + each info line(3mm)
    db_box_start_y = text_anchor_y - db_info_height

    # -- 6a. DB Box (DASHED rectangle around distribution board per reference DWG) --
    # Encompasses: main breaker, ELCB/RCCB, busbar, and all sub-circuit breakers
    db_box_end_y = (busbar_y_row + config.db_box_busbar_margin
                    + config.mcb_h + config.stub_len
                    + config.db_box_tail_margin + config.db_box_label_margin)
    db_box_left = result.busbar_start_x - 10   # Extra margin for leftmost circuit labels
    db_box_right = result.busbar_end_x + 10    # Extra margin for rightmost circuit labels
    # Clamp to drawing bounds
    db_box_left = max(db_box_left, config.min_x + 2)
    db_box_right = min(db_box_right, config.max_x - 2)

    # Store DB box y-range for later update by resolve_overlaps
    result.db_box_start_y = db_box_start_y
    result.db_box_end_y = db_box_end_y

    # DB Box — DASHED rectangle (matching reference DWG CENTER linetype)
    # Four sides of dashed rectangle — store indices for later update
    base_idx = len(result.dashed_connections)
    result.dashed_connections.append(((db_box_left, db_box_start_y), (db_box_right, db_box_start_y)))
    result.dashed_connections.append(((db_box_left, db_box_end_y), (db_box_right, db_box_end_y)))
    result.dashed_connections.append(((db_box_left, db_box_start_y), (db_box_left, db_box_end_y)))
    result.dashed_connections.append(((db_box_right, db_box_start_y), (db_box_right, db_box_end_y)))
    result.db_box_dashed_indices = [base_idx, base_idx + 1, base_idx + 2, base_idx + 3]

    # "40A DB" + "APPROVED LOAD" labels — bottom-left inside DB box (per reference DWG)
    # Text anchored to ORIGINAL main breaker position so it stays above the expanded box bottom
    if ctx.db_info_label:
        result.components.append(PlacedComponent(
            symbol_name="DB_INFO_BOX",
            x=db_box_left + 3,
            y=text_anchor_y + 8,  # Fixed to original position, not expanded box bottom
            label=ctx.db_info_label,
            rating=ctx.db_info_text,
        ))

    return db_box_right


def _place_earth_bar(ctx: _LayoutContext, db_box_right: float) -> None:
    """Place earth bar symbol, conductor label, and connections."""
    result = ctx.result
    config = ctx.config
    requirements = ctx.requirements

    # -- 7. Earth Bar (outside DB box, right side) --
    # RealEarth symbol dimensions: width=12mm, height=10mm (from real_symbol_paths.json)
    from app.sld.real_symbols import get_symbol_dimensions
    _earth_dims = get_symbol_dimensions("EARTH")
    _earth_w = _earth_dims["width_mm"]   # 12
    _earth_h = _earth_dims["height_mm"]  # 10

    earth_x = db_box_right + config.earth_x_from_db
    earth_y = result.busbar_y - config.earth_y_below_busbar

    # Earth conductor size annotation (calculate early for boundary check)
    earth_conductor_mm2 = requirements.get("earth_conductor_mm2", 0)
    if not earth_conductor_mm2:
        inc_cable = requirements.get("incoming_cable", {})
        if isinstance(inc_cable, dict):
            inc_size = inc_cable.get("size_mm2", 0)
        else:
            inc_size = 0
        if inc_size:
            from app.sld.standards import get_earth_conductor_size
            earth_conductor_mm2 = get_earth_conductor_size(inc_size)

    # -- Boundary check: ensure earth + labels fit within drawing border --
    _CHAR_W = 1.8  # Approximate character width in mm at char_height 2.3
    earth_label_right = earth_x + _earth_w + 3 + 2  # symbol + gap + "E" text width
    if earth_conductor_mm2:
        conductor_label = f"1 x {earth_conductor_mm2}sqmm CU/GRN-YEL"
        conductor_label_right = earth_x + len(conductor_label) * _CHAR_W
        earth_rightmost = max(earth_label_right, conductor_label_right)
    else:
        earth_rightmost = earth_label_right

    border_right = config.max_x + 10  # Drawing border at A3 width - margin (~410mm)
    if earth_rightmost > border_right - 3:
        shift = earth_rightmost - (border_right - 3)
        earth_x = earth_x - shift
        # Maintain minimum 3mm gap from DB box
        earth_x = max(earth_x, db_box_right + config.earth_x_from_db - 2)

    result.components.append(PlacedComponent(
        symbol_name="EARTH",
        x=earth_x,
        y=earth_y,
        label="E",
    ))
    result.symbols_used.add("EARTH")

    if earth_conductor_mm2:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=earth_x,
            y=earth_y - 5,
            label=f"1 x {earth_conductor_mm2}sqmm CU/GRN-YEL",
        ))

    # Solid earth conductor -- from DB box right wall to earth bar (outside DB box)
    earth_cx = earth_x + _earth_w / 2  # Center of earth symbol
    earth_top_pin_y = earth_y + _earth_h  # top pin at y + height
    # Horizontal: DB box right wall → earth bar center X (at earth bar top pin level)
    result.connections.append(((db_box_right, earth_top_pin_y),
                               (earth_cx, earth_top_pin_y)))
    # Junction dot at DB box right wall (connection point indicator)
    result.junction_dots.append((db_box_right, earth_top_pin_y))


# -- Helper functions --

def _split_into_rows(sub_circuits: list[dict], max_per_row: int) -> list[list[dict]]:
    """Split sub-circuits into rows of max_per_row each."""
    if not sub_circuits:
        return [[]]
    rows = []
    for i in range(0, len(sub_circuits), max_per_row):
        rows.append(sub_circuits[i:i + max_per_row])
    return rows


def _next_standard_rating(current: int) -> int:
    """Get the next standard breaker rating above the given value."""
    standard = [16, 20, 25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000]
    for r in standard:
        if r >= current:
            return r
    return standard[-1]


def _assign_circuit_ids(sub_circuits: list[dict], supply_type: str) -> list[str]:
    """
    Pre-assign circuit IDs based on Singapore SLD conventions.

    Single-phase: S1, S2 (lighting), P1, P2 (power), H1, H2 (heater),
                  SP1, SP2 (spare)
    Three-phase: L1S1, L2S1, L3S1 (lighting round-robin),
                 L1P1, L2P1, L3P1 (power round-robin), SP1, SP2 (spare)

    Heater circuits (water heater, instant heater, storage heater) use "H" prefix
    per Singapore LEW convention (reference DWG: H5, H6 for heater points).
    """
    ids: list[str] = []

    # First pass: categorize circuits
    categories: list[str] = []
    for circuit in sub_circuits:
        name_lower = (str(circuit.get("name", "") or circuit.get("circuit_name", "")) or "").lower()
        if "spare" in name_lower:
            categories.append("spare")
        elif any(kw in name_lower for kw in ("light", "lamp", "led")):
            categories.append("lighting")
        elif any(kw in name_lower for kw in ("heater", "water heater", "instant heater", "storage heater")):
            categories.append("heater")
        else:
            categories.append("power")

    # Second pass: assign IDs with per-category counters
    # Note: Heater (H) and Power (P) share the SAME numeric counter.
    # Reference DWG: P1, P2, P3, P4, H5, H6 — heater continues from power count.
    s_idx = 0    # lighting counter
    ph_idx = 0   # power + heater shared counter
    sp_idx = 0   # spare counter

    for cat in categories:
        if supply_type == "single_phase":
            if cat == "spare":
                sp_idx += 1
                ids.append(f"SP{sp_idx}")
            elif cat == "lighting":
                s_idx += 1
                ids.append(f"S{s_idx}")
            elif cat == "heater":
                ph_idx += 1
                ids.append(f"H{ph_idx}")
            else:  # power
                ph_idx += 1
                ids.append(f"P{ph_idx}")
        else:  # three_phase — round-robin phase distribution
            if cat == "spare":
                sp_idx += 1
                ids.append(f"SP{sp_idx}")
            elif cat == "lighting":
                phase = (s_idx % 3) + 1
                num = (s_idx // 3) + 1
                ids.append(f"L{phase}S{num}")
                s_idx += 1
            elif cat == "heater":
                ph_idx += 1
                ids.append(f"H{ph_idx}")
            else:  # power
                phase = (ph_idx % 3) + 1
                num = (ph_idx // 3) + 1
                ids.append(f"L{phase}P{num}")
                ph_idx += 1

    return ids


def _get_circuit_poles(circuit: dict, supply_type: str) -> str:
    """Determine pole configuration for sub-circuit.

    Singapore SLD convention (per professional LEW reference DWG):
    - SPN (Single Pole + Neutral) for single-phase sub-circuits
    - TPN (Triple Pole + Neutral) for three-phase sub-circuits

    Note: Reference DWGs consistently use "SPN" for residential single-phase
    MCBs. The neutral conductor is terminated at the MCB/DB, making "SPN"
    (not just "SP") the correct designation.
    """
    phase = circuit.get("phase", "")
    if phase:
        phase_lower = phase.lower()
        if "single" in phase_lower or "1" in phase_lower:
            return "SPN"
        if "three" in phase_lower or "3" in phase_lower:
            return "TPN"
    # Default based on supply type
    if supply_type == "three_phase":
        return "TPN"
    # Single-phase default: SPN (matching professional LEW reference DWG)
    return "SPN"


def _get_circuit_fault_kA(breaker_type: str, circuit: dict | None = None) -> int:
    """Get fault rating for sub-circuit breaker.

    MCB sub-circuits: ALWAYS 6kA per Singapore standard (SS 638).
    - Main MCBs use 10kA (FAULT_LEVEL_DEFAULTS), but sub-circuit MCBs are 6kA.
    - Gemini often sends 10kA for sub-circuits (confusing main/sub defaults),
      so we enforce the correct value for MCBs regardless of explicit input.

    MCCB/ACB sub-circuits: use explicit value if provided, else defaults.
    """
    from app.sld.standards import SUB_CIRCUIT_FAULT_DEFAULTS
    bt = breaker_type.upper()

    # MCB sub-circuits: always 6kA — no override allowed
    if bt == "MCB":
        return SUB_CIRCUIT_FAULT_DEFAULTS.get("MCB", 6)

    # MCCB/ACB: respect explicit user value if provided
    if circuit:
        user_kA = circuit.get("fault_kA", 0)
        if user_kA:
            return int(user_kA)
    return SUB_CIRCUIT_FAULT_DEFAULTS.get(bt, 6)


def _place_sub_circuits_upward(
    result: LayoutResult,
    row_circuits: list[dict],
    row_idx: int,
    row_count: int,
    busbar_y: float,
    bus_start_x: float,
    bus_end_x: float,
    h_spacing: float,
    config: LayoutConfig,
    all_circuits: list[dict],
    supply_type: str = "three_phase",
    circuit_ids: list[str] | None = None,
) -> None:
    """Place a row of sub-circuits branching UPWARD from busbar with vertical labels."""
    bus_width = bus_end_x - bus_start_x

    # -- Detect category group boundaries (S→P, P→H, etc.) for extra spacing --
    import re as _re
    group_gap = 6.0  # Extra mm between circuit category groups
    group_breaks: list[int] = []  # Indices where a new category starts
    if circuit_ids and len(circuit_ids) > 1:
        prev_prefix = _re.match(r"[A-Za-z]+", circuit_ids[0])
        prev_prefix = prev_prefix.group() if prev_prefix else ""
        for ci in range(1, len(circuit_ids)):
            cur_prefix = _re.match(r"[A-Za-z]+", circuit_ids[ci])
            cur_prefix = cur_prefix.group() if cur_prefix else ""
            if cur_prefix != prev_prefix:
                group_breaks.append(ci)
            prev_prefix = cur_prefix
    num_breaks = len(group_breaks)

    for i, circuit in enumerate(row_circuits):
        global_idx = row_idx * config.max_circuits_per_row + i

        # Calculate tap point on busbar (with group gap offsets)
        if row_count == 1:
            tap_x = (bus_start_x + bus_end_x) / 2
        elif row_count > 1:
            usable = bus_width - 2 * config.busbar_margin - num_breaks * group_gap
            base_x = bus_start_x + config.busbar_margin + i * (usable / (row_count - 1))
            # Add cumulative group gap for each break before this circuit
            breaks_before = sum(1 for b in group_breaks if b <= i)
            tap_x = base_x + breaks_before * group_gap
        else:
            tap_x = bus_start_x + config.busbar_margin

        tap_x = max(tap_x, config.min_x + 20)
        tap_x = min(tap_x, config.max_x - 20)

        # Sub-circuit info
        sc_name = str(circuit.get("name", "") or circuit.get("circuit_name", f"DB-{global_idx + 1}"))

        # Look up pre-assigned circuit ID
        if circuit_ids and global_idx < len(circuit_ids):
            circuit_id = circuit_ids[global_idx]
        else:
            circuit_id = f"C{global_idx + 1}"

        # Circuit ID box at busbar tap point (small rectangle with ID text)
        result.components.append(PlacedComponent(
            symbol_name="CIRCUIT_ID_BOX",
            x=tap_x,
            y=busbar_y + 2,
            circuit_id=circuit_id,
        ))

        # -- Spare circuit: no breaker, just empty tap + "SPARE" label --
        if "spare" in sc_name.lower():
            # Short vertical line from busbar (upward, past circuit ID box)
            spare_top_y = busbar_y + 15
            result.connections.append(((tap_x, busbar_y), (tap_x, spare_top_y)))
            result.junction_dots.append((tap_x, busbar_y))
            # "SPARE" label (vertical text, above the tap)
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=tap_x,
                y=spare_top_y + 2,
                label="SPARE",
                rotation=90.0,
            ))
            continue

        # Vertical line UP from busbar to breaker
        sc_y = busbar_y + config.busbar_to_breaker_gap  # Above busbar (past circuit ID box)

        # Vertical drop from busbar (upward)
        result.connections.append(((tap_x, busbar_y), (tap_x, sc_y)))
        result.junction_dots.append((tap_x, busbar_y))

        # Sub-circuit breaker info
        sc_breaker_type = str(circuit.get("breaker_type", "MCB")).upper()
        sc_breaker_rating_raw = circuit.get("breaker_rating", 32)
        # Parse string ratings like "20A" → 20
        if isinstance(sc_breaker_rating_raw, str):
            import re
            m = re.match(r"(\d+)", sc_breaker_rating_raw)
            sc_breaker_rating = int(m.group(1)) if m else 32
        else:
            sc_breaker_rating = sc_breaker_rating_raw
        # Accept both "cable" (dict/str) and separate "cable_size"/"cable_type"/"cable_cores"
        sc_cable_raw = circuit.get("cable", "")
        if not sc_cable_raw and circuit.get("cable_size"):
            sc_cable_raw = {
                "size_mm2": circuit.get("cable_size", "").replace("mm2", ""),
                "type": circuit.get("cable_type", "PVC"),
                "cores": circuit.get("cable_cores", "2C").replace("C", ""),
                "method": circuit.get("wiring_method", ""),
            }
        sc_cable = format_cable_spec(sc_cable_raw)
        sc_load_kw = circuit.get("load_kw", 0)
        sc_phase = circuit.get("phase", "")

        # Determine breaker dimensions — synced with real_symbol_paths.json
        if sc_breaker_type in ("RCCB", "ELCB"):
            sc_cb_w = config.rccb_w
            sc_cb_h = config.rccb_h
        elif sc_breaker_type in ("MCCB", "ACB"):
            sc_cb_w = config.breaker_w
            sc_cb_h = config.breaker_h
        else:
            sc_cb_w = config.mcb_w
            sc_cb_h = config.mcb_h

        # Load current calculation
        load_info = ""
        if sc_load_kw and sc_load_kw > 0:
            current = round(sc_load_kw * 1000 / (400 * 1.732), 1)
            load_info = f"{sc_load_kw}kW / {current}A"

        # Determine fault kA and breaker characteristic
        sc_fault_kA = _get_circuit_fault_kA(sc_breaker_type, circuit)
        sc_breaker_char = str(
            circuit.get("breaker_characteristic", "")
            or circuit.get("breaker_char", "")
        ).upper()

        # Determine poles from circuit data or supply type
        # Single-phase sub-circuits: SPN (Single Pole + Neutral)
        sc_breaker_poles_raw = circuit.get("breaker_poles")
        if sc_breaker_poles_raw:
            sc_poles_from_data = str(sc_breaker_poles_raw)
            if sc_poles_from_data.isdigit():
                sc_poles = {1: "SP", 2: "DP", 3: "TPN", 4: "4P"}.get(int(sc_poles_from_data), "SP")
            else:
                sc_poles = sc_poles_from_data
        else:
            sc_poles = _get_circuit_poles(circuit, supply_type)

        cb_sym = f"CB_{sc_breaker_type}"
        result.components.append(PlacedComponent(
            symbol_name=cb_sym,
            x=tap_x - sc_cb_w / 2,
            y=sc_y,
            label=sc_name,
            rating=f"{sc_breaker_rating}A",
            cable_annotation=sc_cable,
            circuit_id=circuit_id,
            load_info=load_info,
            rotation=90.0,  # Vertical text (matches real Singapore SLD)
            # LEW-style breaker block fields
            poles=sc_poles,
            breaker_type_str=sc_breaker_type,
            fault_kA=sc_fault_kA,
            label_style="breaker_block",
            breaker_characteristic=sc_breaker_char,
        ))
        result.symbols_used.add(sc_breaker_type)

        # Tail from breaker top (extending upward)
        breaker_top_y = sc_y + sc_cb_h + config.stub_len
        tail_end_y = breaker_top_y + config.tail_length  # Conductor tail above breaker

        # Connection from breaker top to tail end
        result.connections.append(((tap_x, breaker_top_y), (tap_x, tail_end_y)))

        # Circuit name label (vertical text, above the tail)
        # Circuit ID is already shown in the CIRCUIT_ID_BOX at the busbar tap
        # Combine room/area name as suffix (reference DWG: "6 Nos 13A S/S/O — BEDROOM 1")
        sc_room = str(circuit.get("room", "") or circuit.get("location", "") or circuit.get("area", "")).strip()
        sc_display_name = sc_name
        if sc_room:
            # Append room as suffix with em dash separator
            sc_display_name = f"{sc_name} — {sc_room}"
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=tap_x,
            y=tail_end_y + 2,
            label=sc_display_name,
            rotation=90.0,
        ))

    # Cable leader lines are added AFTER resolve_overlaps (see _add_cable_leader_lines)
    # because resolve_overlaps changes the sub-circuit tap_x positions.
