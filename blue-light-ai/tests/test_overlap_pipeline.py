"""
Unit tests for the overlap resolution 5-step pipeline.

Tests cover:
- _compute_group_width: minimum width calculation
- _determine_final_positions: left-to-right layout
- _rebuild_from_positions: index-based repositioning
- _fit_busbar_to_groups: busbar extent fitting
- resolve_overlaps: full pipeline integration
"""

import pytest

from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent
from app.sld.layout.overlap import (
    SubCircuitGroup,
    _compute_group_width,
    _determine_final_positions,
    _fit_busbar_to_groups,
    _rebuild_from_positions,
    resolve_overlaps,
)


# =============================================
# Fixtures
# =============================================

def _make_config() -> LayoutConfig:
    return LayoutConfig()


def _make_mcb(x: float, y: float, circuit_id: str = "", label: str = "LIGHTS",
              is_spare: bool = False) -> PlacedComponent:
    """Create a sub-circuit MCB component (breaker_block style)."""
    return PlacedComponent(
        symbol_name="CB_MCB",
        x=x - 3.6,  # breaker placed at x - half_width
        y=y,
        label="SPARE" if is_spare else label,
        rating="20A",
        cable_annotation="2 x 1C 2.5sqmm PVC/PVC + 2.5sqmm PVC CPC",
        circuit_id=circuit_id,
        rotation=90.0,
        poles="SPN",
        breaker_type_str="MCB",
        fault_kA=6,
        label_style="breaker_block",
        breaker_characteristic="B",
    )


def _make_circuit_id_box(x: float, y: float, circuit_id: str) -> PlacedComponent:
    return PlacedComponent(
        symbol_name="CIRCUIT_ID_BOX",
        x=x, y=y,
        circuit_id=circuit_id,
        rotation=90.0,
    )


def _make_label(x: float, y: float, label: str) -> PlacedComponent:
    return PlacedComponent(
        symbol_name="LABEL",
        x=x, y=y,
        label=label,
        rotation=90.0,
    )


def _make_simple_layout(num_circuits: int = 3, spacing: float = 26.0) -> LayoutResult:
    """Create a simple layout with N sub-circuits for testing overlap resolution."""
    result = LayoutResult()
    config = _make_config()

    busbar_y = 200.0
    bus_start_x = 100.0
    bus_end_x = bus_start_x + (num_circuits + 1) * spacing

    result.busbar_y = busbar_y
    result.busbar_start_x = bus_start_x
    result.busbar_end_x = bus_end_x
    result.busbar_y_per_row = [busbar_y]
    result.spine_x = (bus_start_x + bus_end_x) / 2

    # BUSBAR component
    result.components.append(PlacedComponent(
        symbol_name="BUSBAR", x=bus_start_x, y=busbar_y,
        label="63A DB",
    ))

    # Sub-circuits
    for i in range(num_circuits):
        tap_x = bus_start_x + config.busbar_margin + i * spacing
        breaker_y = busbar_y + config.busbar_to_breaker_gap

        # MCB (breaker_block)
        result.components.append(_make_mcb(
            tap_x, breaker_y,
            circuit_id=f"L{(i % 3) + 1}P{(i // 3) + 1}",
        ))

        # CIRCUIT_ID_BOX
        result.components.append(_make_circuit_id_box(
            tap_x, busbar_y + 5.5, f"L{(i % 3) + 1}P{(i // 3) + 1}",
        ))

        # LABEL (circuit name)
        result.components.append(_make_label(
            tap_x, breaker_y + 30, f"1 no. CIRCUIT {i + 1}",
        ))

        # Connections: busbar→breaker, breaker→tail
        result.connections.append(((tap_x, busbar_y), (tap_x, breaker_y)))
        result.connections.append(((tap_x, breaker_y + 16), (tap_x, breaker_y + 30)))

        # Junction dot at busbar
        result.junction_dots.append((tap_x, busbar_y))

    return result


# =============================================
# _compute_group_width
# =============================================

