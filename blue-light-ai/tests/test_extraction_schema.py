"""
Tests for app.sld.extraction_schema — SLD data extraction, validation, and normalization.
"""

import json

import pytest

from app.sld.extraction_schema import (
    BreakerSpec,
    BusbarSpec,
    CableSpec,
    ClientInfo,
    ElcbSpec,
    IncomingData,
    MeteringSpec,
    OutgoingCircuit,
    SldExtractedData,
    format_extraction_result,
    normalize_to_generation_format,
    parse_and_validate,
    parse_extracted_json,
)


# ─────────────────────────────────────────────────────────────────────
# Sample Data: Based on SLD Drawing Information.pdf (100A MSB, 69.282 kVA)
# ─────────────────────────────────────────────────────────────────────

SAMPLE_FULL_SLD = {
    "incoming": {
        "kva": 69.28,
        "phase": "three_phase",
        "voltage": 400,
        "main_breaker": {
            "type": "MCCB",
            "rating_a": 100,
            "poles": "TPN",
            "ka_rating": 10,
            "characteristic": "B",
        },
        "cable": {
            "size_mm2": "50",
            "earth_mm2": "25",
            "type": "XLPE",
            "cores": "4 X 1 CORE",
            "description": "4 x 1C 50mm XLPE CABLE + 25sqmm CPC PVC CABLE",
        },
        "elcb": {
            "type": "ELCB",
            "rating_a": 100,
            "poles": 4,
            "sensitivity_ma": 30,
        },
        "busbar": {
            "rating_a": 100,
            "type": "COMB",
        },
        "metering": {
            "type": "ct_meter",
            "ct_ratio": "100/5A",
            "isolator_rating_a": 125,
            "has_indicator_lights": True,
            "has_elr": True,
            "has_shunt_trip": True,
        },
        "earth_protection": True,
    },
    "outgoing_circuits": [
        {
            "id": "L1S1",
            "description": "LED PANEL LIGHT",
            "breaker": {
                "type": "MCB",
                "rating_a": 10,
                "poles": "SPN",
                "ka_rating": 6,
                "characteristic": "B",
            },
            "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING",
            "qty": 2,
            "load_type": "lighting",
        },
        {
            "id": "L2P1",
            "description": "13A SOCKET OUTLET",
            "breaker": {
                "type": "MCB",
                "rating_a": 20,
                "poles": "SPN",
                "ka_rating": 6,
                "characteristic": "B",
            },
            "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING",
            "qty": 3,
            "load_type": "power",
        },
        {
            "id": "SP1",
            "description": "SPARE",
            "breaker": {
                "type": "MCB",
                "rating_a": 20,
                "poles": "SPN",
                "ka_rating": 6,
                "characteristic": "B",
            },
            "cable": None,
            "qty": None,
            "load_type": "spare",
        },
    ],
    "client_info": {
        "name": "NECESSARY PROVISIONS PTE. LTD.",
        "address": "200 PANDAN LOOP #03-01 S1288388",
        "lew_name": "Ryan, Nyap Junn Yean",
        "lew_licence": "8/33613",
        "contractor": "I2R M&E PTE. LTD.",
        "main_contractor": "UY CONTRACTOR",
    },
}


# ─────────────────────────────────────────────────────────────────────
# Test: Pydantic Model Parsing
# ─────────────────────────────────────────────────────────────────────


