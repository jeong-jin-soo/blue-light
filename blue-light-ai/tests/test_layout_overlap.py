"""
Unit tests for SLD Layout overlap resolution pure functions.

Tests cover:
- BoundingBox: AABB overlap detection and area calculation
- _compute_bounding_box: component → bounding box conversion
- _compute_dynamic_spacing: responsive horizontal spacing
- _breaker_half_width: half-width lookup for breaker symbols
- SubCircuitGroup: data integrity
- _normalize_row_spacing: category gaps, ditto, 3-phase normalization
- _update_secondary_busbars: secondary busbar position update
- _expand_busbar_if_needed: busbar expansion logic
- _fit_positions_to_bounds: bounds fitting / compression
- _compute_safe_leader_bounds: cable leader safe boundaries
"""

import pytest
from collections import OrderedDict

from app.sld.layout.overlap import (
    BoundingBox,
    SubCircuitGroup,
    _breaker_half_width,
    _compute_bounding_box,
    _compute_dynamic_spacing,
    _compute_safe_leader_bounds,
    _expand_busbar_if_needed,
    _fit_positions_to_bounds,
    _get_symbol_dims,
    _normalize_row_spacing,
    _update_secondary_busbars,
)
from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent


# =============================================
# BoundingBox
# =============================================

class TestBoundingBox:
    """Axis-aligned bounding box overlap detection."""

    def test_properties(self):
        bb = BoundingBox(x=10, y=20, width=30, height=40)
        assert bb.right == 40
        assert bb.top == 60

    # --- overlaps ---

    def test_no_overlap_horizontal(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=20, y=0, width=10, height=10)
        assert not a.overlaps(b)
        assert not b.overlaps(a)

    def test_no_overlap_vertical(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=0, y=20, width=10, height=10)
        assert not a.overlaps(b)
        assert not b.overlaps(a)

    def test_overlap_partial(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=5, y=5, width=10, height=10)
        assert a.overlaps(b)
        assert b.overlaps(a)

    def test_overlap_contained(self):
        outer = BoundingBox(x=0, y=0, width=20, height=20)
        inner = BoundingBox(x=5, y=5, width=5, height=5)
        assert outer.overlaps(inner)
        assert inner.overlaps(outer)

    def test_touching_edges_not_overlapping(self):
        """Touching edges (not strict overlap) should NOT count as overlapping."""
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=10, y=0, width=10, height=10)
        assert not a.overlaps(b)

    def test_touching_corners_not_overlapping(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=10, y=10, width=10, height=10)
        assert not a.overlaps(b)

    def test_overlap_symmetry(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=3, y=3, width=10, height=10)
        assert a.overlaps(b) == b.overlaps(a)

    def test_identical_boxes_overlap(self):
        a = BoundingBox(x=5, y=5, width=10, height=10)
        assert a.overlaps(a)

    def test_tiny_overlap(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=9.99, y=9.99, width=10, height=10)
        assert a.overlaps(b)

    # --- overlap_area ---

    def test_no_overlap_area_zero(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=20, y=0, width=10, height=10)
        assert a.overlap_area(b) == 0.0

    def test_overlap_area_partial(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=5, y=5, width=10, height=10)
        # Overlap region: x=[5,10], y=[5,10] → 5*5=25
        assert a.overlap_area(b) == 25.0

    def test_overlap_area_contained(self):
        outer = BoundingBox(x=0, y=0, width=20, height=20)
        inner = BoundingBox(x=5, y=5, width=5, height=5)
        assert outer.overlap_area(inner) == 25.0

    def test_overlap_area_symmetry(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=3, y=3, width=10, height=10)
        assert a.overlap_area(b) == b.overlap_area(a)

    def test_overlap_area_identical(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        assert a.overlap_area(a) == 100.0

    def test_touching_edges_area_zero(self):
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=10, y=0, width=10, height=10)
        assert a.overlap_area(b) == 0.0

    def test_negative_coordinates(self):
        a = BoundingBox(x=-10, y=-10, width=20, height=20)
        b = BoundingBox(x=-5, y=-5, width=10, height=10)
        # b is fully contained in a: 10*10=100
        assert a.overlap_area(b) == 100.0

    def test_zero_width_box(self):
        a = BoundingBox(x=0, y=0, width=0, height=10)
        b = BoundingBox(x=0, y=0, width=10, height=10)
        assert a.overlap_area(b) == 0.0
        assert not a.overlaps(b)


