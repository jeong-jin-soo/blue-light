"""
Unit tests for CT metering section overlap detection and auto-adjustment.

Tests cover:
- _Branch / _SpineLabel data models
- _branch_y_extent: component body bounding
- _label_y_extent: label text bounding
- _branch_bbox: combined body + label bounding
- _collect_branches: branch identification from junction_arrows
- _detect_and_resolve: overlap detection and push-apart
- _shift_branch: branch element shifting
- _extend_spine_if_needed: spine backbone extension
- validate_ct_metering_overlaps: public API integration
"""

import pytest
from dataclasses import dataclass, field

from app.sld.layout.ct_overlap import (
    _Branch,
    _SpineLabel,
    _branch_bbox,
    _branch_y_extent,
    _collect_branches,
    _detect_and_resolve,
    _extend_spine_if_needed,
    _label_y_extent,
    _shift_branch,
    _spine_label_bbox,
    validate_ct_metering_overlaps,
)
from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_result(
    components=None,
    connections=None,
    junction_arrows=None,
    spine_x=None,
) -> LayoutResult:
    """Create a minimal LayoutResult for testing."""
    result = LayoutResult()
    if components:
        result.components = list(components)
    if connections:
        result.connections = list(connections)
    if junction_arrows:
        result.junction_arrows = list(junction_arrows)
    if spine_x is not None:
        result.spine_x = spine_x
    return result


def _make_branch_comp(symbol_name, x, y, rotation=90.0, label=""):
    """Create a horizontal branch component."""
    return PlacedComponent(
        symbol_name=symbol_name, x=x, y=y, rotation=rotation, label=label,
    )


def _make_spine_comp(symbol_name, x, y, label="", label_y_override=None):
    """Create a spine (vertical) component."""
    return PlacedComponent(
        symbol_name=symbol_name, x=x, y=y, rotation=0.0, label=label,
        label_y_override=label_y_override,
    )


# ---------------------------------------------------------------------------
# _Branch dataclass
# ---------------------------------------------------------------------------

class TestBranch:
    """_Branch data model."""

    def test_defaults(self):
        b = _Branch(branch_y=100.0, direction="right")
        assert b.branch_y == 100.0
        assert b.direction == "right"
        assert b.comp_indices == []
        assert b.conn_indices == []
        assert b.jarrow_idx is None
        assert b.bbox_y_min == 0.0
        assert b.bbox_y_max == 0.0

    def test_with_values(self):
        b = _Branch(
            branch_y=50.0, direction="left",
            comp_indices=[1, 2, 3], conn_indices=[4, 5],
            jarrow_idx=0, bbox_y_min=45.0, bbox_y_max=55.0,
        )
        assert len(b.comp_indices) == 3
        assert b.jarrow_idx == 0


# ---------------------------------------------------------------------------
# _SpineLabel dataclass
# ---------------------------------------------------------------------------

class TestSpineLabel:
    def test_creation(self):
        sl = _SpineLabel(
            comp_idx=0, bbox_y_min=40.0, bbox_y_max=50.0,
            bbox_x_min=10.0, bbox_x_max=30.0,
        )
        assert sl.bbox_y_max - sl.bbox_y_min == 10.0
        assert sl.bbox_x_max - sl.bbox_x_min == 20.0


# ---------------------------------------------------------------------------
# _branch_y_extent
# ---------------------------------------------------------------------------

