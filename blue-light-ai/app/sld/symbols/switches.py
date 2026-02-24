"""
Switch symbols: Isolator, ATS (Automatic Transfer Switch), Contactor.
Scaled for professional A3 engineering drawings.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.sld.symbols.base import BaseSymbol

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class Isolator(BaseSymbol):
    """
    Isolator/Disconnect switch.
    Two parallel lines with a gap and a diagonal line.
    """

    name: str = "ISOLATOR"
    width: float = 12
    height: float = 18
    layer: str = "SLD_SYMBOLS"

    def __init__(self):
        cx = self.width / 2
        self.pins = {
            "top": (cx, self.height + 5),
            "bottom": (cx, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        cx = x + self.width / 2

        backend.set_layer(self.layer)

        # Bottom contact point
        backend.add_line((cx, y), (cx, y + 4))
        backend.add_circle((cx, y + 4), radius=1.2)

        # Diagonal blade
        backend.add_line((cx, y + 4), (cx + 5, y + 14))

        # Top contact point
        backend.add_circle((cx, y + 14), radius=1.2)
        backend.add_line((cx, y + 14), (cx, y + self.height))

        # Connection stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((cx, y + self.height), (cx, y + self.height + 5))
        backend.add_line((cx, y), (cx, y - 5))


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
            "input_1": (8, self.height + 5),
            "input_2": (22, self.height + 5),
            "output": (15, -5),
        }

    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        backend.set_layer(self.layer)

        # Rectangle enclosure
        backend.add_lwpolyline(
            [
                (x, y),
                (x + self.width, y),
                (x + self.width, y + self.height),
                (x, y + self.height),
            ],
            close=True,
        )

        # ATS label
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext("ATS", insert=(x + 10, y + 14), char_height=5)

        # Two input stubs
        backend.set_layer("SLD_CONNECTIONS")
        backend.add_line((x + 8, y + self.height), (x + 8, y + self.height + 5))
        backend.add_line((x + 22, y + self.height), (x + 22, y + self.height + 5))

        # One output stub
        backend.add_line((x + 15, y), (x + 15, y - 5))
