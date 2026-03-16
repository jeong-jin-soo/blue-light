"""
Unit tests for the SLD circuit input normalizer.

Tests cover:
- NormalizedCircuit: typed contract, dict compatibility, key alias resolution
- normalize_circuit: flat/nested breaker dict, MCB text parsing, cable defaults
- normalize_application_info: key aliases, contractor splitting
- _parse_mcb_spec: text-based MCB specification parsing
"""

import pytest

from app.sld.circuit_normalizer import (
    NormalizedCircuit,
    _parse_mcb_spec,
    normalize_application_info,
    normalize_circuit,
)


# =============================================
# NormalizedCircuit (A3)
# =============================================

class TestNormalizedCircuit:
    """NormalizedCircuit typed contract and dict compatibility."""

    def test_dict_get_returns_typed_field(self):
        nc = NormalizedCircuit(name="Lighting", breaker_rating=10)
        assert nc.get("name") == "Lighting"
        assert nc.get("breaker_rating") == 10

    def test_dict_get_default_for_empty_string(self):
        nc = NormalizedCircuit()
        assert nc.get("name", "fallback") == "fallback"

    def test_dict_get_zero_returned_as_is(self):
        nc = NormalizedCircuit(breaker_rating=0)
        assert nc.get("breaker_rating") == 0

    def test_bracket_access(self):
        nc = NormalizedCircuit(breaker_type="MCCB")
        assert nc["breaker_type"] == "MCCB"

    def test_contains_for_set_fields(self):
        nc = NormalizedCircuit(name="Power", breaker_rating=20)
        assert "name" in nc
        assert "breaker_rating" in nc

    def test_contains_false_for_empty_fields(self):
        nc = NormalizedCircuit()
        assert "name" not in nc  # empty string
        assert "breaker_rating" not in nc  # 0

    def test_setitem_updates_field(self):
        nc = NormalizedCircuit()
        nc["breaker_type"] = "RCCB"
        assert nc.breaker_type == "RCCB"

    def test_setitem_extra_field(self):
        nc = NormalizedCircuit()
        nc["custom_key"] = "custom_val"
        assert nc.get("custom_key") == "custom_val"

    def test_setdefault_does_not_overwrite(self):
        nc = NormalizedCircuit(breaker_type="MCCB")
        nc.setdefault("breaker_type", "MCB")
        assert nc.breaker_type == "MCCB"

    def test_to_dict(self):
        nc = NormalizedCircuit(name="Test", breaker_rating=32)
        d = nc.to_dict()
        assert isinstance(d, dict)
        assert d["name"] == "Test"
        assert d["breaker_rating"] == 32

    def test_extra_keys_in_to_dict(self):
        nc = NormalizedCircuit()
        nc["custom"] = 42
        d = nc.to_dict()
        assert d["custom"] == 42


class TestNormalizeCircuitReturnsTyped:
    """normalize_circuit() returns NormalizedCircuit with typed access."""

    def test_returns_normalized_circuit(self):
        result = normalize_circuit({"name": "Lighting", "breaker_rating": 10})
        assert isinstance(result, NormalizedCircuit)
        assert result.name == "Lighting"
        assert result.breaker_rating == 10

    def test_key_aliases_resolved(self):
        """All key aliases should be resolved to canonical names."""
        raw = {
            "circuit_name": "Socket",  # alias for 'name'
            "breaker": {"rating": 20, "char": "B"},  # nested aliases
        }
        result = normalize_circuit(raw)
        assert result.name == "Socket"
        assert result.breaker_rating == 20
        assert result.breaker_characteristic == "B"

    def test_description_alias_for_name(self):
        result = normalize_circuit({"description": "Aircon Unit"})
        assert result.name == "Aircon Unit"

    def test_idempotent(self):
        """Normalizing a NormalizedCircuit returns it unchanged."""
        nc = NormalizedCircuit(name="Test", breaker_rating=32)
        result = normalize_circuit(nc)
        assert result is nc

    def test_phase_normalized(self):
        result = normalize_circuit({"name": "L1 Socket", "phase": "R"})
        assert result.phase == "L1"


# =============================================
# _parse_mcb_spec
# =============================================

class TestParseMcbSpec:
    """Parse text-based MCB specification strings."""

    def test_basic_rating(self):
        result = _parse_mcb_spec("20A")
        assert result["breaker_rating"] == 20

    def test_rating_with_type(self):
        result = _parse_mcb_spec("10A SP Type B")
        assert result["breaker_rating"] == 10
        assert result["breaker_poles"] == "SPN"  # SP → SPN normalization
        assert result["breaker_characteristic"] == "B"

    def test_dp_isolator(self):
        result = _parse_mcb_spec("20A DP ISOL")
        assert result["breaker_rating"] == 20
        assert result["breaker_poles"] == "DP"
        assert result["breaker_type"] == "ISOLATOR"

    def test_tpn_mcb(self):
        result = _parse_mcb_spec("63A TPN MCB 10kA")
        assert result["breaker_rating"] == 63
        assert result["breaker_poles"] == "TPN"
        assert result["breaker_type"] == "MCB"
        assert result["fault_kA"] == 10

    def test_mccb(self):
        result = _parse_mcb_spec("100A TPN MCCB 25kA")
        assert result["breaker_type"] == "MCCB"
        assert result["fault_kA"] == 25

    def test_type_c(self):
        result = _parse_mcb_spec("32A DP Type C")
        assert result["breaker_characteristic"] == "C"

    def test_spare_returns_empty(self):
        result = _parse_mcb_spec("Spare")
        assert result == {}

    def test_empty_returns_empty(self):
        result = _parse_mcb_spec("")
        assert result == {}

    def test_rccb(self):
        result = _parse_mcb_spec("40A DP RCCB")
        assert result["breaker_type"] == "RCCB"

    def test_elcb(self):
        result = _parse_mcb_spec("63A TPN ELCB")
        assert result["breaker_type"] == "ELCB"

    def test_acb(self):
        result = _parse_mcb_spec("800A 4P ACB 50kA")
        assert result["breaker_type"] == "ACB"
        assert result["fault_kA"] == 50

    def test_sp_normalized_to_spn(self):
        result = _parse_mcb_spec("20A SP")
        assert result["breaker_poles"] == "SPN"

    def test_4p_poles(self):
        result = _parse_mcb_spec("100A 4P MCCB")
        assert result["breaker_poles"] == "4P"

    def test_isolator_short_form(self):
        """ISO. should match as ISOLATOR."""
        result = _parse_mcb_spec("20A DP ISO")
        assert result["breaker_type"] == "ISOLATOR"


