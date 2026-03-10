"""
Unit tests for the SVG drawing backend.

Tests cover:
- Coordinate transformation (_flip_y)
- Drawing primitives (line, polyline, circle, arc, mtext, filled_rect)
- Layer management (stroke colors/widths)
- Output generation (get_svg_string)
"""

import math

import pytest

from app.sld.svg_backend import SvgBackend
from app.sld.page_config import PageConfig


# =============================================
# Page configuration
# =============================================

class TestSvgPageConfig:
    """PageConfig support and backward compatibility."""

    def test_page_config_overrides_params(self):
        """PageConfig overrides explicit page_width/page_height params."""
        custom = PageConfig(page_width=594.0, page_height=420.0)  # A2 landscape
        svg = SvgBackend(page_width=100.0, page_height=50.0, page_config=custom)
        assert svg._page_width == 594.0
        assert svg._page_height == 420.0

    def test_backward_compat_direct_params(self):
        """Direct page_width/page_height still work without page_config."""
        svg = SvgBackend(page_width=600.0, page_height=400.0)
        assert svg._page_width == 600.0
        assert svg._page_height == 400.0


# =============================================
# Coordinate transformation
# =============================================

class TestFlipY:
    """Y-axis inversion for bottom-left to SVG top-left coordinate conversion."""

    def test_bottom_becomes_page_height(self):
        svg = SvgBackend(page_height=297.0)
        assert svg._flip_y(0.0) == 297.0

    def test_top_becomes_zero(self):
        svg = SvgBackend(page_height=297.0)
        assert svg._flip_y(297.0) == 0.0

    def test_midpoint(self):
        svg = SvgBackend(page_height=200.0)
        assert svg._flip_y(100.0) == 100.0

    def test_custom_page_height(self):
        svg = SvgBackend(page_height=500.0)
        assert svg._flip_y(300.0) == 200.0


# =============================================
# Layer management
# =============================================

class TestLayerManagement:
    """Layer switching affects stroke color and width."""

    def test_default_layer_is_symbols(self):
        svg = SvgBackend()
        assert svg._current_layer == "SLD_SYMBOLS"

    def test_set_layer(self):
        svg = SvgBackend()
        svg.set_layer("SLD_CONNECTIONS")
        assert svg._current_layer == "SLD_CONNECTIONS"

    def test_stroke_color_always_black(self):
        svg = SvgBackend()
        for layer in ["SLD_SYMBOLS", "SLD_CONNECTIONS", "SLD_POWER_MAIN", "SLD_ANNOTATIONS"]:
            svg.set_layer(layer)
            assert '#000000' in svg._stroke()

    def test_power_main_thicker_stroke(self):
        svg = SvgBackend()
        svg.set_layer("SLD_POWER_MAIN")
        stroke = svg._stroke()
        assert '0.5' in stroke or '0.50' in stroke

    def test_symbols_layer_thinner_stroke(self):
        svg = SvgBackend()
        svg.set_layer("SLD_SYMBOLS")
        stroke = svg._stroke()
        assert '0.25' in stroke

    def test_unknown_layer_defaults(self):
        svg = SvgBackend()
        svg.set_layer("NONEXISTENT")
        stroke = svg._stroke()
        # Should still produce valid stroke attribute
        assert 'stroke=' in stroke


# =============================================
# Drawing primitives — add_line
# =============================================

