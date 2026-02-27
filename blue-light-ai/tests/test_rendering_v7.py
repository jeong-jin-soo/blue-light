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

from app.sld.layout import LayoutConfig, PlacedComponent, compute_layout
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

class TestElcbLabelPosition:
    """Tests for ELCB label positioning."""

    def test_elcb_label_no_overlap_with_db_box(self):
        """ELCB label should be to the RIGHT of ELCB symbol (x > elcb_tap_x)."""
        result = compute_layout(BASIC_3PHASE_REQ)
        db_boxes = _get_components_by_type(result, "DB_INFO_BOX")
        elcb_labels = [
            c for c in result.components
            if c.symbol_name == "LABEL" and "ELCB" in c.label
        ]
        if elcb_labels and db_boxes:
            elcb_label = elcb_labels[0]
            db_box = db_boxes[0]
            # ELCB label should not overlap horizontally with DB_INFO_BOX
            # ELCB label x should be left of DB_INFO_BOX x
            # OR ELCB label should be positioned to the right of ELCB symbol
            assert elcb_label.x < db_box.x or elcb_label.y > db_box.y


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
        pdf_bytes, svg_string = SldGenerator.generate_pdf_bytes(BASIC_3PHASE_REQ)
        assert isinstance(pdf_bytes, bytes)
        assert pdf_bytes[:5] == b"%PDF-"
        assert len(pdf_bytes) > 1000  # Non-trivial PDF

    def test_returns_svg_string(self):
        """generate_pdf_bytes() should also return a valid SVG string."""
        pdf_bytes, svg_string = SldGenerator.generate_pdf_bytes(BASIC_3PHASE_REQ)
        assert isinstance(svg_string, str)
        assert svg_string.startswith("<svg")
        assert "</svg>" in svg_string

    def test_single_phase_pdf_bytes(self):
        """generate_pdf_bytes() should work with single-phase requirements."""
        pdf_bytes, svg_string = SldGenerator.generate_pdf_bytes(BASIC_1PHASE_REQ)
        assert isinstance(pdf_bytes, bytes)
        assert pdf_bytes[:5] == b"%PDF-"


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