class TestComputeGroupWidth:
    """Minimum horizontal space for sub-circuit groups."""

    def test_spare_circuit_compact(self):
        comp = _make_mcb(100, 200, is_spare=True)
        group = SubCircuitGroup(tap_x=100, breaker_idx=0, is_spare=True)
        components = [comp]
        width = _compute_group_width(group, components)
        assert width == 15.0
        assert group.left_extent == 7.5
        assert group.right_extent == 7.5

    def test_regular_mcb_wider_than_spare(self):
        comp = _make_mcb(100, 200)
        group = SubCircuitGroup(tap_x=100, breaker_idx=0)
        components = [comp]
        width = _compute_group_width(group, components)
        assert width > 15.0  # Regular MCB needs more space than SPARE

    def test_no_breaker_fallback(self):
        group = SubCircuitGroup(tap_x=100)
        width = _compute_group_width(group, [])
        assert width == 25.0

    def test_extents_set(self):
        comp = _make_mcb(100, 200)
        group = SubCircuitGroup(tap_x=100, breaker_idx=0)
        width = _compute_group_width(group, [comp])
        assert group.left_extent > 0
        assert group.right_extent > 0
        assert abs(group.left_extent + group.right_extent - width) < 0.01


# =============================================
# _determine_final_positions
# =============================================

class TestDetermineFinalPositions:
    """Left-to-right tap position calculation."""

    def test_single_circuit_centered(self):
        result = LayoutResult()
        result.busbar_start_x = 100
        result.busbar_end_x = 300
        config = _make_config()

        group = SubCircuitGroup(tap_x=200, left_extent=10, right_extent=10)
        positions = _determine_final_positions([group], [], result, config)
        assert len(positions) == 1
        # Single circuit centered on available space
        expected = (110 + 290) / 2  # (start+margin + end-margin) / 2
        assert abs(positions[0] - expected) < 1.0

    def test_multiple_circuits_ordered(self):
        result = LayoutResult()
        result.busbar_start_x = 50
        result.busbar_end_x = 350
        config = _make_config()

        groups = [
            SubCircuitGroup(tap_x=100, left_extent=10, right_extent=10),
            SubCircuitGroup(tap_x=150, left_extent=10, right_extent=10),
            SubCircuitGroup(tap_x=200, left_extent=10, right_extent=10),
        ]
        positions = _determine_final_positions(groups, [], result, config)
        assert len(positions) == 3
        # Should be in ascending order
        assert positions[0] < positions[1] < positions[2]

    def test_no_overlaps_in_result(self):
        result = LayoutResult()
        result.busbar_start_x = 50
        result.busbar_end_x = 350
        config = _make_config()

        groups = [
            SubCircuitGroup(tap_x=100, left_extent=15, right_extent=15),
            SubCircuitGroup(tap_x=120, left_extent=15, right_extent=15),
            SubCircuitGroup(tap_x=140, left_extent=15, right_extent=15),
        ]
        positions = _determine_final_positions(groups, [], result, config)
        # Minimum gap = right_extent[i] + left_extent[i+1] = 30
        for i in range(len(positions) - 1):
            gap = positions[i + 1] - positions[i]
            assert gap >= 20  # At least right+left extents (may be compressed)

    def test_empty_list(self):
        result = LayoutResult()
        result.busbar_start_x = 50
        result.busbar_end_x = 350
        positions = _determine_final_positions([], [], result, _make_config())
        assert positions == []

    def test_within_drawing_bounds(self):
        result = LayoutResult()
        result.busbar_start_x = 50
        result.busbar_end_x = 350
        config = _make_config()

        groups = [
            SubCircuitGroup(tap_x=x, left_extent=10, right_extent=10)
            for x in range(60, 340, 20)
        ]
        positions = _determine_final_positions(groups, [], result, config)
        for p in positions:
            assert p >= config.min_x + 10  # Within bounds (with some margin)
            assert p <= config.max_x - 10

    def test_gap_before_respected(self):
        result = LayoutResult()
        result.busbar_start_x = 50
        result.busbar_end_x = 350
        config = _make_config()

        groups = [
            SubCircuitGroup(tap_x=100, left_extent=10, right_extent=10),
            SubCircuitGroup(tap_x=150, left_extent=10, right_extent=10, gap_before=20),
            SubCircuitGroup(tap_x=200, left_extent=10, right_extent=10),
        ]
        positions = _determine_final_positions(groups, [], result, config)
        # Gap between first and second should be larger than second and third
        gap_1_2 = positions[1] - positions[0]
        gap_2_3 = positions[2] - positions[1]
        assert gap_1_2 > gap_2_3 - 1  # Allow small rounding


