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


def _normalize_load_quantity(text: str) -> str:
    """Normalize quantity prefix per Singapore LEW convention.

    Rules (LEW guide Rule 2):
    - Singular: "1 no." (e.g., "1 no. 20A DP isolator")
    - Plural:   "2 nos." (e.g., "2 nos. 13A twin S/S/O")
    - If quantity is missing, assume 1 unit and prepend "1 no."

    Examples:
        "4 Nos 13A TWIN S/S/O"  → "4 nos. 13A TWIN S/S/O"
        "1 Nos LIGHTS"          → "1 no. LIGHTS"
        "20A DP ISOLATOR"       → "1 no. 20A DP ISOLATOR"
        "SPARE"                 → "SPARE" (unchanged)
    """
    if not text or text.upper() == "SPARE":
        return text

    # Pattern: number + optional unit word (Nos/No/nos/no/pcs/units etc.)
    m = re.match(
        r"^(\d+)\s*(nos?\.?|units?|pcs?|sets?)\s+(.+)$",
        text, re.IGNORECASE,
    )
    if m:
        qty = int(m.group(1))
        rest = m.group(3)
        unit = "no." if qty == 1 else "nos."
        return f"{qty} {unit} {rest}"

    # Number at start without unit word (e.g., "2 LIGHTS")
    m2 = re.match(r"^(\d+)\s+(.+)$", text)
    if m2:
        qty = int(m2.group(1))
        rest = m2.group(2)
        # Don't treat cable-like patterns as quantities (e.g., "2 x 1C ...")
        if rest.lower().startswith("x "):
            return text
        unit = "no." if qty == 1 else "nos."
        return f"{qty} {unit} {rest}"

    # No quantity at all — prepend "1 no."
    return f"1 no. {text}"


def _wrap_label(text: str, max_chars: int = 30, max_lines: int = 2) -> str:
    """Wrap a long label into max_lines lines using \\P separators.

    Splits at word boundaries. If the text exceeds max_lines when wrapped
    at max_chars, recalculates line width to fit all content in max_lines.
    """
    if len(text) <= max_chars or "\\P" in text:
        return text  # Already short or pre-wrapped

    words = text.split()

    def _split_words(limit: int) -> list[str]:
        lines: list[str] = []
        cur = ""
        for w in words:
            if cur and len(cur) + 1 + len(w) > limit:
                lines.append(cur)
                cur = w
            else:
                cur = f"{cur} {w}" if cur else w
        if cur:
            lines.append(cur)
        return lines

    lines = _split_words(max_chars)

    # If more than max_lines, recalculate with wider line width to fit all text
    if len(lines) > max_lines:
        target = len(text) // max_lines + 1
        lines = _split_words(target)

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
    """Get the next standard breaker rating above the given value.

    Uses the authoritative list from ``standards.py`` which covers
    6 A – 3200 A (26 ratings).  Previously this function used a local
    list that capped at 1000 A, silently returning an incorrect value
    for large installations (MCCB/ACB > 1000 A).
    """
    from app.sld.standards import STANDARD_BREAKER_RATINGS

    for r in STANDARD_BREAKER_RATINGS:
        if r >= current:
            return r
    return STANDARD_BREAKER_RATINGS[-1]


