"""
SLD Layout meter board section — horizontal meter board placement.

Extracted from sections.py. Contains:
- _MeterBoardGeom (NamedTuple)
- _compute_meter_board_geom()
- _place_meter_board_symbols()
- _add_incoming_supply_line()
- _add_outgoing_cable_tick()
- _add_meter_board_box_and_earth()
- _place_meter_board()
"""

from __future__ import annotations

import logging
from typing import NamedTuple

from app.sld.layout.models import (
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    _LayoutContext,
    format_cable_spec,
)
from app.sld.locale import SG_LOCALE

logger = logging.getLogger(__name__)


class _MeterBoardGeom(NamedTuple):
    """Computed geometry for horizontal meter board layout."""
    # Component centers (horizontal)
    iso_cx: float
    kwh_cx: float
    mcb_cx: float
    mb_center_y: float
    # Horizontal extents (rotated 90°: symbol height → horizontal)
    iso_h_extent: float
    kwh_h_extent: float
    mcb_h_extent: float
    # Pin X positions
    iso_left_x: float
    iso_right_x: float
    mcb_left_x: float
    mcb_right_x: float
    # Box boundaries
    mb_box_left: float
    mb_box_right: float
    mb_box_top: float
    mb_box_bottom: float
    # Label Y positions
    kwh_label_y: float
    mb_label_y: float
    # Stub length
    stub: float


def _compute_meter_board_geom(config: LayoutConfig, cx: float, y: float) -> _MeterBoardGeom:
    """Compute all meter board geometry from config and position (pure function).

    Returns a _MeterBoardGeom with all coordinates needed for placement.
    """
    from app.sld.catalog import get_catalog
    _cat = get_catalog()

    comp_spacing = config.meter_board_comp_spacing
    _stub = _cat.get("ISOLATOR").stub
    _mb_inset = config.meter_board_inset

    # Horizontal extents from catalog (symbol height → h_extent when rotated 90°)
    iso_h_extent = _cat.get("ISOLATOR").effective_h_extent
    kwh_h_extent = _cat.get("KWH_METER").effective_h_extent
    mcb_h_extent = _cat.get("MCB").effective_h_extent

    # Vertical half-extents (symbol.width/2 → v_half when rotated 90°)
    iso_v_half = _cat.get("ISOLATOR").width / 2
    kwh_v_half = _cat.get("KWH_METER").height * 0.6 / 2  # kwh_rect_h = height * 0.6
    mcb_v_half = _cat.get("MCB").width / 2
    max_v_half = max(iso_v_half, kwh_v_half, mcb_v_half)

    # Text sizes (must match generator.py)
    _comp_label_ch = 1.6
    _comp_label_lsp = 1.4
    _comp_label_lines = 2
    _comp_label_h = _comp_label_ch * _comp_label_lsp * _comp_label_lines
    _anno_label_ch = 2.8

    # Component centers
    iso_cx = cx + iso_h_extent / 2 + _stub + _mb_inset
    kwh_cx = iso_cx + comp_spacing
    mcb_cx = iso_cx + 2 * comp_spacing
    mb_center_y = y + 8

    # Pin positions — connection lines reach directly to component pin (body edge).
    # DXF blocks have pins at body edge; procedural symbols draw stubs that
    # extend outward from body edge, overlapping harmlessly with connection lines.
    iso_left_x = iso_cx - iso_h_extent / 2
    iso_right_x = iso_cx + iso_h_extent / 2
    mcb_left_x = mcb_cx - mcb_h_extent / 2
    mcb_right_x = mcb_cx + mcb_h_extent / 2

    # Vertical bands — above center
    _gap = config.meter_board_gap
    kwh_label_y = mb_center_y + max_v_half + _gap + _anno_label_ch
    mb_box_top = kwh_label_y + _gap

    # Vertical bands — below center
    comp_label_y = mb_center_y - max_v_half - _gap
    comp_label_bot = comp_label_y - _comp_label_h
    mb_label_y = comp_label_bot - 2
    mb_label_bot = mb_label_y - _anno_label_ch
    mb_box_bottom = mb_label_bot - 1

    # Box horizontal extent
    iso_body_left = iso_cx - iso_h_extent / 2
    mb_box_left = iso_body_left - 4
    mb_box_right = mcb_right_x + 4

    return _MeterBoardGeom(
        iso_cx=iso_cx, kwh_cx=kwh_cx, mcb_cx=mcb_cx,
        mb_center_y=mb_center_y,
        iso_h_extent=iso_h_extent, kwh_h_extent=kwh_h_extent, mcb_h_extent=mcb_h_extent,
        iso_left_x=iso_left_x, iso_right_x=iso_right_x,
        mcb_left_x=mcb_left_x, mcb_right_x=mcb_right_x,
        mb_box_left=mb_box_left, mb_box_right=mb_box_right,
        mb_box_top=mb_box_top, mb_box_bottom=mb_box_bottom,
        kwh_label_y=kwh_label_y, mb_label_y=mb_label_y,
        stub=_stub,
    )


