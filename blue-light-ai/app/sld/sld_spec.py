"""
SLD Electrical Specification Tables & Validation Logic.

Data source: "SLD informations_ 25 Feb 26.xlsx" (Table form + Table details)
 + Note.txt (3-Phase incoming tap Single Phase DB exception)

Provides:
1. INCOMING_SPEC  — kVA → main breaker rating, cable size, phase, breaker type, kA, etc.
2. OUTGOING_SPEC  — sub-breaker rating → minimum cable size
3. validate_sld_requirements() — validates Gemini-extracted JSON against these specs
4. lookup_incoming_by_kva() — auto-determines all incoming parameters from kVA alone
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────────────
# 1. INCOMING specification table (from "Table form" INCOMING section)
# ─────────────────────────────────────────────────────────────────────
#
# Each entry is keyed by main breaker rating (A).
# Fields come directly from the Excel columns B–N:
#   cable_size_mm2   — MINIMUM SIZE OF PHASE, NEUTRAL, EARTH (col B)
#   cable_cores      — QUANTITY & NO. OF CORE (col C)
#   cable_type       — TYPE (col D)
#   rating_a         — RATING (col E)
#   poles            — NO. OF POLE (col F)
#   breaker_type     — TYPE MCB/MCCB/ACB (col G)
#   breaker_ka       — KA RATING (col I)
#   earth_prot_type  — TYPE RCCB/ELR/EFR (col J)
#   earth_prot_rating— RATING (col K)
#   earth_prot_poles — NO. OF POLE (col L)
#   earth_prot_sens  — SENSITIVITY/SETTING (col N)

@dataclass(frozen=True)
class IncomingSpec:
    """Specification for one incoming main-breaker tier."""
    rating_a: int
    cable_size: str              # e.g. "6 + 6mmsq E", "70", "95"
    cable_cores: str             # e.g. "4 X 1 CORE", "1 X 4 CORE"
    cable_type: str              # e.g. "PVC", "PVC/PVC", "XLPE/PVC"
    poles: str                   # "SPN", "DP", "TPN", "4P"
    breaker_type: str            # "MCB", "MCCB", "ACB"
    breaker_ka: int              # fault rating kA
    phase: str                   # "single_phase" or "three_phase"
    earth_prot_types: list[str]  # ["RCCB", "ELR", "EFR"]
    requires_ct: bool            # True if kVA >= 45 (CT metering)
    requires_isolator: bool      # True if kVA >= 45
    method: str = ""             # Installation method: "METAL TRUNKING", "CONDUIT", etc.


# Ranges where specific attributes change (from Excel "Table form"):
#   32A–63A   → SPN/DP, MCB, 10kA, single-phase, 4 X 1 CORE, PVC/PVC/PVC/XLPE
#   80A–100A  → SPN/DP, MCB, 10kA, single-phase, 1 X 4 CORE (transition to 4-core)
#   150A–630A → TPN, MCCB, 35kA, three-phase
#   800A–1600A→ 4P,  ACB,  50kA, three-phase

INCOMING_SPEC: dict[int, IncomingSpec] = {
    32: IncomingSpec(
        rating_a=32, cable_size="6 + 6mmsq E", cable_cores="4 X 1 CORE",
        cable_type="PVC", poles="DP", breaker_type="MCB", breaker_ka=10,
        phase="single_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=False, requires_isolator=False,
    ),
    40: IncomingSpec(
        rating_a=40, cable_size="10 + 10mmsq E", cable_cores="4 X 1 CORE",
        cable_type="PVC/PVC", poles="DP", breaker_type="MCB", breaker_ka=10,
        phase="single_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=False, requires_isolator=False,
    ),
    63: IncomingSpec(
        rating_a=63, cable_size="16 + 16mmsq E", cable_cores="4 X 1 CORE",
        cable_type="XLPE/PVC", poles="DP", breaker_type="MCB", breaker_ka=10,
        phase="single_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=False, requires_isolator=False,
    ),
    80: IncomingSpec(
        rating_a=80, cable_size="25 + 16mmsq E", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="DP", breaker_type="MCB", breaker_ka=10,
        phase="single_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=False, requires_isolator=False,
    ),
    100: IncomingSpec(
        rating_a=100, cable_size="35 + 16mmsq E", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="DP", breaker_type="MCB", breaker_ka=10,
        phase="single_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=False, requires_isolator=False,
    ),
    150: IncomingSpec(
        rating_a=150, cable_size="70", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="TPN", breaker_type="MCCB", breaker_ka=35,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    200: IncomingSpec(
        rating_a=200, cable_size="95", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="TPN", breaker_type="MCCB", breaker_ka=35,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    250: IncomingSpec(
        rating_a=250, cable_size="95", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="TPN", breaker_type="MCCB", breaker_ka=35,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    300: IncomingSpec(
        rating_a=300, cable_size="120", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="TPN", breaker_type="MCCB", breaker_ka=35,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    400: IncomingSpec(
        rating_a=400, cable_size="185", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="TPN", breaker_type="MCCB", breaker_ka=35,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    630: IncomingSpec(
        rating_a=630, cable_size="300", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="TPN", breaker_type="MCCB", breaker_ka=35,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    800: IncomingSpec(
        rating_a=800, cable_size="500", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="4P", breaker_type="ACB", breaker_ka=50,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    1000: IncomingSpec(
        rating_a=1000, cable_size="500", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="4P", breaker_type="ACB", breaker_ka=50,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    1200: IncomingSpec(
        rating_a=1200, cable_size="630", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="4P", breaker_type="ACB", breaker_ka=50,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
    1600: IncomingSpec(
        rating_a=1600, cable_size="630", cable_cores="1 X 4 CORE",
        cable_type="XLPE/PVC", poles="4P", breaker_type="ACB", breaker_ka=50,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
    ),
}

# Ordered list for lookup (ascending by rating)
_INCOMING_RATINGS_ASC = sorted(INCOMING_SPEC.keys())


# ─────────────────────────────────────────────────────────────────────
# 1b. Three-phase INCOMING specification (small TPN: 32A–125A)
# ─────────────────────────────────────────────────────────────────────
#
# DWG data analysis (26 files) reveals three-phase TPN installations from 32A.
# These overlap with single-phase ratings (32-100A) but have different cable/pole specs.
# INCOMING_SPEC above covers single-phase (DP) variants; this covers TPN variants.

INCOMING_SPEC_3PHASE: dict[int, IncomingSpec] = {
    32: IncomingSpec(
        rating_a=32, cable_size="10 + 10mmsq E", cable_cores="4 X 1 CORE",
        cable_type="PVC", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False, method="METAL TRUNKING",
    ),
    40: IncomingSpec(
        rating_a=40, cable_size="16 + 16mmsq E", cable_cores="4 X 1 CORE",
        cable_type="PVC", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False, method="METAL TRUNKING",
    ),
    63: IncomingSpec(
        rating_a=63, cable_size="16 + 16mmsq E", cable_cores="4 X 1 CORE",
        cable_type="PVC/PVC", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False, method="METAL TRUNKING",
    ),
    80: IncomingSpec(
        rating_a=80, cable_size="35 + 16mmsq E", cable_cores="4 X 1 CORE",
        cable_type="PVC/PVC", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False, method="METAL TRUNKING",
    ),
    100: IncomingSpec(
        rating_a=100, cable_size="50 + 25mmsq E", cable_cores="4 X 1 CORE",
        cable_type="XLPE", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False, method="METAL TRUNKING",
    ),
    125: IncomingSpec(
        rating_a=125, cable_size="50 + 25mmsq E", cable_cores="4 X 1 CORE",
        cable_type="XLPE/PVC", poles="TPN", breaker_type="MCCB", breaker_ka=25,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True, method="METAL TRUNKING",
    ),
}


# ─────────────────────────────────────────────────────────────────────
# 2. OUTGOING specification table (from "Table form" OUTGOING section)
# ─────────────────────────────────────────────────────────────────────
#
# Sub-breaker rating (A) → minimum cable size (mm²)
# From Excel columns B (cable size) & E (rating)

OUTGOING_SPEC: dict[int, float] = {
    6: 1.5,
    10: 1.5,
    16: 2.5,
    20: 2.5,
    32: 6,
    63: 16,
    80: 35,
    100: 35,
    150: 70,
    200: 95,
    250: 95,
    300: 120,
    400: 185,
    630: 300,
    800: 500,
    1000: 500,
    1200: 630,
    1600: 630,
}

# Ordered list for lookup (ascending by rating)
_OUTGOING_RATINGS_ASC = sorted(OUTGOING_SPEC.keys())


# ─────────────────────────────────────────────────────────────────────
# 3. kVA → Main Breaker Rating mapping
# ─────────────────────────────────────────────────────────────────────
#
# From "Table details" column A (main breaker rating) + Table form relationships.
# Singapore standard: 3-phase 400V, single-phase 230V
#
# kVA thresholds derived from: I = kVA × 1000 / (V × √3)  [3-phase]
#                               I = kVA × 1000 / V          [1-phase]
#
# The boundary between single-phase and three-phase is at ~100A (≈40 kVA for 1-phase).
# Per Excel data: 32A–100A = single-phase (DP/SPN); 150A+ = three-phase (TPN/4P).

# Single-phase: V = 230V → kVA = rating × 230 / 1000
# Three-phase:  V = 400V → kVA = rating × 400 × √3 / 1000 ≈ rating × 0.6928

# kVA tier boundaries (max kVA for each breaker rating)
KVA_TO_BREAKER_MAP: list[tuple[float, int, str]] = [
    # (max_kva, breaker_rating_a, phase)
    # Single-phase (230V): kVA = A × 230 / 1000
    (7.36, 32, "single_phase"),      # 32A × 230V = 7.36 kVA
    (9.2, 40, "single_phase"),       # 40A × 230V = 9.2 kVA
    (14.49, 63, "single_phase"),     # 63A × 230V = 14.49 kVA
    (18.4, 80, "single_phase"),      # 80A × 230V = 18.4 kVA
    (23.0, 100, "single_phase"),     # 100A × 230V = 23.0 kVA
    # Three-phase small TPN (400V): kVA = A × 400 × √3 / 1000
    # DWG data confirms 32A–125A TPN installations are common
    (22.17, 32, "three_phase"),      # 32A × 400 × √3 = 22.17 kVA
    (27.71, 40, "three_phase"),      # 40A × 400 × √3 = 27.71 kVA
    (43.65, 63, "three_phase"),      # 63A × 400 × √3 = 43.65 kVA
    (55.43, 80, "three_phase"),      # 80A × 400 × √3 = 55.43 kVA
    (69.28, 100, "three_phase"),     # 100A × 400 × √3 = 69.28 kVA
    (86.60, 125, "three_phase"),     # 125A × 400 × √3 = 86.60 kVA
    # Three-phase large (400V):
    (103.9, 150, "three_phase"),     # 150A × 400 × √3 = 103.9 kVA
    (138.6, 200, "three_phase"),     # 200A × 400 × √3 = 138.6 kVA
    (173.2, 250, "three_phase"),     # 250A × 400 × √3 = 173.2 kVA
    (207.8, 300, "three_phase"),     # 300A × 400 × √3 = 207.8 kVA
    (277.1, 400, "three_phase"),     # 400A × 400 × √3 = 277.1 kVA
    (436.6, 630, "three_phase"),     # 630A × 400 × √3 = 436.6 kVA
    (554.3, 800, "three_phase"),     # 800A × 400 × √3 = 554.3 kVA
    (692.8, 1000, "three_phase"),    # 1000A × 400 × √3 = 692.8 kVA
    (831.4, 1200, "three_phase"),    # 1200A × 400 × √3 = 831.4 kVA
    (1108.5, 1600, "three_phase"),   # 1600A × 400 × √3 = 1108.5 kVA
]


def lookup_incoming_by_kva(kva: float, supply_type: str = "") -> IncomingSpec:
    """
    Given kVA, determine the complete incoming specification.

    Selects the smallest standard breaker rating that can handle the load,
    then returns all associated cable, phase, and protection parameters.

    When supply_type is specified, only entries matching that phase are considered.
    This is critical for distinguishing between single-phase and three-phase
    at overlapping kVA ranges (e.g. 22 kVA can be 100A single-phase or 32A TPN).

    Args:
        kva: Load in kilo-volt-amperes.
        supply_type: Optional phase filter ("single_phase" or "three_phase").

    Returns:
        IncomingSpec with all incoming parameters.

    Raises:
        ValueError: If kVA exceeds maximum supported rating (1600A / ~1108 kVA).
    """
    for max_kva, rating_a, phase in KVA_TO_BREAKER_MAP:
        if supply_type and phase != supply_type:
            continue
        if kva <= max_kva:
            # Three-phase small TPN: use INCOMING_SPEC_3PHASE if available
            if phase == "three_phase" and rating_a in INCOMING_SPEC_3PHASE:
                return INCOMING_SPEC_3PHASE[rating_a]
            return INCOMING_SPEC[rating_a]

    # kVA exceeds all standard tiers — return the largest available spec
    # (SG team decision 2026-03-08: no strict limit, user/LEW responsibility)
    # Find the last entry matching supply_type (or last overall)
    for max_kva, rating_a, phase in reversed(KVA_TO_BREAKER_MAP):
        if supply_type and phase != supply_type:
            continue
        if phase == "three_phase" and rating_a in INCOMING_SPEC_3PHASE:
            return INCOMING_SPEC_3PHASE[rating_a]
        return INCOMING_SPEC[rating_a]

    # Absolute fallback (should never reach here)
    raise ValueError(
        f"kVA value {kva} exceeds maximum supported rating. "
        f"Max supported: ~1108 kVA (1600A ACB). "
        f"Contact LEW for custom MSB design."
    )


def lookup_outgoing_cable(sub_breaker_rating_a: int) -> float:
    """
    Given a sub-breaker rating (A), return the minimum cable size (mm²).

    Args:
        sub_breaker_rating_a: Rating in Amps.

    Returns:
        Minimum cable size in mm².

    Raises:
        ValueError: If rating not in standard table.
    """
    # Exact match first
    if sub_breaker_rating_a in OUTGOING_SPEC:
        return OUTGOING_SPEC[sub_breaker_rating_a]

    # Next higher standard rating
    for rating in _OUTGOING_RATINGS_ASC:
        if rating >= sub_breaker_rating_a:
            return OUTGOING_SPEC[rating]

    raise ValueError(
        f"Sub-breaker rating {sub_breaker_rating_a}A exceeds maximum "
        f"supported rating ({_OUTGOING_RATINGS_ASC[-1]}A)."
    )


# ─────────────────────────────────────────────────────────────────────
# 4. Validation logic for Gemini-extracted JSON
# ─────────────────────────────────────────────────────────────────────

@dataclass
class ValidationResult:
    """Result of SLD requirements validation."""
    valid: bool = True
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    corrections: dict[str, Any] = field(default_factory=dict)

    def add_error(self, msg: str) -> None:
        self.errors.append(msg)
        self.valid = False

    def add_warning(self, msg: str) -> None:
        self.warnings.append(msg)

    def add_correction(self, key: str, original: Any, corrected: Any, reason: str) -> None:
        self.corrections[key] = {
            "original": original,
            "corrected": corrected,
            "reason": reason,
        }
        self.warnings.append(f"Auto-corrected '{key}': {original} → {corrected} ({reason})")


def _detect_3phase_to_single_phase_db(requirements: dict) -> bool:
    """
    Detect the non-standard practice: 3-Phase incoming tap to Single Phase DB.

    This is allowed ONLY if:
    - Building owner and building LEW have approved it
    - The incoming supply is 3-phase but the DB is configured as single-phase

    Signals:
    - supply_type is "three_phase" but main breaker is DP (not TPN/4P)
    - Explicit flag in requirements
    - kVA suggests three-phase but breaker_rating ≤ 100A with DP poles
    """
    supply = requirements.get("supply_type", "")
    poles = requirements.get("breaker_poles", requirements.get("poles", ""))
    kva = requirements.get("kva", 0)
    breaker_rating = requirements.get("breaker_rating", 0)

    # Explicit flag
    if requirements.get("three_phase_to_single_phase_db", False):
        return True

    # 3-phase supply but DP poles (should be TPN/4P for 3-phase)
    if "three" in str(supply).lower() or "3" in str(supply):
        if poles.upper() in ("DP", "SPN", "2P"):
            return True

    # kVA high enough for 3-phase but rating ≤ 100A (single-phase territory)
    if kva > 23.0 and breaker_rating and breaker_rating <= 100:
        poles_upper = poles.upper() if poles else ""
        if poles_upper in ("DP", "SPN", "2P", ""):
            return True

    return False


def _get_effective_spec(
    result: ValidationResult,
    breaker_rating: int,
    spec: IncomingSpec | None,
    supply_type: str,
) -> IncomingSpec | None:
    """Determine the effective IncomingSpec for downstream validation steps.

    Consolidates the repeated effective spec lookup logic. Uses the corrected
    breaker_rating (from Step 4) if available, then resolves the correct spec
    table entry based on supply_type (prefers 3-phase TPN when applicable).

    Returns:
        The resolved IncomingSpec, or None if no spec can be determined.
    """
    corrected_rating = result.corrections.get("breaker_rating", {}).get("corrected")
    effective_rating = corrected_rating or breaker_rating or (spec.rating_a if spec else 0)
    # Prefer 3-phase spec when supply_type is three_phase (avoids TPN→DP misfire)
    effective_supply = (
        result.corrections.get("supply_type", {}).get("corrected")
        or supply_type
        or (spec.phase if spec else "")
    )
    if effective_supply == "three_phase" and effective_rating in INCOMING_SPEC_3PHASE:
        return INCOMING_SPEC_3PHASE[effective_rating]
    return INCOMING_SPEC.get(effective_rating, spec)


def _get_effective_rating(
    result: ValidationResult,
    breaker_rating: int,
    spec: IncomingSpec | None,
) -> int:
    """Determine the effective breaker rating for downstream validation.

    Uses the corrected breaker_rating (from Step 4) if available,
    otherwise falls back to user-provided or spec-derived rating.
    """
    corrected_rating = result.corrections.get("breaker_rating", {}).get("corrected")
    return corrected_rating or breaker_rating or (spec.rating_a if spec else 0)


def _validate_breaker_rating(
    spec: IncomingSpec | None,
    breaker_rating: int,
    kva: float,
    result: ValidationResult,
) -> None:
    """Validate / auto-correct breaker_rating (Step 4).

    SG team decision (2026-03-08): user/LEW responsible for kVA.
    If user explicitly provides a standard breaker_rating, trust it
    (warn only, don't auto-correct). Only auto-correct non-standard ratings.
    """
    if spec and breaker_rating:
        if breaker_rating != spec.rating_a:
            user_in_table = (breaker_rating in INCOMING_SPEC
                             or breaker_rating in INCOMING_SPEC_3PHASE)

            if user_in_table and breaker_rating > spec.rating_a:
                # User specified a larger breaker — allowed (oversized)
                result.add_warning(
                    f"Main breaker {breaker_rating}A is larger than minimum "
                    f"required {spec.rating_a}A for {kva} kVA. "
                    f"This is acceptable but may be over-specified."
                )
            elif user_in_table and breaker_rating < spec.rating_a:
                # User specified a smaller standard breaker — warn only
                # (user/LEW responsibility: diversity factor may apply)
                result.add_warning(
                    f"Main breaker {breaker_rating}A may be undersized for "
                    f"{kva} kVA (standard minimum: {spec.rating_a}A). "
                    f"Ensure diversity factor or approved load justifies this rating."
                )
            elif not user_in_table:
                result.add_correction(
                    "breaker_rating",
                    breaker_rating, spec.rating_a,
                    f"Non-standard rating. Corrected to {spec.rating_a}A for {kva} kVA.",
                )
    elif spec and not breaker_rating:
        result.add_correction(
            "breaker_rating", 0, spec.rating_a,
            f"Auto-determined from kVA={kva}",
        )


def _validate_breaker_type(
    effective_spec: IncomingSpec | None,
    breaker_type: str,
    effective_rating: int,
    result: ValidationResult,
) -> None:
    """Validate / auto-correct breaker_type (Step 5).

    If user explicitly provides a valid breaker type (MCB/MCCB/ACB/RCCB/ELCB),
    trust it and warn only — don't auto-correct. Only auto-correct invalid
    types or fill in missing values.
    """
    _VALID_BREAKER_TYPES = {"MCB", "MCCB", "ACB", "RCCB", "ELCB"}
    if effective_spec and breaker_type:
        if breaker_type.upper() != effective_spec.breaker_type:
            if breaker_type.upper() in _VALID_BREAKER_TYPES:
                # User explicitly chose a valid type — respect it, warn only
                result.add_warning(
                    f"Main breaker type '{breaker_type}' differs from standard "
                    f"'{effective_spec.breaker_type}' for {effective_rating}A. "
                    f"User-specified value retained."
                )
            else:
                # Invalid type — auto-correct
                result.add_correction(
                    "breaker_type",
                    breaker_type, effective_spec.breaker_type,
                    f"Invalid breaker type '{breaker_type}'. "
                    f"Corrected to {effective_spec.breaker_type} for {effective_rating}A.",
                )
    elif effective_spec and not breaker_type:
        result.add_correction(
            "breaker_type", "", effective_spec.breaker_type,
            f"Auto-determined: {effective_rating}A → {effective_spec.breaker_type}",
        )


def _validate_fault_rating(
    effective_spec: IncomingSpec | None,
    breaker_ka: int,
    effective_rating: int,
    breaker_type: str,
    result: ValidationResult,
) -> None:
    """Validate / auto-correct breaker_ka fault rating (Step 6).

    When user provides a valid breaker type (preserved by Step 5), use
    that type's minimum kA instead of the spec's default type kA.
    Auto-corrects insufficient fault ratings. Warns on over-specified values.
    """
    if not effective_spec:
        return

    # SS 638 minimum kA per breaker type
    _MIN_KA_BY_TYPE = {"MCB": 10, "MCCB": 25, "ACB": 50}

    # Use user's type kA minimum if type was preserved (not corrected)
    user_type = breaker_type.upper() if breaker_type else ""
    type_was_corrected = "breaker_type" in result.corrections

    if user_type in _MIN_KA_BY_TYPE and not type_was_corrected:
        expected_ka = _MIN_KA_BY_TYPE[user_type]
        type_label = user_type
    else:
        expected_ka = effective_spec.breaker_ka
        type_label = effective_spec.breaker_type

    if breaker_ka:
        if breaker_ka < expected_ka:
            result.add_correction(
                "breaker_ka",
                breaker_ka, expected_ka,
                f"Fault rating {breaker_ka}kA insufficient for "
                f"{type_label} at {effective_rating}A. "
                f"Auto-corrected to {expected_ka}kA.",
            )
        elif breaker_ka > expected_ka:
            result.add_warning(
                f"Fault rating {breaker_ka}kA exceeds standard "
                f"{expected_ka}kA for {effective_rating}A "
                f"{type_label}. Acceptable but verify necessity."
            )
    else:
        result.add_correction(
            "breaker_ka", 0, expected_ka,
            f"Auto-determined: {type_label} → {expected_ka}kA",
        )


def _validate_poles(
    effective_spec: IncomingSpec | None,
    breaker_poles: str,
    effective_rating: int,
    requirements: dict,
    result: ValidationResult,
) -> None:
    """Validate / auto-correct breaker poles (Step 7).

    Checks pole configuration against spec. Skips correction for:
    - 3-phase incoming to single-phase DB configuration
    - Compatible pole configs for same phase system (SPN↔DP, TPN↔4P)
    """
    # Compatible pole groups within the same phase system
    _SINGLE_PHASE_POLES = {"SPN", "DP"}
    _THREE_PHASE_POLES = {"TPN", "4P"}

    if effective_spec and breaker_poles:
        expected_poles = effective_spec.poles
        if breaker_poles.upper() != expected_poles:
            if _detect_3phase_to_single_phase_db(requirements):
                pass  # Handled elsewhere
            else:
                user_upper = breaker_poles.upper()
                same_phase_group = (
                    (user_upper in _SINGLE_PHASE_POLES and expected_poles in _SINGLE_PHASE_POLES)
                    or (user_upper in _THREE_PHASE_POLES and expected_poles in _THREE_PHASE_POLES)
                )
                if same_phase_group:
                    result.add_warning(
                        f"Poles '{breaker_poles}' differs from standard "
                        f"'{expected_poles}' for {effective_rating}A. "
                        f"User-specified value retained."
                    )
                else:
                    result.add_correction(
                        "breaker_poles",
                        breaker_poles, expected_poles,
                        f"{effective_rating}A {effective_spec.phase} → {expected_poles}",
                    )
    elif effective_spec and not breaker_poles:
        result.add_correction(
            "breaker_poles", "", effective_spec.poles,
            f"Auto-determined: {effective_spec.phase} → {effective_spec.poles}",
        )


def _validate_elcb(
    effective_spec: IncomingSpec | None,
    effective_rating: int,
    requirements: dict,
    result: ValidationResult,
) -> None:
    """Validate / auto-correct ELCB/RCCB configuration (Step 8.5).

    EMA July 2023: 30mA RCCB mandatory for residential.
    SS 638: ELCB sensitivity depends on supply type and rating.

    Rules:
      - 1-phase (any rating): 30mA DP
      - 3-phase ≤100A: 30mA 4P
      - 3-phase >100A: 100~300mA 4P
    """
    from app.sld.validation_messages import ELCB_RECOMMENDED

    elcb = requirements.get("elcb", {})
    # Use corrected supply_type if available, else original
    supply_type_correction = result.corrections.get("supply_type", {})
    supply_type = (
        supply_type_correction.get("corrected", "")
        if supply_type_correction
        else requirements.get("supply_type", "")
    )

    # --- A. Missing ELCB warning ---
    if not elcb or not elcb.get("rating"):
        result.add_warning(ELCB_RECOMMENDED)
        return

    # --- B. Sensitivity validation ---
    user_ma = elcb.get("sensitivity_ma", 0)
    is_three_phase = supply_type == "three_phase"

    if is_three_phase and effective_rating and effective_rating > 100:
        # 3-phase >100A: 100~300mA required
        expected_ma = 100
        if user_ma and user_ma < 100:
            result.add_correction(
                "elcb.sensitivity_ma", user_ma, expected_ma,
                f"3-phase {effective_rating}A requires 100~300mA sensitivity "
                f"(30mA too sensitive for >100A). Auto-corrected to {expected_ma}mA.",
            )
        elif user_ma and user_ma > 300:
            result.add_warning(
                f"ELCB sensitivity {user_ma}mA exceeds maximum 300mA for "
                f"3-phase {effective_rating}A. Verify setting."
            )
        elif not user_ma:
            result.add_correction(
                "elcb.sensitivity_ma", 0, expected_ma,
                f"Auto-determined: 3-phase {effective_rating}A → {expected_ma}mA",
            )
    else:
        # 1-phase or 3-phase ≤100A: 30mA
        expected_ma = 30
        if user_ma and user_ma > 30:
            result.add_warning(
                f"ELCB sensitivity {user_ma}mA exceeds recommended 30mA for "
                f"{'single-phase' if not is_three_phase else f'3-phase {effective_rating}A'}. "
                f"30mA provides better protection for personnel safety."
            )
        elif not user_ma:
            result.add_correction(
                "elcb.sensitivity_ma", 0, expected_ma,
                f"Auto-determined: "
                f"{'single-phase' if not is_three_phase else f'3-phase ≤100A'} → {expected_ma}mA",
            )

    # --- C. Poles validation ---
    user_poles = elcb.get("poles", "")
    expected_poles = "4P" if is_three_phase else "DP"
    if user_poles and user_poles.upper() != expected_poles:
        result.add_correction(
            "elcb.poles", user_poles, expected_poles,
            f"ELCB poles '{user_poles}' incorrect for {supply_type}. "
            f"Corrected to {expected_poles}.",
        )
    elif not user_poles:
        result.add_correction(
            "elcb.poles", "", expected_poles,
            f"Auto-determined: {supply_type} → {expected_poles}",
        )

    # --- D. Rating validation (≥ main breaker rating) ---
    elcb_rating = elcb.get("rating", 0)
    if elcb_rating and effective_rating and elcb_rating < effective_rating:
        result.add_warning(
            f"ELCB rating {elcb_rating}A is less than main breaker {effective_rating}A. "
            f"ELCB rating should be ≥ main breaker rating."
        )


def _validate_metering(
    effective_spec: IncomingSpec | None,
    metering: str,
    supply_source: str,
    effective_rating: int,
    result: ValidationResult,
    *,
    is_cable_extension: bool = False,
) -> None:
    """Validate / auto-correct metering type (Step 9).

    Landlord supply skips metering auto-correction. Otherwise determines
    CT metering (requires_ct) or SP meter based on the effective spec.
    """
    # Cable extension: no meter board — strip any metering (regardless of supply_source).
    if is_cable_extension:
        if metering:
            result.add_correction(
                "metering", metering, "",
                "Cable extension has no meter board — removed metering",
            )
        return
    # Landlord supply: keep sp_meter if explicitly specified (PG KWH meter board).
    if supply_source == "landlord":
        if metering:
            # User explicitly specified metering — keep it as-is.
            return
        # No metering specified for landlord — default to sp_meter
        # (landlord riser → KWH meter board → DB is the standard pattern).
        result.add_correction(
            "metering", "", "sp_meter",
            "Landlord supply: auto-added sp_meter (PG KWH meter board)",
        )
        return
    if not effective_spec:
        return
    if effective_spec.requires_ct:
        if metering and metering != "ct_meter":
            result.add_correction(
                "metering",
                metering, "ct_meter",
                f"{effective_rating}A requires CT metering",
            )
        elif not metering:
            result.add_correction(
                "metering", "", "ct_meter",
                f"Auto-determined: {effective_rating}A (CT metering required)",
            )
    else:
        if not metering:
            result.add_correction(
                "metering", "", "sp_meter",
                f"Auto-determined: {effective_rating}A (direct metering, CT not required)",
            )


def _validate_sub_circuits(
    circuits: list[dict],
    effective_rating: int,
    result: ValidationResult,
) -> None:
    """Validate sub-circuit breaker ratings and cable sizes (Step 10).

    Checks each sub-circuit's cable size against the minimum required
    and ensures no sub-breaker exceeds the main breaker rating.
    """
    for i, circuit in enumerate(circuits):
        ckt_rating = circuit.get("breaker_rating", circuit.get("rating", 0))
        ckt_cable = circuit.get("cable_size", 0)

        if ckt_rating:
            try:
                min_cable = lookup_outgoing_cable(ckt_rating)
                if ckt_cable and float(ckt_cable) < min_cable:
                    result.add_warning(
                        f"Circuit {i + 1}: cable {ckt_cable}mm² is smaller than "
                        f"minimum {min_cable}mm² for {ckt_rating}A sub-breaker."
                    )
            except (ValueError, TypeError):
                pass  # Non-numeric cable size — skip

        # Sub-breaker must not exceed main breaker
        if ckt_rating and effective_rating:
            if ckt_rating > effective_rating:
                result.add_error(
                    f"Circuit {i + 1}: sub-breaker {ckt_rating}A exceeds "
                    f"main breaker {effective_rating}A."
                )


def _log_validation_summary(result: ValidationResult) -> None:
    """Log validation summary: errors, warnings, corrections (Step 11)."""
    if result.errors:
        logger.warning(
            "SLD validation failed with %d error(s): %s",
            len(result.errors), "; ".join(result.errors),
        )
    if result.warnings:
        logger.info(
            "SLD validation: %d warning(s): %s",
            len(result.warnings), "; ".join(result.warnings),
        )
    if result.corrections:
        logger.info(
            "SLD validation: %d auto-correction(s) applied",
            len(result.corrections),
        )


def validate_sld_requirements(requirements: dict) -> ValidationResult:
    """
    Validate and enrich Gemini-extracted SLD requirements JSON.

    Checks the JSON data against the official specification tables
    and auto-corrects values where possible. Delegates each validation
    step to a dedicated sub-function.

    Returns:
        ValidationResult with errors, warnings, and auto-corrections.
    """
    result = ValidationResult()
    kva = requirements.get("kva", 0)
    supply_type = requirements.get("supply_type", "")
    breaker_rating = requirements.get("breaker_rating", 0)
    breaker_type = requirements.get("breaker_type", "")
    breaker_poles = requirements.get("breaker_poles", requirements.get("poles", ""))
    breaker_ka = requirements.get("breaker_ka", 0)

    # ── 0. Basic required fields ──────────────────────────────────
    from app.sld.validation_messages import MISSING_KVA_OR_BREAKER
    if not kva and not breaker_rating:
        result.add_error(MISSING_KVA_OR_BREAKER)
        return result

    # ── 1. kVA → Spec lookup ──────────────────────────────────────
    spec: IncomingSpec | None = None
    if kva:
        try:
            spec = lookup_incoming_by_kva(kva, supply_type=supply_type)
        except ValueError as e:
            result.add_warning(str(e))

    # ── 1.5 Capacity limit checks ─────────────────────────────────
    if kva and supply_type == "single_phase" and kva > 23:
        result.add_warning(
            "Single-phase supply maximum is 23kVA (100A at 230V). "
            f"Requested {kva}kVA exceeds this limit. "
            "Consider three-phase supply or verify with SP PowerGrid."
        )
    supply_source = requirements.get("supply_source", "")
    if kva and kva > 280 and supply_source not in ("landlord", "substation"):
        result.add_warning(
            "Direct service from SP PowerGrid limited to 280kVA (400A). "
            f"Requested {kva}kVA may require substation supply. "
            "Verify supply arrangement with SP PowerGrid."
        )

    # ── 2. 3-Phase incoming to Single Phase DB detection ──────────
    if _detect_3phase_to_single_phase_db(requirements):
        result.add_warning(
            "⚠️ NON-STANDARD CONFIGURATION DETECTED: "
            "3-Phase incoming tap to Single Phase DB. "
            "This is only allowed if the building owner and building LEW "
            "have explicitly approved this arrangement. "
            "Ensure proper documentation and approval before proceeding."
        )

    # ── 3. Validate / auto-correct supply_type ────────────────────
    if spec and supply_type:
        if supply_type != spec.phase and not _detect_3phase_to_single_phase_db(requirements):
            result.add_correction(
                "supply_type", supply_type, spec.phase,
                f"kVA={kva} maps to {spec.rating_a}A which is {spec.phase}",
            )
    if spec and not supply_type:
        result.add_correction(
            "supply_type", "", spec.phase,
            f"Auto-determined from kVA={kva} → {spec.phase}",
        )

    # ── Steps 4–10: delegated to sub-functions ────────────────────
    _validate_breaker_rating(spec, breaker_rating, kva, result)
    effective_spec = _get_effective_spec(result, breaker_rating, spec, supply_type)
    effective_rating = _get_effective_rating(result, breaker_rating, spec)
    _validate_breaker_type(effective_spec, breaker_type, effective_rating, result)
    _validate_fault_rating(effective_spec, breaker_ka, effective_rating, breaker_type, result)
    _validate_poles(effective_spec, breaker_poles, effective_rating, requirements, result)
    # ── 8.5 ELCB/RCCB validation ──────────────────────────────────
    _validate_elcb(effective_spec, effective_rating, requirements, result)
    # ── 8. Cable size auto-correction ─────────────────────────────
    cable_size = requirements.get("cable_size", "")
    if effective_spec and not cable_size:
        result.add_correction(
            "cable_size", "", effective_spec.cable_size,
            f"Auto-determined: {effective_rating}A → {effective_spec.cable_size}",
        )
    _validate_metering(effective_spec, requirements.get("metering", ""),
                       requirements.get("supply_source", ""), effective_rating, result,
                       is_cable_extension=bool(requirements.get("is_cable_extension")))
    _validate_sub_circuits(requirements.get("circuits", []), effective_rating, result)
    _log_validation_summary(result)
    return result


def apply_corrections(requirements: dict, result: ValidationResult) -> dict:
    """
    Apply auto-corrections from validation to requirements dict.

    Returns a new dict with corrections applied (does NOT mutate original).
    Supports dot-notation keys for nested dicts (e.g. "elcb.sensitivity_ma").
    """
    corrected = dict(requirements)
    for key, correction in result.corrections.items():
        if "." in key:
            # Nested key: "elcb.sensitivity_ma" → corrected["elcb"]["sensitivity_ma"]
            parent_key, child_key = key.split(".", 1)
            parent = corrected.setdefault(parent_key, {})
            if isinstance(parent, dict):
                parent[child_key] = correction["corrected"]
        else:
            corrected[key] = correction["corrected"]
    return corrected


# ─────────────────────────────────────────────────────────────────────
# 5. Convenience: full lookup from kVA alone
# ─────────────────────────────────────────────────────────────────────

def get_full_spec_from_kva(kva: float, supply_type: str = "") -> dict[str, Any]:
    """
    Given only kVA, return a complete specification dictionary
    ready for SLD generation.

    This is the main entry point for automatic parameter determination.

    Args:
        kva: Load in kilo-volt-amperes.
        supply_type: Optional phase filter ("single_phase" or "three_phase").

    Returns:
        dict with all incoming parameters determined:
        {
            "kva": float,
            "supply_type": str,
            "breaker_rating": int,
            "breaker_type": str,
            "breaker_poles": str,
            "breaker_ka": int,
            "cable_size": str,
            "cable_cores": str,
            "cable_type": str,
            "requires_ct": bool,
            "requires_isolator": bool,
            "metering": str,
            "earth_protection_types": list[str],
        }
    """
    spec = lookup_incoming_by_kva(kva, supply_type=supply_type)
    return {
        "kva": kva,
        "supply_type": spec.phase,
        "breaker_rating": spec.rating_a,
        "breaker_type": spec.breaker_type,
        "breaker_poles": spec.poles,
        "breaker_ka": spec.breaker_ka,
        "cable_size": spec.cable_size,
        "cable_cores": spec.cable_cores,
        "cable_type": spec.cable_type,
        "requires_ct": spec.requires_ct,
        "requires_isolator": spec.requires_isolator,
        "metering": "ct_meter" if spec.requires_ct else "sp_meter",
        "earth_protection_types": spec.earth_prot_types,
    }
