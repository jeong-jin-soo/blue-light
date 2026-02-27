"""
Tests for SLD Electrical Specification Tables & Validation.

Verifies:
- kVA → breaker/cable/phase auto-determination
- Incoming spec table completeness
- Outgoing spec table completeness
- Validation logic for Gemini-extracted JSON
- 3-Phase incoming to Single Phase DB detection
"""

import pytest

from app.sld.sld_spec import (
    INCOMING_SPEC,
    KVA_TO_BREAKER_MAP,
    OUTGOING_SPEC,
    IncomingSpec,
    ValidationResult,
    apply_corrections,
    get_full_spec_from_kva,
    lookup_incoming_by_kva,
    lookup_outgoing_cable,
    validate_sld_requirements,
)


# ── IncomingSpec Table Tests ─────────────────────────────────────

class TestIncomingSpecTable:
    """Verify INCOMING_SPEC matches Excel 'Table form' data exactly."""

    def test_all_15_tiers_present(self):
        expected = [32, 40, 63, 80, 100, 150, 200, 250, 300, 400, 630, 800, 1000, 1200, 1600]
        assert sorted(INCOMING_SPEC.keys()) == expected

    def test_cable_sizes_match_excel(self):
        """Cable sizes from Excel Table form column B."""
        expected = {
            32: "6 + 6mmsq E",
            40: "10 + 10mmsq E",
            63: "16 + 16mmsq E",
            80: "25 + 16mmsq E",
            100: "35 + 16mmsq E",
            150: "70",
            200: "95",
            250: "95",
            300: "120",
            400: "185",
            630: "300",
            800: "500",
            1000: "500",
            1200: "630",
            1600: "630",
        }
        for rating, cable in expected.items():
            assert INCOMING_SPEC[rating].cable_size == cable, (
                f"Rating {rating}A: expected cable '{cable}', "
                f"got '{INCOMING_SPEC[rating].cable_size}'"
            )

    def test_breaker_types_match_excel(self):
        """Breaker types from Excel Table form column G."""
        # 32-100A = MCB, 150-630A = MCCB, 800-1600A = ACB
        for rating in [32, 40, 63, 80, 100]:
            assert INCOMING_SPEC[rating].breaker_type == "MCB"
        for rating in [150, 200, 250, 300, 400, 630]:
            assert INCOMING_SPEC[rating].breaker_type == "MCCB"
        for rating in [800, 1000, 1200, 1600]:
            assert INCOMING_SPEC[rating].breaker_type == "ACB"

    def test_phase_assignment(self):
        """32-100A = single_phase, 150+ = three_phase."""
        for rating in [32, 40, 63, 80, 100]:
            assert INCOMING_SPEC[rating].phase == "single_phase"
        for rating in [150, 200, 250, 300, 400, 630, 800, 1000, 1200, 1600]:
            assert INCOMING_SPEC[rating].phase == "three_phase"

    def test_poles_match_excel(self):
        """Poles from Excel Table form column F."""
        for rating in [32, 40, 63, 80, 100]:
            assert INCOMING_SPEC[rating].poles == "DP"
        for rating in [150, 200, 250, 300, 400, 630]:
            assert INCOMING_SPEC[rating].poles == "TPN"
        for rating in [800, 1000, 1200, 1600]:
            assert INCOMING_SPEC[rating].poles == "4P"

    def test_ka_ratings_match_excel(self):
        """kA from Excel Table form column I."""
        for rating in [32, 40, 63, 80, 100]:
            assert INCOMING_SPEC[rating].breaker_ka == 10
        for rating in [150, 200, 250, 300, 400, 630]:
            assert INCOMING_SPEC[rating].breaker_ka == 35
        for rating in [800, 1000, 1200, 1600]:
            assert INCOMING_SPEC[rating].breaker_ka == 50

    def test_ct_threshold(self):
        """CT metering required for kVA ≥ 45 (≈80A+ single-phase, all three-phase)."""
        # No CT for small single-phase
        assert INCOMING_SPEC[32].requires_ct is False
        assert INCOMING_SPEC[40].requires_ct is False
        assert INCOMING_SPEC[63].requires_ct is False
        # CT for larger installations
        assert INCOMING_SPEC[80].requires_ct is True
        assert INCOMING_SPEC[150].requires_ct is True
        assert INCOMING_SPEC[1600].requires_ct is True


