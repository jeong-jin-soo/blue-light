"""
Unit tests for SLD Layout section placement functions.

Tests each _place_* function independently using _LayoutContext fixtures.
Validates that components are added correctly, Y position advances,
and structural invariants hold.
"""

import pytest

from app.sld.layout.models import (
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    _LayoutContext,
)
from app.sld.layout.sections import (
    _parse_requirements,
    _place_db_box,
    _place_earth_bar,
    _place_elcb,
    _place_incoming_supply,
    _place_main_breaker,
    _place_main_busbar,
    _place_meter_board,
    _place_sub_circuits_rows,
    _place_unit_isolator,
)


# =============================================
# Fixtures
# =============================================

def _make_config() -> LayoutConfig:
    """Create a LayoutConfig with default (real) values."""
    return LayoutConfig()


def _make_context(**overrides) -> _LayoutContext:
    """Create a minimal _LayoutContext for testing."""
    config = _make_config()
    result = LayoutResult()
    ctx = _LayoutContext(
        result=result,
        config=config,
        cx=config.start_x,
        y=config.start_y,
    )
    for k, v in overrides.items():
        setattr(ctx, k, v)
    return ctx


# =============================================
# _parse_requirements
# =============================================

class TestParseRequirements:
    """Input normalization into ctx fields."""

    def test_single_phase(self):
        ctx = _make_context()
        _parse_requirements(ctx, {"supply_type": "single_phase"}, None)
        assert ctx.supply_type == "single_phase"
        assert ctx.voltage == 230

    def test_three_phase(self):
        ctx = _make_context()
        _parse_requirements(ctx, {"supply_type": "three_phase"}, None)
        assert ctx.supply_type == "three_phase"
        assert ctx.voltage == 400

    def test_default_supply_type(self):
        ctx = _make_context()
        _parse_requirements(ctx, {}, None)
        assert ctx.supply_type == "three_phase"  # Default

    def test_main_breaker_parsing(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "supply_type": "three_phase",
            "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN"},
        }, None)
        assert ctx.breaker_type == "MCCB"
        assert ctx.breaker_rating == 63
        assert ctx.breaker_poles == "TPN"

    def test_auto_poles_single_phase(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "supply_type": "single_phase",
            "main_breaker": {"type": "MCB", "rating": 40},
        }, None)
        assert ctx.breaker_poles == "DP"

    def test_auto_poles_three_phase(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "supply_type": "three_phase",
            "main_breaker": {"type": "MCCB", "rating": 63},
        }, None)
        assert ctx.breaker_poles == "TPN"

    def test_sub_circuits_normalized(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "sub_circuits": [
                {"name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "SOCKET", "breaker_type": "MCB", "breaker_rating": 20},
            ],
        }, None)
        assert len(ctx.sub_circuits) == 2

    def test_empty_requirements(self):
        ctx = _make_context()
        _parse_requirements(ctx, {}, None)
        assert ctx.supply_type == "three_phase"
        assert ctx.breaker_rating == 0
        assert ctx.sub_circuits == []

    def test_negative_breaker_rating_reset(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "main_breaker": {"rating": -10},
        }, None)
        assert ctx.breaker_rating == 0

    def test_db_rating_fallback(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "db_rating": "63A",
        }, None)
        assert ctx.breaker_rating == 63

    def test_elcb_config(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "elcb": {"rating": 63, "sensitivity_ma": 100, "type": "RCCB"},
        }, None)
        assert ctx.elcb_rating == 63
        assert ctx.elcb_ma == 100
        assert ctx.elcb_type_str == "RCCB"

    def test_busbar_rating_auto(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "main_breaker": {"rating": 40},
        }, None)
        # Min 100A
        assert ctx.busbar_rating == 100

    def test_busbar_rating_large(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "main_breaker": {"rating": 200},
        }, None)
        assert ctx.busbar_rating == 200

    def test_landlord_supply_source(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "supply_source": "landlord",
        }, None)
        assert ctx.supply_source == "landlord"
        # Landlord: no metering by default (just unit isolator, no meter board)
        assert ctx.metering == ""

    def test_landlord_sp_meter_preserved(self):
        """Landlord supply with sp_meter should be preserved (PG KWH meter board)."""
        ctx = _make_context()
        _parse_requirements(ctx, {
            "supply_source": "landlord",
            "metering": "sp_meter",
        }, None)
        assert ctx.supply_source == "landlord"
        assert ctx.metering == "sp_meter"

    def test_cable_extension_landlord_no_meter(self):
        """Cable extension is landlord but should have no meter board."""
        ctx = _make_context()
        _parse_requirements(ctx, {
            "supply_source": "landlord",
            "metering": "sp_meter",
            "is_cable_extension": True,
        }, None)
        assert ctx.supply_source == "landlord"
        assert ctx.metering is None

    def test_landlord_ct_meter_preserved(self):
        """Landlord supply with ct_meter should be preserved."""
        ctx = _make_context()
        _parse_requirements(ctx, {
            "supply_source": "landlord",
            "metering": "ct_meter",
        }, None)
        assert ctx.supply_source == "landlord"
        assert ctx.metering == "ct_meter"

    def test_cable_extension_forces_landlord(self):
        ctx = _make_context()
        _parse_requirements(ctx, {
            "is_cable_extension": True,
            "supply_source": "sp_powergrid",
        }, None)
        assert ctx.supply_source == "landlord"
        assert ctx.is_cable_extension is True

    def test_main_breaker_rating_fallback_db_rating(self):
        """db_rating string parsed as breaker_rating when main_breaker.rating absent."""
        ctx = _make_context()
        _parse_requirements(ctx, {"kva": 20, "db_rating": "63A TPN DB"}, None)
        assert ctx.breaker_rating == 63

    def test_main_breaker_negative_rating_reset(self):
        """Negative main_breaker.rating is reset to 0."""
        ctx = _make_context()
        _parse_requirements(ctx, {"kva": 20, "main_breaker": {"rating": -50}}, None)
        assert ctx.breaker_rating == 0

    def test_elcb_config_non_dict(self):
        """Non-dict elcb value gracefully falls back to rating=0."""
        ctx = _make_context()
        _parse_requirements(ctx, {"kva": 20, "elcb": "some string"}, None)
        assert ctx.elcb_rating == 0

    def test_ct_ratio_string_input(self):
        """CT ratio given as plain string is stored directly."""
        ctx = _make_context()
        _parse_requirements(ctx, {"kva": 100, "ct": "200/5A"}, None)
        assert ctx.ct_ratio == "200/5A"

    def test_ct_ratio_dict_input(self):
        """CT ratio given as dict with 'ratio' key is extracted."""
        ctx = _make_context()
        _parse_requirements(ctx, {"kva": 100, "ct": {"ratio": "200/5A"}}, None)
        assert ctx.ct_ratio == "200/5A"

    def test_incoming_cable_auto_single_phase(self):
        """Auto-determined incoming cable for single-phase has count=2 (L+N)."""
        ctx = _make_context()
        _parse_requirements(ctx, {
            "kva": 10,
            "supply_type": "single_phase",
            "main_breaker": {"rating": 40},
        }, None)
        assert isinstance(ctx.incoming_cable, dict)
        assert ctx.incoming_cable["count"] == 2

    def test_incoming_cable_auto_three_phase(self):
        """Auto-determined incoming cable for three-phase has count=4 (L1+L2+L3+N)."""
        ctx = _make_context()
        _parse_requirements(ctx, {
            "kva": 50,
            "supply_type": "three_phase",
            "main_breaker": {"rating": 80},
        }, None)
        assert isinstance(ctx.incoming_cable, dict)
        assert ctx.incoming_cable["count"] == 4

    def test_busbar_rating_minimum_100A(self):
        """Busbar rating enforces minimum 100A even for small breakers."""
        ctx = _make_context()
        _parse_requirements(ctx, {"kva": 10, "main_breaker": {"rating": 50}}, None)
        assert ctx.busbar_rating == 100

    def test_busbar_rating_inherits_breaker(self):
        """Busbar rating equals breaker rating when breaker > 100A."""
        ctx = _make_context()
        _parse_requirements(ctx, {"kva": 100, "main_breaker": {"rating": 250}}, None)
        assert ctx.busbar_rating == 250


