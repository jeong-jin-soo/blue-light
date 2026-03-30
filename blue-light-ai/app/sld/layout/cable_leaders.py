"""
SLD Layout cable leader lines — post-resolve cable spec annotations.

Extracted from overlap.py. Contains:
- _compute_safe_leader_bounds()
- _estimate_cable_text_num_lines()
- _draw_cable_leader_group()
- _add_cable_leader_lines()
"""

from __future__ import annotations

import logging
import re
from collections import OrderedDict

from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent
from app.sld.layout.overlap import SubCircuitGroup, _identify_groups

logger = logging.getLogger(__name__)


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
    region=None,
) -> tuple[float, float]:
    """Compute safe horizontal bounds for cable leader line extension.

    Considers SPARE circuit positions, adjacent cable group boundaries,
    circuit name label bounding boxes, and DB region boundaries (multi-DB)
    to prevent leader lines from overlapping with other labels or boards.

    Args:
        groups: If provided, also checks circuit name label bounding boxes.
        components: Required when groups is provided.

    Returns:
        (safe_left, safe_right) — horizontal bounds for leader extension.
    """
    _SPARE_GAP = config.spare_circuit_gap
    # Use region bounds for multi-DB (prevents leader invasion into adjacent boards)
    safe_left = region.min_x if region else config.min_x
    safe_right = region.max_x if region else config.max_x

    # Clamp to SPARE positions (leave gap for SPARE labels)
    for sx in spare_tap_xs:
        if sx < leftmost_x:
            safe_left = max(safe_left, sx + _SPARE_GAP)
        if sx > rightmost_x:
            safe_right = min(safe_right, sx - _SPARE_GAP)

    # Clamp to adjacent cable group boundaries
    # Margin accounts for cable text extending beyond leader endpoint:
    # text offset (3mm) + text half_width (~2.8mm) + clearance (0.2mm) = 6mm
    _CABLE_TEXT_MARGIN = config.cable_text_margin
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
        # Collect tap_xs for the current cable group to skip own labels
        current_group_taps = list(cable_groups.values())[gi]
        _CABLE_TEXT_HALF_W = config.label_char_height  # 2.8mm for 2-line text
        _LABEL_GAP = _CABLE_TEXT_HALF_W + 1.5  # 4.3mm total clearance
        for g in groups:
            if g.name_label_idx is None:
                continue
            # Skip labels belonging to circuits in the current cable group
            if any(abs(g.tap_x - tx) < 0.5 for tx in current_group_taps):
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
    config: "LayoutConfig | None" = None,
    label_y_override: float | None = None,
) -> None:
    """Draw one cable leader group: horizontal line, ticker marks, L-bend, and text.

    Appends connections (leader line, L-bend), thick_connections (tickers),
    and a LABEL component (cable spec text) to layout_result.

    Args:
        label_y_override: If set, cable text label starts at this Y instead of
            bend_top_y + 1. Used to align cable labels with sub-circuit labels.
    """
    # Horizontal leader line (DXF: LEADER entity, others: LINE)
    layout_result.leader_connections.append((
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
    text_y = label_y_override if label_y_override is not None else (bend_top_y + 1)
    # Clamp leader endpoints to drawing boundaries
    _max_x = config.max_x if config else 395.0
    _min_x = config.min_x if config else 25.0
    # For 90° rotated text, comp.x is the left/bottom edge.
    # To center text on the L-bend vertical line: x = bend_x - char_height / 2
    _ch = config.label_char_height if config else 2.8
    if text_on_left:
        _bend_x = max(leader_start_x, _min_x)
        layout_result.connections.append((
            (_bend_x, leader_y),
            (_bend_x, bend_top_y),
        ))
        layout_result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=max(_bend_x - _ch / 2, _min_x),
            y=text_y,
            label=cable_text,
            rotation=90.0,
        ))
    else:
        _bend_x = min(leader_end_x, _max_x - 3)
        layout_result.connections.append((
            (_bend_x, leader_y),
            (_bend_x, bend_top_y),
        ))
        layout_result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=_bend_x - _ch / 2,
            y=text_y,
            label=cable_text,
            rotation=90.0,
        ))