class TestPydanticParsing:
    """Test Pydantic model validation and parsing."""

    def test_parse_full_sld(self):
        """Complete SLD JSON parses into valid SldExtractedData."""
        data = parse_extracted_json(SAMPLE_FULL_SLD)
        assert isinstance(data, SldExtractedData)
        assert data.incoming is not None
        assert data.incoming.kva == 69.28
        assert data.incoming.phase == "three_phase"
        assert data.incoming.voltage == 400
        assert len(data.outgoing_circuits) == 3
        assert data.client_info is not None

    def test_parse_from_json_string(self):
        """JSON string input parses correctly."""
        json_str = json.dumps(SAMPLE_FULL_SLD)
        data = parse_extracted_json(json_str)
        assert data.incoming.kva == 69.28
        assert len(data.outgoing_circuits) == 3

    def test_parse_minimal_data(self):
        """Minimal JSON with only kVA and phase."""
        minimal = {
            "incoming": {
                "kva": 45,
                "phase": "three_phase",
            },
        }
        data = parse_extracted_json(minimal)
        assert data.incoming.kva == 45
        assert data.incoming.phase == "three_phase"
        assert data.incoming.main_breaker is None
        assert len(data.outgoing_circuits) == 0

    def test_parse_null_fields(self):
        """Null fields are handled correctly."""
        with_nulls = {
            "incoming": {
                "kva": 100,
                "phase": "three_phase",
                "voltage": None,
                "main_breaker": {
                    "type": "MCCB",
                    "rating_a": None,
                    "poles": None,
                    "ka_rating": None,
                    "characteristic": None,
                },
            },
            "outgoing_circuits": [],
            "client_info": None,
        }
        data = parse_extracted_json(with_nulls)
        assert data.incoming.voltage is None
        assert data.incoming.main_breaker.type == "MCCB"
        assert data.incoming.main_breaker.rating_a is None
        assert data.client_info is None

    def test_parse_empty_dict(self):
        """Empty dict produces empty SldExtractedData."""
        data = parse_extracted_json({})
        assert data.incoming is None
        assert len(data.outgoing_circuits) == 0
        assert data.client_info is None

    def test_breaker_spec_model(self):
        """BreakerSpec model validates correctly."""
        spec = BreakerSpec(type="MCB", rating_a=63, poles="DP", ka_rating=10, characteristic="B")
        assert spec.type == "MCB"
        assert spec.rating_a == 63

    def test_cable_spec_model(self):
        """CableSpec model validates correctly."""
        spec = CableSpec(
            size_mm2="50",
            earth_mm2="25",
            type="XLPE",
            cores="4 X 1 CORE",
            description="4 x 1C 50mm XLPE CABLE",
        )
        assert spec.size_mm2 == "50"
        assert spec.type == "XLPE"

    def test_client_info_model(self):
        """ClientInfo model validates correctly."""
        info = ClientInfo(
            name="TEST CO.",
            address="123 TEST ST",
            lew_name="John Doe",
        )
        assert info.name == "TEST CO."
        assert info.lew_licence is None


# ─────────────────────────────────────────────────────────────────────
# Test: Parse and Validate (Integration with sld_spec.py)
# ─────────────────────────────────────────────────────────────────────


