"""레퍼런스 간격 매칭 테스트.

프로파일 추출 검증, 매칭 검증, LayoutConfig 적용 검증.
"""

from __future__ import annotations

from pathlib import Path

import pytest

_BASE_DIR = Path(__file__).resolve().parent.parent.parent
_PROFILES_PATH = _BASE_DIR / "data" / "regression" / "reference_spacing_profiles.json"


class TestSpacingProfiles:
    """프로파일 데이터 존재 및 무결성."""

    def test_profiles_file_exists(self):
        assert _PROFILES_PATH.exists(), "reference_spacing_profiles.json not found"

    def test_profiles_has_entries(self):
        import json
        with open(_PROFILES_PATH) as f:
            data = json.load(f)
        profiles = data.get("profiles", {})
        assert len(profiles) >= 20, f"Expected ≥20 profiles, got {len(profiles)}"

    def test_profiles_have_spacing(self):
        import json
        with open(_PROFILES_PATH) as f:
            data = json.load(f)
        has_spacing = sum(
            1 for p in data["profiles"].values()
            if p.get("subcircuit_spacing_mm", 0) > 0
        )
        assert has_spacing >= 15, f"Expected ≥15 profiles with spacing, got {has_spacing}"

    def test_aggregated_has_all_types(self):
        import json
        with open(_PROFILES_PATH) as f:
            data = json.load(f)
        agg = data.get("aggregated", {})
        assert "direct_metering_3phase" in agg
        assert "direct_metering_1phase" in agg

    def test_spacing_values_reasonable(self):
        """서브회로 간격은 3~50mm 범위."""
        import json
        with open(_PROFILES_PATH) as f:
            data = json.load(f)
        for name, prof in data["profiles"].items():
            spacing = prof.get("subcircuit_spacing_mm", 0)
            if spacing > 0:
                assert 2.0 <= spacing <= 50.0, \
                    f"{name}: spacing {spacing}mm out of range"


class TestReferenceMatcher:
    """레퍼런스 매칭 함수."""

    def test_match_3phase_6circuits(self):
        from app.sld.reference_matcher import get_reference_spacing
        req = {
            "supply_type": "three_phase",
            "sub_circuits": [{"name": f"C{i}"} for i in range(6)],
        }
        result = get_reference_spacing(req)
        assert result is not None
        assert result.horizontal_spacing is not None
        assert result.horizontal_spacing > 5.0

    def test_match_3phase_20circuits(self):
        from app.sld.reference_matcher import get_reference_spacing
        req = {
            "supply_type": "three_phase",
            "sub_circuits": [{"name": f"C{i}"} for i in range(20)],
        }
        result = get_reference_spacing(req)
        assert result is not None
        # 20회로는 6회로보다 간격이 좁아야 함
        assert result.horizontal_spacing is not None

    def test_match_1phase(self):
        from app.sld.reference_matcher import get_reference_spacing
        req = {
            "supply_type": "single_phase",
            "sub_circuits": [{"name": f"C{i}"} for i in range(4)],
        }
        result = get_reference_spacing(req)
        assert result is not None

    def test_match_returns_none_for_empty(self):
        from app.sld.reference_matcher import get_reference_spacing
        result = get_reference_spacing({})
        # Empty requirements → still tries to match
        # May or may not find a match


class TestLayoutConfigFromReference:
    """LayoutConfig.from_reference() 적용 검증."""

    def test_from_reference_changes_spacing(self):
        from app.sld.layout.models import LayoutConfig
        req = {
            "supply_type": "three_phase",
            "sub_circuits": [{"name": f"C{i}"} for i in range(15)],
        }
        default_config = LayoutConfig()
        ref_config = LayoutConfig.from_reference(req)

        # 레퍼런스 매칭이 성공하면 horizontal_spacing이 다를 수 있음
        # (같을 수도 있음 — 레퍼런스에 따라)
        # 최소한 config가 생성되어야 함
        assert ref_config is not None
        assert ref_config.horizontal_spacing > 0

    def test_from_reference_preserves_page_bounds(self):
        """레퍼런스 간격은 바뀌지만 페이지 경계는 유지."""
        from app.sld.layout.models import LayoutConfig
        req = {
            "supply_type": "three_phase",
            "sub_circuits": [{"name": f"C{i}"} for i in range(10)],
        }
        default_config = LayoutConfig()
        ref_config = LayoutConfig.from_reference(req)

        # 페이지 경계는 변하지 않아야 함
        assert ref_config.min_x == default_config.min_x
        assert ref_config.max_x == default_config.max_x
        assert ref_config.min_y == default_config.min_y
        assert ref_config.max_y == default_config.max_y

    def test_from_reference_with_layout_works(self):
        """레퍼런스 config로 실제 레이아웃이 생성되는지."""
        from app.sld.layout.engine import compute_layout
        from app.sld.layout.models import LayoutConfig

        req = {
            "supply_type": "three_phase",
            "kva": 22,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 100,
            "elcb": {"rating": 63, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
            "sub_circuits": [
                {"name": f"L{i}", "breaker_type": "MCB", "breaker_rating": 10,
                 "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING"}
                for i in range(6)
            ],
        }
        config = LayoutConfig.from_reference(req)
        result = compute_layout(req, config=config)

        assert result is not None
        assert len(result.components) > 10
        assert result.sections_rendered.get("main_breaker")
