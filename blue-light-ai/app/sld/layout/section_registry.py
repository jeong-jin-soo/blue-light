"""
SLD Template Registry — maps (supply_type, metering, supply_source) to section sequences.

This module defines the CORRECT section ordering for each SLD type.
Each template is a list of Section instances that are executed sequentially
by compute_layout().

Usage:
    sequence = get_section_sequence(requirements)
    for section in sequence:
        section.execute(ctx)

Design principles:
- Each SLD type is a flat list of Section instances
- The list IS the documentation of what gets drawn and in what order
- Adding a new SLD type = adding a new list
- No conditional branching inside compute_layout — all branching is here
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from app.sld.layout.section_base import FunctionSection, Section

if TYPE_CHECKING:
    from app.sld.layout.models import _LayoutContext

logger = logging.getLogger(__name__)


def get_section_sequence(requirements: dict) -> list[Section]:
    """Return the section sequence for the given requirements.

    Returns list of Section instances.
    The engine calls section.execute(ctx) for each in order.

    This is the SINGLE PLACE that determines what sections appear in an SLD.
    """
    metering = requirements.get("metering", "")
    supply_source = requirements.get("supply_source", "sp_powergrid")

    if metering == "ct_meter":
        return _ct_meter_sequence()
    elif metering in ("sp_meter", "landlord_meter") or metering:
        return _sp_meter_sequence()
    else:
        # No metering specified — use landlord/direct supply path
        return _direct_supply_sequence()


def _ct_meter_sequence() -> list[Section]:
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

    class _IsolatorForCt(Section):
        """Unit isolator with metering temporarily cleared (CT path)."""
        name = "unit_isolator"

        def place(self, ctx: "_LayoutContext") -> None:
            saved = ctx.metering
            ctx.metering = ""
            _place_unit_isolator(ctx)
            ctx.metering = saved

    class _CtGapAndSetup(Section):
        """Add isolator-to-DB gap and set up CT metering flags."""
        name = "ct_gap_setup"

        def place(self, ctx: "_LayoutContext") -> None:
            gap = ctx.config.isolator_to_db_gap
            ctx.result.connections.append(((ctx.cx, ctx.y), (ctx.cx, ctx.y + gap)))
            ctx.y += gap
            ctx._ct_box_start_y = ctx.y - 1
            ctx._ct_pre_mccb_fuse = True

    class _SetDbBoxStart(Section):
        """Transfer CT box start Y to db_box_start_y."""
        name = "db_box_start"

        def place(self, ctx: "_LayoutContext") -> None:
            ctx.db_box_start_y = ctx._ct_box_start_y

    return [
        FunctionSection("incoming_supply", _place_incoming_supply),
        _IsolatorForCt(),
        _CtGapAndSetup(),
        FunctionSection("ct_pre_mccb_fuse", _place_ct_pre_mccb_fuse),
        FunctionSection("main_breaker", _place_main_breaker, skip_gap=True),
        FunctionSection("ct_metering", _place_ct_metering_section),
        _SetDbBoxStart(),
        FunctionSection("elcb", _place_elcb),
        FunctionSection("internal_cable", _place_internal_cable),
        FunctionSection("main_busbar", _place_main_busbar),
    ]


def _sp_meter_sequence() -> list[Section]:
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
        FunctionSection("incoming_supply", _place_incoming_supply),
        FunctionSection("meter_board", _place_meter_board),
        FunctionSection("unit_isolator", _place_unit_isolator),
        FunctionSection("main_breaker", _place_main_breaker),
        FunctionSection("ct_pre_mccb_fuse", _place_ct_pre_mccb_fuse),
        FunctionSection("elcb", _place_elcb),
        FunctionSection("internal_cable", _place_internal_cable),
        FunctionSection("main_busbar", _place_main_busbar),
    ]


def _direct_supply_sequence() -> list[Section]:
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
        FunctionSection("incoming_supply", _place_incoming_supply),
        FunctionSection("meter_board", _place_meter_board),
        FunctionSection("unit_isolator", _place_unit_isolator),
        FunctionSection("main_breaker", _place_main_breaker),
        FunctionSection("ct_pre_mccb_fuse", _place_ct_pre_mccb_fuse),
        FunctionSection("elcb", _place_elcb),
        FunctionSection("internal_cable", _place_internal_cable),
        FunctionSection("main_busbar", _place_main_busbar),
    ]
