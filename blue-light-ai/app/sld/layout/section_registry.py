"""
SLD Template Registry — maps (supply_type, metering, supply_source) to section sequences.

This module defines the CORRECT section ordering for each SLD type.
Each template is a list of section function references that are called
sequentially by compute_layout().

Usage:
    sequence = get_section_sequence(requirements)
    for section_fn in sequence:
        section_fn(ctx)

Design principles:
- Each SLD type is a flat list of functions (not classes with inheritance)
- The list IS the documentation of what gets drawn and in what order
- Adding a new SLD type = adding a new list
- No conditional branching inside compute_layout — all branching is here
"""

from __future__ import annotations

import logging
from typing import Callable, TYPE_CHECKING

if TYPE_CHECKING:
    from app.sld.layout.models import _LayoutContext

logger = logging.getLogger(__name__)

# Type alias for section placement functions
SectionFn = Callable[["_LayoutContext"], None]


def _noop(ctx: "_LayoutContext") -> None:
    """No-op placeholder for conditional sections."""


def get_section_sequence(requirements: dict) -> list[tuple[str, SectionFn]]:
    """Return the section sequence for the given requirements.

    Returns list of (section_name, section_function) tuples.
    The engine calls each function in order, passing the shared context.

    This is the SINGLE PLACE that determines what sections appear in an SLD.
    """
    from app.sld.layout.sections import (
        _place_ct_metering_section,
        _place_ct_pre_mccb_fuse,
        _place_db_box,
        _place_earth_bar,
        _place_elcb,
        _place_incoming_supply,
        _place_internal_cable,
        _place_main_breaker,
        _place_main_busbar,
        _place_meter_board,
        _place_sub_circuits_rows,
        _place_unit_isolator,
    )

    metering = requirements.get("metering", "")
    supply_source = requirements.get("supply_source", "sp_powergrid")

    if metering == "ct_meter":
        return _ct_meter_sequence()
    elif metering in ("sp_meter", "landlord_meter") or metering:
        return _sp_meter_sequence()
    else:
        # No metering specified — use landlord/direct supply path
        return _direct_supply_sequence()


def _ct_meter_sequence() -> list[tuple[str, "SectionFn"]]:
    """CT metering SLD: supply → isolator → gap → fuse → MCCB → CT section → ELCB → busbar."""
    from app.sld.layout.sections import (
        _place_ct_metering_section,
        _place_ct_pre_mccb_fuse,
        _place_elcb,
        _place_incoming_supply,
        _place_internal_cable,
        _place_main_breaker,
        _place_main_busbar,
        _place_unit_isolator,
    )

    def _isolator_for_ct(ctx: "_LayoutContext") -> None:
        """Place unit isolator with metering temporarily cleared."""
        saved = ctx.metering
        ctx.metering = ""
        _place_unit_isolator(ctx)
        ctx.metering = saved

    def _ct_gap_and_setup(ctx: "_LayoutContext") -> None:
        """Add isolator-to-DB gap and set up CT metering flags."""
        gap = ctx.config.isolator_to_db_gap
        ctx.result.connections.append(((ctx.cx, ctx.y), (ctx.cx, ctx.y + gap)))
        ctx.y += gap
        ctx._ct_box_start_y = ctx.y - 1
        ctx._ct_pre_mccb_fuse = True

    def _main_breaker_no_gap(ctx: "_LayoutContext") -> None:
        _place_main_breaker(ctx, skip_gap=True)

    def _set_db_box_start(ctx: "_LayoutContext") -> None:
        ctx.db_box_start_y = ctx._ct_box_start_y

    return [
        ("incoming_supply", _place_incoming_supply),
        ("unit_isolator", _isolator_for_ct),
        ("ct_gap_setup", _ct_gap_and_setup),
        ("ct_pre_mccb_fuse", _place_ct_pre_mccb_fuse),
        ("main_breaker", _main_breaker_no_gap),
        ("ct_metering", _place_ct_metering_section),
        ("db_box_start", _set_db_box_start),
        ("elcb", _place_elcb),
        ("internal_cable", _place_internal_cable),
        ("main_busbar", _place_main_busbar),
    ]


def _sp_meter_sequence() -> list[tuple[str, "SectionFn"]]:
    """SP meter SLD: meter board → isolator → main breaker → ELCB → busbar."""
    from app.sld.layout.sections import (
        _place_ct_pre_mccb_fuse,
        _place_elcb,
        _place_incoming_supply,
        _place_internal_cable,
        _place_main_breaker,
        _place_main_busbar,
        _place_meter_board,
        _place_unit_isolator,
    )

    return [
        ("incoming_supply", _place_incoming_supply),
        ("meter_board", _place_meter_board),
        ("unit_isolator", _place_unit_isolator),
        ("main_breaker", _place_main_breaker),
        ("ct_pre_mccb_fuse", _place_ct_pre_mccb_fuse),
        ("elcb", _place_elcb),
        ("internal_cable", _place_internal_cable),
        ("main_busbar", _place_main_busbar),
    ]


def _direct_supply_sequence() -> list[tuple[str, "SectionFn"]]:
    """Direct supply (no metering): supply → isolator → breaker → ELCB → busbar."""
    from app.sld.layout.sections import (
        _place_ct_pre_mccb_fuse,
        _place_elcb,
        _place_incoming_supply,
        _place_internal_cable,
        _place_main_breaker,
        _place_main_busbar,
        _place_meter_board,
        _place_unit_isolator,
    )

    return [
        ("incoming_supply", _place_incoming_supply),
        ("meter_board", _place_meter_board),
        ("unit_isolator", _place_unit_isolator),
        ("main_breaker", _place_main_breaker),
        ("ct_pre_mccb_fuse", _place_ct_pre_mccb_fuse),
        ("elcb", _place_elcb),
        ("internal_cable", _place_internal_cable),
        ("main_busbar", _place_main_busbar),
    ]