class TestParseAndValidate:
    """Test the parse_and_validate pipeline."""

    def test_full_sld_validates(self):
        """Full SLD data passes validation."""
        result = parse_and_validate(SAMPLE_FULL_SLD)
        assert "extracted" in result
        assert "validation" in result
        assert "corrected" in result
        assert "generation_ready" in result

    def test_kva_based_auto_correction(self):
        """kVA value triggers auto-correction of missing fields."""
        minimal = {
            "incoming": {
                "kva": 69.28,
            },
        }
        result = parse_and_validate(minimal)
        corrections = result["validation"]["corrections"]

        # Should auto-determine supply_type, breaker_type, etc.
        assert "supply_type" in corrections or result["corrected"].get("supply_type")

    def test_single_phase_kva(self):
        """Small kVA correctly maps to single-phase."""
        single_phase = {
            "incoming": {
                "kva": 7.0,
                "phase": "single_phase",
            },
            "outgoing_circuits": [
                {
                    "description": "Lighting",
                    "breaker": {"type": "MCB", "rating_a": 10},
                },
            ],
        }
        result = parse_and_validate(single_phase)
        corrected = result["corrected"]
        # 7.0 kVA → 32A single-phase
        assert corrected.get("supply_type") == "single_phase" or \
               result["extracted"].get("incoming", {}).get("phase") == "single_phase"

    def test_three_phase_kva(self):
        """Large kVA correctly maps to three-phase."""
        three_phase = {
            "incoming": {
                "kva": 200,
                "phase": "three_phase",
            },
        }
        result = parse_and_validate(three_phase)
        corrected = result["corrected"]
        # 200 kVA → 300A three-phase
        assert corrected.get("breaker_rating") == 300 or \
               corrected.get("supply_type") == "three_phase"

    def test_three_phase_to_single_phase_db_warning(self):
        """3-Phase to Single Phase DB triggers warning."""
        non_standard = {
            "incoming": {
                "kva": 50,
                "phase": "three_phase",
                "main_breaker": {
                    "type": "MCB",
                    "rating_a": 63,
                    "poles": "DP",
                },
            },
        }
        result = parse_and_validate(non_standard)
        warnings = result["validation"]["warnings"]
        # Should detect 3-phase with DP poles as non-standard
        has_3phase_warning = any(
            "NON-STANDARD" in w or "3-Phase" in w or "non-standard" in w.lower()
            for w in warnings
        )
        assert has_3phase_warning, f"Expected 3-Phase warning, got warnings: {warnings}"

    def test_undersized_breaker_warns_only(self):
        """Undersized standard breaker → warning only (no auto-correction).

        SG team decision (2026-03-08): user/LEW responsible for kVA.
        """
        undersized = {
            "incoming": {
                "kva": 200,
                "main_breaker": {
                    "type": "MCCB",
                    "rating_a": 150,
                },
            },
        }
        result = parse_and_validate(undersized)
        corrections = result["validation"].get("corrections", {})
        warnings = result["validation"].get("warnings", [])
        # 200 kVA → spec says 300A, but 150A is standard → warn only
        assert "breaker_rating" not in corrections
        assert any("undersized" in w.lower() or "may be undersized" in w.lower() for w in warnings)

    def test_missing_kva_and_breaker_error(self):
        """Missing both kVA and breaker_rating → error."""
        empty = {
            "incoming": {},
        }
        result = parse_and_validate(empty)
        errors = result["validation"]["errors"]
        assert len(errors) > 0
        has_missing = any("kva" in e.lower() or "breaker_rating" in e.lower() for e in errors)
        assert has_missing

    def test_sub_circuit_validation(self):
        """Sub-circuit breaker ratings are validated."""
        with_circuits = {
            "incoming": {
                "kva": 69.28,
                "main_breaker": {
                    "type": "MCCB",
                    "rating_a": 100,
                },
            },
            "outgoing_circuits": [
                {
                    "description": "Lighting",
                    "breaker": {"type": "MCB", "rating_a": 10},
                },
                {
                    "description": "Power",
                    "breaker": {"type": "MCB", "rating_a": 20},
                },
            ],
        }
        result = parse_and_validate(with_circuits)
        # Should not have errors for valid sub-circuits
        gen_ready = result["generation_ready"]
        assert len(gen_ready.get("sub_circuits", [])) == 2

    def test_validation_result_structure(self):
        """Validation result has expected structure."""
        result = parse_and_validate(SAMPLE_FULL_SLD)
        validation = result["validation"]
        assert "valid" in validation
        assert "errors" in validation
        assert "warnings" in validation
        assert "corrections" in validation
        assert isinstance(validation["errors"], list)
        assert isinstance(validation["warnings"], list)
        assert isinstance(validation["corrections"], dict)


# ─────────────────────────────────────────────────────────────────────
# Test: Normalize to Generation Format
# ─────────────────────────────────────────────────────────────────────


