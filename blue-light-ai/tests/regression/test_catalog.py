"""Component Catalog 무결성 테스트.

카탈로그의 구조적 무결성, 핀 좌표 정확성, 기존 JSON과의 일관성을 검증한다.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.sld.catalog import ComponentCatalog, ComponentDef, Pin, get_catalog, reset_catalog

_BASE_DIR = Path(__file__).resolve().parent.parent.parent
_LEGACY_JSON = _BASE_DIR / "data" / "templates" / "real_symbol_paths.json"


@pytest.fixture(scope="module")
def catalog() -> ComponentCatalog:
    reset_catalog()
    return get_catalog()


# ---------------------------------------------------------------------------
# 구조적 무결성
# ---------------------------------------------------------------------------

class TestCatalogIntegrity:
    """카탈로그 기본 구조 검증."""

    EXPECTED_COMPONENTS = [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "KWH_METER", "CT", "ISOLATOR", "BI_CONNECTOR", "EARTH",
        "FUSE", "POTENTIAL_FUSE", "ELR",
        "AMMETER", "VOLTMETER", "SELECTOR_SWITCH", "INDICATOR_LIGHTS",
    ]

    def test_all_components_present(self, catalog):
        for name in self.EXPECTED_COMPONENTS:
            assert catalog.has(name), f"Component '{name}' missing from catalog"

    def test_component_count(self, catalog):
        assert len(catalog) >= 17, f"Expected ≥17 components, got {len(catalog)}"

    def test_cb_prefix_fallback(self, catalog):
        """CB_ 접두사로도 조회 가능."""
        for prefix_name in ["CB_MCCB", "CB_MCB", "CB_RCCB", "CB_ELCB", "CB_ACB"]:
            comp = catalog.get(prefix_name)
            assert comp is not None, f"CB_ prefix lookup failed for '{prefix_name}'"
            assert comp.name == prefix_name.removeprefix("CB_")

    def test_categories_valid(self, catalog):
        valid_categories = {"breaker", "meter", "protection", "connector", "auxiliary"}
        for name in catalog.all_names():
            comp = catalog.get(name)
            assert comp.category in valid_categories, \
                f"'{name}' has invalid category '{comp.category}'"

    def test_by_category(self, catalog):
        breakers = catalog.by_category("breaker")
        assert len(breakers) >= 5, f"Expected ≥5 breakers, got {len(breakers)}"


# ---------------------------------------------------------------------------
# 치수 유효성
# ---------------------------------------------------------------------------

class TestDimensions:
    """컴포넌트 치수가 합리적 범위 내인지."""

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "KWH_METER", "CT", "ISOLATOR", "BI_CONNECTOR", "EARTH",
        "FUSE", "POTENTIAL_FUSE", "ELR",
        "AMMETER", "VOLTMETER", "SELECTOR_SWITCH", "INDICATOR_LIGHTS",
    ])
    def test_positive_dimensions(self, name, catalog):
        comp = catalog.get(name)
        assert comp.width > 0, f"'{name}' width must be positive"
        assert comp.height > 0, f"'{name}' height must be positive"
        assert comp.stub >= 0, f"'{name}' stub must be non-negative"

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "KWH_METER", "CT", "ISOLATOR", "BI_CONNECTOR",
        "FUSE", "POTENTIAL_FUSE", "ELR",
        "AMMETER", "VOLTMETER", "SELECTOR_SWITCH",
    ])
    def test_reasonable_dimensions(self, name, catalog):
        """SLD 심볼은 0.5~30mm 범위."""
        comp = catalog.get(name)
        assert 0.5 <= comp.width <= 30, f"'{name}' width {comp.width} out of range"
        assert 0.5 <= comp.height <= 30, f"'{name}' height {comp.height} out of range"


# ---------------------------------------------------------------------------
# 핀 좌표 정확성
# ---------------------------------------------------------------------------

class TestPins:
    """핀 위치가 올바른지."""

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "CT", "ISOLATOR", "BI_CONNECTOR",
        "FUSE", "POTENTIAL_FUSE",
    ])
    def test_has_vertical_pins(self, name, catalog):
        comp = catalog.get(name)
        assert "top" in comp.pins, f"'{name}' missing 'top' pin"
        assert "bottom" in comp.pins, f"'{name}' missing 'bottom' pin"

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "CT", "ISOLATOR",
        "FUSE", "POTENTIAL_FUSE",
    ])
    def test_top_pin_above_body(self, name, catalog):
        """top pin은 body 상단(height) 위에 있어야 한다."""
        comp = catalog.get(name)
        top = comp.pin("top")
        assert top.y > comp.height, \
            f"'{name}' top pin y={top.y} should be > height={comp.height}"

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "CT", "ISOLATOR",
        "FUSE", "POTENTIAL_FUSE",
    ])
    def test_bottom_pin_below_body(self, name, catalog):
        """bottom pin은 body 하단(0) 아래에 있어야 한다."""
        comp = catalog.get(name)
        bottom = comp.pin("bottom")
        assert bottom.y < 0, \
            f"'{name}' bottom pin y={bottom.y} should be < 0"

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "CT", "ISOLATOR",
    ])
    def test_pin_x_centered(self, name, catalog):
        """수직 핀은 body 중앙 X에 위치."""
        comp = catalog.get(name)
        center_x = comp.width / 2
        top = comp.pin("top")
        bottom = comp.pin("bottom")
        assert abs(top.x - center_x) < 0.01, \
            f"'{name}' top pin x={top.x} not centered (expected {center_x})"
        assert abs(bottom.x - center_x) < 0.01, \
            f"'{name}' bottom pin x={bottom.x} not centered (expected {center_x})"

    def test_pin_absolute_calculation(self, catalog):
        """pin_absolute()가 올바르게 계산되는지."""
        mccb = catalog.get("MCCB")
        abs_top = mccb.pin_absolute("top", 207.25, 150.0)
        assert abs(abs_top[0] - 210.0) < 0.01
        assert abs(abs_top[1] - 161.0) < 0.01

    def test_indicator_lights_no_vertical_pins(self, catalog):
        """INDICATOR_LIGHTS는 horizontal-only (top/bottom 없음)."""
        lights = catalog.get("INDICATOR_LIGHTS")
        assert "top" not in lights.pins
        assert "bottom" not in lights.pins
        assert "left" in lights.pins
        assert "right" in lights.pins


# ---------------------------------------------------------------------------
# 기존 JSON과의 일관성
# ---------------------------------------------------------------------------

class TestLegacyConsistency:
    """component_catalog.json이 real_symbol_paths.json과 치수 일치."""

    @pytest.fixture(scope="class")
    def legacy_data(self):
        with open(_LEGACY_JSON) as f:
            return json.load(f)

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "KWH_METER", "CT", "ISOLATOR",
        "FUSE", "POTENTIAL_FUSE", "ELR",
    ])
    def test_width_matches_legacy(self, name, catalog, legacy_data):
        comp = catalog.get(name)
        legacy = legacy_data.get(name, {})
        if "width_mm" in legacy:
            assert abs(comp.width - legacy["width_mm"]) < 0.01, \
                f"'{name}' width {comp.width} != legacy {legacy['width_mm']}"

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "KWH_METER", "CT", "ISOLATOR",
        "FUSE", "POTENTIAL_FUSE", "ELR",
    ])
    def test_height_matches_legacy(self, name, catalog, legacy_data):
        comp = catalog.get(name)
        legacy = legacy_data.get(name, {})
        if "height_mm" in legacy:
            assert abs(comp.height - legacy["height_mm"]) < 0.01, \
                f"'{name}' height {comp.height} != legacy {legacy['height_mm']}"

    @pytest.mark.parametrize("name", [
        "MCB", "MCCB", "ACB", "RCCB", "ELCB",
        "CT", "ISOLATOR", "FUSE", "POTENTIAL_FUSE",
    ])
    def test_stub_matches_legacy(self, name, catalog, legacy_data):
        comp = catalog.get(name)
        legacy = legacy_data.get(name, {})
        if "stub_mm" in legacy:
            assert abs(comp.stub - legacy["stub_mm"]) < 0.01, \
                f"'{name}' stub {comp.stub} != legacy {legacy['stub_mm']}"


# ---------------------------------------------------------------------------
# h_extent 검증
# ---------------------------------------------------------------------------

class TestHExtent:
    """수평 배치 시 extent 값."""

    def test_mccb_h_extent_equals_height(self, catalog):
        mccb = catalog.get("MCCB")
        assert mccb.effective_h_extent == mccb.height

    def test_kwh_meter_h_extent_differs(self, catalog):
        """KWH_METER h_extent는 rect_w (height와 다름)."""
        kwh = catalog.get("KWH_METER")
        assert kwh.h_extent is not None
        assert kwh.h_extent != kwh.height

    def test_indicator_lights_h_extent(self, catalog):
        lights = catalog.get("INDICATOR_LIGHTS")
        assert lights.effective_h_extent == 16.0
