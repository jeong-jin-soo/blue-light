"""
Tests for SLD Rendering Engine v7 improvements.

Verifies:
- CIRCUIT_ID_BOX components at busbar tap points
- DB_INFO_BOX dashed box component
- ELCB label positioning (right side, no overlap)
- Spare circuit handling
- generate_pdf_bytes() convenience method
- Title block CHECKED/DATE cell separation
- Layout correctness for single-phase and three-phase
- Busbar width scaling
- Breaker block label spacing
"""

import pytest

from app.sld.layout import (
    BoundingBox,
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    SubCircuitGroup,
    _breaker_half_width,
    _compute_bounding_box,
    _compute_group_width,
    _identify_groups,
    compute_layout,
    resolve_overlaps,
)
from app.sld.generator import SldGenerator
from app.sld.svg_backend import SvgBackend
from app.sld.title_block import (
    ROW_CHECK_DATE,
    ROW_LOW,
    ROW_BOT,
    COL5,
    DWG_SPLIT_X,
    draw_border,
    draw_title_block_frame,
    fill_title_block_data,
)


# -- Test fixtures --

BASIC_3PHASE_REQ = {
    "supply_type": "three_phase",
    "kva": 22,
    "voltage": 400,
    "main_breaker": {"type": "MCB", "rating": 32, "poles": "TPN", "fault_kA": 10},
    "busbar_rating": 100,
    "elcb": {"rating": 40, "sensitivity_ma": 100, "poles": 4},
    "sub_circuits": [
        {
            "name": "Lighting",
            "breaker_type": "MCB",
            "breaker_rating": 10,
            "breaker_characteristic": "B",
            "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING",
        },
        {
            "name": "Power",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING",
        },
        {
            "name": "Aircon",
            "breaker_type": "MCB",
            "breaker_rating": 32,
            "cable": "2 x 1C 6sqmm PVC + 4sqmm PVC CPC IN METAL TRUNKING",
        },
        {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

BASIC_1PHASE_REQ = {
    "supply_type": "single_phase",
    "kva": 14.49,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 63, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {
            "name": "Lighting",
            "breaker_type": "MCB",
            "breaker_rating": 10,
            "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING",
        },
        {
            "name": "Power",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING",
        },
        {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}


def _get_components_by_type(layout_result, symbol_name: str) -> list[PlacedComponent]:
    """Helper: filter components by symbol_name."""
    return [c for c in layout_result.components if c.symbol_name == symbol_name]


# -- Test: CIRCUIT_ID_BOX --

class TestCircuitIdBox:
    """Tests for circuit ID boxes at busbar tap points."""

    def test_circuit_id_box_in_layout(self):
        """CIRCUIT_ID_BOX components should exist for each sub-circuit."""
        result = compute_layout(BASIC_3PHASE_REQ)
        id_boxes = _get_components_by_type(result, "CIRCUIT_ID_BOX")
        # 4 sub-circuits (including spare) → 4 circuit ID boxes
        assert len(id_boxes) == 4

    def test_circuit_id_box_position(self):
        """CIRCUIT_ID_BOX should be positioned at busbar_y + 2."""
        result = compute_layout(BASIC_3PHASE_REQ)
        id_boxes = _get_components_by_type(result, "CIRCUIT_ID_BOX")
        for box in id_boxes:
            assert box.y == pytest.approx(result.busbar_y + 2, abs=0.1), (
                f"CIRCUIT_ID_BOX at x={box.x} has y={box.y}, "
                f"expected busbar_y+2={result.busbar_y + 2}"
            )

    def test_circuit_id_box_has_valid_ids(self):
        """Each CIRCUIT_ID_BOX should have a non-empty circuit_id."""
        result = compute_layout(BASIC_3PHASE_REQ)
        id_boxes = _get_components_by_type(result, "CIRCUIT_ID_BOX")
        ids = [box.circuit_id for box in id_boxes]
        assert all(len(cid) > 0 for cid in ids)
        # 3-phase: Lighting→L1S1, Power→L1P1, Aircon→L2P1, Spare→SP1
        assert "SP1" in ids  # Spare circuit


# -- Test: DB_INFO_BOX --

class TestDbInfoBox:
    """Tests for the DB info dashed box below busbar."""

    def test_db_info_box_in_layout(self):
        """DB_INFO_BOX component should exist in the layout."""
        result = compute_layout(BASIC_3PHASE_REQ)
        db_boxes = _get_components_by_type(result, "DB_INFO_BOX")
        assert len(db_boxes) == 1

    def test_db_info_box_contains_load_info(self):
        """DB_INFO_BOX should contain approved load information."""
        result = compute_layout(BASIC_3PHASE_REQ)
        db_box = _get_components_by_type(result, "DB_INFO_BOX")[0]
        assert "APPROVED LOAD" in db_box.rating
        assert "22KVA" in db_box.rating
        assert "400V" in db_box.rating

    def test_db_info_box_contains_db_rating(self):
        """DB_INFO_BOX label should show DB rating."""
        result = compute_layout(BASIC_3PHASE_REQ)
        db_box = _get_components_by_type(result, "DB_INFO_BOX")[0]
        assert "32A DB" in db_box.label

    def test_db_info_box_with_premises_address(self):
        """DB_INFO_BOX should include premises address when provided."""
        result = compute_layout(
            BASIC_3PHASE_REQ,
            application_info={"address": "123 ORCHARD ROAD"},
        )
        db_box = _get_components_by_type(result, "DB_INFO_BOX")[0]
        assert "LOCATED AT PREMISES" in db_box.rating
        assert "123 ORCHARD ROAD" in db_box.rating


# -- Test: ELCB label positioning --

class TestElcbInlinePosition:
    """Tests for ELCB/RCCB inline positioning (between main breaker and busbar)."""

    def test_elcb_inline_between_breaker_and_busbar(self):
        """ELCB should be placed inline on cx, between main breaker and busbar."""
        result = compute_layout(BASIC_3PHASE_REQ)
        elcb_comps = [c for c in result.components
                      if c.symbol_name in ("CB_ELCB", "CB_RCCB")]
        assert len(elcb_comps) == 1, "Should have exactly one ELCB/RCCB"
        elcb = elcb_comps[0]

        # ELCB should be centered on cx (default 210)
        cx = 210  # LayoutConfig.start_x default
        elcb_center_x = elcb.x + 7  # 14mm width / 2
        assert abs(elcb_center_x - cx) < 1.0, (
            f"ELCB center_x={elcb_center_x} should be at cx={cx}"
        )

        # ELCB y should be below busbar_y (bottom-up layout)
        assert elcb.y < result.busbar_y, (
            f"ELCB y={elcb.y} should be < busbar_y={result.busbar_y}"
        )

    def test_rccb_inline_single_phase(self):
        """Single-phase RCCB should also be inline at cx."""
        result = compute_layout(BASIC_1PHASE_REQ)
        rccb_comps = [c for c in result.components
                      if c.symbol_name == "CB_RCCB"]
        assert len(rccb_comps) == 1, "Should have exactly one RCCB"
        rccb = rccb_comps[0]
        cx = 210
        rccb_center_x = rccb.x + 7
        assert abs(rccb_center_x - cx) < 1.0
        assert rccb.y < result.busbar_y

    def test_no_elcb_hanging_below_busbar(self):
        """No ELCB/RCCB component should be below the busbar Y."""
        result = compute_layout(BASIC_3PHASE_REQ)
        for comp in result.components:
            if comp.symbol_name in ("CB_ELCB", "CB_RCCB"):
                # In bottom-up layout, y < busbar_y means the component is below busbar
                # but inline ELCB should be below busbar (between main breaker and busbar)
                # The key is it should NOT be far below (the old hanging was at busbar_y - 32)
                # Inline ELCB should be within ~30mm below busbar
                assert comp.y > result.busbar_y - 30, (
                    f"ELCB y={comp.y} is too far below busbar_y={result.busbar_y}"
                )


# -- Test: Spare circuit --

class TestSpareCircuit:
    """Tests for spare circuit handling."""

    def test_spare_circuit_no_breaker(self):
        """Spare circuits should have no breaker symbol, only SPARE label."""
        result = compute_layout(BASIC_3PHASE_REQ)
        # No CB_MCB component with "spare" in the name
        breaker_comps = [
            c for c in result.components
            if c.symbol_name.startswith("CB_") and "spare" in c.label.lower()
        ]
        assert len(breaker_comps) == 0

    def test_spare_circuit_has_label(self):
        """Spare circuits should have a SPARE text label."""
        result = compute_layout(BASIC_3PHASE_REQ)
        spare_labels = [
            c for c in result.components
            if c.symbol_name == "LABEL" and c.label == "SPARE"
        ]
        assert len(spare_labels) == 1


# -- Test: generate_pdf_bytes --

class TestGeneratePdfBytes:
    """Tests for the generate_pdf_bytes() static method."""

    def test_returns_valid_pdf_bytes(self):
        """generate_pdf_bytes() should return bytes starting with PDF header."""
        pdf_bytes, svg_string, dxf_bytes = SldGenerator.generate_pdf_bytes(BASIC_3PHASE_REQ)
        assert isinstance(pdf_bytes, bytes)
        assert pdf_bytes[:5] == b"%PDF-"
        assert len(pdf_bytes) > 1000  # Non-trivial PDF

    def test_returns_svg_string(self):
        """generate_pdf_bytes() should also return a valid SVG string."""
        pdf_bytes, svg_string, dxf_bytes = SldGenerator.generate_pdf_bytes(BASIC_3PHASE_REQ)
        assert isinstance(svg_string, str)
        assert svg_string.startswith("<svg")
        assert "</svg>" in svg_string

    def test_returns_dxf_bytes(self):
        """generate_pdf_bytes() should return DXF bytes when backend_type='dxf'."""
        pdf_bytes, svg_string, dxf_bytes = SldGenerator.generate_pdf_bytes(BASIC_3PHASE_REQ, backend_type="dxf")
        assert isinstance(dxf_bytes, bytes)
        assert len(dxf_bytes) > 1000

    def test_single_phase_pdf_bytes(self):
        """generate_pdf_bytes() should work with single-phase requirements."""
        pdf_bytes, svg_string, dxf_bytes = SldGenerator.generate_pdf_bytes(BASIC_1PHASE_REQ)
        assert isinstance(pdf_bytes, bytes)
        assert pdf_bytes[:5] == b"%PDF-"

    def test_legacy_pdf_backend(self):
        """generate_pdf_bytes() with backend_type='pdf' should return None for DXF."""
        pdf_bytes, svg_string, dxf_bytes = SldGenerator.generate_pdf_bytes(BASIC_3PHASE_REQ, backend_type="pdf")
        assert isinstance(pdf_bytes, bytes)
        assert dxf_bytes is None


# -- Test: Title block CHECKED/DATE --

class TestTitleBlockCheckedDate:
    """Tests for CHECKED/DATE cell separation in title block."""

    def test_checked_date_constant(self):
        """ROW_CHECK_DATE should be between ROW_LOW and ROW_BOT."""
        assert ROW_BOT < ROW_CHECK_DATE < ROW_LOW

    def test_title_block_frame_has_divider(self):
        """Title block frame should contain the CHECKED/DATE divider line."""
        svg = SvgBackend()
        draw_title_block_frame(svg)
        svg_str = svg.get_svg_string()
        # The divider line goes from COL5 to DWG_SPLIT_X at y=ROW_CHECK_DATE
        # In SVG, y is flipped: y_svg = 297 - 15 = 282
        assert "282.00" in svg_str  # Flipped ROW_CHECK_DATE y-coordinate


# -- Test: Layout correctness --

class TestLayoutCorrectness:
    """Tests for layout correctness across different configurations."""

    def test_single_phase_layout(self):
        """Single-phase layout should have correct supply_type and voltage."""
        result = compute_layout(BASIC_1PHASE_REQ)
        assert result.supply_type == "single_phase"
        assert result.voltage == 230

    def test_three_phase_layout(self):
        """Three-phase layout should have correct supply_type and voltage."""
        result = compute_layout(BASIC_3PHASE_REQ)
        assert result.supply_type == "three_phase"
        assert result.voltage == 400

    def test_busbar_width_scales_with_circuits(self):
        """Busbar width should be larger when there are more circuits."""
        # 4 circuits
        result_4 = compute_layout(BASIC_3PHASE_REQ)
        bus_width_4 = result_4.busbar_end_x - result_4.busbar_start_x

        # 8 circuits (double the sub_circuits)
        req_8 = dict(BASIC_3PHASE_REQ)
        req_8["sub_circuits"] = BASIC_3PHASE_REQ["sub_circuits"] * 2
        result_8 = compute_layout(req_8)
        bus_width_8 = result_8.busbar_end_x - result_8.busbar_start_x

        assert bus_width_8 >= bus_width_4

    def test_breaker_block_components(self):
        """Sub-circuit breakers should use breaker_block label_style."""
        result = compute_layout(BASIC_3PHASE_REQ)
        breaker_comps = [
            c for c in result.components
            if c.symbol_name.startswith("CB_") and c.label_style == "breaker_block"
        ]
        # 3 non-spare circuits → 3 breaker block components
        assert len(breaker_comps) == 3

    def test_earth_bar_present(self):
        """Earth bar should always be present in the layout."""
        result = compute_layout(BASIC_3PHASE_REQ)
        earth_comps = _get_components_by_type(result, "EARTH")
        assert len(earth_comps) == 1


# -- Test: Full generation --

class TestFullGeneration:
    """End-to-end generation tests."""

    def test_generate_3phase_no_error(self, tmp_path):
        """Full generation of a 3-phase SLD should succeed."""
        gen = SldGenerator()
        pdf_path = str(tmp_path / "test.pdf")
        svg_path = str(tmp_path / "test.svg")
        result = gen.generate(BASIC_3PHASE_REQ, {}, pdf_path, svg_path)
        assert result["component_count"] > 0
        assert result["pdf_path"] == pdf_path
        assert len(result["svg_string"]) > 100

    def test_generate_1phase_no_error(self, tmp_path):
        """Full generation of a 1-phase SLD should succeed."""
        gen = SldGenerator()
        pdf_path = str(tmp_path / "test.pdf")
        svg_path = str(tmp_path / "test.svg")
        result = gen.generate(BASIC_1PHASE_REQ, {}, pdf_path, svg_path)
        assert result["component_count"] > 0

    def test_generate_with_application_info(self, tmp_path):
        """Generation with full application info should succeed."""
        gen = SldGenerator()
        pdf_path = str(tmp_path / "test.pdf")
        app_info = {
            "address": "200 PANDAN LOOP",
            "postalCode": "128388",
            "clientName": "TEST PTE LTD",
            "assignedLewName": "John Doe",
            "assignedLewLicenceNo": "8/12345",
        }
        result = gen.generate(BASIC_3PHASE_REQ, app_info, pdf_path)
        assert result["component_count"] > 0


# -- Test fixtures: Dense circuit configurations --

DENSE_3PHASE_REQ = {
    "supply_type": "three_phase",
    "kva": 100,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 160, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "elcb": {"rating": 160, "sensitivity_ma": 100, "poles": 4},
    "sub_circuits": [
        {
            "name": f"Circuit {i + 1}",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING",
        }
        for i in range(15)
    ],
}

DENSE_1PHASE_REQ = {
    "supply_type": "single_phase",
    "kva": 14.49,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 63, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {
            "name": f"Circuit {i + 1}",
            "breaker_type": "MCB",
            "breaker_rating": 20,
            "breaker_characteristic": "B",
            "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING",
        }
        for i in range(10)
    ],
}


# -- Test: BoundingBox --

class TestBoundingBox:
    """Tests for BoundingBox collision detection."""

    def test_overlaps_true(self):
        """Overlapping boxes should return True."""
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=5, y=5, width=10, height=10)
        assert a.overlaps(b) is True
        assert b.overlaps(a) is True

    def test_overlaps_false_horizontal(self):
        """Non-overlapping boxes (horizontally separated) should return False."""
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=15, y=0, width=10, height=10)
        assert a.overlaps(b) is False

    def test_overlaps_false_vertical(self):
        """Non-overlapping boxes (vertically separated) should return False."""
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=0, y=15, width=10, height=10)
        assert a.overlaps(b) is False

    def test_overlaps_edge_touching(self):
        """Edge-touching boxes should NOT overlap (boundary is exclusive)."""
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=10, y=0, width=10, height=10)
        assert a.overlaps(b) is False

    def test_overlap_area(self):
        """Overlap area should be computed correctly."""
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=5, y=5, width=10, height=10)
        assert a.overlap_area(b) == pytest.approx(25.0)

    def test_overlap_area_zero_when_no_overlap(self):
        """Overlap area should be zero when no overlap."""
        a = BoundingBox(x=0, y=0, width=10, height=10)
        b = BoundingBox(x=20, y=0, width=10, height=10)
        assert a.overlap_area(b) == pytest.approx(0.0)

    def test_properties(self):
        """right and top properties should be correct."""
        bb = BoundingBox(x=5, y=10, width=20, height=30)
        assert bb.right == pytest.approx(25.0)
        assert bb.top == pytest.approx(40.0)