class TestNormalizeToGenerationFormat:
    """Test conversion to generate_sld() requirements format."""

    def test_full_normalization(self):
        """Full SLD data normalizes to generation format."""
        extracted = parse_extracted_json(SAMPLE_FULL_SLD)
        gen = normalize_to_generation_format(extracted)

        assert gen["supply_type"] == "three_phase"
        assert gen["kva"] == 69.28
        assert gen["main_breaker"]["type"] == "MCCB"
        assert gen["main_breaker"]["rating"] == 100
        assert gen["main_breaker"]["poles"] == "TPN"
        assert gen["main_breaker"]["fault_kA"] == 10
        assert gen["busbar_rating"] == 100
        assert gen["elcb"]["rating"] == 100
        assert gen["elcb"]["sensitivity_ma"] == 30
        assert gen["elcb"]["type"] == "ELCB"
        assert gen["earth_protection"] is True
        assert gen["metering"] == "ct_meter"
        assert gen["incoming_cable"] == "4 x 1C 50mm XLPE CABLE + 25sqmm CPC PVC CABLE"
        assert len(gen["sub_circuits"]) == 3

    def test_sub_circuit_format(self):
        """Sub-circuits convert to expected format with all fields."""
        extracted = parse_extracted_json(SAMPLE_FULL_SLD)
        gen = normalize_to_generation_format(extracted)

        sc0 = gen["sub_circuits"][0]
        assert "LED PANEL LIGHT" in sc0["name"]
        assert sc0["breaker_type"] == "MCB"
        assert sc0["breaker_rating"] == 10
        assert sc0["breaker_characteristic"] == "B"
        assert sc0["fault_kA"] == 6
        assert "1.5sqmm" in sc0["cable"]

    def test_spare_circuit(self):
        """Spare circuit normalizes correctly."""
        extracted = parse_extracted_json(SAMPLE_FULL_SLD)
        gen = normalize_to_generation_format(extracted)

        spare = gen["sub_circuits"][2]
        assert "SPARE" in spare["name"]
        assert spare["breaker_type"] == "MCB"
        assert spare["breaker_rating"] == 20

    def test_qty_in_name(self):
        """Circuit quantity is included in the name."""
        extracted = parse_extracted_json(SAMPLE_FULL_SLD)
        gen = normalize_to_generation_format(extracted)

        # "LED PANEL LIGHT" has qty=2 → name should include "(2 nos)"
        sc0 = gen["sub_circuits"][0]
        assert "(2 nos)" in sc0["name"]

    def test_minimal_normalization(self):
        """Minimal data normalizes with defaults."""
        minimal = {
            "incoming": {
                "kva": 45,
                "phase": "three_phase",
            },
            "outgoing_circuits": [
                {
                    "description": "Lighting",
                    "breaker": {"type": "MCB", "rating_a": 10},
                },
            ],
        }
        extracted = parse_extracted_json(minimal)
        gen = normalize_to_generation_format(extracted)

        assert gen["supply_type"] == "three_phase"
        assert gen["kva"] == 45
        assert gen["earth_protection"] is True  # Default
        assert len(gen["sub_circuits"]) == 1

    def test_normalization_with_corrections(self):
        """Corrected values are used in normalization."""
        extracted = parse_extracted_json({
            "incoming": {
                "kva": 69.28,
            },
        })
        corrected = {
            "supply_type": "three_phase",
            "breaker_type": "MCCB",
            "breaker_rating": 100,
            "breaker_poles": "TPN",
            "breaker_ka": 35,
            "cable_size": "35 + 16mmsq E",
            "metering": "ct_meter",
        }
        gen = normalize_to_generation_format(extracted, corrected)

        assert gen["supply_type"] == "three_phase"
        assert gen["main_breaker"]["type"] == "MCCB"
        assert gen["main_breaker"]["rating"] == 100
        assert gen["main_breaker"]["poles"] == "TPN"
        assert gen["main_breaker"]["fault_kA"] == 35
        assert gen["metering"] == "ct_meter"

    def test_elcb_rccb_normalization(self):
        """RCCB type is preserved in normalization."""
        rccb_sld = {
            "incoming": {
                "kva": 14.49,
                "phase": "single_phase",
                "main_breaker": {"type": "MCB", "rating_a": 63, "poles": "DP"},
                "elcb": {"type": "RCCB", "rating_a": 63, "poles": 2, "sensitivity_ma": 30},
            },
        }
        extracted = parse_extracted_json(rccb_sld)
        gen = normalize_to_generation_format(extracted)

        assert gen["elcb"]["type"] == "RCCB"
        assert gen["elcb"]["rating"] == 63
        assert gen["elcb"]["poles"] == 2
        assert gen["elcb"]["sensitivity_ma"] == 30

    def test_busbar_default_minimum(self):
        """Busbar rating defaults to max(main_breaker, 100)."""
        small_sld = {
            "incoming": {
                "kva": 7.36,
                "phase": "single_phase",
                "main_breaker": {"type": "MCB", "rating_a": 32, "poles": "DP"},
            },
        }
        extracted = parse_extracted_json(small_sld)
        gen = normalize_to_generation_format(extracted)

        # 32A main breaker → busbar should be max(32, 100) = 100
        assert gen["busbar_rating"] == 100

    def test_no_breaker_builds_from_corrected(self):
        """When no breaker is specified, build from corrected values."""
        extracted = parse_extracted_json({"incoming": {"kva": 69.28}})
        corrected = {
            "breaker_type": "MCCB",
            "breaker_rating": 100,
            "breaker_poles": "TPN",
            "breaker_ka": 35,
        }
        gen = normalize_to_generation_format(extracted, corrected)

        assert gen["main_breaker"]["type"] == "MCCB"
        assert gen["main_breaker"]["rating"] == 100


