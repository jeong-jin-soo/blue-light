"""
SLD Circuit Input Normalizer.

Layer 1 of the 3-Layer Architecture:
  Input Normalizer → Circuit Resolver → Layout Engine

Transforms diverse input formats into a standardized NormalizedCircuit
dataclass that the layout engine can consistently consume.

NormalizedCircuit (A3 Architecture Fix):
    Single typed contract for circuit data.  Every consumer reads
    typed attributes (e.g., `circuit.breaker_rating`) instead of
    dict.get() with ad-hoc key fallbacks.  The dataclass also acts
    as a dict (via asdict()) for backward compatibility.

Handles:
- Nested breaker dict → flat keys (breaker_rating, breaker_type, etc.)
- Text MCB spec parsing: "10A SP Type B" → {rating:10, poles:SPN, char:B}
- Cable method defaults (METAL TRUNKING)
- application_info key aliases (drawing_no → drawing_number, contractor split)
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field, asdict
from typing import Any

logger = logging.getLogger(__name__)


# =============================================================
# NormalizedCircuit — single typed contract (A3)
# =============================================================

@dataclass
class NormalizedCircuit:
    """Typed representation of a sub-circuit after input normalization.

    Every consumer (helpers._parse_circuit_data, engine.py, sections.py)
    should read from this dataclass instead of raw dict.get() calls.

    The class supports dict-like access via __getitem__ and .get() for
    backward compatibility during the migration period.

    Field Naming Convention (canonical keys):
        breaker_rating        — NOT 'rating', 'rating_A', 'rating_a'
        breaker_type          — NOT 'type'
        breaker_poles         — NOT 'poles'
        breaker_characteristic — NOT 'char', 'trip_curve', 'breaker_char'
        fault_kA              — NOT 'breaker_ka', 'ka_rating'
        name                  — NOT 'circuit_name', 'description'
    """

    name: str = ""
    breaker_rating: int = 0
    breaker_type: str = "MCB"
    breaker_poles: str = ""
    breaker_characteristic: str = ""
    fault_kA: int = 0
    cable: Any = ""           # str | dict — normalized by resolve_circuit
    phase: str = ""           # L1 | L2 | L3 (normalized from R/Y/B)
    circuit_id: str = ""
    load_kw: float = 0.0
    load_type: str = ""       # lighting | socket | aircon | spare | ...
    # Pass-through: any extra keys from the original dict
    _extra: dict = field(default_factory=dict, repr=False)

    # -- Dict compatibility (migration period) --

    def get(self, key: str, default: Any = None) -> Any:
        """Dict-like .get() for backward compatibility."""
        if hasattr(self, key) and key != "_extra":
            val = getattr(self, key)
            # Return the actual value; only fall back to default if the field
            # is at its empty sentinel AND caller provided a default.
            if val == "" and default is not None:
                return default
            return val
        return self._extra.get(key, default)

    def __getitem__(self, key: str) -> Any:
        if hasattr(self, key) and key != "_extra":
            return getattr(self, key)
        return self._extra[key]

    def __contains__(self, key: str) -> bool:
        """Check if a key exists and has a non-default/empty value.

        Mimics dict behavior: 'key in circuit' is True only if the
        field has been explicitly set to a non-empty value.
        """
        if hasattr(self, key) and key != "_extra":
            val = getattr(self, key)
            # 0 is falsy but valid for breaker_rating=0 meaning "not set"
            if isinstance(val, (int, float)):
                return val != 0
            return bool(val)
        return key in self._extra

    def __setitem__(self, key: str, value: Any) -> None:
        if hasattr(self, key) and key != "_extra":
            object.__setattr__(self, key, value)
        else:
            self._extra[key] = value

    def setdefault(self, key: str, value: Any) -> Any:
        """Dict-like setdefault for backward compatibility."""
        current = self.get(key)
        if current is None or current == "" or current == 0:
            self[key] = value
            return value
        return current

    def to_dict(self) -> dict:
        """Convert to plain dict (for serialization or legacy code)."""
        d = asdict(self)
        extra = d.pop("_extra", {})
        d.update(extra)
        return d


# -- Phase name normalization (R/Y/B → L1/L2/L3) --

_PHASE_ALIAS: dict[str, str] = {
    "R": "L1", "RED": "L1",
    "Y": "L2", "YEL": "L2", "YELLOW": "L2",
    "B": "L3", "BLU": "L3", "BLUE": "L3",
    "L1": "L1", "L2": "L2", "L3": "L3",
}


def normalize_phase_name(phase: str) -> str:
    """Normalize Singapore phase naming conventions to L1/L2/L3.

    Handles: R/Y/B (traditional), RED/YELLOW/BLUE, L1/L2/L3 (IEC).
    Returns the input unchanged if not a recognized phase alias.
    """
    if not phase:
        return phase
    return _PHASE_ALIAS.get(phase.upper().strip(), phase)


def normalize_circuit(raw: dict) -> NormalizedCircuit:
    """Normalize a single sub-circuit dict to a typed NormalizedCircuit.

    Ensures the layout engine always finds typed attributes:
      - breaker_rating (int)
      - breaker_type (str)
      - breaker_characteristic (str)
      - breaker_poles (str)
      - cable (dict with method)

    Priority: explicit flat keys > nested breaker dict > parsed MCB text.
    Never overwrites a value that already exists.

    Returns NormalizedCircuit, which supports dict-like access for
    backward compatibility (circuit.get("key"), circuit["key"]).
    """
    if isinstance(raw, NormalizedCircuit):
        return raw
    if not isinstance(raw, dict):
        return raw  # type: ignore[return-value]  # passthrough for non-dict input

    # -- 1. Flatten nested "breaker" dict --
    breaker = raw.get("breaker", {})
    if isinstance(breaker, dict) and breaker:
        if "rating" in breaker:
            raw.setdefault("breaker_rating", breaker["rating"])
        if "type" in breaker:
            raw.setdefault("breaker_type", breaker["type"])
        char_val = (
            breaker.get("breaker_characteristic")
            or breaker.get("characteristic")
            or breaker.get("breaker_char")
            or breaker.get("char")
            or breaker.get("trip_curve")
        )
        if char_val:
            raw.setdefault("breaker_characteristic", char_val)
        if "poles" in breaker:
            raw.setdefault("breaker_poles", breaker["poles"])
        if "fault_kA" in breaker:
            raw.setdefault("fault_kA", breaker["fault_kA"])

    # -- 2. Parse text-based MCB spec (e.g., "10A SP Type B", "20A DP ISOL") --
    mcb_text = str(raw.get("mcb", "") or raw.get("MCB", "")).strip()
    if mcb_text and not raw.get("breaker_rating"):
        parsed = _parse_mcb_spec(mcb_text)
        for k, v in parsed.items():
            raw.setdefault(k, v)

    # -- 3. Normalize breaker_rating: string "20A" → int 20 --
    br = raw.get("breaker_rating")
    if isinstance(br, str):
        m = re.match(r"(\d+)", br)
        if m:
            raw["breaker_rating"] = int(m.group(1))

    # -- 4. Cable: ensure method default --
    cable = raw.get("cable")
    if isinstance(cable, dict):
        cable.setdefault("method", "METAL TRUNKING")
        cable.setdefault("cpc_type", "PVC")
        size = cable.get("size_mm2", "")
        if isinstance(size, (int, float)):
            cable["size_mm2"] = str(size)
        cpc = cable.get("cpc_mm2", "")
        if isinstance(cpc, (int, float)):
            cable["cpc_mm2"] = str(cpc)

    # -- 5. Normalize breaker_type default --
    raw.setdefault("breaker_type", "MCB")

    # -- 6. Normalize phase name (R/Y/B → L1/L2/L3) --
    phase = raw.get("phase")
    if isinstance(phase, str) and phase:
        raw["phase"] = normalize_phase_name(phase)

    # -- 7. Name alias resolution --
    name = str(raw.get("name", "") or raw.get("circuit_name", "") or raw.get("description", ""))

    # -- 8. Characteristic alias resolution --
    breaker_char = str(
        raw.get("breaker_characteristic", "")
        or raw.get("breaker_char", "")
        or raw.get("characteristic", "")
        or raw.get("char", "")
        or raw.get("trip_curve", "")
    )

    # -- 9. Fault kA alias resolution --
    fault_kA_val = raw.get("fault_kA") or raw.get("breaker_ka") or raw.get("ka_rating") or 0
    if isinstance(fault_kA_val, str):
        m = re.match(r"(\d+)", fault_kA_val)
        fault_kA_val = int(m.group(1)) if m else 0

    # -- Collect known fields into NormalizedCircuit --
    _KNOWN_KEYS = {
        "name", "circuit_name", "description",
        "breaker_rating", "rating", "rating_A", "rating_a",
        "breaker_type", "type",
        "breaker_poles", "poles",
        "breaker_characteristic", "breaker_char", "characteristic", "char", "trip_curve",
        "fault_kA", "breaker_ka", "ka_rating",
        "cable", "cable_size", "cable_type", "cable_cores", "wiring_method",
        "phase", "circuit_id", "load_kw", "load_type",
        "breaker", "mcb", "MCB",
    }
    extra = {k: v for k, v in raw.items() if k not in _KNOWN_KEYS}

    return NormalizedCircuit(
        name=name,
        breaker_rating=int(raw.get("breaker_rating", 0) or 0),
        breaker_type=str(raw.get("breaker_type", "MCB")).upper(),
        breaker_poles=str(raw.get("breaker_poles", "")),
        breaker_characteristic=breaker_char.upper(),
        fault_kA=int(fault_kA_val),
        cable=raw.get("cable", ""),
        phase=str(raw.get("phase", "")),
        circuit_id=str(raw.get("circuit_id", "")),
        load_kw=float(raw.get("load_kw", 0) or 0),
        load_type=str(raw.get("load_type", "")),
        _extra=extra,
    )


def normalize_application_info(raw: dict) -> dict:
    """Normalize application_info keys for title block rendering.

    Handles:
    - drawing_no → drawing_number
    - electrical_contractor with embedded address → split into name + addr
    - Various contractor key aliases
    """
    if not isinstance(raw, dict):
        return {}

    result = dict(raw)  # Shallow copy to avoid mutating original

    # -- drawing_no → drawing_number --
    if "drawing_no" in result and "drawing_number" not in result:
        result["drawing_number"] = result["drawing_no"]

    # -- electrical_contractor with newlines → split name/address --
    ec = result.get("electrical_contractor", "")
    if ec and "\n" in ec:
        lines = [l.strip() for l in ec.split("\n") if l.strip()]
        if lines:
            # First line = company name, rest = address
            result.setdefault("elec_contractor", lines[0])
            if len(lines) > 1:
                result.setdefault("elec_contractor_addr", "\n".join(lines[1:]))
    elif ec and "\\P" in ec:
        lines = [l.strip() for l in ec.split("\\P") if l.strip()]
        if lines:
            result.setdefault("elec_contractor", lines[0])
            if len(lines) > 1:
                result.setdefault("elec_contractor_addr", "\n".join(lines[1:]))

    # -- main_contractor aliases --
    if "main_contractor" in result and "contractor_name" not in result:
        result.setdefault("contractor_name", result["main_contractor"])

    return result


def _parse_mcb_spec(text: str) -> dict:
    """Parse a text-based MCB specification string.

    Examples:
        "10A SP Type B"   → {breaker_rating: 10, breaker_poles: "SPN", breaker_characteristic: "B"}
        "20A DP ISOL"     → {breaker_rating: 20, breaker_poles: "DP", breaker_type: "ISOLATOR"}
        "63A TPN MCB 10kA" → {breaker_rating: 63, breaker_poles: "TPN", breaker_type: "MCB", fault_kA: 10}
        "32A DP Type C"   → {breaker_rating: 32, breaker_poles: "DP", breaker_characteristic: "C"}
        "Spare"           → {} (no parsing needed)
    """
    result = {}
    text_upper = text.upper().strip()

    if not text_upper or text_upper == "SPARE":
        return result

    # Rating: first number followed by optional "A"
    rating_match = re.match(r"(\d+)\s*A?\b", text_upper)
    if rating_match:
        result["breaker_rating"] = int(rating_match.group(1))

    # Poles: SP/SPN/DP/TPN/4P
    poles_match = re.search(r"\b(SPN|TPN|4P|DP|SP)\b", text_upper)
    if poles_match:
        poles = poles_match.group(1)
        # Normalize SP → SPN (Singapore convention)
        if poles == "SP":
            poles = "SPN"
        result["breaker_poles"] = poles

    # Type: ISOL/ISOLATOR/MCB/MCCB/ACB/RCCB/ELCB
    if re.search(r"\bISO(?:L(?:ATOR)?)?\.?\b", text_upper):
        result["breaker_type"] = "ISOLATOR"
    elif re.search(r"\bMCCB\b", text_upper):
        result["breaker_type"] = "MCCB"
    elif re.search(r"\bACB\b", text_upper):
        result["breaker_type"] = "ACB"
    elif re.search(r"\bRCCB\b", text_upper):
        result["breaker_type"] = "RCCB"
    elif re.search(r"\bELCB\b", text_upper):
        result["breaker_type"] = "ELCB"
    elif re.search(r"\bMCB\b", text_upper):
        result["breaker_type"] = "MCB"

    # Characteristic: Type B/C/D or standalone B/C/D after rating
    char_match = re.search(r"\bTYPE\s+([BCD])\b", text_upper)
    if char_match:
        result["breaker_characteristic"] = char_match.group(1)
    else:
        # Try standalone B/C/D (but not if it's part of "MCB", "ACB", etc.)
        char_match2 = re.search(r"(?<!\w)([BCD])(?!\w)(?!CB)", text_upper)
        if char_match2:
            result["breaker_characteristic"] = char_match2.group(1)

    # Fault kA: e.g., "10kA", "25kA"
    ka_match = re.search(r"(\d+)\s*[kK][aA]", text_upper)
    if ka_match:
        result["fault_kA"] = int(ka_match.group(1))

    return result