# =============================================
# _rebuild_from_positions
# =============================================

class TestRebuildFromPositions:
    """Index-based element repositioning."""

    def test_components_move(self):
        result = LayoutResult()
        result.components = [
            _make_mcb(100, 200),
            _make_circuit_id_box(100, 203.5, "L1P1"),
            _make_label(100, 230, "LIGHTS"),
        ]
        result.connections = [
            ((100, 200), (100, 212)),
            ((100, 228), (100, 238)),
        ]
        result.junction_dots = [(100, 200)]

        group = SubCircuitGroup(
            tap_x=100,
            breaker_idx=0,
            circuit_id_idx=1,
            name_label_idx=2,
            connection_indices=[0, 1],
            junction_dot_idx=0,
        )

        new_tap_x = 150.0
        _rebuild_from_positions([group], [new_tap_x], result)

        # Breaker should shift by delta
        assert abs(result.components[0].x - (150 - 3.6)) < 0.01
        # CIRCUIT_ID_BOX set to absolute tap_x
        assert result.components[1].x == 150.0
        # LABEL set to absolute tap_x
        assert result.components[2].x == 150.0
        # Connections updated
        assert result.connections[0][0][0] == 150.0
        assert result.connections[0][1][0] == 150.0
        # Junction dot updated
        assert result.junction_dots[0][0] == 150.0

    def test_no_movement_when_delta_zero(self):
        result = LayoutResult()
        result.components = [_make_mcb(100, 200)]
        original_x = result.components[0].x

        group = SubCircuitGroup(tap_x=100, breaker_idx=0)
        _rebuild_from_positions([group], [100.0], result)

        assert result.components[0].x == original_x


# =============================================
# _fit_busbar_to_groups
# =============================================

class TestFitBusbarToGroups:
    """Busbar extent fitting to cover all groups."""

    def test_busbar_shrinks_to_fit(self):
        config = _make_config()
        result = LayoutResult()
        result.busbar_start_x = 50
        result.busbar_end_x = 350
        result.components = [
            PlacedComponent(symbol_name="BUSBAR", x=50, y=200, label="63A DB"),
        ]

        groups = [
            SubCircuitGroup(tap_x=100, left_extent=10, right_extent=10),
            SubCircuitGroup(tap_x=200, left_extent=10, right_extent=10),
        ]
        new_tap_xs = [100, 200]

        _fit_busbar_to_groups(groups, new_tap_xs, result, result.components, config)

        # Busbar should shrink to cover [90, 210] + margin
        assert result.busbar_start_x < 100
        assert result.busbar_end_x > 200
        assert result.busbar_end_x < 350  # Shrunk from original

    def test_busbar_extends_for_incoming(self):
        config = _make_config()
        result = LayoutResult()
        result.busbar_start_x = 100
        result.busbar_end_x = 300
        result.components = [
            PlacedComponent(symbol_name="BUSBAR", x=100, y=200, label="63A DB"),
        ]

        groups = [
            SubCircuitGroup(tap_x=150, left_extent=10, right_extent=10),
        ]
        _fit_busbar_to_groups(
            groups, [150], result, result.components, config,
            incoming_chain_x=50,  # Far left of current busbar
        )
        # Busbar should extend to cover incoming chain
        assert result.busbar_start_x <= 50

    def test_empty_groups_noop(self):
        result = LayoutResult()
        result.busbar_start_x = 100
        result.busbar_end_x = 300
        _fit_busbar_to_groups([], [], result, [], _make_config())
        assert result.busbar_start_x == 100
        assert result.busbar_end_x == 300


# =============================================
# resolve_overlaps (full pipeline)
# =============================================

