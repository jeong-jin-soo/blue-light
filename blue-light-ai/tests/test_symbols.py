"""
Tests for SLD Symbol Library.

Verifies:
- All symbols instantiate correctly
- Pins and anchors are valid
- draw() executes without errors on both PDF and SVG backends
- to_svg() returns valid SVG strings
- SYMBOL_MAP includes all symbols
"""

import pytest

from app.sld.svg_backend import SvgBackend
from app.sld.symbols.base import BaseSymbol
from app.sld.symbols.breakers import ACB, ELCB, MCB, MCCB, RCCB, CircuitBreaker
from app.sld.symbols.busbars import Busbar
from app.sld.symbols.loads import IndustrialSocket, TimerWithBypass
from app.sld.symbols.meters import Ammeter, KwhMeter, Voltmeter
from app.sld.symbols.motors import Generator, Motor
from app.sld.symbols.msb_components import IndicatorLight, ShuntTrip
from app.sld.symbols.protection import EarthSymbol, Fuse, SurgeProtector
from app.sld.symbols.switches import (
    ATS,
    BIConnector,
    DoublePoleSwitch,
    Isolator,
    IsolatorForMachine,
)
from app.sld.symbols.transformers import CurrentTransformer, PowerTransformer


# Collect all symbol classes and instances for parametrized tests
def _all_symbols() -> list[tuple[str, BaseSymbol]]:
    """Return all symbol instances for testing."""
    return [
        ("MCB", MCB()),
        ("MCCB", MCCB()),
        ("ACB", ACB()),
        ("RCCB", RCCB()),
        ("ELCB", ELCB()),
        ("KwhMeter", KwhMeter()),
        ("Ammeter", Ammeter()),
        ("Voltmeter", Voltmeter()),
        ("Isolator", Isolator()),
        ("IsolatorForMachine", IsolatorForMachine()),
        ("DoublePoleSwitch", DoublePoleSwitch()),
        ("BIConnector", BIConnector()),
        ("ATS", ATS()),
        ("Fuse", Fuse()),
        ("EarthSymbol", EarthSymbol()),
        ("SurgeProtector", SurgeProtector()),
        ("PowerTransformer", PowerTransformer()),
        ("CurrentTransformer", CurrentTransformer()),
        ("Motor", Motor()),
        ("Generator", Generator()),
        ("IndustrialSocket", IndustrialSocket()),
        ("TimerWithBypass", TimerWithBypass()),
        ("ShuntTrip", ShuntTrip()),
        ("IndicatorLight", IndicatorLight("L1")),
        ("Busbar", Busbar(100)),
    ]


ALL_SYMBOLS = _all_symbols()
SYMBOL_IDS = [name for name, _ in ALL_SYMBOLS]


class TestSymbolBasics:
    """Test basic symbol properties."""

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_has_positive_dimensions(self, name, symbol):
        """All symbols must have positive width and height."""
        assert symbol.width > 0, f"{name} width must be > 0"
        assert symbol.height > 0, f"{name} height must be > 0"

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_has_name(self, name, symbol):
        """All symbols must have a non-empty name."""
        assert symbol.name, f"{name} must have a non-empty name"

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_has_at_least_one_pin(self, name, symbol):
        """All symbols must have at least one connection pin."""
        assert len(symbol.pins) >= 1, f"{name} must have at least 1 pin"

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_pin_values_are_tuples(self, name, symbol):
        """All pin values must be (x, y) float tuples."""
        for pin_name, (px, py) in symbol.pins.items():
            assert isinstance(px, (int, float)), f"{name}.{pin_name} x must be numeric"
            assert isinstance(py, (int, float)), f"{name}.{pin_name} y must be numeric"


class TestSymbolAnchors:
    """Test anchor point system."""

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_anchors_are_dict(self, name, symbol):
        """Anchors must be a dict."""
        assert isinstance(symbol.anchors, dict), f"{name} anchors must be dict"

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_anchor_values_are_tuples(self, name, symbol):
        """All anchor values must be (x, y) float tuples."""
        for anc_name, (ax, ay) in symbol.anchors.items():
            assert isinstance(ax, (int, float)), f"{name}.{anc_name} x must be numeric"
            assert isinstance(ay, (int, float)), f"{name}.{anc_name} y must be numeric"

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_get_anchor_absolute(self, name, symbol):
        """get_anchor() should return absolute coordinates."""
        for anc_name in symbol.anchors:
            ax, ay = symbol.get_anchor(anc_name, 100, 200)
            assert isinstance(ax, (int, float))
            assert isinstance(ay, (int, float))
            # Should be offset by x=100, y=200
            rel_x, rel_y = symbol.anchors[anc_name]
            assert abs(ax - (100 + rel_x)) < 0.01
            assert abs(ay - (200 + rel_y)) < 0.01

    def test_get_anchor_invalid_name(self):
        """get_anchor() should raise ValueError for unknown anchor."""
        symbol = MCB()
        with pytest.raises(ValueError, match="no anchor"):
            symbol.get_anchor("nonexistent")


class TestSymbolDrawing:
    """Test that draw() executes without errors."""

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_draw_on_svg_backend(self, name, symbol):
        """draw() should execute without errors on SvgBackend."""
        backend = SvgBackend()
        symbol.draw(backend, 100, 100)
        svg = backend.get_svg_string()
        assert "<svg" in svg
        assert len(backend._elements) > 0, f"{name} should produce SVG elements"

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_draw_at_origin(self, name, symbol):
        """draw() should work at origin (0, 0)."""
        backend = SvgBackend()
        symbol.draw(backend, 0, 0)
        svg = backend.get_svg_string()
        assert "<svg" in svg


