"""
SLD 섹션 완전성 검증 — 모든 입력 조합에서 기대 섹션이 렌더링되는지 확인.

입력 변수(supply_source × metering × cable_extension)의 모든 유효 조합에 대해:
1. 항상 렌더링되어야 하는 섹션(main_breaker, main_busbar 등)이 존재하는지
2. 조건부 섹션(meter_board, unit_isolator, ct_metering 등)이 정확히 렌더링되는지
3. 상호 배타적 섹션(meter_board ↔ unit_isolator)이 동시에 나타나지 않는지

이 테스트는 "없는 것을 못 본다" 문제를 방지하기 위해 만들어졌다.
이전에 building_riser + ct_meter 조합에서 unit_isolator가 렌더링되지 않은 버그가
시각적 비교에서 발견되지 못했던 경험에서 비롯된다.
"""

import pytest

from app.sld.layout.engine import compute_layout
from app.sld.layout.models import LayoutConfig


# =====================================================================
# Helpers
# =====================================================================

def _cable(mm2: float, cpc_mm2: float | None = None, method: str = "METAL TRUNKING") -> str:
    cpc = cpc_mm2 or mm2
    return f"2 x 1C {mm2}sqmm PVC + {cpc}sqmm PVC CPC IN {method}"


def _lighting(name: str = "LIGHTING", rating: int = 10) -> dict:
    return {
        "name": name,
        "breaker_type": "MCB",
        "breaker_rating": rating,
        "breaker_characteristic": "B",
        "cable": _cable(1.5, 1.5),
    }


def _power(name: str = "POWER POINTS", rating: int = 20) -> dict:
    return {
        "name": name,
        "breaker_type": "MCB",
        "breaker_rating": rating,
        "breaker_characteristic": "B",
        "cable": _cable(2.5, 2.5),
    }


def _make_requirements(
    supply_source: str = "sp_powergrid",
    metering: str | None = "sp_meter",
    is_cable_extension: bool = False,
    supply_type: str = "three_phase",
    breaker_rating: int = 100,
    num_circuits: int = 4,
    elcb_rating: int = 0,
    ct_ratio: str = "",
) -> dict:
    """Build a minimal requirements dict for a given input combination."""
    circuits = []
    for i in range(num_circuits):
        if i % 2 == 0:
            circuits.append(_lighting(f"LIGHTING {i+1}"))
        else:
            circuits.append(_power(f"POWER {i+1}"))

    req: dict = {
        "supply_type": supply_type,
        "supply_source": supply_source,
        "kva": 30,
        "voltage": 400 if supply_type == "three_phase" else 230,
        "main_breaker": {
            "type": "MCCB" if breaker_rating >= 100 else "MCB",
            "rating": breaker_rating,
            "poles": "TPN" if supply_type == "three_phase" else "DP",
            "fault_kA": 10,
        },
        "busbar_rating": max(breaker_rating, 63),
        "sub_circuits": circuits,
    }

    if metering:
        req["metering"] = metering
    # When metering is None: omit key → sld_spec auto-determines (usually sp_meter)

    if is_cable_extension:
        req["is_cable_extension"] = True

    if elcb_rating:
        req["elcb"] = {
            "rating": elcb_rating,
            "sensitivity_ma": 30,
            "poles": 4 if supply_type == "three_phase" else 2,
            "type": "RCCB",
        }

    if ct_ratio:
        req["metering_config"] = {
            "ct_ratio": ct_ratio,
            "metering_ct_class": "CL1 5VA",
            "protection_ct_class": "5P10 20VA",
        }

    if supply_source in ("landlord", "building_riser") and not metering:
        req["isolator"] = {
            "rating": breaker_rating,
            "type": "4P",
            "location_text": "LOCATED INSIDE UNIT",
        }

    return req


# =====================================================================
# Constants — section categories
# =====================================================================

# Sections that ALWAYS render in single-DB path
ALWAYS_RENDERED = {
    "main_breaker",
    "main_busbar",
    "sub_circuits",
    "db_box",
    "earth_bar",
}