# =============================================
# _place_incoming_supply
# =============================================

class TestPlaceIncomingSupply:
    """Incoming supply section placement."""

    def test_metered_supply_skips(self):
        """SP metering → no incoming supply components (meter board handles it)."""
        ctx = _make_context(metering="sp_meter", supply_type="three_phase")
        initial_count = len(ctx.result.components)
        _place_incoming_supply(ctx)
        assert len(ctx.result.components) == initial_count

    def test_landlord_supply_adds_components(self):
        ctx = _make_context(
            supply_source="landlord", metering=None,
            supply_type="three_phase", requirements={},
        )
        _place_incoming_supply(ctx)
        # Landlord: LABEL only (no AC symbol, no phase lines)
        assert len(ctx.result.components) >= 1
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "FLOW_ARROW_UP" not in symbols  # No AC symbol for landlord
        assert "LABEL" in symbols
        labels = [c.label for c in ctx.result.components if c.symbol_name == "LABEL"]
        assert "FROM LANDLORD RISER" in labels

    def test_y_advances(self):
        ctx = _make_context(
            supply_source="landlord", metering=None,
            supply_type="single_phase", requirements={},
        )
        initial_y = ctx.y
        _place_incoming_supply(ctx)
        assert ctx.y > initial_y

    def test_three_phase_sp_adds_phase_labels(self):
        """SP supply (non-landlord, non-metered) adds AC symbol + phase labels."""
        ctx = _make_context(
            supply_source="sp_powergrid", metering=None,
            supply_type="three_phase", requirements={},
        )
        _place_incoming_supply(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "FLOW_ARROW_UP" in symbols
        labels = [c.label for c in ctx.result.components if c.symbol_name == "LABEL"]
        for phase in ["L1", "L2", "L3", "N"]:
            assert phase in labels

    def test_landlord_no_phase_labels(self):
        """Landlord supply: no phase labels, just simple vertical line."""
        ctx = _make_context(
            supply_source="landlord", metering=None,
            supply_type="three_phase", requirements={},
        )
        _place_incoming_supply(ctx)
        labels = [c.label for c in ctx.result.components if c.symbol_name == "LABEL"]
        for phase in ["L1", "L2", "L3", "N"]:
            assert phase not in labels

    def test_cable_extension_label(self):
        ctx = _make_context(
            supply_source="landlord", metering=None,
            supply_type="single_phase", is_cable_extension=True,
            requirements={},
        )
        _place_incoming_supply(ctx)
        labels = [c.label for c in ctx.result.components if c.symbol_name == "LABEL"]
        assert any("POWER SUPPLY" in l.upper() for l in labels)


# =============================================
# _place_meter_board
# =============================================

class TestPlaceMeterBoard:
    """Meter board section placement."""

    def test_no_metering_skips(self):
        ctx = _make_context(metering=None)
        initial_count = len(ctx.result.components)
        _place_meter_board(ctx)
        assert len(ctx.result.components) == initial_count

    def test_sp_meter_adds_components(self):
        ctx = _make_context(
            metering="sp_meter", supply_type="three_phase",
            breaker_rating=63, breaker_poles="TPN", breaker_fault_kA=10,
            main_breaker_char="B", meter_poles="4P",
            supply_source="sp_powergrid", requirements={},
        )
        _place_meter_board(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "ISOLATOR" in symbols
        assert "KWH_METER" in symbols
        assert "CB_MCB" in symbols

    def test_dashed_box_created(self):
        ctx = _make_context(
            metering="sp_meter", supply_type="three_phase",
            breaker_rating=63, breaker_poles="TPN", breaker_fault_kA=10,
            main_breaker_char="B", meter_poles="4P",
            supply_source="sp_powergrid", requirements={},
        )
        _place_meter_board(ctx)
        # Dashed box = 4 dashed connections
        assert len(ctx.result.resolved_connections(style_filter={"dashed"})) == 4

    def test_y_advances(self):
        ctx = _make_context(
            metering="sp_meter", supply_type="three_phase",
            breaker_rating=63, breaker_poles="TPN", breaker_fault_kA=10,
            main_breaker_char="", meter_poles="4P",
            supply_source="sp_powergrid", requirements={},
        )
        initial_y = ctx.y
        _place_meter_board(ctx)
        assert ctx.y > initial_y

    def test_ct_meter_adds_ct(self):
        ctx = _make_context(
            metering="ct_meter", supply_type="three_phase",
            breaker_rating=200, breaker_poles="TPN", breaker_fault_kA=25,
            main_breaker_char="", meter_poles="4P",
            supply_source="sp_powergrid", ct_ratio="200/5A",
            requirements={},
        )
        _place_meter_board(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "CT" in symbols

    def test_earth_symbol_three_phase(self):
        ctx = _make_context(
            metering="sp_meter", supply_type="three_phase",
            breaker_rating=63, breaker_poles="TPN", breaker_fault_kA=10,
            main_breaker_char="", meter_poles="4P",
            supply_source="sp_powergrid", requirements={},
        )
        _place_meter_board(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "EARTH" in symbols


# =============================================
# _place_unit_isolator
# =============================================

class TestPlaceUnitIsolator:
    """Unit isolator placement."""

    def test_metered_supply_skips(self):
        ctx = _make_context(metering="sp_meter")
        initial_count = len(ctx.result.components)
        _place_unit_isolator(ctx)
        assert len(ctx.result.components) == initial_count

    def test_landlord_with_isolator_125a(self):
        """Landlord 125A: isolator shown."""
        ctx = _make_context(
            supply_source="landlord", metering=None,
            breaker_rating=125, meter_poles="4P",
            requirements={},
        )
        _place_unit_isolator(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "ISOLATOR" in symbols

    def test_landlord_with_isolator_63a(self):
        """Landlord 63A: isolator ALWAYS shown (regardless of rating)."""
        ctx = _make_context(
            supply_source="landlord", metering=None,
            breaker_rating=63, meter_poles="DP",
            requirements={},
        )
        _place_unit_isolator(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "ISOLATOR" in symbols
        # Landlord isolator labels placed as separate LABEL component (to the left)
        all_labels = [c.label for c in ctx.result.components if c.symbol_name == "LABEL"]
        assert any("LOCATED INSIDE UNIT" in l for l in all_labels)

    def test_cable_extension_skips_isolator(self):
        """Cable extension: no unit isolator even for landlord."""
        ctx = _make_context(
            supply_source="landlord", metering=None,
            breaker_rating=63, meter_poles="DP",
            requirements={},
        )
        ctx.is_cable_extension = True
        initial_count = len(ctx.result.components)
        _place_unit_isolator(ctx)
        assert len(ctx.result.components) == initial_count


# =============================================
# _place_main_breaker
# =============================================

class TestPlaceMainBreaker:
    """Main circuit breaker placement."""

    def test_mccb_placed(self):
        ctx = _make_context(
            breaker_type="MCCB", breaker_rating=63,
            breaker_poles="TPN", breaker_fault_kA=25,
            main_breaker_char="",
        )
        _place_main_breaker(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "CB_MCCB" in symbols

    def test_mcb_placed(self):
        ctx = _make_context(
            breaker_type="MCB", breaker_rating=40,
            breaker_poles="DP", breaker_fault_kA=10,
            main_breaker_char="B",
        )
        _place_main_breaker(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "CB_MCB" in symbols

    def test_y_advances(self):
        ctx = _make_context(
            breaker_type="MCCB", breaker_rating=63,
            breaker_poles="TPN", breaker_fault_kA=25,
            main_breaker_char="",
        )
        initial_y = ctx.y
        _place_main_breaker(ctx)
        assert ctx.y > initial_y

    def test_db_box_start_set(self):
        ctx = _make_context(
            breaker_type="MCCB", breaker_rating=63,
            breaker_poles="TPN", breaker_fault_kA=25,
            main_breaker_char="",
        )
        _place_main_breaker(ctx)
        assert ctx.db_box_start_y > 0

    def test_label_format(self):
        ctx = _make_context(
            breaker_type="MCCB", breaker_rating=63,
            breaker_poles="TPN", breaker_fault_kA=25,
            main_breaker_char="C",
        )
        _place_main_breaker(ctx)
        breaker = [c for c in ctx.result.components if c.symbol_name == "CB_MCCB"][0]
        assert "63A" in breaker.label
        assert "TPN" in breaker.label
        assert "TYPE C" in breaker.label  # Single-line format: "63A TPN MCCB (TYPE C 25KA)"
        assert breaker.rating == ""  # No separate rating line

    def test_symbols_used_tracked(self):
        ctx = _make_context(
            breaker_type="MCCB", breaker_rating=63,
            breaker_poles="TPN", breaker_fault_kA=25,
            main_breaker_char="",
        )
        _place_main_breaker(ctx)
        assert "MCCB" in ctx.result.symbols_used

    def test_connections_added(self):
        ctx = _make_context(
            breaker_type="MCCB", breaker_rating=63,
            breaker_poles="TPN", breaker_fault_kA=25,
            main_breaker_char="",
        )
        initial_conns = len(ctx.result.resolved_connections(style_filter={"normal"}))
        _place_main_breaker(ctx)
        assert len(ctx.result.resolved_connections(style_filter={"normal"})) > initial_conns


# =============================================
# _place_elcb
# =============================================

class TestPlaceElcb:
    """ELCB/RCCB inline placement."""

    def test_no_elcb_skips(self):
        ctx = _make_context(elcb_rating=0)
        initial_count = len(ctx.result.components)
        _place_elcb(ctx)
        assert len(ctx.result.components) == initial_count

    def test_elcb_placed(self):
        ctx = _make_context(
            elcb_rating=63, elcb_ma=30,
            elcb_type_str="ELCB", elcb_config={},
            supply_type="single_phase",
        )
        _place_elcb(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "CB_ELCB" in symbols

    def test_rccb_placed(self):
        ctx = _make_context(
            elcb_rating=63, elcb_ma=100,
            elcb_type_str="RCCB", elcb_config={},
            supply_type="three_phase",
        )
        _place_elcb(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "CB_RCCB" in symbols

    def test_y_advances(self):
        ctx = _make_context(
            elcb_rating=63, elcb_ma=30,
            elcb_type_str="ELCB", elcb_config={},
            supply_type="single_phase",
        )
        initial_y = ctx.y
        _place_elcb(ctx)
        assert ctx.y > initial_y


# =============================================
# _place_main_busbar
# =============================================

class TestPlaceMainBusbar:
    """Main busbar and DB info placement."""

    def test_busbar_placed(self):
        ctx = _make_context(
            supply_type="three_phase", voltage=400, kva=45,
            breaker_rating=63, elcb_rating=0, busbar_rating=100,
            sub_circuits=[{"name": "LIGHTS"}],
        )
        _place_main_busbar(ctx)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "BUSBAR" in symbols

    def test_busbar_y_set(self):
        ctx = _make_context(
            supply_type="three_phase", voltage=400,
            breaker_rating=63, busbar_rating=100,
            sub_circuits=[{"name": "A"}, {"name": "B"}],
        )
        _place_main_busbar(ctx)
        assert ctx.result.busbar_y == ctx.y

    def test_busbar_extents_set(self):
        ctx = _make_context(
            supply_type="three_phase", voltage=400,
            breaker_rating=63, busbar_rating=100,
            sub_circuits=[{"name": f"C{i}"} for i in range(10)],
        )
        _place_main_busbar(ctx)
        assert ctx.result.busbar_start_x < ctx.result.busbar_end_x

    def test_db_info_stored(self):
        ctx = _make_context(
            supply_type="three_phase", voltage=400, kva=45,
            breaker_rating=63, busbar_rating=100,
            sub_circuits=[],
        )
        _place_main_busbar(ctx)
        assert "63A" in ctx.db_info_label
        assert "KVA" in ctx.db_info_text.upper()


# =============================================
# _place_sub_circuits_rows
# =============================================

class TestPlaceSubCircuitsRows:
    """Sub-circuit row placement."""

    def _setup_ctx(self, num_circuits=3, supply_type="three_phase"):
        ctx = _make_context(
            supply_type=supply_type,
            sub_circuits=[
                {"name": f"CIRCUIT {i}", "breaker_type": "MCB", "breaker_rating": 20}
                for i in range(num_circuits)
            ],
        )
        # Set busbar (normally done by _place_main_busbar)
        ctx.result.busbar_y = ctx.y
        ctx.result.busbar_start_x = ctx.config.min_x + 20
        ctx.result.busbar_end_x = ctx.config.max_x - 20
        return ctx

    def test_components_added(self):
        ctx = self._setup_ctx(3)
        _place_sub_circuits_rows(ctx)
        # Each circuit: CB_MCB + CIRCUIT_ID_BOX + LABEL = 3 components each
        breakers = [c for c in ctx.result.components if c.symbol_name.startswith("CB_")]
        assert len(breakers) >= 3

    def test_circuit_ids_assigned(self):
        ctx = self._setup_ctx(3, supply_type="three_phase")
        _place_sub_circuits_rows(ctx)
        id_boxes = [c for c in ctx.result.components if c.symbol_name == "CIRCUIT_ID_BOX"]
        # 3 circuits → 3 SPARE padded to 6 → 6 circuit IDs
        assert len(id_boxes) >= 3

    def test_connections_added(self):
        ctx = self._setup_ctx(3)
        initial_conns = len(ctx.result.resolved_connections(style_filter={"normal"}))
        _place_sub_circuits_rows(ctx)
        # Each circuit adds at least 2 connections (busbar→breaker, breaker→tail)
        assert len(ctx.result.resolved_connections(style_filter={"normal"})) >= initial_conns + 6

    def test_busbar_y_per_row_populated(self):
        ctx = self._setup_ctx(3)
        _place_sub_circuits_rows(ctx)
        assert len(ctx.result.busbar_y_per_row) >= 1

    def test_single_phase(self):
        ctx = self._setup_ctx(3, supply_type="single_phase")
        _place_sub_circuits_rows(ctx)
        breakers = [c for c in ctx.result.components if c.symbol_name.startswith("CB_")]
        assert len(breakers) == 3  # No spare padding for single phase

    def test_spare_padding_three_phase(self):
        ctx = self._setup_ctx(4, supply_type="three_phase")
        _place_sub_circuits_rows(ctx)
        # 3-phase triplet padding rounds 4 circuits up to 6 (next multiple of 3)
        breakers = [c for c in ctx.result.components if c.symbol_name.startswith("CB_")]
        assert len(breakers) == 6


# =============================================
# _place_db_box
# =============================================

class TestPlaceDbBox:
    """DB box dashed rectangle placement."""

    def _setup_ctx(self):
        ctx = _make_context(
            db_box_start_y=150, db_info_label="63A DB",
            db_info_text="APPROVED LOAD: 45 kVA",
            db_location_text="(LOCATED INSIDE UNIT #01-36)",
        )
        ctx.result.busbar_start_x = 100
        ctx.result.busbar_end_x = 300
        return ctx

    def test_dashed_box_created(self):
        ctx = self._setup_ctx()
        _place_db_box(ctx, busbar_y_row=200)
        # 4 dashed connections for box sides
        assert len(ctx.result.resolved_connections(style_filter={"dashed"})) == 4

    def test_db_box_indices_stored(self):
        ctx = self._setup_ctx()
        _place_db_box(ctx, busbar_y_row=200)
        assert len(ctx.result.db_box_dashed_indices) == 4

    def test_db_info_box_placed(self):
        ctx = self._setup_ctx()
        _place_db_box(ctx, busbar_y_row=200)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "DB_INFO_BOX" in symbols

    def test_location_text_placed(self):
        ctx = self._setup_ctx()
        _place_db_box(ctx, busbar_y_row=200)
        labels = [c.label for c in ctx.result.components if c.symbol_name == "LABEL"]
        assert any("LOCATED" in l for l in labels)

    def test_returns_db_box_right(self):
        ctx = self._setup_ctx()
        result = _place_db_box(ctx, busbar_y_row=200)
        assert isinstance(result, (int, float))
        assert result > ctx.result.busbar_end_x  # Right of busbar + margin


# =============================================
# _place_earth_bar
# =============================================

class TestPlaceEarthBar:
    """Earth bar placement."""

    def test_earth_placed(self):
        ctx = _make_context(requirements={})
        ctx.result.busbar_y = 200
        _place_earth_bar(ctx, db_box_right=300)
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "EARTH" in symbols

    def test_conductor_label_with_incoming_cable(self):
        ctx = _make_context(requirements={
            "incoming_cable": {"size_mm2": "16"},
        })
        ctx.result.busbar_y = 200
        _place_earth_bar(ctx, db_box_right=300)
        labels = [c.label for c in ctx.result.components if c.symbol_name == "LABEL"]
        # Should have earth conductor label (e.g., "1 x 10sqmm CU/GRN-YEL")
        assert any("sqmm" in l for l in labels)

    def test_connection_to_db_box(self):
        ctx = _make_context(requirements={})
        ctx.result.busbar_y = 200
        _place_earth_bar(ctx, db_box_right=300)
        # Should add horizontal connection from DB box right wall
        assert len(ctx.result.resolved_connections(style_filter={"normal"})) >= 1


# =============================================
# Full pipeline: sequential placement
# =============================================

class TestFullPlacementPipeline:
    """Integration test: all placement sections in sequence."""

    def test_three_phase_metered(self):
        """Standard 63A 3-phase metered supply."""
        ctx = _make_context()
        requirements = {
            "supply_type": "three_phase",
            "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN"},
            "sub_circuits": [
                {"name": "LIGHTS 1", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "LIGHTS 2", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "LIGHTS 3", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "13A SOCKET 1", "breaker_type": "MCB", "breaker_rating": 20},
                {"name": "13A SOCKET 2", "breaker_type": "MCB", "breaker_rating": 20},
                {"name": "13A SOCKET 3", "breaker_type": "MCB", "breaker_rating": 20},
            ],
        }
        _parse_requirements(ctx, requirements, None)
        _place_incoming_supply(ctx)
        _place_meter_board(ctx)
        _place_unit_isolator(ctx)
        _place_main_breaker(ctx)
        _place_elcb(ctx)
        _place_main_busbar(ctx)
        busbar_y = _place_sub_circuits_rows(ctx)
        db_right = _place_db_box(ctx, busbar_y)
        _place_earth_bar(ctx, db_right)

        # Structural checks
        assert len(ctx.result.components) > 0
        assert len(ctx.result.resolved_connections(style_filter={"normal"})) > 0
        assert ctx.result.busbar_y > 0
        assert ctx.result.busbar_start_x < ctx.result.busbar_end_x
        assert "MCCB" in ctx.result.symbols_used
        assert "MCB" in ctx.result.symbols_used

    def test_single_phase_landlord(self):
        """40A single-phase landlord supply."""
        ctx = _make_context()
        requirements = {
            "supply_type": "single_phase",
            "supply_source": "landlord",
            "main_breaker": {"type": "MCB", "rating": 40},
            "sub_circuits": [
                {"name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10},
                {"name": "13A SOCKET", "breaker_type": "MCB", "breaker_rating": 20},
            ],
        }
        _parse_requirements(ctx, requirements, None)
        _place_incoming_supply(ctx)
        _place_meter_board(ctx)
        _place_unit_isolator(ctx)
        _place_main_breaker(ctx)
        _place_elcb(ctx)
        _place_main_busbar(ctx)
        busbar_y = _place_sub_circuits_rows(ctx)
        db_right = _place_db_box(ctx, busbar_y)
        _place_earth_bar(ctx, db_right)

        # Landlord: no FLOW_ARROW_UP (simple vertical line), has isolator + MCB
        symbols = [c.symbol_name for c in ctx.result.components]
        assert "FLOW_ARROW_UP" not in symbols  # Landlord: no AC symbol
        assert "ISOLATOR" in symbols  # Landlord: always has unit isolator
        assert "CB_MCB" in symbols

    def test_with_elcb(self):
        """Supply with ELCB inline."""
        ctx = _make_context()
        requirements = {
            "supply_type": "single_phase",
            "main_breaker": {"type": "MCB", "rating": 40},
            "elcb": {"rating": 40, "sensitivity_ma": 30, "type": "ELCB"},
            "sub_circuits": [
                {"name": "LIGHTS", "breaker_type": "MCB", "breaker_rating": 10},
            ],
        }
        _parse_requirements(ctx, requirements, None)
        _place_incoming_supply(ctx)
        _place_meter_board(ctx)
        _place_unit_isolator(ctx)
        _place_main_breaker(ctx)
        _place_elcb(ctx)
        _place_main_busbar(ctx)
        busbar_y = _place_sub_circuits_rows(ctx)
        _place_db_box(ctx, busbar_y)

        symbols = [c.symbol_name for c in ctx.result.components]
        assert "CB_ELCB" in symbols
        assert "CB_MCB" in symbols
