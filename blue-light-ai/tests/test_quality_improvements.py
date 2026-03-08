"""
Tests for 7-Step SLD Quality Improvement.

Covers:
- Step 1: circuit_normalizer.py (normalize_circuit, normalize_application_info, _parse_mcb_spec)
- Step 2: circuit_types.py (classify_circuit, resolve_circuit, BREAKER_TO_CABLE)
- Step 3: _get_circuit_poles TPN→SPN for sub-circuits
- Step 5: supply_label selection (from_landlord_riser, from_power_supply)
- Step 6: title_block _fit_font_size
- Step 7: kVA validation relaxation
"""

import pytest

from app.sld.circuit_normalizer import normalize_circuit, normalize_application_info, _parse_mcb_spec
from app.sld.circuit_types import classify_circuit, resolve_circuit, BREAKER_TO_CABLE


# ======================================================================
# Step 1: circuit_normalizer tests
# ======================================================================

class TestNormalizeCircuit:
    """Tests for normalize_circuit()."""

    def test_flatten_nested_breaker(self):
        """Nested breaker dict should be flattened to top-level keys."""
        raw = {
            "name": "Lighting",
            "breaker": {"rating": 10, "type": "MCB", "characteristic": "B", "poles": "SPN"},
        }
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 10
        assert result["breaker_type"] == "MCB"
        assert result["breaker_characteristic"] == "B"
        assert result["breaker_poles"] == "SPN"

    def test_flat_keys_not_overwritten(self):
        """Existing flat keys should NOT be overwritten by nested breaker."""
        raw = {
            "breaker_rating": 20,
            "breaker": {"rating": 10},
        }
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 20  # Flat key preserved

    def test_parse_mcb_text(self):
        """MCB text spec should be parsed when no breaker_rating exists."""
        raw = {"mcb": "10A SP Type B"}
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 10
        assert result["breaker_poles"] == "SPN"
        assert result["breaker_characteristic"] == "B"

    def test_string_rating_normalized(self):
        """String breaker_rating like '20A' should become int 20."""
        raw = {"breaker_rating": "20A"}
        result = normalize_circuit(raw)
        assert result["breaker_rating"] == 20

    def test_cable_method_default(self):
        """Cable dict should get 'METAL TRUNKING' method default."""
        raw = {"cable": {"size_mm2": 2.5, "cores": 2}}
        result = normalize_circuit(raw)
        assert result["cable"]["method"] == "METAL TRUNKING"
        assert result["cable"]["cpc_type"] == "PVC"
        assert result["cable"]["size_mm2"] == "2.5"  # Normalized to string

    def test_non_dict_returns_as_is(self):
        """Non-dict input should be returned unchanged."""
        assert normalize_circuit("not a dict") == "not a dict"
        assert normalize_circuit(None) is None

    def test_breaker_type_default_mcb(self):
        """Default breaker_type should be MCB."""
        raw = {"name": "test"}
        result = normalize_circuit(raw)
        assert result["breaker_type"] == "MCB"


class TestParseMcbSpec:
    """Tests for _parse_mcb_spec()."""

    def test_standard_spec(self):
        result = _parse_mcb_spec("10A SP Type B")
        assert result == {
            "breaker_rating": 10,
            "breaker_poles": "SPN",
            "breaker_characteristic": "B",
        }

    def test_dp_isolator(self):
        result = _parse_mcb_spec("20A DP ISOL")
        assert result["breaker_rating"] == 20
        assert result["breaker_poles"] == "DP"
        assert result["breaker_type"] == "ISOLATOR"

    def test_tpn_mcb_with_ka(self):
        result = _parse_mcb_spec("63A TPN MCB 10kA")
        assert result["breaker_rating"] == 63
        assert result["breaker_poles"] == "TPN"
        assert result["breaker_type"] == "MCB"
        assert result["fault_kA"] == 10

    def test_mccb(self):
        result = _parse_mcb_spec("100A TPN MCCB")
        assert result["breaker_type"] == "MCCB"

    def test_spare_returns_empty(self):
        assert _parse_mcb_spec("Spare") == {}
        assert _parse_mcb_spec("") == {}

    def test_type_c(self):
        result = _parse_mcb_spec("32A DP Type C")
        assert result["breaker_characteristic"] == "C"