class TestAddLine:
    """Line primitive SVG generation."""

    def test_basic_line(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_line((10, 20), (50, 80))
        assert len(svg._elements) == 1
        el = svg._elements[0]
        assert '<line' in el
        assert 'x1="10.00"' in el
        assert 'y1="80.00"' in el  # 100 - 20
        assert 'x2="50.00"' in el
        assert 'y2="20.00"' in el  # 100 - 80

    def test_custom_lineweight(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_line((0, 0), (10, 10), lineweight=50)  # 50 hundredths = 0.5mm
        el = svg._elements[0]
        assert 'stroke-width="0.50"' in el

    def test_default_lineweight_from_layer(self):
        svg = SvgBackend(page_height=100.0)
        svg.set_layer("SLD_POWER_MAIN")
        svg.add_line((0, 0), (10, 10))
        el = svg._elements[0]
        # SLD_POWER_MAIN has 0.50 width
        assert '0.5' in el or '0.50' in el


# =============================================
# Drawing primitives — add_lwpolyline
# =============================================

class TestAddPolyline:
    """Polyline / polygon SVG generation."""

    def test_open_polyline(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_lwpolyline([(0, 0), (10, 10), (20, 0)])
        el = svg._elements[0]
        assert '<polyline' in el

    def test_closed_polygon(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_lwpolyline([(0, 0), (10, 0), (10, 10), (0, 10)], close=True)
        el = svg._elements[0]
        assert '<polygon' in el

    def test_single_point_no_output(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_lwpolyline([(5, 5)])
        assert len(svg._elements) == 0

    def test_custom_lineweight(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_lwpolyline([(0, 0), (10, 10)], lineweight=100)
        el = svg._elements[0]
        assert 'stroke-width="1.00"' in el

    def test_y_coordinates_flipped(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_lwpolyline([(0, 0), (10, 50)])
        el = svg._elements[0]
        # y=0 → 100, y=50 → 50
        assert '100.00' in el
        assert '50.00' in el


# =============================================
# Drawing primitives — add_circle
# =============================================

class TestAddCircle:
    """Circle SVG generation."""

    def test_basic_circle(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_circle((50, 50), 10)
        el = svg._elements[0]
        assert '<circle' in el
        assert 'cx="50.00"' in el
        assert 'cy="50.00"' in el  # 100 - 50
        assert 'r="10.00"' in el
        assert 'fill="none"' in el

    def test_filled_circle_tuple_color(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_filled_circle((50, 50), 5, fill_color=(1.0, 0.0, 0.0))
        el = svg._elements[0]
        assert 'fill="rgb(255,0,0)"' in el
        assert 'stroke="none"' in el

    def test_filled_circle_string_color(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_filled_circle((50, 50), 5, fill_color="#FF0000")
        el = svg._elements[0]
        assert 'fill="#FF0000"' in el

    def test_filled_circle_default_black(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_filled_circle((50, 50), 5)
        el = svg._elements[0]
        assert 'fill="rgb(0,0,0)"' in el


# =============================================
# Drawing primitives — add_arc
# =============================================

class TestAddArc:
    """Arc SVG generation using path commands."""

    def test_basic_arc(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_arc((50, 50), 20, 0, 90)
        el = svg._elements[0]
        assert '<path' in el
        assert 'A 20.00,20.00' in el

    def test_large_arc_flag(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_arc((50, 50), 20, 0, 270)
        el = svg._elements[0]
        # Extent = 270 > 180, large_arc should be 1
        assert '1,0' in el  # large_arc=1, sweep=0

    def test_small_arc_flag(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_arc((50, 50), 20, 0, 90)
        el = svg._elements[0]
        # Extent = 90 < 180, large_arc should be 0
        assert '0,0' in el


# =============================================
# Drawing primitives — add_mtext
# =============================================

class TestAddMtext:
    """Multiline text SVG generation."""

    def test_single_line(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_mtext("Hello", insert=(10, 50))
        el = svg._elements[0]
        assert '<text' in el
        assert '>Hello</text>' in el

    def test_multiline_non_rotated(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_mtext("Line1\\PLine2", insert=(10, 50))
        el = svg._elements[0]
        assert '<tspan' in el
        assert 'Line1' in el
        assert 'Line2' in el

    def test_rotated_text_has_transform(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_mtext("Hello", insert=(10, 50), rotation=90.0)
        el = svg._elements[0]
        assert 'transform="rotate(' in el

    def test_non_rotated_no_transform(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_mtext("Hello", insert=(10, 50), rotation=0.0)
        el = svg._elements[0]
        assert 'transform' not in el

    def test_html_escape(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_mtext("A & B <C>", insert=(10, 50))
        el = svg._elements[0]
        assert '&amp;' in el
        assert '&lt;' in el
        assert '&gt;' in el

    def test_char_height(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_mtext("Text", insert=(10, 50), char_height=5.0)
        el = svg._elements[0]
        assert 'font-size="5.0"' in el

    def test_rotated_multiline_separate_elements(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_mtext("A\\PB\\PC", insert=(10, 50), rotation=90.0)
        # Rotated multiline emits separate <text> elements
        assert len(svg._elements) == 3

    def test_non_string_input(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_mtext(12345, insert=(10, 50))
        el = svg._elements[0]
        assert '12345' in el


# =============================================
# Drawing primitives — add_filled_rect
# =============================================

class TestAddFilledRect:
    """Filled rectangle SVG generation."""

    def test_basic_rect(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_filled_rect(10, 20, 30, 40)
        el = svg._elements[0]
        assert '<rect' in el
        assert 'width="30.00"' in el
        assert 'height="40.00"' in el

    def test_y_flipped(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_filled_rect(10, 20, 30, 40)
        el = svg._elements[0]
        # SVG y = page_height - (y + height) = 100 - 60 = 40
        assert 'y="40.00"' in el

    def test_custom_fill_tuple(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_filled_rect(0, 0, 10, 10, fill_color=(0.5, 0.5, 0.5))
        el = svg._elements[0]
        assert 'fill="rgb(127,127,127)"' in el

    def test_custom_fill_string(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_filled_rect(0, 0, 10, 10, fill_color="#AABBCC")
        el = svg._elements[0]
        assert 'fill="#AABBCC"' in el


# =============================================
# Output generation
# =============================================

class TestGetSvgString:
    """Complete SVG string output."""

    def test_valid_svg_structure(self):
        svg = SvgBackend()
        result = svg.get_svg_string()
        assert result.startswith('<svg')
        assert 'xmlns="http://www.w3.org/2000/svg"' in result
        assert '</svg>' in result

    def test_contains_viewbox(self):
        svg = SvgBackend(page_width=420.0, page_height=297.0)
        result = svg.get_svg_string()
        assert 'viewBox="0 0 420.0 297.0"' in result

    def test_contains_dimensions(self):
        svg = SvgBackend(page_width=420.0, page_height=297.0)
        result = svg.get_svg_string()
        assert 'width="420.0mm"' in result
        assert 'height="297.0mm"' in result

    def test_white_background(self):
        svg = SvgBackend()
        result = svg.get_svg_string()
        assert 'fill="white"' in result

    def test_elements_included(self):
        svg = SvgBackend(page_height=100.0)
        svg.add_line((0, 0), (10, 10))
        svg.add_circle((50, 50), 5)
        result = svg.get_svg_string()
        assert '<line' in result
        assert '<circle' in result

    def test_empty_svg(self):
        svg = SvgBackend()
        result = svg.get_svg_string()
        # Should still be valid SVG even with no elements
        assert '<svg' in result
        assert '</svg>' in result

    def test_multiple_lines(self):
        svg = SvgBackend(page_height=100.0)
        for i in range(5):
            svg.add_line((i, 0), (i, 10))
        assert len(svg._elements) == 5
        result = svg.get_svg_string()
        assert result.count('<line') == 5
