"""Post-placement connectivity validation and correction.

Runs after ALL layout placement and post-processing (overlap resolution,
phase fanout, cable leader lines) are complete, but before vertical centering.

Snaps connection endpoints to actual symbol pin positions when they are
within tolerance — ensuring every electrical component's drawn stubs
align with the connection lines reaching them.

Design:
- Component-centric: iterate placed electrical components, compute absolute
  pin positions, find nearby connection endpoints, snap them.
- Tolerance-based: only snap endpoints within SNAP_TOLERANCE mm of a pin.
- Axis-aware: only snap along the connection's dominant axis to prevent
  creating diagonal connections from originally axis-aligned ones.
- Non-destructive: never creates new connections; only adjusts existing endpoints.
- Skips pseudo-components (LABEL, FLOW_ARROW, BUSBAR, CIRCUIT_ID_BOX, DB_INFO_BOX).
"""

from __future__ import annotations

import logging
import math
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.sld.layout.models import LayoutConfig, LayoutResult

logger = logging.getLogger(__name__)

# Pseudo-components that don't have electrical connection pins.
_PSEUDO_COMPONENTS = frozenset({
    "LABEL",
    "FLOW_ARROW",
    "FLOW_ARROW_UP",
    "BUSBAR",
    "CIRCUIT_ID_BOX",
    "DB_INFO_BOX",
})

# Maximum distance (mm) to snap a connection endpoint to a pin.
SNAP_TOLERANCE = 5.0

# Maximum cross-axis displacement (mm) allowed when snapping.
# Prevents turning axis-aligned connections into diagonals.
# Must be tight — even 1mm cross-axis shift creates visible diagonals
# on long connections.
_MAX_CROSS_AXIS = 0.5

# Skip snapping if endpoint is already closer than this (mm).
_EPSILON = 0.1


def _find_nearest_pin(
    pt: tuple[float, float],
    pins: list[tuple[float, float, int, str]],
    cross_axis: str | None,
) -> tuple[float, float] | None:
    """Find the nearest pin to *pt* within tolerance and axis constraints.

    Args:
        pt: The endpoint (x, y) to match.
        pins: List of (pin_x, pin_y, comp_idx, pin_name).
        cross_axis: "x" if cross-axis is X (horizontal connection → Y must match),
                    "y" if cross-axis is Y (vertical connection → X must match),
                    None if no axis constraint (short/diagonal connections).
    Returns:
        Nearest valid pin (px, py) or None.
    """
    best_dist = SNAP_TOLERANCE
    best_pin = None

    for px, py, _ci, _pn in pins:
        d = math.hypot(pt[0] - px, pt[1] - py)
        if d < _EPSILON:
            return None  # Already at a pin — no snapping needed.
        if d >= best_dist:
            continue

        # Cross-axis check: snap must not displace the perpendicular axis too much.
        if cross_axis == "x":
            # Connection is horizontal → Y must stay close.
            if abs(pt[1] - py) > _MAX_CROSS_AXIS:
                continue
        elif cross_axis == "y":
            # Connection is vertical → X must stay close.
            if abs(pt[0] - px) > _MAX_CROSS_AXIS:
                continue

        best_dist = d
        best_pin = (px, py)

    return best_pin


