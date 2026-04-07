"""레퍼런스 간격 매칭.

requirements → 가장 유사한 DXF의 간격 프로파일 선택 → LayoutConfig 오버라이드.

Usage:
    from app.sld.reference_matcher import get_reference_spacing

    spacing = get_reference_spacing(requirements)
    # spacing = {"horizontal_spacing": 15.3, ...} or {} if no match
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_BASE_DIR = Path(__file__).resolve().parent.parent.parent  # blue-light-ai/
_PROFILES_PATH = _BASE_DIR / "data" / "regression" / "reference_spacing_profiles.json"

# Matching weights (consistent with template_matcher.py)
_WEIGHTS = {
    "circuit_count": 0.40,   # Most important — directly affects spacing
    "supply_type": 0.30,     # Hard filter effectively
    "sld_type": 0.30,        # Metering type matters for spine structure
}


@dataclass
class MatchedSpacing:
    """매칭된 레퍼런스의 간격 오버라이드."""
    reference_file: str
    match_score: float
    circuits_ref: int

    # 적용할 오버라이드 — 기존 4개
    horizontal_spacing: float | None = None   # 서브회로 간격 (mm)
    breaker_to_rccb_gap: float | None = None  # 스파인: breaker→RCCB 간격 (mm)
    rccb_to_busbar_gap: float | None = None   # 스파인: RCCB→busbar 간격 (mm)
    isolator_to_breaker_gap: float | None = None  # 스파인: isolator→breaker 간격 (mm, CT only)

    # Phase A 확장 — 5개 추가
    spine_component_gap: float | None = None      # 스파인 컴포넌트 간 간격 (mm)
    busbar_to_breaker_gap: float | None = None     # 부스바→서브회로 브레이커 간격 (mm)
    isolator_to_db_gap: float | None = None        # 아이솔레이터→DB 간격 (mm)
    row_spacing: float | None = None               # 다열 회로 행간 간격 (mm)
    label_char_height: float | None = None         # 라벨 텍스트 높이 (mm)

    # Spine gap 유효 범위 (이상치 clamp)
    _MIN_SPINE_GAP: float = 5.0
    _MAX_SPINE_GAP: float = 80.0

    # Phase A 확장 필드별 clamp 범위
    _CLAMP_RANGES: dict = field(default_factory=lambda: {
        "spine_component_gap": (2.0, 8.0),
        "busbar_to_breaker_gap": (7.0, 20.0),
        "isolator_to_db_gap": (8.0, 25.0),
        "row_spacing": (40.0, 80.0),
        "label_char_height": (2.0, 3.0),
    })

    def __post_init__(self):
        # dataclass field 기본값으로 _CLAMP_RANGES가 이미 설정됨
        pass

    def _clamp_gap(self, value: float | None) -> float | None:
        if value is None or value <= 0:
            return None
        return max(self._MIN_SPINE_GAP, min(value, self._MAX_SPINE_GAP))

    def _clamp_range(self, name: str, value: float | None) -> float | None:
        """Phase A 확장 필드의 범위 clamp."""
        if value is None or value <= 0:
            return None
        lo, hi = self._CLAMP_RANGES.get(name, (0, 1e6))
        return max(lo, min(value, hi))

    def to_overrides(self) -> dict[str, float]:
        """LayoutConfig에 적용할 오버라이드 dict 반환."""
        overrides = {}
        if self.horizontal_spacing is not None and self.horizontal_spacing > 0:
            overrides["horizontal_spacing"] = self.horizontal_spacing

        # 기존 spine gap 오버라이드
        gap = self._clamp_gap(self.breaker_to_rccb_gap)
        if gap is not None:
            overrides["ref_breaker_to_rccb_gap"] = gap
        gap = self._clamp_gap(self.rccb_to_busbar_gap)
        if gap is not None:
            overrides["ref_rccb_to_busbar_gap"] = gap
        gap = self._clamp_gap(self.isolator_to_breaker_gap)
        if gap is not None:
            overrides["ref_isolator_to_breaker_gap"] = gap

        # Phase A 확장 오버라이드 — LayoutConfig 필드명과 동일
        for attr_name in ("spine_component_gap", "busbar_to_breaker_gap",
                          "isolator_to_db_gap", "row_spacing", "label_char_height"):
            val = self._clamp_range(attr_name, getattr(self, attr_name))
            if val is not None:
                overrides[attr_name] = val

        return overrides


def _load_profiles() -> dict:
    """프로파일 JSON 로드."""
    if not _PROFILES_PATH.exists():
        return {}
    with open(_PROFILES_PATH) as f:
        return json.load(f)


def _score_match(
    req_circuits: int,
    req_supply: str,
    req_sld_type: str,
    prof: dict,
) -> float:
    """프로파일과 requirements의 유사도 점수 (0~1)."""
    score = 0.0

    # Supply type: hard filter via sld_type
    prof_type = prof.get("sld_type", "")
    if req_sld_type and prof_type:
        if req_sld_type == prof_type:
            score += _WEIGHTS["sld_type"]
        elif req_supply == "three_phase" and "3phase" in prof_type:
            score += _WEIGHTS["sld_type"] * 0.5
        elif req_supply == "single_phase" and "1phase" in prof_type:
            score += _WEIGHTS["sld_type"] * 0.5
    elif req_supply:
        if req_supply == "three_phase" and "3phase" in prof_type:
            score += _WEIGHTS["sld_type"] * 0.5

    # Circuit count: closer = better
    prof_circuits = prof.get("circuits", 0)
    if prof_circuits > 0 and req_circuits > 0:
        ratio = min(req_circuits, prof_circuits) / max(req_circuits, prof_circuits)
        score += _WEIGHTS["circuit_count"] * ratio

    return score


def get_reference_spacing(requirements: dict) -> MatchedSpacing | None:
    """requirements에 가장 유사한 레퍼런스의 간격 프로파일 반환.

    Returns None if no suitable match found.
    """
    data = _load_profiles()
    profiles = data.get("profiles", {})
    if not profiles:
        logger.debug("No reference spacing profiles available")
        return None

    supply_type = requirements.get("supply_type", "three_phase")
    metering = requirements.get("metering", "")
    circuits = len(requirements.get("sub_circuits", []))

    # SLD type inference
    if metering in ("ct_metering", "ct_meter"):
        sld_type = "ct_metering_3phase"
    elif supply_type == "single_phase":
        sld_type = "direct_metering_1phase"
    else:
        sld_type = "direct_metering_3phase"

    # Score all profiles
    scored: list[tuple[float, str, dict]] = []
    for filename, prof in profiles.items():
        if prof.get("circuits", 0) < 3:
            continue  # Skip profiles without meaningful subcircuit data
        if prof.get("subcircuit_spacing_mm", 0) <= 0:
            continue

        score = _score_match(circuits, supply_type, sld_type, prof)
        scored.append((score, filename, prof))

    if not scored:
        return None

    scored.sort(key=lambda x: -x[0])
    best_score, best_file, best_prof = scored[0]

    if best_score < 0.2:
        logger.debug("Best match score %.2f too low for %s", best_score, best_file)
        return None

    spacing_mm = best_prof.get("subcircuit_spacing_mm", 0)
    spine = best_prof.get("spine_gaps_mm", {})

    logger.info(
        "Reference spacing: %s (score=%.2f, circuits=%d→%d, spacing=%.1fmm, spine_gaps=%s)",
        best_file, best_score, best_prof.get("circuits", 0), circuits, spacing_mm, spine,
    )

    # Phase A 확장 필드
    extended = best_prof.get("extended", {})

    return MatchedSpacing(
        reference_file=best_file,
        match_score=best_score,
        circuits_ref=best_prof.get("circuits", 0),
        horizontal_spacing=spacing_mm if spacing_mm > 0 else None,
        breaker_to_rccb_gap=spine.get("breaker_to_rccb"),
        rccb_to_busbar_gap=spine.get("rccb_to_busbar"),
        isolator_to_breaker_gap=spine.get("isolator_to_breaker"),
        # Phase A 확장
        spine_component_gap=extended.get("spine_component_gap"),
        busbar_to_breaker_gap=extended.get("busbar_to_breaker_gap"),
        isolator_to_db_gap=extended.get("isolator_to_db_gap"),
        row_spacing=extended.get("row_spacing"),
        label_char_height=extended.get("label_char_height"),
    )