# =====================================================================
# Parametrized test data: (supply_source, metering, cable_ext) → expected conditional sections
# =====================================================================

_COMBINATIONS = {
    # ── SP PowerGrid supply ──
    # sp_meter: meter board handles incoming, isolator is inside meter board
    ("sp_powergrid", "sp_meter", False): {"meter_board"},
    # ct_meter (≥125A 3-phase): incoming supply + isolator + CT spine + fuse
    ("sp_powergrid", "ct_meter", False): {"incoming_supply", "unit_isolator", "ct_metering_section", "ct_pre_mccb_fuse"},

    # ── Landlord supply ──
    # sp_meter: meter board (default for landlord — sld_spec auto-corrects empty to sp_meter)
    ("landlord", "sp_meter", False): {"meter_board"},
    # Cable extension: forces metering=None → no meter board, no isolator (cable_ext skip)
    ("landlord", "sp_meter", True): {"incoming_supply"},

    # ── Building riser supply ──
    # sp_meter: meter board handles incoming
    ("building_riser", "sp_meter", False): {"meter_board"},
    # ct_meter (≥125A 3-phase): incoming + isolator + CT spine + fuse
    ("building_riser", "ct_meter", False): {"incoming_supply", "unit_isolator", "ct_metering_section", "ct_pre_mccb_fuse"},
}


def _make_test_id(supply: str, metering: str | None, cable_ext: bool) -> str:
    m = metering or "none"
    ext = "_cableext" if cable_ext else ""
    return f"{supply}_{m}{ext}"


_PARAMS = [
    pytest.param(k[0], k[1], k[2], v, id=_make_test_id(k[0], k[1], k[2]))
    for k, v in _COMBINATIONS.items()
]


# =====================================================================
# Tests
# =====================================================================

class TestSectionCompleteness:
    """각 입력 조합에서 기대 섹션이 모두 렌더링되는지 검증."""

    @pytest.mark.parametrize("supply,metering,cable_ext,expected_conditional", _PARAMS)
    def test_always_rendered_sections(self, supply, metering, cable_ext, expected_conditional):
        """항상 렌더링되어야 하는 섹션 (main_breaker, busbar, circuits, db_box, earth_bar)."""
        req = _make_requirements(
            supply_source=supply,
            metering=metering,
            is_cable_extension=cable_ext,
            # CT metering needs ≥125A 3-phase
            breaker_rating=150 if metering == "ct_meter" else 100,
            ct_ratio="100/5A" if metering == "ct_meter" else "",
        )
        result = compute_layout(req)
        rendered = result.sections_rendered

        for section in ALWAYS_RENDERED:
            assert rendered.get(section), (
                f"'{section}' 미렌더링 — 조합: {supply}/{metering}/cable_ext={cable_ext}\n"
                f"  sections_rendered = {rendered}"
            )

    @pytest.mark.parametrize("supply,metering,cable_ext,expected_conditional", _PARAMS)
    def test_conditional_sections_present(self, supply, metering, cable_ext, expected_conditional):
        """조건부 섹션이 기대대로 렌더링되는지 검증."""
        req = _make_requirements(
            supply_source=supply,
            metering=metering,
            is_cable_extension=cable_ext,
            breaker_rating=150 if metering == "ct_meter" else 100,
            ct_ratio="100/5A" if metering == "ct_meter" else "",
        )
        result = compute_layout(req)
        rendered = result.sections_rendered

        for section in expected_conditional:
            assert rendered.get(section), (
                f"'{section}' 미렌더링 — 조합: {supply}/{metering}/cable_ext={cable_ext}\n"
                f"  sections_rendered = {rendered}"
            )


