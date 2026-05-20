"""CP-SAT based SLD layout solver.

A constraint-satisfaction replacement for the procedural layout engine.
Components are modelled as rectangular boxes with declared widths/heights
(sourced from `app/sld/catalog.py`).  Global non-overlap and Singapore
14-section flow ordering are enforced as hard constraints via OR-tools
CP-SAT, so collisions are mathematically prevented rather than detected
and patched after rendering.

This module is INDEPENDENT of the legacy engine — it runs in parallel
behind a feature flag.  See `place_layout()` for the entry point.
"""

from app.sld.solver.adapter import adapt_to_layout_result
from app.sld.solver.boxes import Box, BoxRole, SolverScene
from app.sld.solver.place import SolveResult, place_layout

__all__ = [
    "Box", "BoxRole", "SolverScene",
    "SolveResult", "place_layout",
    "adapt_to_layout_result",
]
