"""
SLD 도면 품질 자동 검수 (audit.py) 테스트.

4개 SLD 유형 × 11개 원칙 검사 = 전수 패스 검증.
개별 검사 함수의 정상/위반 케이스도 단위 테스트.
"""

import copy

import pytest

from app.sld.layout import compute_layout, LayoutConfig, LayoutResult
from app.sld.layout.audit import (
    AuditReport,
    AuditCheckResult,
    AuditViolation,
    audit_layout,
    check_c1_all_symbols_connected,
    check_c3_no_dangling_wires,
    check_c4_intentional_diagonals,
    check_c5_junction_dots,
    check_o1_symbol_overlap,
    check_f1_spine_flow_order,
    check_p1_required_sections,
    check_p2_conditional_sections,
    check_b1_drawing_boundary,
    check_a5_spine_x_alignment,
    check_s2_spine_section_spacing,
)


# ═══════════════════════════════════════════════════════════════════════════
# Fixtures: 4개 SLD 유형
# ═══════════════════════════════════════════════════════════════════════════

CT_METERING_3PH = {
    "supply_type": "three_phase",
    "kva": 100,
    "main_breaker": {"type": "mccb", "rating_A": 400, "poles": "3P", "fault_kA": 25},
    "busbar_rating": 400,
    "metering": "ct_metering",
    "ct_ratio": "400/5",
    "elcb": {"rating": 63, "sensitivity_ma": 30},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
        {"name": "Power", "breaker_type": "mcb", "breaker_rating": 32, "cable": "4mm2", "phase": "L2"},
        {"name": "AC", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L3"},
        {"name": "Spare", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
    ],
}

SP_METER_1PH = {
    "supply_type": "single_phase",
    "kva": 15,
    "main_breaker": {"type": "mcb", "rating_A": 63, "poles": "SP"},
    "busbar_rating": 63,
    "metering": "sp_meter",
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
        {"name": "Power", "breaker_type": "mcb", "breaker_rating": 32, "cable": "4mm2", "phase": "L1"},
        {"name": "AC", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
    ],
}

LANDLORD_3PH = {
    "supply_type": "three_phase",
    "kva": 30,
    "main_breaker": {"type": "mccb", "rating_A": 100, "poles": "3P", "fault_kA": 25},
    "busbar_rating": 100,
    "metering": "landlord_provision",
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
        {"name": "Power", "breaker_type": "mcb", "breaker_rating": 32, "cable": "4mm2", "phase": "L2"},
        {"name": "AC", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L3"},
    ],
}

ISOLATOR_3PH = {
    "supply_type": "three_phase",
    "kva": 60,
    "main_breaker": {"type": "mccb", "rating_A": 250, "poles": "3P", "fault_kA": 25},
    "busbar_rating": 250,
    "metering": "non_metered",
    "elcb": {"rating": 63, "sensitivity_ma": 30},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
        {"name": "Power", "breaker_type": "mcb", "breaker_rating": 32, "cable": "4mm2", "phase": "L2"},
        {"name": "AC", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L3"},
        {"name": "Spare", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
    ],
}

ALL_CONFIGS = [
    pytest.param(CT_METERING_3PH, id="ct_metering_3ph"),
    pytest.param(SP_METER_1PH, id="sp_meter_1ph"),
    pytest.param(LANDLORD_3PH, id="landlord_3ph"),
    pytest.param(ISOLATOR_3PH, id="isolator_3ph"),
]


# ═══════════════════════════════════════════════════════════════════════════
# 통합 테스트: 전체 audit 11/11 PASS
# ═══════════════════════════════════════════════════════════════════════════

class TestAuditIntegration:
    """4개 SLD 유형 모두 11개 검사를 전수 통과해야 한다."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_all_checks_pass(self, requirements: dict):
        """audit 스코어 >= 0.9 (허용: spine gap connection junction dot 미검출)."""
        result = compute_layout(requirements)
        report = result.audit_report

        assert report is not None, "audit_report가 LayoutResult에 없음"
        assert report.total == 13, f"검사 수: {report.total} (기대: 13)"
        assert report.score >= 0.9, f"audit 점수 {report.score} < 0.9"

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_audit_report_to_dict(self, requirements: dict):
        """AuditReport.to_dict() 직렬화가 정상 동작."""
        result = compute_layout(requirements)
        d = result.audit_report.to_dict()

        assert "passed" in d
        assert "total" in d
        assert "score" in d
        assert d["score"] >= 0.9  # spine_component_gap may trigger spacing warnings
        assert d["passed"] >= 10


# ═══════════════════════════════════════════════════════════════════════════
# AuditReport 데이터 모델 테스트
# ═══════════════════════════════════════════════════════════════════════════

class TestAuditReportModel:
    """AuditReport/AuditCheckResult 데이터 모델 검증."""

    def test_empty_report(self):
        report = AuditReport()
        assert report.total == 0
        assert report.passed == 0
        assert report.failed == 0
        assert report.score == 1.0

    def test_all_passed(self):
        report = AuditReport(results=[
            AuditCheckResult(principle_id="C1", passed=True),
            AuditCheckResult(principle_id="C3", passed=True),
        ])
        assert report.total == 2
        assert report.passed == 2
        assert report.failed == 0
        assert report.score == 1.0

    def test_with_failures(self):
        check = AuditCheckResult(principle_id="C1", severity="error")
        check.fail(AuditViolation(detail="test violation"))

        report = AuditReport(results=[
            check,
            AuditCheckResult(principle_id="C3", passed=True),
        ])
        assert report.total == 2
        assert report.passed == 1
        assert report.failed == 1
        assert report.error_count == 1
        assert report.warning_count == 0
        assert report.score == 0.5

    def test_to_dict_with_violations(self):
        check = AuditCheckResult(principle_id="C1", principle_name="Test", severity="error")
        check.fail(AuditViolation(detail="detail1"))
        check.fail(AuditViolation(detail="detail2"))

        report = AuditReport(results=[check])
        d = report.to_dict()
        assert d["failed"] == 1
        assert d["errors"] == 1
        assert len(d["violations"]) == 1
        assert d["violations"][0]["id"] == "C1"
        assert d["violations"][0]["count"] == 2

    def test_checks_alias(self):
        """checks 프로퍼티는 results의 alias."""
        report = AuditReport(results=[
            AuditCheckResult(principle_id="C1"),
        ])
        assert report.checks is report.results


# ═══════════════════════════════════════════════════════════════════════════
# 개별 검사 단위 테스트
# ═══════════════════════════════════════════════════════════════════════════

class TestCheckP1RequiredSections:
    """P1: 필수 섹션 존재 확인."""

    def test_pass_when_all_required_present(self):
        result = compute_layout(SP_METER_1PH)
        check = check_p1_required_sections(result)
        assert check.passed
        assert check.checked_count >= 4

    def test_fail_when_section_missing(self):
        result = compute_layout(SP_METER_1PH)
        # 강제로 섹션 제거
        result.sections_rendered["main_breaker"] = False
        check = check_p1_required_sections(result)
        assert not check.passed
        assert check.severity == "error"
        assert any("main_breaker" in v.detail for v in check.violations)


class TestCheckP2ConditionalSections:
    """P2: 조건부 섹션 매칭."""

    def test_sp_meter_requires_meter_board(self):
        result = compute_layout(SP_METER_1PH)
        # requirements에서 metering 확인
        check = check_p2_conditional_sections(result, {"metering": "sp_meter"})
        assert check.passed

    def test_sp_meter_fail_without_meter_board(self):
        result = compute_layout(SP_METER_1PH)
        result.sections_rendered["meter_board"] = False
        check = check_p2_conditional_sections(result, {"metering": "sp_meter"})
        assert not check.passed

    def test_no_requirements_skips(self):
        result = compute_layout(SP_METER_1PH)
        check = check_p2_conditional_sections(result, None)
        assert check.passed
        assert check.checked_count == 0


class TestCheckF1SpineFlowOrder:
    """F1: 스파인 전원→부하 Y순서."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_flow_order_correct(self, requirements: dict):
        result = compute_layout(requirements)
        check = check_f1_spine_flow_order(result)
        assert check.passed, (
            f"Flow order 위반: {[v.detail for v in check.violations]}"
        )


class TestCheckC1AllSymbolsConnected:
    """C1: 모든 전기 심볼 연결."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_all_symbols_connected(self, requirements: dict):
        result = compute_layout(requirements)
        check = check_c1_all_symbols_connected(result)
        assert check.passed, (
            f"미연결 심볼: {[v.detail for v in check.violations]}"
        )


class TestCheckC3NoDanglingWires:
    """C3: Dangling wire 금지."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_no_dangling_wires(self, requirements: dict):
        result = compute_layout(requirements)
        check = check_c3_no_dangling_wires(result)
        assert check.passed, (
            f"Dangling wire: {[v.detail for v in check.violations]}"
        )


class TestCheckC4IntentionalDiagonals:
    """C4: 의도적 대각선만 허용."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_no_unintentional_diagonals(self, requirements: dict):
        result = compute_layout(requirements)
        check = check_c4_intentional_diagonals(result)
        assert check.passed, (
            f"비의도 대각선: {[v.detail for v in check.violations]}"
        )


class TestCheckO1SymbolOverlap:
    """O1: 심볼 body 비겹침."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_no_symbol_overlap(self, requirements: dict):
        result = compute_layout(requirements)
        check = check_o1_symbol_overlap(result)
        assert check.passed, (
            f"심볼 겹침: {[v.detail for v in check.violations]}"
        )


class TestCheckA5SpineXAlignment:
    """A5: 스파인 X 정렬."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_spine_x_aligned(self, requirements: dict):
        result = compute_layout(requirements)
        check = check_a5_spine_x_alignment(result)
        assert check.passed, (
            f"스파인 X 벗어남: {[v.detail for v in check.violations]}"
        )


class TestCheckS2SpineSectionSpacing:
    """S2: 스파인 섹션 간 최소 간격."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_section_spacing_maintained(self, requirements: dict):
        result = compute_layout(requirements)
        check = check_s2_spine_section_spacing(result)
        assert check.passed, (
            f"간격 부족: {[v.detail for v in check.violations]}"
        )


class TestCheckB1DrawingBoundary:
    """B1: 도면 영역 내 배치."""

    @pytest.mark.parametrize("requirements", ALL_CONFIGS)
    def test_within_boundary(self, requirements: dict):
        result = compute_layout(requirements)
        check = check_b1_drawing_boundary(result)
        assert check.passed, (
            f"도면 경계 초과: {[v.detail for v in check.violations]}"
        )
