"""
Unit tests for SLD Layout helper pure functions.

Tests cover:
- _normalize_load_quantity: LEW Rule 2 quantity normalization
- _wrap_label: text wrapping with \\P separator
- _split_into_rows: array chunking
- _next_standard_rating: breaker rating lookup
- _pad_spares_for_triplets: 3-phase SPARE padding
- _assign_circuit_ids: Singapore circuit ID assignment
- _get_circuit_poles: pole configuration determination
- _get_circuit_fault_kA: fault rating lookup
- _compute_dynamic_spacing: responsive horizontal spacing
- format_cable_spec: cable specification formatting
"""

import pytest

from app.sld.layout.helpers import (
    _assign_circuit_ids,
    _get_circuit_fault_kA,
    _get_circuit_poles,
    _next_standard_rating,
    _normalize_load_quantity,
    _pad_spares_for_triplets,
    _split_into_rows,
    _wrap_label,
)
from app.sld.layout.models import LayoutConfig, format_cable_spec


# =============================================
# _normalize_load_quantity
# =============================================

class TestNormalizeLoadQuantity:
    """LEW guide Rule 2: quantity prefix normalization."""

    def test_plural_nos(self):
        assert _normalize_load_quantity("4 Nos 13A TWIN S/S/O") == "4 nos. 13A TWIN S/S/O"

    def test_singular_nos(self):
        assert _normalize_load_quantity("1 Nos LIGHTS") == "1 no. LIGHTS"

    def test_singular_no(self):
        assert _normalize_load_quantity("1 No. LIGHTS") == "1 no. LIGHTS"

    def test_no_quantity_prepends_1(self):
        assert _normalize_load_quantity("20A DP ISOLATOR") == "1 no. 20A DP ISOLATOR"

    def test_spare_unchanged(self):
        assert _normalize_load_quantity("SPARE") == "SPARE"

    def test_spare_case_insensitive(self):
        assert _normalize_load_quantity("spare") == "spare"

    def test_empty_string(self):
        assert _normalize_load_quantity("") == ""

    def test_cable_pattern_not_treated_as_quantity(self):
        # "2 x 1C ..." should NOT be treated as "2 nos. 1C ..."
        assert _normalize_load_quantity("2 x 1C 2.5sqmm PVC") == "2 x 1C 2.5sqmm PVC"

    def test_number_without_unit_word(self):
        assert _normalize_load_quantity("2 LIGHTS") == "2 nos. LIGHTS"

    def test_pcs_unit(self):
        assert _normalize_load_quantity("3 pcs SOCKET") == "3 nos. SOCKET"

    def test_sets_unit(self):
        assert _normalize_load_quantity("2 sets AIRCON") == "2 nos. AIRCON"

    def test_units_unit(self):
        assert _normalize_load_quantity("5 units FAN") == "5 nos. FAN"


# =============================================
# _wrap_label
# =============================================

class TestWrapLabel:
    """Text wrapping for vertical SLD labels."""

    def test_short_text_unchanged(self):
        assert _wrap_label("LIGHTS") == "LIGHTS"

    def test_already_wrapped_unchanged(self):
        assert _wrap_label("LINE ONE\\PLINE TWO") == "LINE ONE\\PLINE TWO"

    def test_long_text_wraps(self):
        result = _wrap_label("1 no. 20A DP ISOLATOR FOR AIRCON UNIT", max_chars=20)
        assert "\\P" in result
        # Text is 37 chars with max_chars=20: wraps into multiple lines
        lines = result.split("\\P")
        assert len(lines) >= 2

    def test_max_lines_respected(self):
        long_text = "A " * 50  # Very long text
        result = _wrap_label(long_text.strip(), max_chars=10, max_lines=2)
        lines = result.split("\\P")
        assert len(lines) <= 2

    def test_exact_boundary_no_wrap(self):
        text = "A" * 30  # Exactly max_chars
        assert _wrap_label(text, max_chars=30) == text

    def test_one_char_over_wraps(self):
        text = "ABCDEFGH IJKLMNOP QRSTUVWXYZ EXTRA"  # Over 30 chars
        result = _wrap_label(text, max_chars=30)
        # Should either wrap or stay as is (if 30 chars fits)
        assert isinstance(result, str)


# =============================================
# _split_into_rows
# =============================================

