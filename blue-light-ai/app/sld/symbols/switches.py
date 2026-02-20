"""
Switch symbols: Isolator, ATS (Automatic Transfer Switch), Contactor.
"""

import ezdxf

from app.sld.symbols.base import BaseSymbol


class Isolator(BaseSymbol):
    """
    Isolator/Disconnect switch.
    Two parallel lines with a gap and a diagonal line.
    """

    name: str = "ISOLATOR"
    width: float = 10
    height: float = 14
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        cx = self.width / 2
        attribs = {"layer": self.layer}

        # Bottom contact point
        block.add_line((cx, 0), (cx, 3), dxfattribs=attribs)
        block.add_circle((cx, 3), radius=1, dxfattribs=attribs)

        # Diagonal blade
        block.add_line((cx, 3), (cx + 4, 11), dxfattribs=attribs)

        # Top contact point
        block.add_circle((cx, 11), radius=1, dxfattribs=attribs)
        block.add_line((cx, 11), (cx, self.height), dxfattribs=attribs)

        # Connection stubs
        block.add_line((cx, self.height), (cx, self.height + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((cx, 0), (cx, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})


class ATS(BaseSymbol):
    """
    Automatic Transfer Switch.
    Two incoming lines converging to one output with a switch symbol.
    """

    name: str = "ATS"
    width: float = 30
    height: float = 20
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        self.pins = {
            "input_1": (8, self.height + 3),
            "input_2": (22, self.height + 3),
            "output": (15, -3),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        attribs = {"layer": self.layer}

        # Rectangle enclosure
        block.add_lwpolyline(
            [(0, 0), (self.width, 0), (self.width, self.height), (0, self.height)],
            close=True,
            dxfattribs=attribs,
        )

        # ATS label
        block.add_mtext(
            "ATS",
            dxfattribs={
                "layer": "SLD_ANNOTATIONS",
                "char_height": 4,
                "insert": (10, 12),
            },
        )

        # Two input stubs
        block.add_line((8, self.height), (8, self.height + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((22, self.height), (22, self.height + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})

        # One output stub
        block.add_line((15, 0), (15, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})
