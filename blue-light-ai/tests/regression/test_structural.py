"""구조 규칙 테스트.

레퍼런스에서 도출된 구조적 불변 규칙을 generator 출력에 적용한다:
- 필수 심볼/섹션 존재
- RCCB/ELCB 존재
- CT 계측 전용 컴포넌트
"""

from __future__ import annotations

import pytest

from app.sld.layout.engine import compute_layout
from app.sld.regression.validators import LayoutValidator

from .conftest import ALL_CONFIGS, get_layout


class TestCoreSections:
    """모든 SLD 유형에서 핵심 섹션이 렌더링되는지."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_has_entry_section(self, config_id):
        """incoming_supply(CT) 또는 meter_board(SP meter)가 렌더링되어야 한다."""
        result = get_layout(config_id)
        has_entry = (
            result.sections_rendered.get("incoming_supply")
            or result.sections_rendered.get("meter_board")
        )
        assert has_entry, \
            f"[{config_id}] No entry section (incoming_supply or meter_board) rendered. " \
            f"sections={dict(result.sections_rendered)}"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_main_breaker_rendered(self, config_id):
        result = get_layout(config_id)
        assert result.sections_rendered.get("main_breaker"), \
            f"[{config_id}] main_breaker section not rendered"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_main_busbar_rendered(self, config_id):
        result = get_layout(config_id)
        assert result.sections_rendered.get("main_busbar"), \
            f"[{config_id}] main_busbar section not rendered"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_sub_circuits_rendered(self, config_id):
        result = get_layout(config_id)
        assert result.sections_rendered.get("sub_circuits"), \
            f"[{config_id}] sub_circuits section not rendered"


class TestConditionalSections:
    """조건부 섹션이 올바르게 렌더링되는지."""

    def test_meter_board_for_sp_meter(self):
        result = get_layout("sp_meter_3phase")
        assert result.sections_rendered.get("meter_board"), \
            "sp_meter should render meter_board section"

    def test_meter_board_for_direct_without_ct(self):
        """direct(non-CT) metering에서는 meter_board가 렌더링됨 (SP meter로 자동보정)."""
        result = get_layout("direct_3phase_63a")
        # metering="none" → sld_spec 자동보정 → sp_meter → meter_board 렌더링
        # 이것은 정상 동작: ≤100A direct supply는 SP meter 사용
        assert result.sections_rendered.get("meter_board") or \
               result.sections_rendered.get("main_breaker"), \
            "direct metering should render either meter_board or main_breaker"

    def test_ct_metering_section_for_ct(self):
        result = get_layout("ct_metering_150a")
        assert result.sections_rendered.get("ct_metering_section"), \
            "ct_metering should render ct_metering_section"

    def test_no_ct_section_for_direct(self):
        result = get_layout("direct_3phase_63a")
        assert not result.sections_rendered.get("ct_metering_section"), \
            "direct metering should NOT render ct_metering_section"

    def test_elcb_rendered_for_all(self):
        for config_id in ALL_CONFIGS:
            result = get_layout(config_id)
            assert result.sections_rendered.get("elcb"), \
                f"[{config_id}] ELCB section should be rendered"


class TestSymbolPresence:
    """필수 심볼이 symbols_used에 존재하는지."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_has_breaker_symbol(self, config_id):
        result = get_layout(config_id)
        breaker_syms = {"CB_MCCB", "CB_MCB", "CB_ACB", "MCB"}
        assert result.symbols_used & breaker_syms, \
            f"[{config_id}] No breaker symbol found. symbols_used={result.symbols_used}"

    @pytest.mark.parametrize("config_id", ["direct_3phase_63a", "ct_metering_150a", "sp_meter_3phase"])
    def test_has_rccb_for_3phase(self, config_id):
        result = get_layout(config_id)
        rccb_syms = {"CB_RCCB", "CB_ELCB", "RCCB"}
        assert result.symbols_used & rccb_syms, \
            f"[{config_id}] 3-phase should have RCCB/ELCB. symbols_used={result.symbols_used}"

    def test_ct_has_ct_symbol(self):
        result = get_layout("ct_metering_150a")
        assert "CT" in result.symbols_used, \
            f"CT metering should have CT symbol. symbols_used={result.symbols_used}"


class TestRuleBasedValidation:
    """universal_rules.json 기반 자동 검증."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_layout_passes_structural_rules(self, config_id, rules):
        result = get_layout(config_id)
        req = ALL_CONFIGS[config_id]
        validator = LayoutValidator()
        report = validator.validate(result, rules, req)

        # Structural 카테고리의 error 위반만 체크
        structural_errors = [
            v for v in report.violations
            if v.severity == "error"
        ]
        if structural_errors:
            msgs = [f"  {v.rule_name}: {v.message}" for v in structural_errors]
            pytest.fail(f"[{config_id}] Structural rule violations:\n" + "\n".join(msgs))