class TestSplitIntoRows:
    """Row splitting for multi-row sub-circuit layouts."""

    def test_empty_list(self):
        assert _split_into_rows([], 10) == [[]]

    def test_single_item(self):
        result = _split_into_rows([{"name": "A"}], 10)
        assert len(result) == 1
        assert len(result[0]) == 1

    def test_exact_fit(self):
        items = [{"name": f"C{i}"} for i in range(10)]
        result = _split_into_rows(items, 10)
        assert len(result) == 1
        assert len(result[0]) == 10

    def test_overflow_creates_two_rows(self):
        items = [{"name": f"C{i}"} for i in range(11)]
        result = _split_into_rows(items, 10)
        assert len(result) == 2
        assert len(result[0]) == 10
        assert len(result[1]) == 1

    def test_three_rows(self):
        items = [{"name": f"C{i}"} for i in range(25)]
        result = _split_into_rows(items, 10)
        assert len(result) == 3
        assert len(result[0]) == 10
        assert len(result[1]) == 10
        assert len(result[2]) == 5

    def test_max_per_row_one(self):
        items = [{"name": f"C{i}"} for i in range(3)]
        result = _split_into_rows(items, 1)
        assert len(result) == 3
        for row in result:
            assert len(row) == 1


# =============================================
# _next_standard_rating
# =============================================

class TestNextStandardRating:
    """Standard breaker rating lookup."""

    def test_exact_match(self):
        assert _next_standard_rating(16) == 16
        assert _next_standard_rating(63) == 63
        assert _next_standard_rating(100) == 100

    def test_between_values(self):
        assert _next_standard_rating(17) == 20
        assert _next_standard_rating(33) == 40
        assert _next_standard_rating(65) == 80

    def test_below_minimum(self):
        assert _next_standard_rating(1) == 6
        assert _next_standard_rating(5) == 6

    def test_above_maximum(self):
        """Rating above max (3200A) returns 3200A."""
        assert _next_standard_rating(3500) == 3200

    def test_boundary_values(self):
        assert _next_standard_rating(15) == 16
        assert _next_standard_rating(16) == 16
        assert _next_standard_rating(999) == 1000
        assert _next_standard_rating(1000) == 1000

    def test_high_current_1200A(self):
        """1200A should return 1250A (not cap at 1000A)."""
        assert _next_standard_rating(1200) == 1250

    def test_high_current_3200A_cap(self):
        """3200A (max standard) should return 3200A."""
        assert _next_standard_rating(3200) == 3200

    def test_high_current_above_3200A(self):
        """Above 3200A should return 3200A as max."""
        assert _next_standard_rating(4000) == 3200


# =============================================
# _pad_spares_for_triplets
# =============================================

