"""
Busbar symbols: Main Busbar, Sub-Busbar.

Busbars are represented as thick horizontal lines.
"""

import ezdxf

from app.sld.symbols.base import BaseSymbol


class Busbar(BaseSymbol):
    """
    Busbar symbol — thick horizontal line.
    Width is dynamic based on the number of connected circuits.
    """

    name: str = "BUSBAR"
    width: float = 200
    height: float = 2
    layer: str = "SLD_SYMBOLS"

    def __init__(self, bus_width: float = 200, bus_name: str = "BUSBAR"):
        self.width = bus_width
        self.name = bus_name
        self.pins = {
            "left": (0, 0),
            "right": (self.width, 0),
            "center": (self.width / 2, 0),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        block.add_line(
            (0, 0), (self.width, 0),
            dxfattribs={
                "layer": self.layer,
                "lineweight": 50,  # Thick line for busbar
            },
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
