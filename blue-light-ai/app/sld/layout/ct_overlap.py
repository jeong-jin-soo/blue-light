"""CT metering section overlap detection and auto-adjustment.

Runs after _place_ct_metering_section() completes, before _place_elcb().

Detects bounding-box overlaps between horizontal branches (ASS, VSS, KWH,
ELR) and spine-side labels (CT labels), then pushes overlapping elements
apart along the Y axis.

Design:
- Identifies CT metering components by symbol_name and Y range.
- Groups horizontal branch components by direction (LEFT/RIGHT).
- Checks same-direction branches for Y-axis AABB overlap.
- Checks CT spine labels vs. RIGHT branch components.
- Pushes the upper element upward with minimum clearance.
- Updates connections, junction_arrows, and spine backbone.
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent

logger = logging.getLogger(__name__)

# Symbols that live on horizontal CT metering branches.
_BRANCH_SYMBOLS = frozenset({
    "SELECTOR_SWITCH", "AMMETER", "VOLTMETER",
    "KWH_METER", "ELR",
    "POTENTIAL_FUSE", "INDICATOR_LIGHTS",
})

# Minimum clearance (mm) between adjacent same-side branches.
_MIN_CLEARANCE = 2.0

# Tolerance for matching Y coordinates.
_Y_TOL = 0.5


# ---------------------------------------------------------------------------
# Data model
# ---------------------------------------------------------------------------

@dataclass
class _Branch:
    """One horizontal branch in the CT metering section."""

    branch_y: float
    direction: str  # "left" or "right"
    comp_indices: list[int] = field(default_factory=list)
    conn_indices: list[int] = field(default_factory=list)
    jarrow_idx: int | None = None  # index in result.junction_arrows
    bbox_y_min: float = 0.0
    bbox_y_max: float = 0.0


@dataclass
class _SpineLabel:
    """Label of a spine component (CT, BI_CONNECTOR, MCCB)."""

    comp_idx: int
    bbox_y_min: float
    bbox_y_max: float
    bbox_x_min: float
    bbox_x_max: float


# ---------------------------------------------------------------------------
# Bounding-box helpers
# ---------------------------------------------------------------------------

def _branch_y_extent(
    comp: "PlacedComponent",
) -> tuple[float, float]:
    """Return (y_min, y_max) of a horizontal branch component body.

    For rotation=90 components, the body is centered on comp.y.
    """
    from app.sld.real_symbols import get_real_symbol

    try:
        sym = get_real_symbol(comp.symbol_name)
    except (ValueError, KeyError):
        return (comp.y - 4.0, comp.y + 4.0)  # conservative fallback

    # Horizontal branch components: body centered vertically on branch_y.
    if abs(comp.rotation - 90.0) < 0.1 and hasattr(sym, "draw_horizontal"):
        # v_extent depends on symbol: for most it's sym.width (rotated),
        # for KWH_METER it's _rect_h, for ELR it's sym.height.
        if hasattr(sym, "_rect_h"):
            v_half = sym._rect_h / 2
        else:
            v_half = sym.width / 2
        return (comp.y - v_half, comp.y + v_half)

    # Vertical component (spine): body from comp.y to comp.y + height.
    return (comp.y, comp.y + sym.height)


def _label_y_extent(
    comp: "PlacedComponent",
    ch: float = 1.6,
) -> tuple[float, float]:
    """Return (y_min, y_max) of a component's label text.

    For horizontal branch components, the label is placed above/beside the
    symbol.  For spine components (CT), the label is to the right.
    """
    label = comp.label or ""
    if not label:
        return (comp.y, comp.y)

    lines = label.split("\\P")
    num_lines = len(lines)
    text_h = num_lines * ch * 1.4

    # Horizontal branch: label above component (default generator path).
    if abs(comp.rotation - 90.0) < 0.1:
        from app.sld.real_symbols import get_real_symbol
        try:
            sym = get_real_symbol(comp.symbol_name)
            v_half = sym.width / 2 if not hasattr(sym, "_rect_h") else getattr(sym, "_rect_h", 4) / 2
        except (ValueError, KeyError):
            v_half = 4.0
        label_top = comp.y + v_half + 2.5 + text_h  # above symbol
        label_bot = comp.y + v_half + 2.5
        return (label_bot, label_top)

    # Spine component (vertical): label to the right.
    if comp.label_y_override is not None:
        label_top = comp.label_y_override
    else:
        from app.sld.real_symbols import get_real_symbol
        try:
            sym = get_real_symbol(comp.symbol_name)
            label_top = comp.y + sym.height / 2 + 2
        except (ValueError, KeyError):
            label_top = comp.y + 4
    label_bot = label_top - text_h
    return (label_bot, label_top)


def _branch_bbox(
    branch: _Branch,
    components: list["PlacedComponent"],
) -> tuple[float, float]:
    """Compute combined (y_min, y_max) for all components + labels on a branch."""
    y_min = float("inf")
    y_max = float("-inf")

    for ci in branch.comp_indices:
        comp = components[ci]
        cy_lo, cy_hi = _branch_y_extent(comp)
        y_min = min(y_min, cy_lo)
        y_max = max(y_max, cy_hi)

        # Include label extent.
        ly_lo, ly_hi = _label_y_extent(comp)
        y_min = min(y_min, ly_lo)
        y_max = max(y_max, ly_hi)

    return (y_min, y_max)


def _spine_label_bbox(
    comp: "PlacedComponent",
) -> _SpineLabel | None:
    """Compute bounding box of a spine component's label (CT, MCCB etc.)."""
    label = comp.label or ""
    if not label:
        return None

    from app.sld.real_symbols import get_real_symbol

    lines = label.split("\\P")
    max_len = max(len(ln) for ln in lines)
    num_lines = len(lines)
    ch = 1.6
    text_w = max_len * ch * 0.6
    text_h = num_lines * ch * 1.4

    try:
        sym = get_real_symbol(comp.symbol_name)
        lx = comp.x + sym.width + 3
    except (ValueError, KeyError):
        lx = comp.x + 5

    if comp.label_y_override is not None:
        ly_top = comp.label_y_override
    else:
        try:
            ly_top = comp.y + sym.height / 2 + 2
        except Exception:
            ly_top = comp.y + 4
    ly_bot = ly_top - text_h

    return _SpineLabel(
        comp_idx=0,  # filled by caller
        bbox_y_min=ly_bot,
        bbox_y_max=ly_top,
        bbox_x_min=lx,
        bbox_x_max=lx + text_w,
    )