def _pad_spares_for_triplets(sub_circuits: list[dict], supply_type: str) -> list[dict]:
    """Auto-pad SPARE circuits to complete 3-phase triplets.

    In a TPN distribution board, every busbar position is allocated to a
    specific phase (L1, L2, L3).  Circuits are grouped in consecutive
    triplets.  When the last group in a section doesn't fill all 3
    positions, the remaining positions become SPARE.

    Reference: 63A TPN SLD 14 — 27-way DB (9 lighting + 18 power).

    Section detection: detects boundary when breaker rating changes
    (e.g., 10A→20A) or circuit category changes (lighting→power/isolator).

    Only applies to three_phase supply; single_phase returns as-is.
    """
    if supply_type != "three_phase" or not sub_circuits:
        return sub_circuits

    # --- Detect section boundaries ---
    # A section boundary occurs when the "type" changes:
    #   lighting → non-lighting, or non-lighting → lighting
    def _is_lighting(c: dict) -> bool:
        name = str(c.get("name", "") or c.get("circuit_name", "")).lower()
        cid = str(c.get("circuit_id", "")).upper()
        if "spare" in name:
            return False  # User-added spare — don't classify
        # Explicit circuit_id with S prefix = lighting
        if re.match(r"^L[123]S", cid):
            return True
        return any(kw in name for kw in ("light", "lamp", "led"))

    def _is_spare(c: dict) -> bool:
        name = str(c.get("name", "") or c.get("circuit_name", "")).lower()
        return "spare" in name

    # Split into sections: consecutive runs of same type (lighting vs non-lighting)
    # User-specified SPAREs at section boundaries are preserved.
    sections: list[list[dict]] = []
    current_section: list[dict] = []
    current_is_lighting: bool | None = None

    for c in sub_circuits:
        if _is_spare(c):
            # Spare belongs to whichever section it's adjacent to
            current_section.append(c)
            continue
        c_lighting = _is_lighting(c)
        if current_is_lighting is None:
            current_is_lighting = c_lighting
        if c_lighting != current_is_lighting:
            # Section boundary — flush
            sections.append(current_section)
            current_section = []
            current_is_lighting = c_lighting
        current_section.append(c)
    if current_section:
        sections.append(current_section)

    # Pad each section to multiple of 3
    result: list[dict] = []
    for section in sections:
        result.extend(section)
        # Find last non-spare circuit's breaker specs for auto-SPARE inheritance
        _last_non_spare: dict = {}
        for c in reversed(section):
            if not _is_spare(c):
                _last_non_spare = c
                break
        non_spare_count = sum(1 for c in section if not _is_spare(c))
        user_spare_count = sum(1 for c in section if _is_spare(c))
        total = non_spare_count + user_spare_count
        remainder = total % 3
        if remainder != 0:
            pad_count = 3 - remainder
            for _ in range(pad_count):
                # Auto-SPAREs inherit breaker specs from last non-spare circuit
                # so they join the same ditto group and don't show a separate
                # breaker annotation (e.g., "32A" default).
                auto_spare: dict = {"name": "SPARE", "_auto_spare": True}
                for key in ("breaker_type", "breaker_rating", "breaker_poles",
                            "fault_kA", "breaker_characteristic", "cable",
                            "cable_size", "cable_type", "cable_cores"):
                    if key in _last_non_spare:
                        auto_spare[key] = _last_non_spare[key]
                result.append(auto_spare)

    return result


# ---------------------------------------------------------------------------
# Circuit ID assignment — regex patterns (module-level for reuse)
# ---------------------------------------------------------------------------
_PHASE_ID_RE = re.compile(r"^L[123]\w+", re.IGNORECASE)  # L1S, L2P1, etc.
_ISOL_ID_RE = re.compile(r"^ISOL\s*\d+", re.IGNORECASE)  # ISOL 1, ISOL2
_SPARE_ID_RE = re.compile(r"^SP\d+$", re.IGNORECASE)      # SP1, SP2