# ── Outgoing Spec Table Tests ────────────────────────────────────

class TestOutgoingSpecTable:
    """Verify OUTGOING_SPEC matches Excel 'Table form' OUTGOING section."""

    def test_all_18_tiers_present(self):
        expected = [6, 10, 16, 20, 32, 63, 80, 100, 150, 200, 250, 300, 400, 630, 800, 1000, 1200, 1600]
        assert sorted(OUTGOING_SPEC.keys()) == expected

    def test_cable_sizes_match_excel(self):
        """Outgoing cable sizes from Excel Table form OUTGOING column B."""
        expected = {
            6: 1.5, 10: 1.5, 16: 2.5, 20: 2.5, 32: 6,
            63: 16, 80: 35, 100: 35, 150: 70, 200: 95,
            250: 95, 300: 120, 400: 185, 630: 300, 800: 500,
            1000: 500, 1200: 630, 1600: 630,
        }
        for rating, cable in expected.items():
            assert OUTGOING_SPEC[rating] == cable

    def test_lookup_exact(self):
        assert lookup_outgoing_cable(16) == 2.5
        assert lookup_outgoing_cable(63) == 16
        assert lookup_outgoing_cable(400) == 185

    def test_lookup_non_standard_rounds_up(self):
        """Non-standard ratings should round up to next tier."""
        assert lookup_outgoing_cable(15) == 2.5   # next ≥ 15 is 16 → 2.5
        assert lookup_outgoing_cable(50) == 16     # next ≥ 50 is 63 → 16

    def test_lookup_exceeds_max_raises(self):
        with pytest.raises(ValueError, match="exceeds maximum"):
            lookup_outgoing_cable(2000)


# ── kVA Lookup Tests ─────────────────────────────────────────────

class TestKvaLookup:
    """Test kVA → IncomingSpec auto-determination."""

    def test_small_single_phase(self):
        """5 kVA → 32A single-phase MCB."""
        spec = lookup_incoming_by_kva(5)
        assert spec.rating_a == 32
        assert spec.phase == "single_phase"
        assert spec.breaker_type == "MCB"
        assert spec.cable_size == "6 + 6mmsq E"

    def test_medium_single_phase(self):
        """14 kVA → 63A single-phase MCB."""
        spec = lookup_incoming_by_kva(14)
        assert spec.rating_a == 63
        assert spec.phase == "single_phase"
        assert spec.breaker_type == "MCB"
        assert spec.cable_size == "16 + 16mmsq E"

    def test_boundary_single_to_three_phase(self):
        """23 kVA → 100A (last single-phase tier)."""
        spec = lookup_incoming_by_kva(23)
        assert spec.rating_a == 100
        assert spec.phase == "single_phase"

    def test_first_three_phase(self):
        """24 kVA → 150A (first three-phase tier)."""
        spec = lookup_incoming_by_kva(24)
        assert spec.rating_a == 150
        assert spec.phase == "three_phase"
        assert spec.breaker_type == "MCCB"

    def test_large_three_phase(self):
        """270 kVA → 400A three-phase MCCB (400A handles up to 277.1 kVA)."""
        spec = lookup_incoming_by_kva(270)
        assert spec.rating_a == 400
        assert spec.phase == "three_phase"

    def test_300kva_maps_to_630a(self):
        """300 kVA > 277.1 kVA (400A max) → bumps to 630A."""
        spec = lookup_incoming_by_kva(300)
        assert spec.rating_a == 630

    def test_acb_tier(self):
        """600 kVA → 800A ACB."""
        spec = lookup_incoming_by_kva(600)
        assert spec.rating_a == 1000
        assert spec.breaker_type == "ACB"
        assert spec.breaker_ka == 50

    def test_max_tier(self):
        """1100 kVA → 1600A ACB."""
        spec = lookup_incoming_by_kva(1100)
        assert spec.rating_a == 1600

    def test_exceeds_max_raises(self):
        with pytest.raises(ValueError, match="exceeds maximum"):
            lookup_incoming_by_kva(1200)

    def test_exact_boundary_values(self):
        """Test exact boundary kVA values."""
        # 7.36 kVA is exactly the 32A boundary
        spec = lookup_incoming_by_kva(7.36)
        assert spec.rating_a == 32

        # 7.37 kVA should bump to 40A
        spec = lookup_incoming_by_kva(7.37)
        assert spec.rating_a == 40


