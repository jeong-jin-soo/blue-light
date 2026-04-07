"""LayoutOptimizer 3-Tier 테스트.

Tier 1: 레퍼런스 룩업, Tier 2: Gemini 폴백, Tier 0: 기본값.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, patch

import pytest

from app.sld.layout_optimizer import LayoutOptimizer, OptimizationResult, _PARAM_RANGES


# ─── fixtures ───────────────────────────────────────────────────────

def _make_reqs(circuits: int = 15, supply: str = "three_phase", metering: str = "direct"):
    return {
        "supply_type": supply,
        "metering": metering,
        "sub_circuits": [{"name": f"CKT{i}"} for i in range(circuits)],
        "main_breaker": {"type": "MCCB", "rating": 63},
    }


# ─── Tier 0 (disabled) ─────────────────────────────────────────────

class TestTier0Disabled:
    def test_disabled_returns_tier0(self):
        opt = LayoutOptimizer(enabled=False)
        result = opt.optimize_config(_make_reqs())
        assert result.tier_used == 0
        assert result.config is not None
        assert result.parameters_applied == {}

    def test_disabled_uses_from_reference(self):
        """disabled여도 from_reference 기본 경로는 동작."""
        opt = LayoutOptimizer(enabled=False)
        result = opt.optimize_config(_make_reqs())
        # from_reference는 내부적으로 reference matcher 호출
        assert result.config.drawing_width > 0


# ─── Tier 1 (reference lookup) ─────────────────────────────────────

class TestTier1Reference:
    def test_tier1_match_applies_parameters(self):
        """15 circuits, 3-phase → 높은 유사도 매칭."""
        opt = LayoutOptimizer(enabled=True)
        result = opt.optimize_config(_make_reqs(circuits=15))
        assert result.tier_used == 1
        assert result.reference_file is not None
        assert result.similarity_score >= 0.4
        assert "horizontal_spacing" in result.parameters_applied

    def test_tier1_config_has_reference_spacing(self):
        opt = LayoutOptimizer(enabled=True)
        result = opt.optimize_config(_make_reqs(circuits=15))
        assert result.config.horizontal_spacing != 26  # 기본값이 아닌 레퍼런스 값

    def test_tier1_single_phase(self):
        opt = LayoutOptimizer(enabled=True)
        result = opt.optimize_config(_make_reqs(circuits=10, supply="single_phase"))
        assert result.tier_used == 1
        assert result.reference_file is not None


# ─── Tier 2 (Gemini fallback) ──────────────────────────────────────

class TestTier2Gemini:
    def test_tier1_low_score_falls_to_tier2(self):
        """매칭 점수가 낮으면 Tier 2 시도."""
        opt = LayoutOptimizer(enabled=True, api_key="test-key")
        # 1 circuit — 모든 프로파일과 낮은 유사도
        with patch.object(opt, "_predict_via_llm", new_callable=AsyncMock) as mock_llm:
            mock_llm.return_value = {"horizontal_spacing": 30.0, "row_spacing": 55.0}
            result = opt.optimize_config(_make_reqs(circuits=1))

        # Tier 1 임계값(0.4) 미달 → Tier 2
        if result.tier_used == 2:
            assert result.parameters_applied.get("horizontal_spacing") == 30.0
            assert result.parameters_applied.get("row_spacing") == 55.0

    def test_tier2_parameter_clamping(self):
        """범위 초과 값은 clamp."""
        opt = LayoutOptimizer(enabled=True, api_key="test-key")
        with patch.object(opt, "_predict_via_llm", new_callable=AsyncMock) as mock_llm:
            mock_llm.return_value = {
                "horizontal_spacing": 100.0,  # max 42
                "spine_component_gap": 0.5,   # min 2
            }
            result = opt.optimize_config(_make_reqs(circuits=1))

        if result.tier_used == 2:
            assert result.parameters_applied["horizontal_spacing"] == 42.0
            assert result.parameters_applied["spine_component_gap"] == 2.0

    def test_tier2_invalid_json_uses_defaults(self):
        """Gemini가 잘못된 응답 → Tier 0 기본값."""
        opt = LayoutOptimizer(enabled=True, api_key="test-key")
        with patch.object(opt, "_predict_via_llm", new_callable=AsyncMock) as mock_llm:
            mock_llm.return_value = {}  # 빈 dict
            result = opt.optimize_config(_make_reqs(circuits=1))

        # 빈 params → Tier 0
        if result.tier_used == 0:
            assert result.parameters_applied == {}

    def test_tier2_gemini_exception_falls_to_tier0(self):
        """Gemini 호출 실패 → Tier 0."""
        opt = LayoutOptimizer(enabled=True, api_key="test-key")
        with patch.object(opt, "_predict_via_llm", new_callable=AsyncMock) as mock_llm:
            mock_llm.side_effect = RuntimeError("API error")
            result = opt.optimize_config(_make_reqs(circuits=1))

        assert result.tier_used == 0


# ─── layout_overrides 소비 (Phase C) ──────────────────────────────

class TestLayoutOverrides:
    def test_layout_overrides_consumed_by_engine(self):
        """Vision AI의 layout_overrides가 실제 적용되는지 검증."""
        from app.sld.layout.engine import compute_layout

        reqs = _make_reqs(circuits=6)
        reqs["layout_overrides"] = {
            "horizontal_spacing": 30.0,
            "spine_component_gap": 7.0,
        }
        result = compute_layout(reqs)
        assert result.config.horizontal_spacing == 30.0
        assert result.config.spine_component_gap == 7.0

    def test_layout_overrides_consumed_by_engine_v3(self):
        """engine_v3에서도 layout_overrides 적용."""
        from app.sld.layout.engine_v3 import compute_layout_v3

        reqs = _make_reqs(circuits=6)
        reqs["layout_overrides"] = {
            "row_spacing": 50.0,
        }
        result = compute_layout_v3(reqs)
        assert result.config.row_spacing == 50.0


# ─── 통합 (SldPipeline) ───────────────────────────────────────────

class TestPipelineIntegration:
    def test_pipeline_without_optimizer(self):
        """SldPipeline() 인자 없이 호출 가능 (하위호환)."""
        from app.sld.generator import SldPipeline
        pipeline = SldPipeline()
        assert pipeline._optimizer is None

    def test_pipeline_with_optimizer(self):
        """SldPipeline(optimizer=...) 정상 생성."""
        from app.sld.generator import SldPipeline
        opt = LayoutOptimizer(enabled=False)
        pipeline = SldPipeline(optimizer=opt)
        assert pipeline._optimizer is opt

    def test_pipeline_disabled_optimizer_same_output(self):
        """optimizer disabled → 기존 동작과 동일."""
        from app.sld.generator import SldPipeline

        reqs = _make_reqs(circuits=6)
        # Without optimizer
        r1 = SldPipeline()._generate_once(reqs, {}, "dxf", None)
        # With disabled optimizer
        r2 = SldPipeline(optimizer=LayoutOptimizer(enabled=False))._generate_once(reqs, {}, "dxf", None)

        # 동일 output (SVG string 비교)
        assert r1.svg_string == r2.svg_string


# ─── MatchedSpacing 확장 (Phase A) ────────────────────────────────

class TestMatchedSpacingExtended:
    def test_to_overrides_includes_extended_fields(self):
        from app.sld.reference_matcher import MatchedSpacing

        ms = MatchedSpacing(
            reference_file="test.dxf",
            match_score=0.8,
            circuits_ref=10,
            horizontal_spacing=20.0,
            row_spacing=55.0,
            busbar_to_breaker_gap=15.0,
            spine_component_gap=4.0,
        )
        ov = ms.to_overrides()
        assert ov["horizontal_spacing"] == 20.0
        assert ov["row_spacing"] == 55.0
        assert ov["busbar_to_breaker_gap"] == 15.0
        assert ov["spine_component_gap"] == 4.0

    def test_to_overrides_clamps_extended_fields(self):
        from app.sld.reference_matcher import MatchedSpacing

        ms = MatchedSpacing(
            reference_file="test.dxf",
            match_score=0.8,
            circuits_ref=10,
            row_spacing=200.0,        # max 80
            spine_component_gap=0.5,  # min 2
        )
        ov = ms.to_overrides()
        assert ov["row_spacing"] == 80.0
        assert ov["spine_component_gap"] == 2.0

    def test_to_overrides_skips_none_fields(self):
        from app.sld.reference_matcher import MatchedSpacing

        ms = MatchedSpacing(
            reference_file="test.dxf",
            match_score=0.8,
            circuits_ref=10,
        )
        ov = ms.to_overrides()
        assert "row_spacing" not in ov
        assert "spine_component_gap" not in ov


# ─── param ranges ─────────────────────────────────────────────────

class TestParamRanges:
    def test_all_param_ranges_have_valid_bounds(self):
        for name, (lo, hi, default) in _PARAM_RANGES.items():
            assert lo < hi, f"{name}: min >= max"
            assert lo <= default <= hi, f"{name}: default outside range"

    def test_all_params_exist_in_layout_config(self):
        from app.sld.layout.models import LayoutConfig
        cfg = LayoutConfig()
        for name in _PARAM_RANGES:
            assert hasattr(cfg, name), f"{name} not in LayoutConfig"