def _categorize_circuit(circuit: dict) -> tuple[str, str | None]:
    """Categorize a single circuit for ID assignment.

    Category detection priority:
      1. Name contains "spare" → spare
      2. breaker_type == "ISOLATOR" or name contains "isol" → isolator
      3. Explicit circuit_id matches L[123]... pattern → user_id
      4. Name matches L[123]... pattern → user_id
      5. Name contains "light"/"lamp"/"led" → lighting
      6. Name contains "heater" → heater
      7. Default → power

    Returns:
        (category, user_id) — category is one of
        "lighting"|"power"|"heater"|"isolator"|"spare"|"user_id".
        user_id is non-None only when the circuit already has a valid ID.
    """
    # Explicit circuit ID from schedule upload (e.g., "L1S1", "ISOL1")
    explicit_cid = str(circuit.get("circuit_id", "") or "").strip()

    name_raw = str(circuit.get("name", "") or circuit.get("circuit_name", "")) or ""
    name_lower = name_raw.lower()
    breaker_type = str(circuit.get("breaker_type", "") or "").upper()
    # Check nested breaker dict too
    breaker_dict = circuit.get("breaker", {})
    if isinstance(breaker_dict, dict) and not breaker_type:
        breaker_type = str(breaker_dict.get("type", "")).upper()

    if "spare" in name_lower:
        return ("spare", None)

    if breaker_type == "ISOLATOR" or "isol" in name_lower:
        # Explicit circuit_id takes priority, then name
        if explicit_cid and _ISOL_ID_RE.match(explicit_cid):
            return ("isolator", explicit_cid)
        if _ISOL_ID_RE.match(name_raw.strip()):
            return ("isolator", name_raw.strip())
        return ("isolator", None)

    if explicit_cid and _PHASE_ID_RE.match(explicit_cid):
        return ("user_id", explicit_cid)

    if _PHASE_ID_RE.match(name_raw.strip()):
        return ("user_id", name_raw.strip())

    if any(kw in name_lower for kw in ("light", "lamp", "led")):
        return ("lighting", None)

    if any(kw in name_lower for kw in ("heater", "water heater", "instant heater", "storage heater")):
        return ("heater", None)

    return ("power", None)


def _infer_section_from_backward_scan(
    circuit_idx: int,
    categories: list[str],
    user_ids: list[str | None],
) -> str:
    """Look backward from *circuit_idx* to infer the section letter.

    Scans preceding circuits for the nearest non-spare/non-isolator category
    (or user_id with recognizable phase prefix) and returns the corresponding
    section type: "lighting" or "power" (default).
    """
    for j in range(circuit_idx - 1, -1, -1):
        if categories[j] in ("lighting", "power", "heater"):
            return categories[j]
        if categories[j] == "user_id" and user_ids[j]:
            # circuit ID 에서 섹션 추론: L1S→lighting, L1P→power
            _uid = user_ids[j].upper()
            if re.match(r"^L[123]S", _uid):
                return "lighting"
            return "power"
    return "power"  # default


def _assign_spare_phase_slot(
    ids: list[str],
    categories: list[str],
    user_ids: list[str | None],
    index: int,
) -> str:
    """Determine the phase slot for a SPARE circuit in three-phase mode.

    Analyses already-assigned IDs to find the next available (phase, num) slot
    in the preceding section (lighting → S, power/heater → P).
    Fills gaps in L1/L2/L3 pattern before advancing to the next number.
    """
    prev_section = _infer_section_from_backward_scan(index, categories, user_ids)
    sec_char = "S" if prev_section == "lighting" else "P"
    _sec_re = re.compile(r"^L([123])" + sec_char + r"(\d+)$", re.IGNORECASE)

    present: set[tuple[int, int]] = set()  # (phase, num) tuples
    for existing_id in ids:
        m = _sec_re.match(existing_id)
        if m:
            present.add((int(m.group(1)), int(m.group(2))))

    max_num = max((n for _, n in present), default=1)

    # Fill missing phases for max_num first, then next number
    for n in range(max_num, max_num + 10):
        for p in [1, 2, 3]:
            if (p, n) not in present:
                return f"L{p}{sec_char}{n}"

    # Fallback (should not happen)
    return f"L1{sec_char}{max_num + 1}"


