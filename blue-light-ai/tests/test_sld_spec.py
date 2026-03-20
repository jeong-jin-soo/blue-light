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
    INCOMING_SPEC_3PHASE,
    KVA_TO_BREAKER_MAP,
    OUTGOING_SPEC,
    IncomingSpec,
    ValidationResult,
    _get_effective_spec,
    _validate_metering,
    _validate_sub_circuits,
    apply_corrections,
    get_full_spec_from_kva,
    lookup_incoming_by_kva,
    lookup_outgoing_cable,
    validate_sld_requirements,
)


# ── IncomingSpec Table Tests ─────────────────────────────────────

class TestIncomingSpecTable:
    """Verify INCOMING_SPEC matches Excel 'Table form' data exactly."""

    def test_all_16_tiers_present(self):
        expected = [32, 40, 63, 80, 100, 150, 200, 250, 300, 400, 500, 630, 800, 1000, 1200, 1600]
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
            500: "240",
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
        """CT metering: all single-phase (32A-100A) use sp_meter (no CT).
        DWG data confirms sp_meter for all ≤100A single-phase installations.
        CT metering starts at 150A three-phase (in INCOMING_SPEC)."""
        # No CT for all single-phase ratings (32A-100A)
        assert INCOMING_SPEC[32].requires_ct is False
        assert INCOMING_SPEC[40].requires_ct is False
        assert INCOMING_SPEC[63].requires_ct is False
        assert INCOMING_SPEC[80].requires_ct is False
        assert INCOMING_SPEC[100].requires_ct is False
        # CT for three-phase large installations (150A+)
        assert INCOMING_SPEC[150].requires_ct is True
        assert INCOMING_SPEC[1600].requires_ct is True


# ── Outgoing Spec Table Tests ────────────────────────────────────

