"""
Section base class for the SLD layout engine v2.

Design principles:
1. Each Section places its own components and advances ctx.y
2. Symbol dimensions come from ComponentCatalog (single source of truth)
3. Connections use catalog pin coordinates — no manual arithmetic
4. Y-cursor = last component's exit pin Y
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from functools import lru_cache
from typing import TYPE_CHECKING

from app.sld.catalog import ComponentDef, get_catalog
from app.sld.layout.models import PlacedComponent

if TYPE_CHECKING:
    from app.sld.layout.models import LayoutConfig, LayoutResult, _LayoutContext

logger = logging.getLogger(__name__)


def _comp_def(name: str) -> ComponentDef:
    """Get ComponentDef from catalog (CB_ prefix handled automatically)."""
    return get_catalog().get(name)


@lru_cache(maxsize=32)
def _cached_sym(name: str):
    """Get a real symbol object (cached). Used by place_branch for h_pins."""
    from app.sld.real_symbols import get_real_symbol
    return get_real_symbol(name)


def sym_dims(name: str) -> tuple[float, float, float]:
    """Return (width, height, stub) for a symbol by name.

    Uses ComponentCatalog as the single source of truth.
    """
    comp = _comp_def(name)
    return comp.width, comp.height, comp.stub


def sym_h_pins(name: str, x: float, y: float) -> dict[str, tuple[float, float]]:
    """Get horizontal pin positions {left: (x,y), right: (x,y)} from catalog."""
    comp = _comp_def(name)
    result = {}
    if "left" in comp.pins:
        p = comp.pins["left"]
        result["left"] = (x + p.x, y + p.y)
    if "right" in comp.pins:
        p = comp.pins["right"]
        result["right"] = (x + p.x, y + p.y)
    return result


def sym_v_pins(name: str, x: float, y: float) -> dict[str, tuple[float, float]]:
    """Get vertical pin positions {top: (x,y), bottom: (x,y)} from catalog."""
    comp = _comp_def(name)
    result = {}
    if "top" in comp.pins:
        p = comp.pins["top"]
        result["top"] = (x + p.x, y + p.y)
    if "bottom" in comp.pins:
        p = comp.pins["bottom"]
        result["bottom"] = (x + p.x, y + p.y)
    return result


class Section(ABC):
    """Base class for SLD layout sections.

    Subclasses implement place() which:
    - Reads ctx fields for configuration
    - Appends PlacedComponents to ctx.result.components
    - Appends connections to ctx.result.connections
    - Advances ctx.y past the section's extent

    The execute() method wraps place() with automatic sections_rendered tracking.
    """

    #: Section name for sections_rendered tracking (override in subclass or __init__)
    name: str = ""

    @abstractmethod
    def place(self, ctx: _LayoutContext) -> None:
        """Place this section's elements and advance ctx.y."""

    def execute(self, ctx: _LayoutContext) -> None:
        """Place with automatic sections_rendered tracking.

        Calls place(), then marks the section as rendered.
        Subclasses should NOT override this — override place() instead.
        """
        y_before = ctx.y
        self.place(ctx)
        # sections_rendered는 place() 내부에서도 설정 가능 (기존 패턴 호환)
        # place() 후 Y가 변했으면 실제로 렌더링된 것으로 간주
        if self.name and ctx.y != y_before:
            ctx.result.sections_rendered.setdefault(self.name, True)


class FunctionSection(Section):
    """Adapter: wraps a _place_* function as a Section instance.

    Enables gradual migration — existing functions work unchanged via::

        FunctionSection("main_breaker", _place_main_breaker)
    """

    def __init__(self, name: str, fn, **kwargs):
        self.name = name
        self._fn = fn
        self._kwargs = kwargs

    def place(self, ctx: _LayoutContext) -> None:
        if self._kwargs:
            self._fn(ctx, **self._kwargs)
        else:
            self._fn(ctx)

    def __repr__(self) -> str:
        return f"FunctionSection({self.name!r})"

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

        Uses catalog pin coordinates — no manual arithmetic.
        Y-cursor is set to exit pin (top) after placement.

        Args:
            ctx: Layout context (cx = spine X, y = current cursor)
            symbol_name: e.g. "CB_MCCB", "ISOLATOR"
            label: Component label text
            gap_before: Spine connection length before the symbol
            **comp_kwargs: Extra PlacedComponent fields

        Returns:
            (body_bottom_y, bottom_pin_y, top_pin_y)
        """
        comp = _comp_def(symbol_name)

        if gap_before > 0:
            ctx.result.connections.append(
                ((ctx.cx, ctx.y), (ctx.cx, ctx.y + gap_before))
            )
            ctx.y += gap_before

        comp_y = ctx.y
        comp_x = ctx.cx - comp.center_x()

        ctx.result.components.append(PlacedComponent(
            x=comp_x,
            y=comp_y,
            symbol_name=symbol_name,
            label=label,
            **comp_kwargs,
        ))

        # Pin coordinates from catalog — no manual h+stub arithmetic
        bottom_pin_y = comp_y + comp.pin("bottom").y  # = comp_y - stub
        top_pin_y = comp_y + comp.pin("top").y         # = comp_y + height + stub

        # Draw stub connections to close gaps between pin tips and body edges.
        # Some symbols (MCCB, RCCB) draw their own stubs via DXF block rendering —
        # double-drawing is harmless (overlapping lines are invisible in output).
        stub = comp.stub
        if stub > 0:
            body_top_y = comp_y + comp.height
            # Bottom stub: pin tip → body bottom
            ctx.result.connections.append(
                ((ctx.cx, bottom_pin_y), (ctx.cx, comp_y))
            )
            # Top stub: body top → pin tip
            ctx.result.connections.append(
                ((ctx.cx, body_top_y), (ctx.cx, top_pin_y))
            )

        ctx.y = top_pin_y  # cursor = exit pin

        return comp_y, bottom_pin_y, top_pin_y

    @staticmethod
    def place_batch_on_spine(
        ctx: _LayoutContext,
        components: list[dict],
    ) -> list[tuple[float, float, float]]:
        """Place multiple symbols on the vertical spine sequentially.

        Each dict in *components*: {"symbol": str, "label": str, "gap_before": float}
        Returns list of (body_bottom_y, bottom_pin_y, top_pin_y) per component.
        """
        results = []
        for spec in components:
            result = FunctionSection.place_on_spine(
                ctx,
                spec["symbol"],
                label=spec.get("label", ""),
                gap_before=spec.get("gap_before", 0),
            )
            results.append(result)
        return results

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
            comp = _comp_def(sym_name)

            # Horizontal body width from catalog h_extent
            body_w = comp.effective_h_extent
            stub = comp.stub
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
