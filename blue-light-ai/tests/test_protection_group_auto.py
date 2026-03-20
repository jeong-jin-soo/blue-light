"""Tests for auto-detection of per-phase RCCB protection groups in schedule_parser."""

from app.sld.schedule_parser import _auto_detect_protection_groups


class TestAutoDetectProtectionGroups:
    """Verify _auto_detect_protection_groups post-processing."""

    def _make_three_phase_db(self, circuits, elcb=None, name="DB2"):
        """Helper to build a minimal multi-DB extraction result."""
        db = {"name": name, "outgoing_circuits": circuits}
        if elcb:
            db["elcb"] = elcb
        return {
            "incoming": {"phase": "three_phase", "voltage": 400},
            "distribution_boards": [db],
        }

    # ── Happy path: phase-assigned circuits grouped into PGs ──

    def test_basic_phase_grouping(self):
        """9 circuits (3 per phase) → 3 protection groups."""
        circuits = [
            {"id": "L1S1", "description": "Lighting", "phase": "L1", "breaker": {"type": "MCB", "rating_a": 10}},
            {"id": "L1P1", "description": "Socket", "phase": "L1", "breaker": {"type": "MCB", "rating_a": 20}},
            {"id": "L1P2", "description": "Socket", "phase": "L1", "breaker": {"type": "MCB", "rating_a": 20}},
            {"id": "L2S1", "description": "Lighting", "phase": "L2", "breaker": {"type": "MCB", "rating_a": 10}},
            {"id": "L2P1", "description": "Socket", "phase": "L2", "breaker": {"type": "MCB", "rating_a": 20}},
            {"id": "L2P2", "description": "Socket", "phase": "L2", "breaker": {"type": "MCB", "rating_a": 20}},
            {"id": "L3S1", "description": "Lighting", "phase": "L3", "breaker": {"type": "MCB", "rating_a": 10}},
            {"id": "L3P1", "description": "Socket", "phase": "L3", "breaker": {"type": "MCB", "rating_a": 20}},
            {"id": "L3P2", "description": "Socket", "phase": "L3", "breaker": {"type": "MCB", "rating_a": 20}},
        ]
        data = self._make_three_phase_db(circuits)
        result = _auto_detect_protection_groups(data)
        db = result["distribution_boards"][0]

        assert len(db["protection_groups"]) == 3
        assert db["protection_groups"][0]["phase"] == "L1"
        assert db["protection_groups"][1]["phase"] == "L2"
        assert db["protection_groups"][2]["phase"] == "L3"
        # Each group has 3 circuits
        for pg in db["protection_groups"]:
            assert len(pg["circuits"]) == 3
        # Board-level ELCB removed (now per-phase)
        assert "elcb" not in db
        # No unassigned circuits
        assert db["outgoing_circuits"] == []

    def test_rccb_defaults(self):
        """Default RCCB: 40A 2P 30mA."""
        circuits = [
            {"id": f"L{p}P{i}", "phase": f"L{p}", "breaker": {"type": "MCB"}}
            for p in (1, 2, 3) for i in range(1, 4)
        ]
        data = self._make_three_phase_db(circuits)
        result = _auto_detect_protection_groups(data)
        rccb = result["distribution_boards"][0]["protection_groups"][0]["rccb"]
        assert rccb["type"] == "RCCB"
        assert rccb["rating_a"] == 40
        assert rccb["poles"] == 2
        assert rccb["sensitivity_ma"] == 30

    def test_inherits_board_elcb_rating(self):
        """Per-phase RCCB inherits rating from board-level ELCB."""
        circuits = [
            {"id": f"L{p}P{i}", "phase": f"L{p}", "breaker": {"type": "MCB"}}
            for p in (1, 2, 3) for i in range(1, 4)
        ]
        elcb = {"rating_a": 63, "sensitivity_ma": 100, "type": "RCCB"}
        data = self._make_three_phase_db(circuits, elcb=elcb)
        result = _auto_detect_protection_groups(data)
        rccb = result["distribution_boards"][0]["protection_groups"][0]["rccb"]
        assert rccb["rating_a"] == 63
        assert rccb["sensitivity_ma"] == 100

    def test_ryb_phase_normalization(self):
        """R/Y/B phase names → L1/L2/L3."""
        circuits = [
            {"id": "R1", "phase": "R", "breaker": {"type": "MCB"}},
            {"id": "R2", "phase": "R", "breaker": {"type": "MCB"}},
            {"id": "R3", "phase": "R", "breaker": {"type": "MCB"}},
            {"id": "Y1", "phase": "Y", "breaker": {"type": "MCB"}},
            {"id": "Y2", "phase": "Y", "breaker": {"type": "MCB"}},
            {"id": "Y3", "phase": "Y", "breaker": {"type": "MCB"}},
            {"id": "B1", "phase": "B", "breaker": {"type": "MCB"}},
            {"id": "B2", "phase": "B", "breaker": {"type": "MCB"}},
            {"id": "B3", "phase": "B", "breaker": {"type": "MCB"}},
        ]
        data = self._make_three_phase_db(circuits)
        result = _auto_detect_protection_groups(data)
        phases = [pg["phase"] for pg in result["distribution_boards"][0]["protection_groups"]]
        assert phases == ["L1", "L2", "L3"]

    def test_infer_phase_from_circuit_id(self):
        """Circuits without explicit phase but with L1/L2/L3 prefix in ID."""
        circuits = [
            {"id": "L1S1", "description": "Lighting", "breaker": {"type": "MCB"}},
            {"id": "L1P1", "description": "Socket", "breaker": {"type": "MCB"}},
            {"id": "L2S1", "description": "Lighting", "breaker": {"type": "MCB"}},
            {"id": "L2P1", "description": "Socket", "breaker": {"type": "MCB"}},
            {"id": "L3S1", "description": "Lighting", "breaker": {"type": "MCB"}},
            {"id": "L3P1", "description": "Socket", "breaker": {"type": "MCB"}},
        ]
        data = self._make_three_phase_db(circuits)
        result = _auto_detect_protection_groups(data)
        assert len(result["distribution_boards"][0]["protection_groups"]) == 3

    def test_unassigned_circuits_stay_in_outgoing(self):
        """Circuits without phase go to outgoing_circuits."""
        circuits = [
            {"id": "L1P1", "phase": "L1", "breaker": {"type": "MCB"}},
            {"id": "L1P2", "phase": "L1", "breaker": {"type": "MCB"}},
            {"id": "L1P3", "phase": "L1", "breaker": {"type": "MCB"}},
            {"id": "L2P1", "phase": "L2", "breaker": {"type": "MCB"}},
            {"id": "L2P2", "phase": "L2", "breaker": {"type": "MCB"}},
            {"id": "L2P3", "phase": "L2", "breaker": {"type": "MCB"}},
            {"id": "L3P1", "phase": "L3", "breaker": {"type": "MCB"}},
            {"id": "L3P2", "phase": "L3", "breaker": {"type": "MCB"}},
            {"id": "SPARE", "description": "Spare", "breaker": {}},
        ]
        data = self._make_three_phase_db(circuits)
        result = _auto_detect_protection_groups(data)
        db = result["distribution_boards"][0]
        assert len(db["protection_groups"]) == 3
        assert len(db["outgoing_circuits"]) == 1
        assert db["outgoing_circuits"][0]["id"] == "SPARE"

    # ── Skip cases: should NOT create protection groups ──

    def test_skip_single_phase(self):
        """Single-phase incoming → no protection groups."""
        data = {
            "incoming": {"phase": "single_phase", "voltage": 230},
            "distribution_boards": [{
                "name": "DB",
                "outgoing_circuits": [
                    {"id": f"P{i}", "breaker": {"type": "MCB"}} for i in range(1, 10)
                ],
            }],
        }
        result = _auto_detect_protection_groups(data)
        assert not result["distribution_boards"][0].get("protection_groups")

    def test_skip_too_few_circuits(self):
        """< 6 phase-assigned circuits → no grouping."""
        circuits = [
            {"id": "L1P1", "phase": "L1", "breaker": {"type": "MCB"}},
            {"id": "L2P1", "phase": "L2", "breaker": {"type": "MCB"}},
            {"id": "L3P1", "phase": "L3", "breaker": {"type": "MCB"}},
        ]
        data = self._make_three_phase_db(circuits)
        result = _auto_detect_protection_groups(data)
        assert not result["distribution_boards"][0].get("protection_groups")

    def test_skip_existing_protection_groups(self):
        """Already has protection_groups → no modification."""
        data = self._make_three_phase_db([])
        data["distribution_boards"][0]["protection_groups"] = [{"phase": "L1", "circuits": []}]
        result = _auto_detect_protection_groups(data)
        assert len(result["distribution_boards"][0]["protection_groups"]) == 1

    def test_skip_no_distribution_boards(self):
        """Single-DB mode (outgoing_circuits only) → passthrough."""
        data = {
            "incoming": {"phase": "three_phase"},
            "outgoing_circuits": [{"id": "P1", "breaker": {"type": "MCB"}}],
        }
        result = _auto_detect_protection_groups(data)
        assert "distribution_boards" not in result or not result.get("distribution_boards")

    def test_skip_only_one_phase(self):
        """All circuits on one phase → no grouping (needs ≥ 2 phases)."""
        circuits = [
            {"id": f"L1P{i}", "phase": "L1", "breaker": {"type": "MCB"}}
            for i in range(1, 10)
        ]
        data = self._make_three_phase_db(circuits)
        result = _auto_detect_protection_groups(data)
        assert not result["distribution_boards"][0].get("protection_groups")