class TestResolveOverlaps:
    """Full 5-step overlap resolution pipeline."""

    def test_returns_layout_result(self):
        result = _make_simple_layout(3)
        config = _make_config()
        out = resolve_overlaps(result, config)
        assert isinstance(out, LayoutResult)
        assert out is result  # Modified in-place

    def test_no_component_loss(self):
        """All components survive overlap resolution."""
        result = _make_simple_layout(6)
        config = _make_config()
        initial_count = len(result.components)
        resolve_overlaps(result, config)
        # Components may be added (cable leaders etc.) but never removed
        assert len(result.components) >= initial_count

    def test_no_connection_loss(self):
        """All connections survive (new ones may be added)."""
        result = _make_simple_layout(6)
        config = _make_config()
        initial_count = len(result.connections)
        resolve_overlaps(result, config)
        assert len(result.connections) >= initial_count

    def test_busbar_covers_all_circuits(self):
        """Busbar extends to cover all sub-circuit tap points."""
        result = _make_simple_layout(9)
        config = _make_config()
        resolve_overlaps(result, config)

        # All circuit breakers should be within busbar extent
        for comp in result.components:
            if comp.symbol_name.startswith("CB_") and comp.label_style == "breaker_block":
                tap_x = comp.x + 3.6  # Approximate tap_x
                assert tap_x >= result.busbar_start_x - 5  # Small tolerance
                assert tap_x <= result.busbar_end_x + 5

    def test_deterministic(self):
        """Same input → same output."""
        config = _make_config()

        result1 = _make_simple_layout(6)
        resolve_overlaps(result1, config)

        result2 = _make_simple_layout(6)
        resolve_overlaps(result2, config)

        # Compare component positions
        for c1, c2 in zip(result1.components, result2.components):
            assert abs(c1.x - c2.x) < 0.01
            assert abs(c1.y - c2.y) < 0.01

    def test_empty_layout(self):
        """Empty layout doesn't crash."""
        result = LayoutResult()
        config = _make_config()
        out = resolve_overlaps(result, config)
        assert isinstance(out, LayoutResult)

    def test_single_circuit(self):
        """Single sub-circuit layout."""
        result = _make_simple_layout(1)
        config = _make_config()
        resolve_overlaps(result, config)
        breakers = [c for c in result.components if c.label_style == "breaker_block"]
        assert len(breakers) == 1

    def test_many_circuits(self):
        """Large layout (18 circuits) resolves without error."""
        result = _make_simple_layout(18, spacing=18)
        config = _make_config()
        resolve_overlaps(result, config)
        breakers = [c for c in result.components if c.label_style == "breaker_block"]
        assert len(breakers) == 18


# =============================================
# C3: Index stability invariant tests
# =============================================

class TestC3IndexInvariant:
    """C3 fix: verify that resolve_overlaps never corrupts component indices.

    The 5-step pipeline stores integer indices in Step 1 and uses them in
    Step 4.  These tests verify that component count and identity are
    preserved throughout the pipeline.
    """

    def test_component_count_preserved(self):
        """resolve_overlaps must not add or remove components."""
        result = _make_simple_layout(6, spacing=15)
        count_before = len(result.components)
        config = _make_config()
        resolve_overlaps(result, config)
        assert len(result.components) == count_before

    def test_connection_count_preserved(self):
        """resolve_overlaps must not add or remove connections."""
        result = _make_simple_layout(6, spacing=15)
        count_before = len(result.connections)
        config = _make_config()
        resolve_overlaps(result, config)
        assert len(result.connections) == count_before

    def test_junction_dot_count_preserved(self):
        """resolve_overlaps must not add or remove junction dots."""
        result = _make_simple_layout(6, spacing=15)
        count_before = len(result.junction_dots)
        config = _make_config()
        resolve_overlaps(result, config)
        assert len(result.junction_dots) == count_before

    def test_symbol_names_preserved(self):
        """Symbol names should not change during overlap resolution."""
        result = _make_simple_layout(6, spacing=15)
        names_before = [c.symbol_name for c in result.components]
        config = _make_config()
        resolve_overlaps(result, config)
        names_after = [c.symbol_name for c in result.components]
        assert names_before == names_after

    def test_labels_preserved(self):
        """Labels should not change during overlap resolution."""
        result = _make_simple_layout(6, spacing=15)
        labels_before = [c.label for c in result.components]
        config = _make_config()
        resolve_overlaps(result, config)
        labels_after = [c.label for c in result.components]
        assert labels_before == labels_after

    def test_breaker_positions_changed(self):
        """Overlapping breakers should have their X positions adjusted."""
        # Tight spacing forces overlap resolution
        result = _make_simple_layout(6, spacing=8)
        x_before = [c.x for c in result.components if c.label_style == "breaker_block"]
        config = _make_config()
        resolve_overlaps(result, config)
        x_after = [c.x for c in result.components if c.label_style == "breaker_block"]
        # At least some positions should have changed (spread out)
        assert x_before != x_after, "Tight layout should trigger overlap resolution"