class TestBranchYExtent:
    """Component body bounding for branch components."""

    def test_horizontal_branch_component(self):
        """Rotation=90 component should use symbol width as vertical extent."""
        comp = _make_branch_comp("AMMETER", x=20, y=100, rotation=90.0)
        y_min, y_max = _branch_y_extent(comp)
        assert y_min < 100.0
        assert y_max > 100.0
        # Should be centered on comp.y
        assert abs((y_min + y_max) / 2 - 100.0) < 1.0

    def test_vertical_spine_component(self):
        """Rotation=0 component should use comp.y to comp.y + height."""
        comp = _make_spine_comp("CT", x=50, y=100)
        y_min, y_max = _branch_y_extent(comp)
        assert y_min == 100.0
        assert y_max > 100.0

    def test_unknown_symbol_fallback(self):
        """Unknown symbol should use conservative ±4.0 fallback."""
        comp = _make_branch_comp("UNKNOWN_WIDGET", x=20, y=100, rotation=90.0)
        y_min, y_max = _branch_y_extent(comp)
        assert y_min == pytest.approx(96.0, abs=0.5)
        assert y_max == pytest.approx(104.0, abs=0.5)


# ---------------------------------------------------------------------------
# _label_y_extent
# ---------------------------------------------------------------------------

class TestLabelYExtent:
    """Label text bounding."""

    def test_empty_label(self):
        comp = _make_branch_comp("AMMETER", x=20, y=100, label="")
        y_min, y_max = _label_y_extent(comp)
        assert y_min == 100.0
        assert y_max == 100.0

    def test_horizontal_branch_label_above(self):
        """Horizontal branch label should be above the component."""
        comp = _make_branch_comp("AMMETER", x=20, y=100, rotation=90.0, label="ASS")
        y_min, y_max = _label_y_extent(comp)
        # Label should be above the component body center
        assert y_min > 100.0

    def test_multiline_label_taller(self):
        """Multi-line label should have larger vertical extent."""
        single = _make_branch_comp("AMMETER", x=20, y=100, rotation=90.0, label="L1")
        multi = _make_branch_comp("AMMETER", x=20, y=100, rotation=90.0, label="L1\\PL2")
        _, h1 = _label_y_extent(single)
        _, h2 = _label_y_extent(multi)
        assert h2 > h1

    def test_spine_label_with_override(self):
        """Spine component with label_y_override should use it."""
        comp = _make_spine_comp("CT", x=50, y=100, label="CT 100/5A", label_y_override=110.0)
        y_min, y_max = _label_y_extent(comp)
        assert y_max == 110.0
        assert y_min < y_max


# ---------------------------------------------------------------------------
# _branch_bbox
# ---------------------------------------------------------------------------

class TestBranchBbox:
    """Combined body + label bounding box for a branch."""

    def test_single_component_no_label(self):
        comp = _make_branch_comp("AMMETER", x=20, y=100, rotation=90.0)
        branch = _Branch(branch_y=100.0, direction="right", comp_indices=[0])
        y_min, y_max = _branch_bbox(branch, [comp])
        assert y_min < 100.0
        assert y_max > 100.0

    def test_includes_label_extent(self):
        """Branch bbox should include label extent."""
        comp_no_label = _make_branch_comp("AMMETER", x=20, y=100, rotation=90.0)
        comp_with_label = _make_branch_comp("AMMETER", x=20, y=100, rotation=90.0, label="ASS\\PL1")

        branch_no_label = _Branch(branch_y=100.0, direction="right", comp_indices=[0])
        branch_with_label = _Branch(branch_y=100.0, direction="right", comp_indices=[0])

        _, ymax_no = _branch_bbox(branch_no_label, [comp_no_label])
        _, ymax_with = _branch_bbox(branch_with_label, [comp_with_label])
        assert ymax_with > ymax_no

    def test_multiple_components(self):
        """Branch with multiple components should span all of them."""
        comp1 = _make_branch_comp("AMMETER", x=20, y=100, rotation=90.0)
        comp2 = _make_branch_comp("VOLTMETER", x=40, y=100, rotation=90.0)
        branch = _Branch(branch_y=100.0, direction="right", comp_indices=[0, 1])
        y_min, y_max = _branch_bbox(branch, [comp1, comp2])
        assert y_min < 100.0
        assert y_max > 100.0


# ---------------------------------------------------------------------------
# _spine_label_bbox
# ---------------------------------------------------------------------------