class TestNormalizeApplicationInfo:
    """Tests for normalize_application_info()."""

    def test_drawing_no_mapping(self):
        """drawing_no should be copied to drawing_number."""
        raw = {"drawing_no": "SLD-001"}
        result = normalize_application_info(raw)
        assert result["drawing_number"] == "SLD-001"

    def test_drawing_number_not_overwritten(self):
        """Existing drawing_number should NOT be overwritten."""
        raw = {"drawing_no": "OLD", "drawing_number": "NEW"}
        result = normalize_application_info(raw)
        assert result["drawing_number"] == "NEW"

    def test_contractor_split_newline(self):
        """electrical_contractor with newlines should split into name + address."""
        raw = {"electrical_contractor": "ABC Pte Ltd\n123 Street\nSingapore 123456"}
        result = normalize_application_info(raw)
        assert result["elec_contractor"] == "ABC Pte Ltd"
        assert "123 Street" in result["elec_contractor_addr"]

    def test_contractor_split_backslash_p(self):
        """electrical_contractor with \\P should split into name + address."""
        raw = {"electrical_contractor": "ABC Pte Ltd\\P123 Street\\PSingapore 123456"}
        result = normalize_application_info(raw)
        assert result["elec_contractor"] == "ABC Pte Ltd"

    def test_main_contractor_alias(self):
        """main_contractor should populate contractor_name."""
        raw = {"main_contractor": "XYZ Corp"}
        result = normalize_application_info(raw)
        assert result["contractor_name"] == "XYZ Corp"

    def test_non_dict_returns_empty(self):
        assert normalize_application_info(None) == {}
        assert normalize_application_info("bad") == {}


# ======================================================================
# Step 2: circuit_types tests
# ======================================================================

class TestClassifyCircuit:
    """Tests for classify_circuit()."""

    def test_explicit_type(self):
        assert classify_circuit({"type": "lighting"}) == "lighting"
        assert classify_circuit({"circuit_type": "aircon"}) == "aircon"

    def test_keyword_lighting(self):
        assert classify_circuit({"name": "LED Panel Light"}) == "lighting"
        assert classify_circuit({"name": "Emergency Exit Light"}) == "lighting"

    def test_keyword_socket(self):
        assert classify_circuit({"name": "13A Socket Outlet"}) == "socket"
        assert classify_circuit({"name": "Power Point"}) == "socket"

    def test_keyword_aircon(self):
        assert classify_circuit({"name": "Air Conditioning Unit"}) == "aircon"
        assert classify_circuit({"name": "A/C Compressor"}) == "aircon"

    def test_keyword_heater(self):
        assert classify_circuit({"name": "Instant Water Heater"}) == "heater"

    def test_keyword_motor(self):
        assert classify_circuit({"name": "Exhaust Fan Motor"}) == "motor"

    def test_keyword_spare(self):
        assert classify_circuit({"name": "Spare"}) == "spare"
        assert classify_circuit({"name": "Future Reserve"}) == "spare"

    def test_fallback_power(self):
        """Unclassified circuits should default to 'power'."""
        assert classify_circuit({"name": "Unknown Device"}) == "power"
        assert classify_circuit({}) == "power"


class TestResolveCircuit:
    """Tests for resolve_circuit()."""

    def test_lighting_defaults(self):
        circuit = {"name": "Lighting"}
        result = resolve_circuit(circuit)
        assert result["breaker_rating"] == 10
        assert result["breaker_characteristic"] == "B"
        assert result["breaker_type"] == "MCB"
        assert result["breaker_poles"] == "SPN"
        # Cable should be auto-filled from rating
        assert result["cable"]["size_mm2"] == "1.5"

    def test_socket_defaults(self):
        circuit = {"name": "Socket Outlet"}
        result = resolve_circuit(circuit)
        assert result["breaker_rating"] == 20
        assert result["cable"]["size_mm2"] == "2.5"

    def test_aircon_residential_isolator(self):
        """Residential aircon should get auto ISOLATOR."""
        circuit = {"name": "Air Conditioning"}
        result = resolve_circuit(circuit, premises_type="residential")
        assert result["breaker_type"] == "ISOLATOR"
        assert result["breaker_poles"] == "DP"

    def test_aircon_commercial_mcb(self):
        """Commercial aircon should stay MCB (default)."""
        circuit = {"name": "Air Conditioning"}
        result = resolve_circuit(circuit, premises_type="commercial")
        assert result["breaker_type"] == "MCB"

    def test_user_value_preserved(self):
        """User-specified values should NOT be overwritten."""
        circuit = {"name": "Lighting", "breaker_rating": 16, "breaker_characteristic": "C"}
        result = resolve_circuit(circuit)
        assert result["breaker_rating"] == 16  # User value, not default 10
        assert result["breaker_characteristic"] == "C"  # User value, not default B

    def test_spare_no_cable(self):
        """Spare circuits should NOT get cable auto-fill."""
        circuit = {"name": "Spare"}
        result = resolve_circuit(circuit)
        assert result.get("cable") is None or not result.get("cable")

    def test_breaker_to_cable_mapping(self):
        """BREAKER_TO_CABLE should map correctly."""
        assert BREAKER_TO_CABLE[10] == 1.5
        assert BREAKER_TO_CABLE[20] == 2.5
        assert BREAKER_TO_CABLE[32] == 6.0
        assert BREAKER_TO_CABLE[63] == 16.0
        assert BREAKER_TO_CABLE[100] == 35.0


