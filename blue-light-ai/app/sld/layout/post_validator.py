"""
Post-layout automatic validation and auto-fix for SLD layouts.

Runs after compute_layout() to detect and fix common rendering issues:
1. Margin clearance — content too close to page border
2. Text overlaps — rotated labels overlapping each other
3. Busbar disconnection — circuits not reaching their busbar
4. SPARE disconnection — spare stubs not connected to crossbar

Integrated into SldPipeline via validate_and_fix().
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)


@dataclass
class ValidationIssue:
    """A single layout issue detected by post-layout validation."""
    type: str           # MARGIN, TEXT_OVERLAP, DISCONNECTED, SPARE_DISCONNECTED
    severity: str       # critical, warning
    detail: str
    component_idx: int | None = None
    connection_idx: int | None = None
    fix_applied: bool = False


# ---------------------------------------------------------------------------
# 1. Margin clearance check
# ---------------------------------------------------------------------------

def _check_margin_clearance(
    result, config, *, physical_margin: float = 15.0,
) -> list[ValidationIssue]:
    """Check that all components stay within usable area (with margin)."""
    issues = []
    min_x = config.min_x + physical_margin
    max_x = config.max_x - physical_margin

    for i, comp in enumerate(result.components):
        if comp.x < config.min_x or comp.x > config.max_x:
            issues.append(ValidationIssue(
                type="MARGIN", severity="warning",
                detail=f"Component '{comp.symbol_name}' at x={comp.x:.1f} outside [{config.min_x:.0f},{config.max_x:.0f}]",
                component_idx=i,
            ))
    return issues


# ---------------------------------------------------------------------------
# 2. Text overlap check (90-degree rotated labels)
# ---------------------------------------------------------------------------

def _check_text_overlaps(result, config) -> list[ValidationIssue]:
    """Detect overlapping 90-degree rotated text labels."""
    issues = []
    labels = []

    for i, comp in enumerate(result.components):
        if comp.symbol_name in ("LABEL", "CIRCUIT_ID_BOX") and abs(comp.rotation - 90.0) < 1:
            ch = getattr(config, 'label_char_height', 2.2)
            labels.append((comp.x, ch, i, comp.symbol_name))

    labels.sort(key=lambda t: t[0])

    for j in range(len(labels) - 1):
        x1, h1, idx1, name1 = labels[j]
        x2, h2, idx2, name2 = labels[j + 1]
        gap = abs(x2 - x1)
        min_gap = (h1 + h2) / 2
        if gap < min_gap:
            issues.append(ValidationIssue(
                type="TEXT_OVERLAP", severity="warning",
                detail=f"{name1}[{idx1}] ↔ {name2}[{idx2}] gap={gap:.1f}mm < {min_gap:.1f}mm",
                component_idx=idx1,
            ))
    return issues


# ---------------------------------------------------------------------------
# 3. Busbar connection check
# ---------------------------------------------------------------------------

def _check_busbar_connections(result, config) -> list[ValidationIssue]:
    """Check that circuit connections start at the busbar Y coordinate.

    Skips connections intentionally truncated by fan-out (phase_fanout.py):
    side circuits start at intermediate_y, not busbar_y.
    """
    issues = []
    busbar_y = getattr(result, 'busbar_y', None)
    if busbar_y is None:
        return issues

    # Collect all busbar Y values (main + sub-DB)
    busbar_ys = {round(busbar_y, 1)}
    for comp in result.components:
        if comp.symbol_name == "BUSBAR":
            busbar_ys.add(round(comp.y, 1))

    # Build set of X coords for fan-out side circuits (intentionally not at busbar)
    fanout_side_xs: set[float] = set()
    for fg in getattr(result, 'fanout_groups', []):
        _center_x, _by, _side_xs = fg
        for sx in _side_xs:
            fanout_side_xs.add(round(sx, 1))

    for i, conn in enumerate(result.connections):
        start_x = conn[0][0]
        start_y = conn[0][1]
        end_y = conn[1][1]
        # Vertical connections should start or end at a busbar
        if abs(conn[0][0] - conn[1][0]) < 0.5:  # vertical line
            # Skip fan-out side circuits (intentionally truncated)
            if round(start_x, 1) in fanout_side_xs:
                continue
            near_busbar = any(abs(start_y - by) < 2.0 for by in busbar_ys)
            if not near_busbar:
                # Check if end touches busbar instead
                near_busbar_end = any(abs(end_y - by) < 2.0 for by in busbar_ys)
                if not near_busbar_end:
                    issues.append(ValidationIssue(
                        type="DISCONNECTED", severity="critical",
                        detail=f"Connection[{i}] ({conn[0][0]:.1f},{start_y:.1f})→({conn[1][0]:.1f},{end_y:.1f}) not at any busbar",
                        connection_idx=i,
                    ))
    return issues


# ---------------------------------------------------------------------------
# 4. SPARE disconnection check
# ---------------------------------------------------------------------------

def _check_spare_connections(result, config) -> list[ValidationIssue]:
    """Check that SPARE circuits have connections to the busbar."""
    issues = []
    spare_xs = set()

    for comp in result.components:
        if comp.symbol_name == "CB_SPARE":
            spare_xs.add(round(comp.x + 1.0, 1))  # approximate center

    for sx in spare_xs:
        has_conn = any(
            abs(conn[0][0] - sx) < 3.0 or abs(conn[1][0] - sx) < 3.0
            for conn in result.connections
        )
        if not has_conn:
            issues.append(ValidationIssue(
                type="SPARE_DISCONNECTED", severity="critical",
                detail=f"SPARE at x≈{sx:.1f} has no connection to busbar",
            ))
    return issues


# ---------------------------------------------------------------------------
# Main validation entry point
# ---------------------------------------------------------------------------

def validate_layout(result, config) -> list[ValidationIssue]:
    """Run all post-layout validation checks."""
    issues = []
    issues += _check_margin_clearance(result, config)
    issues += _check_text_overlaps(result, config)
    issues += _check_busbar_connections(result, config)
    issues += _check_spare_connections(result, config)
    return issues


# ---------------------------------------------------------------------------
# Auto-fix functions
# ---------------------------------------------------------------------------

def _fix_margin(result, config, issues: list[ValidationIssue]) -> int:
    """Shrink content scale to add more margin. Returns count of fixes."""
    if not issues:
        return 0
    # Reduce component_scale by 5% to pull content inward
    old_scale = config.component_scale
    config.component_scale = old_scale * 0.95
    logger.info(f"Auto-fix: margin — scale {old_scale:.3f} → {config.component_scale:.3f}")
    for issue in issues:
        issue.fix_applied = True
    return len(issues)


def _fix_text_overlap(result, config, issues: list[ValidationIssue]) -> int:
    """Truncate overlapping text labels to reduce collision. Returns count."""
    fixes = 0
    _ABBREVIATIONS = {
        "LIGHTING POINTS": "LTG PTS",
        "DOUBLE S/S/O": "DBL S/O",
        "LIGHTING POINT": "LTG PT",
        "HEATER POINT": "HTR PT",
        "DP ISOLATOR": "DP ISO",
        "TPN ISOLATOR": "TPN ISO",
    }
    for issue in issues:
        idx = issue.component_idx
        if idx is not None and idx < len(result.components):
            comp = result.components[idx]
            if comp.symbol_name == "LABEL" and comp.label:
                original = comp.label
                for full, abbr in _ABBREVIATIONS.items():
                    if full in comp.label:
                        comp.label = comp.label.replace(full, abbr)
                        break
                if comp.label != original:
                    issue.fix_applied = True
                    fixes += 1
    return fixes


def _fix_disconnected(result, config, issues: list[ValidationIssue]) -> int:
    """Snap disconnected connections to nearest busbar. Returns count."""
    busbar_y = getattr(result, 'busbar_y', None)
    if busbar_y is None:
        return 0

    busbar_ys = [busbar_y]
    for comp in result.components:
        if comp.symbol_name == "BUSBAR":
            busbar_ys.append(comp.y)

    fixes = 0
    for issue in issues:
        idx = issue.connection_idx
        if idx is not None and idx < len(result.connections):
            conn = result.connections[idx]
            start = conn[0]
            # Find nearest busbar Y
            nearest_by = min(busbar_ys, key=lambda by: abs(start[1] - by))
            if abs(start[1] - nearest_by) < 30:
                result.connections[idx] = ((start[0], nearest_by), conn[1])
                result.junction_dots.append((start[0], nearest_by))
                issue.fix_applied = True
                fixes += 1
    return fixes


def _fix_spare_disconnected(result, config, issues: list[ValidationIssue]) -> int:
    """Add missing connections for disconnected SPARE circuits. Returns count."""
    busbar_y = getattr(result, 'busbar_y', None)
    if busbar_y is None:
        return 0
    fixes = 0
    for issue in issues:
        if "x≈" in issue.detail:
            try:
                sx = float(issue.detail.split("x≈")[1].split(" ")[0])
                # Add vertical connection from busbar to SPARE
                spare_y = busbar_y + 12  # default gap
                result.connections.append(((sx, busbar_y), (sx, spare_y)))
                result.junction_dots.append((sx, busbar_y))
                issue.fix_applied = True
                fixes += 1
            except (ValueError, IndexError):
                pass
    return fixes


# ---------------------------------------------------------------------------
# Combined validate-and-fix
# ---------------------------------------------------------------------------

def validate_and_fix(result, config, *, max_attempts: int = 2) -> list[ValidationIssue]:
    """Run validation, auto-fix critical issues, re-validate.

    Returns final list of remaining issues (ideally empty).
    """
    all_issues = []

    for attempt in range(max_attempts):
        issues = validate_layout(result, config)
        if not issues:
            logger.info(f"Post-layout validation: PASS (attempt {attempt + 1})")
            return all_issues

        # Classify
        margin_issues = [i for i in issues if i.type == "MARGIN"]
        overlap_issues = [i for i in issues if i.type == "TEXT_OVERLAP"]
        disconn_issues = [i for i in issues if i.type == "DISCONNECTED"]
        spare_issues = [i for i in issues if i.type == "SPARE_DISCONNECTED"]

        total_fixes = 0
        if margin_issues:
            total_fixes += _fix_margin(result, config, margin_issues)
        if overlap_issues:
            total_fixes += _fix_text_overlap(result, config, overlap_issues)
        if disconn_issues:
            total_fixes += _fix_disconnected(result, config, disconn_issues)
        if spare_issues:
            total_fixes += _fix_spare_disconnected(result, config, spare_issues)

        all_issues.extend(issues)

        summary = (
            f"Post-layout validation attempt {attempt + 1}: "
            f"{len(issues)} issues ({len(margin_issues)} margin, "
            f"{len(overlap_issues)} overlap, {len(disconn_issues)} disconn, "
            f"{len(spare_issues)} spare), {total_fixes} auto-fixed"
        )
        logger.info(summary)

        if total_fixes == 0:
            break  # No more fixes possible

    return all_issues
