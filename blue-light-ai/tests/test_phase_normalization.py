"""Tests for R/Y/B → L1/L2/L3 phase normalization."""

import pytest

from app.sld.circuit_normalizer import normalize_circuit, normalize_phase_name


class TestNormalizePhaseNameUnit:
    """Unit tests for normalize_phase_name()."""

    @pytest.mark.parametrize("input_phase,expected", [
        ("R", "L1"), ("Y", "L2"), ("B", "L3"),
        ("RED", "L1"), ("YELLOW", "L2"), ("BLUE", "L3"),
        ("YEL", "L2"), ("BLU", "L3"),
        ("L1", "L1"), ("L2", "L2"), ("L3", "L3"),
        # Case insensitive
        ("r", "L1"), ("y", "L2"), ("b", "L3"),
        ("red", "L1"), ("yellow", "L2"), ("blue", "L3"),
        ("l1", "L1"), ("l2", "L2"), ("l3", "L3"),
        # Whitespace
        (" R ", "L1"), ("  L2  ", "L2"),
    ])
    def test_known_aliases(self, input_phase, expected):
        assert normalize_phase_name(input_phase) == expected

    def test_empty_string(self):
        assert normalize_phase_name("") == ""

    def test_unknown_value_passthrough(self):
        assert normalize_phase_name("N") == "N"
        assert normalize_phase_name("unknown") == "unknown"
        assert normalize_phase_name("PE") == "PE"


class TestNormalizeCircuitPhase:
    """Tests for phase normalization within normalize_circuit()."""

    def test_circuit_phase_r_to_l1(self):
        c = normalize_circuit({"name": "Light 1", "phase": "R", "breaker_rating": 10})
        assert c["phase"] == "L1"

    def test_circuit_phase_y_to_l2(self):
        c = normalize_circuit({"name": "Power 1", "phase": "Y", "breaker_rating": 20})
        assert c["phase"] == "L2"

    def test_circuit_no_phase_unchanged(self):
        c = normalize_circuit({"name": "Spare", "breaker_rating": 10})
        assert "phase" not in c or c.get("phase") is None

    def test_circuit_l1_stays_l1(self):
        c = normalize_circuit({"name": "Light 1", "phase": "L1", "breaker_rating": 10})
        assert c["phase"] == "L1"
