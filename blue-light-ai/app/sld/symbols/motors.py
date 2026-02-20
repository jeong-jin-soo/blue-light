"""
Motor and Generator symbols.

IEC 60617 standard: Circle with M/G designation.
"""

import ezdxf

from app.sld.symbols.base import BaseSymbol


class Motor(BaseSymbol):
    """Motor symbol — circle with 'M'."""

    name: str = "MOTOR"
    width: float = 16
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        cx = self.width / 2
        cy = self.height / 2
        attribs = {"layer": self.layer}

        block.add_circle((cx, cy), radius=8, dxfattribs=attribs)
        block.add_mtext(
            "M",
            dxfattribs={
                "layer": "SLD_ANNOTATIONS",
                "char_height": 6,
                "insert": (cx - 2, cy - 3),
            },
        )

        # Connection stub (top)
        block.add_line((cx, cy + 8), (cx, self.height + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})


class Generator(BaseSymbol):
    """Generator symbol — circle with 'G'."""

    name: str = "GENERATOR"
    width: float = 20
    height: float = 20
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        cx = self.width / 2
        cy = self.height / 2
        attribs = {"layer": self.layer}

        block.add_circle((cx, cy), radius=10, dxfattribs=attribs)
        block.add_mtext(
            "G",
            dxfattribs={
                "layer": "SLD_ANNOTATIONS",
                "char_height": 8,
                "insert": (cx - 3, cy - 4),
            },
        )

        # Connection stub (top)
        block.add_line((cx, cy + 10), (cx, self.height + 5), dxfattribs={"layer": "SLD_CONNECTIONS"})
