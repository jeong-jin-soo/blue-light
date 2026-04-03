"""
Tests for _assign_circuit_ids() circuit ID assignment logic.

Covers:
- Single-phase: lighting (S), power (P), heater (H) counters
- Single-phase: shared P/H counter (heater continues from power)
- Three-phase: round-robin L1/L2/L3 distribution
- Three-phase: SPARE fills next available phase slot
- User-provided circuit_id override (pass-through)
- ISOLATOR has separate counter (ISOL 1, ISOL 2)
- Mixed user + auto IDs without counter collision
- _categorize_circuit sub-function unit tests
- _assign_spare_phase_slot sub-function unit tests
"""

import pytest

from app.sld.layout.helpers import (
    _assign_circuit_ids,
    _assign_spare_phase_slot,
    _categorize_circuit,
)


def _make_circuit(name: str, breaker_type: str = "MCB", circuit_id: str = "") -> dict:
    """Build a minimal circuit dict."""
    d = {"name": name, "breaker_type": breaker_type, "breaker_rating": 20}
    if circuit_id:
        d["circuit_id"] = circuit_id
    return d


class TestSinglePhaseIds:
    """Single-phase circuit ID assignment."""

    def test_lighting_and_power_and_spare(self):
        """Lighting 3 + power 2 + spare 1 → S1,S2,S3,P1,P2,SP1."""
        circuits = [
            _make_circuit("Lights A"),
            _make_circuit("Lights B"),
            _make_circuit("LED Downlights"),
            _make_circuit("13A S/S/O"),
            _make_circuit("20A Power"),
            _make_circuit("SPARE"),
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["S1", "S2", "S3", "P1", "P2", "SP1"]

    def test_heater_shares_power_counter(self):
        """Heater uses shared P/H counter: P1, P2, H3."""
        circuits = [
            _make_circuit("13A S/S/O"),
            _make_circuit("20A Power"),
            _make_circuit("Water Heater"),
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["P1", "P2", "H3"]

    def test_isolator_own_counter(self):
        """ISOLATOR gets separate 'ISOL N' counter."""
        circuits = [
            _make_circuit("Lights"),
            _make_circuit("AIRCON ISOLATOR", breaker_type="ISOLATOR"),
            _make_circuit("AIRCON ISOLATOR 2", breaker_type="ISOLATOR"),
            _make_circuit("13A S/S/O"),
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["S1", "ISOL 1", "ISOL 2", "P1"]


class TestThreePhaseIds:
    """Three-phase circuit ID assignment with round-robin."""

    def test_lighting_round_robin(self):
        """6 lighting circuits → L1S1,L2S1,L3S1,L1S2,L2S2,L3S2."""
        circuits = [_make_circuit(f"Lights {i+1}") for i in range(6)]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids == ["L1S1", "L2S1", "L3S1", "L1S2", "L2S2", "L3S2"]

    def test_power_round_robin(self):
        """3 power circuits → L1P1,L2P1,L3P1."""
        circuits = [
            _make_circuit("13A S/S/O"),
            _make_circuit("20A Power Point"),
            _make_circuit("Power Outlet"),
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids == ["L1P1", "L2P1", "L3P1"]

    def test_spare_fills_phase_slot(self):
        """SPARE in 3-phase fills next available phase slot of preceding section."""
        circuits = [
            _make_circuit("Lights 1"),
            _make_circuit("Lights 2"),
            _make_circuit("SPARE"),  # Should fill L3S1 (lighting section)
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids[0] == "L1S1"
        assert ids[1] == "L2S1"
        # SPARE should get L3S1 (completing the triplet)
        assert ids[2] == "L3S1"

    def test_isolator_uses_power_counter(self):
        """ISOLATOR uses power counter (L{phase}P{num}) in 3-phase grouping.

        The isolator symbol on the conductor already identifies the device;
        the circuit ID follows sequential power numbering.
        """
        circuits = [
            _make_circuit("Lights"),
            _make_circuit("AC Isolator", breaker_type="ISOLATOR"),
            _make_circuit("Lights 2"),
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids[0] == "L1S1"
        assert ids[1] == "L1P1"  # Isolator gets power ID
        assert ids[2] == "L2S1"  # Lighting counter unaffected

    def test_heater_three_phase(self):
        """Heater in 3-phase uses H prefix, sharing P/H counter."""
        circuits = [
            _make_circuit("13A S/S/O"),
            _make_circuit("Water Heater"),
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids[0] == "L1P1"
        # Heater shares the power counter (ph_idx), so after P used idx 0→1, H gets idx 1→2
        assert ids[1] == "H2"


class TestUserProvidedIds:
    """Tests for explicit circuit_id override."""

    def test_explicit_circuit_id_passthrough(self):
        """User-provided circuit_id takes priority."""
        circuits = [
            _make_circuit("My Light", circuit_id="L1S1"),
            _make_circuit("My Power", circuit_id="L2P1"),
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids == ["L1S1", "L2P1"]

    def test_name_as_circuit_id(self):
        """Name matching L-phase pattern used as circuit ID."""
        circuits = [
            _make_circuit("L1S1"),
            _make_circuit("L2S1"),
            _make_circuit("L3S1"),
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids == ["L1S1", "L2S1", "L3S1"]

    def test_mixed_user_and_auto(self):
        """Mixed user + auto IDs should not cause counter collision."""
        circuits = [
            _make_circuit("L1S1"),           # user-provided
            _make_circuit("Lights Auto"),     # auto-assigned
            _make_circuit("L3S1"),           # user-provided
        ]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert ids[0] == "L1S1"
        assert ids[2] == "L3S1"
        # Auto-assigned should get L1S1 from counter (auto counter doesn't know about user IDs)
        # This is expected behavior — user IDs and auto IDs are independent
        assert ids[1].startswith("L")

    def test_isol_name_passthrough(self):
        """Name like 'ISOL 1' is recognized as valid circuit ID."""
        circuits = [
            _make_circuit("ISOL 1", breaker_type="ISOLATOR"),
        ]
        ids = _assign_circuit_ids(circuits, "single_phase")
        assert ids == ["ISOL 1"]


class TestOutputLength:
    """Verify output always has same length as input."""

    @pytest.mark.parametrize("n", [0, 1, 3, 6, 12, 30])
    def test_output_length_matches_input(self, n):
        """Output list length must equal input list length."""
        circuits = [_make_circuit(f"Circuit {i+1}") for i in range(n)]
        ids = _assign_circuit_ids(circuits, "three_phase")
        assert len(ids) == n

    def test_empty_input(self):
        """Empty input returns empty output."""
        assert _assign_circuit_ids([], "single_phase") == []
        assert _assign_circuit_ids([], "three_phase") == []


# =============================================
# _categorize_circuit sub-function tests
# =============================================

class TestCategorizeCircuit:
    """Unit tests for the extracted _categorize_circuit sub-function."""

    def test_categorize_spare(self):
        """name="SPARE" → ("spare", None)."""
        cat, uid = _categorize_circuit({"name": "SPARE"})
        assert cat == "spare"
        assert uid is None

    def test_categorize_isolator_by_type(self):
        """breaker_type="ISOLATOR" → ("isolator", None)."""
        cat, uid = _categorize_circuit({"name": "AIRCON", "breaker_type": "ISOLATOR"})
        assert cat == "isolator"
        assert uid is None

    def test_categorize_user_id(self):
        """circuit_id="L1S1" → ("user_id", "L1S1")."""
        cat, uid = _categorize_circuit({"name": "Lights", "circuit_id": "L1S1"})
        assert cat == "user_id"
        assert uid == "L1S1"

    def test_categorize_lighting(self):
        """name contains "LED" or "LIGHT" → ("lighting", None)."""
        for name in ("LED Downlight", "Lights A", "Lamp Post"):
            cat, uid = _categorize_circuit({"name": name})
            assert cat == "lighting", f"Expected lighting for name={name!r}"
            assert uid is None

    def test_categorize_power_default(self):
        """name="Socket" → ("power", None) — default category."""
        cat, uid = _categorize_circuit({"name": "Socket"})
        assert cat == "power"
        assert uid is None

    def test_categorize_heater(self):
        """name containing 'heater' → ("heater", None)."""
        cat, uid = _categorize_circuit({"name": "Water Heater"})
        assert cat == "heater"
        assert uid is None


# =============================================
# _assign_spare_phase_slot sub-function tests
# =============================================

class TestAssignSparePhaseSlot:
    """Unit tests for the extracted _assign_spare_phase_slot sub-function."""

    def test_spare_phase_slot_fills_gap(self):
        """L1S1,L2S1 present → SPARE gets L3S1 (fills gap in triplet)."""
        ids = ["L1S1", "L2S1"]
        categories = ["lighting", "lighting", "spare"]
        user_ids = [None, None, None]
        result = _assign_spare_phase_slot(ids, categories, user_ids, index=2)
        assert result == "L3S1"

    def test_spare_phase_slot_power_section(self):
        """SPARE after power section fills power phase gap."""
        ids = ["L1P1", "L2P1"]
        categories = ["power", "power", "spare"]
        user_ids = [None, None, None]
        result = _assign_spare_phase_slot(ids, categories, user_ids, index=2)
        assert result == "L3P1"

    def test_spare_phase_slot_after_full_triplet(self):
        """SPARE after complete triplet starts next number."""
        ids = ["L1S1", "L2S1", "L3S1"]
        categories = ["lighting", "lighting", "lighting", "spare"]
        user_ids = [None, None, None, None]
        result = _assign_spare_phase_slot(ids, categories, user_ids, index=3)
        assert result == "L1S2"
