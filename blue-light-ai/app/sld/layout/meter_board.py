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
from app.sld.layout.section_base import connect_points, connect_ports, connect_port_to_point
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
    # KWH label is 2 lines (e.g., "PG\nKWH METER"), needs 2× annotation height
    _gap = config.meter_board_gap
    _kwh_label_lines = 2
    kwh_label_y = mb_center_y + max_v_half + _gap + _anno_label_ch * _kwh_label_lines
    mb_box_top = kwh_label_y + _gap

    # Vertical bands — below center
    comp_label_y = mb_center_y - max_v_half - _gap
    comp_label_bot = comp_label_y - _comp_label_h
    mb_label_y = comp_label_bot - 2
    mb_label_bot = mb_label_y - _anno_label_ch
    mb_box_bottom = mb_label_bot - 1

    # Box horizontal extent — must contain MCB label text below symbol.
    # MCB label (e.g., "63A TPN MCB\nTYPE B 10kA") is rendered below the symbol.
    # Estimate: longest label line ~14 chars × 1.6mm = ~22mm wide, centered on mcb_cx.
    _mcb_label_half_w = 14 * _comp_label_ch * 0.6  # ~13mm
    _mcb_label_right = mcb_cx + _mcb_label_half_w
    iso_body_left = iso_cx - iso_h_extent / 2
    mb_box_left = iso_body_left - 4
    mb_box_right = max(mcb_right_x + 4, _mcb_label_right + 2)

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

    # ISOLATOR (left) — Reference uses procedural isolator symbol (2 circles + diagonal)
    _iso_x = g.iso_cx - g.iso_h_extent / 2
    _iso_id = ctx.next_id("mb_isolator")
    _iso_ports = {"left": (g.iso_left_x, g.mb_center_y), "right": (g.iso_right_x, g.mb_center_y)}
    _iso_comp = PlacedComponent(
        symbol_name="ISOLATOR",
        x=_iso_x, y=g.mb_center_y,
        label=f"{ctx.breaker_rating}A {ctx.meter_poles}",
        rating=SG_LOCALE.meter_board.isolator,
        rotation=90.0,
        id=_iso_id, ports=_iso_ports,
    )
    result.components.append(_iso_comp)
    result.symbols_used.add("ISOLATOR")

    # Spine → ISO routing (port-based)
    if g.iso_left_x > ctx.cx:
        connect_port_to_point(result, _iso_comp, "left", (ctx.cx, g.mb_center_y), port_is_start=False)

    # ISO → KWH wiring (connection reaches KWH left pin / body edge)
    kwh_left_x = g.kwh_cx - g.kwh_h_extent / 2

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
    _kwh_x = g.kwh_cx - g.kwh_h_extent / 2
    kwh_right_x_pin = g.kwh_cx + g.kwh_h_extent / 2
    _kwh_id = ctx.next_id("mb_kwh")
    _kwh_ports = {"left": (kwh_left_x, g.mb_center_y), "right": (kwh_right_x_pin, g.mb_center_y)}
    _kwh_comp = PlacedComponent(
        symbol_name="KWH_METER", x=_kwh_x, y=g.mb_center_y, rotation=90.0,
        id=_kwh_id, ports=_kwh_ports,
    )
    result.components.append(_kwh_comp)
    result.symbols_used.add("KWH_METER")

    # ISO right → KWH left (port-based)
    connect_ports(result, _iso_comp, "right", _kwh_comp, "left")

    # KWH label (above symbols, inside box)
    _kwh_label = ctx.requirements.get("kwh_label")
    if not _kwh_label:
        if ctx.supply_source == "landlord":
            _kwh_label = SG_LOCALE.meter_board.kwh_meter_pg
        else:
            _kwh_label = SG_LOCALE.meter_board.kwh_meter_by_sp
    # KWH label: 2 lines (e.g., "PG" + "KWH METER"), each centered above KWH symbol.
    from app.sld.layout.font_util import measure_text_width as _mtw
    _kwh_lines = _kwh_label.split("\\P")
    _anno_ch = 2.8
    _line_gap = 0.5

    for li, line_text in enumerate(_kwh_lines):
        _tw = _mtw(line_text, cap_height=_anno_ch)
        _lx = g.kwh_cx - _tw / 2
        _ly = g.kwh_label_y - li * (_anno_ch + _line_gap)
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=_lx,
            y=_ly,
            label=line_text,
        ))

    # MCB (right)
    _mcb_poles = ctx.breaker_poles
    _mcb_char = ctx.main_breaker_char or "B"
    _mcb_ka = ctx.breaker_fault_kA or 10
    _mcb_x = g.mcb_cx - g.mcb_h_extent / 2
    _mcb_id = ctx.next_id("mb_mcb")
    _mcb_ports = {"left": (g.mcb_left_x, g.mb_center_y), "right": (g.mcb_right_x, g.mb_center_y)}
    _mcb_comp = PlacedComponent(
        symbol_name="CB_MCB",
        x=_mcb_x, y=g.mb_center_y,
        label=f"{ctx.breaker_rating}A {_mcb_poles} MCB",
        rating=f"TYPE {_mcb_char} {_mcb_ka}kA",
        rotation=90.0,
        id=_mcb_id, ports=_mcb_ports,
    )
    result.components.append(_mcb_comp)
    result.symbols_used.add("MCB")

    # KWH right → MCB left (port-based)
    connect_ports(result, _kwh_comp, "right", _mcb_comp, "left")


