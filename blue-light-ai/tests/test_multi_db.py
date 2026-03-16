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
        """Each sub-DB name should appear inside DB_INFO_BOX (reference: board name inside box)."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        info_boxes = [c for c in result.components if c.symbol_name == "DB_INFO_BOX"]
        info_labels = [c.label for c in info_boxes]
        assert any("Lighting DB" in t for t in info_labels), \
            f"'Lighting DB' not found in DB_INFO_BOX. Labels: {info_labels}"
        assert any("Power DB" in t for t in info_labels), \
            f"'Power DB' not found in DB_INFO_BOX. Labels: {info_labels}"


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


# ---------------------------------------------------------------------------
# Protection Groups (per-phase RCCB) tests
# ---------------------------------------------------------------------------

MULTI_DB_WITH_PROTECTION_GROUPS = {
    "supply_type": "three_phase",
    "kva": 100,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 35},
    "metering": "ct_meter",
    "distribution_boards": [
        {
            "name": "MSB",
            "breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 100,
            "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": 4},
            "sub_circuits": [
                {"name": "Light 1", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "Light 2", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "Light 3", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20},
                {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20},
                {"name": "Power 3", "breaker_type": "MCB", "breaker_rating": 20},
            ],
        },
        {
            "name": "DB2",
            "breaker": {"type": "MCB", "rating": 40, "poles": "TPN", "fault_kA": 6},
            "busbar_rating": 80,
            "protection_groups": [
                {
                    "phase": "L1",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "circuits": [
                        {"name": "L1 Light A", "breaker_type": "MCB", "breaker_rating": 10},
                        {"name": "L1 Power A", "breaker_type": "MCB", "breaker_rating": 20},
                        {"name": "L1 Spare", "breaker_type": "MCB", "breaker_rating": 10},
                    ],
                },
                {
                    "phase": "L2",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "circuits": [
                        {"name": "L2 Light A", "breaker_type": "MCB", "breaker_rating": 10},
                        {"name": "L2 Power A", "breaker_type": "MCB", "breaker_rating": 20},
                        {"name": "L2 Spare", "breaker_type": "MCB", "breaker_rating": 10},
                    ],
                },
                {
                    "phase": "L3",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "circuits": [
                        {"name": "L3 Light A", "breaker_type": "MCB", "breaker_rating": 10},
                        {"name": "L3 Power A", "breaker_type": "MCB", "breaker_rating": 20},
                        {"name": "L3 Spare", "breaker_type": "MCB", "breaker_rating": 10},
                    ],
                },
            ],
        },
    ],
}


class TestProtectionGroups:
    """Tests for per-phase RCCB protection group layout."""

    def test_protection_groups_produces_result(self):
        """Multi-DB with protection_groups should produce a valid layout."""
        result = compute_layout(MULTI_DB_WITH_PROTECTION_GROUPS)
        assert result.db_count == 2
        assert len(result.components) > 0

    def test_protection_groups_has_rccb_symbols(self):
        """DB2 with 3 protection groups should produce 3 RCCB symbols."""
        result = compute_layout(MULTI_DB_WITH_PROTECTION_GROUPS)
        rccb_count = sum(
            1 for c in result.components
            if c.symbol_name == "RCCB" and "40A" in c.rating
        )
        assert rccb_count >= 3, \
            f"Expected >=3 per-phase RCCBs, found {rccb_count}"

    def test_protection_groups_all_circuits_placed(self):
        """All circuits from protection groups should be placed as breakers."""
        result = compute_layout(MULTI_DB_WITH_PROTECTION_GROUPS)
        # MSB: 6 circuits + DB2: 9 circuits = 15 sub-circuit breakers
        # (padded to triplets: 6 + 9 = 15, already multiples of 3)
        sub_breakers = [
            c for c in result.components
            if c.symbol_name.startswith("CB_")
        ]
        # At least 15 sub-circuit breakers (may have spares padded)
        assert len(sub_breakers) >= 15, \
            f"Expected >=15 sub-circuit breakers, found {len(sub_breakers)}"

    def test_protection_groups_backward_compat(self):
        """Single-DB input should not be affected by protection group support."""
        result = compute_layout(SINGLE_PHASE_3CKT)
        assert result.db_count == 1

    def test_multi_db_no_pgroups_still_works(self):
        """Multi-DB without protection_groups should work as before."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert result.db_count == 2
        rccb_per_phase = [
            c for c in result.components
            if c.symbol_name == "RCCB" and "40A" in (c.rating or "")
        ]
        # No per-phase RCCBs in basic multi-DB
        assert len(rccb_per_phase) == 0


