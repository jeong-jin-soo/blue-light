"""3-Tier Layout Optimizer.

Tier 1: 레퍼런스 룩업 (즉시, $0) — reference_matcher.py 기반
Tier 2: Gemini LLM 예측 (폴백) — few-shot 프롬프트 + 구조화 JSON
Tier 0: 기본값 (둘 다 실패 시)

Usage:
    optimizer = LayoutOptimizer()
    result = optimizer.optimize_config(requirements, page_config)
    # result.config: LayoutConfig with optimized parameters
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from app.sld.layout.models import LayoutConfig
    from app.sld.page_config import PageConfig

logger = logging.getLogger(__name__)

# Tier 1 최소 유사도 임계값
_TIER1_THRESHOLD = 0.4

# 최적화 대상 파라미터 및 유효 범위
_PARAM_RANGES: dict[str, tuple[float, float, float]] = {
    # name: (min, max, default)
    "horizontal_spacing": (10, 42, 26),
    "spine_component_gap": (2, 8, 5),
    "busbar_to_breaker_gap": (7, 20, 12),
    "isolator_to_db_gap": (8, 25, 14),
    "row_spacing": (40, 80, 65),
}

_TIER2_SYSTEM_PROMPT = """\
You are an expert Singapore LEW (Licensed Electrical Worker) SLD layout advisor.
Given electrical specifications, predict optimal layout spacing parameters (in mm)
for an A3-landscape Single Line Diagram.

Return ONLY a JSON object with these numeric fields:
- horizontal_spacing: subcircuit column spacing (10-42mm, fewer circuits → wider)
- spine_component_gap: vertical gap between spine components (2-8mm)
- busbar_to_breaker_gap: busbar to subcircuit breaker gap (7-20mm)
- isolator_to_db_gap: isolator to DB/breaker gap (8-25mm)
- row_spacing: vertical spacing between subcircuit rows (40-80mm, only if multi-row)