class TestSectionMutualExclusion:
    """상호 배타적 섹션이 동시에 렌더링되지 않는지 검증."""

    @pytest.mark.parametrize("supply,metering,cable_ext,expected_conditional", _PARAMS)
    def test_meter_board_vs_unit_isolator(self, supply, metering, cable_ext, expected_conditional):
        """meter_board가 렌더링되면 unit_isolator는 렌더링되지 않아야 함.

        meter_board는 내부에 아이솔레이터를 포함하므로 별도 unit_isolator는 중복.
        """
        req = _make_requirements(
            supply_source=supply,
            metering=metering,
            is_cable_extension=cable_ext,
            breaker_rating=150 if metering == "ct_meter" else 100,
            ct_ratio="100/5A" if metering == "ct_meter" else "",
        )
        result = compute_layout(req)
        r = result.sections_rendered

        if r.get("meter_board"):
            assert not r.get("unit_isolator"), (
                "meter_board와 unit_isolator 동시 렌더링 — 아이솔레이터 중복\n"
                f"  조합: {supply}/{metering}/cable_ext={cable_ext}\n"
                f"  sections_rendered = {r}"
            )

    @pytest.mark.parametrize("supply,metering,cable_ext,expected_conditional", _PARAMS)
    def test_ct_metering_implies_unit_isolator(self, supply, metering, cable_ext, expected_conditional):
        """ct_metering_section이 렌더링되면 unit_isolator도 반드시 있어야 함.

        CT 계측에서는 meter_board가 없으므로 unit_isolator가 반드시 필요.
        """
        req = _make_requirements(
            supply_source=supply,
            metering=metering,
            is_cable_extension=cable_ext,
            breaker_rating=150 if metering == "ct_meter" else 100,
            ct_ratio="100/5A" if metering == "ct_meter" else "",
        )
        result = compute_layout(req)
        r = result.sections_rendered

        if r.get("ct_metering_section"):
            assert r.get("unit_isolator"), (
                "ct_metering_section 있는데 unit_isolator 없음 — 인입부 아이솔레이터 누락\n"
                f"  조합: {supply}/{metering}/cable_ext={cable_ext}\n"
                f"  sections_rendered = {r}"
            )

    @pytest.mark.parametrize("supply,metering,cable_ext,expected_conditional", _PARAMS)
    def test_ct_fuse_only_with_ct_metering(self, supply, metering, cable_ext, expected_conditional):
        """ct_pre_mccb_fuse는 ct_metering_section 없이 렌더링되지 않아야 함."""
        req = _make_requirements(
            supply_source=supply,
            metering=metering,
            is_cable_extension=cable_ext,
            breaker_rating=150 if metering == "ct_meter" else 100,
            ct_ratio="100/5A" if metering == "ct_meter" else "",
        )
        result = compute_layout(req)
        r = result.sections_rendered

        if r.get("ct_pre_mccb_fuse") and not r.get("ct_metering_section"):
            pytest.fail(
                "ct_pre_mccb_fuse 있는데 ct_metering_section 없음\n"
                f"  조합: {supply}/{metering}/cable_ext={cable_ext}\n"
                f"  sections_rendered = {r}"
            )


class TestSectionCompletenessIncoming:
    """incoming_supply 섹션 렌더링 조건 검증.

    SP metered supply(sp_meter)는 meter_board가 incoming을 처리하므로
    incoming_supply 섹션이 스킵됨. 그 외에는 항상 렌더링.
    """

    @pytest.mark.parametrize("supply,metering,cable_ext,expected_conditional", _PARAMS)
    def test_incoming_supply_presence(self, supply, metering, cable_ext, expected_conditional):
        req = _make_requirements(
            supply_source=supply,
            metering=metering,
            is_cable_extension=cable_ext,
            breaker_rating=150 if metering == "ct_meter" else 100,
            ct_ratio="100/5A" if metering == "ct_meter" else "",
        )
        result = compute_layout(req)
        r = result.sections_rendered

        if "incoming_supply" in expected_conditional:
            assert r.get("incoming_supply"), (
                f"incoming_supply 미렌더링 — 조합: {supply}/{metering}/cable_ext={cable_ext}\n"
                f"  sections_rendered = {r}"
            )