# ---------------------------------------------------------------------------
# Branch collection
# ---------------------------------------------------------------------------

def _collect_branches(
    result: "LayoutResult",
) -> tuple[list[_Branch], list[int], list[int]]:
    """Identify horizontal branches and spine CTs in the CT metering section.

    Returns:
        (branches, spine_ct_indices, bi_connector_indices)
    """
    components = result.components
    connections = result.connections
    jarrows = result.junction_arrows

    # 1. Find CT metering junction_arrows (hook symbols on spine).
    #    Each junction_arrow spawns one horizontal branch.
    branches: list[_Branch] = []

    for ja_idx, (jx, jy, jdir) in enumerate(jarrows):
        branch = _Branch(branch_y=jy, direction=jdir, jarrow_idx=ja_idx)

        # 2. Find branch components (on the same Y, extending away from spine).
        for ci, comp in enumerate(components):
            if comp.symbol_name not in _BRANCH_SYMBOLS:
                continue
            if abs(comp.y - jy) > _Y_TOL:
                continue
            # Check direction: LEFT branch components have x < jx,
            # RIGHT branch components have x > jx (approximately).
            if jdir == "left" and comp.x < jx:
                branch.comp_indices.append(ci)
            elif jdir == "right" and comp.x > jx - 1:
                branch.comp_indices.append(ci)

        # 3. Find branch connections (horizontal lines at branch_y).
        for conn_idx, ((sx, sy), (ex, ey)) in enumerate(connections):
            if abs(sy - jy) < _Y_TOL and abs(ey - jy) < _Y_TOL:
                # Horizontal line at branch_y.
                if jdir == "left" and min(sx, ex) < jx:
                    branch.conn_indices.append(conn_idx)
                elif jdir == "right" and max(sx, ex) > jx:
                    branch.conn_indices.append(conn_idx)

        if branch.comp_indices:
            branches.append(branch)

    # 4. Find spine CTs and BI_CONNECTORs.
    spine_ct_indices = [
        i for i, c in enumerate(components) if c.symbol_name == "CT"
    ]
    bi_connector_indices = [
        i for i, c in enumerate(components) if c.symbol_name == "BI_CONNECTOR"
    ]

    return branches, spine_ct_indices, bi_connector_indices


# ---------------------------------------------------------------------------
# Overlap detection
# ---------------------------------------------------------------------------

