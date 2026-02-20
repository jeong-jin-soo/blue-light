"""
Metering symbols: kWh Meter, Ammeter, Voltmeter.
"""

import ezdxf

from app.sld.symbols.base import BaseSymbol


class KwhMeter(BaseSymbol):
    """kWh Meter symbol — circle with 'kWh'."""

    name: str = "KWH_METER"
    width: float = 16
    height: float = 16
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

        block.add_circle((cx, cy), radius=8, dxfattribs=attribs)
        block.add_mtext(
            "kWh",
            dxfattribs={
                "layer": "SLD_ANNOTATIONS",
                "char_height": 3.5,
                "insert": (cx - 4, cy - 1.5),
            },
        )

        # Connection stubs
        block.add_line((cx, cy + 8), (cx, self.height + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((cx, cy - 8), (cx, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})


class Ammeter(BaseSymbol):
    """Ammeter symbol — circle with 'A'."""

    name: str = "AMMETER"
    width: float = 12
    height: float = 12
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

        block.add_circle((cx, cy), radius=6, dxfattribs=attribs)
        block.add_mtext(
            "A",
            dxfattribs={
                "layer": "SLD_ANNOTATIONS",
                "char_height": 5,
                "insert": (cx - 2, cy - 2.5),
            },
        )

        block.add_line((cx, cy + 6), (cx, self.height + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((cx, cy - 6), (cx, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})
