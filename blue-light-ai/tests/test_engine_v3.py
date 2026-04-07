"""v3 엔진 테스트: 기존 v2와 동일한 출력을 생성하는지 검증.

v3는 Measure → Allocate → Place(4-step) 파이프라인을 사용하지만,
현재 Phase 3는 기존 섹션 함수를 그대로 호출하므로 출력이 동일해야 한다.
"""

import pytest

from app.sld.layout.engine import compute_layout
from app.sld.layout.engine_v3 import compute_layout_v3


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture
def simple_3p():
    return {
        "supply_type": "three_phase", "voltage": 400,
        "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 10},
        "elcb": {"rating": 63, "sensitivity_ma": 100},
        "sub_circuits": [
            {"name": f"Light {i}", "breaker_type": "MCB", "rating": 6}
            for i in range(12)
        ],
        "busbar_rating": 63,
    }


@pytest.fixture
def ct_metering():
    return {
        "supply_type": "three_phase", "voltage": 400,
        "metering": "ct_meter",
        "main_breaker": {"type": "MCCB", "rating": 150, "poles": "TPN", "fault_kA": 25},
        "sub_circuits": [
            {"name": f"C{i}", "breaker_type": "MCB", "rating": 20}
            for i in range(18)
        ],
        "busbar_rating": 150,
    }


@pytest.fixture
def sp_meter():
    return {
        "supply_type": "three_phase", "voltage": 400,
        "metering": "sp_meter",
        "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 10},
        "elcb": {"rating": 63, "sensitivity_ma": 100},
        "sub_circuits": [
            {"name": f"S{i}", "breaker_type": "MCB", "rating": 16}
            for i in range(9)
        ],
        "busbar_rating": 63,
    }


@pytest.fixture
def single_phase():
    return {
        "supply_type": "single_phase", "voltage": 230,
        "main_breaker": {"type": "MCB", "rating": 40, "poles": "SPN", "fault_kA": 6},
        "elcb": {"rating": 40, "sensitivity_ma": 30},
        "sub_circuits": [
            {"name": f"Ckt{i}", "breaker_type": "MCB", "rating": 10}
            for i in range(6)
        ],
        "busbar_rating": 40,
    }


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestV3Parity:
    """v3 엔진이 v2와 동일한 출력을 생성하는지 검증."""

    @pytest.mark.parametrize("fixture_name", [
        "simple_3p", "ct_metering", "sp_meter", "single_phase",
    ])
    def test_component_count_matches(self, fixture_name, request):
        req = request.getfixturevalue(fixture_name)
        r2 = compute_layout(dict(req))
        r3 = compute_layout_v3(dict(req))
        assert len(r3.components) == len(r2.components), \
            f"Component count: v2={len(r2.components)}, v3={len(r3.components)}"

    @pytest.mark.parametrize("fixture_name", [
        "simple_3p", "ct_metering", "sp_meter", "single_phase",
    ])
    def test_connection_count_matches(self, fixture_name, request):
        req = request.getfixturevalue(fixture_name)
        r2 = compute_layout(dict(req))
        r3 = compute_layout_v3(dict(req))
        assert len(r3.resolved_connections(style_filter={"normal"})) == len(r2.resolved_connections(style_filter={"normal"})), \
            f"Connection count: v2={len(r2.resolved_connections(style_filter={'normal'}))}, v3={len(r3.resolved_connections(style_filter={'normal'}))}"

    @pytest.mark.parametrize("fixture_name", [
        "simple_3p", "ct_metering", "sp_meter", "single_phase",
    ])
    def test_busbar_position_valid(self, fixture_name, request):
        """v3 busbar Y가 페이지 범위 내에 있고, 스파인 중간에 위치하는지 확인.
        Region 기반 배치는 중앙 정렬이므로 v2와 정확히 같지 않을 수 있다."""
        req = request.getfixturevalue(fixture_name)
        r3 = compute_layout_v3(dict(req))
        # Busbar Y가 페이지 범위 내
        assert 62 < r3.busbar_y < 285, \
            f"Busbar Y={r3.busbar_y:.1f} outside page bounds"
        # Busbar X range가 양수 폭을 가짐
        assert r3.busbar_end_x > r3.busbar_start_x

    @pytest.mark.parametrize("fixture_name", [
        "simple_3p", "ct_metering", "sp_meter", "single_phase",
    ])
    def test_sections_rendered_match(self, fixture_name, request):
        req = request.getfixturevalue(fixture_name)
        r2 = compute_layout(dict(req))
        r3 = compute_layout_v3(dict(req))
        v2_sections = {k for k, v in r2.sections_rendered.items() if v}
        v3_sections = {k for k, v in r3.sections_rendered.items() if v}
        assert v3_sections == v2_sections, \
            f"Sections rendered differ: v2={v2_sections}, v3={v3_sections}"

    @pytest.mark.parametrize("fixture_name", [
        "simple_3p", "ct_metering", "sp_meter", "single_phase",
    ])
    def test_allocation_plan_attached(self, fixture_name, request):
        """v3 result should have allocation plan info via config."""
        req = request.getfixturevalue(fixture_name)
        r3 = compute_layout_v3(dict(req))
        assert r3.config is not None

    @pytest.mark.parametrize("fixture_name", [
        "simple_3p", "ct_metering", "sp_meter", "single_phase",
    ])
    def test_no_overflow(self, fixture_name, request):
        """v3 region 기반 배치는 중앙 정렬하므로 표준 픽스처에서 오버플로우가 없어야 한다."""
        req = request.getfixturevalue(fixture_name)
        r3 = compute_layout_v3(dict(req))
        v3_overflow = r3.overflow_metrics.has_overflow if r3.overflow_metrics else False
        assert not v3_overflow, \
            f"v3 has overflow: {r3.overflow_metrics}"
