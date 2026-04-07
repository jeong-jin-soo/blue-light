"""
SLD Layout Integrity Tests — automated structural verification.

Validates that compute_layout() produces structurally sound results across
all SLD configuration variants:
1. Connection integrity — graph connectivity, no zero-length segments
2. Overlap detection — sub-circuit breakers and main components don't overlap
3. Layout structure — vertical ordering, busbar coverage, drawing boundaries
"""

import pytest
from itertools import combinations

from app.sld.layout import (
    BoundingBox,
    LayoutResult,
    PlacedComponent,
    _compute_bounding_box,
    _identify_groups,
    compute_layout,
)


# ---------------------------------------------------------------------------
# Test fixtures — 5 representative SLD configurations
# ---------------------------------------------------------------------------

SINGLE_PHASE_METERED = {
    "supply_type": "single_phase",
    "kva": 9,  # 40A × 230V = 9.2 kVA (realistic for single-phase 40A)
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {"name": "Socket 1", "breaker_type": "MCB", "breaker_rating": 20,
         "breaker_characteristic": "B",
         "cable": "2C 4.0sqmm PVC/PVC"},
        {"name": "Socket 2", "breaker_type": "MCB", "breaker_rating": 20,
         "breaker_characteristic": "B",
         "cable": "2C 4.0sqmm PVC/PVC"},
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "breaker_characteristic": "B",
         "cable": "2C 1.5sqmm PVC/PVC"},
    ],
}

THREE_PHASE_METERED = {
    "supply_type": "three_phase",
    "kva": 22,
    "voltage": 400,
    "main_breaker": {"type": "MCB", "rating": 32, "poles": "TPN", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 100, "poles": 4},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING"},
        {"name": "Power", "breaker_type": "MCB", "breaker_rating": 20,
         "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING"},
        {"name": "Aircon", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC + 4sqmm PVC CPC IN METAL TRUNKING"},
    ],
}

THREE_PHASE_CT = {
    "supply_type": "three_phase",
    "kva": 60,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "ct_meter",
    "elcb": {"rating": 100, "sensitivity_ma": 300, "poles": 4},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 16,
         "cable": "2 x 1C 2.5sqmm PVC"},
        {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC"},
        {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC"},
        {"name": "Aircon", "breaker_type": "MCB", "breaker_rating": 63,
         "cable": "2 x 1C 10sqmm PVC"},
    ],
}

