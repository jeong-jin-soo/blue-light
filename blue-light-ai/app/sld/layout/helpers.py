"""
SLD Layout helper functions.

Utility functions used by section methods and engine:
- _split_into_rows: row splitting for multi-row layouts
- _next_standard_rating: next standard breaker rating lookup
- _assign_circuit_ids: Singapore SLD circuit ID assignment
- _get_circuit_poles: pole configuration determination
- _get_circuit_fault_kA: fault rating lookup
- _place_sub_circuits_upward: sub-circuit placement (upward from busbar)
"""

from __future__ import annotations

import logging
import re

from app.sld.layout.models import (
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    format_cable_spec,
)
from app.sld.locale import SG_LOCALE

logger = logging.getLogger(__name__)


def _wrap_label(text: str, max_chars: int = 30) -> str:
    """Wrap a long label into multiline using \\P separators.

    Splits at word boundaries to keep each line under max_chars.
    For rotated (90°) vertical text, each \\P line reduces the
    vertical extent at the cost of a small horizontal offset.
    """
    if len(text) <= max_chars or "\\P" in text:
        return text  # Already short or pre-wrapped

    words = text.split()
    lines: list[str] = []
    current_line = ""
    for word in words:
        if current_line and len(current_line) + 1 + len(word) > max_chars:
            lines.append(current_line)
            current_line = word
        else:
            current_line = f"{current_line} {word}" if current_line else word
    if current_line:
        lines.append(current_line)
    return "\\P".join(lines)


def _split_into_rows(sub_circuits: list[dict], max_per_row: int) -> list[list[dict]]:
    """Split sub-circuits into rows of max_per_row each."""
    if not sub_circuits:
        return [[]]
    rows = []
    for i in range(0, len(sub_circuits), max_per_row):
        rows.append(sub_circuits[i:i + max_per_row])
    return rows


def _next_standard_rating(current: int) -> int:
    """Get the next standard breaker rating above the given value."""
    standard = [16, 20, 25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000]
    for r in standard:
        if r >= current:
            return r
    return standard[-1]