def _add_cable_leader_lines(
    layout_result: LayoutResult,
    config: LayoutConfig,
    *,
    region=None,
    busbar_end_x: float = 0,
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
    from app.sld.catalog import get_catalog as _gc
    _mcb_d = _gc().get("MCB")
    db_box_top_offset = (config.db_box_busbar_margin + _mcb_d.height + _mcb_d.stub
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

    # Find sub-circuit label Y to align cable labels with (if available)
    _subcircuit_label_y = None
    for comp in layout_result.components:
        if (comp.symbol_name == "LABEL" and comp.rotation == 90.0
                and comp.label and "sqmm" not in comp.label.lower()
                and comp.label != "SPARE"
                and not comp.label.startswith("2C ")
                and not comp.label.startswith("4 x")):
            _subcircuit_label_y = comp.y
            break

    for row_busbar_y, row_entries in rows_map.items():
        # Leader Y for this row (original position — not shifted by ISOLATOR)
        leader_y = row_busbar_y + db_box_top_offset + config.leader_margin_above_db

        # Group by cable spec within this row
        cable_groups: OrderedDict[str, list[float]] = OrderedDict()
        for tap_x, cable_spec in row_entries:
            cable_groups.setdefault(cable_spec, []).append(tap_x)

        # Estimate cable text height and clamp leader_y to keep text within top border
        max_spec_len = max(len(s) for s in cable_groups.keys())
        est_line_chars = max_spec_len // 2 + 5
        est_text_h = est_line_chars * config.char_w_label
        max_leader_y = config.max_y - config.leader_bend_height - 1 - est_text_h
        if leader_y > max_leader_y:
            leader_y = max_leader_y

        group_keys = list(cable_groups.keys())

        # Build circuit name label bounding boxes for collision detection
        # (X range only; at same Y zone, vertical ranges always overlap for 90° text)
        # Store (x_min, x_max, center_x) to allow filtering own-group names
        # Include SPARE labels — they occupy space and cable text must not overlap them.
        name_label_bbs: list[tuple[float, float, float]] = []
        for g in groups:
            if g.name_label_idx is None:
                continue
            comp = layout_result.components[g.name_label_idx]
            n_lines = max(len((comp.label or "").split("\\P")), 1)
            _ls = config.label_char_height * 1.4
            x_span = config.label_char_height + (n_lines - 1) * _ls
            hw = x_span / 2
            name_label_bbs.append((comp.x - hw, comp.x + hw, comp.x))

        # Track placed cable text bounding boxes for sequential collision avoidance
        placed_cable_bbs: list[tuple[float, float, float]] = []  # (x_min, x_max, y)

        def _has_collision(
            bb: tuple[float, float], y: float,
            own_tap_xs: list[float] | None = None,
            text_x: float | None = None,
        ) -> bool:
            """Check if cable text BB collides with name labels or placed cable texts.

            own_tap_xs: tap positions of the current cable group — names at these
            positions are excluded ONLY if they are far from the cable text anchor
            (i.e., they are mid-leader names that the leader line passes over).
            Names near text_x (the cable text placement point) are always checked.
            """
            _MARGIN = 0.5  # extra clearance to catch near-misses
            _TEXT_PROXIMITY = 8.0  # mm — labels within this distance of text_x are always checked
            for nx_min, nx_max, nx_center in name_label_bbs:
                # Skip own-group names ONLY if far from text placement point
                if own_tap_xs and any(abs(nx_center - tx) < 0.5 for tx in own_tap_xs):
                    if text_x is not None and abs(nx_center - text_x) < _TEXT_PROXIMITY:
                        pass  # Near text anchor — do NOT skip, check collision
                    else:
                        continue  # Mid-leader — safe to skip
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
                region=region,
            )
            # Clamp to busbar extent (prevents overlap with crossbar circuits)
            if busbar_end_x and busbar_end_x > 0:
                safe_right = min(safe_right, busbar_end_x + 5)

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
            # SVG line_spacing = char_height * 1.4
            # 다중 행 수직 텍스트: anchor(x)에서 +X 방향으로 각 행 추가
            _line_spacing = config.label_char_height * 1.4
            cable_x_span = config.label_char_height + (cable_num_lines - 1) * _line_spacing
            cable_hw = cable_x_span / 2  # 대칭 hw (collision avoidance용)
            cable_hw_right = cable_x_span  # 비대칭: anchor에서 우측으로 전체 확장
            text_x = (leader_start_x - config.label_char_height / 2) if text_on_left else (leader_end_x - config.label_char_height / 2)
            # 비대칭 bb: text_x 기준 좌측은 char_h/2, 우측은 cable_x_span
            cable_bb = (text_x - config.label_char_height / 2, text_x + cable_x_span)

            collision = _has_collision(cable_bb, effective_leader_y, own_tap_xs=tap_xs, text_x=text_x)

            if collision:
                # Attempt 1: flip text direction
                alt_on_left = not text_on_left
                if alt_on_left:
                    alt_ext = min(leader_extension, effective_left)
                    alt_start = leftmost_x - alt_ext
                    alt_text_x = alt_start - config.label_char_height / 2
                else:
                    alt_ext = min(leader_extension, effective_right)
                    alt_end = rightmost_x + alt_ext
                    alt_text_x = alt_end - config.label_char_height / 2
                alt_bb = (alt_text_x - config.label_char_height / 2, alt_text_x + cable_x_span)

                if not _has_collision(alt_bb, effective_leader_y, own_tap_xs=tap_xs, text_x=alt_text_x):
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
                    # Attempt 2: Progressive Y stagger (+4, +8, +12mm)
                    _stagger_resolved = False
                    for _stagger_offset in (4.0, 8.0, 12.0):
                        stagger_y = min(effective_leader_y + _stagger_offset, max_leader_y)
                        # Try original direction with stagger
                        if not _has_collision(cable_bb, stagger_y, own_tap_xs=tap_xs, text_x=text_x):
                            effective_leader_y = stagger_y
                            _stagger_resolved = True
                            break
                        # Try flipped direction with stagger
                        if not _has_collision(alt_bb, stagger_y, own_tap_xs=tap_xs, text_x=alt_text_x):
                            effective_leader_y = stagger_y
                            text_on_left = alt_on_left
                            if text_on_left:
                                leader_start_x = leftmost_x - alt_ext
                                leader_end_x = rightmost_x
                            else:
                                leader_start_x = leftmost_x
                                leader_end_x = rightmost_x + alt_ext
                            cable_bb = alt_bb
                            _stagger_resolved = True
                            break
                    if not _stagger_resolved:
                        # Attempt 3: extend leader beyond SPARE to drawing border
                        # Use absolute drawing bounds, not safe_left/safe_right
                        _resolved = False
                        _abs_max_right = config.max_x - 1  # drawing border with margin
                        _abs_min_left = config.min_x + 1
                        for _try_left in (text_on_left, not text_on_left):
                            for _extra in (4.0, 8.0, 12.0, 16.0, 24.0, 32.0):
                                if _try_left:
                                    _start2 = max(leftmost_x - leader_extension - _extra, _abs_min_left)
                                    _tx2 = _start2 - config.label_char_height / 2
                                    _bb2 = (_tx2 - config.label_char_height / 2, _tx2 + cable_x_span)
                                    if _bb2[0] < _abs_min_left:
                                        continue
                                    if not _has_collision(_bb2, effective_leader_y, own_tap_xs=tap_xs, text_x=_tx2):
                                        text_on_left = True
                                        leader_start_x = _start2
                                        leader_end_x = rightmost_x
                                        cable_bb = _bb2
                                        _resolved = True
                                        break
                                else:
                                    _end2 = min(rightmost_x + leader_extension + _extra, _abs_max_right)
                                    _tx2 = _end2 - config.label_char_height / 2
                                    _bb2 = (_tx2 - config.label_char_height / 2, _tx2 + cable_x_span)
                                    if _bb2[1] > _abs_max_right + cable_hw:
                                        continue
                                    if not _has_collision(_bb2, effective_leader_y, own_tap_xs=tap_xs, text_x=_tx2):
                                        text_on_left = False
                                        leader_start_x = leftmost_x
                                        leader_end_x = _end2
                                        cable_bb = _bb2
                                        _resolved = True
                                        break
                            if _resolved:
                                break
                        if not _resolved:
                            # Best-effort: apply stagger even if not fully resolved
                            effective_leader_y = stagger_y

            # Clamp to drawing bounds
            if effective_leader_y > max_leader_y:
                effective_leader_y = max_leader_y
            # Clamp leader endpoints to drawing X boundaries
            leader_start_x = max(leader_start_x, config.min_x)
            leader_end_x = min(leader_end_x, config.max_x - 1)

            # Register final cable text BB for subsequent collision checks
            _ch_half = config.label_char_height / 2
            final_text_x = (leader_start_x - _ch_half) if text_on_left else (leader_end_x - _ch_half)
            final_bb = (final_text_x - config.label_char_height / 2, final_text_x + cable_x_span)
            placed_cable_bbs.append((final_bb[0], final_bb[1], effective_leader_y))

            # Draw this cable group's leader lines
            _draw_cable_leader_group(
                tap_xs, cable_spec, effective_leader_y, text_on_left,
                leader_start_x, leader_end_x,
                config.leader_bend_height, tick_size, layout_result,
                config=config,
                label_y_override=_subcircuit_label_y,
            )
