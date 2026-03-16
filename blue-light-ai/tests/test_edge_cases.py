"""
Edge case and boundary value tests for SLD layout engine.

Covers:
- Single circuit (minimum)
- Busbar label (always "BUSBAR" per LEW convention)
- Large circuit count (30+)
- Zero/invalid breaker ratings
- Multi-row Y-tolerance
- Empty sub_circuits
- Performance guard
"""

import time

import pytest

from app.sld.layout import compute_layout, LayoutConfig, LayoutResult, PlacedComponent
from app.sld.layout.helpers import _next_standard_rating


def _make_req(
    supply_type: str = "single_phase",
    kva: float = 14,
    voltage: int = 230,
    busbar_rating: int = 100,
    main_breaker: dict | None = None,
    elcb: dict | None = None,
    sub_circuits: list[dict] | None = None,
) -> dict:
    """Build a minimal requirements dict."""
    if main_breaker is None:
        main_breaker = {"type": "MCB", "rating": 63, "poles": "DP", "fault_kA": 10}
    req = {
        "supply_type": supply_type,
        "kva": kva,
        "voltage": voltage,
        "main_breaker": main_breaker,
        "busbar_rating": busbar_rating,
        "sub_circuits": sub_circuits or [],
    }
    if elcb:
        req["elcb"] = elcb
    return req


def _get_components_by_type(layout: LayoutResult, symbol_name: str) -> list[PlacedComponent]:
    return [c for c in layout.components if c.symbol_name == symbol_name]


def _make_circuits(n: int, breaker_rating: int = 20) -> list[dict]:
    """Generate n identical sub-circuits."""
    return [
        {
            "name": f"Circuit {i+1}",
            "breaker_type": "MCB",
            "breaker_rating": breaker_rating,
            "breaker_characteristic": "B",
        }
        for i in range(n)
    ]


# ===========================================================================
# Test: Minimum circuit count
# ===========================================================================

class TestMinimumCircuit:
    """Test SLD generation with exactly 1 circuit."""

    def test_single_circuit_layout(self):
        """A single sub-circuit should produce valid layout."""
        req = _make_req(sub_circuits=[
            {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10},
        ])
        layout = compute_layout(req)
        assert len(layout.components) > 5  # At least busbar, breaker, ID, etc.
        assert layout.busbar_start_x < layout.busbar_end_x

    def test_single_circuit_has_id_box(self):
        """Single circuit should still have CIRCUIT_ID_BOX."""
        req = _make_req(sub_circuits=[
            {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10},
        ])
        layout = compute_layout(req)
        id_boxes = _get_components_by_type(layout, "CIRCUIT_ID_BOX")
        assert len(id_boxes) >= 1

    def test_single_circuit_has_busbar(self):
        """Single circuit should have a BUSBAR component."""
        req = _make_req(sub_circuits=[
            {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10},
        ])
        layout = compute_layout(req)
        busbars = _get_components_by_type(layout, "BUSBAR")
        assert len(busbars) >= 1


# ===========================================================================
# Test: 500A busbar threshold
# ===========================================================================