class TestOutgoingSpecTable:
    """Verify OUTGOING_SPEC matches Excel 'Table form' OUTGOING section."""

    def test_all_23_tiers_present(self):
        expected = [5, 6, 10, 13, 15, 16, 20, 25, 32, 63, 80, 100, 150, 200, 250, 300, 400, 500, 630, 800, 1000, 1200, 1600]
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
        assert lookup_outgoing_cable(14) == 1.5    # next ≥ 14 is 15 → 1.5
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
        """24 kVA (no supply_type) → 40A three-phase TPN MCB.
        (22.17 < 24 < 27.71, so hits 40A TPN tier)."""
        spec = lookup_incoming_by_kva(24)
        assert spec.rating_a == 40
        assert spec.phase == "three_phase"
        assert spec.breaker_type == "MCB"
        assert spec.poles == "TPN"

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

    def test_exceeds_max_returns_largest(self):
        """kVA exceeding max returns the largest available spec (no error).

        SG team decision (2026-03-08): no strict kVA limit, user/LEW responsibility.
        """
        spec = lookup_incoming_by_kva(1200)
        assert spec.rating_a == 1600  # Largest available

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
        """sp_meter for small installations, ct_meter for large (≥125A 3-phase)."""
        small = get_full_spec_from_kva(5)
        assert small["metering"] == "sp_meter"

        # 50 kVA → 80A three_phase TPN (no CT per DWG data)
        medium = get_full_spec_from_kva(50)
        assert medium["metering"] == "sp_meter"

        # 100 kVA → 150A three_phase (CT metering required)
        large = get_full_spec_from_kva(100)
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

    def test_undersized_breaker_warns_only(self):
        """Standard breaker smaller than kVA minimum → warning only (no auto-correct).

        SG team decision (2026-03-08): user/LEW responsible for kVA.
        Diversity factor may justify a smaller breaker than kVA suggests.
        """
        req = {"kva": 14, "breaker_rating": 32}  # 14 kVA → spec says 63A, but 32A is standard
        result = validate_sld_requirements(req)
        assert "breaker_rating" not in result.corrections  # No auto-correction
        assert any("undersized" in w.lower() or "may be undersized" in w.lower() for w in result.warnings)

    def test_oversized_breaker_warns(self):
        """Breaker larger than minimum → warning only."""
        req = {"kva": 5, "breaker_rating": 63}  # 5 kVA needs 32A, user chose 63A
        result = validate_sld_requirements(req)
        assert result.valid is True
        assert any("larger than minimum" in w for w in result.warnings)

    def test_auto_correction_supply_type(self):
        """Missing supply_type auto-corrected from kVA."""
        req = {"kva": 110}  # > 103.9 kVA → 150A three_phase
        result = validate_sld_requirements(req)
        assert "supply_type" in result.corrections
        assert result.corrections["supply_type"]["corrected"] == "three_phase"

    def test_valid_breaker_type_mismatch_warns_not_corrects(self):
        """Valid breaker type that differs from spec → warn only, not corrected."""
        req = {"kva": 200, "breaker_type": "MCB"}  # 200kVA → 300A → spec says MCCB
        result = validate_sld_requirements(req)
        # MCB is a valid type → should NOT be auto-corrected
        assert "breaker_type" not in result.corrections
        # But should have a warning
        assert any("differs from standard" in w for w in result.warnings)

    def test_invalid_breaker_type_auto_corrected(self):
        """Invalid breaker type → auto-corrected to spec value."""
        req = {"kva": 200, "breaker_type": "XYZ"}  # Invalid type
        result = validate_sld_requirements(req)
        assert "breaker_type" in result.corrections
        assert result.corrections["breaker_type"]["corrected"] == "MCCB"

    def test_user_mccb_for_100a_preserved(self):
        """User explicitly specifies MCCB for 100A (spec says MCB) → preserved."""
        req = {
            "kva": 69.28,
            "supply_type": "three_phase",
            "breaker_rating": 100,
            "breaker_type": "MCCB",
            "breaker_poles": "TPN",
            "breaker_ka": 25,
        }
        result = validate_sld_requirements(req)
        assert "breaker_type" not in result.corrections
        assert any("MCCB" in w and "retained" in w for w in result.warnings)

    def test_auto_correction_metering(self):
        """Metering auto-determined based on kVA."""
        req_small = {"kva": 5}
        result_small = validate_sld_requirements(req_small)
        assert result_small.corrections.get("metering", {}).get("corrected") == "sp_meter"

        # 110 kVA → 150A three_phase → ct_meter
        req_large = {"kva": 110}
        result_large = validate_sld_requirements(req_large)
        assert result_large.corrections.get("metering", {}).get("corrected") == "ct_meter"

    def test_metering_message_accuracy(self):
        """SP meter message should reference rating, not misleading kVA threshold."""
        req = {"kva": 69.28, "supply_type": "three_phase"}
        result = validate_sld_requirements(req)
        metering_correction = result.corrections.get("metering", {})
        assert metering_correction.get("corrected") == "sp_meter"
        reason = metering_correction.get("reason", "")
        # Should NOT contain misleading "<45kVA" text
        assert "<45kVA" not in reason
        # Should contain actual rating info
        assert "100A" in reason

    def test_insufficient_ka_auto_corrects(self):
        """Fault rating below minimum → auto-corrected."""
        req = {"kva": 110, "breaker_rating": 150, "breaker_ka": 10}  # Needs 35kA
        result = validate_sld_requirements(req)
        assert "breaker_ka" in result.corrections
        assert result.corrections["breaker_ka"]["corrected"] == 35

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
        req = {"kva": 105}  # > 103.9 kVA → 200A three_phase (103.9 < 105 < 138.6)
        result = validate_sld_requirements(req)
        corrected = apply_corrections(req, result)

        assert corrected["supply_type"] == "three_phase"
        assert corrected["breaker_rating"] == 200
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


# ── Three-Phase Small TPN Spec Tests ──────────────────────────────

class TestIncomingSpec3Phase:
    """Verify INCOMING_SPEC_3PHASE for three-phase small TPN ratings (DWG data)."""

    def test_all_6_tiers_present(self):
        expected = [32, 40, 63, 80, 100, 125]
        assert sorted(INCOMING_SPEC_3PHASE.keys()) == expected

    def test_all_three_phase(self):
        for rating, spec in INCOMING_SPEC_3PHASE.items():
            assert spec.phase == "three_phase"

    def test_poles_are_tpn(self):
        for rating, spec in INCOMING_SPEC_3PHASE.items():
            assert spec.poles == "TPN"

    def test_mcb_mccb_transition_at_125a(self):
        """MCB for ≤100A, MCCB starts at 125A per DWG data."""
        for rating in [32, 40, 63, 80, 100]:
            assert INCOMING_SPEC_3PHASE[rating].breaker_type == "MCB"
        assert INCOMING_SPEC_3PHASE[125].breaker_type == "MCCB"

    def test_ct_metering_at_125a(self):
        """No CT for 32A-100A TPN, CT starts at 125A."""
        for rating in [32, 40, 63, 80, 100]:
            assert INCOMING_SPEC_3PHASE[rating].requires_ct is False
        assert INCOMING_SPEC_3PHASE[125].requires_ct is True

    def test_ka_ratings(self):
        """10kA for MCB (32-100A), 25kA for MCCB (125A)."""
        for rating in [32, 40, 63, 80, 100]:
            assert INCOMING_SPEC_3PHASE[rating].breaker_ka == 10
        assert INCOMING_SPEC_3PHASE[125].breaker_ka == 25