# =============================================
# normalize_circuit
# =============================================

class TestNormalizeCircuit:
    """Normalize sub-circuit dicts to flat keys."""

    def test_non_dict_passthrough(self):
        assert normalize_circuit("not a dict") == "not a dict"

    def test_empty_dict(self):
        result = normalize_circuit({})
        assert result["breaker_type"] == "MCB"  # default

    def test_flat_keys_preserved(self):
        raw = {"breaker_rating": 20, "breaker_type": "MCCB"}
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 20
        assert result["breaker_type"] == "MCCB"

    def test_nested_breaker_flattened(self):
        raw = {"breaker": {"rating": 32, "type": "MCB", "characteristic": "C"}}
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 32
        assert result["breaker_type"] == "MCB"
        assert result["breaker_characteristic"] == "C"

    def test_flat_keys_take_priority_over_nested(self):
        raw = {
            "breaker_rating": 20,
            "breaker": {"rating": 32},  # Should NOT override flat key
        }
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 20

    def test_mcb_text_parsed(self):
        raw = {"mcb": "10A SP Type B"}
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 10
        assert result["breaker_poles"] == "SPN"
        assert result["breaker_characteristic"] == "B"

    def test_string_rating_normalized(self):
        raw = {"breaker_rating": "20A"}
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 20

    def test_cable_method_default(self):
        raw = {"cable": {"size_mm2": 2.5}}
        result = normalize_circuit(raw)
        assert result["cable"]["method"] == "METAL TRUNKING"
        assert result["cable"]["cpc_type"] == "PVC"

    def test_cable_method_preserved(self):
        raw = {"cable": {"size_mm2": 2.5, "method": "CONDUIT"}}
        result = normalize_circuit(raw)
        assert result["cable"]["method"] == "CONDUIT"

    def test_cable_numeric_size_stringified(self):
        raw = {"cable": {"size_mm2": 4.0, "cpc_mm2": 2.5}}
        result = normalize_circuit(raw)
        assert result["cable"]["size_mm2"] == "4.0"
        assert result["cable"]["cpc_mm2"] == "2.5"

    def test_nested_breaker_char_aliases(self):
        """Various characteristic key names should be normalized."""
        for key in ["breaker_characteristic", "characteristic", "char", "trip_curve"]:
            raw = {"breaker": {key: "C"}}
            result = normalize_circuit(raw)
            assert result.get("breaker_characteristic") == "C", f"Failed for key: {key}"

    def test_nested_breaker_fault_ka(self):
        raw = {"breaker": {"fault_kA": 10}}
        result = normalize_circuit(raw)
        assert result["fault_kA"] == 10

    def test_mcb_text_not_parsed_when_rating_exists(self):
        raw = {"breaker_rating": 20, "mcb": "10A SP Type B"}
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 20  # Flat key preserved


# =============================================
# normalize_application_info
# =============================================

class TestNormalizeApplicationInfo:
    """Normalize application_info keys for title block."""

    def test_non_dict_returns_empty(self):
        assert normalize_application_info(None) == {}
        assert normalize_application_info("string") == {}

    def test_drawing_no_alias(self):
        result = normalize_application_info({"drawing_no": "DWG-001"})
        assert result["drawing_number"] == "DWG-001"

    def test_drawing_number_not_overwritten(self):
        result = normalize_application_info({
            "drawing_no": "OLD",
            "drawing_number": "NEW",
        })
        assert result["drawing_number"] == "NEW"

    def test_electrical_contractor_split_newline(self):
        result = normalize_application_info({
            "electrical_contractor": "ABC Electric\n123 Main St\nSingapore 123456"
        })
        assert result["elec_contractor"] == "ABC Electric"
        assert "123 Main St" in result["elec_contractor_addr"]

    def test_electrical_contractor_split_backslash_p(self):
        result = normalize_application_info({
            "electrical_contractor": "ABC Electric\\P123 Main St"
        })
        assert result["elec_contractor"] == "ABC Electric"
        assert "123 Main St" in result["elec_contractor_addr"]

    def test_main_contractor_alias(self):
        result = normalize_application_info({"main_contractor": "Builder Co"})
        assert result["contractor_name"] == "Builder Co"

    def test_original_not_mutated(self):
        original = {"drawing_no": "DWG-001"}
        result = normalize_application_info(original)
        assert "drawing_number" not in original  # Original unchanged
        assert "drawing_number" in result

    def test_empty_dict(self):
        result = normalize_application_info({})
        assert result == {}

    def test_single_line_contractor_not_split(self):
        result = normalize_application_info({
            "electrical_contractor": "Simple Corp"
        })
        assert "elec_contractor" not in result
