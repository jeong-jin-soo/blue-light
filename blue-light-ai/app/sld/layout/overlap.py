"""
SLD Layout overlap resolution system.

Pipeline Architecture
=====================
compute_layout() (engine.py) calls resolve_overlaps() AFTER all sections are
placed (steps 1-8 in engine.py).  resolve_overlaps() executes a 5-step
pipeline **per row** of sub-circuits:

  Step 1: _identify_groups(layout_result)
    INPUT:  LayoutResult with all components/connections placed
    OUTPUT: list[SubCircuitGroup] with tap_x, breaker_idx, circuit_id_idx,
            name_label_idx, connection_indices, junction_dot_idx set
    DEPS:   Requires spine_x set by compute_layout()
            Requires busbar_y_per_row for multi-row Y-proximity filtering
    INVARIANT: groups sorted by (row_idx, tap_x) ascending

  Step 2: _compute_group_width(group, components)
    INPUT:  SubCircuitGroup with breaker_idx or spare_label_idx set
    OUTPUT: group.min_width, group.left_extent, group.right_extent set
    DEPS:   Requires _compute_bounding_box() to read symbol dimensions
    INVARIANT: left_extent + right_extent == min_width

  Step 3: _determine_final_positions(groups, components, layout_result, config)
    INPUT:  groups with left_extent/right_extent/gap_before set
    OUTPUT: list[float] new_tap_xs (final X positions)
    DEPS:   May modify layout_result.busbar_start_x / busbar_end_x
    INVARIANT: new_tap_xs preserves group ordering from Step 1

  Step 4: _rebuild_from_positions(groups, new_tap_xs, layout_result)
    INPUT:  groups with stored indices, new_tap_xs from Step 3
    OUTPUT: layout_result.components/connections/dots mutated in-place
    DEPS:   Indices from Step 1 must still be valid (no list insertions/deletions
            between Step 1 and Step 4)
    INVARIANT: delta_x = new_tap_x - original_tap_x applied uniformly to all
               elements in a group

  Step 5: _fit_busbar_to_groups(groups, new_tap_xs, layout_result, ...)
    INPUT:  final groups and positions from Steps 3-4
    OUTPUT: busbar_start_x / busbar_end_x adjusted, BUSBAR/LABEL/DB box updated
    DEPS:   db_box_dashed_indices must be valid

Correctness Invariants
  - No list insertions/deletions between Step 1 and Step 4 (index stability)
  - Steps 1-4 are per-row; Step 5 is cross-row
  - incoming_chain_x connections are never moved (they belong to the spine)
  - Post-resolve operations (_add_phase_fanout, _add_cable_leader_lines,
    _add_isolator_device_symbols) run AFTER resolve_overlaps completes

Also includes:
- BoundingBox: AABB for overlap detection
- SubCircuitGroup: all layout elements belonging to one sub-circuit column
- _add_cable_leader_lines(): cable spec leader lines (post-resolve)
- resolve_overlaps(): main entry point
"""

from __future__ import annotations

import logging
import re
from collections import OrderedDict
from dataclasses import dataclass, field

from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent
from app.sld.locale import SG_LOCALE

logger = logging.getLogger(__name__)


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


# Fallback symbol dimensions used when real_symbol_paths.json is unavailable.
_FALLBACK_SYMBOL_DIMS: dict[str, tuple[float, float]] = {
    "CB_MCB": (7.2, 13.0),
    "CB_MCCB": (8.4, 15.0),
    "CB_ACB": (10, 17),
    "CB_ELCB": (10.0, 15.0),
    "CB_RCCB": (10.0, 15.0),
    "ISOLATOR": (8, 14),
    "KWH_METER": (14, 10),
    "CT": (12, 12),
    "EARTH": (8, 6.7),
    "CIRCUIT_ID_BOX": (8, 5),
    "DB_INFO_BOX": (80, 18),
    "FLOW_ARROW_UP": (8, 10),
}

_SYMBOL_DIMS: dict[str, tuple[float, float]] | None = None


def _get_symbol_dims() -> dict[str, tuple[float, float]]:
    """Load symbol dimensions from real_symbol_paths.json (single source of truth).

    Falls back to ``_FALLBACK_SYMBOL_DIMS`` if the JSON file cannot be read.
    Result is cached after first call.
    """
    global _SYMBOL_DIMS
    if _SYMBOL_DIMS is not None:
        return _SYMBOL_DIMS

    try:
        from app.sld.real_symbols import get_symbol_dimensions

        dims = dict(_FALLBACK_SYMBOL_DIMS)  # start with fallback
        _JSON_KEY_MAP = {
            "CB_MCB": "MCB", "CB_MCCB": "MCCB", "CB_ACB": "ACB",
            "CB_ELCB": "ELCB", "CB_RCCB": "RCCB",
            "ISOLATOR": "ISOLATOR", "KWH_METER": "KWH_METER",
            "CT": "CT", "EARTH": "EARTH",
        }
        for overlap_key, json_key in _JSON_KEY_MAP.items():
            try:
                d = get_symbol_dimensions(json_key)
                dims[overlap_key] = (d["width_mm"], d["height_mm"])
            except (ValueError, KeyError):
                pass  # keep fallback for this symbol
        _SYMBOL_DIMS = dims
    except Exception:
        logger.debug("Failed to load symbol dims from JSON, using fallback")
        _SYMBOL_DIMS = dict(_FALLBACK_SYMBOL_DIMS)

    return _SYMBOL_DIMS

# Text measurement constants — defaults mirrored in LayoutConfig
# (char_width_estimate, label_char_height) for centralised overrides.
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

    # Special: CIRCUIT_ID_BOX — vertical text at tap_x (rotation=90°)
    if name == "CIRCUIT_ID_BOX":
        text_h = len(comp.circuit_id or "") * _CHAR_W + 2  # text length → height
        return BoundingBox(x=comp.x - 1.5, y=comp.y, width=3, height=text_h)

    # Special: DB_INFO_BOX extends downward from comp.y
    if name == "DB_INFO_BOX":
        return BoundingBox(x=comp.x, y=comp.y - 18, width=80, height=18)

    # Breaker with breaker_block label style (sub-circuit breakers)
    if name.startswith("CB_") and comp.label_style == "breaker_block":
        # Symbol dimensions
        sym_w, sym_h = _get_symbol_dims().get(name, (10, 16))

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
    dims = _get_symbol_dims()
    if name in dims:
        w, h = dims[name]
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
    row_idx: int = 0                     # Row index (0-based, for multi-row layouts)
    row_busbar_y: float = 0.0            # Busbar Y of this circuit's row


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


def _compute_dynamic_spacing(
    num_circuits: int,
    config: LayoutConfig,
    available_width: float | None = None,
) -> float:
    """Compute horizontal spacing that expands to fill available width.

    For fewer circuits, spacing increases (up to max_horizontal_spacing)
    so the diagram fills the drawing area. For many circuits, spacing
    decreases (down to min_horizontal_spacing).

    Args:
        num_circuits: Number of circuits to space.
        config: Layout config.
        available_width: If provided, constrains spacing to fit within this
            width (mm) instead of using the full page width.  Used by
            multi-DB / protection-group layouts.

    Note: cable schedule reserve is handled by shifting cx in compute_layout(),
    so the busbar naturally occupies a narrower zone.
    """
    effective_count = min(num_circuits, config.max_circuits_per_row)
    if effective_count <= 1:
        return config.horizontal_spacing

    if available_width is not None:
        max_bus_width = available_width
    else:
        max_bus_width = config.max_x - config.min_x - 40  # ~330mm
    ideal_spacing = (max_bus_width - 2 * config.busbar_margin) / effective_count
    # In constrained mode (multi-DB), allow tighter spacing to fit within allocation.
    # PG sub-busbars (< 80mm) keep aggressive 3mm floor for per-phase groups.
    # DB-level busbars (>= 80mm) use 6mm floor to ensure breaker label readability.
    if available_width is not None:
        min_spacing = 3.0 if available_width < 80 else 6.0
    else:
        min_spacing = config.min_horizontal_spacing
    return max(min_spacing, min(ideal_spacing, config.max_horizontal_spacing))