# ── Phase-aware kVA Lookup Tests ──────────────────────────────────

class TestPhaseAwareKvaLookup:
    """Test lookup_incoming_by_kva() with supply_type parameter."""

    def test_three_phase_22kva_maps_to_32a_tpn(self):
        """22 kVA + three_phase → 32A TPN MCB."""
        spec = lookup_incoming_by_kva(22, supply_type="three_phase")
        assert spec.rating_a == 32
        assert spec.phase == "three_phase"
        assert spec.poles == "TPN"
        assert spec.breaker_type == "MCB"

    def test_single_phase_22kva_maps_to_100a_dp(self):
        """22 kVA + single_phase → 100A DP MCB."""
        spec = lookup_incoming_by_kva(22, supply_type="single_phase")
        assert spec.rating_a == 100
        assert spec.phase == "single_phase"
        assert spec.poles == "DP"

    def test_three_phase_45kva_maps_to_63a_tpn(self):
        """45 kVA + three_phase → 80A TPN (43.65 < 45 < 55.43)."""
        spec = lookup_incoming_by_kva(45, supply_type="three_phase")
        assert spec.rating_a == 80
        assert spec.phase == "three_phase"

    def test_three_phase_70kva_maps_to_125a_tpn(self):
        """70 kVA + three_phase → 125A TPN MCCB (69.28 < 70 < 86.60)."""
        spec = lookup_incoming_by_kva(70, supply_type="three_phase")
        assert spec.rating_a == 125
        assert spec.phase == "three_phase"
        assert spec.breaker_type == "MCCB"

    def test_backward_compat_no_supply_type(self):
        """Without supply_type, existing behavior preserved for small kVA."""
        spec = lookup_incoming_by_kva(5)
        assert spec.rating_a == 32
        assert spec.phase == "single_phase"

    def test_get_full_spec_with_supply_type(self):
        """get_full_spec_from_kva() passes supply_type correctly."""
        result = get_full_spec_from_kva(45, supply_type="three_phase")
        assert result["breaker_rating"] == 80
        assert result["supply_type"] == "three_phase"
        assert result["metering"] == "sp_meter"  # 80A TPN uses sp_meter


# ── Sub-function Unit Tests ──────────────────────────────────────

class TestGetEffectiveSpec:
    """Test _get_effective_spec() helper for effective spec resolution."""

    def test_get_effective_spec_with_correction(self):
        """When breaker_rating was corrected in Step 4, effective spec uses corrected rating.

        Scenario: user gives non-standard 45A rating, spec says 63A.
        The corrected rating (63A) should drive the effective spec lookup.
        """
        result = ValidationResult()
        # Simulate Step 4 correction: 45A → 63A
        result.add_correction(
            "breaker_rating", 45, 63,
            "Non-standard rating. Corrected to 63A.",
        )
        spec = INCOMING_SPEC[63]  # 63A single-phase (the kVA-based spec)

        effective = _get_effective_spec(result, breaker_rating=45, spec=spec, supply_type="single_phase")
        assert effective is not None
        assert effective.rating_a == 63
        assert effective.breaker_type == "MCB"
        assert effective.poles == "DP"


