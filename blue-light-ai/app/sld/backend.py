"""
Drawing backend protocol for SLD generation.

Abstracts the drawing primitives so that symbols and generators
can target different output formats (PDF, SVG, etc.) without changes.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable


@runtime_checkable
class DrawingBackend(Protocol):
    """
    Protocol that all drawing backends must implement.

    Methods mirror the subset of ezdxf primitives used by the SLD symbol library:
    - add_line: straight line between two points
    - add_lwpolyline: lightweight polyline (sequence of points, optionally closed)
    - add_circle: circle defined by center and radius
    - add_arc: arc defined by center, radius, and angular range
    - add_mtext: multiline text with position and size
    - set_layer: change current drawing layer (affects color/style)

    All coordinates are in mm, matching the A3 landscape drawing space (420x297mm).
    """

    def set_layer(self, layer_name: str) -> None:
        """
        Set the current drawing layer.

        Layers:
        - SLD_SYMBOLS: Main symbol outlines
        - SLD_CONNECTIONS: Connection lines between symbols
        - SLD_ANNOTATIONS: Text labels, ratings, annotations
        - SLD_TITLE_BLOCK: Border and title block elements
        """
        ...

    def add_line(
        self,
        start: tuple[float, float],
        end: tuple[float, float],
        *,
        lineweight: int | None = None,
    ) -> None:
        """Draw a straight line from start to end (in mm)."""
        ...

    def add_lwpolyline(
        self,
        points: list[tuple[float, float]],
        *,
        close: bool = False,
        lineweight: int | None = None,
    ) -> None:
        """Draw a lightweight polyline through the given points (in mm)."""
        ...

    def add_circle(
        self,
        center: tuple[float, float],
        radius: float,
    ) -> None:
        """Draw a circle (in mm)."""
        ...

    def add_arc(
        self,
        center: tuple[float, float],
        radius: float,
        start_angle: float,
        end_angle: float,
    ) -> None:
        """
        Draw an arc (in mm).

        Angles are in degrees, counter-clockwise from positive X-axis,
        matching the DXF/ezdxf convention.
        """
        ...

    def add_mtext(
        self,
        text: str,
        *,
        insert: tuple[float, float],
        char_height: float = 3.0,
    ) -> None:
        """
        Draw multiline text.

        Args:
            text: Text content. Use '\\P' for line breaks (DXF convention).
            insert: Position (x, y) in mm — top-left anchor.
            char_height: Character height in mm.
        """
        ...
