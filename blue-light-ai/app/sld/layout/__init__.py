"""
SLD Layout Engine package.

Automatic component placement for Singapore LEW-style Single Line Diagrams.
Bottom-up layout: incoming supply at bottom, sub-circuits branch upward.

This package was refactored from the monolithic layout.py module.
All public symbols are re-exported here to maintain backward compatibility
with existing ``from app.sld.layout import X`` imports.
"""

from app.sld.layout.engine import compute_layout
from app.sld.layout.helpers import _assign_circuit_ids
from app.sld.layout.models import (
    LayoutConfig,
    LayoutResult,
    OverflowMetrics,
    PlacedComponent,
    _LayoutContext,
    format_cable_spec,
)
from app.sld.layout.overlap import (
    BoundingBox,
    SubCircuitGroup,
    _breaker_half_width,
    _compute_bounding_box,
    _compute_dynamic_spacing,
    _compute_group_width,
    _identify_groups,
    resolve_overlaps,
)
from app.sld.layout.section_base import FunctionSection, Section
from app.sld.layout.section_registry import get_section_sequence

__all__ = [
    # models
    "LayoutConfig",
    "LayoutResult",
    "OverflowMetrics",
    "PlacedComponent",
    "_LayoutContext",
    "format_cable_spec",
    # engine
    "compute_layout",
    # section architecture
    "Section",
    "FunctionSection",
    "get_section_sequence",
    # overlap
    "BoundingBox",
    "SubCircuitGroup",
    "_breaker_half_width",
    "_compute_bounding_box",
    "_compute_dynamic_spacing",
    "_compute_group_width",
    "_identify_groups",
    "resolve_overlaps",
    # helpers
    "_assign_circuit_ids",
]
