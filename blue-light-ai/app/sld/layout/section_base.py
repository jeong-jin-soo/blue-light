"""
Section base class for the SLD layout engine v2.

Design principles:
1. Each Section places its own components and advances ctx.y
2. Symbol dimensions are ALWAYS queried from real_symbols (no magic numbers)
3. Connections are computed from exact pin positions (no post-hoc snapping)
4. The orchestrator auto-connects spine gaps between sections
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from functools import lru_cache
from typing import TYPE_CHECKING

from app.sld.layout.models import PlacedComponent

if TYPE_CHECKING:
    from app.sld.layout.models import LayoutConfig, LayoutResult, _LayoutContext

logger = logging.getLogger(__name__)


@lru_cache(maxsize=32)
def _cached_sym(name: str):
    """Get a real symbol object (cached)."""
    from app.sld.real_symbols import get_real_symbol
    return get_real_symbol(name)


def sym_dims(name: str) -> tuple[float, float, float]:
    """Return (width, height, stub) for a symbol by name.

    Uses real_symbols as the single source of truth — no hardcoded constants.
    """
    sym = _cached_sym(name)
    return sym.width, sym.height, getattr(sym, '_stub', 2.0)


def sym_h_pins(name: str, x: float, y: float) -> dict[str, tuple[float, float]]:
    """Get horizontal pin positions {left: (x,y), right: (x,y)} for a symbol."""
    sym = _cached_sym(name)
    return sym.horizontal_pins(x, y)


def sym_v_pins(name: str, x: float, y: float) -> dict[str, tuple[float, float]]:
    """Get vertical pin positions {top: (x,y), bottom: (x,y)} for a symbol."""
    sym = _cached_sym(name)
    return sym.vertical_pins(x, y)


class Section(ABC):
    """Base class for SLD layout sections.

    Subclasses implement place() which:
    - Reads ctx fields for configuration
    - Appends PlacedComponents to ctx.result.components
    - Appends connections to ctx.result.connections
    - Advances ctx.y past the section's extent
    """

    @abstractmethod
    def place(self, ctx: _LayoutContext) -> None:
        """Place this section's elements and advance ctx.y."""

    # ── Spine placement helpers ──

    @staticmethod
    def place_on_spine(
        ctx: _LayoutContext,
        symbol_name: str,
        label: str = "",
        gap_before: float = 0,
        **comp_kwargs,
    ) -> tuple[float, float, float]:
        """Place a symbol centered on the vertical spine.

        Args:
            ctx: Layout context (cx = spine X, y = current cursor)
            symbol_name: e.g. "CB_MCCB", "ISOLATOR"
            label: Component label text
            gap_before: Spine connection length before the symbol
            **comp_kwargs: Extra PlacedComponent fields

        Returns:
            (body_bottom_y, bottom_pin_y, top_pin_y)
            - body_bottom_y: Y of the symbol body bottom edge
            - bottom_pin_y: Y of the bottom connection pin tip
            - top_pin_y: Y of the top connection pin tip
        """
        w, h, stub = sym_dims(symbol_name)

        if gap_before > 0:
            ctx.result.connections.append(
                ((ctx.cx, ctx.y), (ctx.cx, ctx.y + gap_before))
            )
            ctx.y += gap_before

        comp_y = ctx.y
        comp_x = ctx.cx - w / 2

        ctx.result.components.append(PlacedComponent(
            x=comp_x,
            y=comp_y,
            symbol_name=symbol_name,
            label=label,
            **comp_kwargs,
        ))

        bottom_pin_y = comp_y - stub
        top_pin_y = comp_y + h + stub
        ctx.y = top_pin_y

        return comp_y, bottom_pin_y, top_pin_y

    @staticmethod
    def spine_connection(ctx: _LayoutContext, distance: float) -> None:
        """Add a vertical spine connection and advance cursor."""
        if distance > 0:
            ctx.result.connections.append(
                ((ctx.cx, ctx.y), (ctx.cx, ctx.y + distance))
            )
            ctx.y += distance

    @staticmethod
    def add_label(
        ctx: _LayoutContext,
        x: float,
        y: float,
        text: str,
        rotation: float = 0,
        **kwargs,
    ) -> None:
        """Add a LABEL pseudo-component."""
        ctx.result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=x,
            y=y,
            label=text,
            rotation=rotation,
            **kwargs,
        ))

    @staticmethod
    def place_branch(
        ctx: _LayoutContext,
        branch_y: float,
        direction: str,
        components: list[tuple[str, str, float | None]],
        arm_len: float = 15.0,
        gap: float = 3.0,
    ) -> list[tuple[str, float, float]]:
        """Place a horizontal branch of chained components.

        Args:
            ctx: Layout context
            branch_y: Y coordinate of the branch
            direction: "left" or "right"
            components: List of (symbol_name, label, width_hint|None)
            arm_len: Length of arm from spine to first component
            gap: Gap between components

        Returns:
            List of (symbol_name, comp_x, comp_y) for each placed component
        """
        result = ctx.result
        cx = ctx.cx
        sign = -1 if direction == "left" else 1
        is_last_idx = len(components) - 1

        # Junction dot at branch point
        result.junction_dots.append((cx, branch_y))

        # Junction arrow (CT hook style)
        result.junction_arrows.append((cx, branch_y, direction))

        # Arm from spine
        arm_end_x = cx + sign * arm_len
        result.connections.append(((cx, branch_y), (arm_end_x, branch_y)))

        placed: list[tuple[str, float, float]] = []
        cursor_x = arm_end_x

        for idx, (sym_name, label, width_hint) in enumerate(components):
            w, h, stub = sym_dims(sym_name)

            # Get body width from horizontal pins
            sym = _cached_sym(sym_name)
            h_pins = sym.horizontal_pins(0, 0)
            body_w = h_pins["right"][0] - h_pins["left"][0] - 2 * getattr(sym, '_stub', 2.0)
            if width_hint and width_hint > body_w:
                body_w = width_hint

            is_last = idx == is_last_idx

            if direction == "right":
                comp_x = cursor_x
                comp_y = branch_y
                result.components.append(PlacedComponent(
                    x=comp_x, y=comp_y,
                    symbol_name=sym_name,
                    label=label,
                    rotation=90.0,
                    no_right_stub=is_last,
                ))
                placed.append((sym_name, comp_x, comp_y))
                cursor_x = comp_x + body_w + stub
            else:
                comp_x = cursor_x - body_w
                comp_y = branch_y
                result.components.append(PlacedComponent(
                    x=comp_x, y=comp_y,
                    symbol_name=sym_name,
                    label=label,
                    rotation=90.0,
                    no_left_stub=is_last,
                ))
                placed.append((sym_name, comp_x, comp_y))
                cursor_x = comp_x - stub

            # Gap connection to next component
            if not is_last:
                gap_start_x = cursor_x
                gap_end_x = cursor_x + sign * gap
                result.connections.append(
                    ((gap_start_x, branch_y), (gap_end_x, branch_y))
                )
                cursor_x = gap_end_x

        return placed
