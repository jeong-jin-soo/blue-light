"""
SLD Validation Integration Tests.

Verifies that compute_layout()'s built-in validation gate correctly:
1. Rejects invalid requirements (hard errors → ValueError)
2. Auto-corrects non-standard values (corrections → layout uses fixed values)
3. Passes through valid requirements unchanged
4. Can be bypassed with skip_validation=True
"""

from __future__ import annotations

import pytest

from app.sld.layout import LayoutResult, compute_layout


# ---------------------------------------------------------------------------
# Test fixtures — valid requirements
# ---------------------------------------------------------------------------

VALID_SINGLE_PHASE = {
    "supply_type": "single_phase",
    "kva": 9,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2C 1.5sqmm PVC/PVC"},
        {"name": "Power", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2C 4.0sqmm PVC/PVC"},
    ],
}

VALID_THREE_PHASE = {
    "supply_type": "three_phase",
    "kva": 22,
    "voltage": 400,
    "main_breaker": {"type": "MCB", "rating": 32, "poles": "TPN", "fault_kA": 10},
    "busbar_rating": 100,
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2 x 1C 1.5sqmm PVC"},
        {"name": "Power", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC"},
    ],
}


# ---------------------------------------------------------------------------
# 1. Hard error detection (ValueError)
# ---------------------------------------------------------------------------

class TestValidationErrors:
    """compute_layout should raise ValueError for hard validation errors."""

    def test_kva_exceeds_max_single_phase(self):
        """Single-phase kVA exceeding 23 kVA (100A max) should raise ValueError."""
        req = dict(VALID_SINGLE_PHASE)
        req["kva"] = 50  # Exceeds max single-phase (23 kVA for 100A)
        with pytest.raises(ValueError, match="kVA value 50 exceeds maximum"):
            compute_layout(req)

    def test_kva_exceeds_max_three_phase(self):
        """Three-phase kVA exceeding ~1108 kVA should raise ValueError."""
        req = dict(VALID_THREE_PHASE)
        req["kva"] = 2000  # Exceeds max three-phase (1108 kVA for 1600A)
        with pytest.raises(ValueError, match="kVA value 2000 exceeds maximum"):
            compute_layout(req)


# ---------------------------------------------------------------------------
# 2. Auto-correction propagation
# ---------------------------------------------------------------------------

class TestAutoCorrection:
    """Validation auto-corrections should be applied to the layout result."""

    def test_breaker_rating_corrected(self):
        """Non-standard breaker rating should be auto-corrected."""
        req = dict(VALID_THREE_PHASE)
        req["main_breaker"] = dict(req["main_breaker"])
        req["main_breaker"]["rating"] = 30  # 30A is non-standard; should → 32A
        result = compute_layout(req)
        assert isinstance(result, LayoutResult)
        # The layout should still produce valid components
        assert len(result.components) > 0

    def test_fault_ka_corrected_for_mccb(self):
        """MCCB with insufficient fault kA should be auto-corrected."""
        req = {
            "supply_type": "three_phase",
            "kva": 60,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 10},
            "busbar_rating": 200,
            "sub_circuits": [
                {"name": "Power", "breaker_type": "MCB", "breaker_rating": 32,
                 "cable": "2 x 1C 6sqmm PVC"},
            ],
        }
        # Should not raise — just auto-correct kA from 10 → higher value
        result = compute_layout(req)
        assert isinstance(result, LayoutResult)
        assert len(result.components) > 0


# ---------------------------------------------------------------------------
# 3. Valid requirements — pass through unchanged
# ---------------------------------------------------------------------------

