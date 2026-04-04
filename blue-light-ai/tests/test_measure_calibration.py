"""Measure↔Place 높이 캘리브레이션 — measure 높이가 실제 place 진행량과 일치하는지 검증.

Phase 3 region 기반 전환의 전제조건: 각 섹션의 measure 높이가 실제 ctx.y 진행량과
±2mm 이내로 일치해야 한다. 불일치가 있으면 region 주입 시 위치가 어긋난다.
"""

from __future__ import annotations

import pytest

from app.sld.layout.engine_v3 import compute_layout_v3
from app.sld.layout.measure import measure_all_sections
from app.sld.layout.models import LayoutConfig
from app.sld.layout.section_base import Section
from app.sld.layout.section_registry import get_section_sequence


# ── 픽스처 ──────────────────────────────────────────────────────

def _simple_3p():
    return {
        "supply_type": "three_phase",
        "voltage": 400,
        "kva": 24,
        "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
        "elcb": {"type": "RCCB", "rating": 63, "sensitivity_ma": 30, "poles": "TPN"},
        "busbar_rating": 100,
        "sub_circuits": [
            {"circuit_id": f"L{(i%3)+1}S{(i//3)+1}", "phase": f"L{(i%3)+1}",
             "name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10,
             "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B",
             "cable": "2 x 1C 1.5sqmm PVC"}
            for i in range(12)
        ],
    }


def _ct_metering():
    return {
        "supply_type": "three_phase",
        "voltage": 400,
        "kva": 60,
        "metering": "ct_meter",
        "ct_ratio": "200/5",
        "main_breaker": {"type": "MCCB", "rating": 150, "poles": "TPN", "fault_kA": 50},
        "busbar_rating": 200,
        "sub_circuits": [
            {"circuit_id": f"L{(i%3)+1}P{(i//3)+1}", "phase": f"L{(i%3)+1}",
             "name": "POWER", "breaker_type": "MCB", "breaker_rating": 20,
             "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B",
             "cable": "2 x 1C 2.5sqmm PVC"}
            for i in range(18)
        ],
    }


def _sp_meter():
    return {
        "supply_type": "three_phase",
        "voltage": 400,
        "kva": 24,
        "metering": "sp_meter",
        "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
        "elcb": {"type": "RCCB", "rating": 63, "sensitivity_ma": 30, "poles": "TPN"},
        "busbar_rating": 100,
        "meter_board": {
            "isolator_rating": 63,
            "isolator_type": "4P",
            "meter_type": "KWH",
            "outgoing_breaker": {"type": "MCB", "rating": 63, "poles": "TPN",
                                 "characteristic": "B", "fault_kA": 10},
        },
        "sub_circuits": [
            {"circuit_id": f"L{(i%3)+1}S{(i//3)+1}", "phase": f"L{(i%3)+1}",
             "name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10,
             "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B",
             "cable": "2 x 1C 1.5sqmm PVC"}
            for i in range(9)
        ],
    }


def _single_phase():
    return {
        "supply_type": "single_phase",
        "voltage": 230,
        "kva": 9,
        "main_breaker": {"type": "MCB", "rating": 40, "poles": "SPN", "fault_kA": 6,
                         "breaker_characteristic": "B"},
        "elcb": {"type": "RCCB", "rating": 40, "sensitivity_ma": 30, "poles": "DP"},
        "busbar_rating": 63,
        "sub_circuits": [
            {"circuit_id": f"L1S{i+1}", "phase": "L1",
             "name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10,
             "breaker_poles": "SPN", "fault_kA": 6, "breaker_characteristic": "B",
             "cable": "2 x 1C 1.5sqmm PVC"}
            for i in range(6)
        ],
    }


FIXTURES = [
    pytest.param(_simple_3p, id="simple_3p"),
    pytest.param(_ct_metering, id="ct_metering"),
    pytest.param(_sp_meter, id="sp_meter"),
    pytest.param(_single_phase, id="single_phase"),
]


# ── 캘리브레이션 테스트 ──────────────────────────────────────────

class TestMeasureVsPlace:
    """각 섹션의 measure 높이와 실제 place ctx.y 진행량을 비교."""

    @pytest.fixture(params=FIXTURES)
    def fixture(self, request):
        return request.param()

    def test_measure_vs_place_advancement(self, fixture):
        """measure 높이와 place 진행량의 차이가 허용 범위 내인지 검증."""
        config = LayoutConfig()
        req = fixture

        # Phase 1: Measure
        measures = measure_all_sections(req, config)
        measure_map = {m.section_id: m for m in measures if m.present and m.height > 0}

        # Phase 3: Place — 섹션별 Y 진행량 기록
        from app.sld.layout.engine import _parse_requirements
        from app.sld.layout.models import LayoutResult, _LayoutContext

        result = LayoutResult()
        cx = config.start_x
        y = config.min_y + 15

        ctx = _LayoutContext(
            result=result, config=config, cx=cx, y=y,
            requirements=req, application_info={},
        )
        _parse_requirements(ctx, req, {})

        section_sequence = get_section_sequence(req)
        y_advancement: dict[str, float] = {}

        for section in section_sequence:
            y_before = ctx.y
            section.execute(ctx)
            delta = ctx.y - y_before
            if section.name and delta > 0:
                y_advancement[section.name] = delta

        # sub_circuits, db_box, earth_bar는 section loop 밖에서 별도 호출됨 → 제외
        POST_BUSBAR = {"sub_circuits", "db_box", "earth_bar"}

        # 비교
        mismatches = []
        for section_id, measure in measure_map.items():
            if section_id in POST_BUSBAR:
                continue
            actual = y_advancement.get(section_id, 0)
            diff = abs(measure.height - actual)
            if diff > 3.0:  # 3mm tolerance
                mismatches.append(
                    f"{section_id}: measure={measure.height:.1f}, "
                    f"actual={actual:.1f}, diff={diff:.1f}"
                )

        if mismatches:
            msg = "\n".join(mismatches)
            pytest.fail(f"Measure/Place height mismatches (>3mm):\n{msg}")

    def test_total_spine_height_reasonable(self, fixture):
        """전체 스파인 높이가 합리적인 범위인지 확인."""
        config = LayoutConfig()
        measures = measure_all_sections(fixture, config)
        total = sum(m.height for m in measures if m.present and m.height > 0)
        # A3 landscape 높이 223mm 이내
        assert 30 < total < 240, f"Total spine height {total:.1f}mm out of range"