def _create_groups_from_breakers(
    components: list[PlacedComponent],
) -> list[SubCircuitGroup]:
    """Create initial groups from breaker_block components (pure)."""
    groups: list[SubCircuitGroup] = []
    for i, comp in enumerate(components):
        if comp.label_style == "breaker_block" and comp.symbol_name.startswith("CB_"):
            tap_x = comp.x + _breaker_half_width(comp)
            groups.append(SubCircuitGroup(tap_x=tap_x, breaker_idx=i))
    return groups


def _assign_rows_to_groups(
    groups: list[SubCircuitGroup],
    components: list[PlacedComponent],
    layout_result: LayoutResult,
) -> float:
    """Assign row_idx/row_busbar_y to each group and compute Y tolerance.

    Returns _Y_TOL (Y-axis tolerance for element matching).
    """
    busbar_ys = layout_result.busbar_y_per_row or [layout_result.busbar_y]

    if len(busbar_ys) >= 2:
        min_row_gap = min(
            abs(busbar_ys[i + 1] - busbar_ys[i])
            for i in range(len(busbar_ys) - 1)
        )
        _Y_TOL = max(min_row_gap * 0.7, 30.0)
    else:
        _Y_TOL = 999.0

    for g in groups:
        if g.breaker_idx is not None:
            comp_y = components[g.breaker_idx].y
        elif g.spare_label_idx is not None:
            comp_y = components[g.spare_label_idx].y
        else:
            comp_y = busbar_ys[0]

        best_row = 0
        best_dist = float("inf")
        for ri, by in enumerate(busbar_ys):
            dist = abs(comp_y - by)
            if dist < best_dist:
                best_dist = dist
                best_row = ri
        g.row_idx = best_row
        g.row_busbar_y = busbar_ys[best_row]

    groups.sort(key=lambda g: (g.row_idx, g.tap_x))
    return _Y_TOL


def _detect_incoming_chain_x(
    layout_result: LayoutResult,
    groups: list[SubCircuitGroup],
) -> float:
    """Detect the incoming supply chain X coordinate.

    Primary: uses spine_x from compute_layout().
    Fallback: histogram-based detection for backward compatibility.
    """
    _TOL = 1.5

    if layout_result.spine_x > 0:
        incoming_chain_x = layout_result.spine_x
    else:
        vert_conn_x_count: dict[float, int] = {}
        for (sx, sy), (ex, ey) in layout_result.connections:
            if abs(sx - ex) < 0.5:
                x_val = round(sx, 1)
                vert_conn_x_count[x_val] = vert_conn_x_count.get(x_val, 0) + 1

        incoming_chain_x = 0.0
        max_count = 0
        second_count = 0
        for x_val, count in vert_conn_x_count.items():
            is_tap = any(abs(x_val - g.tap_x) < _TOL for g in groups)
            if not is_tap:
                if count > max_count:
                    second_count = max_count
                    max_count = count
                    incoming_chain_x = x_val
                elif count > second_count:
                    second_count = count
        if max_count > 0 and max_count <= second_count:
            incoming_chain_x = 0.0

    # Transition validation
    if layout_result.spine_x > 0 and incoming_chain_x > 0:
        _histo_count: dict[float, int] = {}
        for (sx, sy), (ex, ey) in layout_result.connections:
            if abs(sx - ex) < 0.5:
                x_val = round(sx, 1)
                _histo_count[x_val] = _histo_count.get(x_val, 0) + 1
        _histo_x = 0.0
        _max_c = 0
        for x_val, count in _histo_count.items():
            is_tap = any(abs(x_val - g.tap_x) < _TOL for g in groups)
            if not is_tap and count > _max_c:
                _max_c = count
                _histo_x = x_val
        if _histo_x > 0 and abs(_histo_x - incoming_chain_x) > _TOL:
            logger.warning(
                "spine_x=%.1f differs from histogram_x=%.1f — using spine_x",
                incoming_chain_x, _histo_x,
            )

    return incoming_chain_x


def _match_elements_to_groups(
    groups: list[SubCircuitGroup],
    layout_result: LayoutResult,
    incoming_chain_x: float,
    _Y_TOL: float,
) -> None:
    """Match CIRCUIT_ID_BOX, LABELs, connections, dots, and arrows to groups.

    Mutates groups in-place, setting circuit_id_idx, name_label_idx,
    connection_indices, junction_dot_idx, arrow_point_idx, and is_spare.
    """
    components = layout_result.components
    connections = layout_result.connections
    _TOL = 1.5
    main_busbar_y = layout_result.busbar_y

    # Match CIRCUIT_ID_BOX
    for i, comp in enumerate(components):
        if comp.symbol_name != "CIRCUIT_ID_BOX":
            continue
        for g in groups:
            if abs(comp.x - g.tap_x) < _TOL and g.circuit_id_idx is None:
                if abs(comp.y - g.row_busbar_y) > _Y_TOL:
                    continue
                g.circuit_id_idx = i
                break

    # Set is_spare flag
    for g in groups:
        if g.breaker_idx is not None:
            label = (components[g.breaker_idx].label or "").upper()
            if label == "SPARE":
                g.is_spare = True

    # Match vertical name LABELs (rotation=90)
    for i, comp in enumerate(components):
        if not (comp.symbol_name == "LABEL" and abs(comp.rotation - 90.0) < 0.1):
            continue
        for g in groups:
            if abs(comp.x - g.tap_x) < 6.0 and g.name_label_idx is None:
                if g.breaker_idx is not None:
                    breaker_y = components[g.breaker_idx].y
                    if abs(comp.y - breaker_y) > _Y_TOL + 30:
                        continue
                g.name_label_idx = i
                break

    # Match vertical connections (exclude incoming chain and out-of-zone)
    for ci, ((sx, sy), (ex, ey)) in enumerate(connections):
        if abs(sx - ex) > 0.5:
            continue
        conn_x = sx
        if abs(conn_x - incoming_chain_x) < _TOL:
            continue
        conn_max_y = max(sy, ey)
        conn_min_y = min(sy, ey)
        if conn_max_y < main_busbar_y - 5 or conn_min_y > main_busbar_y + 60:
            continue
        for g in groups:
            if abs(conn_x - g.tap_x) < _TOL:
                if abs(conn_min_y - g.row_busbar_y) > _Y_TOL:
                    continue
                g.connection_indices.append(ci)
                break

    # Match junction_dots (skip fanout-relocated dots — they share center position)
    fanout_dots = layout_result.fanout_relocated_dots
    for di, (dx, dy) in enumerate(layout_result.junction_dots):
        if di in fanout_dots:
            continue
        if dy < main_busbar_y - 5 or dy > main_busbar_y + 60:
            continue
        for g in groups:
            if abs(dx - g.tap_x) < _TOL and g.junction_dot_idx is None:
                if abs(dy - g.row_busbar_y) > _Y_TOL:
                    continue
                g.junction_dot_idx = di
                break

    # Match arrow_points
    for ai, (ax, ay) in enumerate(layout_result.arrow_points):
        for g in groups:
            if abs(ax - g.tap_x) < _TOL and g.arrow_point_idx is None:
                if g.breaker_idx is not None:
                    breaker_y = components[g.breaker_idx].y
                    if abs(ay - breaker_y) > _Y_TOL + 30:
                        continue
                g.arrow_point_idx = ai
                break


def _identify_groups(
    layout_result: LayoutResult,
) -> tuple[list[SubCircuitGroup], float]:
    """Identify sub-circuit groups by scanning components and connections.

    Delegates to 4 sub-functions:
      1. _create_groups_from_breakers — create initial groups
      2. _assign_rows_to_groups — assign row_idx, sort by (row_idx, tap_x)
      3. _detect_incoming_chain_x — find spine X coordinate
      4. _match_elements_to_groups — match IDs, labels, connections, dots

    Returns:
        groups: list of SubCircuitGroup sorted by (row_idx, tap_x) ascending
        incoming_chain_x: x-coordinate of the incoming supply chain
    """
    groups = _create_groups_from_breakers(layout_result.components)
    if not groups:
        return [], 0.0

    _Y_TOL = _assign_rows_to_groups(groups, layout_result.components, layout_result)
    incoming_chain_x = _detect_incoming_chain_x(layout_result, groups)
    _match_elements_to_groups(groups, layout_result, incoming_chain_x, _Y_TOL)

    _validate_group_completeness(groups, layout_result.components, layout_result, incoming_chain_x)
    return groups, incoming_chain_x


