"""Phase 1 (Measure) + Phase 2 (Allocate) 유닛 테스트.

측정 함수의 정확성과 할당 알고리즘의 안정성을 검증한다.
"""

import pytest

from app.sld.layout.measure import (
    SECTION_ORDER,
    measure_all_sections,
)
from app.sld.layout.allocate import allocate, A3_LANDSCAPE, PageSpec
from app.sld.layout.models import LayoutConfig, SectionMeasure


# ---------------------------------------------------------------------------
# Test fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def simple_3p():
    """3상 주거용 12회로, ELCB 있음, 미터링 없음."""
    return {
        "supply_type": "three_phase",
        "voltage": 400,
        "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 10},
        "elcb": {"rating": 63, "sensitivity_ma": 100},
        "sub_circuits": [
            {"name": f"Light {i}", "breaker_type": "MCB", "rating": 6}
            for i in range(12)
        ],
    }


@pytest.fixture
def ct_metering_150a():
    """CT metering, 150A, ELCB 없음."""
    return {
        "supply_type": "three_phase",
        "voltage": 400,
        "metering": "ct_meter",
        "main_breaker": {"type": "MCCB", "rating": 150, "poles": "TPN", "fault_kA": 25},
        "sub_circuits": [
            {"name": f"Circuit {i}", "breaker_type": "MCB", "rating": 20}
            for i in range(18)
        ],
    }


@pytest.fixture
def sp_meter_elcb():
    """SP meter + ELCB."""
    return {
        "supply_type": "three_phase",
        "voltage": 400,
        "metering": "sp_meter",
        "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 10},
        "elcb": {"rating": 63, "sensitivity_ma": 100},
        "sub_circuits": [
            {"name": f"Socket {i}", "breaker_type": "MCB", "rating": 16}
            for i in range(9)
        ],
    }


@pytest.fixture
def single_phase():
    """단상 소형."""
    return {
        "supply_type": "single_phase",
        "voltage": 230,
        "main_breaker": {"type": "MCB", "rating": 40, "poles": "SPN", "fault_kA": 6},
        "elcb": {"rating": 40, "sensitivity_ma": 30},
        "sub_circuits": [
            {"name": f"Ckt{i}", "breaker_type": "MCB", "rating": 10}
            for i in range(6)
        ],
    }


@pytest.fixture
def large_3p():
    """3상 대형 26회로."""
    return {
        "supply_type": "three_phase",
        "voltage": 400,
        "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
        "elcb": {"rating": 100, "sensitivity_ma": 100},
        "sub_circuits": [
            {"name": f"C{i}", "breaker_type": "MCB", "rating": 20}
            for i in range(26)
        ],
    }


# ---------------------------------------------------------------------------
# Phase 1: Measure tests
# ---------------------------------------------------------------------------

