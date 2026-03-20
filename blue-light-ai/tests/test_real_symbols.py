"""
Unit tests for the real symbol library.

Tests cover:
- Symbol registry (REAL_SYMBOL_MAP, get_real_symbol)
- Symbol geometry (width, height, pins, anchors)
- Drawing primitives (draw() produces SVG output without errors)
- BaseSymbol methods (get_pin, get_anchor, center, to_svg)
"""

import pytest

from app.sld.real_symbols import (
    REAL_SYMBOL_MAP,
    RealACB,
    RealCircuitBreaker,
    RealCT,
    RealELCB,
    RealEarth,
    RealFuse,
    RealIsolator,
    RealKwhMeter,
    RealMCB,
    RealMCCB,
    RealRCCB,
    get_real_symbol,
    get_symbol_dimensions,
)
from app.sld.svg_backend import SvgBackend


# =============================================
# Symbol registry
# =============================================

class TestSymbolRegistry:
    """REAL_SYMBOL_MAP registry and get_real_symbol lookup."""

    def test_all_core_types_registered(self):
        expected = ["MCB", "MCCB", "ACB", "RCCB", "ELCB", "KWH_METER", "CT", "ISOLATOR", "EARTH", "FUSE"]
        for sym_type in expected:
            assert sym_type in REAL_SYMBOL_MAP, f"{sym_type} not in REAL_SYMBOL_MAP"

    def test_get_real_symbol_by_name(self):
        sym = get_real_symbol("MCB")
        assert isinstance(sym, RealMCB)

    def test_get_real_symbol_with_cb_prefix(self):
        sym = get_real_symbol("CB_MCB")
        assert isinstance(sym, RealMCB)

    def test_get_real_symbol_unknown_raises(self):
        with pytest.raises(ValueError, match="Unknown real symbol"):
            get_real_symbol("NONEXISTENT_WIDGET")

    def test_each_symbol_instantiates(self):
        """Every registered symbol can be instantiated without error."""
        for name, cls in REAL_SYMBOL_MAP.items():
            sym = cls()
            assert sym is not None


# =============================================
# Symbol dimensions
# =============================================

class TestSymbolDimensions:
    """Symbol width, height, pins, anchors from calibrated JSON data."""

    def test_get_symbol_dimensions_mcb(self):
        dims = get_symbol_dimensions("MCB")
        assert "width_mm" in dims
        assert "height_mm" in dims
        assert dims["width_mm"] > 0
        assert dims["height_mm"] > 0

    def test_get_symbol_dimensions_unknown_raises(self):
        with pytest.raises(ValueError, match="Unknown symbol type"):
            get_symbol_dimensions("NONEXISTENT")

    @pytest.mark.parametrize("sym_type", ["MCB", "MCCB", "ACB", "RCCB", "ELCB",
                                           "KWH_METER", "CT", "ISOLATOR", "EARTH", "FUSE"])
    def test_positive_dimensions(self, sym_type):
        sym = get_real_symbol(sym_type)
        assert sym.width > 0
        assert sym.height > 0


# =============================================
# BaseSymbol methods
# =============================================

class TestBaseSymbolMethods:
    """get_pin, get_anchor, center, to_svg."""

    def test_get_pin(self):
        sym = RealMCB()
        top = sym.get_pin("top")
        bottom = sym.get_pin("bottom")
        assert top[1] > bottom[1]  # Top pin higher than bottom

    def test_get_pin_unknown_raises(self):
        sym = RealMCB()
        with pytest.raises(ValueError, match="no pin"):
            sym.get_pin("left")

    def test_get_pin_absolute(self):
        sym = RealMCB()
        abs_pin = sym.get_pin_absolute("top", 100, 200)
        rel_pin = sym.get_pin("top")
        assert abs_pin == (100 + rel_pin[0], 200 + rel_pin[1])

    def test_get_anchor(self):
        sym = RealMCB()
        anchor = sym.get_anchor("label_right", 100, 200)
        assert anchor[0] > 100  # Right of center

    def test_get_anchor_unknown_raises(self):
        sym = RealMCB()
        with pytest.raises(ValueError, match="no anchor"):
            sym.get_anchor("nonexistent")

    def test_center(self):
        sym = RealMCB()
        cx, cy = sym.center()
        assert abs(cx - sym.width / 2) < 0.01
        assert abs(cy - sym.height / 2) < 0.01

    def test_to_svg(self):
        sym = RealMCB()
        svg = sym.to_svg()
        assert '<svg' in svg
        assert '</svg>' in svg
        assert sym.name in svg