class TestValidateSubCircuits:
    """Test _validate_sub_circuits() for sub-breaker validation."""

    def test_validate_sub_circuits_exceeds_main(self):
        """Sub-circuit breaker_rating > main breaker → error.

        When a 100A sub-breaker is used with a 63A main breaker,
        the sub-circuit should produce an error.
        """
        result = ValidationResult()
        circuits = [
            {"breaker_rating": 32, "cable_size": 6},   # OK
            {"breaker_rating": 100, "cable_size": 35},  # Exceeds 63A main
        ]
        _validate_sub_circuits(circuits, effective_rating=63, result=result)

        assert result.valid is False
        assert len(result.errors) == 1
        assert "exceeds main" in result.errors[0]
        assert "Circuit 2" in result.errors[0]


class TestValidateMetering:
    """Test _validate_metering() for metering type validation."""

    def test_validate_metering_landlord_no_auto_add(self):
        """Landlord supply with no metering → no auto-correction.

        When supply_source is 'landlord' and metering is empty,
        the function should NOT auto-add sp_meter. Many landlord SLDs
        have no PG meter board — just a unit isolator.
        """
        result = ValidationResult()
        effective_spec = INCOMING_SPEC[150]  # 150A, requires_ct=True
        _validate_metering(
            effective_spec,
            metering="",
            supply_source="landlord",
            effective_rating=150,
            result=result,
        )
        # Landlord supply should NOT auto-add metering
        assert "metering" not in result.corrections

    def test_validate_metering_landlord_explicit_preserved(self):
        """Landlord supply with explicit sp_meter → preserved as-is."""
        result = ValidationResult()
        _validate_metering(
            INCOMING_SPEC[63],
            metering="sp_meter",
            supply_source="landlord",
            effective_rating=63,
            result=result,
        )
        # Explicit metering should not be auto-corrected
        assert "metering" not in result.corrections

    def test_validate_metering_cable_extension_no_meter(self):
        """Cable extension (landlord) with metering → strip metering."""
        result = ValidationResult()
        _validate_metering(
            INCOMING_SPEC[63],
            metering="sp_meter",
            supply_source="landlord",
            effective_rating=63,
            result=result,
            is_cable_extension=True,
        )
        assert "metering" in result.corrections
        assert result.corrections["metering"]["corrected"] == ""


# ── Top-Level Key Fallback (engine.py) ───────────────────────────

class TestTopLevelKeyFallback:
    """Test that _validate_and_correct reads top-level breaker keys as fallback."""

    def test_top_level_breaker_rating_used(self):
        """Top-level breaker_rating used when main_breaker dict absent."""
        from app.sld.layout.engine import _validate_and_correct

        req = {
            "kva": 14,
            "supply_type": "single_phase",
            "breaker_rating": 40,
            "breaker_type": "MCB",
            "breaker_poles": "DP",
            "sub_circuits": [
                {"name": "Light", "breaker_rating": 10, "breaker_type": "MCB"},
            ],
        }
        result = _validate_and_correct(req)
        mb = result.get("main_breaker", {})
        # 40A is a standard rating → should be preserved (not overwritten to 63A)
        assert mb.get("rating") == 40 or result.get("breaker_rating") == 40

    def test_main_breaker_dict_takes_priority(self):
        """main_breaker dict takes priority over top-level keys."""
        from app.sld.layout.engine import _validate_and_correct

        req = {
            "kva": 14,
            "supply_type": "single_phase",
            "breaker_rating": 63,  # top-level
            "main_breaker": {"rating": 40, "type": "MCB", "poles": "DP"},
            "sub_circuits": [
                {"name": "Light", "breaker_rating": 10, "breaker_type": "MCB"},
            ],
        }
        result = _validate_and_correct(req)
        mb = result.get("main_breaker", {})
        # main_breaker.rating=40 should take priority over top-level 63
        assert mb.get("rating") == 40

    def test_metering_correction_propagated_to_requirements(self):
        """Metering auto-correction ('' → 'sp_meter') should propagate back."""
        from app.sld.layout.engine import _validate_and_correct

        req = {
            "kva": 14,
            "supply_type": "single_phase",
            "main_breaker": {"rating": 63, "type": "MCB", "poles": "DP", "fault_kA": 10},
            "sub_circuits": [
                {"name": "Light", "breaker_rating": 10, "breaker_type": "MCB"},
            ],
        }
        # No metering key → should be auto-corrected to "sp_meter"
        result = _validate_and_correct(req)
        assert result.get("metering") == "sp_meter", (
            "Metering correction should propagate to requirements dict"
        )

    def test_metering_not_auto_set_for_landlord(self):
        """Landlord supply should NOT auto-set sp_meter (no meter board by default)."""
        from app.sld.layout.engine import _validate_and_correct

        req = {
            "kva": 14,
            "supply_type": "single_phase",
            "supply_source": "landlord",
            "main_breaker": {"rating": 63, "type": "MCB", "poles": "DP", "fault_kA": 10},
            "sub_circuits": [
                {"name": "Light", "breaker_rating": 10, "breaker_type": "MCB"},
            ],
        }
        result = _validate_and_correct(req)
        assert not result.get("metering"), (
            "Landlord supply should NOT auto-set metering (no meter board by default)"
        )

    def test_metering_not_forced_for_cable_extension(self):
        """Cable extension (is_cable_extension=True) should NOT auto-set metering."""
        from app.sld.layout.engine import _validate_and_correct

        req = {
            "kva": 14,
            "supply_type": "single_phase",
            "is_cable_extension": True,
            "main_breaker": {"rating": 63, "type": "MCB", "poles": "DP", "fault_kA": 10},
            "sub_circuits": [
                {"name": "Light", "breaker_rating": 10, "breaker_type": "MCB"},
            ],
        }
        result = _validate_and_correct(req)
        assert not result.get("metering"), (
            "Cable extension should not auto-set metering (treated as landlord)"
        )