def validate_connectivity(result: LayoutResult, config: LayoutConfig) -> int:
    """Validate and correct connection-to-pin alignment for all components.

    For each electrical component, computes absolute pin positions and finds
    connection endpoints within ``SNAP_TOLERANCE`` mm. Snaps those endpoints
    to exact pin positions.

    Args:
        result: Complete LayoutResult with all components and connections.
        config: Layout configuration (currently unused, reserved for future).

    Returns:
        Number of endpoints snapped.
    """
    from app.sld.real_symbols import get_real_symbol

    # 1. Build pin registry: [(pin_x, pin_y, comp_idx, pin_name), ...]
    pins: list[tuple[float, float, int, str]] = []

    for comp_idx, comp in enumerate(result.components):
        if comp.symbol_name in _PSEUDO_COMPONENTS:
            continue

        try:
            sym = get_real_symbol(comp.symbol_name)
        except (ValueError, KeyError):
            continue

        # Determine orientation — must match generator.py:530-534
        use_horizontal = (
            comp.rotation == 90.0
            and hasattr(sym, "draw_horizontal")
            and getattr(comp, "label_style", "") != "breaker_block"
        )

        if use_horizontal:
            pin_dict = sym.horizontal_pins(comp.x, comp.y)
            # horizontal_pins returns stub-inclusive positions (body edge ± stub).
            # Connection lines reach body edges directly; DXF blocks have pins
            # at body edges, and procedural stubs overlap with connections.
            # → Adjust to body edge (remove stub offset) for correct snapping.
            stub = getattr(sym, '_stub', 2.0)
            adjusted: dict[str, tuple[float, float]] = {}
            for pn, (px, py) in pin_dict.items():
                if pn == "left":
                    adjusted[pn] = (px + stub, py)
                elif pn == "right":
                    adjusted[pn] = (px - stub, py)
                else:
                    adjusted[pn] = (px, py)
            pin_dict = adjusted
        else:
            pin_dict = sym.vertical_pins(comp.x, comp.y)
            # vertical_pins returns stub-inclusive positions (body edge ± stub).
            # Connection lines reach body edges directly; adjust to body edge
            # (remove stub offset) for correct snapping — same logic as horizontal.
            stub = getattr(sym, '_stub', 2.0)
            adjusted_v: dict[str, tuple[float, float]] = {}
            for pn, (px, py) in pin_dict.items():
                if pn == "bottom":
                    adjusted_v[pn] = (px, py + stub)  # move up to body bottom edge
                elif pn == "top":
                    adjusted_v[pn] = (px, py - stub)  # move down to body top edge
                else:
                    adjusted_v[pn] = (px, py)
            pin_dict = adjusted_v

        for pin_name, (px, py) in pin_dict.items():
            pins.append((px, py, comp_idx, pin_name))

    # 2. Build frozen points from junction_arrows.
    # Junction arrows (CT hooks) are drawn at exact positions — connection
    # endpoints at these positions must NOT be snapped away, because the
    # hook and the line emerging from it are one visual unit.
    frozen: set[tuple[float, float]] = set()
    for ja_cx, ja_cy, _ja_dir in result.junction_arrows:
        frozen.add((ja_cx, ja_cy))

    # 3. Snap connection endpoints to nearest pins.
    snapped = 0

    # Only process regular connections (solid lines).
    # thick_connections = cable tick marks (decorative, not electrical).
    # dashed_connections = DB box boundaries (not electrical).
    conn_lists = [result.connections]

    for conn_list in conn_lists:
        for conn_idx in range(len(conn_list)):
            start, end = conn_list[conn_idx]

            # Determine connection orientation for axis constraint.
            dx = abs(end[0] - start[0])
            dy = abs(end[1] - start[1])
            length = math.hypot(dx, dy)

            if length < _EPSILON:
                continue  # Skip zero-length connections.

            # Determine cross-axis constraint.
            if dx < 1.0 and dy > 2.0:
                cross_axis = "y"  # Vertical → X must stay close.
            elif dy < 1.0 and dx > 2.0:
                cross_axis = "x"  # Horizontal → Y must stay close.
            else:
                cross_axis = None  # Short or diagonal — allow any axis.

            new_start = start
            new_end = end
            snap_s = False
            snap_e = False

            # Check if endpoints are frozen (at junction_arrow positions).
            start_frozen = any(
                math.hypot(start[0] - fx, start[1] - fy) < _EPSILON
                for fx, fy in frozen
            )
            end_frozen = any(
                math.hypot(end[0] - fx, end[1] - fy) < _EPSILON
                for fx, fy in frozen
            )

            if not start_frozen:
                pin_s = _find_nearest_pin(start, pins, cross_axis)
                if pin_s is not None:
                    new_start = pin_s
                    snap_s = True

            if not end_frozen:
                pin_e = _find_nearest_pin(end, pins, cross_axis)
                if pin_e is not None:
                    new_end = pin_e
                    snap_e = True

            # Prevent zero-length result.
            if snap_s or snap_e:
                if math.hypot(new_start[0] - new_end[0],
                              new_start[1] - new_end[1]) < _EPSILON:
                    continue  # Would create zero-length — skip.

                conn_list[conn_idx] = (new_start, new_end)
                if snap_s:
                    snapped += 1
                if snap_e:
                    snapped += 1

    if snapped:
        logger.debug("Connectivity validation: snapped %d endpoint(s)", snapped)

    return snapped