def _detect_and_resolve(
    branches: list[_Branch],
    spine_ct_indices: list[int],
    result: "LayoutResult",
) -> int:
    """Detect overlaps and push branches apart.

    Returns number of adjustments made.
    """
    components = result.components

    # Compute bounding boxes.
    for branch in branches:
        y_lo, y_hi = _branch_bbox(branch, components)
        branch.bbox_y_min = y_lo
        branch.bbox_y_max = y_hi

    # Group branches by direction.
    right_branches = sorted(
        [b for b in branches if b.direction == "right"],
        key=lambda b: b.branch_y,
    )
    left_branches = sorted(
        [b for b in branches if b.direction == "left"],
        key=lambda b: b.branch_y,
    )

    adjustments = 0

    # Check same-direction branch pairs (sorted bottom-to-top).
    for group in (right_branches, left_branches):
        for i in range(len(group) - 1):
            lower = group[i]
            upper = group[i + 1]

            overlap = lower.bbox_y_max + _MIN_CLEARANCE - upper.bbox_y_min
            if overlap > 0:
                _shift_branch(upper, overlap, result)
                # Recompute bbox after shift.
                upper.bbox_y_min += overlap
                upper.bbox_y_max += overlap
                upper.branch_y += overlap
                adjustments += 1
                logger.debug(
                    "CT overlap: shifted %s branch at y=%.1f up by %.1fmm",
                    upper.direction, upper.branch_y - overlap, overlap,
                )

    # Check spine CT labels vs. RIGHT branches.
    for ct_idx in spine_ct_indices:
        ct_comp = components[ct_idx]
        slabel = _spine_label_bbox(ct_comp)
        if slabel is None:
            continue

        for branch in right_branches:
            # Only check if they overlap in X (label extends rightward from spine,
            # branch extends rightward from spine + arm_len).
            # The CT label is close to the spine; branches start at arm_len away.
            # They only overlap in X if the label is very wide or arm is short.
            # For typical layouts, the arm_len (15mm) ensures no X overlap.
            # Still check Y overlap for safety.
            branch_x_min = min(
                components[ci].x for ci in branch.comp_indices
            ) if branch.comp_indices else 999

            # If CT label and branch overlap in both X and Y:
            if (slabel.bbox_x_max > branch_x_min
                    and slabel.bbox_y_max + _MIN_CLEARANCE > branch.bbox_y_min
                    and slabel.bbox_y_min < branch.bbox_y_max):
                overlap = slabel.bbox_y_max + _MIN_CLEARANCE - branch.bbox_y_min
                if overlap > 0:
                    _shift_branch(branch, overlap, result)
                    branch.bbox_y_min += overlap
                    branch.bbox_y_max += overlap
                    branch.branch_y += overlap
                    adjustments += 1
                    logger.debug(
                        "CT label overlap: shifted branch at y=%.1f up by %.1fmm",
                        branch.branch_y - overlap, overlap,
                    )

    return adjustments


# ---------------------------------------------------------------------------
# Branch shifting
# ---------------------------------------------------------------------------

def _shift_branch(
    branch: _Branch,
    delta_y: float,
    result: "LayoutResult",
) -> None:
    """Shift all elements of a branch by delta_y (positive = upward)."""
    components = result.components
    connections = result.connections

    # Components.
    for ci in branch.comp_indices:
        comp = components[ci]
        comp.y += delta_y
        if comp.label_y_override is not None:
            comp.label_y_override += delta_y

    # Connections.
    for conn_idx in branch.conn_indices:
        (sx, sy), (ex, ey) = connections[conn_idx]
        connections[conn_idx] = ((sx, sy + delta_y), (ex, ey + delta_y))

    # Junction arrow.
    if branch.jarrow_idx is not None:
        jx, jy, jdir = result.junction_arrows[branch.jarrow_idx]
        result.junction_arrows[branch.jarrow_idx] = (jx, jy + delta_y, jdir)


def _extend_spine_if_needed(
    result: "LayoutResult",
    bi_connector_indices: list[int],
) -> None:
    """Extend the spine backbone connection if BI connector was pushed up.

    The spine is one straight vertical line.  Find it and update its top Y
    to match the highest BI connector top + stub.
    """
    if not bi_connector_indices:
        return

    from app.sld.real_symbols import get_symbol_dimensions

    components = result.components
    bi_dims = get_symbol_dimensions("BI_CONNECTOR")
    bi_stub = bi_dims["stub_mm"]
    bi_h = bi_dims["height_mm"]

    # Find the highest BI connector top.
    max_bi_top = max(
        components[i].y + bi_h + bi_stub for i in bi_connector_indices
    )

    # Find spine backbone (vertical line at spine_x spanning the CT section).
    spine_x = getattr(result, "spine_x", None)
    if spine_x is None:
        return

    for conn_idx, ((sx, sy), (ex, ey)) in enumerate(result.connections):
        # Spine: vertical line at spine_x, spanning a large Y range.
        if (abs(sx - spine_x) < 1.0 and abs(ex - spine_x) < 1.0
                and abs(sx - ex) < 1.0 and abs(ey - sy) > 10.0):
            # This is a spine segment.  Extend top if needed.
            top_y = max(sy, ey)
            bot_y = min(sy, ey)
            if max_bi_top > top_y:
                if sy > ey:
                    result.connections[conn_idx] = ((sx, max_bi_top), (ex, ey))
                else:
                    result.connections[conn_idx] = ((sx, sy), (ex, max_bi_top))
                logger.debug(
                    "Extended spine from y=%.1f to y=%.1f", top_y, max_bi_top,
                )


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def validate_ct_metering_overlaps(
    result: "LayoutResult",
    config: "LayoutConfig",
) -> int:
    """Detect and resolve CT metering section overlaps.

    Runs after ``_place_ct_metering_section()`` completes.

    Args:
        result: LayoutResult with CT metering components placed.
        config: Layout configuration.

    Returns:
        Number of overlap adjustments made (0 if no overlaps).
    """
    branches, spine_ct_indices, bi_indices = _collect_branches(result)

    if not branches:
        return 0  # No CT metering section.

    adjustments = _detect_and_resolve(
        branches, spine_ct_indices, result,
    )

    if adjustments > 0:
        _extend_spine_if_needed(result, bi_indices)
        logger.debug(
            "CT metering overlap resolution: %d adjustment(s)", adjustments,
        )

    return adjustments
