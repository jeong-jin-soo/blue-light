"""Tests for PageConfig — single source of truth for page dimensions."""

import pytest

from app.sld.layout.models import LayoutConfig
from app.sld.page_config import A3_LANDSCAPE, PageConfig


class TestPageConfigDefaults:
    """A3_LANDSCAPE must match the current hardcoded values across the codebase."""

    def test_default_page_dimensions(self):
        pc = PageConfig()
        assert pc.page_width == 420.0
        assert pc.page_height == 297.0
        assert pc.margin == 10.0
        assert pc.title_block_height == 32.0

    def test_a3_landscape_singleton(self):
        assert A3_LANDSCAPE.page_width == 420.0
        assert A3_LANDSCAPE.page_height == 297.0

    def test_drawing_boundaries(self):
        pc = A3_LANDSCAPE
        assert pc.drawing_left == 10.0
        assert pc.drawing_right == 410.0
        assert pc.drawing_top == 287.0
        assert pc.drawing_bottom == 10.0

    def test_title_block_zone(self):
        pc = A3_LANDSCAPE
        assert pc.title_block_bottom == 10.0
        assert pc.title_block_top == 42.0  # 10 + 32 = current TB_TOP

    def test_usable_dimensions(self):
        pc = A3_LANDSCAPE
        assert pc.usable_width == 400.0   # 420 - 20
        assert pc.usable_height == 277.0  # 297 - 20


class TestPageConfigCustom:
    """Custom page sizes must derive correct boundaries."""

    def test_a4_landscape(self):
        pc = PageConfig(page_width=297.0, page_height=210.0)
        assert pc.drawing_right == 287.0  # 297 - 10
        assert pc.drawing_top == 200.0    # 210 - 10
        assert pc.usable_width == 277.0   # 297 - 20

    def test_custom_margin(self):
        pc = PageConfig(margin=15.0)
        assert pc.drawing_left == 15.0
        assert pc.drawing_right == 405.0  # 420 - 15
        assert pc.usable_width == 390.0   # 420 - 30

    def test_custom_title_block_height(self):
        pc = PageConfig(title_block_height=50.0)
        assert pc.title_block_top == 60.0  # 10 + 50


class TestPageConfigFrozen:
    """PageConfig must be immutable."""

    def test_frozen_page_width(self):
        pc = PageConfig()
        with pytest.raises(AttributeError):
            pc.page_width = 500.0  # type: ignore[misc]

    def test_frozen_margin(self):
        pc = PageConfig()
        with pytest.raises(AttributeError):
            pc.margin = 20.0  # type: ignore[misc]


class TestLayoutConfigFromPageConfig:
    """LayoutConfig.from_page_config() must derive boundary fields from PageConfig."""

    def test_default_matches_direct_construction(self):
        """from_page_config() with no args must match LayoutConfig() for all boundary fields."""
        direct = LayoutConfig()
        derived = LayoutConfig.from_page_config()
        assert derived.drawing_width == direct.drawing_width
        assert derived.drawing_height == direct.drawing_height
        assert derived.min_x == direct.min_x
        assert derived.max_x == direct.max_x
        assert derived.min_y == direct.min_y
        assert derived.max_y == direct.max_y
        assert derived.start_x == direct.start_x
        assert derived.start_y == direct.start_y

    def test_overrides_applied(self):
        """Keyword overrides must take precedence over derived values."""
        lc = LayoutConfig.from_page_config(min_x=50)
        assert lc.min_x == 50
        # Other boundary fields still derived from A3
        assert lc.max_x == 395

    def test_custom_page_produces_different_boundaries(self):
        """A smaller page must produce smaller boundary values."""
        pc = PageConfig(page_width=297.0, page_height=210.0)
        lc = LayoutConfig.from_page_config(pc)
        assert lc.min_x < lc.max_x
        assert lc.min_y < lc.max_y
        assert lc.drawing_width < 380  # Smaller than A3

    def test_a3_explicit_matches_default(self):
        """Passing A3_LANDSCAPE explicitly must produce same result as no args."""
        default = LayoutConfig.from_page_config()
        explicit = LayoutConfig.from_page_config(A3_LANDSCAPE)
        assert default.drawing_width == explicit.drawing_width
        assert default.drawing_height == explicit.drawing_height
        assert default.min_x == explicit.min_x
        assert default.max_x == explicit.max_x
        assert default.min_y == explicit.min_y
        assert default.max_y == explicit.max_y
        assert default.start_x == explicit.start_x
        assert default.start_y == explicit.start_y

    def test_spacing_fields_use_calibrated_defaults(self):
        """Non-boundary fields must remain at calibrated defaults, not be affected by page config."""
        lc = LayoutConfig.from_page_config()
        defaults = LayoutConfig()
        assert lc.vertical_spacing == defaults.vertical_spacing
        assert lc.horizontal_spacing == defaults.horizontal_spacing
        assert lc.busbar_margin == defaults.busbar_margin
        assert lc.max_circuits_per_row == defaults.max_circuits_per_row
        assert lc.cable_schedule_reserve == defaults.cable_schedule_reserve