Omit fields you cannot confidently predict. All values in mm.
"""


@dataclass
class OptimizationResult:
    """레이아웃 최적화 결과."""
    tier_used: int                    # 1=레퍼런스, 2=Gemini, 0=기본값
    config: LayoutConfig
    reference_file: str | None = None
    similarity_score: float = 0.0
    parameters_applied: dict[str, float] = field(default_factory=dict)


class LayoutOptimizer:
    """3-Tier layout parameter optimizer."""

    def __init__(self, *, enabled: bool = True, api_key: str | None = None):
        self._enabled = enabled
        self._api_key = api_key

    def optimize_config(
        self,
        requirements: dict,
        page_config: "PageConfig | None" = None,
    ) -> OptimizationResult:
        """requirements 기반 최적 LayoutConfig 생성.

        1. Tier 1: 레퍼런스 룩업 (score >= threshold)
        2. Tier 2: Gemini LLM 예측 (Tier 1 실패 시)
        3. Tier 0: 기본값 반환
        """
        from app.sld.layout.models import LayoutConfig

        if not self._enabled:
            return OptimizationResult(
                tier_used=0,
                config=LayoutConfig.from_reference(requirements, page_config),
            )

        # Tier 1: 레퍼런스 매칭
        config, tier1_result = self._try_tier1(requirements, page_config)
        if tier1_result is not None:
            return tier1_result

        # Tier 2: Gemini LLM 폴백
        tier2_result = self._try_tier2(requirements, page_config, config)
        if tier2_result is not None:
            return tier2_result

        # Tier 0: 기본값
        logger.info("LayoutOptimizer: using defaults (tier=0)")
        return OptimizationResult(tier_used=0, config=config)

    def _try_tier1(
        self,
        requirements: dict,
        page_config: "PageConfig | None",
    ) -> tuple["LayoutConfig", OptimizationResult | None]:
        """Tier 1: 레퍼런스 룩업.

        Returns: (base_config, result_or_None)
        """
        from app.sld.layout.models import LayoutConfig
        from app.sld.reference_matcher import get_reference_spacing

        base = LayoutConfig.from_page_config(page_config)
        matched = get_reference_spacing(requirements)

        if matched and matched.match_score >= _TIER1_THRESHOLD:
            overrides = matched.to_overrides()
            for key, value in overrides.items():
                if hasattr(base, key):
                    setattr(base, key, value)
            logger.info(
                "LayoutOptimizer: tier=1, ref=%s, score=%.2f, params=%s",
                matched.reference_file, matched.match_score, overrides,
            )
            return base, OptimizationResult(
                tier_used=1,
                config=base,
                reference_file=matched.reference_file,
                similarity_score=matched.match_score,
                parameters_applied=overrides,
            )

        # Tier 1 매칭 실패/부족 — base config 반환 (from_reference는 이미 적용)
        if matched:
            # 낮은 점수라도 기존 from_reference 적용은 유지
            overrides = matched.to_overrides()
            for key, value in overrides.items():
                if hasattr(base, key):
                    setattr(base, key, value)

        return base, None

    def _try_tier2(
        self,
        requirements: dict,
        page_config: "PageConfig | None",
        config: "LayoutConfig",
    ) -> OptimizationResult | None:
        """Tier 2: Gemini LLM 예측."""
        try:
            import asyncio
            params = asyncio.run(self._predict_via_llm(requirements))
            if not params:
                return None

            # Clamp + 적용
            applied: dict[str, float] = {}
            for key, value in params.items():
                if key not in _PARAM_RANGES:
                    continue
                lo, hi, _ = _PARAM_RANGES[key]
                clamped = max(lo, min(float(value), hi))
                if hasattr(config, key):
                    setattr(config, key, clamped)
                    applied[key] = clamped

            if applied:
                logger.info("LayoutOptimizer: tier=2 (Gemini), params=%s", applied)
                return OptimizationResult(
                    tier_used=2,
                    config=config,
                    parameters_applied=applied,
                )
        except Exception as exc:
            logger.warning("LayoutOptimizer: Tier 2 failed: %s", exc)

        return None

    async def _predict_via_llm(self, requirements: dict) -> dict[str, float]:
        """Gemini API 호출로 레이아웃 파라미터 예측."""
        from app.config import settings

        resolved_key = self._api_key or settings.gemini_api_key
        if not resolved_key:
            logger.debug("LayoutOptimizer: no API key, skipping Tier 2")
            return {}

        # few-shot 예시 구성
        few_shots = self._build_few_shots(requirements)
        user_prompt = self._build_user_prompt(requirements, few_shots)

        try:
            from google import genai
            from google.genai import types

            client = genai.Client(api_key=resolved_key)
            response = await client.aio.models.generate_content(
                model=settings.gemini_fallback_model,  # Flash-Lite (빠르고 저렴)
                contents=user_prompt,
                config=types.GenerateContentConfig(
                    system_instruction=_TIER2_SYSTEM_PROMPT,
                    response_mime_type="application/json",
                    temperature=0.0,
                ),
            )
            raw = response.text
            result = json.loads(raw)
            # 숫자 값만 필터
            return {k: v for k, v in result.items() if isinstance(v, (int, float))}
        except json.JSONDecodeError as exc:
            logger.warning("LayoutOptimizer: Gemini returned invalid JSON: %s", exc)
            return {}
        except Exception as exc:
            logger.warning("LayoutOptimizer: Gemini call failed: %s", exc)
            return {}

    def _build_few_shots(self, requirements: dict, max_examples: int = 5) -> list[dict]:
        """프로파일에서 가장 유사한 N개를 few-shot 예시로 선택."""
        from app.sld.reference_matcher import _load_profiles, _score_match

        data = _load_profiles()
        profiles = data.get("profiles", {})
        if not profiles:
            return []

        supply_type = requirements.get("supply_type", "three_phase")
        metering = requirements.get("metering", "")
        circuits = len(requirements.get("sub_circuits", []))

        if metering in ("ct_metering", "ct_meter"):
            sld_type = "ct_metering_3phase"
        elif supply_type == "single_phase":
            sld_type = "direct_metering_1phase"
        else:
            sld_type = "direct_metering_3phase"

        scored: list[tuple[float, str, dict]] = []
        for filename, prof in profiles.items():
            if prof.get("circuits", 0) < 3:
                continue
            score = _score_match(circuits, supply_type, sld_type, prof)
            scored.append((score, filename, prof))

        scored.sort(key=lambda x: -x[0])
        examples = []
        for score, filename, prof in scored[:max_examples]:
            example: dict[str, Any] = {
                "supply_type": "three_phase" if "3phase" in prof.get("sld_type", "") else "single_phase",
                "circuits": prof["circuits"],
                "sld_type": prof.get("sld_type", ""),
                "horizontal_spacing": prof.get("subcircuit_spacing_mm", 0),
            }
            # spine gaps
            for gap_key in ("breaker_to_rccb", "rccb_to_busbar", "isolator_to_breaker"):
                val = prof.get("spine_gaps_mm", {}).get(gap_key)
                if val:
                    example[f"spine_{gap_key}"] = val
            # extended
            for ext_key, ext_val in prof.get("extended", {}).items():
                if not ext_key.endswith("_raw"):  # _raw 필드 제외
                    example[ext_key] = ext_val
            examples.append(example)

        return examples

    def _build_user_prompt(self, requirements: dict, few_shots: list[dict]) -> str:
        """Tier 2 사용자 프롬프트 구성."""
        circuits = len(requirements.get("sub_circuits", []))
        supply = requirements.get("supply_type", "three_phase")
        metering = requirements.get("metering", "direct")
        main_breaker = requirements.get("main_breaker", {})
        breaker_rating = main_breaker.get("rating", "")
        breaker_type = main_breaker.get("type", "MCCB")

        prompt_parts = [
            "Here are reference spacing examples from real LEW SLD drawings:\n",
        ]
        for i, ex in enumerate(few_shots, 1):
            prompt_parts.append(f"Example {i}: {json.dumps(ex)}")

        prompt_parts.append(f"\nTarget specification:")
        prompt_parts.append(f"- supply_type: {supply}")
        prompt_parts.append(f"- circuit_count: {circuits}")
        prompt_parts.append(f"- metering: {metering}")
        prompt_parts.append(f"- main_breaker: {breaker_type} {breaker_rating}")
        prompt_parts.append(f"\nPredict optimal layout spacing parameters for this SLD.")

        return "\n".join(prompt_parts)