def _validate_group_completeness(
    groups: list[SubCircuitGroup],
    components: list[PlacedComponent],
    layout_result: LayoutResult,
    incoming_chain_x: float,
) -> None:
    """Diagnose orphan elements that were not matched to any group.

    Pure diagnostic — no data mutation, logging only.
    """
    _TOL = 1.5

    # Check CIRCUIT_ID_BOX matching
    cid_matched = {g.circuit_id_idx for g in groups if g.circuit_id_idx is not None}
    total_cids = 0
    for i, comp in enumerate(components):
        if comp.symbol_name == "CIRCUIT_ID_BOX":
            total_cids += 1
            if i not in cid_matched:
                nearest = min(
                    (abs(comp.x - g.tap_x) for g in groups), default=999.0
                )
                logger.warning(
                    "Orphan CIRCUIT_ID_BOX[%d] x=%.1f, nearest_gap=%.1f (tol=%.1f)",
                    i, comp.x, nearest, _TOL,
                )

    # Check junction_dot matching (same Y-range filter as _match_elements_to_groups)
    jd_matched = {g.junction_dot_idx for g in groups if g.junction_dot_idx is not None}
    fanout_dots = layout_result.fanout_relocated_dots
    main_busbar_y = layout_result.busbar_y
    total_jds = len(layout_result.junction_dots)
    orphan_jds = 0
    for di, (dx, dy) in enumerate(layout_result.junction_dots):
        if di in jd_matched or di in fanout_dots:
            continue  # Matched or relocated by phase fanout
        if abs(dx - incoming_chain_x) < _TOL:
            continue  # Incoming chain dot
        if dy < main_busbar_y - 5 or dy > main_busbar_y + 60:
            continue  # Outside sub-circuit zone (structural dot)
        orphan_jds += 1
        logger.debug(
            "Unmatched junction_dot[%d] x=%.1f y=%.1f", di, dx, dy,
        )

    # Check arrow_point matching
    ap_matched = {g.arrow_point_idx for g in groups if g.arrow_point_idx is not None}
    total_aps = len(layout_result.arrow_points)
    orphan_aps = 0
    for ai, (ax, ay) in enumerate(layout_result.arrow_points):
        if ai not in ap_matched:
            orphan_aps += 1
            logger.debug(
                "Unmatched arrow_point[%d] x=%.1f y=%.1f", ai, ax, ay,
            )

    # Summary
    logger.debug(
        "Group matching: %d groups, CID %d/%d matched, "
        "jdots %d/%d matched, arrows %d/%d matched, incoming_x=%.1f",
        len(groups),
        len(cid_matched), total_cids,
        len(jd_matched) - (1 if incoming_chain_x > 0 else 0), total_jds,
        len(ap_matched), total_aps,
        incoming_chain_x,
    )
    if orphan_jds > 0 or orphan_aps > 0:
        logger.warning(
            "Unmatched elements: %d junction_dots, %d arrow_points "
            "(may affect resolve_overlaps accuracy)",
            orphan_jds, orphan_aps,
        )


def _compute_group_width(
    group: SubCircuitGroup,
    components: list[PlacedComponent],
    config: LayoutConfig | None = None,
) -> float:
    """
    Compute minimum horizontal space (mm) for a sub-circuit group.

    Also sets group.left_extent and group.right_extent for asymmetric spacing.
    """
    _MARGIN = config.overlap_margin if config else 2.0

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


def _expand_busbar_if_needed(
    total_needed: float,
    layout_result: LayoutResult,
    config: LayoutConfig,
    sc_bus_start: float,
    sc_bus_end: float,
    _MARGIN: float,
) -> tuple[float, float]:
    """Expand busbar left/right if sub-circuits need more space than available.

    Tries rightward expansion first, then leftward.  Mutates
    layout_result.busbar_start_x / busbar_end_x if expansion occurs.

    Returns:
        (sc_bus_start, sc_bus_end) — updated sub-circuit busbar boundaries.
    """
    available = sc_bus_end - sc_bus_start

    if total_needed <= available:
        return sc_bus_start, sc_bus_end

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

    return sc_bus_start, sc_bus_end


def _fit_positions_to_bounds(
    new_tap_xs: list[float],
    config: LayoutConfig,
    bound_margin: float = 20.0,
) -> list[float]:
    """Fit tap positions within drawing bounds while preserving relative spacing.

    For positions that fit within bounds, shifts the entire group together.
    If the span exceeds the available width, applies proportional compression
    around the center. This prevents individual clamping from destroying
    uniform spacing in 3-phase TPN layouts.

    Returns:
        Adjusted list of tap_x positions.
    """
    min_tap = config.min_x + bound_margin
    max_tap = config.max_x - bound_margin

    if len(new_tap_xs) > 1:
        cur_min = min(new_tap_xs)
        cur_max = max(new_tap_xs)
        cur_span = cur_max - cur_min
        avail_span = max_tap - min_tap

        if cur_span <= avail_span:
            # Span fits — shift together to stay within bounds
            if cur_min < min_tap:
                shift = min_tap - cur_min
                new_tap_xs = [t + shift for t in new_tap_xs]
            elif cur_max > max_tap:
                shift = cur_max - max_tap
                new_tap_xs = [t - shift for t in new_tap_xs]
        else:
            # Span too wide — proportional compression around center
            center = (cur_min + cur_max) / 2
            target_center = (min_tap + max_tap) / 2
            scale = avail_span / cur_span
            new_tap_xs = [target_center + (t - center) * scale for t in new_tap_xs]
    elif new_tap_xs:
        new_tap_xs = [max(min_tap, min(new_tap_xs[0], max_tap))]

    return new_tap_xs