def _add_incoming_supply_line(ctx: _LayoutContext, g: _MeterBoardGeom) -> None:
    """Add horizontal supply entry line, incoming label, and cable tick annotation."""
    result = ctx.result

    # Supply entry line from MCB rightward — must start past the box right edge
    # so tick marks and labels are outside the meter board box.
    _line_start_x = g.mcb_right_x
    supply_end_x = max(g.mb_box_right + 15, g.mcb_right_x + 20)
    # MCB right port → supply endpoint
    # _mcb_comp is in the caller scope (captured from _place_meter_board_symbols)
    _mcb_comp_ref = next((c for c in reversed(result.components) if c.symbol_name == "CB_MCB" and c.ports), None)
    if _mcb_comp_ref:
        connect_port_to_point(result, _mcb_comp_ref, "right", (supply_end_x, g.mb_center_y))
    else:
        connect_points(result, (_line_start_x, g.mb_center_y), (supply_end_x, g.mb_center_y))

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
        # Tick must be outside meter board box
        tick_x = max(g.mb_box_right + 5, g.mcb_right_x + 10)
        tick_size = 1.5
        connect_points(result,
            (tick_x - tick_size, g.mb_center_y - tick_size),
            (tick_x + tick_size, g.mb_center_y + tick_size), style="thick")
        leader_bottom_y = g.mb_center_y - 10
        connect_points(result, (tick_x, g.mb_center_y), (tick_x, leader_bottom_y))
        shelf_len = 3
        connect_points(result, (tick_x, leader_bottom_y), (tick_x + shelf_len, leader_bottom_y), style="leader")
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
    connect_points(result,
        (cx - tick_size, tick_y - tick_size),
        (cx + tick_size, tick_y + tick_size), style="thick")
    # Leader line: from tick on spine → left.
    # Text is NOT placed here — it's deferred to Step D (place_labels)
    # where all geometry is finalized and collision-free placement is possible.
    _leader_len = 12
    connect_points(result, (cx, tick_y), (cx - _leader_len, tick_y), style="leader")

    # Register deferred cable label for Step D placement
    result.deferred_cable_labels.append({
        "text": outgoing_cable_text,
        "tick_x": cx,
        "tick_y": tick_y,
        "side": "left",
        "leader_len": _leader_len,
        "char_height": 2.8,
        "source": "outgoing_cable",
    })


def _add_meter_board_box_and_earth(ctx: _LayoutContext, g: _MeterBoardGeom) -> None:
    """Draw dashed box, labels, and earth symbol (3-phase only)."""
    result = ctx.result
    config = ctx.config

    # Dashed box
    connect_points(result, (g.mb_box_left, g.mb_box_bottom), (g.mb_box_right, g.mb_box_bottom), style="dashed")
    connect_points(result, (g.mb_box_left, g.mb_box_top), (g.mb_box_right, g.mb_box_top), style="dashed")
    connect_points(result, (g.mb_box_left, g.mb_box_bottom), (g.mb_box_left, g.mb_box_top), style="dashed")
    connect_points(result, (g.mb_box_right, g.mb_box_bottom), (g.mb_box_right, g.mb_box_top), style="dashed")

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

        _earth_id = ctx.next_id("mb_earth")
        _earth_ports = {"top": (earth_cx, earth_top_pin_y)}
        _earth_comp = PlacedComponent(
            symbol_name="EARTH", x=earth_x, y=earth_y, label="E",
            id=_earth_id, ports=_earth_ports,
        )
        result.components.append(_earth_comp)
        result.symbols_used.add("EARTH")
        # Junction → earth top (port-based)
        connect_points(result, (g.mb_box_right, junction_y), (earth_cx, junction_y))
        connect_port_to_point(result, _earth_comp, "top", (earth_cx, junction_y), port_is_start=False)
        result.junction_dots.append((g.mb_box_right, junction_y))


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
        connect_points(ctx.result, (ctx.cx, g.mb_center_y), (ctx.cx, y_exit))

        _add_outgoing_cable_tick(ctx, g, y_exit)
        _add_meter_board_box_and_earth(ctx, g)

        y = y_exit

    ctx.y = y