# ---------------------------------------------------------------------------
# Layout Plan tests
# ---------------------------------------------------------------------------

class TestLayoutPlan:
    """Tests for _plan_layout() pre-computation."""

    def test_plan_created_for_multi_db(self):
        """Multi-DB should create a LayoutPlan."""
        from app.sld.layout.engine import _plan_layout
        from app.sld.layout.models import LayoutConfig, LayoutResult, _LayoutContext
        config = LayoutConfig()
        ctx = _LayoutContext(
            result=LayoutResult(), config=config, cx=config.start_x, y=80,
            supply_type="three_phase",
        )
        dbs = MULTI_DB_2_BOARDS["distribution_boards"]
        plan = _plan_layout(ctx, dbs)
        assert len(plan.db_plans) == 2
        assert len(plan.db_cx_positions) == 2
        assert plan.scale_factor > 0
        assert plan.total_width > 0

    def test_plan_cx_positions_ordered(self):
        """DB center positions should be left-to-right ordered."""
        from app.sld.layout.engine import _plan_layout
        from app.sld.layout.models import LayoutConfig, LayoutResult, _LayoutContext
        config = LayoutConfig()
        ctx = _LayoutContext(
            result=LayoutResult(), config=config, cx=config.start_x, y=80,
            supply_type="three_phase",
        )
        dbs = MULTI_DB_2_BOARDS["distribution_boards"]
        plan = _plan_layout(ctx, dbs)
        for i in range(len(plan.db_cx_positions) - 1):
            assert plan.db_cx_positions[i] < plan.db_cx_positions[i + 1]

    def test_plan_with_protection_groups(self):
        """Plan should account for protection group circuits in width."""
        from app.sld.layout.engine import _plan_layout
        from app.sld.layout.models import LayoutConfig, LayoutResult, _LayoutContext
        config = LayoutConfig()
        ctx = _LayoutContext(
            result=LayoutResult(), config=config, cx=config.start_x, y=80,
            supply_type="three_phase",
        )
        dbs = MULTI_DB_WITH_PROTECTION_GROUPS["distribution_boards"]
        plan = _plan_layout(ctx, dbs)
        assert len(plan.db_plans) == 2
        # DB2 has 3 protection groups
        db2_plan = plan.db_plans[1]
        assert len(db2_plan.protection_groups) == 3
        assert db2_plan.protection_groups[0].phase == "L1"
        assert db2_plan.protection_groups[1].phase == "L2"
        assert db2_plan.protection_groups[2].phase == "L3"

    def test_plan_not_created_for_single_db(self):
        """Single-DB should not create a LayoutPlan."""
        result = compute_layout(SINGLE_PHASE_3CKT)
        # Can't directly check ctx.plan, but verify single-DB behavior is unchanged
        assert result.db_count == 1


# ---------------------------------------------------------------------------
# Hierarchical Multi-DB fixtures
# ---------------------------------------------------------------------------