# =============================================
# _compute_bounding_box
# =============================================

class TestComputeBoundingBox:
    """Component → bounding box conversion."""

    def test_busbar_returns_none(self):
        comp = PlacedComponent(symbol_name="BUSBAR", x=0, y=0)
        assert _compute_bounding_box(comp) is None

    def test_circuit_id_box(self):
        comp = PlacedComponent(
            symbol_name="CIRCUIT_ID_BOX", x=100, y=200,
            circuit_id="L1S1", rotation=90.0,
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.x < 100  # Centered around x
        assert bb.y == 200
        assert bb.height > 0

    def test_db_info_box(self):
        comp = PlacedComponent(symbol_name="DB_INFO_BOX", x=50, y=100)
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.width == 80
        # Height = abs(rating_offset_y) + rating_char_height + 2
        expected_h = abs(comp.rating_offset_y) + comp.rating_char_height + 2  # 4+1.8+2=7.8
        assert bb.height == expected_h
        # Extends downward from comp.y
        assert bb.y == 100 - expected_h

    def test_mcb_breaker_block_vertical(self):
        comp = PlacedComponent(
            symbol_name="CB_MCB", x=100, y=200,
            label_style="breaker_block", rotation=90.0,
            rating="20A", poles="SPN", breaker_type_str="MCB", fault_kA=6,
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.width > 0
        assert bb.height > 0

    def test_mcb_breaker_block_horizontal(self):
        comp = PlacedComponent(
            symbol_name="CB_MCB", x=100, y=200,
            label_style="breaker_block", rotation=0.0,
            rating="63A", poles="TPN", breaker_type_str="MCB", fault_kA=10,
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None
        # Horizontal labels extend to the right
        assert bb.width > 10

    def test_mccb_breaker_block_wider(self):
        mcb = PlacedComponent(
            symbol_name="CB_MCB", x=100, y=200,
            label_style="breaker_block", rotation=0.0,
            rating="20A", poles="SPN", breaker_type_str="MCB", fault_kA=6,
        )
        mccb = PlacedComponent(
            symbol_name="CB_MCCB", x=100, y=200,
            label_style="breaker_block", rotation=0.0,
            rating="100A", poles="TPN", breaker_type_str="MCCB", fault_kA=25,
        )
        bb_mcb = _compute_bounding_box(mcb)
        bb_mccb = _compute_bounding_box(mccb)
        # MCCB has larger base_offset (16 vs 12)
        assert bb_mccb.width > bb_mcb.width

    def test_standard_symbol(self):
        comp = PlacedComponent(symbol_name="KWH_METER", x=50, y=100)
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.width == 16.0
        assert bb.height == 12.0

    def test_label_horizontal(self):
        comp = PlacedComponent(
            symbol_name="LABEL", x=50, y=100,
            label="METER BOARD", rotation=0.0,
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.width > 0
        assert bb.height > 0

    def test_label_vertical_rotation_90(self):
        comp = PlacedComponent(
            symbol_name="LABEL", x=50, y=100,
            label="1 no. LIGHTS", rotation=90.0,
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None
        # Rotated: width↔height swap
        # Width should be small (line count * char_h)
        # Height should be large (text length * char_w)
        assert bb.height > bb.width

    def test_label_multiline(self):
        comp = PlacedComponent(
            symbol_name="LABEL", x=50, y=100,
            label="LINE ONE\\PLINE TWO", rotation=0.0,
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None
        # 2 lines → taller
        single = PlacedComponent(
            symbol_name="LABEL", x=50, y=100,
            label="LINE ONE", rotation=0.0,
        )
        bb_single = _compute_bounding_box(single)
        assert bb.height > bb_single.height

    def test_empty_label(self):
        comp = PlacedComponent(
            symbol_name="LABEL", x=50, y=100, label="", rotation=0.0,
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None

    def test_unknown_symbol_default_box(self):
        comp = PlacedComponent(symbol_name="UNKNOWN_WIDGET", x=0, y=0)
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.width == 5
        assert bb.height == 5


# =============================================
# _breaker_half_width
# =============================================

class TestBreakerHalfWidth:
    """Half-width lookup for breaker symbol centering.

    Phase 4: values are data-driven from real_symbol_paths.json (width_mm / 2).
    Tests verify consistency with the JSON source.
    """

    def test_mcb(self):
        comp = PlacedComponent(symbol_name="CB_MCB", x=0, y=0)
        assert _breaker_half_width(comp) == 2.5  # 5.0mm / 2 (pin center)

    def test_mccb(self):
        comp = PlacedComponent(symbol_name="CB_MCCB", x=0, y=0)
        assert _breaker_half_width(comp) == 2.75  # 5.5mm / 2 (pin center)

    def test_rccb(self):
        comp = PlacedComponent(symbol_name="CB_RCCB", x=0, y=0)
        assert _breaker_half_width(comp) == 3.25  # 6.5mm / 2 (pin center)

    def test_elcb(self):
        comp = PlacedComponent(symbol_name="CB_ELCB", x=0, y=0)
        assert _breaker_half_width(comp) == 3.25  # 6.5mm / 2 (pin center)

    def test_acb(self):
        comp = PlacedComponent(symbol_name="CB_ACB", x=0, y=0)
        assert _breaker_half_width(comp) == 3.5  # 7.0mm / 2 (pin center)

    def test_unknown_defaults_to_mccb(self):
        comp = PlacedComponent(symbol_name="CB_UNKNOWN", x=0, y=0)
        assert _breaker_half_width(comp) == 2.75  # defaults to MCCB pin center

    def test_values_match_real_symbol_json(self):
        """Verify half-widths are derived from real_symbol_paths.json (Phase 4)."""
        from app.sld.real_symbols import get_symbol_dimensions
        for symbol_name, json_key in [
            ("CB_MCB", "MCB"), ("CB_MCCB", "MCCB"),
            ("CB_RCCB", "RCCB"), ("CB_ELCB", "ELCB"), ("CB_ACB", "ACB"),
        ]:
            dims = get_symbol_dimensions(json_key)
            expected = dims["width_mm"] / 2.0
            comp = PlacedComponent(symbol_name=symbol_name, x=0, y=0)
            assert _breaker_half_width(comp) == expected, (
                f"{symbol_name}: half_width={_breaker_half_width(comp)} "
                f"but JSON says {expected}"
            )


# =============================================
# SubCircuitGroup dataclass
# =============================================

class TestSubCircuitGroup:
    """SubCircuitGroup data integrity."""

    def test_defaults(self):
        g = SubCircuitGroup(tap_x=100.0)
        assert g.tap_x == 100.0
        assert g.breaker_idx is None
        assert g.is_spare is False
        assert g.is_ditto is False
        assert g.min_width == 25.0
        assert g.connection_indices == []
        assert g.row_idx == 0

    def test_with_breaker(self):
        g = SubCircuitGroup(tap_x=150.0, breaker_idx=5, is_spare=True)
        assert g.breaker_idx == 5
        assert g.is_spare is True


# =============================================
# _compute_dynamic_spacing (overlap.py version)
# =============================================

class TestComputeDynamicSpacingOverlap:
    """Dynamic spacing from overlap module (same function, re-exported)."""

    def test_returns_float(self):
        config = LayoutConfig()
        result = _compute_dynamic_spacing(10, config)
        assert isinstance(result, float)

    def test_monotonically_decreasing(self):
        """More circuits → smaller spacing (monotonic decrease)."""
        config = LayoutConfig()
        spacings = [_compute_dynamic_spacing(n, config) for n in [3, 6, 10, 15, 20, 25]]
        for i in range(len(spacings) - 1):
            assert spacings[i] >= spacings[i + 1]

    def test_clamped_to_bounds(self):
        config = LayoutConfig()
        for n in range(1, 40):
            s = _compute_dynamic_spacing(n, config)
            if n > 1:
                assert config.min_horizontal_spacing <= s <= config.max_horizontal_spacing


# =============================================
# _get_symbol_dims (single-source-of-truth)
# =============================================

class TestSymbolDims:
    """Symbol dimensions loaded from JSON with fallback."""

    def test_symbol_dims_from_json(self):
        """Verify dims match real_symbol_paths.json values."""
        from app.sld.real_symbols import get_symbol_dimensions

        dims = _get_symbol_dims()
        for overlap_key, json_key in [
            ("CB_MCB", "MCB"), ("CB_MCCB", "MCCB"), ("CB_ACB", "ACB"),
            ("CB_ELCB", "ELCB"), ("CB_RCCB", "RCCB"),
            ("ISOLATOR", "ISOLATOR"), ("KWH_METER", "KWH_METER"),
            ("CT", "CT"), ("EARTH", "EARTH"),
        ]:
            json_d = get_symbol_dimensions(json_key)
            assert dims[overlap_key] == (json_d["width_mm"], json_d["height_mm"]), (
                f"{overlap_key} mismatch: overlap={dims[overlap_key]} vs JSON={json_d}"
            )

    def test_symbol_dims_contains_all_keys(self):
        """Verify all required keys exist in the dims dict."""
        dims = _get_symbol_dims()
        for key in ["CB_MCB", "CB_MCCB", "CB_ACB", "CB_ELCB", "CB_RCCB",
                     "ISOLATOR", "KWH_METER", "CT", "EARTH",
                     "CIRCUIT_ID_BOX", "DB_INFO_BOX", "FLOW_ARROW_UP"]:
            assert key in dims, f"Missing key: {key}"


# =============================================
# _normalize_row_spacing (Step C1)
# =============================================

def _make_breaker_comp(circuit_id, symbol_name="CB_MCB", **kw):
    """Helper: create a breaker_block PlacedComponent."""
    return PlacedComponent(
        symbol_name=symbol_name, x=100, y=200,
        label_style="breaker_block", rotation=90.0,
        circuit_id=circuit_id,
        rating=kw.get("rating", "20A"),
        poles=kw.get("poles", "SPN"),
        breaker_type_str=kw.get("breaker_type_str", "MCB"),
        fault_kA=kw.get("fault_kA", 6),
        breaker_characteristic=kw.get("breaker_characteristic", "C"),
    )


class TestNormalizeRowSpacing:
    """_normalize_row_spacing: category gaps, ditto, 3-phase normalization."""

    def test_ditto_detection(self):
        """Identical breaker specs → second+ groups marked as ditto."""
        comps = [
            _make_breaker_comp("L1S1", rating="20A", poles="SPN"),
            _make_breaker_comp("L1S2", rating="20A", poles="SPN"),
            _make_breaker_comp("L1S3", rating="20A", poles="SPN"),
        ]
        groups = [
            SubCircuitGroup(tap_x=50, breaker_idx=0),
            SubCircuitGroup(tap_x=75, breaker_idx=1),
            SubCircuitGroup(tap_x=100, breaker_idx=2),
        ]
        _normalize_row_spacing(groups, comps)
        assert not groups[0].is_ditto  # First is NOT ditto
        assert groups[1].is_ditto
        assert groups[2].is_ditto

    def test_no_ditto_different_specs(self):
        """Different breaker specs → no ditto."""
        comps = [
            _make_breaker_comp("L1S1", rating="20A"),
            _make_breaker_comp("L1S2", rating="32A"),
        ]
        groups = [
            SubCircuitGroup(tap_x=50, breaker_idx=0),
            SubCircuitGroup(tap_x=75, breaker_idx=1),
        ]
        _normalize_row_spacing(groups, comps)
        assert not groups[0].is_ditto
        assert not groups[1].is_ditto

    def test_3phase_uniform_extent(self):
        """6+ phase IDs → all groups get uniform extents."""
        comps = [_make_breaker_comp(f"L{(i % 3) + 1}S{(i // 3) + 1}") for i in range(6)]
        groups = [SubCircuitGroup(tap_x=50 + i * 25, breaker_idx=i) for i in range(6)]
        _normalize_row_spacing(groups, comps)
        # All extents should be equal
        lefts = {g.left_extent for g in groups}
        rights = {g.right_extent for g in groups}
        assert len(lefts) == 1, f"Expected uniform left_extent, got {lefts}"
        assert len(rights) == 1, f"Expected uniform right_extent, got {rights}"

    def test_section_gap_at_s_to_p_transition(self):
        """Section boundary (S→P) at triplet boundary gets gap."""
        comps = [
            _make_breaker_comp("L1S1"), _make_breaker_comp("L2S1"), _make_breaker_comp("L3S1"),
            _make_breaker_comp("L1P1"), _make_breaker_comp("L2P1"), _make_breaker_comp("L3P1"),
        ]
        groups = [SubCircuitGroup(tap_x=50 + i * 25, breaker_idx=i) for i in range(6)]
        _normalize_row_spacing(groups, comps)
        # Gap at index 3 (first of power section)
        assert groups[3].gap_before > 0

    def test_no_phase_ids_skips_normalization(self):
        """Fewer than 6 phase IDs → no 3-phase normalization."""
        comps = [
            _make_breaker_comp("S1", rating="20A"),
            _make_breaker_comp("S2", rating="32A"),
            _make_breaker_comp("S3", rating="20A"),
        ]
        groups = [SubCircuitGroup(tap_x=50 + i * 25, breaker_idx=i) for i in range(3)]
        _normalize_row_spacing(groups, comps)
        # Without phase normalization, different ratings get different widths
        # (no uniform override)
        widths = [g.min_width for g in groups]
        # At least the widths should be set (>0)
        assert all(w > 0 for w in widths)


# =============================================
# _update_secondary_busbars (Step C2)
# =============================================

class TestUpdateSecondaryBusbars:
    """_update_secondary_busbars: secondary busbar position update."""

    def test_single_row_noop(self):
        """Single row (row 0 only) → no changes to components."""
        config = LayoutConfig()
        lr = LayoutResult(busbar_y=100, busbar_start_x=20, busbar_end_x=380)
        comp = PlacedComponent(symbol_name="BUSBAR", x=20, y=100, label="100A")
        lr.components.append(comp)

        rows_map = {0: [SubCircuitGroup(tap_x=100, breaker_idx=0, row_idx=0)]}
        groups = [SubCircuitGroup(tap_x=100, breaker_idx=0, row_idx=0)]
        tap_xs = [100.0]

        _update_secondary_busbars(rows_map, groups, tap_xs, lr, config, 50.0)
        # No changes — only row 0 exists
        assert comp.x == 20

    def test_secondary_busbar_covers_row(self):
        """2-row layout → secondary busbar x is updated."""
        config = LayoutConfig()
        lr = LayoutResult(
            busbar_y=100, busbar_start_x=20, busbar_end_x=380,
            busbar_y_per_row=[100.0, 200.0],
        )
        # Row 0 main busbar + Row 1 secondary busbar
        lr.components.append(PlacedComponent(symbol_name="BUSBAR", x=20, y=100, label="100A"))
        lr.components.append(PlacedComponent(symbol_name="BUSBAR", x=20, y=200))

        g0 = SubCircuitGroup(tap_x=100, breaker_idx=0, row_idx=0, left_extent=12, right_extent=12)
        g1 = SubCircuitGroup(tap_x=150, breaker_idx=1, row_idx=1, left_extent=12, right_extent=12)
        rows_map = {0: [g0], 1: [g1]}
        all_groups = [g0, g1]
        all_tap_xs = [100.0, 150.0]

        _update_secondary_busbars(rows_map, all_groups, all_tap_xs, lr, config, 50.0)
        # Row 1 BUSBAR component should have x updated
        row1_busbar = lr.components[1]
        assert row1_busbar.x < 150  # Moved to cover circuits


# =============================================
# _expand_busbar_if_needed (Step C3)
# =============================================

class TestExpandBusbarIfNeeded:
    """_expand_busbar_if_needed: busbar expansion logic."""

    def test_no_expansion_when_enough_space(self):
        """Sufficient space → no busbar mutation."""
        config = LayoutConfig()
        lr = LayoutResult(busbar_y=100, busbar_start_x=20, busbar_end_x=380)
        orig_start = lr.busbar_start_x
        orig_end = lr.busbar_end_x

        start, end = _expand_busbar_if_needed(
            100, lr, config, 30, 370, 10,
        )
        assert lr.busbar_start_x == orig_start
        assert lr.busbar_end_x == orig_end

    def test_rightward_expansion(self):
        """Insufficient space → busbar extends rightward."""
        config = LayoutConfig()
        lr = LayoutResult(busbar_y=100, busbar_start_x=20, busbar_end_x=200)
        start, end = _expand_busbar_if_needed(
            500, lr, config, 30, 190, 10,
        )
        assert lr.busbar_end_x > 200
        assert end > 190


# =============================================
# _fit_positions_to_bounds (Step C3)
# =============================================

class TestFitPositionsToBounds:
    """_fit_positions_to_bounds: bounds fitting / compression."""

    def test_positions_within_bounds_unchanged(self):
        """Positions already within bounds → no change."""
        config = LayoutConfig()
        positions = [100.0, 150.0, 200.0]
        result = _fit_positions_to_bounds(positions, config)
        assert result == positions

    def test_shift_right_when_too_far_left(self):
        """Positions below min_x → shifted right."""
        config = LayoutConfig()
        positions = [5.0, 30.0, 55.0]
        result = _fit_positions_to_bounds(positions, config)
        assert min(result) >= config.min_x + 20  # bound_margin=20

    def test_compression_when_span_exceeds_bounds(self):
        """Span wider than available → proportional compression."""
        config = LayoutConfig()
        # Create positions spanning wider than max_x - min_x - 2*bound_margin
        positions = [0.0, 500.0]
        result = _fit_positions_to_bounds(positions, config)
        span = result[-1] - result[0]
        avail = (config.max_x - 20) - (config.min_x + 20)
        assert span <= avail + 0.1

    def test_single_position_clamped(self):
        """Single position clamped to bounds."""
        config = LayoutConfig()
        result = _fit_positions_to_bounds([0.0], config)
        assert result[0] >= config.min_x + 20


# =============================================
# _compute_safe_leader_bounds (Step C4)
# =============================================

class TestComputeSafeLeaderBounds:
    """_compute_safe_leader_bounds: cable leader safe boundaries."""

    def test_no_spares_full_width(self):
        """No SPARE circuits → full drawing width available."""
        config = LayoutConfig()
        cable_groups = OrderedDict([("2.5mm CABLE", [100.0, 150.0])])
        safe_left, safe_right = _compute_safe_leader_bounds(
            100.0, 150.0, [], cable_groups, 0, config,
        )
        assert safe_left == config.min_x
        assert safe_right == config.max_x

    def test_spare_clamps_left(self):
        """SPARE circuit to the left → safe_left clamped."""
        config = LayoutConfig()
        cable_groups = OrderedDict([("2.5mm CABLE", [100.0, 150.0])])
        spare_tap_xs = [80.0]  # SPARE at x=80
        safe_left, safe_right = _compute_safe_leader_bounds(
            100.0, 150.0, spare_tap_xs, cable_groups, 0, config,
        )
        assert safe_left >= 80.0 + 5.0  # SPARE_GAP = 5.0

    def test_adjacent_group_clamps(self):
        """Adjacent cable group → boundary clamped."""
        config = LayoutConfig()
        cable_groups = OrderedDict([
            ("2.5mm CABLE", [100.0, 150.0]),
            ("4.0mm CABLE", [200.0, 250.0]),
        ])
        # For group 0, group 1 is to the right
        safe_left, safe_right = _compute_safe_leader_bounds(
            100.0, 150.0, [], cable_groups, 0, config,
        )
        assert safe_right <= 200.0  # Clamped by adjacent group
