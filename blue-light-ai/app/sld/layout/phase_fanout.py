"""
SLD Layout phase fan-out — 3-phase busbar fan-out geometry.

Extracted from overlap.py. Contains:
- _get_circuit_id()
- _parse_phase_prefix()
- _extract_section_code()
- _build_phase_groups()
- _add_phase_fanout()
"""

from __future__ import annotations

import logging
import re

from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent
from app.sld.layout.overlap import SubCircuitGroup, _identify_groups

logger = logging.getLogger(__name__)


def _get_circuit_id(group: SubCircuitGroup, components: list) -> str:
    """Get the circuit ID string (e.g., 'L1S', 'L2P1') from a group."""
    if group.circuit_id_idx is not None:
        cid = components[group.circuit_id_idx].circuit_id or ""
        return cid.strip()
    return ""


def _parse_phase_prefix(circuit_id: str) -> tuple[str, str]:
    """Parse circuit ID into (phase, suffix). E.g., 'L1P3' → ('L1', 'P3')."""
    m = re.match(r"^(L[123])(.*)", circuit_id, re.IGNORECASE)
    if m:
        return m.group(1).upper(), m.group(2)
    return ("", circuit_id)


def _extract_section_code(circuit_id: str) -> str:
    """Extract section code from circuit ID.

    Examples:
        'L1S1' → 'S',  'L2P3' → 'P',  'ISOL1' → '',  'SP1' → ''
    """
    m = re.match(r"^L[123]([A-Z])", circuit_id, re.IGNORECASE)
    if m:
        return m.group(1).upper()
    return ""


def _build_phase_groups(
    circuits: list[tuple[SubCircuitGroup, float]],
    components: list,
) -> list[list[tuple[SubCircuitGroup, float]]]:
    """Build 3-phase fan-out groups respecting section boundaries.

    In a TPN distribution board, every 3 consecutive busbar positions form
    one physical triplet connected to L1/L2/L3 phases of the comb bar.
    ALL circuit types participate: regular MCB, ISOLATOR, and SPARE.

    Section boundaries (e.g., Lighting→Power) are detected from circuit IDs
    (L1S1→section 'S', L1P1→section 'P'). Triplets never span sections.
    SPARE and ISOLATOR circuits inherit the section of the preceding circuit.

    Reference: 63A TPN SLD 14 — 27-way DB:
      Lighting: [L1S1,L2S1,L3S1] [L1S2,L2S2,L3S2] [L1S3,SPARE,SPARE]
      Power:    [L1P1,L2P1,L3P1] [ISOL1,L2P2,L3P2] [L1P3,L2P3,ISOL2]
                [L1P4,L2P4,ISOL3] [L1P5,L2P5,L3P5] [L1P6,SPARE,SPARE]

    Returns groups of 2 or 3 circuits (singles are skipped — no fan-out needed).
    """
    # --- 1. Assign section code to each circuit ---
    section_codes: list[str] = []
    last_section = ""
    for (g, _by) in circuits:
        cid = _get_circuit_id(g, components)
        sec = _extract_section_code(cid)
        if sec:
            last_section = sec
        # SPARE / ISOL inherit previous section
        section_codes.append(last_section)

    # --- 2. Split at section boundaries ---
    sections: list[list[tuple[SubCircuitGroup, float]]] = []
    current_section: list[tuple[SubCircuitGroup, float]] = []
    current_code = section_codes[0] if section_codes else ""

    for idx, item in enumerate(circuits):
        code = section_codes[idx]
        if code != current_code and current_section:
            sections.append(current_section)
            current_section = []
            current_code = code
        current_section.append(item)
    if current_section:
        sections.append(current_section)

    # --- 3. Chunk each section into triplets ---
    groups: list[list[tuple[SubCircuitGroup, float]]] = []
    for section in sections:
        for i in range(0, len(section), 3):
            chunk = section[i:i + 3]
            if len(chunk) >= 2:
                groups.append(chunk)
        if len(section) % 3 != 0:
            logging.getLogger(__name__).warning(
                "Section has %d circuits (not multiple of 3) — "
                "incomplete triplet at section end", len(section),
            )

    return groups


def _add_phase_fanout(
    layout_result: LayoutResult,
    config: LayoutConfig,
    supply_type: str,
) -> None:
    """Add 3-phase fan-out at busbar (post-resolve_overlaps).

    Position-based triplet grouping: every 3 consecutive busbar positions
    form one fan-out group. ALL circuit types participate (MCB, ISOLATOR,
    SPARE) since they all occupy physical positions on the comb bar.

    Reference pattern (63A TPN SLD 14 DXF):
      - Center circuit: straight vertical from busbar to MCB
      - Side circuits: diagonal from (center_x, busbar_y) to (side_x, busbar_y + fan_h),
        then vertical from (side_x, busbar_y + fan_h) to MCB
      - Fan-out height ratio: dy/dx ≈ 0.266 (193 DU / 727 DU spacing)

    Fan-out geometry is stored in layout_result.fanout_groups for backend-specific
    rendering (DXF block INSERT or procedural lines).
    """
    if supply_type != "three_phase":
        return
    if not layout_result.use_triplets:
        return

    groups, _ = _identify_groups(layout_result)
    if not groups:
        return

    busbar_ys = layout_result.busbar_y_per_row or [layout_result.busbar_y]
    components = layout_result.components

    # --- Group ALL circuits (including SPARE and ISOL) PER ROW ---
    rows: dict[int, list[tuple[SubCircuitGroup, float]]] = {}
    for g in groups:
        if g.breaker_idx is not None or g.is_spare:
            row_idx = g.row_idx
            by = g.row_busbar_y if g.row_busbar_y else busbar_ys[0]
            rows.setdefault(row_idx, []).append((g, by))

    # Reference: 63A TPN SLD 14 → dy/dx = 193/727 ≈ 0.266
    _FAN_RATIO = 0.266

    connections = layout_result.connections

    for row_idx in sorted(rows.keys()):
        all_circuits = rows[row_idx]

        phase_groups = _build_phase_groups(all_circuits, components)

        for pg in phase_groups:
            if len(pg) == 3:
                left_g, _ = pg[0]
                center_g, center_by = pg[1]
                right_g, _ = pg[2]
                by = center_by
            elif len(pg) == 2:
                center_g, center_by = pg[0]
                side_g, _ = pg[1]
                by = center_by
            else:
                continue

            center_x = center_g.tap_x
            side_xs: list[float] = []

            if len(pg) == 3:
                sides = [left_g, right_g]
            else:
                sides = [side_g]

            for s_g in sides:
                side_xs.append(s_g.tap_x)
                spacing = abs(s_g.tap_x - center_x)
                fan_h = spacing * _FAN_RATIO
                intermediate_y = by + fan_h

                # Modify side vertical connections: start from intermediate_y
                # (original starts from busbar_y — truncate to start from fan-out tip)
                for ci in s_g.connection_indices:
                    (sx, sy), (ex, ey) = connections[ci]
                    if abs(sy - by) < 1.0 and abs(sx - ex) < 0.5:
                        connections[ci] = ((sx, intermediate_y), (ex, ey))

                # Relocate junction dot to center busbar position
                if s_g.junction_dot_idx is not None:
                    layout_result.junction_dots[s_g.junction_dot_idx] = (center_x, by)
                    layout_result.fanout_relocated_dots.add(s_g.junction_dot_idx)

            # Store fan-out group data for rendering
            # (center vertical + diagonals + side verticals rendered by generator)
            layout_result.fanout_groups.append((center_x, by, side_xs))
