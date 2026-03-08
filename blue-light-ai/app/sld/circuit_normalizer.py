"""
SLD Circuit Input Normalizer.

Layer 1 of the 3-Layer Architecture:
  Input Normalizer → Circuit Resolver → Layout Engine

Transforms diverse input formats into a standardized flat dict
that the layout engine can consistently consume.

Handles:
- Nested breaker dict → flat keys (breaker_rating, breaker_type, etc.)
- Text MCB spec parsing: "10A SP Type B" → {rating:10, poles:SPN, char:B}
- Cable method defaults (METAL TRUNKING)
- application_info key aliases (drawing_no → drawing_number, contractor split)
"""

from __future__ import annotations

import logging
import re

logger = logging.getLogger(__name__)


def normalize_circuit(raw: dict) -> dict:
    """Normalize a single sub-circuit dict to flat keys.

    Ensures the layout engine always finds:
      - breaker_rating (int)
      - breaker_type (str)
      - breaker_characteristic (str)
      - breaker_poles (str)
      - cable (dict with method)

    Priority: explicit flat keys > nested breaker dict > parsed MCB text.
    Never overwrites a value that already exists.
    """
    if not isinstance(raw, dict):
        return raw

    # -- 1. Flatten nested "breaker" dict --
    breaker = raw.get("breaker", {})
    if isinstance(breaker, dict) and breaker:
        # Map nested keys to flat keys (setdefault = won't overwrite existing)
        if "rating" in breaker:
            raw.setdefault("breaker_rating", breaker["rating"])
        if "type" in breaker:
            raw.setdefault("breaker_type", breaker["type"])
        # Characteristic: multiple key names in the wild
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
        # Normalize size_mm2 to numeric string
        size = cable.get("size_mm2", "")
        if isinstance(size, (int, float)):
            cable["size_mm2"] = str(size)
        cpc = cable.get("cpc_mm2", "")
        if isinstance(cpc, (int, float)):
            cable["cpc_mm2"] = str(cpc)

    # -- 5. Normalize breaker_type default --
    raw.setdefault("breaker_type", "MCB")

    return raw


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
