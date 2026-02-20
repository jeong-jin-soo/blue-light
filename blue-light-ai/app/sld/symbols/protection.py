"""
Protection symbols: Fuse, Earth, Surge Protector.
"""

import ezdxf

from app.sld.symbols.base import BaseSymbol


class Fuse(BaseSymbol):
    """Fuse symbol — narrow rectangle."""

    name: str = "FUSE"
    width: float = 6
    height: float = 14
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        w, h = self.width, self.height
        cx = w / 2
        attribs = {"layer": self.layer}

        # Narrow rectangle
        block.add_lwpolyline(
            [(0, 2), (w, 2), (w, h - 2), (0, h - 2)],
            close=True,
            dxfattribs=attribs,
        )

        # Connection stubs
        block.add_line((cx, h - 2), (cx, self.height + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((cx, 2), (cx, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})


class EarthSymbol(BaseSymbol):
    """
    Earth/Ground symbol — descending horizontal lines.
    Standard IEC 60617 representation.
    """

    name: str = "EARTH"
    width: float = 12
    height: float = 14
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        cx = self.width / 2
        attribs = {"layer": self.layer}

        # Vertical line from top
        block.add_line((cx, self.height), (cx, 8), dxfattribs=attribs)

        # Three descending horizontal lines
        block.add_line((0, 8), (self.width, 8), dxfattribs=attribs)
        block.add_line((2, 5), (self.width - 2, 5), dxfattribs=attribs)
        block.add_line((4, 2), (self.width - 4, 2), dxfattribs=attribs)


class SurgeProtector(BaseSymbol):
    """Surge Protection Device (SPD)."""

    name: str = "SPD"
    width: float = 10
    height: float = 16
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 3),
            "bottom": (cx, -3),
        }

    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        w, h = self.width, self.height
        cx = w / 2
        attribs = {"layer": self.layer}

        # Rectangle
        block.add_lwpolyline(
            [(0, 0), (w, 0), (w, h), (0, h)],
            close=True,
            dxfattribs=attribs,
        )

        # Lightning bolt (zigzag)
        block.add_lwpolyline(
            [(cx, h - 2), (cx + 2, h / 2 + 1), (cx - 2, h / 2 - 1), (cx, 2)],
            dxfattribs=attribs,
        )

        # Connection stubs
        block.add_line((cx, h), (cx, h + 3), dxfattribs={"layer": "SLD_CONNECTIONS"})
        block.add_line((cx, 0), (cx, -3), dxfattribs={"layer": "SLD_CONNECTIONS"})