# ─────────────────────────────────────────────────────────────────────
# Test: Format Extraction Result
# ─────────────────────────────────────────────────────────────────────


class TestFormatExtractionResult:
    """Test human-readable formatting of extraction results."""

    def test_format_full_result(self):
        """Full result formats to readable summary."""
        result = parse_and_validate(SAMPLE_FULL_SLD)
        formatted = format_extraction_result(result)

        assert "Extracted Incoming Supply" in formatted
        assert "69.28" in formatted
        assert "Three-Phase" in formatted
        assert "MCCB" in formatted
        assert "100A" in formatted
        assert "Outgoing Circuits" in formatted
        assert "LED PANEL LIGHT" in formatted
        assert "13A SOCKET OUTLET" in formatted

    def test_format_with_errors(self):
        """Errors appear in formatted output."""
        # Missing both kva and breaker_rating → validation error
        result = parse_and_validate({
            "incoming": {},
        })
        formatted = format_extraction_result(result)
        assert "❌" in formatted or "Error" in formatted

    def test_format_with_corrections(self):
        """Auto-corrections appear in formatted output."""
        result = parse_and_validate({
            "incoming": {
                "kva": 69.28,
            },
        })
        formatted = format_extraction_result(result)
        assert "Auto-Corrections" in formatted or "✅" in formatted

    def test_format_empty_result(self):
        """Empty result formats without errors."""
        result = {
            "extracted": {},
            "validation": {"valid": True, "errors": [], "warnings": [], "corrections": {}},
            "corrected": {},
            "generation_ready": {},
        }
        formatted = format_extraction_result(result)
        assert isinstance(formatted, str)


# ─────────────────────────────────────────────────────────────────────
# Test: E2E — SLD Drawing Information.pdf Sample Data
# ─────────────────────────────────────────────────────────────────────