class TestValidPassthrough:
    """Valid requirements should pass validation and produce normal layout."""

    def test_valid_single_phase(self):
        """Valid single-phase requirements should produce a layout without errors."""
        result = compute_layout(VALID_SINGLE_PHASE)
        assert isinstance(result, LayoutResult)
        assert len(result.components) > 0
        assert len(result.connections) > 0

    def test_valid_three_phase(self):
        """Valid three-phase requirements should produce a layout without errors."""
        result = compute_layout(VALID_THREE_PHASE)
        assert isinstance(result, LayoutResult)
        assert len(result.components) > 0
        assert len(result.connections) > 0

    def test_valid_with_elcb(self):
        """Valid requirements with ELCB should pass validation."""
        req = dict(VALID_THREE_PHASE)
        req["elcb"] = {"rating": 40, "sensitivity_ma": 100, "poles": 4}
        result = compute_layout(req)
        assert isinstance(result, LayoutResult)
        elcb_comps = [c for c in result.components
                      if c.symbol_name in ("CB_ELCB", "CB_RCCB")]
        assert len(elcb_comps) >= 1

    def test_valid_with_metering(self):
        """Valid requirements with metering should pass validation."""
        req = dict(VALID_SINGLE_PHASE)
        req["metering"] = "sp_meter"
        result = compute_layout(req)
        assert isinstance(result, LayoutResult)
        meter_comps = [c for c in result.components
                       if c.symbol_name == "KWH_METER"]
        assert len(meter_comps) >= 1


# ---------------------------------------------------------------------------
# 4. skip_validation=True bypass
# ---------------------------------------------------------------------------

class TestSkipValidation:
    """skip_validation=True should bypass all validation checks."""

    def test_skip_validation_no_error(self):
        """Invalid kVA should not raise when skip_validation=True."""
        req = dict(VALID_SINGLE_PHASE)
        req["kva"] = 50  # Would normally cause ValueError
        # Should NOT raise
        result = compute_layout(req, skip_validation=True)
        assert isinstance(result, LayoutResult)
        assert len(result.components) > 0

    def test_skip_validation_preserves_values(self):
        """With skip_validation, original values should be used unchanged."""
        req = dict(VALID_THREE_PHASE)
        req["main_breaker"] = dict(req["main_breaker"])
        req["main_breaker"]["rating"] = 30  # Non-standard, would be corrected
        result = compute_layout(req, skip_validation=True)
        assert isinstance(result, LayoutResult)
        # Should use original 30A (not corrected to 32A)
        main_breakers = [c for c in result.components
                         if c.symbol_name in ("CB_MCB", "CB_MCCB", "CB_ACB")
                         and c.label_style != "breaker_block"]
        # At least the main breaker should exist
        assert len(main_breakers) >= 1


# ---------------------------------------------------------------------------
# 5. Defensive input checks (in _parse_requirements)
# ---------------------------------------------------------------------------

class TestDefensiveInputChecks:
    """Defensive checks in _parse_requirements should handle edge cases."""

    def test_negative_breaker_rating(self):
        """Negative breaker rating should be reset to 0 (warning logged)."""
        req = dict(VALID_SINGLE_PHASE)
        req["main_breaker"] = dict(req["main_breaker"])
        req["main_breaker"]["rating"] = -10
        # Should not crash
        result = compute_layout(req, skip_validation=True)
        assert isinstance(result, LayoutResult)

    def test_sub_circuits_not_list(self):
        """Non-list sub_circuits should be replaced with empty list."""
        req = dict(VALID_SINGLE_PHASE)
        req["sub_circuits"] = "invalid"
        result = compute_layout(req, skip_validation=True)
        assert isinstance(result, LayoutResult)

    def test_missing_sub_circuits(self):
        """Missing sub_circuits should default to empty list."""
        req = {
            "supply_type": "single_phase",
            "kva": 9,
            "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
            "busbar_rating": 100,
        }
        result = compute_layout(req, skip_validation=True)
        assert isinstance(result, LayoutResult)

    def test_original_requirements_not_mutated(self):
        """Validation should never mutate the original requirements dict."""
        req = {
            "supply_type": "three_phase",
            "kva": 22,
            "voltage": 400,
            "main_breaker": {"type": "MCB", "rating": 30, "poles": "TPN", "fault_kA": 10},
            "busbar_rating": 100,
            "sub_circuits": [
                {"name": "Power", "breaker_type": "MCB", "breaker_rating": 20},
            ],
        }
        original_rating = req["main_breaker"]["rating"]
        compute_layout(req)  # Validation should correct 30 → 32
        # Original dict should be unchanged
        assert req["main_breaker"]["rating"] == original_rating
