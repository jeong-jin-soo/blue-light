"""
SLD Circuit Type Registry and Resolver.

Layer 2 of the 3-Layer Architecture:
  Input Normalizer → Circuit Resolver → Layout Engine

Encodes Singapore electrical domain knowledge:
- Load → Breaker rating mapping (from AI Training Dataset)
- Breaker → Cable size mapping (SS 638 standards)
- Circuit type classification by name keywords
- Smart defaults that fill in missing specifications

Decision rules (confirmed by SG team 2026-03-08):
- Residential Aircon → auto 20A DP ISOLATOR
- Commercial Aircon → MCB unless user specifies ISOLATOR
- Sub-circuit poles in 3-phase DB → always SPN (not TPN)
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass

logger = logging.getLogger(__name__)


# =============================================================
# Circuit Type Definitions
# =============================================================

@dataclass(frozen=True)
class CircuitTypeSpec:
    """Default specifications for a circuit type."""
    type_key: str               # "lighting", "socket", "aircon", "heater", "spare"
    default_rating: int         # Default MCB rating (A)
    default_char: str           # Default trip characteristic ("B", "C")
    default_cable_mm2: float    # Default cable size (mm²)
    default_cpc_mm2: float      # Default CPC size (mm²)
    default_poles: str          # Default poles for sub-circuit ("SPN", "DP")
    default_type: str           # Default breaker type ("MCB", "ISOLATOR")


# AI Logic Rules (from training dataset, confirmed by SG team)
CIRCUIT_TYPE_REGISTRY: dict[str, CircuitTypeSpec] = {
    "lighting": CircuitTypeSpec(
        type_key="lighting",
        default_rating=10, default_char="B",
        default_cable_mm2=1.5, default_cpc_mm2=1.5,
        default_poles="SPN", default_type="MCB",
    ),
    "socket": CircuitTypeSpec(
        type_key="socket",
        default_rating=20, default_char="B",
        default_cable_mm2=2.5, default_cpc_mm2=2.5,
        default_poles="SPN", default_type="MCB",
    ),
    "aircon": CircuitTypeSpec(
        type_key="aircon",
        default_rating=20, default_char="B",
        default_cable_mm2=4.0, default_cpc_mm2=4.0,
        default_poles="SPN", default_type="MCB",
        # Note: Residential → auto ISOLATOR (handled in resolve_circuit)
    ),
    "heater": CircuitTypeSpec(
        type_key="heater",
        default_rating=20, default_char="B",
        default_cable_mm2=2.5, default_cpc_mm2=2.5,
        default_poles="SPN", default_type="MCB",
    ),
    "motor": CircuitTypeSpec(
        type_key="motor",
        default_rating=32, default_char="C",
        default_cable_mm2=6.0, default_cpc_mm2=6.0,
        default_poles="SPN", default_type="MCB",
    ),
    "spare": CircuitTypeSpec(
        type_key="spare",
        default_rating=20, default_char="B",
        default_cable_mm2=0, default_cpc_mm2=0,
        default_poles="SPN", default_type="MCB",
    ),
    # Fallback for unclassified
    "power": CircuitTypeSpec(
        type_key="power",
        default_rating=20, default_char="B",
        default_cable_mm2=2.5, default_cpc_mm2=2.5,
        default_poles="SPN", default_type="MCB",
    ),
}


# Breaker Rating → Cable Size mapping (SS 638 / training dataset)
BREAKER_TO_CABLE: dict[int, float] = {
    5: 1.0,
    6: 1.5,
    10: 1.5,
    13: 1.5,
    15: 1.5,
    16: 2.5,
    20: 2.5,
    25: 4.0,
    32: 6.0,
    40: 10.0,
    63: 16.0,
    80: 25.0,
    100: 35.0,
    125: 50.0,
    150: 70.0,
    200: 95.0,
    250: 120.0,
    315: 185.0,
    400: 240.0,
}


# =============================================================
# Circuit Classification
# =============================================================

# Keyword → circuit type mapping (order matters: first match wins)
_CLASSIFIERS: list[tuple[str, list[str]]] = [
    ("spare",    ["spare", "future", "reserve"]),
    ("lighting", ["light", "lamp", "led", "luminaire", "cove", "signage",
                  "exit light", "emergency"]),
    ("aircon",   ["air con", "aircon", "a/c", "ac unit", "air conditioning",
                  "aircond", "air-con"]),
    ("heater",   ["heater", "water heater", "instant heater", "storage heater",
                  "geyser"]),
    ("motor",    ["motor", "pump", "compressor", "lift", "elevator",
                  "ventilation fan", "exhaust fan"]),
    ("socket",   ["socket", "s/s/o", "outlet", "twin", "single", "sso",
                  "power point", "power outlet", "receptacle"]),
]


def classify_circuit(circuit: dict) -> str:
    """Classify a circuit by its name/load keywords.

    Accepts NormalizedCircuit or plain dict.
    Returns one of: "lighting", "socket", "aircon", "heater", "motor", "spare", "power"
    """
    # Check explicit type field first
    explicit_type = str(circuit.get("type", "") or circuit.get("circuit_type", "") or circuit.get("load_type", "")).lower()
    if explicit_type in CIRCUIT_TYPE_REGISTRY:
        return explicit_type

    # Classify by name/load keywords
    name = str(circuit.get("name", "") or circuit.get("circuit_name", "") or "").lower()
    load = str(circuit.get("load", "") or circuit.get("load_type", "") or "").lower()
    combined = f"{name} {load}"

    for type_key, keywords in _CLASSIFIERS:
        for kw in keywords:
            if kw in combined:
                return type_key

    # Default: "power" (generic)
    return "power"


# =============================================================
# Circuit Resolver
# =============================================================

def resolve_circuit(circuit, premises_type: str = ""):
    """Apply domain rules to fill missing circuit specifications.

    Priority: user explicit value > domain rule > system default.

    Args:
        circuit: NormalizedCircuit or dict (after normalize_circuit).
        premises_type: "residential" or "commercial" (affects ISOLATOR auto-conversion).
    """
    if not hasattr(circuit, "get"):
        return circuit

    circuit_type = classify_circuit(circuit)
    spec = CIRCUIT_TYPE_REGISTRY.get(circuit_type, CIRCUIT_TYPE_REGISTRY["power"])

    # -- Breaker rating --
    if not circuit.get("breaker_rating"):
        circuit["breaker_rating"] = spec.default_rating

    # -- Breaker characteristic --
    if not circuit.get("breaker_characteristic"):
        circuit["breaker_characteristic"] = spec.default_char

    # -- Breaker type --
    if not circuit.get("breaker_type") or circuit["breaker_type"] == "MCB":
        # Residential aircon → auto ISOLATOR (SG decision 2026-03-08)
        is_residential = premises_type.lower().startswith("res") if premises_type else False
        if circuit_type == "aircon" and is_residential:
            circuit.setdefault("breaker_type", "ISOLATOR")
            circuit.setdefault("breaker_poles", "DP")
        else:
            # MCB max 100A per SS 638; >100A sub-breakers → MCCB
            # (Cross-validated with 62 real LEW DWG files: MCB≤100A, MCCB≥125A)
            rating = circuit.get("breaker_rating", 0)
            if isinstance(rating, (int, float)) and rating > 100:
                circuit.setdefault("breaker_type", "MCCB")
            else:
                circuit.setdefault("breaker_type", spec.default_type)

    # -- Breaker poles (sub-circuit default = SPN) --
    if not circuit.get("breaker_poles"):
        # ISOLATOR type gets DP by default
        if circuit.get("breaker_type", "").upper() == "ISOLATOR":
            circuit["breaker_poles"] = "DP"
        else:
            circuit["breaker_poles"] = spec.default_poles

    # -- Cable: auto-fill from breaker rating if missing --
    if not circuit.get("cable") and circuit_type != "spare":
        rating = circuit.get("breaker_rating", 20)
        cable_mm2 = BREAKER_TO_CABLE.get(rating, spec.default_cable_mm2)
        if cable_mm2:
            circuit["cable"] = {
                "cores": 2,
                "size_mm2": str(cable_mm2),
                "type": "PVC",
                "cpc_mm2": str(cable_mm2 if cable_mm2 <= 16 else cable_mm2 * 0.5),
                "cpc_type": "PVC",
                "method": "METAL TRUNKING",
            }

    # -- Cable method default for existing cable dict --
    cable = circuit.get("cable")
    if isinstance(cable, dict):
        cable.setdefault("method", "METAL TRUNKING")

    return circuit
