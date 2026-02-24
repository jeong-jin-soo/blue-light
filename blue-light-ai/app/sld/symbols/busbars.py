"""
Busbar symbols: Main Busbar, Sub-Busbar.

Busbars are represented as double horizontal lines (professional standard).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class Busbar(BaseSymbol):
    """
    Busbar symbol -- double thick horizontal lines.
    Width is dynamic based on the number of connected circuits.
    Professional representation: two parallel lines 2mm apart.
    """

    name: str = "BUSBAR"
    width: float = 200
    height: float = 3  # Gap between double lines
    layer: str = "SLD_SYMBOLS"

    def __init__(self, bus_width: float = 200, bus_name: str = "BUSBAR"):
        self.width = bus_width
        self.name = bus_name
        self.pins = {
            "left": (0, 0),
            "right": (self.width, 0),
            "center": (self.width / 2, 0),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        backend.set_layer(self.layer)
        gap = 2.0  # 2mm gap between double lines

        # Top line
        backend.add_line(
            (x, y + gap / 2), (x + self.width, y + gap / 2),
            lineweight=80,  # 0.8mm thick
        )
        # Bottom line
        backend.add_line(
            (x, y - gap / 2), (x + self.width, y - gap / 2),
            lineweight=80,
        )

    def get_tap_point(self, index: int, total: int) -> tuple[float, float]:
        """
        Calculate the tap point for a sub-circuit connection.
        Distributes connections evenly along the busbar.
        """
        if total <= 1:
            return (self.width / 2, 0)

        margin = 30  # Margin from edges
        usable_width = self.width - 2 * margin
        spacing = usable_width / (total - 1) if total > 1 else 0
        x = margin + index * spacing
        return (x, 0)
