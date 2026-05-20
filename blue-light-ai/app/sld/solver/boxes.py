"""Box specification — the solver's input data model.

Every visual element on the diagram (symbol, label, channel) is a Box.
The solver decides (x, y) for each box; widths and heights are inputs
(except for `variable_w` boxes such as the busbar).

Coordinate units: 0.1 mm (one CP-SAT integer unit = 0.1 mm).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Optional


# Solver scale: 1 unit = 0.1 mm. CP-SAT works with integers.
UNIT_PER_MM = 10


def mm(value: float) -> int:
    """Convert millimetres to solver integer units."""
    return int(round(value * UNIT_PER_MM))


class BoxRole(str, Enum):
    SYMBOL = "symbol"   # an electrical component body
    LABEL = "label"     # text annotation tied to a parent symbol
    BUS = "bus"         # busbar (typically variable width)
    EARTH = "earth"     # earth bar
    CHANNEL = "channel" # reserved wire corridor (post-routing use)


@dataclass
class Box:
    name: str
    w: int                  # width in solver units (0.1 mm)
    h: int                  # height in solver units
    role: BoxRole
    section: int = 0        # Singapore 14-section index (1..14)
    column: str = ""        # logical column: spine, sub_circuit, ...
    parent: str = ""        # for labels: parent symbol name
    symbol_kind: str = ""   # catalog component name (MCCB, RCCB, ...)
    text: str = ""
    rotated: bool = False   # label drawn vertically
    variable_w: bool = False
    min_w: int = 0
    max_w: int = 0
    # Catalog-derived anchor offsets (relative to box origin). Used post-solve
    # to compute absolute pin coordinates for connection drawing.
    pins: dict[str, tuple[int, int]] = field(default_factory=dict)
    # Disjunctive label placement: the solver picks ONE of these anchors
    # relative to the parent symbol. Only meaningful when role == LABEL.
    # Supported anchors: "right", "left", "top", "bottom".
    label_anchors: list[str] = field(default_factory=lambda: ["right"])
    # Gap in solver units between label box and parent symbol edge.
    label_gap: int = 80   # 8 mm default


@dataclass
class SolverScene:
    """Everything the CP-SAT model needs to lay out one SLD page.

    Margin parameters
    -----------------
    `margin` is the legacy symmetric margin used for the left/right/top edges.
    `margin_bottom` is independent so the scenario can reserve room for the
    title block (occupies the lower band of the page in LEW drawings).
    `None` falls back to `margin` (preserves Phase 1/2 behavior).
    """

    boxes: list[Box] = field(default_factory=list)
    page_w: int = mm(420)   # A3 landscape default
    page_h: int = mm(297)
    margin: int = mm(10)
    margin_bottom: Optional[int] = None  # None → use `margin`

    @property
    def effective_margin_bottom(self) -> int:
        return self.margin if self.margin_bottom is None else self.margin_bottom

    # Logical groups — handy for constraint generation.
    def by_column(self, column: str) -> list[Box]:
        return [b for b in self.boxes if b.column == column]

    def by_role(self, role: BoxRole) -> list[Box]:
        return [b for b in self.boxes if b.role == role]

    def get(self, name: str) -> Optional[Box]:
        for b in self.boxes:
            if b.name == name:
                return b
        return None