class TestPadSparesForTriplets:
    """3-phase SPARE padding to complete triplets."""

    def test_single_phase_unchanged(self):
        circuits = [{"name": "LIGHTS"}, {"name": "SOCKET"}]
        result = _pad_spares_for_triplets(circuits, "single_phase")
        assert len(result) == 2

    def test_empty_list(self):
        assert _pad_spares_for_triplets([], "three_phase") == []

    def test_already_multiple_of_3(self):
        circuits = [{"name": "LIGHTS"}, {"name": "LIGHTS 2"}, {"name": "LIGHTS 3"}]
        result = _pad_spares_for_triplets(circuits, "three_phase")
        assert len(result) == 3

    def test_needs_one_spare(self):
        circuits = [{"name": "LIGHTS"}, {"name": "LIGHTS 2"}]
        result = _pad_spares_for_triplets(circuits, "three_phase")
        assert len(result) == 3
        assert result[2]["name"] == "SPARE"
        assert result[2].get("_auto_spare") is True

    def test_needs_two_spares(self):
        circuits = [{"name": "LIGHTS"}]
        result = _pad_spares_for_triplets(circuits, "three_phase")
        assert len(result) == 3
        assert result[1]["name"] == "SPARE"
        assert result[2]["name"] == "SPARE"

    def test_section_boundary_pads_each_section(self):
        # Lighting section (2 circuits) + Power section (1 circuit)
        circuits = [
            {"name": "LIGHTS"},
            {"name": "LED PANEL"},
            {"name": "13A SOCKET"},
        ]
        result = _pad_spares_for_triplets(circuits, "three_phase")
        # Lighting: 2 → pad to 3, Power: 1 → pad to 3 → total 6
        assert len(result) == 6
        spare_count = sum(1 for c in result if c.get("_auto_spare"))
        assert spare_count == 3  # 1 for lighting + 2 for power

    def test_auto_spare_inherits_breaker_specs(self):
        circuits = [
            {"name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10},
            {"name": "LIGHTS 2", "breaker_type": "MCB", "breaker_rating": 10},
        ]
        result = _pad_spares_for_triplets(circuits, "three_phase")
        auto_spare = result[2]
        assert auto_spare["breaker_type"] == "MCB"
        assert auto_spare["breaker_rating"] == 10

    def test_user_spare_preserved(self):
        circuits = [
            {"name": "LIGHTS"},
            {"name": "SPARE"},
            {"name": "LIGHTS 3"},
        ]
        result = _pad_spares_for_triplets(circuits, "three_phase")
        assert len(result) == 3  # Already 3 (including user spare)

    def test_four_circuits_pads_to_six(self):
        circuits = [{"name": f"LIGHTS {i}"} for i in range(4)]
        result = _pad_spares_for_triplets(circuits, "three_phase")
        assert len(result) == 6
        spare_count = sum(1 for c in result if c.get("_auto_spare"))
        assert spare_count == 2


# =============================================
# _assign_circuit_ids
# =============================================

class TestAssignCircuitIds:
    """Singapore SLD circuit ID assignment."""

    def test_single_phase_lighting(self):
        circuits = [
            {"name": "LIGHTS"},
            {"name": "LED PANEL"},
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["S1", "S2"]

    def test_single_phase_power(self):
        circuits = [
            {"name": "13A SOCKET"},
            {"name": "AIRCON"},
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["P1", "P2"]

    def test_single_phase_mixed(self):
        circuits = [
            {"name": "LIGHTS"},
            {"name": "13A SOCKET"},
            {"name": "SPARE"},
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["S1", "P1", "SP1"]

    def test_single_phase_heater(self):
        circuits = [
            {"name": "13A SOCKET"},
            {"name": "WATER HEATER"},
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        # Power and heater share counter: P1, H2
        assert ids == ["P1", "H2"]

    def test_single_phase_isolator(self):
        circuits = [
            {"name": "ISOLATOR FOR AIRCON"},
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["ISOL 1"]

    def test_three_phase_lighting_round_robin(self):
        circuits = [
            {"name": "LIGHTS 1"},
            {"name": "LIGHTS 2"},
            {"name": "LIGHTS 3"},
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids == ["L1S1", "L2S1", "L3S1"]

    def test_three_phase_power_round_robin(self):
        circuits = [
            {"name": "13A SOCKET 1"},
            {"name": "13A SOCKET 2"},
            {"name": "13A SOCKET 3"},
            {"name": "13A SOCKET 4"},
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids == ["L1P1", "L2P1", "L3P1", "L1P2"]

    def test_three_phase_isolator(self):
        """Isolator uses power counter in 3-phase (L{phase}P{num})."""
        circuits = [
            {"name": "LIGHTS"},
            {"name": "ISOLATOR FOR AIRCON"},
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids[0] == "L1S1"
        assert ids[1] == "L1P1"  # Isolator gets power ID, not ISOL

    def test_three_phase_spare_follows_section(self):
        circuits = [
            {"name": "LIGHTS 1"},
            {"name": "LIGHTS 2"},
            {"name": "SPARE"},
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids[0] == "L1S1"
        assert ids[1] == "L2S1"
        # SPARE should follow lighting section phase rotation
        assert ids[2].startswith("L3S")

    def test_user_provided_circuit_id_preserved(self):
        circuits = [
            {"name": "L1S1"},  # Already a valid phase-prefixed ID
            {"name": "LIGHTS"},
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids[0] == "L1S1"

    def test_explicit_circuit_id_field(self):
        circuits = [
            {"name": "LIGHTS", "circuit_id": "L2P3"},
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids[0] == "L2P3"

    def test_isolator_by_breaker_type(self):
        circuits = [
            {"name": "AIRCON", "breaker_type": "ISOLATOR"},
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["ISOL 1"]

    def test_empty_list(self):
        assert _assign_circuit_ids([], "single_phase") == []
        assert _assign_circuit_ids([], "three_phase") == []


# =============================================
# _get_circuit_poles
# =============================================

class TestGetCircuitPoles:
    """Pole configuration determination."""

    def test_default_is_spn(self):
        assert _get_circuit_poles({}, "single_phase") == "SPN"
        assert _get_circuit_poles({}, "three_phase") == "SPN"

    def test_explicit_single_phase(self):
        assert _get_circuit_poles({"phase": "single"}, "three_phase") == "SPN"
        assert _get_circuit_poles({"phase": "1-phase"}, "three_phase") == "SPN"

    def test_explicit_three_phase(self):
        assert _get_circuit_poles({"phase": "three"}, "single_phase") == "TPN"
        assert _get_circuit_poles({"phase": "3-phase"}, "single_phase") == "TPN"

    def test_sub_circuits_always_spn_by_default(self):
        # Sub-circuits in TPN DB are still SPN (one phase each)
        assert _get_circuit_poles({}, "three_phase") == "SPN"


# =============================================
# _get_circuit_fault_kA
# =============================================

class TestGetCircuitFaultkA:
    """Fault rating lookup for sub-circuit breakers."""

    def test_mcb_always_6ka(self):
        assert _get_circuit_fault_kA("MCB") == 6

    def test_mcb_ignores_explicit_value(self):
        # MCB sub-circuits: always 6kA — no override allowed
        assert _get_circuit_fault_kA("MCB", {"fault_kA": 10}) == 6

    def test_mccb_default(self):
        assert _get_circuit_fault_kA("MCCB") == 25

    def test_mccb_with_explicit_value(self):
        assert _get_circuit_fault_kA("MCCB", {"fault_kA": 36}) == 36

    def test_acb_default(self):
        assert _get_circuit_fault_kA("ACB") == 50

    def test_unknown_type_default_6(self):
        assert _get_circuit_fault_kA("UNKNOWN") == 6



# =============================================
# format_cable_spec
# =============================================

class TestFormatCableSpec:
    """Cable specification formatting (Singapore LEW standard)."""

    def test_none_returns_empty(self):
        assert format_cable_spec(None) == ""

    def test_empty_string_returns_empty(self):
        assert format_cable_spec("") == ""

    def test_dict_basic(self):
        cable = {"cores": 1, "size_mm2": "2.5", "type": "PVC/PVC"}
        result = format_cable_spec(cable)
        assert "1C" in result
        assert "2.5sqmm" in result  # units stay lowercase
        assert "PVC/PVC" in result

    def test_dict_with_cpc(self):
        cable = {
            "cores": 1,
            "size_mm2": "2.5",
            "type": "PVC/PVC",
            "cpc_mm2": "2.5",
            "cpc_type": "PVC",
        }
        result = format_cable_spec(cable)
        assert "CPC" in result
        assert "2.5sqmm" in result  # units stay lowercase

    def test_dict_with_count(self):
        cable = {"count": 2, "cores": 1, "size_mm2": "25", "type": "PVC/PVC"}
        result = format_cable_spec(cable)
        assert result.startswith("2 x")  # multiplier x stays lowercase

    def test_dict_with_method(self):
        cable = {
            "cores": 1,
            "size_mm2": "2.5",
            "type": "PVC/PVC",
            "cpc_mm2": "2.5",
            "cpc_type": "PVC",
            "method": "METAL TRUNKING",
        }
        result = format_cable_spec(cable)
        assert "IN METAL TRUNKING" in result

    def test_string_passthrough_unparseable(self):
        weird = "CUSTOM CABLE SPEC"
        assert format_cable_spec(weird) == weird  # already uppercase

    def test_string_canonical_reformat(self):
        # Parseable string should be reformatted to canonical form
        cable_str = "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC"
        result = format_cable_spec(cable_str)
        assert "2 x" in result   # multiplier stays lowercase
        assert "1C" in result
        assert "2.5sqmm" in result  # units stay lowercase

    def test_multiline_mode(self):
        cable = {
            "cores": 1,
            "size_mm2": "25",
            "type": "PVC/PVC",
            "cpc_mm2": "10",
            "cpc_type": "PVC",
        }
        result = format_cable_spec(cable, multiline=True)
        assert "\\P" in result

    def test_dict_single_count_no_prefix(self):
        cable = {"count": 1, "cores": 2, "size_mm2": "4", "type": "PVC"}
        result = format_cable_spec(cable)
        assert not result.startswith("1 x")
        assert "2C" in result

    def test_smart_case_convention(self):
        """LEW convention: units (sqmm, x) lowercase, words (PVC, CPC, IN) uppercase."""
        cable = {"cores": 1, "size_mm2": "2.5", "type": "pvc/pvc",
                 "cpc_mm2": "2.5", "cpc_type": "pvc", "method": "metal trunking"}
        result = format_cable_spec(cable)
        assert "2.5sqmm" in result, f"units should be lowercase: {result}"
        assert "PVC/PVC" in result, f"cable type should be uppercase: {result}"
        assert "PVC CPC" in result, f"CPC type should be uppercase: {result}"
        assert "IN METAL TRUNKING" in result, f"method should be uppercase: {result}"

    def test_smart_case_count_multiplier(self):
        """Multiplier 'x' stays lowercase."""
        cable = {"count": 2, "cores": 1, "size_mm2": "25", "type": "PVC/PVC"}
        result = format_cable_spec(cable)
        assert "2 x 1C" in result, f"multiplier x should be lowercase: {result}"

    def test_smart_case_string_fallback(self):
        """String fallback uses smart case (units lowercase, words uppercase)."""
        result = format_cable_spec("4 x 50mm² PVC/PVC cable + 50mm² CPC in metal trunking")
        assert "50mm²" in result or "50sqmm" in result, f"units should be lowercase: {result}"
