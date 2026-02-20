"""
Base class for all SLD electrical symbols.
Each symbol is defined as a DXF Block with standardized connection pins.
"""

from abc import ABC, abstractmethod

import ezdxf
from ezdxf.document import Drawing


class BaseSymbol(ABC):
    """
    Abstract base class for electrical symbols in the SLD.

    Each symbol defines:
    - name: Unique block name in the DXF document
    - width/height: Bounding box dimensions (drawing units, mm)
    - pins: Named connection points relative to the symbol origin
            e.g., {"top": (5, 16), "bottom": (5, 0)}
    """

    name: str = ""
    width: float = 0
    height: float = 0
    pins: dict[str, tuple[float, float]] = {}

    # Default DXF layer for this symbol type
    layer: str = "SLD_SYMBOLS"
    color: int = 4  # Cyan by default

    @abstractmethod
    def _draw(self, block: ezdxf.entities.BlockLayout) -> None:
        """
        Draw the symbol geometry into the given block.
        Subclasses implement this to define their shape.
        """
        pass

    def register(self, doc: Drawing) -> str:
        """
        Register this symbol as a block in the DXF document.
        Returns the block name for use with INSERT.
        """
        if self.name in doc.blocks:
            return self.name

        block = doc.blocks.new(name=self.name)
        self._draw(block)
        return self.name

    def get_pin(self, pin_name: str) -> tuple[float, float]:
        """Get the position of a named connection pin."""
        if pin_name not in self.pins:
            raise ValueError(f"Symbol '{self.name}' has no pin '{pin_name}'. Available: {list(self.pins.keys())}")
        return self.pins[pin_name]

    def center(self) -> tuple[float, float]:
        """Get the center point of the symbol bounding box."""
        return (self.width / 2, self.height / 2)
