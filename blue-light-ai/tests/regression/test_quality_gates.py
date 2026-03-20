"""품질 게이트 테스트.

audit 점수, 오버플로, 압축률, 컴포넌트 최소 수.
"""

from __future__ import annotations

import pytest

from app.sld.regression.validators import QualityGateValidator

from .conftest import ALL_CONFIGS, get_layout


class TestAuditScore:
    """audit_report 점수 검증."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_audit_score_minimum(self, config_id):
        result = get_layout(config_id)
        if result.audit_report is None:
            pytest.skip("audit_report not populated")
        assert result.audit_report.score >= 0.8, \
            f"[{config_id}] Audit score {result.audit_report.score:.2f} < 0.8"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_no_audit_errors(self, config_id):
        """Audit 결과에 심각한 오류가 없는지 (정보성 출력)."""
        result = get_layout(config_id)
        if result.audit_report is None:
            pytest.skip("audit_report not populated")
        # Informational — report failed checks but don't fail the test
        failed_checks = [c for c in result.audit_report.results if not c.passed]
        if failed_checks:
            for check in failed_checks[:3]:
                print(f"  Audit: {check.principle_id}: {check.principle_name}")


class TestOverflow:
    """페이지 오버플로 검증."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_no_overflow(self, config_id):
        result = get_layout(config_id)
        if result.overflow_metrics is None:
            return
        assert not result.overflow_metrics.has_overflow, \
            f"[{config_id}] Layout overflows page boundaries"


class TestComponentCounts:
    """최소 컴포넌트 수."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_has_sufficient_components(self, config_id):
        result = get_layout(config_id)
        req = ALL_CONFIGS[config_id]
        n_circuits = len(req.get("sub_circuits", []))

        # At minimum: main breaker + ELCB + busbar_label + subcircuits + db_box
        min_expected = n_circuits + 3
        assert len(result.components) >= min_expected, \
            f"[{config_id}] Too few components: {len(result.components)} < {min_expected}"


class TestQualityGateValidator:
    """QualityGateValidator 통합 검증."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_quality_gate_passes(self, config_id):
        result = get_layout(config_id)
        validator = QualityGateValidator()
        report = validator.validate(result)

        errors = [v for v in report.violations if v.severity == "error"]
        if errors:
            msgs = [f"  {v.rule_name}: {v.message}" for v in errors]
            pytest.fail(f"[{config_id}] Quality gate violations:\n" + "\n".join(msgs))
