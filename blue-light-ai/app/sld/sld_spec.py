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
        rating_a=32, cable_size="10", cable_cores="4 X 1 CORE",
        cable_type="PVC", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False,
    ),
    40: IncomingSpec(
        rating_a=40, cable_size="16", cable_cores="4 X 1 CORE",
        cable_type="PVC", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False,
    ),
    63: IncomingSpec(
        rating_a=63, cable_size="16", cable_cores="4 X 1 CORE",
        cable_type="PVC/PVC", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False,
    ),
    80: IncomingSpec(
        rating_a=80, cable_size="35", cable_cores="4 X 1 CORE",
        cable_type="PVC/PVC", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False,
    ),
    100: IncomingSpec(
        rating_a=100, cable_size="50", cable_cores="4 X 1 CORE",
        cable_type="XLPE", poles="TPN", breaker_type="MCB", breaker_ka=10,
        phase="three_phase", earth_prot_types=["RCCB"],
        requires_ct=False, requires_isolator=False,
    ),
    125: IncomingSpec(
        rating_a=125, cable_size="50", cable_cores="4 X 1 CORE",
        cable_type="XLPE/PVC", poles="TPN", breaker_type="MCCB", breaker_ka=25,
        phase="three_phase", earth_prot_types=["RCCB", "ELR", "EFR"],
        requires_ct=True, requires_isolator=True,
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


def validate_sld_requirements(requirements: dict) -> ValidationResult:
    """
    Validate and enrich Gemini-extracted SLD requirements JSON.

    Checks the JSON data against the official specification tables
    and auto-corrects values where possible.

    Expected requirements keys:
        kva: float               — Total load in kVA
        supply_type: str         — "single_phase" or "three_phase"
        breaker_rating: int      — Main breaker rating (A)
        breaker_type: str        — "MCB", "MCCB", "ACB"
        breaker_poles: str       — "DP", "TPN", "4P"
        breaker_ka: int          — Fault rating (kA)
        cable_size: str          — Incoming cable size
        circuits: list[dict]     — Sub-circuits with breaker info
        metering: str            — "sp_meter" or "ct_meter"
        ... (other fields passed through)

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
            result.add_error(str(e))
            return result

    # ── 2. 3-Phase incoming to Single Phase DB detection ──────────
    if _detect_3phase_to_single_phase_db(requirements):
        result.add_warning(
            "⚠️ NON-STANDARD CONFIGURATION DETECTED: "
            "3-Phase incoming tap to Single Phase DB. "
            "This is only allowed if the building owner and building LEW "
            "have explicitly approved this arrangement. "
            "Ensure proper documentation and approval before proceeding."
        )
        # Don't auto-correct phase — this is an intentional non-standard setup
        # But still validate other parameters against the specified breaker rating

    # ── 3. Validate / auto-correct supply_type ────────────────────
    if spec and supply_type:
        if supply_type != spec.phase and not _detect_3phase_to_single_phase_db(requirements):
            result.add_correction(
                "supply_type",
                supply_type, spec.phase,
                f"kVA={kva} maps to {spec.rating_a}A which is {spec.phase}",
            )

    if spec and not supply_type:
        result.add_correction(
            "supply_type", "", spec.phase,
            f"Auto-determined from kVA={kva} → {spec.phase}",
        )

    # ── 4. Validate / auto-correct breaker_rating ─────────────────
    if spec and breaker_rating:
        if breaker_rating != spec.rating_a:
            # Check if user's rating is reasonable (within one tier)
            tier_idx = _INCOMING_RATINGS_ASC.index(spec.rating_a) \
                if spec.rating_a in _INCOMING_RATINGS_ASC else -1
            user_in_table = breaker_rating in INCOMING_SPEC

            if user_in_table and breaker_rating > spec.rating_a:
                # User specified a larger breaker — allowed (oversized)
                result.add_warning(
                    f"Main breaker {breaker_rating}A is larger than minimum "
                    f"required {spec.rating_a}A for {kva} kVA. "
                    f"This is acceptable but may be over-specified."
                )
            elif user_in_table and breaker_rating < spec.rating_a:
                # User specified a smaller breaker — auto-correct to minimum
                result.add_correction(
                    "breaker_rating",
                    breaker_rating, spec.rating_a,
                    f"Undersized {breaker_rating}A for {kva} kVA. "
                    f"Auto-corrected to {spec.rating_a}A.",
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

    # ── 5. Validate / auto-correct breaker_type ───────────────────
    # Use corrected breaker_rating if it was auto-corrected in Step 4
    corrected_rating = result.corrections.get("breaker_rating", {}).get("corrected")
    effective_rating = corrected_rating or breaker_rating or (spec.rating_a if spec else 0)
    effective_spec = INCOMING_SPEC.get(effective_rating, spec)

    if effective_spec and breaker_type:
        if breaker_type.upper() != effective_spec.breaker_type:
            result.add_correction(
                "breaker_type",
                breaker_type, effective_spec.breaker_type,
                f"{effective_rating}A requires {effective_spec.breaker_type} "
                f"(not {breaker_type})",
            )
    elif effective_spec and not breaker_type:
        result.add_correction(
            "breaker_type", "", effective_spec.breaker_type,
            f"Auto-determined: {effective_rating}A → {effective_spec.breaker_type}",
        )

    # ── 6. Validate / auto-correct breaker_ka ─────────────────────
    if effective_spec and breaker_ka:
        if breaker_ka < effective_spec.breaker_ka:
            # Auto-correct insufficient fault rating instead of blocking
            result.add_correction(
                "breaker_ka",
                breaker_ka, effective_spec.breaker_ka,
                f"Fault rating {breaker_ka}kA insufficient for "
                f"{effective_spec.breaker_type} at {effective_rating}A. "
                f"Auto-corrected to {effective_spec.breaker_ka}kA.",
            )
        elif breaker_ka > effective_spec.breaker_ka:
            result.add_warning(
                f"Fault rating {breaker_ka}kA exceeds standard "
                f"{effective_spec.breaker_ka}kA for {effective_rating}A "
                f"{effective_spec.breaker_type}. Acceptable but verify necessity."
            )
    elif effective_spec and not breaker_ka:
        result.add_correction(
            "breaker_ka", 0, effective_spec.breaker_ka,
            f"Auto-determined: {effective_spec.breaker_type} → {effective_spec.breaker_ka}kA",
        )

    # ── 7. Validate / auto-correct poles ──────────────────────────
    if effective_spec and breaker_poles:
        expected_poles = effective_spec.poles
        if breaker_poles.upper() != expected_poles:
            if not _detect_3phase_to_single_phase_db(requirements):
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

    # ── 8. Validate / auto-correct cable size ─────────────────────
    cable_size = requirements.get("cable_size", "")
    if effective_spec and not cable_size:
        result.add_correction(
            "cable_size", "", effective_spec.cable_size,
            f"Auto-determined: {effective_rating}A → {effective_spec.cable_size}",
        )

    # ── 9. Validate metering type ─────────────────────────────────
    metering = requirements.get("metering", "")
    if effective_spec:
        if effective_spec.requires_ct:
            if metering and metering != "ct_meter":
                result.add_correction(
                    "metering",
                    metering, "ct_meter",
                    f"{effective_rating}A (≥45kVA equivalent) requires CT metering",
                )
            elif not metering:
                result.add_correction(
                    "metering", "", "ct_meter",
                    f"Auto-determined: ≥45kVA → CT metering",
                )
        else:
            if not metering:
                result.add_correction(
                    "metering", "", "sp_meter",
                    f"Auto-determined: <45kVA → SP meter (direct metering)",
                )

    # ── 10. Validate sub-circuits ─────────────────────────────────
    circuits = requirements.get("circuits", [])
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

    # ── 11. Log summary ───────────────────────────────────────────
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

    return result


def apply_corrections(requirements: dict, result: ValidationResult) -> dict:
    """
    Apply auto-corrections from validation to requirements dict.

    Returns a new dict with corrections applied (does NOT mutate original).
    """
    corrected = dict(requirements)
    for key, correction in result.corrections.items():
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