# =============================================
# Circuit breaker symbols
# =============================================

class TestCircuitBreakers:
    """MCB, MCCB, ACB circuit breaker drawing."""

    def test_mcb_draw_no_error(self):
        sym = RealMCB()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_mccb_draw_no_error(self):
        sym = RealMCCB()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_acb_draw_has_crossbar(self):
        sym = RealACB()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        # ACB has extra crossbar line (one more element than base CB)
        mcb = RealMCB()
        mcb_backend = SvgBackend()
        mcb.draw(mcb_backend, 100, 100)
        assert len(backend._elements) > len(mcb_backend._elements)

    def test_draw_skip_trip_arrow(self):
        sym = RealMCB()
        backend_with = SvgBackend()
        sym.draw(backend_with, 100, 100, skip_trip_arrow=False)
        backend_without = SvgBackend()
        sym.draw(backend_without, 100, 100, skip_trip_arrow=True)
        # Fewer elements when skip_trip_arrow=True
        assert len(backend_without._elements) < len(backend_with._elements)

    def test_mcb_has_arc_element(self):
        sym = RealMCB()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        arcs = [el for el in backend._elements if '<path' in el and 'A ' in el]
        assert len(arcs) >= 1

    def test_mcb_has_contact_circles(self):
        sym = RealMCB()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        circles = [el for el in backend._elements if '<circle' in el]
        assert len(circles) >= 2  # Top and bottom contacts

    def test_draw_horizontal(self):
        sym = RealMCB()
        backend = SvgBackend()
        sym.draw_horizontal(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_mccb_wider_than_mcb(self):
        mcb = RealMCB()
        mccb = RealMCCB()
        assert mccb.width >= mcb.width


# =============================================
# RCCB / ELCB symbols
# =============================================

class TestRCCBELCB:
    """RCCB and ELCB (same symbol, different name)."""

    def test_rccb_draw_no_error(self):
        sym = RealRCCB()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_elcb_draw_no_error(self):
        sym = RealELCB()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_rccb_has_rcd_bar(self):
        sym = RealRCCB()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        # RCCB has RCD bar (horizontal + vertical lines) beyond base CB elements
        lines = [el for el in backend._elements if '<line' in el]
        assert len(lines) >= 4  # stubs + RCD bar lines

    def test_elcb_name(self):
        sym = RealELCB()
        assert sym.name == "CB_ELCB"


# =============================================
# KWH Meter
# =============================================

class TestKwhMeter:
    """kWh Meter symbol."""

    def test_draw_no_error(self):
        sym = RealKwhMeter()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_has_kwh_label(self):
        sym = RealKwhMeter()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        texts = [el for el in backend._elements if 'KWH' in el]
        assert len(texts) >= 1

    def test_has_rectangle(self):
        sym = RealKwhMeter()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        polys = [el for el in backend._elements if '<polygon' in el]
        assert len(polys) >= 1

    def test_draw_horizontal(self):
        sym = RealKwhMeter()
        backend = SvgBackend()
        sym.draw_horizontal(backend, 100, 100)
        assert len(backend._elements) > 0


# =============================================
# CT (Current Transformer)
# =============================================

class TestCT:
    """Current Transformer symbol."""

    def test_draw_no_error(self):
        sym = RealCT()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_draws_stubs_only(self):
        """CT arcs are rendered via junction_arrows; symbol draws stubs only."""
        sym = RealCT()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        arcs = [el for el in backend._elements if 'path' in el or 'arc' in el.lower()]
        assert len(arcs) == 0  # arcs delegated to junction_arrows


# =============================================
# Isolator
# =============================================

class TestIsolator:
    """Isolator / Disconnect Switch symbol."""

    def test_draw_no_error(self):
        sym = RealIsolator()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_has_contact_circle(self):
        sym = RealIsolator()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        circles = [el for el in backend._elements if '<circle' in el]
        assert len(circles) >= 1

    def test_draw_horizontal(self):
        sym = RealIsolator()
        backend = SvgBackend()
        sym.draw_horizontal(backend, 100, 100)
        assert len(backend._elements) > 0


# =============================================
# Earth symbol
# =============================================

class TestEarth:
    """Earth / Ground symbol."""

    def test_draw_no_error(self):
        sym = RealEarth()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_has_horizontal_lines(self):
        sym = RealEarth()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        lines = [el for el in backend._elements if '<line' in el]
        assert len(lines) >= 4  # vertical + 3 horizontal


# =============================================
# Fuse symbol
# =============================================

class TestFuse:
    """Fuse symbol."""

    def test_draw_no_error(self):
        sym = RealFuse()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        assert len(backend._elements) > 0

    def test_has_filled_rect(self):
        sym = RealFuse()
        backend = SvgBackend()
        sym.draw(backend, 100, 100)
        rects = [el for el in backend._elements if '<rect' in el]
        assert len(rects) >= 1


# =============================================
# C4: Symbol dimension single-source sync tests
# =============================================

class TestC4DimensionSync:
    """Verify all dimension sources stay in sync with real_symbol_paths.json (C4 fix)."""

    def test_layout_config_defaults_match_json(self):
        """LayoutConfig hardcoded defaults match real_symbol_paths.json."""
        from app.sld.layout.models import LayoutConfig
        import dataclasses

        config = LayoutConfig()
        # After __post_init__, values come from JSON.
        # Verify that the field defaults (before __post_init__) also match.
        field_defaults = {f.name: f.default for f in dataclasses.fields(config)
                         if f.name in ("breaker_w", "breaker_h", "mcb_w", "mcb_h",
                                       "rccb_w", "rccb_h", "isolator_w", "isolator_h")}
        json_values = {
            "breaker_w": get_symbol_dimensions("MCCB")["width_mm"],
            "breaker_h": get_symbol_dimensions("MCCB")["height_mm"],
            "mcb_w": get_symbol_dimensions("MCB")["width_mm"],
            "mcb_h": get_symbol_dimensions("MCB")["height_mm"],
            "rccb_w": get_symbol_dimensions("RCCB")["width_mm"],
            "rccb_h": get_symbol_dimensions("RCCB")["height_mm"],
            "isolator_w": get_symbol_dimensions("ISOLATOR")["width_mm"],
            "isolator_h": get_symbol_dimensions("ISOLATOR")["height_mm"],
        }
        for key, json_val in json_values.items():
            assert field_defaults[key] == pytest.approx(json_val, abs=0.1), \
                f"LayoutConfig.{key} default={field_defaults[key]} != JSON={json_val}"

    def test_overlap_fallback_dims_match_json(self):
        """overlap.py _FALLBACK_SYMBOL_DIMS match real_symbol_paths.json."""
        from app.sld.layout.overlap import _FALLBACK_SYMBOL_DIMS

        _KEY_MAP = {
            "CB_MCB": "MCB", "CB_MCCB": "MCCB", "CB_ACB": "ACB",
            "CB_ELCB": "ELCB", "CB_RCCB": "RCCB",
            "ISOLATOR": "ISOLATOR", "KWH_METER": "KWH_METER",
            "CT": "CT", "EARTH": "EARTH",
        }
        for overlap_key, json_key in _KEY_MAP.items():
            json_d = get_symbol_dimensions(json_key)
            fb_w, fb_h = _FALLBACK_SYMBOL_DIMS[overlap_key]
            assert fb_w == pytest.approx(json_d["width_mm"], abs=0.1), \
                f"Fallback {overlap_key} width={fb_w} != JSON {json_key}={json_d['width_mm']}"
            assert fb_h == pytest.approx(json_d["height_mm"], abs=0.1), \
                f"Fallback {overlap_key} height={fb_h} != JSON {json_key}={json_d['height_mm']}"

    def test_layout_config_post_init_overrides(self):
        """LayoutConfig.__post_init__ successfully loads all dimensions from JSON."""
        from app.sld.layout.models import LayoutConfig
        config = LayoutConfig()
        # After __post_init__, these should match JSON exactly
        assert config.mcb_w == pytest.approx(get_symbol_dimensions("MCB")["width_mm"], abs=0.01)
        assert config.breaker_h == pytest.approx(get_symbol_dimensions("MCCB")["height_mm"], abs=0.01)
        assert config.rccb_w == pytest.approx(get_symbol_dimensions("RCCB")["width_mm"], abs=0.01)