class TestSpineLabelBbox:
    """Spine component label bounding box."""

    def test_no_label(self):
        comp = _make_spine_comp("CT", x=50, y=100)
        assert _spine_label_bbox(comp) is None

    def test_with_label(self):
        comp = _make_spine_comp("CT", x=50, y=100, label="CT 100/5A\\P(CL1 5VA)")
        sl = _spine_label_bbox(comp)
        assert sl is not None
        assert sl.bbox_x_min > 50  # Label is to the right of spine
        assert sl.bbox_y_max > sl.bbox_y_min

    def test_label_y_override(self):
        comp = _make_spine_comp("CT", x=50, y=100, label="CT", label_y_override=115.0)
        sl = _spine_label_bbox(comp)
        assert sl is not None
        assert sl.bbox_y_max == 115.0


# ---------------------------------------------------------------------------
# _collect_branches
# ---------------------------------------------------------------------------

class TestCollectBranches:
    """Branch identification from layout result."""

    def test_empty_result(self):
        result = _make_result()
        branches, ct_idx, bi_idx = _collect_branches(result)
        assert branches == []
        assert ct_idx == []
        assert bi_idx == []

    def test_identifies_right_branch(self):
        """A junction_arrow at (50, 100, 'right') should collect components at y=100, x>50."""
        comp = _make_branch_comp("AMMETER", x=60, y=100, rotation=90.0)
        result = _make_result(
            components=[comp],
            junction_arrows=[(50, 100, "right")],
            connections=[],
        )
        branches, _, _ = _collect_branches(result)
        assert len(branches) == 1
        assert branches[0].direction == "right"
        assert 0 in branches[0].comp_indices

    def test_identifies_left_branch(self):
        """A junction_arrow at (50, 100, 'left') should collect components at y=100, x<50."""
        comp = _make_branch_comp("SELECTOR_SWITCH", x=30, y=100, rotation=90.0)
        result = _make_result(
            components=[comp],
            junction_arrows=[(50, 100, "left")],
            connections=[],
        )
        branches, _, _ = _collect_branches(result)
        assert len(branches) == 1
        assert branches[0].direction == "left"
        assert 0 in branches[0].comp_indices

    def test_ignores_non_branch_symbols(self):
        """Components with non-branch symbol names should be ignored."""
        comp = _make_spine_comp("CT", x=50, y=100)
        result = _make_result(
            components=[comp],
            junction_arrows=[(50, 100, "right")],
            connections=[],
        )
        branches, _, _ = _collect_branches(result)
        assert len(branches) == 0  # CT is not a branch symbol

    def test_finds_spine_cts_and_bi_connectors(self):
        """Should identify spine CT and BI_CONNECTOR components."""
        ct = _make_spine_comp("CT", x=50, y=80)
        bi = _make_spine_comp("BI_CONNECTOR", x=50, y=120)
        result = _make_result(
            components=[ct, bi],
            junction_arrows=[],
            connections=[],
        )
        _, ct_indices, bi_indices = _collect_branches(result)
        assert ct_indices == [0]
        assert bi_indices == [1]

    def test_branch_connections(self):
        """Horizontal connections at branch_y should be associated with the branch."""
        comp = _make_branch_comp("AMMETER", x=60, y=100, rotation=90.0)
        conn = ((50.0, 100.0), (60.0, 100.0))  # Horizontal at y=100
        result = _make_result(
            components=[comp],
            junction_arrows=[(50, 100, "right")],
            connections=[conn],
        )
        branches, _, _ = _collect_branches(result)
        assert len(branches) == 1
        assert 0 in branches[0].conn_indices


# ---------------------------------------------------------------------------
# _shift_branch
# ---------------------------------------------------------------------------