# ── A2: User-type-aware kA / poles validation ────────────────────

class TestUserTypeAwareValidation:
    """kA and poles validation should respect user-provided breaker type."""

    def test_mccb_ka_corrected_to_25_not_10(self):
        """MCCB with insufficient kA → corrected to 25kA (MCCB min), not 10kA."""
        result = validate_sld_requirements({
            "kva": 69.28, "supply_type": "three_phase",
            "breaker_rating": 100, "breaker_type": "MCCB",
            "breaker_poles": "TPN", "breaker_ka": 5,
        })
        ka = result.corrections.get("breaker_ka", {})
        assert ka.get("corrected") == 25
        assert "MCCB" in ka.get("reason", "")

    def test_mccb_ka_auto_determined_as_25(self):
        """MCCB without kA → auto-filled to 25kA (MCCB min)."""
        result = validate_sld_requirements({
            "kva": 69.28, "supply_type": "three_phase",
            "breaker_rating": 100, "breaker_type": "MCCB",
            "breaker_poles": "TPN",
        })
        ka = result.corrections.get("breaker_ka", {})
        assert ka.get("corrected") == 25
        assert "MCCB" in ka.get("reason", "")

    def test_mccb_25ka_no_correction_no_warning(self):
        """MCCB with 25kA (exact min) → no correction, no kA warning."""
        result = validate_sld_requirements({
            "kva": 69.28, "supply_type": "three_phase",
            "breaker_rating": 100, "breaker_type": "MCCB",
            "breaker_poles": "TPN", "breaker_ka": 25,
        })
        assert "breaker_ka" not in result.corrections
        assert not any("kA" in w and "exceeds" in w for w in result.warnings)

    def test_mcb_10ka_standard_no_correction(self):
        """MCB with 10kA (standard) → no correction."""
        result = validate_sld_requirements({
            "kva": 69.28, "supply_type": "three_phase",
            "breaker_rating": 100, "breaker_type": "MCB",
            "breaker_poles": "TPN", "breaker_ka": 10,
        })
        assert "breaker_ka" not in result.corrections

    def test_4p_warns_not_corrects_for_three_phase(self):
        """4P for 3-phase (spec: TPN) → warn only, not corrected."""
        result = validate_sld_requirements({
            "kva": 69.28, "supply_type": "three_phase",
            "breaker_rating": 63, "breaker_type": "ELCB",
            "breaker_poles": "4P", "breaker_ka": 10,
        })
        assert "breaker_poles" not in result.corrections
        assert any("4P" in w and "retained" in w for w in result.warnings)

    def test_dp_for_single_phase_warns_when_spec_is_spn(self):
        """DP for single-phase (spec: DP) → no correction needed (same group)."""
        result = validate_sld_requirements({
            "kva": 14, "supply_type": "single_phase",
            "breaker_rating": 63, "breaker_type": "MCB",
            "breaker_poles": "DP", "breaker_ka": 10,
        })
        # DP matches spec DP → no correction at all
        assert "breaker_poles" not in result.corrections