class TestMeasure:
    """Phase 1 (Measure) 함수 테스트."""

    def test_section_order_complete(self):
        """SECTION_ORDER에 12개 섹션이 모두 포함."""
        assert len(SECTION_ORDER) == 12

    def test_measure_returns_all_sections(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        assert len(measures) == 12
        ids = [m.section_id for m in measures]
        assert ids == SECTION_ORDER

    def test_total_height_positive(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        total = sum(m.height for m in measures if m.present)
        assert total > 50.0  # At least 50mm

    def test_total_height_fits_a3(self, simple_3p):
        """단순 케이스는 A3에 들어가야 한다 (scale 1.0)."""
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        total = sum(m.height for m in measures if m.present)
        assert total <= 240.0  # A3 drawing height

    def test_ct_metering_present(self, ct_metering_150a):
        config = LayoutConfig()
        measures = measure_all_sections(ct_metering_150a, config)
        ct_m = next(m for m in measures if m.section_id == "ct_metering")
        assert ct_m.present is True
        assert ct_m.height > 30.0

    def test_ct_metering_absent_for_non_ct(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        ct_m = next(m for m in measures if m.section_id == "ct_metering")
        assert ct_m.present is False

    def test_meter_board_present_for_sp(self, sp_meter_elcb):
        config = LayoutConfig()
        measures = measure_all_sections(sp_meter_elcb, config)
        mb_m = next(m for m in measures if m.section_id == "meter_board")
        assert mb_m.present is True
        assert mb_m.height > 15.0

    def test_meter_board_absent_for_no_metering(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        mb_m = next(m for m in measures if m.section_id == "meter_board")
        assert mb_m.present is False

    def test_elcb_present_when_configured(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        elcb_m = next(m for m in measures if m.section_id == "elcb")
        assert elcb_m.present is True
        assert elcb_m.height > 10.0

    def test_elcb_absent_when_not_configured(self, ct_metering_150a):
        config = LayoutConfig()
        measures = measure_all_sections(ct_metering_150a, config)
        elcb_m = next(m for m in measures if m.section_id == "elcb")
        assert elcb_m.present is False

    def test_sub_circuits_width_positive(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        sc_m = next(m for m in measures if m.section_id == "sub_circuits")
        assert sc_m.min_width > 50.0

    def test_main_breaker_always_present(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        mb_m = next(m for m in measures if m.section_id == "main_breaker")
        assert mb_m.present is True

    def test_exports_populated(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        mb_m = next(m for m in measures if m.section_id == "main_breaker")
        assert "arc_center_y_offset" in mb_m.exports


# ---------------------------------------------------------------------------
# Phase 2: Allocate tests
# ---------------------------------------------------------------------------

class TestAllocate:
    """Phase 2 (Allocate) 함수 테스트."""

    def test_scale_1_for_simple(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        plan = allocate(measures, config)
        assert plan.scale == 1.0

    def test_all_sections_have_regions(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        plan = allocate(measures, config)
        present_ids = {m.section_id for m in measures if m.present and m.height > 0}
        for sid in present_ids:
            assert sid in plan.section_regions, f"Missing region for {sid}"

    def test_regions_within_page(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        plan = allocate(measures, config)
        for sid, region in plan.section_regions.items():
            assert region.y_start >= A3_LANDSCAPE.drawing_y_start - 1, \
                f"{sid}: y_start {region.y_start} below page"
            assert region.y_end <= A3_LANDSCAPE.drawing_y_end + 1, \
                f"{sid}: y_end {region.y_end} above page"

    def test_regions_non_overlapping(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        plan = allocate(measures, config)
        regions = sorted(plan.section_regions.values(), key=lambda r: r.y_start)
        for i in range(len(regions) - 1):
            assert regions[i].y_end <= regions[i + 1].y_start + 0.01, \
                f"Overlap between {regions[i].section_id} and {regions[i+1].section_id}"

    def test_busbar_within_page(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        plan = allocate(measures, config)
        bx_start, bx_end = plan.busbar_x_range
        assert bx_start >= A3_LANDSCAPE.drawing_x_start
        assert bx_end <= A3_LANDSCAPE.drawing_x_end

    def test_spine_centered(self, simple_3p):
        config = LayoutConfig()
        measures = measure_all_sections(simple_3p, config)
        plan = allocate(measures, config)
        assert plan.spine_x == A3_LANDSCAPE.center_x

    def test_scale_reduces_for_large(self):
        """많은 섹션을 가진 SLD는 scale < 1.0이 될 수 있다."""
        # Create a tiny page to force scaling
        tiny_page = PageSpec(
            drawing_y_start=100, drawing_y_end=200,
            drawing_x_start=50, drawing_x_end=350,
        )
        req = {
            "supply_type": "three_phase",
            "voltage": 400,
            "metering": "ct_meter",
            "main_breaker": {"type": "MCCB", "rating": 200, "poles": "TPN", "fault_kA": 25},
            "elcb": {"rating": 200, "sensitivity_ma": 100},
            "sub_circuits": [{"name": f"C{i}", "breaker_type": "MCB", "rating": 20} for i in range(24)],
        }
        config = LayoutConfig()
        measures = measure_all_sections(req, config)
        plan = allocate(measures, config, page=tiny_page)
        # Total > 100mm (page height), so scale should be < 1.0
        total = sum(m.height for m in measures if m.present)
        if total > 100.0:
            assert plan.scale < 1.0

    def test_ct_metering_has_more_sections(self, ct_metering_150a):
        config = LayoutConfig()
        measures = measure_all_sections(ct_metering_150a, config)
        plan = allocate(measures, config)
        assert plan.has_section("ct_metering")
        assert plan.has_section("ct_pre_mccb_fuse")

    @pytest.mark.parametrize("fixture_name", [
        "simple_3p", "ct_metering_150a", "sp_meter_elcb", "single_phase", "large_3p",
    ])
    def test_all_fixtures_allocate_successfully(self, fixture_name, request):
        """모든 픽스처가 에러 없이 할당 완료."""
        req = request.getfixturevalue(fixture_name)
        config = LayoutConfig()
        measures = measure_all_sections(req, config)
        plan = allocate(measures, config)
        assert plan.total_height > 0
        assert len(plan.section_regions) > 0
