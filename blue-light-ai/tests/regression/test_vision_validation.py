"""Vision AI 검증 테스트.

pytest.mark.vision 마커 — API 호출 비용 발생, 명시적 실행만.
기본 pytest 실행에서 제외됨.

Usage:
    # Vision 테스트만 실행 (API key 필요):
    GEMINI_API_KEY=xxx pytest -m vision -v

    # Vision 제외한 전체 테스트:
    pytest -m "not vision"
"""

from __future__ import annotations

import os
import tempfile

import pytest

# Vision 테스트 마커 등록
pytestmark = pytest.mark.vision

# API key 없으면 전체 스킵
_HAS_API_KEY = bool(os.environ.get("GEMINI_API_KEY") or os.environ.get("gemini_api_key"))


@pytest.fixture(scope="module")
def generated_svgs():
    """4가지 SLD 유형별 SVG 생성 (모듈 스코프 — 한 번만)."""
    from app.sld.generator import SldPipeline

    from .conftest import ALL_CONFIGS

    results = {}
    for config_id, req in ALL_CONFIGS.items():
        try:
            svg_string = SldPipeline().run(req).svg_string
            if svg_string:
                # 임시 파일에 저장
                fd, path = tempfile.mkstemp(suffix=".svg", prefix=f"vision_{config_id}_")
                with os.fdopen(fd, "w") as f:
                    f.write(svg_string)
                results[config_id] = path
        except Exception as e:
            pytest.skip(f"Failed to generate {config_id}: {e}")

    yield results

    # 정리
    for path in results.values():
        if os.path.exists(path):
            os.unlink(path)
        png = path.replace(".svg", ".png")
        if os.path.exists(png):
            os.unlink(png)


class TestSvgToPng:
    """SVG → PNG 변환 기본 테스트 (API key 불필요)."""

    @pytest.mark.parametrize("config_id", ["direct_3phase_63a"])
    def test_svg_to_png_produces_file(self, config_id, generated_svgs):
        if config_id not in generated_svgs:
            pytest.skip(f"SVG not generated for {config_id}")

        from app.sld.vision_validator import svg_to_png

        svg_path = generated_svgs[config_id]
        png_path = svg_to_png(svg_path)
        assert os.path.exists(png_path)
        assert os.path.getsize(png_path) > 1000  # PNG should be substantial


@pytest.mark.skipif(not _HAS_API_KEY, reason="GEMINI_API_KEY not set")
class TestSelfReview:
    """Self-Review Vision 테스트 (API 호출)."""

    @pytest.mark.asyncio
    @pytest.mark.parametrize("config_id", ["direct_3phase_63a", "ct_metering_150a"])
    async def test_self_review_returns_report(self, config_id, generated_svgs):
        if config_id not in generated_svgs:
            pytest.skip(f"SVG not generated for {config_id}")

        from app.sld.vision_validator import self_review

        report = await self_review(generated_svgs[config_id])

        # 기본 구조 검증
        assert report.severity in ("pass", "warning", "fail")
        assert 0.0 <= report.score <= 1.0
        assert isinstance(report.issues, list)
        assert report.summary  # 요약 있어야 함

        # 정상 생성된 SLD는 심각한 문제 없어야 함
        assert report.severity != "fail", \
            f"[{config_id}] Vision AI reports FAIL:\n" + \
            "\n".join(f"  - [{i.severity}] {i.description}" for i in report.issues)


class TestVisionReport:
    """VisionReport 데이터 모델 테스트 (API 불필요)."""

    def test_report_to_dict(self):
        from app.sld.vision_validator import VisionIssue, VisionReport

        report = VisionReport(
            issues=[
                VisionIssue("overlap", "test", "warning", "spine", "fix"),
            ],
            severity="warning",
            score=0.8,
            summary="test",
        )
        d = report.to_dict()
        assert d["severity"] == "warning"
        assert d["score"] == 0.8
        assert len(d["issues"]) == 1
        assert "raw_response" not in d  # 큰 문자열 제외

    def test_has_critical(self):
        from app.sld.vision_validator import VisionIssue, VisionReport

        report = VisionReport(issues=[
            VisionIssue("gap", "broken", "critical"),
        ])
        assert report.has_critical

    def test_no_critical(self):
        from app.sld.vision_validator import VisionIssue, VisionReport

        report = VisionReport(issues=[
            VisionIssue("spacing", "tight", "warning"),
        ])
        assert not report.has_critical


class TestDomainContext:
    """도메인 컨텍스트 및 오탐 필터링 테스트 (API 불필요)."""

    def test_accepted_patterns_file_loads(self):
        from app.sld.vision_validator import _load_accepted_patterns

        patterns = _load_accepted_patterns()
        assert len(patterns) >= 5, f"Expected at least 5 patterns, got {len(patterns)}"
        # 필수 패턴 ID 확인
        ids = {p["id"] for p in patterns}
        assert "single_line_representation" in ids
        assert "sp_abbreviation" in ids
        assert "spn_pole_designation" in ids
        assert "circuit_id_naming" in ids
        assert "spare_no_cable_spec" in ids

    def test_domain_context_injected_in_prompt(self):
        from app.sld.vision_validator import _build_self_review_prompt

        prompt = _build_self_review_prompt()
        assert "Singapore SLD Domain Rules" in prompt
        assert "Do NOT flag" in prompt
        assert "single_line_representation" in prompt
        assert "SP Group" in prompt

    def test_false_positive_filtering(self):
        """기존 오탐 5건이 regex 필터로 제거되는지 검증."""
        from app.sld.vision_validator import VisionIssue, _filter_false_positives

        issues = [
            VisionIssue("label", "Explicit L1, L2, L3, N, E lines are not shown", "warning"),
            VisionIssue("label", "SP is ambiguous abbreviation", "info"),
            VisionIssue("label", "SPN is less explicit than standard 1P", "info"),
            VisionIssue("label", "S1 P1 SP1 non-standard circuit numbering", "warning"),
            VisionIssue("missing", "spare circuit missing cable spec provision", "warning"),
            # 이것은 진짜 이슈 — 필터되면 안 됨
            VisionIssue("overlap", "Label overlaps connection line", "warning"),
            VisionIssue("gap", "Broken spine connection", "critical"),
        ]

        real, filtered = _filter_false_positives(issues)
        assert len(filtered) == 5, f"Expected 5 filtered, got {len(filtered)}: {[f.description[:30] for f in filtered]}"
        assert len(real) == 2
        assert any("overlap" in i.description.lower() for i in real)
        assert any("gap" in i.description.lower() or "broken" in i.description.lower() for i in real)

    def test_severity_recalculated_after_filtering(self):
        """오탐 제거 후 severity가 재계산되는지 검증."""
        import json

        from app.sld.vision_validator import _parse_vision_response

        raw = json.dumps({
            "issues": [
                {"category": "label", "description": "SPN is non-standard and less explicit", "severity": "warning"},
                {"category": "label", "description": "SP abbreviation not standard", "severity": "info"},
            ],
            "severity": "warning",
            "score": 0.7,
            "summary": "test",
        })

        report = _parse_vision_response(raw)
        # 두 이슈 모두 오탐 → 필터 후 이슈 0개 → pass
        assert report.severity == "pass"
        assert len(report.issues) == 0
        assert report.score > 0.7  # 점수 상향

    def test_reference_compare_prompt_has_domain_context(self):
        from app.sld.vision_validator import _build_reference_compare_prompt

        prompt = _build_reference_compare_prompt()
        assert "Singapore SLD Domain Rules" in prompt