def _assign_circuit_ids(sub_circuits: list[dict], supply_type: str) -> list[str]:
    """
    Pre-assign circuit IDs based on Singapore SLD conventions.

    Single-phase: S1, S2 (lighting), P1, P2 (power), H1, H2 (heater),
                  ISOL 1 (isolator), SP1, SP2 (spare)
    Three-phase: L1S1, L2S1, L3S1 (lighting round-robin),
                 L1P1, L2P1, L3P1 (power round-robin),
                 ISOL 1, ISOL 2 (isolator — own counter),
                 SP1, SP2 (spare)

    Heater circuits (water heater, instant heater, storage heater) use "H" prefix
    per Singapore LEW convention (reference DWG: H5, H6 for heater points).

    ISOLATOR circuits use "ISOL N" per DXF reference (63A TPN SLD 14).
    They do NOT participate in the L-phase round-robin counter.
    Detection: breaker_type == "ISOLATOR" or name contains "isol".
    """
    ids: list[str] = []

    # First pass: categorize circuits
    categories: list[str] = []
    for circuit in sub_circuits:
        name_lower = (str(circuit.get("name", "") or circuit.get("circuit_name", "")) or "").lower()
        breaker_type = str(circuit.get("breaker_type", "") or "").upper()
        # Check nested breaker dict too
        breaker_dict = circuit.get("breaker", {})
        if isinstance(breaker_dict, dict) and not breaker_type:
            breaker_type = str(breaker_dict.get("type", "")).upper()

        if "spare" in name_lower:
            categories.append("spare")
        elif breaker_type == "ISOLATOR" or "isol" in name_lower:
            categories.append("isolator")
        elif any(kw in name_lower for kw in ("light", "lamp", "led")):
            categories.append("lighting")
        elif any(kw in name_lower for kw in ("heater", "water heater", "instant heater", "storage heater")):
            categories.append("heater")
        else:
            categories.append("power")

    # Second pass: assign IDs with per-category counters
    # Note: Heater (H) and Power (P) share the SAME numeric counter.
    # Reference DWG: P1, P2, P3, P4, H5, H6 — heater continues from power count.
    # ISOLATOR has its own counter (ISOL 1, ISOL 2, ...) per DXF reference.
    s_idx = 0     # lighting counter
    ph_idx = 0    # power + heater shared counter
    isol_idx = 0  # isolator counter
    sp_idx = 0    # spare counter

    for cat in categories:
        if supply_type == "single_phase":
            if cat == "spare":
                sp_idx += 1
                ids.append(f"SP{sp_idx}")
            elif cat == "isolator":
                isol_idx += 1
                ids.append(f"ISOL {isol_idx}")
            elif cat == "lighting":
                s_idx += 1
                ids.append(f"S{s_idx}")
            elif cat == "heater":
                ph_idx += 1
                ids.append(f"H{ph_idx}")
            else:  # power
                ph_idx += 1
                ids.append(f"P{ph_idx}")
        else:  # three_phase — round-robin phase distribution
            if cat == "spare":
                sp_idx += 1
                ids.append(f"SP{sp_idx}")
            elif cat == "isolator":
                isol_idx += 1
                ids.append(f"ISOL {isol_idx}")
            elif cat == "lighting":
                phase = (s_idx % 3) + 1
                num = (s_idx // 3) + 1
                ids.append(f"L{phase}S{num}")
                s_idx += 1
            elif cat == "heater":
                ph_idx += 1
                ids.append(f"H{ph_idx}")
            else:  # power
                phase = (ph_idx % 3) + 1
                num = (ph_idx // 3) + 1
                ids.append(f"L{phase}P{num}")
                ph_idx += 1

    return ids


def _get_circuit_poles(circuit: dict, supply_type: str) -> str:
    """Determine pole configuration for sub-circuit.

    Singapore SLD convention (per professional LEW reference DWG):
    - SPN (Single Pole + Neutral) for single-phase sub-circuits
    - TPN (Triple Pole + Neutral) for three-phase sub-circuits

    Note: Reference DWGs consistently use "SPN" for residential single-phase
    MCBs. The neutral conductor is terminated at the MCB/DB, making "SPN"
    (not just "SP") the correct designation.
    """
    phase = circuit.get("phase", "")
    if phase:
        phase_lower = phase.lower()
        if "single" in phase_lower or "1" in phase_lower:
            return "SPN"
        if "three" in phase_lower or "3" in phase_lower:
            return "TPN"
    # Sub-circuits are ALWAYS single-pole (SPN) regardless of supply type.
    # In a 3-phase TPN DB, each sub-circuit connects to ONE phase.
    # TPN is only for the main breaker, not sub-circuit MCBs.
    # (Confirmed by SG LEW reference DWG: sub-circuits show SPN/SP even in TPN DB)
    return "SPN"


def _get_circuit_fault_kA(breaker_type: str, circuit: dict | None = None) -> int:
    """Get fault rating for sub-circuit breaker.

    MCB sub-circuits: ALWAYS 6kA per Singapore standard (SS 638).
    - Main MCBs use 10kA (FAULT_LEVEL_DEFAULTS), but sub-circuit MCBs are 6kA.
    - Gemini often sends 10kA for sub-circuits (confusing main/sub defaults),
      so we enforce the correct value for MCBs regardless of explicit input.

    MCCB/ACB sub-circuits: use explicit value if provided, else defaults.
    """
    from app.sld.standards import SUB_CIRCUIT_FAULT_DEFAULTS
    bt = breaker_type.upper()

    # MCB sub-circuits: always 6kA — no override allowed
    if bt == "MCB":
        return SUB_CIRCUIT_FAULT_DEFAULTS.get("MCB", 6)

    # MCCB/ACB: respect explicit user value if provided
    if circuit:
        user_kA = circuit.get("fault_kA", 0)
        if user_kA:
            return int(user_kA)
    return SUB_CIRCUIT_FAULT_DEFAULTS.get(bt, 6)


def _place_sub_circuits_upward(
    result: LayoutResult,
    row_circuits: list[dict],
    row_idx: int,
    row_count: int,
    busbar_y: float,
    bus_start_x: float,
    bus_end_x: float,
    h_spacing: float,
    config: LayoutConfig,
    all_circuits: list[dict],
    supply_type: str = "three_phase",
    circuit_ids: list[str] | None = None,
) -> None:
    """Place a row of sub-circuits branching UPWARD from busbar with vertical labels."""
    bus_width = bus_end_x - bus_start_x

    # -- Detect category group boundaries (S→P, P→H, etc.) for extra spacing --
    group_gap = 6.0  # Extra mm between circuit category groups
    group_breaks: list[int] = []  # Indices where a new category starts
    if circuit_ids and len(circuit_ids) > 1:
        prev_prefix = re.match(r"[A-Za-z]+", circuit_ids[0])
        prev_prefix = prev_prefix.group() if prev_prefix else ""
        for ci in range(1, len(circuit_ids)):
            cur_prefix = re.match(r"[A-Za-z]+", circuit_ids[ci])
            cur_prefix = cur_prefix.group() if cur_prefix else ""
            if cur_prefix != prev_prefix:
                group_breaks.append(ci)
            prev_prefix = cur_prefix
    num_breaks = len(group_breaks)

    for i, circuit in enumerate(row_circuits):
        global_idx = row_idx * config.max_circuits_per_row + i

        # Calculate tap point on busbar (with group gap offsets)
        if row_count == 1:
            tap_x = (bus_start_x + bus_end_x) / 2
        elif row_count > 1:
            usable = bus_width - 2 * config.busbar_margin - num_breaks * group_gap
            base_x = bus_start_x + config.busbar_margin + i * (usable / (row_count - 1))
            # Add cumulative group gap for each break before this circuit
            breaks_before = sum(1 for b in group_breaks if b <= i)
            tap_x = base_x + breaks_before * group_gap
        else:
            tap_x = bus_start_x + config.busbar_margin

        tap_x = max(tap_x, config.min_x + 20)
        tap_x = min(tap_x, config.max_x - 20)

        # Sub-circuit info
        sc_name = str(circuit.get("name", "") or circuit.get("circuit_name", f"DB-{global_idx + 1}"))

        # Look up pre-assigned circuit ID
        if circuit_ids and global_idx < len(circuit_ids):
            circuit_id = circuit_ids[global_idx]
        else:
            circuit_id = f"C{global_idx + 1}"

        # Circuit ID box at busbar tap point (small rectangle with ID text)
        result.components.append(PlacedComponent(
            symbol_name="CIRCUIT_ID_BOX",
            x=tap_x,
            y=busbar_y + 2,
            circuit_id=circuit_id,
        ))

        # -- Spare circuit: no breaker, just empty tap + "SPARE" label --
        if "spare" in sc_name.lower():
            # Short vertical line from busbar (upward, past circuit ID box)
            spare_top_y = busbar_y + 15
            result.connections.append(((tap_x, busbar_y), (tap_x, spare_top_y)))
            result.junction_dots.append((tap_x, busbar_y))
            # "SPARE" label (vertical text, above the tap)
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=tap_x,
                y=spare_top_y + 2,
                label=SG_LOCALE.circuit.spare,
                rotation=90.0,
            ))
            continue

        # Determine poles early (needed for conductor count tick marks and breaker)
        sc_breaker_poles_raw = circuit.get("breaker_poles")
        if sc_breaker_poles_raw:
            sc_poles_from_data = str(sc_breaker_poles_raw)
            if sc_poles_from_data.isdigit():
                sc_poles = {1: "SP", 2: "DP", 3: "TPN", 4: "4P"}.get(int(sc_poles_from_data), "SP")
            else:
                sc_poles = sc_poles_from_data
        else:
            sc_poles = _get_circuit_poles(circuit, supply_type)

        # Vertical line UP from busbar to breaker
        sc_y = busbar_y + config.busbar_to_breaker_gap  # Above busbar (past circuit ID box)

        # Vertical drop from busbar (upward)
        result.connections.append(((tap_x, busbar_y), (tap_x, sc_y)))
        result.junction_dots.append((tap_x, busbar_y))

        # Sub-circuit breaker info
        sc_breaker_type = str(circuit.get("breaker_type", "MCB")).upper()
        sc_breaker_rating_raw = circuit.get("breaker_rating", 32)
        # Parse string ratings like "20A" → 20
        if isinstance(sc_breaker_rating_raw, str):
            m = re.match(r"(\d+)", sc_breaker_rating_raw)
            sc_breaker_rating = int(m.group(1)) if m else 32
        else:
            sc_breaker_rating = sc_breaker_rating_raw
        # Accept both "cable" (dict/str) and separate "cable_size"/"cable_type"/"cable_cores"
        sc_cable_raw = circuit.get("cable", "")
        if not sc_cable_raw and circuit.get("cable_size"):
            sc_cable_raw = {
                "size_mm2": circuit.get("cable_size", "").replace("mm2", ""),
                "type": circuit.get("cable_type", "PVC"),
                "cores": circuit.get("cable_cores", "2C").replace("C", ""),
                "method": circuit.get("wiring_method", ""),
            }
        sc_cable = format_cable_spec(sc_cable_raw)
        sc_load_kw = circuit.get("load_kw", 0)
        sc_phase = circuit.get("phase", "")

        # Determine breaker dimensions — synced with real_symbol_paths.json
        if sc_breaker_type in ("RCCB", "ELCB"):
            sc_cb_w = config.rccb_w
            sc_cb_h = config.rccb_h
        elif sc_breaker_type in ("MCCB", "ACB"):
            sc_cb_w = config.breaker_w
            sc_cb_h = config.breaker_h
        else:
            sc_cb_w = config.mcb_w
            sc_cb_h = config.mcb_h

        # Load current calculation
        load_info = ""
        if sc_load_kw and sc_load_kw > 0:
            # Single-phase (SPN/SP/DP): I = P / V (230V)
            # Three-phase (TPN/4P): I = P / (V × √3) (400V × 1.732)
            is_three_phase = sc_poles in ("TPN", "4P")
            if is_three_phase:
                current = round(sc_load_kw * 1000 / (400 * 1.732), 1)
            else:
                current = round(sc_load_kw * 1000 / 230, 1)
            load_info = f"{sc_load_kw}kW / {current}A"

        # Determine fault kA and breaker characteristic
        sc_fault_kA = _get_circuit_fault_kA(sc_breaker_type, circuit)
        sc_breaker_char = str(
            circuit.get("breaker_characteristic", "")
            or circuit.get("breaker_char", "")
        ).upper()

        cb_sym = f"CB_{sc_breaker_type}"
        result.components.append(PlacedComponent(
            symbol_name=cb_sym,
            x=tap_x - sc_cb_w / 2,
            y=sc_y,
            label=sc_name,
            rating=f"{sc_breaker_rating}A",
            cable_annotation=sc_cable,
            circuit_id=circuit_id,
            load_info=load_info,
            rotation=90.0,  # Vertical text (matches real Singapore SLD)
            # LEW-style breaker block fields
            poles=sc_poles,
            breaker_type_str=sc_breaker_type,
            fault_kA=sc_fault_kA,
            label_style="breaker_block",
            breaker_characteristic=sc_breaker_char,
        ))
        result.symbols_used.add(sc_breaker_type)

        # Tail from breaker top (extending upward)
        breaker_top_y = sc_y + sc_cb_h + config.stub_len
        tail_end_y = breaker_top_y + config.tail_length  # Conductor tail above breaker

        # Connection from breaker top to tail end
        result.connections.append(((tap_x, breaker_top_y), (tap_x, tail_end_y)))

        # Circuit name label (vertical text, above the tail)
        # Circuit ID is already shown in the CIRCUIT_ID_BOX at the busbar tap
        # Build display label: load description + room info (reference DWG pattern)
        # e.g., "2 Nos 13A TWIN S/S/O — BEDROOM 1"
        sc_load_desc = str(circuit.get("load", "") or circuit.get("load_description", "")).strip()
        sc_room = str(circuit.get("room", "") or circuit.get("location", "") or circuit.get("area", "")).strip()

        # Priority: load description > circuit name
        # Reference DWG shows load descriptions (e.g., "1 Nos LIGHTS") above circuits
        if sc_load_desc and sc_load_desc.lower() != "spare":
            sc_display_name = sc_load_desc
        else:
            sc_display_name = sc_name
        if sc_room:
            # Append room as suffix with em dash separator
            sc_display_name = f"{sc_display_name} — {sc_room}"
        # Wrap long labels into multiline (\\P) to fit vertical space
        # Dynamically compute max_chars based on available height above label
        _CHAR_ADVANCE = 1.7  # approx mm per character for char_height 2.8
        label_y = tail_end_y + 2
        avail_h = config.max_y - label_y
        dyn_max = max(15, int(avail_h / _CHAR_ADVANCE))
        sc_display_name = _wrap_label(sc_display_name, max_chars=dyn_max)
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=tap_x,
            y=tail_end_y + 2,
            label=sc_display_name,
            rotation=90.0,
        ))

    # Cable leader lines are added AFTER resolve_overlaps (see _add_cable_leader_lines)
    # because resolve_overlaps changes the sub-circuit tap_x positions.