# ── get_full_spec_from_kva Tests ─────────────────────────────────

class TestFullSpec:
    """Test the convenience full-spec lookup."""

    def test_returns_complete_dict(self):
        result = get_full_spec_from_kva(10)
        assert isinstance(result, dict)
        expected_keys = {
            "kva", "supply_type", "breaker_rating", "breaker_type",
            "breaker_poles", "breaker_ka", "cable_size", "cable_cores",
            "cable_type", "requires_ct", "requires_isolator", "metering",
            "earth_protection_types",
        }
        assert set(result.keys()) == expected_keys

    def test_metering_auto_selection(self):
        """<45kVA → sp_meter, ≥45kVA → ct_meter."""
        small = get_full_spec_from_kva(5)
        assert small["metering"] == "sp_meter"

        large = get_full_spec_from_kva(50)
        assert large["metering"] == "ct_meter"


# ── Validation Tests ─────────────────────────────────────────────

class TestValidation:
    """Test validate_sld_requirements() for Gemini JSON verification."""

    def test_valid_requirements_pass(self):
        """Correct requirements should pass validation."""
        req = {
            "kva": 14,
            "supply_type": "single_phase",
            "breaker_rating": 63,
            "breaker_type": "MCB",
            "breaker_poles": "DP",
            "breaker_ka": 10,
        }
        result = validate_sld_requirements(req)
        assert result.valid is True
        assert len(result.errors) == 0

    def test_missing_kva_and_rating_errors(self):
        """No kva and no breaker_rating → error."""
        result = validate_sld_requirements({})
        assert result.valid is False
        assert any("must be provided" in e for e in result.errors)

    def test_undersized_breaker_errors(self):
        """Breaker too small for kVA → error."""
        req = {"kva": 14, "breaker_rating": 32}  # 14 kVA needs 63A, not 32A
        result = validate_sld_requirements(req)
        assert result.valid is False
        assert any("undersized" in e for e in result.errors)

    def test_oversized_breaker_warns(self):
        """Breaker larger than minimum → warning only."""
        req = {"kva": 5, "breaker_rating": 63}  # 5 kVA needs 32A, user chose 63A
        result = validate_sld_requirements(req)
        assert result.valid is True
        assert any("larger than minimum" in w for w in result.warnings)

    def test_auto_correction_supply_type(self):
        """Missing supply_type auto-corrected from kVA."""
        req = {"kva": 100}
        result = validate_sld_requirements(req)
        assert "supply_type" in result.corrections
        assert result.corrections["supply_type"]["corrected"] == "three_phase"

    def test_auto_correction_breaker_type(self):
        """Wrong breaker type auto-corrected."""
        req = {"kva": 200, "breaker_type": "MCB"}  # 200kVA → 300A → MCCB
        result = validate_sld_requirements(req)
        assert "breaker_type" in result.corrections
        assert result.corrections["breaker_type"]["corrected"] == "MCCB"

    def test_auto_correction_metering(self):
        """Metering auto-determined based on kVA."""
        req_small = {"kva": 5}
        result_small = validate_sld_requirements(req_small)
        assert result_small.corrections.get("metering", {}).get("corrected") == "sp_meter"

        req_large = {"kva": 100}
        result_large = validate_sld_requirements(req_large)
        assert result_large.corrections.get("metering", {}).get("corrected") == "ct_meter"

    def test_insufficient_ka_errors(self):
        """Fault rating below minimum → error."""
        req = {"kva": 100, "breaker_rating": 150, "breaker_ka": 10}  # Needs 35kA
        result = validate_sld_requirements(req)
        assert any("insufficient" in e for e in result.errors)

    def test_sub_breaker_exceeds_main_errors(self):
        """Sub-breaker larger than main breaker → error."""
        req = {
            "kva": 5,
            "breaker_rating": 32,
            "circuits": [{"breaker_rating": 63}],
        }
        result = validate_sld_requirements(req)
        assert any("exceeds main" in e for e in result.errors)

    def test_sub_circuit_cable_undersized_warns(self):
        """Sub-circuit cable too small → warning."""
        req = {
            "kva": 14,
            "circuits": [{"breaker_rating": 32, "cable_size": 2.5}],  # 32A needs 6mm²
        }
        result = validate_sld_requirements(req)
        assert any("smaller than minimum" in w for w in result.warnings)

    def test_apply_corrections(self):
        """apply_corrections() returns new dict with all fixes applied."""
        req = {"kva": 100}
        result = validate_sld_requirements(req)
        corrected = apply_corrections(req, result)

        assert corrected["supply_type"] == "three_phase"
        assert corrected["breaker_rating"] == 150
        assert corrected["breaker_type"] == "MCCB"
        assert corrected["metering"] == "ct_meter"
        # Original unchanged
        assert "supply_type" not in req or req.get("supply_type") == ""


