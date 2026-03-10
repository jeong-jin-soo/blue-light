"""
Page configuration for SLD drawings.

Single source of truth for page dimensions, margins, and title block geometry.
All values in mm.

Default: A3 landscape (420 x 297 mm) — standard for Singapore EMA submissions.
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


# Default A3 landscape instance — used as fallback throughout the codebase.
A3_LANDSCAPE = PageConfig()