# ======================================================================
# Step 3: Sub-circuit poles (TPN→SPN)
# ======================================================================

class TestSubCircuitPoles:
    """Verify sub-circuit poles default to SPN in three-phase DB."""

    def test_three_phase_sub_circuit_spn(self):
        """In a 3-phase TPN DB, sub-circuits should be SPN (single-pole)."""
        from app.sld.layout.engine import compute_layout
        req = {
            "kva": 43,
            "supply_type": "three_phase",
            "supply_source": "landlord",
            "main_breaker": {"type": "MCB", "rating": 63, "poles": "TPN"},
            "sub_circuits": [
                {"name": "Lighting", "breaker": {"rating": 10, "type": "MCB"}},
                {"name": "Socket", "breaker": {"rating": 20, "type": "MCB"}},
            ],
        }
        result = compute_layout(req)
        # Find sub-circuit breaker components (CB_MCB, CB_MCCB, etc.)
        sub_breakers = [c for c in result.components
                        if c.symbol_name.startswith("CB_") and c.circuit_id]
        assert len(sub_breakers) >= 2
        for br in sub_breakers:
            assert br.poles == "SPN", f"Sub-circuit {br.circuit_id} should be SPN, got {br.poles}"


# ======================================================================
# Step 5: Supply label selection
# ======================================================================

class TestSupplyLabel:
    """Test supply label selection logic."""

    def test_default_landlord_label(self):
        """Default landlord supply should use 'FROM LANDLORD RISER'."""
        from app.sld.layout.engine import compute_layout
        req = {
            "kva": 43,
            "supply_source": "landlord",
            "main_breaker": {"type": "MCB", "rating": 63, "poles": "TPN"},
            "sub_circuits": [{"name": "Lighting"}],
        }
        result = compute_layout(req)
        labels = [c.label for c in result.components if c.symbol_name == "LABEL"]
        assert any("FROM LANDLORD RISER" in l for l in labels)

    def test_cable_extension_label(self):
        """Cable extension should use 'FROM POWER SUPPLY ON SITE'."""
        from app.sld.layout.engine import compute_layout
        req = {
            "kva": 43,
            "is_cable_extension": True,
            "main_breaker": {"type": "MCB", "rating": 63, "poles": "TPN"},
            "sub_circuits": [{"name": "Lighting"}],
        }
        result = compute_layout(req)
        labels = [c.label for c in result.components if c.symbol_name == "LABEL"]
        assert any("FROM POWER SUPPLY ON SITE" in l for l in labels)

    def test_supply_label_type_supply(self):
        """supply_label_type='supply' should use 'FROM LANDLORD SUPPLY'."""
        from app.sld.layout.engine import compute_layout
        req = {
            "kva": 43,
            "supply_source": "landlord",
            "supply_label_type": "supply",
            "main_breaker": {"type": "MCB", "rating": 63, "poles": "TPN"},
            "sub_circuits": [{"name": "Lighting"}],
        }
        result = compute_layout(req)
        labels = [c.label for c in result.components if c.symbol_name == "LABEL"]
        assert any("FROM LANDLORD SUPPLY" in l for l in labels)


