"""
Transformer symbols: Power Transformer, CT, PT.

IEC 60617 standard representation:
- Two overlapping circles for power transformer
- Single circle with designation for CT/PT
"""

import ezdxf

from app.sld.symbols.base import BaseSymbol


class PowerTransformer(BaseSymbol):
    """
    Power Transformer symbol — two overlapping circles.
    Primary coil (top) and secondary coil (bottom).
    """

    name: str = "TRANSFORMER"
    width: float = 16
    height: float = 28
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        r = 8  # Radius of each coil circle
        cx = self.width / 2
        attribs = {"layer": self.layer}

        # Primary coil (top circle)
        block.add_circle((cx, r + 6), radius=r, dxfattribs=attribs)

        # Secondary coil (bottom circle, overlapping)
        block.add_circle((cx, r - 2), radius=r, dxfattribs=attribs)

        # Connection lines
        block.add_line(
            (cx, r + 6 + r), (cx, self.height + 5),
            dxfattribs={"layer": "SLD_CONNECTIONS"},
        )
        block.add_line(
            (cx, r - 2 - r), (cx, -5),
            dxfattribs={"layer": "SLD_CONNECTIONS"},
        )


class CurrentTransformer(BaseSymbol):
    """Current Transformer (CT) — circle with 'CT' text."""

    name: str = "CT"
    width: float = 10
    height: float = 10
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        cx = self.width / 2
        cy = self.height / 2
        attribs = {"layer": self.layer}

        block.add_circle((cx, cy), radius=5, dxfattribs=attribs)
        block.add_mtext(
            "CT",
            dxfattribs={
                "layer": "SLD_ANNOTATIONS",
                "char_height": 3,
                "insert": (cx - 2.5, cy - 1.5),
            },
        )

        # Connection stubs
        block.add_line((cx, cy + 5), (cx, self.height + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((cx, cy - 5), (cx, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})
