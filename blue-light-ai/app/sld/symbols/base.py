"""
Backward-compatible re-export of BaseSymbol.

The canonical implementation has moved to ``app.sld.base_symbol``.
This module re-exports BaseSymbol so that existing imports like
``from app.sld.symbols.base import BaseSymbol`` continue to work.
"""

from app.sld.base_symbol import BaseSymbol  # noqa: F401

__all__ = ["BaseSymbol"]