class TestE2ESampleData:
    """
    End-to-end tests using real SLD sample data from SLD Drawing Information.pdf.
    100A MSB, 69.282 kVA at 400V 3-phase.
    """

    def test_e2e_full_pipeline(self):
        """Complete pipeline: parse → validate → correct → normalize.

        With INCOMING_SPEC_3PHASE, 69.28 kVA + three_phase maps to 100A TPN MCB.
        The sample data specifies 100A MCCB TPN, so:
        - breaker_type: MCCB is a valid type → warn only, NOT corrected (user value retained)
        - breaker_rating 100A is correct (no correction needed)
        - poles TPN is correct (no correction needed)
        """
        result = parse_and_validate(SAMPLE_FULL_SLD)

        # Extraction should succeed
        extracted = result["extracted"]
        assert extracted["incoming"]["kva"] == 69.28
        assert extracted["incoming"]["phase"] == "three_phase"
        assert len(extracted["outgoing_circuits"]) == 3

        # MCCB is a valid type → should NOT be auto-corrected (warn only)
        corrections = result["validation"]["corrections"]
        assert "breaker_type" not in corrections, (
            "MCCB is a valid type — should not be auto-corrected"
        )
        # breaker_rating 100A matches spec — no rating correction needed
        assert "breaker_rating" not in corrections

        # Should have a warning about type mismatch
        warnings = result["validation"]["warnings"]
        assert any("differs from standard" in w for w in warnings)

        # Generation-ready format should retain user's MCCB
        gen = result["generation_ready"]
        assert gen["supply_type"] == "three_phase"
        assert gen["kva"] == 69.28
        assert gen["main_breaker"]["type"] == "MCCB"  # User value retained
        assert gen["main_breaker"]["rating"] == 100
        assert len(gen["sub_circuits"]) == 3
        assert gen["elcb"]["rating"] == 100
        assert gen["earth_protection"] is True

    def test_e2e_client_info_preserved(self):
        """Client info is extracted and available."""
        result = parse_and_validate(SAMPLE_FULL_SLD)
        client = result["extracted"].get("client_info", {})
        assert client.get("name") == "NECESSARY PROVISIONS PTE. LTD."
        assert "PANDAN LOOP" in client.get("address", "")
        assert client.get("lew_name") == "Ryan, Nyap Junn Yean"
        assert client.get("lew_licence") == "8/33613"

    def test_e2e_metering_details(self):
        """Metering section details are extracted."""
        result = parse_and_validate(SAMPLE_FULL_SLD)
        metering = result["extracted"]["incoming"].get("metering", {})
        assert metering.get("type") == "ct_meter"
        assert metering.get("ct_ratio") == "100/5A"
        assert metering.get("isolator_rating_a") == 125
        assert metering.get("has_indicator_lights") is True
        assert metering.get("has_elr") is True
        assert metering.get("has_shunt_trip") is True

    def test_e2e_single_phase_residential(self):
        """Single-phase residential SLD pipeline."""
        residential = {
            "incoming": {
                "kva": 14.49,
                "phase": "single_phase",
                "voltage": 230,
                "main_breaker": {
                    "type": "MCB",
                    "rating_a": 63,
                    "poles": "DP",
                    "ka_rating": 10,
                    "characteristic": "B",
                },
                "cable": {
                    "size_mm2": "16",
                    "earth_mm2": "16",
                    "type": "XLPE/PVC",
                    "cores": "4 X 1 CORE",
                },
                "elcb": {
                    "type": "RCCB",
                    "rating_a": 63,
                    "poles": 2,
                    "sensitivity_ma": 30,
                },
                "busbar": {"rating_a": 100, "type": "COMB"},
                "earth_protection": True,
            },
            "outgoing_circuits": [
                {
                    "id": "S1",
                    "description": "Lighting",
                    "breaker": {"type": "MCB", "rating_a": 10, "poles": "SPN",
                                "ka_rating": 6, "characteristic": "B"},
                    "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING",
                    "load_type": "lighting",
                },
                {
                    "id": "P1",
                    "description": "Socket Outlet",
                    "breaker": {"type": "MCB", "rating_a": 20, "poles": "SPN",
                                "ka_rating": 6, "characteristic": "B"},
                    "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING",
                    "load_type": "power",
                },
                {
                    "id": "SP1",
                    "description": "Spare",
                    "breaker": {"type": "MCB", "rating_a": 20, "poles": "SPN",
                                "ka_rating": 6, "characteristic": "B"},
                    "load_type": "spare",
                },
            ],
        }
        result = parse_and_validate(residential)
        gen = result["generation_ready"]

        assert gen["supply_type"] == "single_phase"
        assert gen["main_breaker"]["type"] == "MCB"
        assert gen["main_breaker"]["rating"] == 63
        assert gen["main_breaker"]["poles"] == "DP"
        assert gen["elcb"]["type"] == "RCCB"
        assert gen["elcb"]["poles"] == 2
        assert len(gen["sub_circuits"]) == 3

    def test_e2e_kva_only_auto_spec(self):
        """kVA-only input produces complete generation-ready output."""
        result = parse_and_validate({"incoming": {"kva": 100}})
        gen = result["generation_ready"]

        # 100 kVA → 150A TPN MCCB 35kA three-phase
        assert gen.get("supply_type") == "three_phase"
        assert gen.get("main_breaker", {}).get("type") == "MCCB"
        assert gen.get("main_breaker", {}).get("rating") == 150
