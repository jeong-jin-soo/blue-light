"""
SLD Layout overlap resolution system.

Contains the 5-step overlap resolution pipeline:
1. _identify_groups()           — classify components/connections by sub-circuit
2. _compute_group_width()       — bounding box based minimum widths
3. _determine_final_positions() — single-pass left-to-right with bounds fit
4. _rebuild_from_positions()    — index-based absolute repositioning
5. _fit_busbar_to_groups()      — extent-aware busbar fitting

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


def _compute_dynamic_spacing(num_circuits: int, config: LayoutConfig) -> float:
    """Compute horizontal spacing that expands to fill available width.

    For fewer circuits, spacing increases (up to max_horizontal_spacing)
    so the diagram fills the drawing area. For many circuits, spacing
    decreases (down to min_horizontal_spacing).

    Note: cable schedule reserve is handled by shifting cx in compute_layout(),
    so the busbar naturally occupies a narrower zone.
    """
    effective_count = min(num_circuits, config.max_circuits_per_row)
    if effective_count <= 1:
        return config.horizontal_spacing

    max_bus_width = config.max_x - config.min_x - 40  # ~330mm
    ideal_spacing = (max_bus_width - 2 * config.busbar_margin) / effective_count
    return max(config.min_horizontal_spacing,
               min(ideal_spacing, config.max_horizontal_spacing))


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
    _SPARE = SG_LOCALE.circuit.spare
    for i, comp in enumerate(components):
        if (comp.symbol_name == "LABEL"
                and abs(comp.rotation - 90.0) < 0.1
                and comp.label.strip().upper() == _SPARE):
            tap_x = comp.x  # SPARE label placed at tap_x
            g = SubCircuitGroup(tap_x=tap_x, spare_label_idx=i, is_spare=True)
            groups.append(g)

    if not groups:
        return [], 0.0

    # Sort by tap_x
    groups.sort(key=lambda g: g.tap_x)
    tap_xs_set = {g.tap_x for g in groups}

    # Step 3: Determine incoming chain x
    # Primary: use spine_x from compute_layout() (deterministic, always correct)
    # Fallback: heuristic histogram detection (legacy, for external callers)
    incoming_chain_x = 0.0

    if layout_result.spine_x > 0:
        incoming_chain_x = layout_result.spine_x
    else:
        # Legacy: histogram-based detection for backward compatibility
        vert_conn_x_count: dict[float, int] = {}
        for ci, ((sx, sy), (ex, ey)) in enumerate(connections):
            if abs(sx - ex) < 0.5:  # Vertical connection
                x_val = round(sx, 1)
                vert_conn_x_count[x_val] = vert_conn_x_count.get(x_val, 0) + 1

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

    # Transition validation: compare spine_x with histogram result
    if layout_result.spine_x > 0 and incoming_chain_x > 0:
        # Also run histogram for cross-check (debug only)
        _histo_x = 0.0
        _histo_count: dict[float, int] = {}
        for ci, ((sx, sy), (ex, ey)) in enumerate(connections):
            if abs(sx - ex) < 0.5:
                x_val = round(sx, 1)
                _histo_count[x_val] = _histo_count.get(x_val, 0) + 1
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
        if comp.label.strip().upper() == _SPARE:
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

    # Diagnostic: detect unmatched (orphan) elements
    _validate_group_completeness(groups, components, layout_result, incoming_chain_x)

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

    # Check junction_dot matching
    jd_matched = {g.junction_dot_idx for g in groups if g.junction_dot_idx is not None}
    total_jds = len(layout_result.junction_dots)
    orphan_jds = 0
    for di, (dx, dy) in enumerate(layout_result.junction_dots):
        if di not in jd_matched and abs(dx - incoming_chain_x) >= _TOL:
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

        # Distribute surplus space evenly between groups
        # so sub-circuits fill the available busbar width
        actual_span = new_tap_xs[-1] - new_tap_xs[0]
        max_span = sc_bus_end - sc_bus_start - left_exts[0] - right_exts[-1]
        surplus = max_span - actual_span
        if surplus > 0 and n > 1:
            # Cap per-gap bonus to max_horizontal_spacing limit
            per_gap_bonus = surplus / (n - 1)
            max_gap_bonus = config.max_horizontal_spacing - config.horizontal_spacing
            per_gap_bonus = min(per_gap_bonus, max_gap_bonus)
            for i in range(1, n):
                new_tap_xs[i] += per_gap_bonus * i

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

        # Estimate cable text height and clamp leader_y to keep text within top border
        _CHAR_W_EST = 1.8  # Approximate char width for char_height 2.8
        max_spec_len = max(len(s) for s in cable_groups.keys())
        # After multiline split, longest line is roughly half + some margin
        est_line_chars = max_spec_len // 2 + 5
        est_text_h = est_line_chars * _CHAR_W_EST
        max_leader_y = config.max_y - config.leader_bend_height - 1 - est_text_h
        if leader_y > max_leader_y:
            leader_y = max_leader_y

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
                leader_start_x = max(leftmost_x - leader_extension, config.min_x)
                leader_end_x = rightmost_x
            else:
                leader_start_x = leftmost_x
                leader_end_x = min(rightmost_x + leader_extension, config.max_x)

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
    _GROUP_GAP = 3.0  # Extra mm between circuit category groups (compact)
    if len(groups) > 1:
        prev_prefix = ""
        for gi, g in enumerate(groups):
            cid = ""
            if g.breaker_idx is not None:
                comp = layout_result.components[g.breaker_idx]
                cid = comp.circuit_id or ""
            cur_match = re.match(r"[A-Za-z]+", cid)
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
            pfx_match = re.match(r"[A-Za-z]+", cid)
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
