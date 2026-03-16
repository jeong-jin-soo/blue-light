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
    - add_mtext: multiline text with position, size, and optional rotation
    - add_filled_rect: filled rectangle (for busbar, ELCB boxes, etc.)
    - set_layer: change current drawing layer (affects color/style)

    All coordinates are in mm, matching the A3 landscape drawing space (420x297mm).
    """

    def set_layer(self, layer_name: str) -> None:
        """
        Set the current drawing layer.

        Layers (calibrated from real LEW SLD samples):
        - SLD_SYMBOLS: Main symbol outlines (0.25mm) → DXF: E-SLD-SYM
        - SLD_CONNECTIONS: Connection lines between symbols (0.25mm) → DXF: E-SLD-LINE
        - SLD_POWER_MAIN: Main power supply lines / busbar (0.50mm) → DXF: E-SLD-BUSBAR
        - SLD_ANNOTATIONS: Text labels, ratings, annotations (0.25mm) → DXF: E-SLD-TXT
        - SLD_TITLE_BLOCK: Title block elements (0.25mm) → DXF: E-SLD-TITLE
        - SLD_FRAME: Drawing border (0.25mm) → DXF: E-SLD-FRAME
        - SLD_DB_FRAME: DB dashed boxes (0.25mm, gray) → DXF: E-SLD-BOX
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
        rotation: float = 0.0,
        center_across: bool = False,
    ) -> None:
        """
        Draw multiline text.

        Args:
            text: Text content. Use '\\P' for line breaks (DXF convention).
            insert: Position (x, y) in mm — top-left anchor.
            char_height: Character height in mm.
            rotation: Text rotation in degrees CCW. 90 = vertical (bottom-to-top).
        """
        ...

    def add_filled_rect(
        self,
        x: float,
        y: float,
        width: float,
        height: float,
        *,
        fill_color: tuple[float, float, float] | str = (0.0, 0.0, 0.0),
    ) -> None:
        """
        Draw a filled rectangle.

        Args:
            x, y: Bottom-left corner (in mm).
            width, height: Dimensions (in mm).
            fill_color: Fill color as (r, g, b) floats 0-1 or hex string.
        """
        ...

    def add_filled_circle(
        self,
        center: tuple[float, float],
        radius: float,
        *,
        fill_color: tuple[float, float, float] | str = (0.0, 0.0, 0.0),
    ) -> None:
        """
        Draw a filled circle (e.g., junction dot).

        Args:
            center: Center point (x, y) in mm.
            radius: Radius in mm.
            fill_color: Fill color as (r, g, b) floats 0-1 or hex string.
        """
        ...
