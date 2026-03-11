"""
B1: Multi-DB (Multiple Distribution Boards) layout tests.

Tests:
1. Backward compatibility — single-DB inputs unchanged
2. Multi-DB layout produces valid result with correct db_count
3. BI_CONNECTORs present for each sub-DB
4. Sub-DB boxes non-overlapping horizontally
5. All components within A3 boundaries
6. Overflow detection with multi-DB
7. Sub-DB busbar labels and names
8. Context restoration after sub-DB placement
"""

import pytest

from app.sld.layout import compute_layout


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

SINGLE_PHASE_3CKT = {
    "supply_type": "single_phase",
    "kva": 9,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2},
    "sub_circuits": [
        {"name": "Socket 1", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 4.0sqmm PVC/PVC"},
        {"name": "Socket 2", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 4.0sqmm PVC/PVC"},
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC/PVC"},
    ],
}

THREE_PHASE_6CKT = {
    "supply_type": "three_phase",
    "kva": 22,
    "voltage": 400,
    "main_breaker": {"type": "MCB", "rating": 32, "poles": "TPN", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 100, "poles": 4},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
        {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
        {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
        {"name": "Aircon 1", "breaker_type": "MCB", "breaker_rating": 32, "cable": "2C 6sqmm PVC"},
        {"name": "Aircon 2", "breaker_type": "MCB", "breaker_rating": 32, "cable": "2C 6sqmm PVC"},
        {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
    ],
}

MULTI_DB_2_BOARDS = {
    "supply_type": "three_phase",
    "kva": 100,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 150, "poles": "TPN", "fault_kA": 35},
    "metering": "ct_meter",
    "busbar_rating": 200,
    "distribution_boards": [
        {
            "name": "Lighting DB",
            "breaker": {"type": "MCB", "rating": 63, "poles": "TPN", "fault_kA": 10},
            "busbar_rating": 100,
            "sub_circuits": [
                {"name": "Light 1", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
                {"name": "Light 2", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
                {"name": "Light 3", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
            ],
        },
        {
            "name": "Power DB",
            "breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 200,
            "sub_circuits": [
                {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
                {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
                {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
            ],
        },
    ],
}

MULTI_DB_2_BOARDS_WITH_ELCB = {
    "supply_type": "three_phase",
    "kva": 100,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 150, "poles": "TPN", "fault_kA": 35},
    "metering": "ct_meter",
    "busbar_rating": 200,
    "elcb": {"rating": 150, "sensitivity_ma": 300, "poles": 4},
    "distribution_boards": [
        {
            "name": "Lighting DB",
            "breaker": {"type": "MCB", "rating": 63, "poles": "TPN", "fault_kA": 10},
            "busbar_rating": 100,
            "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": 4, "type": "RCCB"},
            "sub_circuits": [
                {"name": "Light 1", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
                {"name": "Light 2", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
                {"name": "Light 3", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
            ],
        },
        {
            "name": "Power DB",
            "breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 200,
            "elcb": {"rating": 100, "sensitivity_ma": 100, "poles": 4},
            "sub_circuits": [
                {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
                {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
                {"name": "Aircon", "breaker_type": "MCB", "breaker_rating": 32, "cable": "2C 6sqmm PVC"},
            ],
        },
    ],
}

MULTI_DB_ASYMMETRIC = {
    "supply_type": "three_phase",
    "kva": 80,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 125, "poles": "TPN", "fault_kA": 25},
    "metering": "ct_meter",
    "busbar_rating": 200,
    "distribution_boards": [
        {
            "name": "Small DB",
            "breaker": {"type": "MCB", "rating": 40, "poles": "TPN"},
            "busbar_rating": 100,
            "sub_circuits": [
                {"name": "Light", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
                {"name": "Socket", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
                {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
            ],
        },
        {
            "name": "Large DB",
            "breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 200,
            "sub_circuits": [
                {"name": f"Circuit {i+1}", "breaker_type": "MCB",
                 "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"}
                for i in range(9)
            ],
        },
    ],
}


# ---------------------------------------------------------------------------
# Backward compatibility
# ---------------------------------------------------------------------------

class TestBackwardCompat:
    """Single-DB inputs produce identical results (no regressions)."""

    def test_single_phase_db_count_is_1(self):
        result = compute_layout(SINGLE_PHASE_3CKT)
        assert result.db_count == 1

    def test_three_phase_db_count_is_1(self):
        result = compute_layout(THREE_PHASE_6CKT)
        assert result.db_count == 1

    def test_no_db_box_ranges_for_single_db(self):
        result = compute_layout(SINGLE_PHASE_3CKT)
        assert result.db_box_ranges == []

    def test_single_db_has_components(self):
        result = compute_layout(SINGLE_PHASE_3CKT)
        assert len(result.components) > 0

    def test_single_db_has_connections(self):
        result = compute_layout(THREE_PHASE_6CKT)
        assert len(result.connections) > 0

    def test_single_db_dashed_connections(self):
        """Single-DB still gets a DB box (dashed lines)."""
        result = compute_layout(SINGLE_PHASE_3CKT)
        assert len(result.dashed_connections) > 0


# ---------------------------------------------------------------------------
# Multi-DB layout basics
# ---------------------------------------------------------------------------

class TestMultiDbLayout:
    """Multi-DB layout produces valid results."""

    def test_db_count_matches_input(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert result.db_count == 2

    def test_has_components(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert len(result.components) > 0

    def test_has_connections(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert len(result.connections) > 0

    def test_has_dashed_connections(self):
        """Multi-DB produces dashed lines for sub-DB boxes."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert len(result.dashed_connections) > 0

    def test_db_box_ranges_populated(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert len(result.db_box_ranges) == 2

    def test_db_box_ranges_have_names(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        names = [r["name"] for r in result.db_box_ranges]
        assert "Lighting DB" in names
        assert "Power DB" in names

    def test_with_elcb_on_sub_dbs(self):
        """Sub-DB with ELCB still produces valid layout."""
        result = compute_layout(MULTI_DB_2_BOARDS_WITH_ELCB)
        assert result.db_count == 2
        assert len(result.components) > 0

    def test_asymmetric_circuit_counts(self):
        """DBs with different circuit counts produce valid layout."""
        result = compute_layout(MULTI_DB_ASYMMETRIC)
        assert result.db_count == 2
        assert len(result.components) > 0


# ---------------------------------------------------------------------------
# BI_CONNECTORs
# ---------------------------------------------------------------------------

class TestMultiDbBiConnectors:
    """Each sub-DB gets a BI_CONNECTOR from the main busbar."""

    def test_bi_connector_count(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        bi_count = sum(1 for c in result.components if c.symbol_name == "BI_CONNECTOR")
        assert bi_count >= 2

    def test_bi_connector_in_symbols_used(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert "BI_CONNECTOR" in result.symbols_used

    def test_bi_connectors_at_different_x(self):
        """BI_CONNECTORs should be at different X positions (side by side)."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        bi_comps = [c for c in result.components if c.symbol_name == "BI_CONNECTOR"]
        if len(bi_comps) >= 2:
            x_positions = [c.x for c in bi_comps]
            assert len(set(round(x, 1) for x in x_positions)) >= 2, \
                f"BI_CONNECTORs should be at different X positions: {x_positions}"


# ---------------------------------------------------------------------------
# No horizontal overlap between sub-DBs
# ---------------------------------------------------------------------------

class TestMultiDbNoOverlap:
    """Sub-DB boxes should not overlap horizontally."""

    def test_db_boxes_non_overlapping(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        ranges = result.db_box_ranges
        if len(ranges) >= 2:
            # Sort by center X
            sorted_ranges = sorted(ranges, key=lambda r: r["cx"])
            for i in range(len(sorted_ranges) - 1):
                left_right = sorted_ranges[i]["busbar_end_x"]
                right_left = sorted_ranges[i + 1]["busbar_start_x"]
                assert left_right <= right_left + 5, \
                    f"DB boxes overlap: DB{i} right={left_right}, DB{i+1} left={right_left}"

    def test_asymmetric_produces_valid_layout(self):
        """Asymmetric DB (3 vs 9 circuits) produces a valid layout without crash."""
        result = compute_layout(MULTI_DB_ASYMMETRIC)
        assert result.db_count == 2
        assert len(result.db_box_ranges) == 2
        # Both DBs should have distinct center positions
        cx_values = [r["cx"] for r in result.db_box_ranges]
        assert len(set(round(x, 1) for x in cx_values)) == 2


# ---------------------------------------------------------------------------
# Boundary / overflow detection
# ---------------------------------------------------------------------------

class TestMultiDbBoundaries:
    """All multi-DB content should respect A3 boundaries."""

    def test_overflow_metrics_populated(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert result.overflow_metrics is not None

    def test_quality_score_in_range(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        m = result.overflow_metrics
        assert 0.0 <= m.quality_score <= 1.0

    def test_content_extents_nonzero(self):
        result = compute_layout(MULTI_DB_2_BOARDS)
        m = result.overflow_metrics
        assert m.content_max_x > m.content_min_x
        assert m.content_max_y > m.content_min_y

    def test_overflow_metrics_serializable(self):
        import json
        result = compute_layout(MULTI_DB_2_BOARDS)
        d = result.overflow_metrics.to_dict()
        json_str = json.dumps(d)
        assert isinstance(json_str, str)


# ---------------------------------------------------------------------------
# Sub-circuit components within sub-DBs
# ---------------------------------------------------------------------------

class TestMultiDbSubCircuits:
    """Verify sub-circuits from each DB are present in the layout."""

    def test_all_sub_circuit_breakers_placed(self):
        """Each sub-circuit should produce a CB_MCB or CB_MCCB component."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        cb_count = sum(1 for c in result.components
                       if c.symbol_name in ("CB_MCB", "CB_MCCB"))
        # 2 DBs × 3 sub-circuits each = 6 sub-circuit breakers
        # + 1 main breaker + 2 sub-DB breakers = 9+ total
        assert cb_count >= 6, f"Expected ≥6 breakers, got {cb_count}"

    def test_all_db_busbars_placed(self):
        """Each sub-DB should have its own busbar segment."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        busbar_connections = [c for c in result.connections
                              if abs(c[0][1] - c[1][1]) < 0.1  # horizontal lines
                              and abs(c[0][0] - c[1][0]) > 10]  # long enough
        # Should have at least main busbar + 2 sub-DB busbars
        assert len(busbar_connections) >= 3

    def test_db_info_boxes_present(self):
        """Each sub-DB should have a DB_INFO_BOX component."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        info_boxes = [c for c in result.components if c.symbol_name == "DB_INFO_BOX"]
        # At least 2 info boxes (one per sub-DB)
        assert len(info_boxes) >= 2

    def test_db_name_labels_present(self):
        """Each sub-DB name should appear as a label."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        labels = [c for c in result.components if c.symbol_name == "LABEL"]
        label_texts = [c.label for c in labels]
        assert any("Lighting DB" in t for t in label_texts), \
            f"'Lighting DB' label not found. Labels: {label_texts}"
        assert any("Power DB" in t for t in label_texts), \
            f"'Power DB' label not found. Labels: {label_texts}"


# ---------------------------------------------------------------------------
# Context restoration (implementation detail sanity check)
# ---------------------------------------------------------------------------

class TestContextRestoration:
    """After placing multi-DB, main context values should be restored."""

    def test_spine_x_matches_page_center(self):
        """spine_x should be the page center, not a sub-DB cx."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        from app.sld.layout.models import LayoutConfig
        config = LayoutConfig()
        expected_cx = (config.min_x + config.max_x) / 2
        assert abs(result.spine_x - expected_cx) < 1.0, \
            f"spine_x={result.spine_x}, expected ~{expected_cx}"