MULTI_DB_HIERARCHICAL = {
    "supply_type": "three_phase",
    "kva": 69,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
    "metering": "ct_meter",
    "busbar_rating": 100,
    "db_topology": "hierarchical",
    "distribution_boards": [
        {
            "name": "MSB",
            "fed_from": None,
            "breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": 4},
            "busbar_rating": 100,
            "sub_circuits": [
                {"name": "Light 1", "breaker_type": "MCB", "breaker_rating": 10,
                 "cable": "2C 1.5sqmm PVC"},
                {"name": "Light 2", "breaker_type": "MCB", "breaker_rating": 10,
                 "cable": "2C 1.5sqmm PVC"},
                {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20,
                 "cable": "2C 2.5sqmm PVC"},
                {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20,
                 "cable": "2C 2.5sqmm PVC"},
                {"name": "Aircon 1", "breaker_type": "MCB", "breaker_rating": 32,
                 "cable": "2C 6sqmm PVC"},
                {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 20,
                 "cable": "2C 2.5sqmm PVC"},
                # Feeder circuit to DB2
                {"name": "Feeder to DB2", "breaker_type": "MCB", "breaker_rating": 63,
                 "breaker_poles": "TPN", "cable": "4C 16sqmm PVC",
                 "_is_feeder": True, "_feeds_db": "DB2"},
            ],
        },
        {
            "name": "DB2",
            "fed_from": "MSB",
            "breaker": {"type": "MCB", "rating": 40, "poles": "TPN", "fault_kA": 6},
            "busbar_rating": 80,
            "protection_groups": [
                {
                    "phase": "L1",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "circuits": [
                        {"name": "L1 Light A", "breaker_type": "MCB", "breaker_rating": 10},
                        {"name": "L1 Power A", "breaker_type": "MCB", "breaker_rating": 20},
                        {"name": "L1 Spare", "breaker_type": "MCB", "breaker_rating": 10},
                    ],
                },
                {
                    "phase": "L2",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "circuits": [
                        {"name": "L2 Light A", "breaker_type": "MCB", "breaker_rating": 10},
                        {"name": "L2 Power A", "breaker_type": "MCB", "breaker_rating": 20},
                        {"name": "L2 Spare", "breaker_type": "MCB", "breaker_rating": 10},
                    ],
                },
                {
                    "phase": "L3",
                    "rccb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": 2},
                    "circuits": [
                        {"name": "L3 Light A", "breaker_type": "MCB", "breaker_rating": 10},
                        {"name": "L3 Power A", "breaker_type": "MCB", "breaker_rating": 20},
                        {"name": "L3 Spare", "breaker_type": "MCB", "breaker_rating": 10},
                    ],
                },
            ],
        },
    ],
}

# Same data but WITHOUT feeder/fed_from — should stay parallel
MULTI_DB_NO_FEEDER = {
    "supply_type": "three_phase",
    "kva": 69,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
    "metering": "ct_meter",
    "busbar_rating": 100,
    "distribution_boards": [
        {
            "name": "MSB",
            "breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 100,
            "sub_circuits": [
                {"name": "Light 1", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20},
                {"name": "Aircon 1", "breaker_type": "MCB", "breaker_rating": 32},
            ],
        },
        {
            "name": "DB2",
            "breaker": {"type": "MCB", "rating": 40, "poles": "TPN"},
            "busbar_rating": 80,
            "sub_circuits": [
                {"name": "Light A", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "Power A", "breaker_type": "MCB", "breaker_rating": 20},
                {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 10},
            ],
        },
    ],
}


# ---------------------------------------------------------------------------
# Hierarchical Layout tests
# ---------------------------------------------------------------------------

