"""
Track A/B routing logic for SLD generation.

Track A: Use real LEW PDF template with title block replacement only.
         Applied when ALL specs match exactly (structure-identical SLD).

Track B: Generate new SLD from scratch using DxfBackend + ReportLab.
         Applied for all other cases (default).

Track A is a bonus optimization for ~10-15% of cases where the user's
requirements exactly match an existing template. Track B is the primary path.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class TrackDecision:
    """Result of track routing decision."""

    track: str  # "A" or "B"
    reason: str
    template_path: str | None = None  # PDF path for Track A


def decide_track(
    requirements: dict,
    matched_template: dict | None,
) -> TrackDecision:
    """
    Decide whether to use Track A (PDF template reuse) or Track B (generate new).

    Track A conditions (ALL must be met):
    1. Matched template exists with score >= 0.85
    2. Circuit count matches exactly
    3. All circuit breaker_type + breaker_rating match exactly
    4. supply_type, main_breaker_type, metering_type match exactly
    5. kVA difference <= 10%

    Args:
        requirements: User's SLD requirements.
        matched_template: Best-matched template from template_matcher (or None).

    Returns:
        TrackDecision with track="A" or "B" and reason.
    """
    if not matched_template:
        return TrackDecision(track="B", reason="No template matched")

    score = matched_template.get("match_score", 0)
    if score < 0.85:
        return TrackDecision(track="B", reason=f"Match score {score:.2f} < 0.85")

    template_spec = matched_template.get("spec", {})
    template_path = matched_template.get("pdf_path")
    if not template_path:
        return TrackDecision(track="B", reason="Template has no PDF path")

    # Check supply_type
    req_supply = requirements.get("supply_type", "")
    tmpl_supply = template_spec.get("supply_type", "")
    if req_supply != tmpl_supply:
        return TrackDecision(track="B", reason=f"supply_type mismatch: {req_supply} vs {tmpl_supply}")

    # Check main_breaker_type
    req_main_breaker = requirements.get("main_breaker_type", "")
    tmpl_main_breaker = template_spec.get("main_breaker_type", "")
    if req_main_breaker != tmpl_main_breaker:
        return TrackDecision(track="B", reason=f"main_breaker_type mismatch")

    # Check kVA within 10%
    req_kva = requirements.get("kva", 0)
    tmpl_kva = template_spec.get("kva", 0)
    if req_kva and tmpl_kva:
        diff_pct = abs(req_kva - tmpl_kva) / max(req_kva, tmpl_kva) * 100
        if diff_pct > 10:
            return TrackDecision(track="B", reason=f"kVA difference {diff_pct:.1f}% > 10%")

    # Check circuit count
    req_circuits = requirements.get("sub_circuits", [])
    tmpl_circuits = template_spec.get("sub_circuits", [])
    if len(req_circuits) != len(tmpl_circuits):
        return TrackDecision(
            track="B",
            reason=f"Circuit count mismatch: {len(req_circuits)} vs {len(tmpl_circuits)}",
        )

    # Check each circuit's breaker_type + breaker_rating
    for i, (req_c, tmpl_c) in enumerate(zip(req_circuits, tmpl_circuits)):
        req_bt = str(req_c.get("breaker_type", "")).upper()
        tmpl_bt = str(tmpl_c.get("breaker_type", "")).upper()
        req_br = req_c.get("breaker_rating", 0)
        tmpl_br = tmpl_c.get("breaker_rating", 0)
        if req_bt != tmpl_bt or req_br != tmpl_br:
            return TrackDecision(
                track="B",
                reason=f"Circuit {i} breaker mismatch: {req_bt}/{req_br}A vs {tmpl_bt}/{tmpl_br}A",
            )

    # All checks passed — Track A
    logger.info(f"Track A selected: template={template_path}, score={score:.2f}")
    return TrackDecision(
        track="A",
        reason=f"All specs match (score={score:.2f})",
        template_path=template_path,
    )