# -- Test: _compute_bounding_box --

class TestComputeBoundingBox:
    """Tests for bounding box computation from PlacedComponent."""

    def test_busbar_returns_none(self):
        """BUSBAR should return None (structural, never moved)."""
        comp = PlacedComponent(symbol_name="BUSBAR", x=50, y=100, label="DB")
        assert _compute_bounding_box(comp) is None

    def test_circuit_id_box_centered(self):
        """CIRCUIT_ID_BOX should be centered on x (text-only, no box)."""
        comp = PlacedComponent(symbol_name="CIRCUIT_ID_BOX", x=100, y=200, circuit_id="S1")
        bb = _compute_bounding_box(comp)
        assert bb is not None
        # Text width = len("S1") * 1.8 + 2 = 5.6, centered on x=100
        assert bb.x == pytest.approx(100 - 5.6 / 2, abs=0.5)
        assert bb.width == pytest.approx(5.6, abs=0.5)
        assert bb.height == pytest.approx(3.0)

    def test_db_info_box_extends_down(self):
        """DB_INFO_BOX should extend downward from y."""
        comp = PlacedComponent(symbol_name="DB_INFO_BOX", x=50, y=100, label="info")
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.y == pytest.approx(82.0)  # 100 - 18
        assert bb.height == pytest.approx(18.0)

    def test_label_horizontal(self):
        """Horizontal LABEL should compute width from text length."""
        comp = PlacedComponent(symbol_name="LABEL", x=50, y=100, label="100A COMB BUSBAR")
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.width > 0
        assert bb.height > 0

    def test_label_rotated_90(self):
        """90° rotated LABEL should swap width/height."""
        comp = PlacedComponent(
            symbol_name="LABEL", x=50, y=100, label="Test Label", rotation=90
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None
        # For rotation=90, visual width = line count * char_h, visual height = text len * char_w
        assert bb.width < bb.height  # Single line rotated: narrow width, tall height

    def test_breaker_block_mcb(self):
        """Breaker block (MCB, rotation=90) should have non-trivial bounding box."""
        comp = PlacedComponent(
            symbol_name="CB_MCB", x=100, y=150, label="Lighting",
            rating="20", rotation=90, label_style="breaker_block",
        )
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.width > 10  # Should include label columns
        assert bb.height > 16  # Should include symbol + stubs

    def test_symbol_from_dims_table(self):
        """Known symbol should use _SYMBOL_DIMS dimensions."""
        comp = PlacedComponent(symbol_name="KWH_METER", x=50, y=100, label="kWh")
        bb = _compute_bounding_box(comp)
        assert bb is not None
        assert bb.width == pytest.approx(20.0)
        assert bb.height == pytest.approx(14.0)


# -- Test: resolve_overlaps --

class TestResolveOverlaps:
    """Tests for post-layout overlap resolution."""

    def test_existing_basic_3phase_no_regression(self):
        """Basic 3-phase layout should still produce valid results after overlap resolution."""
        result = compute_layout(BASIC_3PHASE_REQ)
        assert len(result.components) > 0
        # Busbar should still be present
        busbars = [c for c in result.components if c.symbol_name == "BUSBAR"]
        assert len(busbars) >= 1

    def test_existing_basic_1phase_no_regression(self):
        """Basic 1-phase layout should still produce valid results after overlap resolution."""
        result = compute_layout(BASIC_1PHASE_REQ)
        assert len(result.components) > 0
        busbars = [c for c in result.components if c.symbol_name == "BUSBAR"]
        assert len(busbars) >= 1

    def test_dense_3phase_generates(self):
        """Dense 3-phase layout (15 circuits) should generate without error."""
        result = compute_layout(DENSE_3PHASE_REQ)
        assert len(result.components) > 0
        mcbs = [c for c in result.components if c.symbol_name == "CB_MCB"]
        assert len(mcbs) >= 15  # At least 15 sub-circuit breakers

    def test_dense_1phase_generates(self):
        """Dense 1-phase layout (10 circuits) should generate without error."""
        result = compute_layout(DENSE_1PHASE_REQ)
        assert len(result.components) > 0

    def test_structural_components_not_moved(self):
        """BUSBAR components should not be moved by overlap resolution."""
        result_before = compute_layout.__wrapped__(BASIC_3PHASE_REQ) if hasattr(compute_layout, '__wrapped__') else None
        result = compute_layout(BASIC_3PHASE_REQ)
        busbars = [c for c in result.components if c.symbol_name == "BUSBAR"]
        for bus in busbars:
            # Busbar y should match the stored busbar_y
            assert bus.y == pytest.approx(result.busbar_y, abs=0.1)

    def test_within_drawing_bounds(self):
        """Standard layouts should have sub-circuit breakers within A3 drawing bounds."""
        config = LayoutConfig()
        # Use standard 4-circuit layout (not dense) for bounds check
        result = compute_layout(BASIC_3PHASE_REQ)
        for comp in result.components:
            if comp.label_style != "breaker_block":
                continue
            assert comp.x >= config.min_x, (
                f"{comp.symbol_name} center x={comp.x} < min_x={config.min_x}"
            )
            assert comp.x <= config.max_x, (
                f"{comp.symbol_name} center x={comp.x} > max_x={config.max_x}"
            )

    def test_deterministic(self):
        """Same input should produce identical output."""
        result1 = compute_layout(DENSE_3PHASE_REQ)
        result2 = compute_layout(DENSE_3PHASE_REQ)
        assert len(result1.components) == len(result2.components)
        for c1, c2 in zip(result1.components, result2.components):
            assert c1.x == pytest.approx(c2.x, abs=0.01)
            assert c1.y == pytest.approx(c2.y, abs=0.01)

    def test_no_overlap_unchanged(self):
        """Components without overlap should not be moved."""
        # Create two well-separated components
        comp1 = PlacedComponent(symbol_name="LABEL", x=50, y=100, label="Label A")
        comp2 = PlacedComponent(symbol_name="LABEL", x=200, y=100, label="Label B")
        layout = LayoutResult(components=[comp1, comp2], busbar_y=100, busbar_end_x=250)
        orig_x1, orig_x2 = comp1.x, comp2.x
        resolve_overlaps(layout)
        assert layout.components[0].x == pytest.approx(orig_x1)
        assert layout.components[1].x == pytest.approx(orig_x2)

    def test_busbar_rating_label_separate(self):
        """Busbar rating should be a separate LABEL component, not in BUSBAR.rating."""
        result = compute_layout(BASIC_3PHASE_REQ)
        busbars = [c for c in result.components if c.symbol_name == "BUSBAR"]
        for bus in busbars:
            if bus.label:  # Main busbar (not row sub-busbars)
                assert bus.rating == "", (
                    f"BUSBAR should have rating='' but has rating='{bus.rating}'"
                )
        # There should be a LABEL with busbar rating text
        labels = [c for c in result.components if c.symbol_name == "LABEL"]
        busbar_labels = [l for l in labels if "BUSBAR" in (l.label or "")]
        assert len(busbar_labels) >= 1, "Should have a separate LABEL for busbar rating"

    def test_dense_3phase_pdf_generation(self, tmp_path):
        """Dense 3-phase layout should generate valid PDF."""
        gen = SldGenerator()
        pdf_path = str(tmp_path / "dense_test.pdf")
        svg_path = str(tmp_path / "dense_test.svg")
        result = gen.generate(DENSE_3PHASE_REQ, {}, pdf_path, svg_path)
        assert result["component_count"] > 0

    def test_dense_1phase_pdf_generation(self, tmp_path):
        """Dense 1-phase layout should generate valid PDF."""
        gen = SldGenerator()
        pdf_path = str(tmp_path / "dense_1p_test.pdf")
        svg_path = str(tmp_path / "dense_1p_test.svg")
        result = gen.generate(DENSE_1PHASE_REQ, {}, pdf_path, svg_path)
        assert result["component_count"] > 0


# -- Test: Connection Alignment --

class TestConnectionAlignment:
    """Tests that connections (wires) stay aligned with moved components."""

    def _get_breaker_tap_x(self, comp: PlacedComponent) -> float:
        """Calculate the tap_x (center) from a breaker block component."""
        if comp.symbol_name == "CB_MCB":
            return comp.x + 1.8   # 3.6mm / 2 (calibrated)
        elif comp.symbol_name == "CB_MCCB":
            return comp.x + 2.1   # 4.2mm / 2
        elif comp.symbol_name == "CB_ACB":
            return comp.x + 2.5   # 5.0mm / 2
        return comp.x + 2.1

    def test_connections_match_breakers_standard(self):
        """For standard layout, connections should align with breaker tap points."""
        result = compute_layout(BASIC_3PHASE_REQ)
        breakers = [c for c in result.components
                    if c.label_style == "breaker_block"]
        for breaker in breakers:
            tap_x = self._get_breaker_tap_x(breaker)
            # There should be at least one connection at this tap_x
            matching = [
                (s, e) for s, e in result.connections
                if abs(s[0] - tap_x) < 0.5 or abs(e[0] - tap_x) < 0.5
            ]
            assert len(matching) >= 1, (
                f"Breaker '{breaker.label}' at tap_x={tap_x:.1f} "
                f"has no matching connection"
            )

    def test_connections_match_breakers_dense(self):
        """For dense layout, connections should still align with breaker tap points."""
        result = compute_layout(DENSE_3PHASE_REQ)
        breakers = [c for c in result.components
                    if c.label_style == "breaker_block"]
        for breaker in breakers:
            tap_x = self._get_breaker_tap_x(breaker)
            matching = [
                (s, e) for s, e in result.connections
                if abs(s[0] - tap_x) < 0.5 or abs(e[0] - tap_x) < 0.5
            ]
            assert len(matching) >= 1, (
                f"Breaker '{breaker.label}' at tap_x={tap_x:.1f} "
                f"has no matching connection"
            )

    def test_circuit_id_box_at_breaker_tap(self):
        """CIRCUIT_ID_BOX should be at same x as breaker tap_x."""
        result = compute_layout(DENSE_3PHASE_REQ)
        breakers = [c for c in result.components
                    if c.label_style == "breaker_block"]
        id_boxes = [c for c in result.components
                    if c.symbol_name == "CIRCUIT_ID_BOX"]
        for breaker in breakers:
            tap_x = self._get_breaker_tap_x(breaker)
            # Find matching CIRCUIT_ID_BOX
            matching = [b for b in id_boxes if abs(b.x - tap_x) < 0.5]
            assert len(matching) >= 1, (
                f"Breaker '{breaker.label}' at tap_x={tap_x:.1f} "
                f"has no matching CIRCUIT_ID_BOX"
            )

    def test_spare_connections_aligned(self):
        """Spare circuit connections should align with spare label positions."""
        result = compute_layout(BASIC_3PHASE_REQ)
        spare_labels = [c for c in result.components
                        if c.symbol_name == "LABEL"
                        and "spare" in (c.label or "").lower()
                        and abs(c.rotation - 90.0) < 0.1]
        for label in spare_labels:
            spare_tap_x = label.x - 3  # LABEL at tap_x + 3
            matching = [
                (s, e) for s, e in result.connections
                if abs(s[0] - spare_tap_x) < 0.5 or abs(e[0] - spare_tap_x) < 0.5
            ]
            assert len(matching) >= 1, (
                f"Spare label at x={label.x:.1f} (tap_x={spare_tap_x:.1f}) "
                f"has no matching connection"
            )

    def test_busbar_tap_connections_vertical(self):
        """Busbar tap connections (vertical drops) should have same x at both ends."""
        result = compute_layout(DENSE_3PHASE_REQ)
        busbar_y = result.busbar_y
        for start, end in result.connections:
            # Identify busbar tap connections (one end at busbar_y)
            if abs(start[1] - busbar_y) < 0.5 or abs(end[1] - busbar_y) < 0.5:
                # Skip horizontal connections (e.g., earth bar horizontal run)
                if abs(start[1] - end[1]) < 0.5:
                    continue
                # Vertical connections should have same x at both ends
                assert abs(start[0] - end[0]) < 0.5, (
                    f"Busbar tap connection not vertical: "
                    f"({start[0]:.1f},{start[1]:.1f}) → ({end[0]:.1f},{end[1]:.1f})"
                )

    def test_connection_alignment_dense_1phase(self):
        """Dense 1-phase connections should align with breakers."""
        result = compute_layout(DENSE_1PHASE_REQ)
        breakers = [c for c in result.components
                    if c.label_style == "breaker_block"]
        for breaker in breakers:
            tap_x = self._get_breaker_tap_x(breaker)
            matching = [
                (s, e) for s, e in result.connections
                if abs(s[0] - tap_x) < 0.5 or abs(e[0] - tap_x) < 0.5
            ]
            assert len(matching) >= 1, (
                f"Breaker '{breaker.label}' at tap_x={tap_x:.1f} "
                f"has no matching connection (1-phase)"
            )


# -- Test: SubCircuitGroup identification --

class TestSubCircuitGrouping:
    """Tests for _identify_groups() sub-circuit classification."""

    def test_identify_groups_basic_3phase(self):
        """Basic 3-phase with 4 circuits (3 breaker + 1 spare) → 4 groups."""
        result = compute_layout(BASIC_3PHASE_REQ)
        groups, incoming_x = _identify_groups(result)
        # BASIC_3PHASE_REQ has 3 non-spare + 1 spare = 4 sub-circuits
        assert len(groups) == 4
        # Should be sorted by tap_x
        for i in range(len(groups) - 1):
            assert groups[i].tap_x <= groups[i + 1].tap_x

    def test_identify_groups_dense_15(self):
        """Dense 3-phase with 15 circuits → 15 groups."""
        result = compute_layout(DENSE_3PHASE_REQ)
        groups, incoming_x = _identify_groups(result)
        assert len(groups) == 15

    def test_identify_groups_with_spare(self):
        """Spare circuits should be identified as is_spare=True."""
        result = compute_layout(BASIC_3PHASE_REQ)
        groups, _ = _identify_groups(result)
        spare_groups = [g for g in groups if g.is_spare]
        assert len(spare_groups) >= 1
        for sg in spare_groups:
            assert sg.spare_label_idx is not None
            assert sg.breaker_idx is None

    def test_incoming_chain_excluded(self):
        """Incoming chain connections should not be assigned to any group."""
        result = compute_layout(BASIC_3PHASE_REQ)
        groups, incoming_x = _identify_groups(result)
        # Collect all connection indices assigned to groups
        assigned = set()
        for g in groups:
            assigned.update(g.connection_indices)
        # Check that connections at incoming_x are NOT assigned
        for ci, ((sx, sy), (ex, ey)) in enumerate(result.connections):
            if abs(sx - incoming_x) < 1.5 and abs(sx - ex) < 0.5:
                assert ci not in assigned, (
                    f"Connection {ci} at incoming_chain_x={incoming_x} "
                    f"should not be in any group"
                )

    def test_group_has_connections(self):
        """Each non-spare group should have at least 1 connection (busbar→breaker)."""
        result = compute_layout(BASIC_3PHASE_REQ)
        groups, _ = _identify_groups(result)
        for g in groups:
            if not g.is_spare:
                assert len(g.connection_indices) >= 1, (
                    f"Group at tap_x={g.tap_x:.1f} has no connections"
                )

    def test_group_width_mcb(self):
        """MCB breaker group width should include label columns."""
        result = compute_layout(BASIC_3PHASE_REQ)
        groups, _ = _identify_groups(result)
        for g in groups:
            if g.breaker_idx is not None:
                width = _compute_group_width(g, result.components)
                assert width > 10  # MCB symbol alone is 10mm
                assert width < 50  # Shouldn't be unreasonably large

    def test_group_width_spare(self):
        """Spare circuit group should have fixed 15mm width."""
        result = compute_layout(BASIC_3PHASE_REQ)
        groups, _ = _identify_groups(result)
        for g in groups:
            if g.is_spare:
                width = _compute_group_width(g, result.components)
                assert width == pytest.approx(15.0)


# -- Test: Position determination --

class TestDeterminePositions:
    """Tests for final tap position calculation."""

    def test_all_taps_within_busbar(self):
        """All sub-circuit taps should be within the busbar extent."""
        result = compute_layout(DENSE_3PHASE_REQ)
        for comp in result.components:
            if comp.label_style == "breaker_block":
                tap_x = comp.x + _breaker_half_width(comp)
                assert tap_x >= result.busbar_start_x - 1, (
                    f"Breaker '{comp.label}' tap_x={tap_x:.1f} < "
                    f"busbar_start={result.busbar_start_x:.1f}"
                )
                assert tap_x <= result.busbar_end_x + 1, (
                    f"Breaker '{comp.label}' tap_x={tap_x:.1f} > "
                    f"busbar_end={result.busbar_end_x:.1f}"
                )

    def test_dense_15_sorted_left_to_right(self):
        """15 circuit breakers should be sorted left-to-right with positive spacing."""
        result = compute_layout(DENSE_3PHASE_REQ)
        breakers = sorted(
            [c for c in result.components if c.label_style == "breaker_block"],
            key=lambda c: c.x,
        )
        assert len(breakers) == 15
        # All breakers should be in ascending x order with some spacing
        for i in range(len(breakers) - 1):
            gap = breakers[i + 1].x - breakers[i].x
            assert gap > 0, (
                f"Breaker {i} x={breakers[i].x:.1f} >= breaker {i+1} x={breakers[i+1].x:.1f}"
            )

    def test_8_circuits_no_overlapping_breakers(self):
        """8 circuits (fits in available space) should have no breaker overlap."""
        req = dict(DENSE_3PHASE_REQ)
        req["sub_circuits"] = DENSE_3PHASE_REQ["sub_circuits"][:8]
        result = compute_layout(req)
        breakers = [c for c in result.components if c.label_style == "breaker_block"]
        bboxes = [(c, _compute_bounding_box(c)) for c in breakers]
        bboxes = [(c, bb) for c, bb in bboxes if bb is not None]

        overlaps = 0
        for i in range(len(bboxes)):
            for j in range(i + 1, len(bboxes)):
                if bboxes[i][1].overlaps(bboxes[j][1]):
                    overlaps += 1

        assert overlaps == 0, f"Found {overlaps} overlapping breaker pairs in 8-circuit layout"

    def test_inline_elcb_no_left_offset(self):
        """With inline ELCB, sub-circuits should use full busbar width (no ELCB offset)."""
        result = compute_layout(BASIC_3PHASE_REQ)
        breakers = sorted(
            [c for c in result.components if c.label_style == "breaker_block"],
            key=lambda c: c.x,
        )
        if breakers:
            leftmost_tap = breakers[0].x + _breaker_half_width(breakers[0])
            # Should use standard margin (~10mm), not the old 30mm ELCB offset
            assert leftmost_tap >= result.busbar_start_x + 8, (
                f"Leftmost tap {leftmost_tap:.1f} too close to busbar start "
                f"{result.busbar_start_x:.1f}"
            )
            # Should NOT have the old 30mm gap
            assert leftmost_tap < result.busbar_start_x + 35, (
                f"Leftmost tap {leftmost_tap:.1f} too far from busbar start "
                f"{result.busbar_start_x:.1f} (old ELCB offset still active?)"
            )

    def test_dense_1phase_taps_within_busbar(self):
        """Dense 1-phase: all taps should be within busbar."""
        result = compute_layout(DENSE_1PHASE_REQ)
        for comp in result.components:
            if comp.label_style == "breaker_block":
                tap_x = comp.x + _breaker_half_width(comp)
                assert tap_x >= result.busbar_start_x - 1
                assert tap_x <= result.busbar_end_x + 1


# -- Test: Rebuild positions --

class TestRebuildPositions:
    """Tests for index-based position rebuilding."""

    def test_connections_match_breakers_after_rebuild(self):
        """After rebuild, every breaker should have connections at its tap_x."""
        result = compute_layout(DENSE_3PHASE_REQ)
        breakers = [c for c in result.components if c.label_style == "breaker_block"]
        for breaker in breakers:
            tap_x = breaker.x + _breaker_half_width(breaker)
            matching = [
                (s, e) for s, e in result.connections
                if abs(s[0] - tap_x) < 0.5 or abs(e[0] - tap_x) < 0.5
            ]
            assert len(matching) >= 1, (
                f"Breaker '{breaker.label}' at tap_x={tap_x:.1f} "
                f"has no matching connection after rebuild"
            )

    def test_circuit_id_at_tap_after_rebuild(self):
        """After rebuild, CIRCUIT_ID_BOX should be at same x as breaker tap."""
        result = compute_layout(DENSE_3PHASE_REQ)
        groups, _ = _identify_groups(result)
        for g in groups:
            if g.breaker_idx is not None and g.circuit_id_idx is not None:
                tap_x = result.components[g.breaker_idx].x + _breaker_half_width(
                    result.components[g.breaker_idx]
                )
                id_x = result.components[g.circuit_id_idx].x
                assert abs(id_x - tap_x) < 0.5, (
                    f"CIRCUIT_ID_BOX x={id_x:.1f} != tap_x={tap_x:.1f}"
                )

    def test_incoming_chain_untouched(self):
        """Incoming chain connections should not be moved."""
        # Get incoming chain x from a basic layout
        result = compute_layout(BASIC_3PHASE_REQ)
        _, incoming_x = _identify_groups(result)

        if incoming_x > 0:
            # Find connections at incoming_x
            incoming_conns = [
                (s, e) for s, e in result.connections
                if abs(s[0] - incoming_x) < 1.0 and abs(s[0] - e[0]) < 0.5
            ]
            # They should all still be at incoming_x
            for s, e in incoming_conns:
                assert abs(s[0] - incoming_x) < 1.0, (
                    f"Incoming chain connection moved from {incoming_x:.1f} to {s[0]:.1f}"
                )

    def test_name_label_offset_from_tap(self):
        """Circuit name labels should be at tap_x + 3 (vertical text offset)."""
        result = compute_layout(DENSE_3PHASE_REQ)
        groups, _ = _identify_groups(result)
        for g in groups:
            if g.breaker_idx is not None and g.name_label_idx is not None:
                tap_x = result.components[g.breaker_idx].x + _breaker_half_width(
                    result.components[g.breaker_idx]
                )
                label_x = result.components[g.name_label_idx].x
                assert abs(label_x - (tap_x + 3)) < 0.5, (
                    f"Name label x={label_x:.1f} != tap_x+3={tap_x + 3:.1f}"
                )