def _determine_final_positions(
    groups: list[SubCircuitGroup],
    components: list[PlacedComponent],
    layout_result: LayoutResult,
    config: LayoutConfig,
    incoming_chain_x: float = 0.0,
    row_busbar_extent: tuple[float, float] | None = None,
) -> list[float]:
    """
    Single-pass left-to-right layout of sub-circuit groups.

    Ensures groups don't overlap by spacing them according to their
    computed minimum widths. Fits within drawing bounds, expanding
    the busbar if necessary. Centers groups on the busbar span
    that includes the incoming supply chain connection.

    Args:
        row_busbar_extent: If provided, (start_x, end_x) busbar extent for
            this specific row. Used by multi-DB to constrain each DB's
            circuits to their own busbar, not the main busbar.

    Delegates to:
      - _expand_busbar_if_needed() — grow busbar when circuits need more space
      - _fit_positions_to_bounds() — shift/compress to stay within drawing bounds

    Returns:
        new_tap_xs: list of final tap_x positions (same order as groups)
    """
    if not groups:
        return []

    _MARGIN = config.busbar_end_margin  # busbar end margin (default 10mm)

    # Use per-row busbar extent if available (multi-DB), else global busbar
    if row_busbar_extent:
        region_w = row_busbar_extent[1] - row_busbar_extent[0]
        # Adaptive margins: reduce for small regions (PG sub-busbars)
        # Default 10mm margins eat 20mm from a 55mm PG region (36%)!
        # Use proportional margins: min 2mm, max config value
        _MARGIN = min(_MARGIN, max(2.0, region_w * 0.05))
        sc_bus_start = row_busbar_extent[0] + _MARGIN
        sc_bus_end = row_busbar_extent[1] - _MARGIN
    else:
        sc_bus_start = layout_result.busbar_start_x + _MARGIN
        sc_bus_end = layout_result.busbar_end_x - _MARGIN

    # Compute total needed span using asymmetric extents
    n = len(groups)
    left_exts = [g.left_extent for g in groups]
    right_exts = [g.right_extent for g in groups]
    gap_befores = [g.gap_before for g in groups]

    total_needed = left_exts[0] + right_exts[-1]
    for i in range(n - 1):
        total_needed += right_exts[i] + left_exts[i + 1]
    total_needed += sum(gap_befores)

    available = sc_bus_end - sc_bus_start

    # Expand busbar if needed (skip for region-constrained layouts)
    if total_needed > available and not row_busbar_extent:
        sc_bus_start, sc_bus_end = _expand_busbar_if_needed(
            total_needed, layout_result, config, sc_bus_start, sc_bus_end, _MARGIN,
        )
        available = sc_bus_end - sc_bus_start

    # If still too tight after expansion, apply proportional compression.
    # For region-constrained layouts, use gentler compression that preserves
    # minimum readable spacing and truncates text labels rather than crushing.
    if total_needed > available and total_needed > 0:
        if row_busbar_extent:
            # Region-constrained: compress extents but keep minimum spacing.
            # The region is fixed so we shrink label extents to fit.
            scale = available / total_needed
            min_ext = 3.0  # minimum extent (mm) — allows text truncation
            left_exts = [max(e * scale, min_ext) for e in left_exts]
            right_exts = [max(e * scale, min_ext) for e in right_exts]
            # Also reduce gap_befores proportionally
            gap_befores = [g * scale for g in gap_befores]
        else:
            scale = available / total_needed
            left_exts = [e * scale for e in left_exts]
            right_exts = [e * scale for e in right_exts]
            left_exts = [max(e, 6.0) for e in left_exts]
            right_exts = [max(e, 6.0) for e in right_exts]

    # Place groups left-to-right using asymmetric extents
    new_tap_xs: list[float] = []

    if n == 1:
        tap_x = (sc_bus_start + sc_bus_end) / 2
        new_tap_xs.append(tap_x)
    else:
        cursor = sc_bus_start + left_exts[0]
        new_tap_xs.append(cursor)
        for i in range(1, n):
            cursor += right_exts[i - 1] + left_exts[i] + gap_befores[i]
            new_tap_xs.append(cursor)

        # Distribute surplus space evenly between groups
        actual_span = new_tap_xs[-1] - new_tap_xs[0]
        max_span = sc_bus_end - sc_bus_start - left_exts[0] - right_exts[-1]
        surplus = max_span - actual_span
        if surplus > 0 and n > 1:
            per_gap_bonus = surplus / (n - 1)
            max_gap_bonus = config.max_horizontal_spacing - config.horizontal_spacing
            per_gap_bonus = min(per_gap_bonus, max_gap_bonus)
            for i in range(1, n):
                new_tap_xs[i] += per_gap_bonus * i

    # Center on incoming chain x
    if n > 1 and incoming_chain_x:
        groups_center = (new_tap_xs[0] + new_tap_xs[-1]) / 2
        extent_bias = (left_exts[0] - right_exts[-1]) / 2
        target_groups_center = incoming_chain_x + extent_bias
        offset = target_groups_center - groups_center
        if abs(offset) > 0.1:
            new_tap_xs = [t + offset for t in new_tap_xs]

    # Fit within drawing bounds
    new_tap_xs = _fit_positions_to_bounds(new_tap_xs, config)

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

    _MARGIN = config.overlap_margin  # mm padding beyond outermost element edge

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
    # Match both "XA BUSBAR" (>500A) and "XA COMB BAR" (≤500A)
    for comp in components:
        label_upper = (comp.label or "").upper()
        if (comp.symbol_name == "LABEL"
                and ("BUSBAR" in label_upper or "COMB BAR" in label_upper)
                and abs(comp.rotation) < 0.1):
            comp.x = layout_result.busbar_start_x + 3
            break

    # Update DB box dashed connections to match new busbar
    _DB_BOX_MARGIN = config.db_box_overlap_margin  # enough to cover RCCB + Earth symbol
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


def _compute_safe_leader_bounds(
    leftmost_x: float,
    rightmost_x: float,
    spare_tap_xs: list[float],
    cable_groups: OrderedDict[str, list[float]],
    gi: int,
    config: LayoutConfig,
    *,
    groups: list[SubCircuitGroup] | None = None,
    components: list[PlacedComponent] | None = None,
) -> tuple[float, float]:
    """Compute safe horizontal bounds for cable leader line extension.

    Considers SPARE circuit positions, adjacent cable group boundaries,
    and circuit name label bounding boxes to prevent leader lines from
    overlapping with other labels.

    Args:
        groups: If provided, also checks circuit name label bounding boxes.
        components: Required when groups is provided.

    Returns:
        (safe_left, safe_right) — horizontal bounds for leader extension.
    """
    _SPARE_GAP = 5.0  # mm gap before SPARE circuit
    safe_left = config.min_x
    safe_right = config.max_x

    # Clamp to SPARE positions (leave gap for SPARE labels)
    for sx in spare_tap_xs:
        if sx < leftmost_x:
            safe_left = max(safe_left, sx + _SPARE_GAP)
        if sx > rightmost_x:
            safe_right = min(safe_right, sx - _SPARE_GAP)

    # Clamp to adjacent cable group boundaries
    # Margin accounts for cable text extending beyond leader endpoint:
    # text offset (3mm) + text half_width (~2.8mm) + clearance (0.2mm) = 6mm
    _CABLE_TEXT_MARGIN = 6.0
    all_group_ranges = [(min(txs), max(txs)) for txs in cable_groups.values()]
    for gj, (g_min, g_max) in enumerate(all_group_ranges):
        if gj == gi:
            continue
        if g_max < leftmost_x:
            safe_left = max(safe_left, g_max + _CABLE_TEXT_MARGIN)
        if g_min > rightmost_x:
            safe_right = min(safe_right, g_min - _CABLE_TEXT_MARGIN)

    # Clamp to circuit name label bounding boxes (rotated 90° vertical text)
    # Cable text half_w estimate for 2-line text (most cable specs split into 2 lines)
    if groups and components:
        _CABLE_TEXT_HALF_W = config.label_char_height  # 2.8mm for 2-line text
        _LABEL_GAP = _CABLE_TEXT_HALF_W + 1.5  # 4.3mm total clearance
        for g in groups:
            if g.name_label_idx is None:
                continue
            label_comp = components[g.name_label_idx]
            # 90° rotated label: horizontal extent = num_lines × char_height
            label_lines = (label_comp.label or "").split("\\P")
            num_lines = max(len(label_lines), 1)
            label_half_w = num_lines * config.label_char_height / 2
            label_x_min = label_comp.x - label_half_w
            label_x_max = label_comp.x + label_half_w

            # Labels to the left of the cable group constrain safe_left
            # Use _CABLE_TEXT_HALF_W to account for cable text reaching beyond endpoint
            if label_x_max < leftmost_x + _CABLE_TEXT_HALF_W:
                safe_left = max(safe_left, label_x_max + _LABEL_GAP)
            # Labels to the right constrain safe_right
            if label_x_min > rightmost_x - _CABLE_TEXT_HALF_W:
                safe_right = min(safe_right, label_x_min - _LABEL_GAP)

    return safe_left, safe_right


def _estimate_cable_text_num_lines(cable_spec: str) -> int:
    """Estimate number of lines cable text will occupy after line splitting.

    Mirrors the splitting logic in _draw_cable_leader_group():
    - Contains "CPC IN" or "PVC CPC IN" → 2 lines
    - Longer than 35 chars with " + " → 2 lines
    - Otherwise → 1 line
    """
    if re.search(r'\s+(PVC\s+CPC|CPC)\s+IN\s+', cable_spec, re.IGNORECASE):
        return 2
    if len(cable_spec) > 35 and " + " in cable_spec:
        return 2
    return 1


