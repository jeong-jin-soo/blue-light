"""
Page configuration for SLD drawings.

Single source of truth for page dimensions, margins, and title block geometry.
All values in mm.

Supported sizes:
- A3 landscape (420 × 297 mm) — standard for Singapore EMA submissions
- A2 landscape (594 × 420 mm) — for large systems (28+ circuits, 3+ boards)
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class PageConfig:
    """Immutable page configuration.

    All derived properties (drawing boundaries, title block zone) are computed
    from the four base parameters so that changing page size automatically
    adjusts everything.
    """

    page_width: float = 420.0
    page_height: float = 297.0
    margin: float = 10.0
    title_block_height: float = 32.0  # TB_TOP - TB_BOTTOM in current title_block.py

    # -- derived properties --------------------------------------------------

    @property
    def drawing_left(self) -> float:
        """Left drawing boundary (= margin)."""
        return self.margin

    @property
    def drawing_right(self) -> float:
        """Right drawing boundary (= page_width - margin)."""
        return self.page_width - self.margin

    @property
    def drawing_top(self) -> float:
        """Top drawing boundary (= page_height - margin)."""
        return self.page_height - self.margin

    @property
    def drawing_bottom(self) -> float:
        """Bottom drawing boundary (= margin)."""
        return self.margin

    @property
    def title_block_bottom(self) -> float:
        """Bottom of title block = margin."""
        return self.margin

    @property
    def title_block_top(self) -> float:
        """Top of title block = margin + title_block_height."""
        return self.margin + self.title_block_height

    @property
    def usable_width(self) -> float:
        """Usable drawing width inside margins."""
        return self.page_width - 2 * self.margin

    @property
    def usable_height(self) -> float:
        """Usable drawing height inside margins."""
        return self.page_height - 2 * self.margin


# Standard page sizes — landscape orientation.
A3_LANDSCAPE = PageConfig()  # 420 × 297 mm
A2_LANDSCAPE = PageConfig(page_width=594.0, page_height=420.0)  # 594 × 420 mm


def auto_page_size(
    requirements: dict | None = None,
    *,
    circuit_threshold: int = 21,
    board_threshold: int = 3,
) -> PageConfig:
    """Select page size based on circuit/board count.

    Rules:
    - ≥ circuit_threshold total circuits → A2
    - ≥ board_threshold distribution boards → A2
    - Otherwise → A3 (default)

    Args:
        requirements: SLD requirements dict.
        circuit_threshold: Total circuit count to trigger A2 (default 21).
        board_threshold: Board count to trigger A2 (default 3).
    """
    if not requirements:
        return A3_LANDSCAPE

    # Count circuits
    total_circuits = len(requirements.get("sub_circuits", []))
    dbs = requirements.get("distribution_boards", [])
    if dbs:
        total_circuits = sum(
            len(db.get("sub_circuits", [])) for db in dbs
        )

    # Count boards
    board_count = len(dbs) if dbs else 1

    if total_circuits >= circuit_threshold or board_count >= board_threshold:
        return A2_LANDSCAPE

    return A3_LANDSCAPE