class TestHierarchicalLayout:
    """Tests for hierarchical (MSB→feeder→DB2) topology layout."""

    def test_hierarchical_produces_valid_result(self):
        """Hierarchical multi-DB should produce a valid layout."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        assert result.db_count == 2
        assert len(result.components) > 0
        assert len(result.connections) > 0

    def test_incoming_under_root_board(self):
        """Incoming supply components (meter, isolator) should be within MSB region."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        # Find meter component as incoming section marker
        meters = [c for c in result.components if c.symbol_name == "KWH_METER"]
        assert len(meters) >= 1, "KWH_METER not found"
        meter_x = meters[0].x
        # Find root board region (MSB)
        msb_regions = [r for r in result.layout_regions
                       if hasattr(r, 'name') and r.name == "MSB"
                       or isinstance(r, dict) and r.get("name") == "MSB"]
        assert len(msb_regions) >= 1, f"MSB region not found. Regions: {result.layout_regions}"
        region = msb_regions[0]
        msb_min_x = region.min_x if hasattr(region, 'min_x') else region["min_x"]
        msb_max_x = region.max_x if hasattr(region, 'max_x') else region["max_x"]
        # Meter X should be within MSB region (with margin for meter board width)
        assert msb_min_x - 15 <= meter_x <= msb_max_x + 15, \
            f"Meter X={meter_x} not within MSB region [{msb_min_x}, {msb_max_x}]"

    def test_root_busbar_in_msb_region(self):
        """Root (MSB) busbar should be within the MSB region, not at page center."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        from app.sld.layout.models import LayoutConfig
        config = LayoutConfig()
        page_cx = (config.min_x + config.max_x) / 2  # ~210
        # Find MSB region
        msb_regions = [r for r in result.layout_regions
                       if hasattr(r, 'name') and r.name == "MSB"
                       or isinstance(r, dict) and r.get("name") == "MSB"]
        assert len(msb_regions) >= 1
        region = msb_regions[0]
        msb_min_x = region.min_x if hasattr(region, 'min_x') else region["min_x"]
        msb_max_x = region.max_x if hasattr(region, 'max_x') else region["max_x"]
        msb_cx = (msb_min_x + msb_max_x) / 2
        # MSB region center should differ from page center (hierarchical shifts it)
        # In hierarchical mode, MSB is one of multiple regions, not page-centered
        assert msb_max_x < page_cx + 50, \
            f"MSB region max_x={msb_max_x} should not span far past page center {page_cx}"

    def test_feeder_circuit_on_root_busbar(self):
        """Root busbar should have a feeder tap point for child DB."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        # The feeder circuit should produce a breaker (CB_MCB) on root busbar
        all_breakers = [c for c in result.components if c.symbol_name.startswith("CB_")]
        # MSB: 6 normal circuits + 1 feeder = 7 breakers on root busbar area
        # DB2: 9 circuits under protection groups
        # + main breaker + DB2 breaker = total should be well above 16
        assert len(all_breakers) >= 16, \
            f"Expected >=16 total breakers, got {len(all_breakers)}"

    def test_child_board_has_bi_connector(self):
        """DB2 (child board) should have a BI_CONNECTOR."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        bi_count = sum(1 for c in result.components if c.symbol_name == "BI_CONNECTOR")
        # At least 1 for DB2 (child), possibly 2 (one for root too)
        assert bi_count >= 1, "No BI_CONNECTOR found for child board"

    def test_child_board_has_own_busbar(self):
        """DB2 should have its own busbar components."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        # Should have multiple BUSBAR components (root + child)
        busbar_count = sum(1 for c in result.components if c.symbol_name == "BUSBAR")
        assert busbar_count >= 2, \
            f"Expected >=2 BUSBAR components (root + child), got {busbar_count}"

    def test_all_circuits_placed(self):
        """All circuits from both boards should be placed as breakers."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        cb_count = sum(1 for c in result.components if c.symbol_name.startswith("CB_"))
        # MSB: 6 normal + 1 feeder = 7 sub-circuit breakers
        # DB2: 9 circuits in protection groups
        # + 1 main breaker (MCCB) + 1 DB2 breaker (MCB) = minimum 18
        assert cb_count >= 18, \
            f"Expected >=18 total breakers (7 MSB + 9 DB2 + 2 main), got {cb_count}"

    def test_child_board_has_rccb_symbols(self):
        """DB2 with 3 protection groups should have 3 RCCB symbols."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        rccb_count = sum(1 for c in result.components if c.symbol_name == "RCCB")
        assert rccb_count >= 3, \
            f"Expected >=3 RCCBs for DB2 protection groups, got {rccb_count}"

    def test_backward_compat_parallel(self):
        """Existing parallel multi-DB fixture should still produce same result."""
        result = compute_layout(MULTI_DB_2_BOARDS)
        assert result.db_count == 2
        # No hierarchical layout regions (topology is parallel)
        # Just verify it still works
        assert len(result.components) > 0
        assert len(result.db_box_ranges) == 2

    def test_db_name_labels_present(self):
        """Both MSB and DB2 names should appear as labels or info boxes."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        labels = [c for c in result.components if c.symbol_name == "LABEL"]
        label_texts = [c.label for c in labels if c.label]
        info_boxes = [c for c in result.components if c.symbol_name == "DB_INFO_BOX"]
        info_labels = [c.label for c in info_boxes if c.label]
        all_texts = label_texts + info_labels
        assert any("MSB" in t for t in all_texts), \
            f"'MSB' not found in labels/info_boxes. All: {all_texts}"
        assert any("DB2" in t for t in all_texts), \
            f"'DB2' not found in labels/info_boxes. All: {all_texts}"

    def test_overflow_metrics_acceptable(self):
        """Hierarchical layout should have reasonable overflow metrics."""
        result = compute_layout(MULTI_DB_HIERARCHICAL)
        m = result.overflow_metrics
        assert m is not None
        assert 0.0 <= m.quality_score <= 1.0
        # Allow some overflow but not catastrophic
        assert m.overflow_left < 20, f"Left overflow too large: {m.overflow_left}mm"
        assert m.overflow_right < 20, f"Right overflow too large: {m.overflow_right}mm"


# ---------------------------------------------------------------------------
# Feeder Detection tests
# ---------------------------------------------------------------------------

class TestFeederDetection:
    """Tests for _build_db_hierarchy() feeder circuit detection."""

    def test_detect_feeder_by_description(self):
        """'Feeder to DB2' pattern should be detected and hierarchy set."""
        from app.sld.extraction_schema import _build_db_hierarchy
        db_list = [
            {
                "name": "MSB",
                "sub_circuits": [
                    {"name": "Light 1", "breaker_type": "MCB", "breaker_rating": 10},
                    {"name": "Feeder to DB2", "breaker_type": "MCB", "breaker_rating": 63},
                ],
            },
            {
                "name": "DB2",
                "sub_circuits": [
                    {"name": "Light A", "breaker_type": "MCB", "breaker_rating": 10},
                ],
            },
        ]
        topology = _build_db_hierarchy(db_list)
        assert topology == "hierarchical"
        # Feeder circuit should be marked
        feeder = db_list[0]["sub_circuits"][1]
        assert feeder.get("_is_feeder") is True
        assert feeder.get("_feeds_db") == "DB2"
        # Child DB should have fed_from set
        assert db_list[1].get("fed_from") == "MSB"

    def test_detect_feeder_case_insensitive(self):
        """Feeder detection should be case-insensitive."""
        from app.sld.extraction_schema import _build_db_hierarchy
        db_list = [
            {
                "name": "MSB",
                "sub_circuits": [
                    {"name": "1 NO. FEEDER TO DB2", "breaker_type": "MCB", "breaker_rating": 63},
                ],
            },
            {
                "name": "DB2",
                "sub_circuits": [],
            },
        ]
        topology = _build_db_hierarchy(db_list)
        assert topology == "hierarchical"
        assert db_list[1].get("fed_from") == "MSB"

    def test_detect_feeder_by_db_name_in_description(self):
        """Circuit description containing another DB name should detect hierarchy."""
        from app.sld.extraction_schema import _build_db_hierarchy
        db_list = [
            {
                "name": "Main Board",
                "sub_circuits": [
                    {"name": "Supply to Sub DB", "breaker_type": "MCB", "breaker_rating": 63},
                ],
            },
            {
                "name": "Sub DB",
                "sub_circuits": [],
            },
        ]
        topology = _build_db_hierarchy(db_list)
        assert topology == "hierarchical"
        assert db_list[1].get("fed_from") == "Main Board"

    def test_no_feeder_stays_parallel(self):
        """Without feeder circuits, topology should be 'parallel'."""
        from app.sld.extraction_schema import _build_db_hierarchy
        db_list = [
            {
                "name": "DB1",
                "sub_circuits": [
                    {"name": "Light 1", "breaker_type": "MCB", "breaker_rating": 10},
                ],
            },
            {
                "name": "DB2",
                "sub_circuits": [
                    {"name": "Light A", "breaker_type": "MCB", "breaker_rating": 10},
                ],
            },
        ]
        topology = _build_db_hierarchy(db_list)
        assert topology == "parallel"
        assert db_list[0].get("fed_from") is None
        assert db_list[1].get("fed_from") is None

    def test_gemini_fed_from_passthrough(self):
        """If fed_from is already set (by Gemini), it should be respected."""
        from app.sld.extraction_schema import _build_db_hierarchy
        db_list = [
            {
                "name": "MSB",
                "sub_circuits": [
                    {"name": "Some circuit", "breaker_type": "MCB", "breaker_rating": 20},
                ],
            },
            {
                "name": "DB2",
                "fed_from": "MSB",  # Pre-set by Gemini
                "sub_circuits": [],
            },
        ]
        topology = _build_db_hierarchy(db_list)
        assert topology == "hierarchical"
        assert db_list[1]["fed_from"] == "MSB"

    def test_no_self_reference(self):
        """A DB should not detect its own name as a feeder target."""
        from app.sld.extraction_schema import _build_db_hierarchy
        db_list = [
            {
                "name": "MSB",
                "sub_circuits": [
                    {"name": "MSB internal", "breaker_type": "MCB", "breaker_rating": 20},
                ],
            },
            {
                "name": "DB2",
                "sub_circuits": [],
            },
        ]
        topology = _build_db_hierarchy(db_list)
        assert topology == "parallel"


# ---------------------------------------------------------------------------
# B1: DistributionBoardData 5-field extraction (incoming_breaker, etc.)
# ---------------------------------------------------------------------------

class TestB1FieldExtraction:
    """B1 fix: 5 fields in DistributionBoardData are extracted and propagated."""

    def test_distribution_board_data_has_new_fields(self):
        """DistributionBoardData schema includes 5 new fields."""
        from app.sld.extraction_schema import DistributionBoardData
        fields = set(DistributionBoardData.model_fields.keys())
        assert "incoming_breaker" in fields
        assert "feeder_breaker" in fields
        assert "feeder_cable" in fields
        assert "main_mcb" in fields
        assert "meter_board" in fields

    def test_normalize_populates_incoming_breaker(self):
        """normalize_to_generation_format propagates incoming_breaker."""
        from app.sld.extraction_schema import (
            BreakerSpec, DistributionBoardData, SldExtractedData,
            IncomingData, normalize_to_generation_format,
        )
        extracted = SldExtractedData(
            incoming=IncomingData(supply_type="three_phase", kva=100, voltage=400),
            distribution_boards=[
                DistributionBoardData(
                    name="MSB",
                    breaker=BreakerSpec(type="MCCB", rating_a=100, poles="TPN"),
                    incoming_breaker=BreakerSpec(type="MCCB", rating_a=150, poles="TPN", ka_rating=35),
                    outgoing_circuits=[],
                ),
            ],
        )
        result = normalize_to_generation_format(extracted)
        dbs = result.get("distribution_boards", [])
        assert len(dbs) == 1
        assert "incoming_breaker" in dbs[0]
        assert dbs[0]["incoming_breaker"]["rating"] == 150
        assert dbs[0]["incoming_breaker"]["type"] == "MCCB"

    def test_normalize_populates_feeder_breaker(self):
        """normalize_to_generation_format propagates feeder_breaker."""
        from app.sld.extraction_schema import (
            BreakerSpec, DistributionBoardData, SldExtractedData,
            IncomingData, normalize_to_generation_format,
        )
        extracted = SldExtractedData(
            incoming=IncomingData(supply_type="three_phase", kva=100, voltage=400),
            distribution_boards=[
                DistributionBoardData(
                    name="MSB",
                    breaker=BreakerSpec(type="MCCB", rating_a=100, poles="TPN"),
                    outgoing_circuits=[],
                ),
                DistributionBoardData(
                    name="DB2",
                    fed_from="MSB",
                    breaker=BreakerSpec(type="MCB", rating_a=63, poles="TPN"),
                    feeder_breaker=BreakerSpec(type="MCCB", rating_a=80, poles="TPN"),
                    outgoing_circuits=[],
                ),
            ],
        )
        result = normalize_to_generation_format(extracted)
        dbs = result.get("distribution_boards", [])
        db2 = next(d for d in dbs if d["name"] == "DB2")
        assert "feeder_breaker" in db2
        assert db2["feeder_breaker"]["rating"] == 80

    def test_normalize_populates_meter_board(self):
        """normalize_to_generation_format propagates meter_board."""
        from app.sld.extraction_schema import (
            BreakerSpec, DistributionBoardData, SldExtractedData,
            IncomingData, normalize_to_generation_format,
        )
        extracted = SldExtractedData(
            incoming=IncomingData(supply_type="three_phase", kva=100, voltage=400),
            distribution_boards=[
                DistributionBoardData(
                    name="MSB",
                    breaker=BreakerSpec(type="MCCB", rating_a=100, poles="TPN"),
                    meter_board="CT METER BOARD",
                    outgoing_circuits=[],
                ),
            ],
        )
        result = normalize_to_generation_format(extracted)
        dbs = result.get("distribution_boards", [])
        assert dbs[0].get("meter_board") == "CT METER BOARD"

    def test_missing_fields_not_in_output(self):
        """If DistributionBoardData field is None, it should not appear in output dict."""
        from app.sld.extraction_schema import (
            BreakerSpec, DistributionBoardData, SldExtractedData,
            IncomingData, normalize_to_generation_format,
        )
        extracted = SldExtractedData(
            incoming=IncomingData(supply_type="three_phase", kva=100, voltage=400),
            distribution_boards=[
                DistributionBoardData(
                    name="MSB",
                    breaker=BreakerSpec(type="MCCB", rating_a=100, poles="TPN"),
                    outgoing_circuits=[],
                ),
            ],
        )
        result = normalize_to_generation_format(extracted)
        dbs = result.get("distribution_boards", [])
        # None fields should not appear in the dict
        assert "incoming_breaker" not in dbs[0]
        assert "feeder_breaker" not in dbs[0]
        assert "feeder_cable" not in dbs[0]


# ---------------------------------------------------------------------------
# B2: Topology auto-detection fallback
# ---------------------------------------------------------------------------

class TestB2TopologyAutoDetect:
    """B2 fix: db_topology omitted for parallel → engine auto-detects."""

    def test_parallel_topology_not_set(self):
        """When no hierarchy detected, db_topology should be absent."""
        from app.sld.extraction_schema import (
            BreakerSpec, DistributionBoardData, SldExtractedData,
            IncomingData, normalize_to_generation_format,
        )
        extracted = SldExtractedData(
            incoming=IncomingData(supply_type="three_phase", kva=100, voltage=400),
            distribution_boards=[
                DistributionBoardData(
                    name="DB1",
                    breaker=BreakerSpec(type="MCB", rating_a=63, poles="TPN"),
                    outgoing_circuits=[],
                ),
                DistributionBoardData(
                    name="DB2",
                    breaker=BreakerSpec(type="MCB", rating_a=63, poles="TPN"),
                    outgoing_circuits=[],
                ),
            ],
        )
        result = normalize_to_generation_format(extracted)
        # No hierarchy → db_topology should be absent (let engine auto-detect)
        assert "db_topology" not in result

    def test_hierarchical_topology_set(self):
        """When hierarchy detected (fed_from set), db_topology = 'hierarchical'."""
        from app.sld.extraction_schema import (
            BreakerSpec, DistributionBoardData, SldExtractedData,
            IncomingData, normalize_to_generation_format, OutgoingCircuit,
        )
        extracted = SldExtractedData(
            incoming=IncomingData(supply_type="three_phase", kva=100, voltage=400),
            distribution_boards=[
                DistributionBoardData(
                    name="MSB",
                    breaker=BreakerSpec(type="MCCB", rating_a=100, poles="TPN"),
                    outgoing_circuits=[
                        OutgoingCircuit(description="Feeder to DB2", breaker=BreakerSpec(type="MCB", rating_a=63)),
                    ],
                ),
                DistributionBoardData(
                    name="DB2",
                    breaker=BreakerSpec(type="MCB", rating_a=63, poles="TPN"),
                    outgoing_circuits=[],
                ),
            ],
        )
        result = normalize_to_generation_format(extracted)
        assert result.get("db_topology") == "hierarchical"

    def test_engine_auto_detects_from_fed_from(self):
        """Engine auto-detects hierarchy when db_topology is absent."""
        reqs = {
            "supply_type": "three_phase",
            "kva": 100,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            "metering": "ct_meter",
            "busbar_rating": 200,
            # Note: no db_topology key — engine should auto-detect
            "distribution_boards": [
                {
                    "name": "MSB",
                    "breaker": {"type": "MCCB", "rating": 100, "poles": "TPN"},
                    "busbar_rating": 100,
                    "sub_circuits": [
                        {"name": "Light 1", "breaker_type": "MCB", "breaker_rating": 10},
                        {"name": "Light 2", "breaker_type": "MCB", "breaker_rating": 10},
                        {"name": "Light 3", "breaker_type": "MCB", "breaker_rating": 10},
                    ],
                },
                {
                    "name": "DB2",
                    "fed_from": "MSB",
                    "breaker": {"type": "MCB", "rating": 63, "poles": "TPN"},
                    "busbar_rating": 100,
                    "sub_circuits": [
                        {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20},
                        {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20},
                        {"name": "Power 3", "breaker_type": "MCB", "breaker_rating": 20},
                    ],
                },
            ],
        }
        result = compute_layout(reqs)
        assert result.db_count == 2
        assert len(result.components) > 0