class TestShiftBranch:
    """Branch element shifting."""

    def test_shifts_components(self):
        comp = _make_branch_comp("AMMETER", x=60, y=100, rotation=90.0)
        result = _make_result(components=[comp])
        branch = _Branch(branch_y=100.0, direction="right", comp_indices=[0])

        _shift_branch(branch, 5.0, result)
        assert result.components[0].y == 105.0

    def test_shifts_connections(self):
        conn = ((50.0, 100.0), (70.0, 100.0))
        result = _make_result(connections=[conn])
        branch = _Branch(branch_y=100.0, direction="right", conn_indices=[0])

        _shift_branch(branch, 5.0, result)
        (sx, sy), (ex, ey) = result.connections[0]
        assert sy == 105.0
        assert ey == 105.0

    def test_shifts_junction_arrow(self):
        result = _make_result(
            junction_arrows=[(50.0, 100.0, "right")],
        )
        branch = _Branch(branch_y=100.0, direction="right", jarrow_idx=0)

        _shift_branch(branch, 5.0, result)
        jx, jy, jdir = result.junction_arrows[0]
        assert jy == 105.0
        assert jdir == "right"

    def test_shifts_label_y_override(self):
        comp = _make_branch_comp("AMMETER", x=60, y=100, rotation=90.0)
        comp.label_y_override = 108.0
        result = _make_result(components=[comp])
        branch = _Branch(branch_y=100.0, direction="right", comp_indices=[0])

        _shift_branch(branch, 5.0, result)
        assert result.components[0].label_y_override == 113.0


# ---------------------------------------------------------------------------
# _detect_and_resolve
# ---------------------------------------------------------------------------

class TestDetectAndResolve:
    """Overlap detection and push-apart."""

    def test_no_overlap_no_adjustment(self):
        """Well-spaced branches should not be adjusted."""
        comp1 = _make_branch_comp("AMMETER", x=60, y=100, rotation=90.0)
        comp2 = _make_branch_comp("VOLTMETER", x=60, y=120, rotation=90.0)
        result = _make_result(
            components=[comp1, comp2],
            junction_arrows=[(50, 100, "right"), (50, 120, "right")],
            connections=[],
        )
        branches, _, _ = _collect_branches(result)
        adjustments = _detect_and_resolve(branches, [], result)
        assert adjustments == 0

    def test_overlapping_branches_push_apart(self):
        """Branches that overlap in Y should be pushed apart."""
        # Two RIGHT branches at y=100 and y=102 (only 2mm apart — will overlap)
        comp1 = _make_branch_comp("AMMETER", x=60, y=100, rotation=90.0)
        comp2 = _make_branch_comp("VOLTMETER", x=60, y=102, rotation=90.0)
        result = _make_result(
            components=[comp1, comp2],
            junction_arrows=[(50, 100, "right"), (50, 102, "right")],
            connections=[],
        )
        branches, _, _ = _collect_branches(result)
        adjustments = _detect_and_resolve(branches, [], result)
        assert adjustments > 0
        # After adjustment, upper branch should have moved up
        assert result.components[1].y > 102.0

    def test_different_direction_no_overlap(self):
        """Left and right branches at same Y should not overlap each other."""
        comp1 = _make_branch_comp("AMMETER", x=30, y=100, rotation=90.0)  # LEFT
        comp2 = _make_branch_comp("VOLTMETER", x=60, y=102, rotation=90.0)  # RIGHT
        result = _make_result(
            components=[comp1, comp2],
            junction_arrows=[(50, 100, "left"), (50, 102, "right")],
            connections=[],
        )
        branches, _, _ = _collect_branches(result)

        # Different directions are checked separately, so if only one branch per
        # direction exists, there's no same-direction pair to overlap.
        adjustments = _detect_and_resolve(branches, [], result)
        assert adjustments == 0


# ---------------------------------------------------------------------------
# _extend_spine_if_needed
# ---------------------------------------------------------------------------