# ── 3-Phase to Single Phase DB Detection ─────────────────────────

class TestThreePhaseToSinglePhaseDetection:
    """Test detection of non-standard 3-Phase incoming to Single Phase DB."""

    def test_explicit_flag(self):
        """Explicit flag triggers warning."""
        req = {
            "kva": 14,
            "breaker_rating": 63,
            "three_phase_to_single_phase_db": True,
        }
        result = validate_sld_requirements(req)
        assert any("NON-STANDARD" in w for w in result.warnings)

    def test_three_phase_supply_with_dp_poles(self):
        """3-phase supply + DP poles triggers warning."""
        req = {
            "kva": 14,
            "supply_type": "three_phase",
            "breaker_rating": 63,
            "breaker_poles": "DP",
        }
        result = validate_sld_requirements(req)
        assert any("NON-STANDARD" in w for w in result.warnings)

    def test_high_kva_with_single_phase_rating(self):
        """kVA > 23 but breaker ≤ 100A with DP → warning."""
        req = {
            "kva": 30,
            "breaker_rating": 100,
            "breaker_poles": "DP",
        }
        result = validate_sld_requirements(req)
        assert any("NON-STANDARD" in w for w in result.warnings)

    def test_normal_three_phase_no_warning(self):
        """Normal 3-phase setup should NOT trigger warning."""
        req = {
            "kva": 100,
            "supply_type": "three_phase",
            "breaker_rating": 150,
            "breaker_poles": "TPN",
        }
        result = validate_sld_requirements(req)
        assert not any("NON-STANDARD" in w for w in result.warnings)

    def test_normal_single_phase_no_warning(self):
        """Normal single-phase setup should NOT trigger warning."""
        req = {
            "kva": 14,
            "supply_type": "single_phase",
            "breaker_rating": 63,
            "breaker_poles": "DP",
        }
        result = validate_sld_requirements(req)
        assert not any("NON-STANDARD" in w for w in result.warnings)