def _place_meter_board_symbols(
    ctx: _LayoutContext, g: _MeterBoardGeom,
) -> None:
    """Place ISO, CT, KWH, MCB symbols and inter-component wiring."""
    result = ctx.result

    # Spine → ISO routing
    if g.iso_left_x > ctx.cx:
        result.connections.append(((ctx.cx, g.mb_center_y), (g.iso_left_x, g.mb_center_y)))

    # ISOLATOR (left) — Reference uses procedural isolator symbol (2 circles + diagonal)
    result.components.append(PlacedComponent(
        symbol_name="ISOLATOR",
        x=g.iso_cx - g.iso_h_extent / 2,
        y=g.mb_center_y,
        label=f"{ctx.breaker_rating}A {ctx.meter_poles}",
        rating=SG_LOCALE.meter_board.isolator,
        rotation=90.0,
    ))
    result.symbols_used.add("ISOLATOR")

    # ISO → KWH wiring (connection reaches KWH left pin / body edge)
    kwh_left_x = g.kwh_cx - g.kwh_h_extent / 2
    result.connections.append(((g.iso_right_x, g.mb_center_y), (kwh_left_x, g.mb_center_y)))

    # CT (between ISO and KWH) — ct_meter + non-landlord only
    if ctx.metering == "ct_meter" and ctx.supply_source != "landlord":
        ct_mid_x = (g.iso_cx + g.kwh_cx) / 2
        from app.sld.catalog import get_catalog as _get_cat_ct
        ct_r = _get_cat_ct().get("CT").width / 2
        ct_label = f"{ctx.ct_ratio} CT" if ctx.ct_ratio else SG_LOCALE.meter_board.ct_by_sp
        result.components.append(PlacedComponent(
            symbol_name="CT", x=ct_mid_x - ct_r, y=g.mb_center_y - ct_r, label=ct_label,
        ))
        result.symbols_used.add("CT")

    # KWH METER — x = left edge (same pattern as ISO/MCB for pin="left" alignment)
    result.components.append(PlacedComponent(
        symbol_name="KWH_METER", x=g.kwh_cx - g.kwh_h_extent / 2, y=g.mb_center_y, rotation=90.0,
    ))
    result.symbols_used.add("KWH_METER")

    # KWH label (above symbols, inside box)
    _kwh_label = ctx.requirements.get("kwh_label")
    if not _kwh_label:
        if ctx.supply_source == "landlord":
            _kwh_label = SG_LOCALE.meter_board.kwh_meter_pg
        else:
            _kwh_label = SG_LOCALE.meter_board.kwh_meter_by_sp
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=(g.iso_cx + g.mcb_cx) / 2 - 10,
        y=g.kwh_label_y,
        label=_kwh_label,
    ))

    # KWH → MCB wiring (connection reaches KWH right pin / body edge)
    kwh_right_x = g.kwh_cx + g.kwh_h_extent / 2
    result.connections.append(((kwh_right_x, g.mb_center_y), (g.mcb_left_x, g.mb_center_y)))

    # MCB (right)
    _mcb_poles = ctx.breaker_poles
    _mcb_char = ctx.main_breaker_char or "B"
    _mcb_ka = ctx.breaker_fault_kA or 10
    result.components.append(PlacedComponent(
        symbol_name="CB_MCB",
        x=g.mcb_cx - g.mcb_h_extent / 2,
        y=g.mb_center_y,
        label=f"{ctx.breaker_rating}A {_mcb_poles} MCB",
        rating=f"TYPE {_mcb_char} {_mcb_ka}kA",
        rotation=90.0,
    ))
    result.symbols_used.add("MCB")