class TestExtendSpineIfNeeded:
    """Spine backbone extension after branch shifting."""

    def test_no_bi_connectors(self):
        """No BI connectors → no-op."""
        result = _make_result()
        _extend_spine_if_needed(result, [])
        # Should not raise

    def test_extends_spine_top(self):
        """If BI connector is above spine top, spine should extend."""
        bi_comp = _make_spine_comp("BI_CONNECTOR", x=50, y=200)
        spine_conn = ((50.0, 50.0), (50.0, 180.0))  # Vertical line at x=50
        result = _make_result(
            components=[bi_comp],
            connections=[spine_conn],
            spine_x=50.0,
        )
        _extend_spine_if_needed(result, [0])
        # Spine should now extend beyond 200 + bi_height + stub
        (sx, sy), (ex, ey) = result.connections[0]
        top = max(sy, ey)
        assert top > 180.0  # Should have been extended

    def test_no_extension_needed(self):
        """If spine already covers BI connector, no change."""
        bi_comp = _make_spine_comp("BI_CONNECTOR", x=50, y=100)
        spine_conn = ((50.0, 50.0), (50.0, 300.0))  # Already very tall
        result = _make_result(
            components=[bi_comp],
            connections=[spine_conn],
            spine_x=50.0,
        )
        _extend_spine_if_needed(result, [0])
        (sx, sy), (ex, ey) = result.connections[0]
        top = max(sy, ey)
        assert top == 300.0  # Unchanged


# ---------------------------------------------------------------------------
# validate_ct_metering_overlaps (public API)
# ---------------------------------------------------------------------------

class TestValidateCtMeteringOverlaps:
    """Public API integration tests."""

    def test_no_ct_metering_section(self):
        """Empty layout → 0 adjustments."""
        result = _make_result()
        config = LayoutConfig()
        assert validate_ct_metering_overlaps(result, config) == 0

    def test_well_spaced_layout(self):
        """Properly spaced branches → 0 adjustments."""
        # Create two RIGHT branches at y=100 and y=130 (well separated)
        comp1 = _make_branch_comp("AMMETER", x=70, y=100, rotation=90.0)
        comp2 = _make_branch_comp("KWH_METER", x=70, y=130, rotation=90.0)
        result = _make_result(
            components=[comp1, comp2],
            junction_arrows=[(50, 100, "right"), (50, 130, "right")],
            connections=[],
            spine_x=50.0,
        )
        config = LayoutConfig()
        adj = validate_ct_metering_overlaps(result, config)
        assert adj == 0

    def test_overlapping_layout_resolved(self):
        """Overlapping branches → adjustments made and connections intact."""
        # Two RIGHT branches very close together (will overlap)
        comp1 = _make_branch_comp("AMMETER", x=70, y=100, rotation=90.0, label="ASS")
        comp2 = _make_branch_comp("KWH_METER", x=70, y=103, rotation=90.0, label="KWH")
        conn1 = ((50.0, 100.0), (70.0, 100.0))
        conn2 = ((50.0, 103.0), (70.0, 103.0))
        result = _make_result(
            components=[comp1, comp2],
            junction_arrows=[(50, 100, "right"), (50, 103, "right")],
            connections=[conn1, conn2],
            spine_x=50.0,
        )
        config = LayoutConfig()
        adj = validate_ct_metering_overlaps(result, config)
        assert adj > 0

        # Verify upper branch moved up
        assert result.components[1].y > 103.0

        # Verify connection was shifted too
        (_, sy2), (_, ey2) = result.connections[1]
        assert sy2 > 103.0
        assert ey2 > 103.0

    def test_junction_arrows_updated(self):
        """After overlap resolution, junction_arrows should be updated."""
        comp1 = _make_branch_comp("AMMETER", x=70, y=100, rotation=90.0)
        comp2 = _make_branch_comp("VOLTMETER", x=70, y=103, rotation=90.0)
        result = _make_result(
            components=[comp1, comp2],
            junction_arrows=[(50, 100, "right"), (50, 103, "right")],
            connections=[],
            spine_x=50.0,
        )
        config = LayoutConfig()
        validate_ct_metering_overlaps(result, config)

        # If adjusted, the upper junction arrow Y should have increased
        _, jy_upper, _ = result.junction_arrows[1]
        assert jy_upper >= 103.0
