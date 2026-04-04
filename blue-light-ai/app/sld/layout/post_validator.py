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
    """Detect physically overlapping 90-degree rotated text labels.

    Only flags actual overlaps (gap < 0.5mm), not tight-but-readable spacing.
    Overlap resolution (Step C) and font measurement (4c) handle normal spacing.
    """
    issues = []
    labels = []
    _MIN_GAP = 0.5  # mm — physical overlap threshold

    for i, comp in enumerate(result.components):
        if comp.symbol_name in ("LABEL", "CIRCUIT_ID_BOX") and abs(comp.rotation - 90.0) < 1:
            ch = getattr(config, 'label_char_height', 2.2)
            labels.append((comp.x, ch, i, comp.symbol_name))

    labels.sort(key=lambda t: t[0])

    for j in range(len(labels) - 1):
        x1, h1, idx1, name1 = labels[j]
        x2, h2, idx2, name2 = labels[j + 1]
        gap = abs(x2 - x1)
        # 같은 tap의 CIRCUIT_ID_BOX + LABEL 쌍은 의도적으로 같은 X
        if gap < 0.01 and name1 != name2:
            continue
        if gap < _MIN_GAP:
            issues.append(ValidationIssue(
                type="TEXT_OVERLAP", severity="warning",
                detail=f"{name1}[{idx1}] ↔ {name2}[{idx2}] gap={gap:.1f}mm < {_MIN_GAP}mm",
                component_idx=idx1,
            ))
    return issues


# ---------------------------------------------------------------------------
# 3. Busbar connection check
# ---------------------------------------------------------------------------

def _check_busbar_connections(result, config) -> list[ValidationIssue]:
    """Check that SUB-CIRCUIT tap connections reach their busbar.

    화이트리스트 방식: busbar 근처의 서브서킷 브레이커(MCB/MCCB/CB_SPARE) tap 위치만 검사.
    spine, meter board, CT, 케이블, earth bar 연결은 구조적으로 정확하므로 검사하지 않는다.
    """
    issues = []
    busbar_y = getattr(result, 'busbar_y', None)
    if busbar_y is None:
        return issues

    # Collect all busbar Y values (main + sub-row busbars)
    busbar_ys = set()
    busbar_ys.add(busbar_y)
    for by in getattr(result, 'busbar_y_per_row', []):
        busbar_ys.add(by)

    # 서브서킷 브레이커의 tap X 수집 — busbar 근처(±40mm)의 CB만 대상
    _CB_NAMES = {"CB_MCB", "CB_MCCB", "CB_ACB", "CB_RCCB", "CB_SPARE"}
    tap_xs: set[float] = set()
    for comp in result.components:
        if comp.symbol_name in _CB_NAMES:
            # 서브서킷 브레이커는 busbar 위에 위치 (Y > busbar_y)
            near_any_busbar = any(abs(comp.y - by) < 40 for by in busbar_ys)
            if near_any_busbar and comp.label_style == "breaker_block":
                tap_xs.add(round(comp.x, 0))

    if not tap_xs:
        return issues

    # Fan-out side circuits: 의도적으로 busbar에 직접 닿지 않음
    fanout_side_xs: set[float] = set()
    for fg in getattr(result, 'fanout_groups', []):
        _center_x, _by, _side_xs = fg
        for sx in _side_xs:
            fanout_side_xs.add(round(sx, 0))

    # 각 tap 위치에서 busbar에 닿는 연결이 있는지 확인
    _TOL = 3.0  # mm tolerance
    for tap_x in tap_xs:
        # fan-out side circuit → busbar 직접 연결 불필요 (fanout geometry로 연결)
        # overlap resolution이 tap을 ±3mm 이동시킬 수 있으므로 넉넉한 tolerance 사용
        _FANOUT_TOL = 5.0
        is_fanout = any(abs(tap_x - sx) < _FANOUT_TOL for sx in fanout_side_xs)
        if is_fanout:
            continue

        has_busbar_conn = False
        for conn in result.connections:
            cx = conn[0][0]
            # 이 tap 근처의 수직 연결인지?
            if abs(cx - tap_x) > _TOL:
                continue
            if abs(conn[0][0] - conn[1][0]) > 1.0:  # 수직 아님
                continue
            # start 또는 end가 busbar에 닿는지?
            sy, ey = conn[0][1], conn[1][1]
            if any(abs(sy - by) < _TOL or abs(ey - by) < _TOL for by in busbar_ys):
                has_busbar_conn = True
                break

        if not has_busbar_conn:
            issues.append(ValidationIssue(
                type="DISCONNECTED", severity="critical",
                detail=f"Sub-circuit tap at x≈{tap_x:.0f} has no connection reaching busbar",
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
    """Text overlap auto-fix — currently no-op.

    Overlap resolution (Step C) 과 font measurement (4c)가 배치 시 겹침을 방지하므로
    사후 약어 치환은 불필요하다. 0.5mm 임계값 이하의 실제 겹침은 배치 버그로 간주.
    """
    return 0


def _fix_disconnected(result, config, issues: list[ValidationIssue]) -> int:
    """Snap disconnected connections to nearest busbar. Returns count.

    DISABLED: This fix was extending connection lines through symbol bodies
    (MCB, ELCB) by snapping start points to busbar Y from far away.
    The "disconnected" validation often flags fan-out side circuits and
    spine connections that are correctly placed but not directly at busbar Y.
    Snapping these to busbar creates lines that pass through breaker symbols.

    TODO: Re-enable with smarter logic that checks for symbol body collisions
    before extending connections.
    """
    return 0


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