SINGLE_PHASE_LANDLORD = {
    "supply_type": "single_phase",
    "kva": 14,
    "voltage": 230,
    "supply_source": "landlord",
    "main_breaker": {"type": "MCB", "rating": 63, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2C 1.5sqmm PVC/PVC"},
        {"name": "Power", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2C 4.0sqmm PVC/PVC"},
    ],
}

THREE_PHASE_MCCB = {
    "supply_type": "three_phase",
    "kva": 45,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 80, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "sp_meter",
    "elcb": {"rating": 80, "sensitivity_ma": 100, "poles": 4},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 16,
         "cable": "2 x 1C 2.5sqmm PVC"},
        {"name": "Power", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC"},
        {"name": "Aircon 1", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC"},
        {"name": "Aircon 2", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC"},
    ],
}

ALL_CASES = [
    pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
    pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    pytest.param(THREE_PHASE_CT, id="3ph_ct"),
    pytest.param(SINGLE_PHASE_LANDLORD, id="1ph_landlord"),
    pytest.param(THREE_PHASE_MCCB, id="3ph_mccb"),
]


@pytest.fixture(params=ALL_CASES)
def layout_result(request) -> LayoutResult:
    """Compute layout for each case."""
    return compute_layout(request.param)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_TOL = 0.5  # Coordinate matching tolerance (mm)


def _snap(v: float) -> float:
    """Round coordinate to tolerance grid for set-based matching."""
    return round(v / _TOL) * _TOL


def _snap_pt(pt: tuple[float, float]) -> tuple[float, float]:
    return (_snap(pt[0]), _snap(pt[1]))


def _build_adjacency(result: LayoutResult) -> dict[tuple, set[tuple]]:
    """Build adjacency map: snapped point → set of connected snapped points."""
    adj: dict[tuple, set[tuple]] = {}
    all_conns = result.resolved_connections(style_filter={"normal", "dashed"})
    for (start, end) in all_conns:
        s, e = _snap_pt(start), _snap_pt(end)
        adj.setdefault(s, set()).add(e)
        adj.setdefault(e, set()).add(s)
    return adj


def _count_connected_components(adj: dict[tuple, set[tuple]]) -> int:
    """Count connected components in the connection graph via BFS."""
    if not adj:
        return 0
    visited: set[tuple] = set()
    components = 0
    for start in adj:
        if start in visited:
            continue
        components += 1
        queue = [start]
        while queue:
            node = queue.pop()
            if node in visited:
                continue
            visited.add(node)
            for neighbor in adj.get(node, set()):
                if neighbor not in visited:
                    queue.append(neighbor)
    return components


def _get_breaker_components(result: LayoutResult) -> list[PlacedComponent]:
    """Filter sub-circuit breaker components only."""
    return [
        c for c in result.components
        if c.symbol_name.startswith("CB_") and c.label_style == "breaker_block"
    ]


def _get_main_components(result: LayoutResult) -> list[PlacedComponent]:
    """Filter main-chain components (main breaker, RCCB/ELCB, isolator)."""
    main_symbols = {"CB_MCB", "CB_MCCB", "CB_ACB", "CB_ELCB", "CB_RCCB", "ISOLATOR"}
    return [
        c for c in result.components
        if c.symbol_name in main_symbols and c.label_style != "breaker_block"
    ]


# ---------------------------------------------------------------------------
# 1. Connection Integrity
# ---------------------------------------------------------------------------

class TestConnectionIntegrity:
    """Verify that all connection segments form a sound wiring graph."""

    def test_connections_not_empty(self, layout_result: LayoutResult):
        """Every SLD case must produce at least one connection."""
        assert len(layout_result.resolved_connections(style_filter={"normal"})) > 0, "No connections in layout"

    def test_all_connections_nonzero_length(self, layout_result: LayoutResult):
        """Every connection segment must have non-zero length (start ≠ end)."""
        for i, (start, end) in enumerate(layout_result.resolved_connections(style_filter={"normal"})):
            dx = abs(start[0] - end[0])
            dy = abs(start[1] - end[1])
            length = (dx**2 + dy**2) ** 0.5
            assert length > 0.01, (
                f"Connection #{i} has zero length: {start} → {end}"
            )

    def test_few_duplicate_connections(self, layout_result: LayoutResult):
        """Duplicate connections should be minimal (≤3).

        A small number of duplicates can occur at junction points
        (e.g., meter board ↔ spine overlap). Many duplicates indicate
        a code path adding connections twice.
        """
        seen: set[tuple] = set()
        dups = 0
        for (start, end) in layout_result.resolved_connections(style_filter={"normal"}):
            key = (_snap_pt(start), _snap_pt(end))
            rev_key = (_snap_pt(end), _snap_pt(start))
            if key in seen or rev_key in seen:
                dups += 1
            seen.add(key)
        assert dups <= 3, (
            f"Too many duplicate connections: {dups} (max 3)"
        )

    def test_connection_graph_bounded_components(self, layout_result: LayoutResult):
        """Connection graph components should be proportional to circuit count.

        Each sub-circuit branch creates ~3-5 isolated graph segments
        (arrow stub, tail extension, tick mark, conductor ticks, etc.)
        because the busbar is not represented as a connection edge.
        Base overhead ~8 (meter board internals, spine, earth).

        Formula: max_components = 10 + 6 × sub_circuit_count
        """
        n_breakers = len(_get_breaker_components(layout_result))
        max_expected = 10 + 6 * max(n_breakers, 1)

        adj = _build_adjacency(layout_result)
        n_components = _count_connected_components(adj)
        assert n_components <= max_expected, (
            f"Connection graph has {n_components} components "
            f"(expected ≤ {max_expected} for {n_breakers} sub-circuits) — "
            f"likely indicates broken connections"
        )

    def test_connections_axis_aligned_or_short_diagonal(self, layout_result: LayoutResult):
        """Connections should be horizontal, vertical, or short diagonals.

        Long diagonal connections (>5mm) are likely layout bugs — wires
        in SLD drawings are always axis-aligned except for tick marks
        and 3-phase fan-out diagonals (dy≈7.0mm from busbar to intermediate).
        """
        for i, (start, end) in enumerate(layout_result.resolved_connections(style_filter={"normal"})):
            dx = abs(start[0] - end[0])
            dy = abs(start[1] - end[1])
            # Allow pure horizontal, pure vertical, or short diagonals (tick marks)
            is_horizontal = dy < 0.01
            is_vertical = dx < 0.01
            is_short_diagonal = (dx**2 + dy**2) ** 0.5 < 5.0
            # 3-phase fan-out: diagonals from busbar junction to side circuits
            # dy varies (2-10mm), dx ≈ circuit spacing (15-40mm)
            is_fanout = dy < 15.0 and dx < 50.0 and (dx > 1.0 or dy > 1.0)
            assert is_horizontal or is_vertical or is_short_diagonal or is_fanout, (
                f"Connection #{i} is a long diagonal ({dx:.1f}×{dy:.1f}mm): "
                f"{start} → {end}"
            )


# ---------------------------------------------------------------------------
# 2. Overlap Detection
# ---------------------------------------------------------------------------

class TestOverlapDetection:
    """Verify that components don't unintentionally overlap."""

    def test_no_breaker_overlap(self, layout_result: LayoutResult):
        """Sub-circuit breakers must not overlap each other.

        Each breaker occupies a vertical column — if columns overlap,
        labels become unreadable and the SLD is rejected.
        """
        breakers = _get_breaker_components(layout_result)
        if len(breakers) < 2:
            pytest.skip("Less than 2 breakers — nothing to check")

        boxes = []
        for comp in breakers:
            bb = _compute_bounding_box(comp)
            if bb is not None:
                boxes.append((comp, bb))

        for (comp_a, bb_a), (comp_b, bb_b) in combinations(boxes, 2):
            overlap = bb_a.overlap_area(bb_b)
            assert overlap < 1.0, (  # Allow < 1 sq mm of marginal touching
                f"Breaker overlap detected ({overlap:.1f} sq mm):\n"
                f"  {comp_a.circuit_id} ({comp_a.symbol_name}) at x={comp_a.x:.1f}\n"
                f"  {comp_b.circuit_id} ({comp_b.symbol_name}) at x={comp_b.x:.1f}"
            )

    def test_no_main_component_overlap(self, layout_result: LayoutResult):
        """Main-chain components (MCB, RCCB, isolator) must not overlap.

        These are stacked vertically on the spine — overlap means
        labels are unreadable.
        """
        main_comps = _get_main_components(layout_result)
        if len(main_comps) < 2:
            pytest.skip("Less than 2 main components")

        boxes = []
        for comp in main_comps:
            bb = _compute_bounding_box(comp)
            if bb is not None:
                boxes.append((comp, bb))

        for (comp_a, bb_a), (comp_b, bb_b) in combinations(boxes, 2):
            overlap = bb_a.overlap_area(bb_b)
            assert overlap < 1.0, (
                f"Main component overlap ({overlap:.1f} sq mm):\n"
                f"  {comp_a.symbol_name} label='{comp_a.label}' at y={comp_a.y:.1f}\n"
                f"  {comp_b.symbol_name} label='{comp_b.label}' at y={comp_b.y:.1f}"
            )


# ---------------------------------------------------------------------------
# 3. Layout Structure
# ---------------------------------------------------------------------------

class TestLayoutStructure:
    """Verify structural properties of the layout."""

    def test_busbar_covers_all_taps(self, layout_result: LayoutResult):
        """Busbar horizontal span must cover all sub-circuit tap X positions."""
        breakers = _get_breaker_components(layout_result)
        if not breakers:
            pytest.skip("No sub-circuit breakers")

        bus_left = layout_result.busbar_start_x
        bus_right = layout_result.busbar_end_x

        for comp in breakers:
            tap_x = comp.x + 3.6  # Approximate center (half of MCB width)
            assert bus_left <= tap_x + _TOL, (
                f"Breaker {comp.circuit_id} tap_x={tap_x:.1f} "
                f"is left of busbar start={bus_left:.1f}"
            )
            assert tap_x - _TOL <= bus_right, (
                f"Breaker {comp.circuit_id} tap_x={tap_x:.1f} "
                f"is right of busbar end={bus_right:.1f}"
            )

    def test_components_within_drawing_boundary(self, layout_result: LayoutResult):
        """All components must be within A3 drawing boundary (420mm x 297mm)."""
        margin = 10  # Title block and border margins
        max_x = 420 - margin
        max_y = 297 - margin

        for comp in layout_result.components:
            assert comp.x >= 0, (
                f"{comp.symbol_name} x={comp.x:.1f} is negative"
            )
            assert comp.x <= max_x, (
                f"{comp.symbol_name} x={comp.x:.1f} exceeds drawing width {max_x}"
            )
            assert comp.y >= 0, (
                f"{comp.symbol_name} y={comp.y:.1f} is negative"
            )
            assert comp.y <= max_y, (
                f"{comp.symbol_name} y={comp.y:.1f} exceeds drawing height {max_y}"
            )

    def test_vertical_order_main_chain(self, layout_result: LayoutResult):
        """Main-chain components must follow bottom-up order:
        incoming supply < main breaker < ELCB/RCCB < busbar.

        In layout coordinates, higher Y = higher on the drawing.
        """
        busbar_y = layout_result.busbar_y
        assert busbar_y > 0, "Busbar Y should be positive"

        # Main breaker should be below busbar
        main_breakers = [
            c for c in layout_result.components
            if c.symbol_name in ("CB_MCB", "CB_MCCB", "CB_ACB")
            and c.label_style != "breaker_block"
        ]
        for mb in main_breakers:
            assert mb.y < busbar_y, (
                f"Main breaker y={mb.y:.1f} should be below busbar_y={busbar_y:.1f}"
            )

        # Sub-circuit breakers should be above busbar
        sub_breakers = _get_breaker_components(layout_result)
        for sb in sub_breakers:
            assert sb.y >= busbar_y, (
                f"Sub-circuit breaker {sb.circuit_id} y={sb.y:.1f} "
                f"should be at or above busbar_y={busbar_y:.1f}"
            )


# ---------------------------------------------------------------------------
# 4. Sub-circuit count verification (separate parametrize)
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("requirements", ALL_CASES)
def test_sub_circuits_count_matches(requirements: dict):
    """Number of sub-circuit breakers should match input requirements.

    3-phase layouts with triplets pad with SPARE circuits to fill phase
    triplets, so actual count >= input count and may be a multiple of 3.
    Without triplets (phase-grouped or no phase data), no padding occurs.
    """
    result = compute_layout(requirements)
    input_count = len(requirements.get("sub_circuits", []))
    actual = len(_get_breaker_components(result))
    if requirements.get("supply_type") == "three_phase":
        assert actual >= input_count, (
            f"Expected >= {input_count} sub-circuit breakers, got {actual}"
        )
        # Multiple of 3 only guaranteed when triplets are applied
        if actual > input_count:
            assert actual % 3 == 0, (
                f"3-phase padded breaker count {actual} should be multiple of 3"
            )
    else:
        assert actual == input_count, (
            f"Expected {input_count} sub-circuit breakers, got {actual}"
        )


# ---------------------------------------------------------------------------
# 5. Cable Annotation Completeness
# ---------------------------------------------------------------------------

# Only metered cases have meter board cable annotations
METERED_CASES = [
    pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
    pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    pytest.param(THREE_PHASE_CT, id="3ph_ct"),
    pytest.param(THREE_PHASE_MCCB, id="3ph_mccb"),
]


@pytest.mark.parametrize("requirements", METERED_CASES)
def test_meter_board_has_cable_annotations(requirements: dict):
    """Metered SLDs must have cable annotation labels on both sides of meter board.

    Reference sample convention (SP PowerGrid):
    - RIGHT side: tick mark + cable spec on incoming supply line
    - LEFT side: tick mark + cable spec on outgoing line to DB

    When incoming_cable is auto-determined (from INCOMING_SPEC), both annotations
    must appear. Each annotation has a LABEL with 'sqmm' keyword.
    """
    result = compute_layout(requirements)

    # Find cable annotation labels (contain 'sqmm' — a hallmark of cable specs)
    cable_labels = [
        c for c in result.components
        if c.symbol_name == "LABEL" and "sqmm" in str(c.label).lower()
    ]

    # Must have at least 2 cable annotations for meter board (LEFT + RIGHT)
    # (sub-circuit cable labels are additional)
    assert len(cable_labels) >= 2, (
        f"Expected ≥2 cable annotation labels (meter board LEFT + RIGHT), "
        f"got {len(cable_labels)}: {[c.label for c in cable_labels]}"
    )


@pytest.mark.parametrize("requirements", METERED_CASES)
def test_meter_board_has_tick_marks(requirements: dict):
    """Metered SLDs must have tick marks on meter board cable lines.

    Tick marks are short diagonal connection segments (length < 5mm)
    crossing the cable lines at approximately 45°.
    At least 2 tick marks expected: one on RIGHT (incoming), one on LEFT (outgoing).
    """
    result = compute_layout(requirements)

    tick_marks = []
    # Check both regular and thick connections (outgoing tick uses thick_connections)
    all_conns = result.resolved_connections(style_filter={"normal", "thick"})
    for c in all_conns:
        p1, p2 = c
        dx = abs(p2[0] - p1[0])
        dy = abs(p2[1] - p1[1])
        length = (dx ** 2 + dy ** 2) ** 0.5
        # Tick marks: short (< 8mm), diagonal (both dx > 0 and dy > 0)
        if length < 8 and dx > 0.5 and dy > 0.5:
            tick_marks.append(c)

    assert len(tick_marks) >= 2, (
        f"Expected ≥2 tick marks on meter board cable lines, "
        f"got {len(tick_marks)}"
    )


# ---------------------------------------------------------------------------
# Test: _identify_groups() completeness
# ---------------------------------------------------------------------------

ALL_CONFIGS_FOR_GROUPS = [
    SINGLE_PHASE_METERED,
    THREE_PHASE_METERED,
    THREE_PHASE_CT,
    SINGLE_PHASE_LANDLORD,
    THREE_PHASE_MCCB,
]


@pytest.mark.parametrize("requirements", ALL_CONFIGS_FOR_GROUPS,
                         ids=["1ph_metered", "3ph_metered", "3ph_ct",
                              "1ph_landlord", "3ph_mccb"])
def test_all_circuit_ids_matched(requirements):
    """Every CIRCUIT_ID_BOX component should be matched to a group."""
    result = compute_layout(requirements)
    groups, incoming_chain_x = _identify_groups(result)

    cid_matched = {g.circuit_id_idx for g in groups if g.circuit_id_idx is not None}
    all_cids = [
        i for i, c in enumerate(result.components)
        if c.symbol_name == "CIRCUIT_ID_BOX"
    ]

    unmatched = [i for i in all_cids if i not in cid_matched]
    assert len(unmatched) == 0, (
        f"{len(unmatched)} orphan CIRCUIT_ID_BOX(es): indices={unmatched}"
    )


@pytest.mark.parametrize("requirements", ALL_CONFIGS_FOR_GROUPS,
                         ids=["1ph_metered_grp", "3ph_metered_grp", "3ph_ct_grp",
                              "1ph_landlord_grp", "3ph_mccb_grp"])
def test_groups_detected(requirements):
    """_identify_groups should find at least one group for every config."""
    result = compute_layout(requirements)
    groups, incoming_chain_x = _identify_groups(result)
    non_spare = [g for g in groups if not g.is_spare]
    assert len(non_spare) > 0, "Should have at least one non-spare group"


@pytest.mark.parametrize("requirements", ALL_CONFIGS_FOR_GROUPS,
                         ids=["1ph_spine", "3ph_spine", "3ph_ct_spine",
                              "1ph_landlord_spine", "3ph_mccb_spine"])
def test_spine_x_stored(requirements):
    """compute_layout should store spine_x in LayoutResult."""
    result = compute_layout(requirements)
    assert result.spine_x > 0, "spine_x should be stored by compute_layout"
    assert result.spine_x == pytest.approx(210.0), (
        f"spine_x should be config.start_x (210.0), got {result.spine_x}"
    )


@pytest.mark.parametrize("requirements", ALL_CONFIGS_FOR_GROUPS,
                         ids=["1ph_icx", "3ph_icx", "3ph_ct_icx",
                              "1ph_landlord_icx", "3ph_mccb_icx"])
def test_incoming_chain_x_matches_spine(requirements):
    """With spine_x set, incoming_chain_x should always be positive and match."""
    result = compute_layout(requirements)
    groups, incoming_chain_x = _identify_groups(result)
    assert incoming_chain_x > 0, (
        "incoming_chain_x should be positive when spine_x is set"
    )
    assert incoming_chain_x == pytest.approx(result.spine_x, abs=0.5), (
        f"incoming_chain_x={incoming_chain_x} should match spine_x={result.spine_x}"
    )
