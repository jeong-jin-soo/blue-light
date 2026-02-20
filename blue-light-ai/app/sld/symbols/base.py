"""
Base class for all SLD electrical symbols.

Each symbol draws itself onto a DrawingBackend at a given (x, y) offset,
using standardized connection pins for layout integration.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.sld.backend import DrawingBackend


class BaseSymbol(ABC):
    """
    Abstract base class for electrical symbols in the SLD.

    Each symbol defines:
    - name: Unique identifier for this symbol type
    - width/height: Bounding box dimensions (mm)
    - pins: Named connection points relative to the symbol origin (0, 0)
            e.g., {"top": (5, 16), "bottom": (5, 0)}
    """

    name: str = ""
    width: float = 0
    height: float = 0
    pins: dict[str, tuple[float, float]] = {}

    # Default drawing layer for this symbol type
    layer: str = "SLD_SYMBOLS"

    @abstractmethod
    def draw(self, backend: DrawingBackend, x: float, y: float) -> None:
        """
        Draw the symbol onto the backend at position (x, y).

        Subclasses implement this to define their shape.
        All internal coordinates should be offset by (x, y).
        """
        pass

    def get_pin(self, pin_name: str) -> tuple[float, float]:
        """Get the position of a named connection pin (relative to origin)."""
        if pin_name not in self.pins:
            raise ValueError(
                f"Symbol '{self.name}' has no pin '{pin_name}'. "
                f"Available: {list(self.pins.keys())}"
            )
        return self.pins[pin_name]

    def get_pin_absolute(self, pin_name: str, x: float, y: float) -> tuple[float, float]:
        """Get the absolute position of a named connection pin."""
        px, py = self.get_pin(pin_name)
        return (x + px, y + py)

    def center(self) -> tuple[float, float]:
        """Get the center point of the symbol bounding box (relative to origin)."""
        return (self.width / 2, self.height / 2)