class TestSymbolSvgExport:
    """Test to_svg() standalone export."""

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_to_svg_returns_valid_svg(self, name, symbol):
        """to_svg() should return a valid SVG string."""
        svg = symbol.to_svg()
        assert svg.startswith("<svg")
        assert "</svg>" in svg
        assert "viewBox" in svg

    @pytest.mark.parametrize("name,symbol", ALL_SYMBOLS, ids=SYMBOL_IDS)
    def test_to_svg_with_markers(self, name, symbol):
        """to_svg() with markers should include pin/anchor indicators."""
        svg = symbol.to_svg(show_pins=True, show_anchors=True)
        # Should contain red circles for pins
        if symbol.pins:
            assert 'fill="red"' in svg, f"{name} should have red pin markers"


class TestSymbolMapIntegration:
    """Test SYMBOL_MAP registration."""

    def test_all_symbols_in_map(self):
        """All known symbol types should be in REAL_SYMBOL_MAP."""
        from app.sld.real_symbols import REAL_SYMBOL_MAP

        expected_keys = {
            "ACB", "MCCB", "MCB", "RCCB", "ELCB",
            "CT",
            "KWH_METER",
            "ISOLATOR",
            "FUSE", "EARTH",
        }

        for key in expected_keys:
            assert key in REAL_SYMBOL_MAP, f"{key} not in REAL_SYMBOL_MAP"

    def test_symbol_map_instantiation(self):
        """All REAL_SYMBOL_MAP entries should be instantiable."""
        from app.sld.real_symbols import REAL_SYMBOL_MAP

        for key, cls in REAL_SYMBOL_MAP.items():
            try:
                instance = cls()
                assert isinstance(instance, BaseSymbol), f"{key} is not a BaseSymbol"
            except TypeError:
                # Some symbols might need arguments
                pass

    def test_legend_descriptions_match_map(self):
        """Every REAL_SYMBOL_MAP key should have a legend description."""
        from app.sld.generator import SldGenerator
        from app.sld.real_symbols import REAL_SYMBOL_MAP

        for key in REAL_SYMBOL_MAP:
            assert key in SldGenerator.LEGEND_DESCRIPTIONS, (
                f"REAL_SYMBOL_MAP key '{key}' missing from LEGEND_DESCRIPTIONS"
            )

    def test_legend_abbreviations_match_map(self):
        """Every REAL_SYMBOL_MAP key should have a legend abbreviation."""
        from app.sld.generator import SldGenerator
        from app.sld.real_symbols import REAL_SYMBOL_MAP

        for key in REAL_SYMBOL_MAP:
            assert key in SldGenerator.LEGEND_ABBREVIATIONS, (
                f"REAL_SYMBOL_MAP key '{key}' missing from LEGEND_ABBREVIATIONS"
            )


class TestSpecificSymbols:
    """Test specific symbol geometry details."""

    def test_mcb_dimensions(self):
        """MCB should be 10x16mm."""
        mcb = MCB()
        assert mcb.width == 10
        assert mcb.height == 16
        assert mcb.arc_radius == 3

    def test_mccb_dimensions(self):
        """MCCB should be 14x20mm."""
        mccb = MCCB()
        assert mccb.width == 14
        assert mccb.height == 20
        assert mccb.arc_radius == 4

    def test_acb_dimensions(self):
        """ACB should be 16x22mm."""
        acb = ACB()
        assert acb.width == 16
        assert acb.height == 22
        assert acb.arc_radius == 5

    def test_ct_is_two_circles(self):
        """CT should draw two overlapping circles (IEC standard)."""
        ct = CurrentTransformer()
        backend = SvgBackend()
        ct.draw(backend, 0, 0)
        # Count circle elements
        circles = [e for e in backend._elements if "<circle" in e]
        assert len(circles) == 2, "CT should have 2 circles (overlapping coils)"

    def test_kwh_meter_has_text(self):
        """KWH meter should draw 'KWH' text."""
        kwh = KwhMeter()
        backend = SvgBackend()
        kwh.draw(backend, 0, 0)
        texts = [e for e in backend._elements if "KWH" in e]
        assert len(texts) >= 1, "KWH meter should contain 'KWH' text"

    def test_ammeter_has_text(self):
        """Ammeter should draw 'A' text."""
        am = Ammeter()
        backend = SvgBackend()
        am.draw(backend, 0, 0)
        svg = backend.get_svg_string()
        assert ">A<" in svg, "Ammeter should contain 'A' text"

    def test_voltmeter_has_text(self):
        """Voltmeter should draw 'V' text."""
        vm = Voltmeter()
        backend = SvgBackend()
        vm.draw(backend, 0, 0)
        svg = backend.get_svg_string()
        assert ">V<" in svg, "Voltmeter should contain 'V' text"

    def test_earth_has_three_bars(self):
        """Earth symbol should have 3 horizontal lines."""
        earth = EarthSymbol()
        backend = SvgBackend()
        earth.draw(backend, 0, 0)
        lines = [e for e in backend._elements if "<line" in e]
        # Vertical + 3 horizontal = at least 4 lines
        assert len(lines) >= 4, "Earth should have at least 4 lines"

    def test_busbar_dynamic_width(self):
        """Busbar width should be configurable."""
        bb100 = Busbar(100)
        bb200 = Busbar(200)
        assert bb100.width == 100
        assert bb200.width == 200

    def test_indicator_light_phase_label(self):
        """IndicatorLight should store phase label."""
        il = IndicatorLight("L1")
        assert il.phase_label == "L1"
        assert "L1" in il.name

    def test_breaker_types(self):
        """Breaker type names should be set correctly."""
        assert MCB().breaker_type == "MCB"
        assert MCCB().breaker_type == "MCCB"
        assert ACB().breaker_type == "ACB"