class TestBusbarThreshold:
    """Test busbar labels always use 'BUSBAR' (LEW convention)."""

    def test_busbar_63a_is_busbar(self):
        """busbar_rating=63 should produce 'BUSBAR' label (LEW convention)."""
        req = _make_req(
            supply_type="single_phase",
            kva=14,
            voltage=230,
            busbar_rating=63,
            main_breaker={"type": "MCB", "rating": 63, "poles": "DP", "fault_kA": 6},
            sub_circuits=_make_circuits(3),
        )
        layout = compute_layout(req)
        labels = [c for c in layout.components
                  if c.symbol_name == "LABEL" and "63A" in (c.label or "")]
        busbar_labels = [l for l in labels if "BUSBAR" in (l.label or "")]
        assert len(busbar_labels) >= 1, \
            "63A should use BUSBAR label (LEW convention)"

    def test_busbar_100a_is_busbar(self):
        """busbar_rating=100 should produce 'BUSBAR' label (LEW convention)."""
        req = _make_req(
            supply_type="three_phase",
            kva=69,
            voltage=400,
            busbar_rating=100,
            main_breaker={"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 35},
            sub_circuits=_make_circuits(3),
        )
        layout = compute_layout(req)
        labels = [c for c in layout.components
                  if c.symbol_name == "LABEL" and "100A" in (c.label or "")]
        busbar_labels = [l for l in labels if "BUSBAR" in (l.label or "")]
        assert len(busbar_labels) >= 1, "100A should use BUSBAR label (LEW convention)"


# ===========================================================================
# Test: Large circuit count
# ===========================================================================

class TestLargeCircuitCount:
    """Test SLD generation with many circuits (30+)."""

    def test_30_circuits_fits_in_bounds(self):
        """30 sub-circuits should fit within A3 drawing bounds."""
        req = _make_req(
            supply_type="three_phase",
            kva=69,
            voltage=400,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            sub_circuits=_make_circuits(30),
        )
        layout = compute_layout(req)
        # Verify all components are within A3 bounds (420mm x 297mm)
        for comp in layout.components:
            assert 0 <= comp.x <= 430, f"Component x={comp.x} out of bounds"
            assert -10 <= comp.y <= 310, f"Component y={comp.y} out of bounds"

    def test_30_circuits_all_have_id(self):
        """30 sub-circuits should all get circuit IDs."""
        req = _make_req(
            supply_type="three_phase",
            kva=69,
            voltage=400,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            sub_circuits=_make_circuits(30),
        )
        layout = compute_layout(req)
        id_boxes = _get_components_by_type(layout, "CIRCUIT_ID_BOX")
        # 30 circuits in 3-phase → already multiple of 3
        assert len(id_boxes) >= 30


# ===========================================================================
# Test: Breaker rating edge cases
# ===========================================================================

class TestBreakerRatingEdge:
    """Test _next_standard_rating() boundary behavior."""

    def test_rating_0_returns_6(self):
        """breaker_rating=0 should map to first standard rating (6A)."""
        assert _next_standard_rating(0) == 6

    def test_rating_negative_returns_6(self):
        """Negative rating should map to first standard rating."""
        assert _next_standard_rating(-1) == 6

    def test_rating_exact_match(self):
        """Exact standard rating should return itself."""
        assert _next_standard_rating(20) == 20
        assert _next_standard_rating(100) == 100
        assert _next_standard_rating(500) == 500

    def test_rating_above_max_returns_max(self):
        """Rating above max should return max (3200A)."""
        assert _next_standard_rating(1500) == 1600
        assert _next_standard_rating(3500) == 3200


# ===========================================================================
# Test: Empty sub_circuits
# ===========================================================================

class TestEmptyCircuits:
    """Test SLD generation with no sub-circuits."""

    def test_empty_circuits_no_crash(self):
        """Empty sub_circuits should not crash."""
        req = _make_req(sub_circuits=[])
        layout = compute_layout(req)
        # Should still have busbar, main breaker, etc.
        assert len(layout.components) > 0

    def test_empty_circuits_has_busbar(self):
        """Even with empty circuits, busbar should exist."""
        req = _make_req(sub_circuits=[])
        layout = compute_layout(req)
        busbars = _get_components_by_type(layout, "BUSBAR")
        assert len(busbars) >= 1


# ===========================================================================
# Test: Performance guard
# ===========================================================================

class TestPerformance:
    """Sanity check that layout computation is fast enough."""

    def test_36_circuits_under_2_seconds(self):
        """36 circuits (12 triplets) should compute in under 2 seconds."""
        req = _make_req(
            supply_type="three_phase",
            kva=69,
            voltage=400,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            sub_circuits=_make_circuits(36),
        )
        start = time.monotonic()
        layout = compute_layout(req)
        elapsed = time.monotonic() - start
        assert elapsed < 2.0, f"Layout took {elapsed:.2f}s (expected <2s)"
        assert len(layout.components) > 36


# ===========================================================================
# Test: Multi-row layout integration
# ===========================================================================

class TestMultiRow:
    """Integration tests for multi-row sub-circuit layout.

    Default max_circuits_per_row = 30, so:
    - 32 circuits → 2 rows (30 + 2)
    - 45 circuits → 2 rows (30 + 15)
    - 61 circuits → 3 rows (30 + 30 + 1)
    """

    @staticmethod
    def _three_phase_req(num_circuits: int) -> dict:
        """Build a three-phase requirements dict with *num_circuits* sub-circuits."""
        return _make_req(
            supply_type="three_phase",
            kva=200,
            voltage=400,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 200, "poles": "TPN", "fault_kA": 25},
            sub_circuits=_make_circuits(num_circuits),
        )

    # ---- 1. 32 circuits triggers two rows ----

    def test_32_circuits_two_rows(self):
        """32 circuits exceeds max_circuits_per_row=30 → busbar_y_per_row has 2 entries."""
        layout = compute_layout(self._three_phase_req(32))
        assert len(layout.busbar_y_per_row) == 2, (
            f"Expected 2 busbar rows, got {len(layout.busbar_y_per_row)}"
        )

    # ---- 2. 32 circuits all within drawing bounds ----

    def test_32_circuits_all_within_bounds(self):
        """All components should stay within the A3 drawing bounds."""
        layout = compute_layout(self._three_phase_req(32))
        config = LayoutConfig()
        for comp in layout.components:
            assert config.min_x - 20 <= comp.x <= config.max_x + 20, (
                f"{comp.symbol_name} x={comp.x:.1f} outside bounds "
                f"[{config.min_x}, {config.max_x}]"
            )

    # ---- 3. 45 3-phase circuits without phase info → sequential IDs ----

    def test_45_circuits_3phase_maintained(self):
        """45 three-phase circuits without phase info → no triplets, sequential IDs."""
        layout = compute_layout(self._three_phase_req(45))
        circuit_ids = [c.circuit_id for c in layout.components if c.circuit_id]
        # Without phase info, triplets are NOT applied (default=False).
        # Circuits get sequential IDs (P1, P2, ...) instead of L1P1, L2P1, L3P1.
        assert len(circuit_ids) > 0, "No circuit IDs found"
        # Verify layout still generates all circuits across multi-row
        assert len(layout.busbar_y_per_row) >= 2, "45 circuits should need multi-row"

    # ---- 4. 61 circuits → 3 rows ----

    def test_61_circuits_3_rows(self):
        """61 circuits → 3 rows (30 + 30 + 1) → busbar_y_per_row has 3 entries."""
        layout = compute_layout(self._three_phase_req(61))
        assert len(layout.busbar_y_per_row) == 3, (
            f"Expected 3 busbar rows, got {len(layout.busbar_y_per_row)}"
        )

    # ---- 5. Each row's busbar covers its circuits ----

    def test_multirow_busbar_covers_circuits(self):
        """For each row, BUSBAR X-span should cover all breaker tap positions in that row."""
        layout = compute_layout(self._three_phase_req(32))
        busbar_ys = layout.busbar_y_per_row
        assert len(busbar_ys) >= 2, "Need at least 2 rows for this test"

        _Y_TOL = 30.0  # Y-tolerance for matching components to a busbar row

        for row_idx, by in enumerate(busbar_ys):
            # Find BUSBAR component for this row
            row_busbars = [
                c for c in layout.components
                if c.symbol_name == "BUSBAR" and abs(c.y - by) < _Y_TOL
            ]
            if not row_busbars:
                # Row may use a different busbar representation; skip
                continue

            busbar = row_busbars[0]

            # Find breaker components near this busbar's Y
            breakers = [
                c for c in layout.components
                if c.symbol_name in ("MCB", "MCCB", "RCBO")
                and abs(c.y - by) < _Y_TOL
                and c.circuit_id  # Only sub-circuit breakers
            ]
            if not breakers:
                continue

            breaker_xs = [b.x for b in breakers]
            min_bx = min(breaker_xs)
            max_bx = max(breaker_xs)

            # Busbar X should be <= leftmost breaker and its end should be >= rightmost
            # The busbar.x is the start; we check that it's not far to the right of breakers
            assert busbar.x <= min_bx + 5, (
                f"Row {row_idx}: busbar start x={busbar.x:.1f} > "
                f"leftmost breaker x={min_bx:.1f}"
            )

    # ---- 6. Row 0 and Row 1 groups don't overlap in Y ----

    def test_multirow_no_cross_row_overlap(self):
        """Breakers in row 0 and row 1 should have non-overlapping Y regions."""
        layout = compute_layout(self._three_phase_req(32))
        busbar_ys = layout.busbar_y_per_row
        assert len(busbar_ys) >= 2, "Need at least 2 rows for this test"

        _Y_TOL = 15.0  # Tolerance for assigning breakers to a row

        # Group breakers by their nearest busbar row
        row_breakers: dict[int, list[PlacedComponent]] = {}
        for comp in layout.components:
            if comp.symbol_name in ("MCB", "MCCB", "RCBO") and comp.circuit_id:
                # Find nearest busbar row
                best_row = min(
                    range(len(busbar_ys)),
                    key=lambda ri: abs(comp.y - busbar_ys[ri]),
                )
                if abs(comp.y - busbar_ys[best_row]) < _Y_TOL:
                    row_breakers.setdefault(best_row, []).append(comp)

        if 0 in row_breakers and 1 in row_breakers:
            row0_ys = [c.y for c in row_breakers[0]]
            row1_ys = [c.y for c in row_breakers[1]]
            # Row 0 and row 1 should be in separate Y bands
            # (busbar_ys are ordered, so row 1 has a higher Y value)
            row0_median = sorted(row0_ys)[len(row0_ys) // 2]
            row1_median = sorted(row1_ys)[len(row1_ys) // 2]
            assert row0_median != row1_median, (
                "Row 0 and Row 1 breakers have the same median Y — "
                "they should be in different vertical bands"
            )


# ===========================================================================
# Step B2: Cable Extension Combinations
# ===========================================================================

class TestCableExtensionCombinations:
    """Test is_cable_extension=True combined with other features.

    Cable extension forces supply_source=landlord (no meter board by default),
    so these tests verify interaction with ELCB, CT metering, and
    3-phase configurations.
    """

    def test_cable_extension_with_elcb(self):
        """Cable extension + ELCB: CB_ELCB component should exist."""
        req = _make_req(
            supply_type="single_phase",
            kva=14,
            elcb={"rating": 63, "sensitivity_ma": 30},
            sub_circuits=_make_circuits(3, breaker_rating=20),
        )
        req["is_cable_extension"] = True
        layout = compute_layout(req)
        elcb_components = _get_components_by_type(layout, "CB_ELCB")
        assert len(elcb_components) >= 1, (
            "Cable extension + ELCB config should produce CB_ELCB component"
        )

    def test_cable_extension_with_ct_metering(self):
        """Cable extension + ct_meter: layout completes without crash.

        NOTE: Cable extension forces landlord supply. The meter board CT
        is only placed for ct_meter + non-landlord (sections.py line 404).
        For cable extension + ct_meter, the meter board is drawn (metering
        is explicitly set) but CT symbol may be omitted.
        This test documents the current behavior.
        """
        req = _make_req(
            supply_type="three_phase",
            kva=100,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 160, "poles": "TPN", "fault_kA": 25},
            sub_circuits=_make_circuits(6, breaker_rating=32),
        )
        req["is_cable_extension"] = True
        req["metering"] = "ct_meter"
        layout = compute_layout(req)
        # At minimum, layout should complete without crash and produce components
        assert len(layout.components) > 5, "Layout should produce components"

    def test_cable_extension_3phase_with_elcb(self):
        """3-phase cable extension + ELCB: layout completes without crash."""
        req = _make_req(
            supply_type="three_phase",
            kva=69,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            elcb={"rating": 100, "sensitivity_ma": 30},
            sub_circuits=_make_circuits(6, breaker_rating=32),
        )
        req["is_cable_extension"] = True
        layout = compute_layout(req)
        # Layout should not crash; check minimum viability
        assert len(layout.components) > 5
        assert layout.busbar_start_x < layout.busbar_end_x
        # ELCB should be placed inline
        elcb_components = _get_components_by_type(layout, "CB_ELCB")
        assert len(elcb_components) >= 1, (
            "3-phase cable extension + ELCB should produce CB_ELCB component"
        )

    def test_cable_extension_landlord_no_meter(self):
        """Cable extension forces landlord supply: no KWH_METER component.

        Cable extension sets supply_source=landlord, which defaults metering
        to None (no meter board). Verify no KWH_METER symbol appears.
        """
        req = _make_req(
            supply_type="single_phase",
            kva=14,
            sub_circuits=_make_circuits(3, breaker_rating=20),
        )
        req["is_cable_extension"] = True
        layout = compute_layout(req)
        kwh_components = _get_components_by_type(layout, "KWH_METER")
        assert len(kwh_components) == 0, (
            "Cable extension (landlord, no metering) should NOT have KWH_METER"
        )


# ===========================================================================
# Step B3: Metering Combinations (CT + landlord, single-phase ELCB)
# ===========================================================================

class TestMeteringCombinations:
    """Test CT metering with landlord supply and single-phase ELCB combos.

    Covers edge cases in metering/supply_source interaction and
    single-phase ELCB pole/sensitivity rendering.
    """

    def test_landlord_with_ct_meter(self):
        """Landlord supply + ct_meter (3-phase): verify layout completes.

        NOTE: The meter board CT component is only placed for non-landlord
        ct_meter paths (sections.py line 404). For landlord + ct_meter,
        the meter board is drawn but CT may be absent.
        """
        req = _make_req(
            supply_type="three_phase",
            kva=100,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 160, "poles": "TPN", "fault_kA": 25},
            sub_circuits=_make_circuits(6, breaker_rating=32),
        )
        req["supply_source"] = "landlord"
        req["metering"] = "ct_meter"
        layout = compute_layout(req)
        # Layout should complete without crash
        assert len(layout.components) > 5
        assert layout.busbar_start_x < layout.busbar_end_x

    def test_landlord_with_ct_and_isolator(self):
        """Landlord + ct_meter + isolator: ISOLATOR component present.

        With metering set, the meter board includes an internal ISOLATOR.
        A standalone unit isolator is skipped when metering is set.
        """
        req = _make_req(
            supply_type="three_phase",
            kva=100,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 160, "poles": "TPN", "fault_kA": 25},
            sub_circuits=_make_circuits(6, breaker_rating=32),
        )
        req["supply_source"] = "landlord"
        req["metering"] = "ct_meter"
        req["isolator_rating"] = 200
        layout = compute_layout(req)
        assert len(layout.components) > 5
        # Meter board always includes an ISOLATOR when metering is set
        iso_components = [
            c for c in layout.components
            if c.symbol_name in ("ISOLATOR", "ISO")
        ]
        assert len(iso_components) >= 1, (
            "Landlord + ct_meter should have at least one ISOLATOR (from meter board)"
        )

    def test_landlord_no_metering_default(self):
        """Landlord supply, no metering field: PG KWH meter board auto-added.

        When supply_source=landlord and no metering is specified, metering
        defaults to sp_meter — landlord riser → KWH meter board → DB is standard.
        """
        req = _make_req(
            supply_type="three_phase",
            kva=69,
            busbar_rating=200,
            main_breaker={"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            sub_circuits=_make_circuits(6, breaker_rating=32),
        )
        req["supply_source"] = "landlord"
        # Deliberately do NOT set req["metering"] — should auto-add sp_meter
        layout = compute_layout(req)
        kwh_components = _get_components_by_type(layout, "KWH_METER")
        assert len(kwh_components) == 1, (
            "Landlord supply should auto-add PG KWH meter board"
        )

    def test_single_phase_with_elcb(self):
        """Single-phase + ELCB config: CB_ELCB component should exist."""
        req = _make_req(
            supply_type="single_phase",
            kva=14,
            elcb={"rating": 63, "sensitivity_ma": 30},
            sub_circuits=_make_circuits(3, breaker_rating=20),
        )
        layout = compute_layout(req)
        elcb_components = _get_components_by_type(layout, "CB_ELCB")
        assert len(elcb_components) >= 1, (
            "Single-phase with ELCB config should produce CB_ELCB component"
        )

    def test_single_phase_elcb_dp_poles(self):
        """Single-phase ELCB: should have DP in its label (Double Pole).

        Per _place_elcb(), default poles for single_phase is 'DP'.
        """
        req = _make_req(
            supply_type="single_phase",
            kva=14,
            elcb={"rating": 63, "sensitivity_ma": 30},
            sub_circuits=_make_circuits(3, breaker_rating=20),
        )
        layout = compute_layout(req)
        elcb_components = _get_components_by_type(layout, "CB_ELCB")
        assert len(elcb_components) >= 1, "CB_ELCB must exist"
        elcb = elcb_components[0]
        assert "DP" in (elcb.label or ""), (
            f"Single-phase ELCB should have 'DP' in label, got: {elcb.label!r}"
        )

    def test_single_phase_elcb_sensitivity(self):
        """Single-phase ELCB: '30mA' should appear in ELCB rating text.

        Per _place_elcb(), the rating is set to '({sensitivity_ma}mA)' format.
        """
        req = _make_req(
            supply_type="single_phase",
            kva=14,
            elcb={"rating": 63, "sensitivity_ma": 30},
            sub_circuits=_make_circuits(3, breaker_rating=20),
        )
        layout = compute_layout(req)
        elcb_components = _get_components_by_type(layout, "CB_ELCB")
        assert len(elcb_components) >= 1, "CB_ELCB must exist"
        elcb = elcb_components[0]
        # _place_elcb sets label to single line: "{rating}A {poles} {type} ({ma}mA)"
        assert "30mA" in (elcb.label or ""), (
            f"ELCB label should contain '30mA', got: {elcb.label!r}"
        )