def _add_incoming_supply_line(ctx: _LayoutContext, g: _MeterBoardGeom) -> None:
    """Add horizontal supply entry line, incoming label, and cable tick annotation."""
    result = ctx.result

    # Supply entry line from MCB rightward
    supply_end_x = g.mcb_right_x + 20
    result.connections.append(((g.mcb_right_x, g.mb_center_y), (supply_end_x, g.mb_center_y)))

    # Incoming label — known supply_source types always use locale labels
    if ctx.is_cable_extension:
        supply_label = SG_LOCALE.incoming.from_power_supply
    elif ctx.supply_source == "building_riser":
        supply_label = SG_LOCALE.incoming.from_building_riser
    elif ctx.supply_source == "landlord":
        if ctx.requirements.get("supply_label_type") == "supply":
            supply_label = SG_LOCALE.incoming.from_landlord_supply
        else:
            supply_label = SG_LOCALE.incoming.from_landlord
    elif ctx.requirements.get("incoming_label"):
        supply_label = ctx.requirements["incoming_label"]
    else:
        supply_label = SG_LOCALE.incoming.incoming_hdb
    result.components.append(PlacedComponent(
        symbol_name="LABEL", x=supply_end_x + 3, y=g.mb_center_y + 3, label=supply_label,
    ))

    # Incoming cable tick + leader line
    # For landlord supply, the outgoing cable tick (DB→meter board) already labels
    # the same cable — skip here to avoid duplication (ref DXF has only 1 cable label).
    # Exception: if outgoing_cable differs from incoming_cable, show both.
    if ctx.supply_source == "landlord" and not ctx.requirements.get("outgoing_cable"):
        return
    cable_text = format_cable_spec(ctx.incoming_cable, multiline=True)
    if cable_text:
        tick_x = g.mcb_right_x + 10
        tick_size = 1.5
        result.thick_connections.append((
            (tick_x - tick_size, g.mb_center_y - tick_size),
            (tick_x + tick_size, g.mb_center_y + tick_size),
        ))
        leader_bottom_y = g.mb_center_y - 10
        result.connections.append(((tick_x, g.mb_center_y), (tick_x, leader_bottom_y)))
        shelf_len = 3
        result.leader_connections.append(((tick_x, leader_bottom_y), (tick_x + shelf_len, leader_bottom_y)))
        _label_ch = 2.8
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=tick_x + shelf_len + 1,
            y=leader_bottom_y + _label_ch * 0.5,
            label=cable_text,
        ))


def _add_outgoing_cable_tick(
    ctx: _LayoutContext, g: _MeterBoardGeom, y_exit: float,
) -> None:
    """Add outgoing cable tick mark and annotation on vertical exit line."""
    result = ctx.result
    cx = ctx.cx
    # Use outgoing_cable from requirements if specified; fall back to incoming_cable.
    _out_cable = ctx.requirements.get("outgoing_cable", "")
    _cable_source = _out_cable if _out_cable else ctx.incoming_cable
    outgoing_cable_text = format_cable_spec(_cable_source, multiline=True)
    if not outgoing_cable_text:
        return

    tick_y = (g.mb_box_top + y_exit) / 2
    tick_size = 1.25
    result.thick_connections.append((
        (cx - tick_size, tick_y - tick_size),
        (cx + tick_size, tick_y + tick_size),
    ))
    # Leader line: from tick on spine → left, then label at the end.
    # Use a generous leader length to keep the label clear of the spine line.
    _leader_len = 8
    result.leader_connections.append(((cx, tick_y), (cx - _leader_len, tick_y)))

    _label_ch = 2.8
    _char_w = _label_ch * 0.6
    _lines = outgoing_cable_text.split("\\P")
    _max_line_len = max(len(ln) for ln in _lines) if _lines else 20
    _text_width = _max_line_len * _char_w
    # Label right edge aligns with leader left end (cx - _leader_len)
    _text_x = cx - _leader_len - _text_width
    result.components.append(PlacedComponent(
        symbol_name="LABEL", x=_text_x, y=tick_y + _label_ch * 0.5, label=outgoing_cable_text,
    ))