def _assign_circuit_ids(sub_circuits: list[dict], supply_type: str) -> list[str]:
    """Pre-assign circuit IDs based on Singapore SLD conventions.

    Two-pass algorithm:
      Pass 1 — Categorize each circuit via ``_categorize_circuit``.
      Pass 2 — Assign IDs using per-category counters.

    Counter rules:
      - Single-phase: S (lighting), P (power), H (heater) — P and H share a counter
      - Three-phase: L{1-3}S (lighting round-robin), L{1-3}P (power round-robin), H (heater)
      - ISOLATOR: own counter → "ISOL 1", "ISOL 2"
      - SPARE: in 3-phase, fills next available phase slot via ``_assign_spare_phase_slot``

    Args:
        sub_circuits: list of circuit dicts (keys: name, circuit_id, breaker_type)
        supply_type: "single_phase" or "three_phase"

    Returns:
        list of circuit ID strings, one per input circuit
    """
    ids: list[str] = []

    # First pass: categorize circuits and detect user-provided IDs
    categories: list[str] = []
    user_ids: list[str | None] = []

    for circuit in sub_circuits:
        cat, uid = _categorize_circuit(circuit)
        categories.append(cat)
        user_ids.append(uid)

    # Second pass: assign IDs with per-category counters
    # Note: Heater (H) and Power (P) share the SAME numeric counter.
    # Reference DWG: P1, P2, P3, P4, H5, H6 — heater continues from power count.
    # ISOLATOR has its own counter (ISOL 1, ISOL 2, ...) per DXF reference.
    s_idx = 0     # lighting counter
    ph_idx = 0    # power + heater shared counter
    isol_idx = 0  # isolator counter
    sp_idx = 0    # spare counter

    for i, cat in enumerate(categories):
        # If user already provided a valid circuit ID, use it directly
        if user_ids[i] is not None:
            ids.append(user_ids[i])
            continue

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
                spare_id = _assign_spare_phase_slot(ids, categories, user_ids, i)
                ids.append(spare_id)
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


def _detect_section_breaks(
    circuit_ids: list[str] | None,
    supply_type: str,
) -> list[int]:
    """Detect section boundary indices for extra spacing between groups.

    Returns list of circuit indices where a new section starts.
    In 3-phase TPN DBs, boundaries are detected at triplet level
    (e.g., Lighting→Power transition). For single-phase, boundaries
    are detected at prefix change (e.g., S→P).
    """
    if not circuit_ids or len(circuit_ids) <= 1:
        return []

    breaks: list[int] = []
    if len(circuit_ids) >= 6 and supply_type == "three_phase":
        def _triplet_category(start_idx: int) -> str:
            for k in range(start_idx, min(start_idx + 3, len(circuit_ids))):
                cid = circuit_ids[k].upper()
                m = re.match(r"^L[123]([A-Z])", cid)
                if m:
                    return m.group(1)
            return ""

        prev_cat = _triplet_category(0)
        for t in range(3, len(circuit_ids), 3):
            cur_cat = _triplet_category(t)
            if cur_cat and prev_cat and cur_cat != prev_cat:
                breaks.append(t)
            if cur_cat:
                prev_cat = cur_cat
    else:
        prev_prefix = re.match(r"[A-Za-z]+", circuit_ids[0])
        prev_prefix = prev_prefix.group() if prev_prefix else ""
        for ci in range(1, len(circuit_ids)):
            cur_prefix = re.match(r"[A-Za-z]+", circuit_ids[ci])
            cur_prefix = cur_prefix.group() if cur_prefix else ""
            if cur_prefix != prev_prefix:
                breaks.append(ci)
            prev_prefix = cur_prefix
    return breaks


def _compute_tap_x(
    i: int,
    row_count: int,
    bus_start_x: float,
    bus_end_x: float,
    group_breaks: list[int],
    config: LayoutConfig,
) -> float:
    """Compute busbar tap X position for circuit at index i."""
    bus_width = bus_end_x - bus_start_x
    group_gap = 6.0
    num_breaks = len(group_breaks)

    if row_count == 1:
        tap_x = (bus_start_x + bus_end_x) / 2
    elif row_count > 1:
        usable = bus_width - 2 * config.busbar_margin - num_breaks * group_gap
        base_x = bus_start_x + config.busbar_margin + i * (usable / (row_count - 1))
        breaks_before = sum(1 for b in group_breaks if b <= i)
        tap_x = base_x + breaks_before * group_gap
    else:
        tap_x = bus_start_x + config.busbar_margin

    return max(config.min_x + 20, min(tap_x, config.max_x - 20))