# ======================================================================
# Step 6: Title block font auto-shrink
# ======================================================================

class TestFitFontSize:
    """Tests for _fit_font_size helper."""

    def test_short_text_keeps_max(self):
        from app.sld.title_block import _fit_font_size
        # Short text should keep max_height
        h = _fit_font_size("ABC Corp", cell_width=60, max_height=3.0)
        assert h == 3.0

    def test_long_text_shrinks(self):
        from app.sld.title_block import _fit_font_size
        # Very long text should shrink
        long_text = "A Very Long Company Name That Exceeds Cell Width Easily"
        h = _fit_font_size(long_text, cell_width=60, max_height=3.0)
        assert h < 3.0

    def test_minimum_font_size(self):
        from app.sld.title_block import _fit_font_size
        # Extremely long text should not go below min_height
        extreme = "X" * 200
        h = _fit_font_size(extreme, cell_width=60, max_height=3.0, min_height=1.8)
        assert h == 1.8

    def test_empty_text_keeps_max(self):
        from app.sld.title_block import _fit_font_size
        h = _fit_font_size("", cell_width=60, max_height=3.0)
        assert h == 3.0


# ======================================================================
# Step 7: kVA validation relaxation
# ======================================================================

class TestKvaRelaxation:
    """kVA values should not block SLD generation."""

    def test_high_kva_no_error(self):
        """Very high kVA should NOT raise an error."""
        from app.sld.layout.engine import compute_layout
        req = {
            "kva": 2000,
            "supply_type": "three_phase",
            "supply_source": "sp_powergrid",
            "main_breaker": {"type": "ACB", "rating": 1600, "poles": "TPN"},
            "sub_circuits": [{"name": "Lighting"}],
        }
        result = compute_layout(req)
        assert result is not None

    def test_diversity_factor_kva(self):
        """kVA with diversity factor (e.g., 69.282 for 63A TPN) should work."""
        from app.sld.layout.engine import compute_layout
        req = {
            "kva": 69.282,
            "supply_type": "three_phase",
            "supply_source": "landlord",
            "main_breaker": {"type": "MCB", "rating": 63, "poles": "TPN"},
            "sub_circuits": [{"name": "Lighting"}, {"name": "Socket"}],
        }
        result = compute_layout(req)
        assert result is not None
        assert len(result.components) > 0


# ======================================================================
# Integration: End-to-end normalizer → resolver → layout
# ======================================================================

class TestEndToEndNormalization:
    """Test that the full pipeline works: raw input → normalized → resolved → layout."""

    def test_nested_breaker_produces_correct_layout(self):
        """Nested breaker input should produce correct MCB ratings in layout."""
        from app.sld.layout.engine import compute_layout
        req = {
            "kva": 43,
            "supply_source": "landlord",
            "main_breaker": {"type": "MCB", "rating": 63, "poles": "TPN"},
            "sub_circuits": [
                {
                    "name": "Lighting Circuit 1",
                    "breaker": {"rating": 10, "type": "MCB", "characteristic": "B"},
                },
                {
                    "name": "Socket Outlet",
                    "breaker": {"rating": 20, "type": "MCB", "characteristic": "B"},
                },
            ],
        }
        result = compute_layout(req)
        # Find sub-circuit breakers (CB_MCB, CB_MCCB, etc.)
        sub_breakers = [c for c in result.components
                        if c.symbol_name.startswith("CB_") and c.circuit_id]
        assert len(sub_breakers) == 2
        ratings = sorted(int(c.rating.replace("A", "")) for c in sub_breakers if c.rating)
        assert 10 in ratings  # Lighting should be 10A
        assert 20 in ratings  # Socket should be 20A

    def test_mcb_text_spec_produces_correct_layout(self):
        """MCB text spec input should parse and produce correct layout."""
        from app.sld.layout.engine import compute_layout
        req = {
            "kva": 43,
            "supply_source": "landlord",
            "main_breaker": {"type": "MCB", "rating": 63, "poles": "TPN"},
            "sub_circuits": [
                {"name": "Lighting", "mcb": "10A SP Type B"},
                {"name": "Socket", "mcb": "20A SP Type B"},
            ],
        }
        result = compute_layout(req)
        sub_breakers = [c for c in result.components
                        if c.symbol_name.startswith("CB_") and c.circuit_id]
        assert len(sub_breakers) == 2
