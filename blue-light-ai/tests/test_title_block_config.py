"""Tests for TitleBlockConfig — computed title block geometry from PageConfig."""

import pytest

from app.sld.page_config import PageConfig
from app.sld.title_block import (
    TB_LEFT, TB_RIGHT, TB_BOTTOM, TB_TOP,
    COL1, COL2, COL3, COL4, COL5, COL6, COL6_MID,
    ROW_TOP, ROW_MID, ROW_BOT,
    TitleBlockConfig,
    _DEFAULT_TB,
    draw_border,
    draw_title_block_frame,
    fill_title_block_data,
)


class TestDefaultMatchesModuleConstants:
    """_DEFAULT_TB must produce IDENTICAL values to current hardcoded constants."""

    def test_default_matches_module_constants(self):
        assert _DEFAULT_TB.left == TB_LEFT
        assert _DEFAULT_TB.right == TB_RIGHT
        assert _DEFAULT_TB.bottom == TB_BOTTOM
        assert _DEFAULT_TB.top == TB_TOP
        assert _DEFAULT_TB.col1 == COL1
        assert _DEFAULT_TB.col2 == COL2
        assert _DEFAULT_TB.col3 == COL3
        assert _DEFAULT_TB.col4 == COL4
        assert _DEFAULT_TB.col5 == COL5
        assert _DEFAULT_TB.col6 == COL6
        assert _DEFAULT_TB.col6_mid == COL6_MID
        assert _DEFAULT_TB.row_top == ROW_TOP
        assert _DEFAULT_TB.row_mid == ROW_MID
        assert _DEFAULT_TB.row_bot == ROW_BOT


class TestProportionalScaling:
    """TitleBlockConfig must scale proportionally for different page sizes."""

    def test_proportional_scaling_a4(self):
        """Create TitleBlockConfig for A4 (297x210) and verify all cols are between left and right."""
        a4 = PageConfig(page_width=297.0, page_height=210.0)
        tbc = TitleBlockConfig.from_page_config(a4)

        # Basic bounds
        assert tbc.left == a4.margin
        assert tbc.right == a4.page_width - a4.margin
        assert tbc.bottom == a4.margin
        assert tbc.top == a4.margin + a4.title_block_height

        # All column positions must be between left and right
        for col_name in ("col1", "col2", "col3", "col4", "col5", "col6", "col6_mid"):
            col_val = getattr(tbc, col_name)
            assert tbc.left <= col_val <= tbc.right, (
                f"{col_name}={col_val} out of bounds [{tbc.left}, {tbc.right}]"
            )

        # Columns must be strictly increasing
        cols = [tbc.col1, tbc.col2, tbc.col3, tbc.col4, tbc.col5, tbc.col6, tbc.col6_mid]
        for i in range(len(cols) - 1):
            assert cols[i] < cols[i + 1], f"col[{i}]={cols[i]} >= col[{i+1}]={cols[i+1]}"

        # Rows
        assert tbc.row_bot <= tbc.row_mid <= tbc.row_top


class TestFrozenImmutability:
    """TitleBlockConfig fields must not be settable (frozen dataclass)."""

    def test_frozen_immutability(self):
        with pytest.raises(AttributeError):
            _DEFAULT_TB.left = 999  # type: ignore[misc]

    def test_frozen_col2(self):
        with pytest.raises(AttributeError):
            _DEFAULT_TB.col2 = 0  # type: ignore[misc]

    def test_frozen_row_mid(self):
        with pytest.raises(AttributeError):
            _DEFAULT_TB.row_mid = 0  # type: ignore[misc]


class TestDrawBorderDefaultUnchanged:
    """draw_border with no page_config must not raise errors."""

    def test_draw_border_default_unchanged(self):
        """Call draw_border with no page_config — should produce same output as before."""
        from app.sld.svg_backend import SvgBackend

        backend = SvgBackend()
        # Should not raise — backward-compatible default
        draw_border(backend)
        svg = backend.get_svg_string()
        assert "polyline" in svg.lower() or "polygon" in svg.lower() or "path" in svg.lower()

    def test_draw_border_with_explicit_page_config(self):
        """draw_border with explicit A4 PageConfig must not raise."""
        from app.sld.svg_backend import SvgBackend

        a4 = PageConfig(page_width=297.0, page_height=210.0)
        backend = SvgBackend()
        draw_border(backend, page_config=a4)
        svg = backend.get_svg_string()
        assert len(svg) > 0


class TestFromPageConfigDefaults:
    """from_page_config with None must use A3_LANDSCAPE."""

    def test_none_uses_a3_landscape(self):
        tbc = TitleBlockConfig.from_page_config(None)
        assert tbc.left == _DEFAULT_TB.left
        assert tbc.right == _DEFAULT_TB.right
        assert tbc.col6_mid == _DEFAULT_TB.col6_mid

    def test_no_args_uses_a3_landscape(self):
        tbc = TitleBlockConfig.from_page_config()
        assert tbc == _DEFAULT_TB