def _parse_circuit_data(circuit: dict, supply_type: str) -> dict:
    """Parse circuit dict into normalized fields for placement.

    Returns dict with keys: name, breaker_type, breaker_rating, poles,
    cable, load_kw, load_info, fault_kA, breaker_char, cb_w, cb_h.
    """
    sc_name = str(circuit.get("name", "") or circuit.get("circuit_name", ""))

    # Poles
    sc_breaker_poles_raw = circuit.get("breaker_poles")
    if sc_breaker_poles_raw:
        sc_poles_from_data = str(sc_breaker_poles_raw)
        if sc_poles_from_data.isdigit():
            sc_poles = {1: "SP", 2: "DP", 3: "TPN", 4: "4P"}.get(int(sc_poles_from_data), "SP")
        else:
            sc_poles = sc_poles_from_data
    else:
        sc_poles = _get_circuit_poles(circuit, supply_type)

    # Breaker type and rating
    sc_breaker_type = str(circuit.get("breaker_type", "MCB")).upper()
    sc_breaker_rating_raw = circuit.get("breaker_rating", 32)
    if isinstance(sc_breaker_rating_raw, str):
        m = re.match(r"(\d+)", sc_breaker_rating_raw)
        sc_breaker_rating = int(m.group(1)) if m else 32
    else:
        sc_breaker_rating = sc_breaker_rating_raw

    # Cable spec
    sc_cable_raw = circuit.get("cable", "")
    if not sc_cable_raw and circuit.get("cable_size"):
        sc_cable_raw = {
            "size_mm2": circuit.get("cable_size", "").replace("mm2", ""),
            "type": circuit.get("cable_type", "PVC"),
            "cores": circuit.get("cable_cores", "2C").replace("C", ""),
            "method": circuit.get("wiring_method", ""),
        }
    sc_cable = format_cable_spec(sc_cable_raw)

    # Load current
    sc_load_kw = circuit.get("load_kw", 0)
    load_info = ""
    if sc_load_kw and sc_load_kw > 0:
        is_three_phase = sc_poles in ("TPN", "4P")
        if is_three_phase:
            current = round(sc_load_kw * 1000 / (400 * 1.732), 1)
        else:
            current = round(sc_load_kw * 1000 / 230, 1)
        load_info = f"{sc_load_kw}kW / {current}A"

    # Fault kA and characteristic
    sc_fault_kA = _get_circuit_fault_kA(sc_breaker_type, circuit)
    sc_breaker_char = str(
        circuit.get("breaker_characteristic", "")
        or circuit.get("breaker_char", "")
    ).upper()

    return {
        "name": sc_name,
        "breaker_type": sc_breaker_type,
        "breaker_rating": sc_breaker_rating,
        "poles": sc_poles,
        "cable": sc_cable,
        "load_info": load_info,
        "fault_kA": sc_fault_kA,
        "breaker_char": sc_breaker_char,
    }


def _get_breaker_dimensions(breaker_type: str, config: LayoutConfig) -> tuple[float, float]:
    """Return (width, height) for a breaker type from config."""
    if breaker_type in ("RCCB", "ELCB"):
        return config.rccb_w, config.rccb_h
    if breaker_type in ("MCCB", "ACB"):
        return config.breaker_w, config.breaker_h
    return config.mcb_w, config.mcb_h


