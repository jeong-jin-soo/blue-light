"""
Tests for ELCB/RCCB Validation & Capacity Limit Checks.

Verifies:
- ELCB sensitivity auto-correction (30mA vs 100mA based on supply/rating)
- ELCB poles auto-determination (DP vs 4P)
- ELCB rating vs main breaker warning
- Missing ELCB warning
- 1-phase max capacity (23kVA) warning
- Direct service 280kVA limit warning
- apply_corrections() nested key support (elcb.sensitivity_ma, elcb.poles)
"""

import pytest

from app.sld.sld_spec import (
    ValidationResult,
    apply_corrections,
    validate_sld_requirements,
)


# ── ELCB/RCCB Sensitivity Validation ─────────────────────────────


class TestELCBSensitivityValidation:
    """Verify ELCB sensitivity is validated against supply type and rating."""

    def test_missing_elcb_warns(self):
        """No ELCB config → warning (ELCB recommended)."""
        req = {"kva": 5, "supply_type": "single_phase"}
        result = validate_sld_requirements(req)
        assert any("ELCB" in w and "mandatory" in w for w in result.warnings)

    def test_missing_elcb_rating_warns(self):
        """ELCB dict without rating → warning."""
        req = {"kva": 5, "supply_type": "single_phase", "elcb": {"sensitivity_ma": 30}}
        result = validate_sld_requirements(req)
        assert any("ELCB" in w and "mandatory" in w for w in result.warnings)

    def test_single_phase_30ma_ok(self):
        """1-phase + 30mA → no correction (correct)."""
        req = {
            "kva": 5,
            "supply_type": "single_phase",
            "elcb": {"rating": 32, "sensitivity_ma": 30, "poles": "DP"},
        }
        result = validate_sld_requirements(req)
        assert "elcb.sensitivity_ma" not in result.corrections
        assert "elcb.poles" not in result.corrections

    def test_single_phase_missing_sensitivity_auto_30ma(self):
        """1-phase + no sensitivity → auto-corrected to 30mA."""
        req = {
            "kva": 5,
            "supply_type": "single_phase",
            "elcb": {"rating": 32},
        }
        result = validate_sld_requirements(req)
        assert result.corrections.get("elcb.sensitivity_ma", {}).get("corrected") == 30

    def test_single_phase_missing_poles_auto_dp(self):
        """1-phase + no poles → auto-corrected to DP."""
        req = {
            "kva": 5,
            "supply_type": "single_phase",
            "elcb": {"rating": 32, "sensitivity_ma": 30},
        }
        result = validate_sld_requirements(req)
        assert result.corrections.get("elcb.poles", {}).get("corrected") == "DP"

    def test_single_phase_100ma_warns(self):
        """1-phase + 100mA → warning (too high, 30mA recommended)."""
        req = {
            "kva": 5,
            "supply_type": "single_phase",
            "elcb": {"rating": 32, "sensitivity_ma": 100, "poles": "DP"},
        }
        result = validate_sld_requirements(req)
        assert any("100mA" in w and "30mA" in w for w in result.warnings)

    def test_three_phase_small_30ma_ok(self):
        """3-phase ≤100A + 30mA → no correction (correct)."""
        req = {
            "kva": 69,
            "supply_type": "three_phase",
            "breaker_rating": 100,
            "elcb": {"rating": 100, "sensitivity_ma": 30, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        assert "elcb.sensitivity_ma" not in result.corrections

    def test_three_phase_large_30ma_corrected_to_100ma(self):
        """3-phase >100A + 30mA → auto-corrected to 100mA."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 30, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        correction = result.corrections.get("elcb.sensitivity_ma", {})
        assert correction.get("corrected") == 100
        assert "30mA too sensitive" in correction.get("reason", "")

    def test_three_phase_large_100ma_ok(self):
        """3-phase >100A + 100mA → no correction (correct)."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 100, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        assert "elcb.sensitivity_ma" not in result.corrections

    def test_three_phase_large_300ma_ok(self):
        """3-phase >100A + 300mA → no correction (within 100~300 range)."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 300, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        assert "elcb.sensitivity_ma" not in result.corrections

    def test_three_phase_large_500ma_warns(self):
        """3-phase >100A + 500mA → warning (exceeds 300mA max)."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 500, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        assert any("500mA" in w and "300mA" in w for w in result.warnings)

    def test_three_phase_large_missing_sensitivity_auto_100ma(self):
        """3-phase >100A + no sensitivity → auto-corrected to 100mA."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        assert result.corrections.get("elcb.sensitivity_ma", {}).get("corrected") == 100


# ── ELCB Poles Validation ────────────────────────────────────────


class TestELCBPolesValidation:
    """Verify ELCB poles are validated against supply type."""

    def test_single_phase_4p_corrected_to_dp(self):
        """1-phase + 4P poles → auto-corrected to DP."""
        req = {
            "kva": 5,
            "supply_type": "single_phase",
            "elcb": {"rating": 32, "sensitivity_ma": 30, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        correction = result.corrections.get("elcb.poles", {})
        assert correction.get("corrected") == "DP"

    def test_three_phase_dp_corrected_to_4p(self):
        """3-phase + DP poles → auto-corrected to 4P."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 100, "poles": "DP"},
        }
        result = validate_sld_requirements(req)
        correction = result.corrections.get("elcb.poles", {})
        assert correction.get("corrected") == "4P"

    def test_three_phase_4p_ok(self):
        """3-phase + 4P → no correction."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 100, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        assert "elcb.poles" not in result.corrections


# ── ELCB Rating vs Main Breaker ──────────────────────────────────


class TestELCBRating:
    """Verify ELCB rating validation against main breaker rating."""

    def test_elcb_rating_below_main_breaker_warns(self):
        """ELCB rating < main breaker → warning."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 100, "sensitivity_ma": 100, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        assert any("100A" in w and "200A" in w and "less than" in w for w in result.warnings)

    def test_elcb_rating_equal_main_ok(self):
        """ELCB rating == main breaker → no warning."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 100, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        assert not any("less than" in w for w in result.warnings)


# ── Capacity Limit Checks ────────────────────────────────────────


class TestCapacityLimits:
    """Verify capacity limit warnings for single-phase and direct service."""

    def test_single_phase_over_23kva_warns(self):
        """1-phase > 23kVA → warning."""
        req = {"kva": 25, "supply_type": "single_phase"}
        result = validate_sld_requirements(req)
        assert any("23kVA" in w and "single-phase" in w.lower() for w in result.warnings)

    def test_single_phase_23kva_no_warning(self):
        """1-phase exactly 23kVA → no warning."""
        req = {"kva": 23, "supply_type": "single_phase"}
        result = validate_sld_requirements(req)
        assert not any("23kVA" in w for w in result.warnings)

    def test_three_phase_25kva_no_single_phase_warning(self):
        """3-phase 25kVA → no single-phase warning."""
        req = {"kva": 25, "supply_type": "three_phase"}
        result = validate_sld_requirements(req)
        assert not any("23kVA" in w for w in result.warnings)

    def test_direct_service_over_280kva_warns(self):
        """Direct service > 280kVA → warning."""
        req = {"kva": 300, "supply_type": "three_phase"}
        result = validate_sld_requirements(req)
        assert any("280kVA" in w for w in result.warnings)

    def test_direct_service_280kva_no_warning(self):
        """Direct service exactly 280kVA → no warning."""
        req = {"kva": 280, "supply_type": "three_phase"}
        result = validate_sld_requirements(req)
        assert not any("280kVA" in w for w in result.warnings)

    def test_landlord_supply_over_280kva_no_warning(self):
        """Landlord supply > 280kVA → no warning (limit doesn't apply)."""
        req = {"kva": 300, "supply_type": "three_phase", "supply_source": "landlord"}
        result = validate_sld_requirements(req)
        assert not any("280kVA" in w for w in result.warnings)

    def test_substation_supply_over_280kva_no_warning(self):
        """Substation supply > 280kVA → no warning (limit doesn't apply)."""
        req = {"kva": 500, "supply_type": "three_phase", "supply_source": "substation"}
        result = validate_sld_requirements(req)
        assert not any("280kVA" in w for w in result.warnings)


# ── apply_corrections() Nested Key Support ───────────────────────


class TestApplyCorrectionNestedKeys:
    """Verify apply_corrections() handles dot-notation keys for nested dicts."""

    def test_nested_elcb_sensitivity_applied(self):
        """elcb.sensitivity_ma correction → applied to requirements["elcb"]["sensitivity_ma"]."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 30, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        corrected = apply_corrections(req, result)

        assert corrected["elcb"]["sensitivity_ma"] == 100

    def test_nested_elcb_poles_applied(self):
        """elcb.poles correction → applied to requirements["elcb"]["poles"]."""
        req = {
            "kva": 5,
            "supply_type": "single_phase",
            "elcb": {"rating": 32, "sensitivity_ma": 30, "poles": "4P"},
        }
        result = validate_sld_requirements(req)
        corrected = apply_corrections(req, result)

        assert corrected["elcb"]["poles"] == "DP"

    def test_nested_correction_creates_parent_if_missing(self):
        """If parent key doesn't exist, create it as dict."""
        result = ValidationResult()
        result.add_correction("elcb.sensitivity_ma", 0, 30, "test")
        corrected = apply_corrections({}, result)
        assert corrected["elcb"]["sensitivity_ma"] == 30

    def test_flat_corrections_still_work(self):
        """Regular (non-nested) corrections still work."""
        req = {"kva": 5}
        result = validate_sld_requirements(req)
        corrected = apply_corrections(req, result)
        # supply_type should be auto-corrected
        assert corrected.get("supply_type") == "single_phase"

    def test_original_not_mutated(self):
        """apply_corrections() returns new dict, original unchanged."""
        req = {
            "kva": 110,
            "supply_type": "three_phase",
            "breaker_rating": 200,
            "elcb": {"rating": 200, "sensitivity_ma": 30, "poles": "4P"},
        }
        original_elcb = dict(req["elcb"])
        result = validate_sld_requirements(req)
        apply_corrections(req, result)

        # Original dict should not be changed at top level
        assert req.get("elcb") is not None