def _draw_cable_leader_group(
    tap_xs: list[float],
    cable_spec: str,
    leader_y: float,
    text_on_left: bool,
    leader_start_x: float,
    leader_end_x: float,
    bend_height: float,
    tick_size: float,
    layout_result: LayoutResult,
) -> None:
    """Draw one cable leader group: horizontal line, ticker marks, L-bend, and text.

    Appends connections (leader line, L-bend), thick_connections (tickers),
    and a LABEL component (cable spec text) to layout_result.
    """
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

    # Split long cable text into 2 lines to avoid exceeding drawing border
    cable_text = cable_spec
    m = re.search(r'\s+(PVC\s+CPC|CPC)\s+IN\s+', cable_text, re.IGNORECASE)
    if m:
        cable_text = cable_text[:m.start()] + "\\P" + cable_text[m.start() + 1:]
    elif len(cable_text) > 35 and " + " in cable_text:
        # Fallback: split at '+' for long cable specs without "CPC IN" pattern
        cable_text = cable_text.replace(" + ", " +\\P", 1)

    # L-shaped bend + cable spec text at leader end
    bend_top_y = leader_y + bend_height
    if text_on_left:
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

    Delegates to:
      - _compute_safe_leader_bounds() — SPARE/adjacent-group safe boundaries
      - _draw_cable_leader_group()   — horizontal leader + tickers + L-bend + text

    Reference DWG pattern:
      left-most cable group  → text at left end of leader
      right-most cable group → text at right end of leader
    """
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
    spare_tap_xs: list[float] = []  # SPARE circuit positions (for leader boundary)
    for g in groups:
        if g.is_spare:
            spare_tap_xs.append(g.tap_x)
            continue
        if g.breaker_idx is None:
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

        # Estimate cable text height and clamp leader_y to keep text within top border
        _CHAR_W_EST = 1.8  # mirrored in LayoutConfig.char_width_estimate
        max_spec_len = max(len(s) for s in cable_groups.keys())
        est_line_chars = max_spec_len // 2 + 5
        est_text_h = est_line_chars * _CHAR_W_EST
        max_leader_y = config.max_y - config.leader_bend_height - 1 - est_text_h
        if leader_y > max_leader_y:
            leader_y = max_leader_y

        group_keys = list(cable_groups.keys())

        # Build circuit name label bounding boxes for collision detection
        # (X range only; at same Y zone, vertical ranges always overlap for 90° text)
        # Store (x_min, x_max, center_x) to allow filtering own-group names
        name_label_bbs: list[tuple[float, float, float]] = []
        for g in groups:
            if g.name_label_idx is None or g.is_spare:
                continue
            comp = layout_result.components[g.name_label_idx]
            n_lines = max(len((comp.label or "").split("\\P")), 1)
            hw = n_lines * config.label_char_height / 2
            name_label_bbs.append((comp.x - hw, comp.x + hw, comp.x))

        # Track placed cable text bounding boxes for sequential collision avoidance
        placed_cable_bbs: list[tuple[float, float, float]] = []  # (x_min, x_max, y)

        def _has_collision(
            bb: tuple[float, float], y: float,
            own_tap_xs: list[float] | None = None,
        ) -> bool:
            """Check if cable text BB collides with EXTERNAL name labels or placed cable texts.

            own_tap_xs: tap positions of the current cable group — names at these
            positions are excluded (cable leader passes over its own group's names,
            so moderate overlap is expected and acceptable).
            """
            _MARGIN = 0.5  # extra clearance to catch near-misses
            for nx_min, nx_max, nx_center in name_label_bbs:
                # Skip names that belong to the current cable group
                if own_tap_xs and any(abs(nx_center - tx) < 0.5 for tx in own_tap_xs):
                    continue
                if bb[1] > nx_min - _MARGIN and bb[0] < nx_max + _MARGIN:
                    return True
            for px_min, px_max, py in placed_cable_bbs:
                if abs(py - y) < 8.0:  # same Y zone (within 8mm)
                    if bb[1] > px_min - _MARGIN and bb[0] < px_max + _MARGIN:
                        return True
            return False

        for gi, (cable_spec, tap_xs) in enumerate(cable_groups.items()):
            tap_xs.sort()
            leftmost_x = tap_xs[0]
            rightmost_x = tap_xs[-1]

            # Compute safe extension limits (with circuit name label collision check)
            safe_left, safe_right = _compute_safe_leader_bounds(
                leftmost_x, rightmost_x, spare_tap_xs, cable_groups, gi, config,
                groups=groups, components=layout_result.components,
            )

            # Determine text placement direction
            effective_left = leftmost_x - safe_left
            effective_right = safe_right - rightmost_x
            if len(group_keys) == 1:
                text_on_left = effective_left >= effective_right
            else:
                text_on_left = (gi % 2 == 0)

            # Compute leader line endpoints
            leader_extension = config.leader_extension
            if text_on_left:
                ext = min(leader_extension, effective_left)
                leader_start_x = leftmost_x - ext
                leader_end_x = rightmost_x
            else:
                ext = min(leader_extension, effective_right)
                leader_start_x = leftmost_x
                leader_end_x = rightmost_x + ext

            effective_leader_y = leader_y

            # --- BB-based collision detection + correction ---
            cable_num_lines = _estimate_cable_text_num_lines(cable_spec)
            cable_hw = cable_num_lines * config.label_char_height / 2
            text_x = (leader_start_x - 3) if text_on_left else leader_end_x
            cable_bb = (text_x - cable_hw, text_x + cable_hw)

            collision = _has_collision(cable_bb, effective_leader_y, own_tap_xs=tap_xs)

            if collision:
                # Attempt 1: flip text direction
                alt_on_left = not text_on_left
                if alt_on_left:
                    alt_ext = min(leader_extension, effective_left)
                    alt_start = leftmost_x - alt_ext
                    alt_text_x = alt_start - 3
                else:
                    alt_ext = min(leader_extension, effective_right)
                    alt_end = rightmost_x + alt_ext
                    alt_text_x = alt_end
                alt_bb = (alt_text_x - cable_hw, alt_text_x + cable_hw)

                if not _has_collision(alt_bb, effective_leader_y, own_tap_xs=tap_xs):
                    # Flip resolved the collision
                    text_on_left = alt_on_left
                    if text_on_left:
                        leader_start_x = leftmost_x - alt_ext
                        leader_end_x = rightmost_x
                    else:
                        leader_start_x = leftmost_x
                        leader_end_x = rightmost_x + alt_ext
                    cable_bb = alt_bb
                else:
                    # Attempt 2: Y stagger (+4mm)
                    stagger_y = min(effective_leader_y + 4.0, max_leader_y)
                    # Try original direction with stagger
                    if not _has_collision(cable_bb, stagger_y, own_tap_xs=tap_xs):
                        effective_leader_y = stagger_y
                    # Try flipped direction with stagger
                    elif not _has_collision(alt_bb, stagger_y, own_tap_xs=tap_xs):
                        effective_leader_y = stagger_y
                        text_on_left = alt_on_left
                        if text_on_left:
                            leader_start_x = leftmost_x - alt_ext
                            leader_end_x = rightmost_x
                        else:
                            leader_start_x = leftmost_x
                            leader_end_x = rightmost_x + alt_ext
                        cable_bb = alt_bb
                    else:
                        # Best-effort: apply stagger even if not fully resolved
                        effective_leader_y = stagger_y

            # Clamp to drawing bounds
            if effective_leader_y > max_leader_y:
                effective_leader_y = max_leader_y

            # Register final cable text BB for subsequent collision checks
            final_text_x = (leader_start_x - 3) if text_on_left else leader_end_x
            final_bb = (final_text_x - cable_hw, final_text_x + cable_hw)
            placed_cable_bbs.append((final_bb[0], final_bb[1], effective_leader_y))

            # Draw this cable group's leader lines
            _draw_cable_leader_group(
                tap_xs, cable_spec, effective_leader_y, text_on_left,
                leader_start_x, leader_end_x,
                config.leader_bend_height, tick_size, layout_result,
            )


def _add_isolator_device_symbols(
    layout_result: LayoutResult,
    config: LayoutConfig,
) -> None:
    """Add DP ISOL device symbols at conductor tops for ISOLATOR circuits (post-resolve).

    Reference DWG "DP ISOL" block (63A TPN SLD 14):
      3.8mm square outline + L-shaped connection stub (right → down).
      Represents the physical isolator switch near the load
      (e.g., 20A DP ISOLATOR for air-conditioning).

    The conductor stops at the bottom of the box (no line through the box).
    helpers.py reserves space: conductor_top_y = tail_end_y + _ISOL_DEVICE_BOX_H,
    so the box sits between tail_end_y (bottom) and conductor_top_y (top).

    Geometry:
      ┌─────────┐
      │         │───┐   ← L-stub: right 2mm, down 1.6mm
      │         │   │
      └─────────┘   │
           │        (stub end)
      (conductor)
    """
    groups, _ = _identify_groups(layout_result)
    if not groups:
        return

    # Dimensions for DP ISOL device symbol (matches _ISOL_DEVICE_BOX_H in helpers.py)
    _BOX_SIZE = 3.8     # mm — square box
    _BOX_HW = _BOX_SIZE / 2
    _STUB_RIGHT = 2.0   # mm — L-stub horizontal extension
    _STUB_DOWN = 1.6    # mm — L-stub vertical drop

    for g in groups:
        if g.breaker_idx is None:
            continue
        comp = layout_result.components[g.breaker_idx]
        if (comp.breaker_type_str or "").upper() != "ISOLATOR":
            continue

        # Find conductor top Y (highest endpoint of tracked connections)
        # In helpers.py, conductor_top_y = tail_end_y + _ISOL_DEVICE_BOX_H
        # The conductor line ends at tail_end_y (= conductor_top_y - _BOX_SIZE)
        conductor_top_y = 0
        for ci in g.connection_indices:
            (_, sy), (_, ey) = layout_result.connections[ci]
            conductor_top_y = max(conductor_top_y, sy, ey)

        if conductor_top_y <= 0:
            continue

        tap_x = g.tap_x
        # Box sits ON TOP of the conductor end:
        # conductor ends at tail_end_y, box occupies [tail_end_y, tail_end_y + _BOX_SIZE]
        # But conductor_top_y here is the actual conductor line end (tail_end_y),
        # because helpers.py writes connection to tail_end_y, not conductor_top_y.
        box_bottom = conductor_top_y
        box_top = conductor_top_y + _BOX_SIZE

        # 1. Outline rectangle (4 line segments, no internal lines)
        layout_result.connections.append(
            ((tap_x - _BOX_HW, box_bottom), (tap_x + _BOX_HW, box_bottom)))  # bottom
        layout_result.connections.append(
            ((tap_x - _BOX_HW, box_top), (tap_x + _BOX_HW, box_top)))        # top
        layout_result.connections.append(
            ((tap_x - _BOX_HW, box_bottom), (tap_x - _BOX_HW, box_top)))     # left
        layout_result.connections.append(
            ((tap_x + _BOX_HW, box_bottom), (tap_x + _BOX_HW, box_top)))     # right

        # 2. L-shaped connection stub (right edge middle → right → down)
        stub_start_x = tap_x + _BOX_HW
        stub_mid_y = (box_bottom + box_top) / 2
        stub_corner_x = stub_start_x + _STUB_RIGHT
        stub_end_y = stub_mid_y - _STUB_DOWN

        layout_result.connections.append(
            ((stub_start_x, stub_mid_y), (stub_corner_x, stub_mid_y)))        # horizontal
        layout_result.connections.append(
            ((stub_corner_x, stub_mid_y), (stub_corner_x, stub_end_y)))       # vertical down


def _get_circuit_id(group: SubCircuitGroup, components: list) -> str:
    """Get the circuit ID string (e.g., 'L1S', 'L2P1') from a group."""
    if group.circuit_id_idx is not None:
        cid = components[group.circuit_id_idx].circuit_id or ""
        return cid.strip()
    return ""


def _parse_phase_prefix(circuit_id: str) -> tuple[str, str]:
    """Parse circuit ID into (phase, suffix). E.g., 'L1P3' → ('L1', 'P3')."""
    import re
    m = re.match(r"^(L[123])(.*)", circuit_id, re.IGNORECASE)
    if m:
        return m.group(1).upper(), m.group(2)
    return ("", circuit_id)


def _extract_section_code(circuit_id: str) -> str:
    """Extract section code from circuit ID.

    Examples:
        'L1S1' → 'S',  'L2P3' → 'P',  'ISOL1' → '',  'SP1' → ''
    """
    import re
    m = re.match(r"^L[123]([A-Z])", circuit_id, re.IGNORECASE)
    if m:
        return m.group(1).upper()
    return ""


def _build_phase_groups(
    circuits: list[tuple[SubCircuitGroup, float]],
    components: list,
) -> list[list[tuple[SubCircuitGroup, float]]]:
    """Build 3-phase fan-out groups respecting section boundaries.

    In a TPN distribution board, every 3 consecutive busbar positions form
    one physical triplet connected to L1/L2/L3 phases of the comb bar.
    ALL circuit types participate: regular MCB, ISOLATOR, and SPARE.

    Section boundaries (e.g., Lighting→Power) are detected from circuit IDs
    (L1S1→section 'S', L1P1→section 'P'). Triplets never span sections.
    SPARE and ISOLATOR circuits inherit the section of the preceding circuit.

    Reference: 63A TPN SLD 14 — 27-way DB:
      Lighting: [L1S1,L2S1,L3S1] [L1S2,L2S2,L3S2] [L1S3,SPARE,SPARE]
      Power:    [L1P1,L2P1,L3P1] [ISOL1,L2P2,L3P2] [L1P3,L2P3,ISOL2]
                [L1P4,L2P4,ISOL3] [L1P5,L2P5,L3P5] [L1P6,SPARE,SPARE]

    Returns groups of 2 or 3 circuits (singles are skipped — no fan-out needed).
    """
    # --- 1. Assign section code to each circuit ---
    section_codes: list[str] = []
    last_section = ""
    for (g, _by) in circuits:
        cid = _get_circuit_id(g, components)
        sec = _extract_section_code(cid)
        if sec:
            last_section = sec
        # SPARE / ISOL inherit previous section
        section_codes.append(last_section)

    # --- 2. Split at section boundaries ---
    sections: list[list[tuple[SubCircuitGroup, float]]] = []
    current_section: list[tuple[SubCircuitGroup, float]] = []
    current_code = section_codes[0] if section_codes else ""

    for idx, item in enumerate(circuits):
        code = section_codes[idx]
        if code != current_code and current_section:
            sections.append(current_section)
            current_section = []
            current_code = code
        current_section.append(item)
    if current_section:
        sections.append(current_section)

    # --- 3. Chunk each section into triplets ---
    groups: list[list[tuple[SubCircuitGroup, float]]] = []
    for section in sections:
        for i in range(0, len(section), 3):
            chunk = section[i:i + 3]
            if len(chunk) >= 2:
                groups.append(chunk)
        if len(section) % 3 != 0:
            import logging
            logging.getLogger(__name__).warning(
                "Section has %d circuits (not multiple of 3) — "
                "incomplete triplet at section end", len(section),
            )

    return groups


def _add_phase_fanout(
    layout_result: LayoutResult,
    config: LayoutConfig,
    supply_type: str,
) -> None:
    """Add 3-phase fan-out lines at busbar (post-resolve_overlaps).

    Position-based triplet grouping: every 3 consecutive busbar positions
    form one fan-out group. ALL circuit types participate (MCB, ISOLATOR,
    SPARE) since they all occupy physical positions on the comb bar.

    Reference pattern (63A TPN SLD 14):
        Triplet:        Pair:
        |    /|\\          /|
        |   / | \\        / |
        |  /  |  \\      /  |
      ━━━━━━(●)━━━━━━  ━(●)━━━━
    """
    if supply_type != "three_phase":
        return
    if not layout_result.use_triplets:
        return

    groups, _ = _identify_groups(layout_result)
    if not groups:
        return

    busbar_ys = layout_result.busbar_y_per_row or [layout_result.busbar_y]
    components = layout_result.components

    # --- Group ALL circuits (including SPARE and ISOL) PER ROW ---
    rows: dict[int, list[tuple[SubCircuitGroup, float]]] = {}
    for g in groups:
        # Include all circuits: breaker circuits AND spares
        if g.breaker_idx is not None or g.is_spare:
            row_idx = g.row_idx
            by = g.row_busbar_y if g.row_busbar_y else busbar_ys[0]
            rows.setdefault(row_idx, []).append((g, by))

    _FAN_HEIGHT = 2.5  # mm above busbar where diagonals meet side verticals

    connections = layout_result.connections

    for row_idx in sorted(rows.keys()):
        all_circuits = rows[row_idx]
        # all_circuits is sorted by tap_x (from _identify_groups sort)

        phase_groups = _build_phase_groups(all_circuits, components)

        for pg in phase_groups:
            if len(pg) == 3:
                # Triplet: LEFT / CENTER / RIGHT
                left_g, _ = pg[0]
                center_g, center_by = pg[1]
                right_g, _ = pg[2]
                by = center_by
            elif len(pg) == 2:
                # Pair: first is center, second is side
                center_g, center_by = pg[0]
                side_g, _ = pg[1]
                by = center_by
            else:
                continue  # Single or unexpected — no fan-out

            intermediate_y = by + _FAN_HEIGHT
            center_x = center_g.tap_x

            if len(pg) == 3:
                sides = [left_g, right_g]
            else:
                sides = [side_g]

            for s_g in sides:
                for ci in s_g.connection_indices:
                    (sx, sy), (ex, ey) = connections[ci]
                    if abs(sy - by) < 1.0 and abs(sx - ex) < 0.5:
                        connections[ci] = ((sx, intermediate_y), (ex, ey))

                if s_g.junction_dot_idx is not None:
                    layout_result.junction_dots[s_g.junction_dot_idx] = (center_x, by)
                    layout_result.fanout_relocated_dots.add(s_g.junction_dot_idx)

                # Diagonal from center busbar junction to side intermediate
                connections.append(((center_x, by), (s_g.tap_x, intermediate_y)))


def _normalize_row_spacing(
    row_groups: list[SubCircuitGroup],
    components: list[PlacedComponent],
    config: LayoutConfig | None = None,
    region_width: float | None = None,
) -> None:
    """Normalize per-row spacing: category gaps, ditto detection, width computation.

    Performs Steps 1b, 1c, 2, 2b, 2c of the overlap resolution pipeline:
      1b — Detect category group breaks (S→P section transitions)
      1c — Detect ditto groups (identical breaker specs → compact width)
      2  — Compute minimum width per group via bounding boxes
      2b — Override ditto group extents to compact width
      2c — 3-phase uniform spacing normalization (TPN DBs)

    Args:
        region_width: If provided, cap Step 2c extents so groups fit within
            this horizontal span. Used for multi-DB region-constrained layouts
            to prevent text extent inflation from causing extreme compression.

    Mutates row_groups in-place: sets gap_before, is_ditto, min_width,
    left_extent, right_extent.
    """
    _GROUP_GAP = config.overlap_group_gap if config else 3.0
    _DITTO_EXTENT = config.overlap_ditto_extent if config else 5.5

    # Step 1b: Detect category group breaks (S→P, P→H, etc.)
    if len(row_groups) > 1:
        prev_prefix = ""
        for gi, g in enumerate(row_groups):
            cid = ""
            if g.breaker_idx is not None:
                comp = components[g.breaker_idx]
                cid = comp.circuit_id or ""
            cur_match = re.match(r"[A-Za-z]+", cid)
            cur_prefix = cur_match.group() if cur_match else ""
            if gi > 0 and cur_prefix and prev_prefix and cur_prefix != prev_prefix:
                g.gap_before = _GROUP_GAP
            prev_prefix = cur_prefix

    # Step 1c: Detect ditto groups (identical breaker specs within same category)
    breaker_spec_sigs: dict[str, list[int]] = {}
    for gi, g in enumerate(row_groups):
        if g.breaker_idx is not None:
            comp = components[g.breaker_idx]
            cid = comp.circuit_id or ""
            pfx_match = re.match(r"[A-Za-z]+", cid)
            pfx = pfx_match.group() if pfx_match else "X"
            sig = (f"{pfx}|{comp.breaker_characteristic}|{comp.rating}"
                   f"|{comp.poles}|{comp.breaker_type_str}|{comp.fault_kA}")
            breaker_spec_sigs.setdefault(sig, []).append(gi)
    for sig, gindices in breaker_spec_sigs.items():
        if len(gindices) >= 2:
            for k in range(1, len(gindices)):
                row_groups[gindices[k]].is_ditto = True

    # Step 2: Compute minimum widths per group
    for g in row_groups:
        g.min_width = _compute_group_width(g, components, config)

    # Step 2b: Override extents for ditto groups (no labels → compact)
    for g in row_groups:
        if g.is_ditto:
            effective_ditto = _DITTO_EXTENT
            if region_width is not None:
                # In constrained regions: ditto minimum 4mm to keep chain arrows visible
                per_group_avail = region_width / max(len(row_groups), 1)
                effective_ditto = max(4.0, min(_DITTO_EXTENT, per_group_avail / 2))
            g.left_extent = effective_ditto
            g.right_extent = effective_ditto
            g.min_width = g.left_extent + g.right_extent

    # Step 2c: 3-phase uniform spacing normalization
    # In a TPN DB, ALL busbar positions form 3-phase triplets with
    # uniform spacing.  Override the per-prefix gap detection (Step 1b)
    # and per-type width differences (SPARE=15mm vs MCB=19mm) so that
    # every circuit occupies the same horizontal space.
    # Only add extra gap at the section boundary (Lighting→Power).
    _phase_id_count = 0
    _phase_digits: set[str] = set()
    for g in row_groups:
        if g.breaker_idx is not None:
            cid = components[g.breaker_idx].circuit_id or ""
            m = re.match(r"^L([123])", cid)
            if m:
                _phase_id_count += 1
                _phase_digits.add(m.group(1))
    _is_interleaved = _phase_id_count >= 6 and len(_phase_digits) >= 2
    _needs_uniform = _phase_id_count >= 6  # uniform spacing for all L[123]* groups
    if _needs_uniform:
        # Clear all prefix-based gaps from Step 1b
        for g in row_groups:
            g.gap_before = 0.0

        # Add gap only at section boundaries (S→P) at triplet boundaries.
        # Only for interleaved boards (L1/L2/L3 alternating), NOT for
        # single-phase PG groups (all same L-prefix, e.g. all L1*).
        if _is_interleaved:
            _SECTION_GAP = 6.0

            def _triplet_section(start_gi: int) -> str:
                """Return 'S', 'P', or '' for a triplet starting at start_gi."""
                for k in range(start_gi, min(start_gi + 3, len(row_groups))):
                    rg = row_groups[k]
                    if rg.breaker_idx is not None:
                        cid = components[rg.breaker_idx].circuit_id or ""
                        m = re.match(r"^L[123]([A-Z])", cid)
                        if m:
                            return m.group(1)  # 'S' or 'P'
                return ""

            prev_sec = _triplet_section(0)
            for t in range(3, len(row_groups), 3):
                cur_sec = _triplet_section(t)
                if cur_sec and prev_sec and cur_sec != prev_sec:
                    row_groups[t].gap_before = _SECTION_GAP
                if cur_sec:
                    prev_sec = cur_sec

        # Normalize all widths to maximum for uniform spacing
        max_left = max(g.left_extent for g in row_groups)
        max_right = max(g.right_extent for g in row_groups)

        # Cap extents for region-constrained layouts to prevent text extent
        # inflation from causing extreme compression in multi-DB layouts.
        # Without capping, a single non-ditto group's full label extent
        # (~28mm right) is applied to ALL groups, requiring 12×33mm=396mm
        # but only ~170mm is available — leading to 0.7mm spacing.
        if region_width is not None:
            n = len(row_groups)
            # Adaptive margin: small regions (PGs) get smaller margins
            end_margin = min(10.0, max(2.0, region_width * 0.05))
            usable = region_width - 2 * end_margin
            # Also reserve room for gap_before (section boundaries, etc.)
            total_gaps = sum(g.gap_before for g in row_groups)
            usable_for_groups = usable - total_gaps
            target_per_group = usable_for_groups / max(n, 1)
            half_target = target_per_group / 2
            max_left = min(max_left, half_target)
            max_right = min(max_right, half_target)
            # Ensure at least 3mm each side for minimal symbol clearance
            max_left = max(max_left, 3.0)
            max_right = max(max_right, 3.0)

        for g in row_groups:
            g.left_extent = max_left
            g.right_extent = max_right
            g.min_width = max_left + max_right


def _update_secondary_busbars(
    rows_map: dict[int, list[SubCircuitGroup]],
    all_final_groups: list[SubCircuitGroup],
    all_final_tap_xs: list[float],
    layout_result: LayoutResult,
    config: LayoutConfig,
    incoming_chain_x: float,
) -> None:
    """Update secondary (row 2+) busbar positions after per-row repositioning.

    After resolve_overlaps processes each row independently, the secondary
    busbars may no longer cover all their circuits' final positions.
    This function recalculates each secondary busbar's horizontal extent
    and updates the corresponding BUSBAR component.

    Row 0 (main busbar) is handled by _fit_busbar_to_groups, so this
    function only processes row_idx >= 1.
    """
    busbar_ys = layout_result.busbar_y_per_row or []
    for row_idx in sorted(rows_map.keys()):
        if row_idx == 0:
            continue  # Main busbar already handled by _fit_busbar_to_groups
        row_groups = rows_map[row_idx]
        row_tap_xs = [
            new_x for g, new_x
            in zip(all_final_groups, all_final_tap_xs)
            if g.row_idx == row_idx
        ]
        if not row_tap_xs:
            continue
        row_left = min(row_tap_xs) - row_groups[0].left_extent - 2
        row_right = max(row_tap_xs) + row_groups[-1].right_extent + 2
        # Ensure incoming chain x is covered
        if incoming_chain_x:
            row_left = min(row_left, incoming_chain_x - 2)
            row_right = max(row_right, incoming_chain_x + 2)
        row_left = max(row_left, config.min_x)
        row_right = min(row_right, config.max_x)

        # Find and update this row's BUSBAR component
        if row_idx < len(busbar_ys):
            row_by = busbar_ys[row_idx]
            for comp in layout_result.components:
                if (comp.symbol_name == "BUSBAR"
                        and not comp.label
                        and abs(comp.y - row_by) < 3):
                    comp.x = row_left
                    break


def resolve_overlaps(
    layout_result: LayoutResult,
    config: LayoutConfig | None = None,
) -> LayoutResult:
    """
    Post-layout overlap resolution using determine-then-rebuild approach.

    Multi-row aware: each row's sub-circuits are positioned independently
    to prevent cross-row interference. This ensures circuits from Row 1
    and Row 2 don't compete for the same X positions.

    Pipeline (per row):
      1. _identify_groups()          — classify components/connections by sub-circuit
      2. _compute_group_width()      — bounding box based minimum widths
      3. _determine_final_positions() — single-pass left-to-right with bounds fit
      4. _rebuild_from_positions()    — index-based absolute repositioning
    Then across all rows:
      5. _fit_busbar_to_groups() — extent-aware busbar fitting (extend or shrink)

    Guarantees:
      - No text/symbol overlaps between adjacent sub-circuits
      - All connections stay aligned with their breaker symbols
      - All tap points remain within the busbar extent
      - Incoming chain connections are never moved
      - Deterministic: same input → same output

    INVARIANT: No list insertions or deletions to layout_result.components,
    connections, junction_dots, or arrow_points between Step 1 (_identify_groups)
    and Step 4 (_rebuild_from_positions). Step 1 stores integer indices into
    these lists, and Step 4 uses those indices to apply position deltas.
    Insertions or deletions would invalidate the stored indices.
    """
    if config is None:
        config = LayoutConfig()

    # Step 1: Identify sub-circuit groups (row-aware matching)
    groups, incoming_chain_x = _identify_groups(layout_result)

    if not groups:
        return layout_result

    # Split groups by row for independent processing
    rows_map: dict[int, list[SubCircuitGroup]] = {}
    for g in groups:
        rows_map.setdefault(g.row_idx, []).append(g)

    all_final_groups: list[SubCircuitGroup] = []
    all_final_tap_xs: list[float] = []

    # ── Multi-DB region-aware path ──
    # When layout_regions exist, circuits from different DBs share the same
    # busbar Y but have different X regions. Split groups by X-region to
    # process each DB's circuits independently.
    regions = layout_result.layout_regions

    for row_idx in sorted(rows_map.keys()):
        row_groups = rows_map[row_idx]

        if regions:
            # Split this row's groups by which region they belong to
            region_buckets: dict[int, list[SubCircuitGroup]] = {}
            unassigned: list[SubCircuitGroup] = []
            for g in row_groups:
                assigned = False
                for ri, region in enumerate(regions):
                    if region.min_x - 5 <= g.tap_x <= region.max_x + 5:
                        region_buckets.setdefault(ri, []).append(g)
                        assigned = True
                        break
                if not assigned:
                    unassigned.append(g)

            # Process each region's groups independently
            for ri in sorted(region_buckets.keys()):
                r_groups = region_buckets[ri]
                region = regions[ri]
                _normalize_row_spacing(
                    r_groups, layout_result.components, config,
                    region_width=region.width,
                )

                row_busbar_extent = (region.min_x, region.max_x)
                new_tap_xs = _determine_final_positions(
                    r_groups, layout_result.components, layout_result, config,
                    incoming_chain_x=incoming_chain_x if ri == 0 else 0.0,
                    row_busbar_extent=row_busbar_extent,
                )

                # Multi-DB region clamping: ensure all positions stay within
                # the strict region bounds. If _determine_final_positions
                # produced positions outside the region (due to centering or
                # bound fitting), clamp them proportionally.
                _MARGIN_R = min(config.busbar_end_margin, max(2.0, region.width * 0.05))
                r_min = region.min_x + _MARGIN_R
                r_max = region.max_x - _MARGIN_R
                if new_tap_xs and len(new_tap_xs) > 1:
                    t_min = min(new_tap_xs)
                    t_max = max(new_tap_xs)
                    if t_min < r_min or t_max > r_max:
                        cur_span = t_max - t_min
                        avail_span = r_max - r_min
                        if cur_span > avail_span and cur_span > 0:
                            center = (r_min + r_max) / 2
                            scale_f = avail_span / cur_span * 0.95
                            old_center = (t_min + t_max) / 2
                            new_tap_xs = [center + (t - old_center) * scale_f
                                          for t in new_tap_xs]
                        elif t_min < r_min:
                            shift = r_min - t_min
                            new_tap_xs = [t + shift for t in new_tap_xs]
                        elif t_max > r_max:
                            shift = t_max - r_max
                            new_tap_xs = [t - shift for t in new_tap_xs]

                _rebuild_from_positions(r_groups, new_tap_xs, layout_result)
                for g, new_x in zip(r_groups, new_tap_xs):
                    all_final_groups.append(g)
                    all_final_tap_xs.append(new_x)

            # Process unassigned groups with global busbar
            if unassigned:
                _normalize_row_spacing(unassigned, layout_result.components, config)
                new_tap_xs = _determine_final_positions(
                    unassigned, layout_result.components, layout_result, config,
                    incoming_chain_x=incoming_chain_x,
                )
                _rebuild_from_positions(unassigned, new_tap_xs, layout_result)
                for g, new_x in zip(unassigned, new_tap_xs):
                    all_final_groups.append(g)
                    all_final_tap_xs.append(new_x)
        else:
            # ── Single-DB path (backward compatible) ──
            _normalize_row_spacing(row_groups, layout_result.components, config)

            row_busbar_extent: tuple[float, float] | None = None
            if layout_result.busbar_x_per_row:
                if row_idx < len(layout_result.busbar_y_per_row):
                    row_busbar_y = layout_result.busbar_y_per_row[row_idx]
                    row_busbar_extent = layout_result.busbar_x_per_row.get(row_busbar_y)

            new_tap_xs = _determine_final_positions(
                row_groups, layout_result.components, layout_result, config,
                incoming_chain_x=incoming_chain_x,
                row_busbar_extent=row_busbar_extent,
            )
            _rebuild_from_positions(row_groups, new_tap_xs, layout_result)
            for g, new_x in zip(row_groups, new_tap_xs):
                all_final_groups.append(g)
                all_final_tap_xs.append(new_x)

    # Step 5: Fit busbar — skip for multi-DB (each region handles its own busbar)
    if all_final_groups and not regions:
        # Sort by final tap_x for correct leftmost/rightmost extent
        sorted_pairs = sorted(
            zip(all_final_tap_xs, all_final_groups),
            key=lambda p: p[0],
        )
        sorted_tap_xs = [p[0] for p in sorted_pairs]
        sorted_groups = [p[1] for p in sorted_pairs]

        _fit_busbar_to_groups(
            sorted_groups, sorted_tap_xs, layout_result,
            layout_result.components, config,
            incoming_chain_x=incoming_chain_x,
        )

        # Step 5b: Update secondary busbar positions (row 2+)
        _update_secondary_busbars(
            rows_map, all_final_groups, all_final_tap_xs,
            layout_result, config, incoming_chain_x,
        )

    return layout_result