def _build_display_label(circuit: dict, sc_name: str, conductor_top_y: float, config: LayoutConfig) -> str:
    """Build display label from load description + room, wrapped for vertical space."""
    sc_load_desc = str(circuit.get("load", "") or circuit.get("load_description", "")).strip()
    sc_room = str(circuit.get("room", "") or circuit.get("location", "") or circuit.get("area", "")).strip()

    if sc_load_desc and sc_load_desc.lower() != "spare":
        sc_display_name = sc_load_desc
    else:
        sc_display_name = sc_name
    sc_display_name = _normalize_load_quantity(sc_display_name)
    if sc_room:
        sc_display_name = f"{sc_display_name} — {sc_room}"

    _CHAR_ADVANCE = 1.7
    _PREFERRED_MAX_CHARS = 25
    label_y = conductor_top_y + 2
    avail_h = config.max_y - label_y
    dyn_max = max(15, min(_PREFERRED_MAX_CHARS, int(avail_h / _CHAR_ADVANCE)))
    return _wrap_label(sc_display_name, max_chars=dyn_max)


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
    group_breaks = _detect_section_breaks(circuit_ids, supply_type)

    for i, circuit in enumerate(row_circuits):
        global_idx = row_idx * config.max_circuits_per_row + i
        tap_x = _compute_tap_x(i, row_count, bus_start_x, bus_end_x, group_breaks, config)

        # Circuit ID
        if circuit_ids and global_idx < len(circuit_ids):
            circuit_id = circuit_ids[global_idx]
        else:
            circuit_id = f"C{global_idx + 1}"

        # Circuit ID box at busbar tap
        result.components.append(PlacedComponent(
            symbol_name="CIRCUIT_ID_BOX", x=tap_x, y=busbar_y + 3.5,
            circuit_id=circuit_id, rotation=90.0,
        ))

        # Parse circuit data
        cd = _parse_circuit_data(circuit, supply_type)
        cd["name"] = cd["name"] or f"DB-{global_idx + 1}"
        sc_cb_w, sc_cb_h = _get_breaker_dimensions(cd["breaker_type"], config)

        # Vertical line from busbar to breaker
        sc_y = busbar_y + config.busbar_to_breaker_gap
        result.connections.append(((tap_x, busbar_y), (tap_x, sc_y)))
        result.junction_dots.append((tap_x, busbar_y))

        # Place breaker
        _render_type = "MCB" if cd["breaker_type"] == "ISOLATOR" else cd["breaker_type"]
        result.components.append(PlacedComponent(
            symbol_name=f"CB_{_render_type}",
            x=tap_x - sc_cb_w / 2, y=sc_y,
            label=cd["name"], rating=f"{cd['breaker_rating']}A",
            cable_annotation=cd["cable"], circuit_id=circuit_id,
            load_info=cd["load_info"], rotation=90.0,
            poles=cd["poles"], breaker_type_str=cd["breaker_type"],
            fault_kA=cd["fault_kA"], label_style="breaker_block",
            breaker_characteristic=cd["breaker_char"],
        ))
        result.symbols_used.add(cd["breaker_type"])

        # Conductor tail (extends upward past cable leader line)
        breaker_top_y = sc_y + sc_cb_h + config.stub_len
        _leader_y_from_busbar = (config.db_box_busbar_margin + config.mcb_h
                                 + config.stub_len + config.db_box_tail_margin
                                 + config.db_box_label_margin
                                 + config.leader_margin_above_db)
        _breaker_top_from_busbar = config.busbar_to_breaker_gap + sc_cb_h + config.stub_len
        effective_tail = max(config.tail_length,
                             _leader_y_from_busbar - _breaker_top_from_busbar + 5)
        tail_end_y = breaker_top_y + effective_tail

        # ISOLATOR: extra space for device box
        _ISOL_DEVICE_BOX_H = 3.8
        _isol_extra = _ISOL_DEVICE_BOX_H if cd["breaker_type"] == "ISOLATOR" else 0.0
        conductor_top_y = tail_end_y + _isol_extra
        result.connections.append(((tap_x, breaker_top_y), (tap_x, tail_end_y)))

        # Circuit name label
        display_label = _build_display_label(circuit, cd["name"], conductor_top_y, config)
        result.components.append(PlacedComponent(
            symbol_name="LABEL", x=tap_x, y=conductor_top_y + 2,
            label=display_label, rotation=90.0,
        ))

    # Cable leader lines are added AFTER resolve_overlaps (see _add_cable_leader_lines)
    # because resolve_overlaps changes the sub-circuit tap_x positions.