def _add_meter_board_box_and_earth(ctx: _LayoutContext, g: _MeterBoardGeom) -> None:
    """Draw dashed box, labels, and earth symbol (3-phase only)."""
    result = ctx.result
    config = ctx.config

    # Dashed box
    result.dashed_connections.append(((g.mb_box_left, g.mb_box_bottom), (g.mb_box_right, g.mb_box_bottom)))
    result.dashed_connections.append(((g.mb_box_left, g.mb_box_top), (g.mb_box_right, g.mb_box_top)))
    result.dashed_connections.append(((g.mb_box_left, g.mb_box_bottom), (g.mb_box_left, g.mb_box_top)))
    result.dashed_connections.append(((g.mb_box_right, g.mb_box_bottom), (g.mb_box_right, g.mb_box_top)))

    # "METER BOARD" label inside box
    result.components.append(PlacedComponent(
        symbol_name="LABEL", x=g.mb_box_left + 1, y=g.mb_label_y,
        label=SG_LOCALE.meter_board.meter_board,
    ))
    # Meter board location text (e.g. "LOCATED AT METER COMPARTMENT")
    # Only shown when explicitly provided via requirements
    if ctx.meter_board_location_text:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=(g.mb_box_left + g.mb_box_right) / 2 - 18,
            y=g.mb_box_bottom - 3,
            label=ctx.meter_board_location_text,
        ))

    # Earth symbol — 3-phase only
    if ctx.supply_type != "single_phase":
        from app.sld.catalog import get_catalog as _gc_mb_earth
        _earth_def = _gc_mb_earth().get("EARTH")
        ew, eh = _earth_def.width, _earth_def.height
        earth_cx = g.mb_box_right + 4
        earth_x = earth_cx - ew / 2
        earth_top_pin_y = g.mb_box_bottom - config.earth_x_from_db / 2
        earth_y = earth_top_pin_y - eh
        junction_y = g.mb_box_bottom + 3

        result.connections.append(((g.mb_box_right, junction_y), (earth_cx, junction_y)))
        result.connections.append(((earth_cx, junction_y), (earth_cx, earth_top_pin_y)))
        result.junction_dots.append((g.mb_box_right, junction_y))
        result.components.append(PlacedComponent(
            symbol_name="EARTH", x=earth_x, y=earth_y, label="E",
        ))
        result.symbols_used.add("EARTH")


def _place_meter_board(ctx: _LayoutContext) -> None:
    """Place meter board section HORIZONTALLY: [ISO]--[KWH]--[MCB] on same Y line.

    Horizontal layout matching professional LEW drawings.
    Delegates to sub-functions for geometry computation, symbol placement,
    cable annotations, and box/earth drawing.
    """
    y = ctx.y

    if ctx.metering:
        ctx.result.sections_rendered["meter_board"] = True
        g = _compute_meter_board_geom(ctx.config, ctx.cx, y)

        _place_meter_board_symbols(ctx, g)
        _add_incoming_supply_line(ctx, g)

        # Vertical exit line from spine
        _out_cable_mb = ctx.requirements.get("outgoing_cable", "") or ctx.incoming_cable
        outgoing_cable_text = format_cable_spec(_out_cable_mb, multiline=True)
        y_exit = g.mb_box_top + (16 if outgoing_cable_text else 8)
        ctx.result.connections.append(((ctx.cx, g.mb_center_y), (ctx.cx, y_exit)))

        _add_outgoing_cable_tick(ctx, g, y_exit)
        _add_meter_board_box_and_earth(ctx, g)

        y = y_exit

    ctx.y = y
