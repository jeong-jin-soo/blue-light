"""
SLD Data Extraction Schema & Validation Pipeline.

Extracts structured JSON from user-provided text/photo descriptions of electrical
installations, validates against Singapore standards (SS 638 / CP 5), and converts
to the format expected by the SLD generation engine.

SLD Drawing Information.pdf naming conventions (A–K labels):
  A: Main Breaker (type/rating/poles/kA/characteristic)
  B: Incoming Cable (size/type/cores)
  C: BI Connector
  D: Indicator Lights (L1/L2/L3)
  E: Earth Protection
  F: Metering Section (Isolator, CT, kWh, MCB, ELR/EFR, ST)
  G: ELCB/RCCB
  H: Busbar (COMB BAR)
  J: Sub-circuit descriptions (vertical labels)
  K: Outgoing cable specs + isolators
"""

from __future__ import annotations

import json
import logging
from typing import Any, Optional

from pydantic import BaseModel, Field, model_validator

from app.sld.sld_spec import (
    ValidationResult,
    apply_corrections,
    get_full_spec_from_kva,
    lookup_incoming_by_kva,
    lookup_outgoing_cable,
    validate_sld_requirements,
)

logger = logging.getLogger(__name__)


# ─────────────────────────────────────────────────────────────────────
# 1. Pydantic Models — Extraction Output Schema
# ─────────────────────────────────────────────────────────────────────

class BreakerSpec(BaseModel):
    """Breaker specification (main or sub-circuit)."""
    type: Optional[str] = Field(None, description="MCB / MCCB / ACB")
    rating_a: Optional[int] = Field(None, description="Rated current in Amps")
    poles: Optional[str] = Field(None, description="SPN / DP / TPN / 4P")
    ka_rating: Optional[int] = Field(None, description="Fault rating in kA")
    characteristic: Optional[str] = Field(None, description="Trip curve: B / C / D")


class CableSpec(BaseModel):
    """Incoming cable specification."""
    size_mm2: Optional[str] = Field(None, description="Phase conductor size e.g. '50'")
    earth_mm2: Optional[str] = Field(None, description="Earth conductor size e.g. '25'")
    type: Optional[str] = Field(None, description="Cable type: PVC / XLPE / PVC/PVC")
    cores: Optional[str] = Field(None, description="Core config e.g. '4 X 1 CORE'")
    description: Optional[str] = Field(
        None,
        description="Full cable description e.g. '4 x 1C 50mm XLPE CABLE + 25sqmm CPC PVC CABLE'",
    )


class ElcbSpec(BaseModel):
    """ELCB/RCCB specification."""
    type: Optional[str] = Field(None, description="ELCB or RCCB")
    rating_a: Optional[int] = Field(None, description="Rated current in Amps")
    poles: Optional[int] = Field(None, description="Number of poles (2 or 4)")
    sensitivity_ma: Optional[int] = Field(None, description="Sensitivity in mA (30/100/300)")


class BusbarSpec(BaseModel):
    """Busbar specification."""
    rating_a: Optional[int] = Field(None, description="Busbar rating in Amps")
    type: Optional[str] = Field(None, description="COMB or COPPER")


class MeteringSpec(BaseModel):
    """Metering section specification."""
    type: Optional[str] = Field(None, description="ct_meter or sp_meter")
    ct_ratio: Optional[str] = Field(None, description="CT ratio e.g. '100/5A'")
    isolator_rating_a: Optional[int] = Field(None, description="Isolator rating in Amps")
    has_indicator_lights: Optional[bool] = Field(None, description="L1/L2/L3 indicator lights")
    has_elr: Optional[bool] = Field(None, description="ELR (Earth Leakage Relay)")
    has_shunt_trip: Optional[bool] = Field(None, description="Shunt Trip device")
    # CT metering section details (vertical layout for ≥125A 3-phase)
    protection_ct_ratio: Optional[str] = Field(None, description="Protection CT ratio e.g. '100/5A'")
    protection_ct_class: Optional[str] = Field(None, description="Protection CT class e.g. '5P10 20VA'")
    metering_ct_class: Optional[str] = Field(None, description="Metering CT class e.g. 'CL1 5VA'")
    has_ammeter: Optional[bool] = Field(True, description="Ammeter with selector switch (ASS)")
    has_voltmeter: Optional[bool] = Field(True, description="Voltmeter with selector switch (VSS)")
    elr_spec: Optional[str] = Field(None, description="ELR specification e.g. '0-3A 0.2sec'")
    voltmeter_range: Optional[str] = Field(None, description="Voltmeter range e.g. '0-500V'")
    ammeter_range: Optional[str] = Field(None, description="Ammeter range e.g. '0-500A'")


class IncomingData(BaseModel):
    """Incoming supply section (labels A–H from SLD Drawing Information.pdf)."""
    kva: Optional[float] = Field(None, description="Total load capacity in kVA")
    phase: Optional[str] = Field(None, description="single_phase or three_phase")
    voltage: Optional[int] = Field(None, description="Supply voltage (230 or 400)")
    supply_source: Optional[str] = Field(None, description="sp_powergrid, landlord, or building_riser")
    incoming_label: Optional[str] = Field(None, description="Supply source label for SLD diagram, e.g. 'FROM LANDLORD RISER'")
    main_breaker: Optional[BreakerSpec] = Field(None, description="[A] Main Breaker")
    cable: Optional[CableSpec] = Field(None, description="[B] Incoming Cable")
    elcb: Optional[ElcbSpec] = Field(None, description="[G] ELCB/RCCB")
    busbar: Optional[BusbarSpec] = Field(None, description="[H] Busbar")
    metering: Optional[MeteringSpec] = Field(None, description="[F] Metering Section")
    earth_protection: Optional[bool] = Field(None, description="[E] Earth Protection present")
    outgoing_cable: Optional[CableSpec] = Field(
        None, description="Cable from isolator to DB (if different from incoming cable)"
    )


class OutgoingCircuit(BaseModel):
    """Single outgoing sub-circuit (labels J–K from SLD Drawing Information.pdf)."""
    id: Optional[str] = Field(None, description="Circuit ID e.g. L1S1, S1, P1")
    description: Optional[str] = Field(None, description="[J] Load description")
    breaker: Optional[BreakerSpec] = Field(None, description="Sub-circuit breaker")
    cable: Optional[str] = Field(
        None,
        description="[K] Full cable spec e.g. '2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING'",
    )
    qty: Optional[int] = Field(None, description="Quantity of load points")
    load_type: Optional[str] = Field(
        None,
        description="Load category: lighting / power / aircon / spare / motor / other",
    )
    phase: Optional[str] = Field(None, description="Phase assignment: L1 / L2 / L3")


class ClientInfo(BaseModel):
    """Client / project information from the title block."""
    name: Optional[str] = Field(None, description="Client company name")
    address: Optional[str] = Field(None, description="Premises address")
    lew_name: Optional[str] = Field(None, description="Licensed Electrical Worker name")
    lew_licence: Optional[str] = Field(None, description="LEW licence number")
    contractor: Optional[str] = Field(None, description="M&E contractor name")
    main_contractor: Optional[str] = Field(None, description="Main contractor name")


class ProtectionGroupData(BaseModel):
    """Per-phase RCCB protection group (e.g., RCCB L1 with its circuits)."""
    phase: Optional[str] = Field(None, description="Phase: L1 / L2 / L3")
    rccb: Optional[ElcbSpec] = Field(None, description="Per-phase RCCB specification")
    circuits: Optional[list[OutgoingCircuit]] = Field(
        default=None, description="Circuits protected by this RCCB"
    )

    @model_validator(mode="after")
    def _coerce_nulls_to_lists(self) -> "ProtectionGroupData":
        """Gemini sometimes returns null instead of []; coerce to empty list."""
        if self.circuits is None:
            self.circuits = []
        return self


class DistributionBoardData(BaseModel):
    """A single distribution board in a multi-DB installation."""
    name: Optional[str] = Field(None, description="Board name: MSB, DB2, Lighting DB, etc.")
    fed_from: Optional[str] = Field(
        None,
        description="Name of parent board that feeds this DB (e.g., 'MSB'). None = root board.",
    )
    breaker: Optional[BreakerSpec] = Field(None, description="Board main breaker")
    elcb: Optional[ElcbSpec] = Field(None, description="Board-level ELCB/RCCB (if any)")
    busbar: Optional[BusbarSpec] = Field(None, description="Board busbar")
    protection_groups: Optional[list[ProtectionGroupData]] = Field(
        default=None,
        description="Per-phase RCCB groups (e.g., L1/L2/L3 each with own RCCB)",
    )
    outgoing_circuits: Optional[list[OutgoingCircuit]] = Field(
        default=None,
        description="Circuits not in a protection group",
    )
    # B1 fix: 5 fields consumed by engine.py but previously missing from schema
    incoming_breaker: Optional[BreakerSpec] = Field(
        None,
        description="Incoming breaker at the board entry (distinct from outgoing main breaker).",
    )
    feeder_breaker: Optional[BreakerSpec] = Field(
        None,
        description="Feeder breaker on the parent board side that feeds this DB.",
    )
    feeder_cable: Optional[CableSpec] = Field(
        None,
        description="Cable connecting parent board to this DB.",
    )
    main_mcb: Optional[BreakerSpec] = Field(
        None,
        description="Main MCB if distinct from the incoming breaker (e.g., post-isolator MCB).",
    )
    meter_board: Optional[str] = Field(
        None,
        description="Meter board label/type (e.g., 'SP METER BOARD', 'CT METER BOARD').",
    )

    @model_validator(mode="after")
    def _coerce_nulls_to_lists(self) -> "DistributionBoardData":
        """Gemini sometimes returns null instead of []; coerce to empty list."""
        if self.protection_groups is None:
            self.protection_groups = []
        if self.outgoing_circuits is None:
            self.outgoing_circuits = []
        return self


class SldExtractedData(BaseModel):
    """Complete SLD extraction output schema."""
    incoming: Optional[IncomingData] = None
    outgoing_circuits: Optional[list[OutgoingCircuit]] = Field(default=None)
    distribution_boards: Optional[list[DistributionBoardData]] = Field(
        default=None,
        description="Multi-DB: list of distribution boards (empty = single-DB mode)",
    )
    client_info: Optional[ClientInfo] = None

    @model_validator(mode="after")
    def _coerce_nulls_to_lists(self) -> "SldExtractedData":
        """Gemini sometimes returns null instead of []; coerce to empty list."""
        if self.outgoing_circuits is None:
            self.outgoing_circuits = []
        if self.distribution_boards is None:
            self.distribution_boards = []
        return self


# ─────────────────────────────────────────────────────────────────────
# 2. Gemini Extraction Prompt
# ─────────────────────────────────────────────────────────────────────

SLD_EXTRACTION_PROMPT = """You are an expert electrical diagram data analyst specializing in Singapore Single Line Diagrams (SLD).

Your task: Analyze the user's text or photo information and extract structured JSON data following the schema below.

## SLD Component Naming (A–K Labels per SLD Drawing Information.pdf)
- A: Main Breaker (type/rating/poles/kA/trip characteristic)
- B: Incoming Cable (conductor size/type/core configuration)
- C: BI Connector (Busbar Inspection connector)
- D: Indicator Lights (L1/L2/L3 phase indicators)
- E: Earth Protection (earth bar + conductor)
- F: Metering Section (Isolator, CT, kWh meter, MCB, ELR/EFR, Shunt Trip)
- G: ELCB/RCCB (Earth Leakage Circuit Breaker / Residual Current Circuit Breaker)
- H: Busbar (COMB BAR or tinned copper busbar)
- J: Sub-circuit descriptions (load names, vertical text labels)
- K: Outgoing cable specifications + sub-circuit isolators

## Singapore Electrical Standards (SS 638:2018, CP 5:2018)
- Single-phase: 230V, poles = DP/SPN, breaker ≤ 100A
- Three-phase: 400V, poles = TPN/4P, breaker ≥ 32A
- MCB: ≤ 100A, MCCB: 125–630A, ACB: > 630A
- Sub-circuit MCB fault rating: 6kA (Singapore standard)
- Main breaker MCB fault rating: 10kA
- MCCB fault rating: 35kA, ACB fault rating: 50kA
- ELCB/RCCB: mandatory per SS 638
- CT metering: required for ≥ 125A three-phase (≥ 86kVA). Single-phase uses sp_meter at all ratings (32A-100A)
- Cable types: PVC (small), PVC/PVC, XLPE/PVC (medium-large)

## Output JSON Schema

```json
{
  "incoming": {
    "kva": <float or null>,
    "phase": "<single_phase|three_phase or null>",
    "voltage": <230|400 or null>,
    "main_breaker": {
      "type": "<MCB|MCCB|ACB or null>",
      "rating_a": <int or null>,
      "poles": "<SPN|DP|TPN|4P or null>",
      "ka_rating": <int or null>,
      "characteristic": "<B|C|D or null>"
    },
    "cable": {
      "size_mm2": "<string or null>",
      "earth_mm2": "<string or null>",
      "type": "<PVC|XLPE|PVC/PVC|XLPE/PVC or null>",
      "cores": "<string e.g. '4 X 1 CORE' or null>",
      "description": "<full cable text or null>"
    },
    "elcb": {
      "type": "<ELCB|RCCB or null>",
      "rating_a": <int or null>,
      "poles": <2|4 or null>,
      "sensitivity_ma": <30|100|300 or null>
    },
    "busbar": {
      "rating_a": <int or null>,
      "type": "<COMB|COPPER or null>"
    },
    "metering": {
      "type": "<ct_meter|sp_meter or null>",
      "ct_ratio": "<string e.g. '100/5A' or null>",
      "isolator_rating_a": <int or null>,
      "has_indicator_lights": <bool or null>,
      "has_elr": <bool or null>,
      "has_shunt_trip": <bool or null>
    },
    "earth_protection": <bool or null>
  },
  "outgoing_circuits": [
    {
      "id": "<circuit ID e.g. L1S1, S1 or null>",
      "description": "<load description e.g. 'LED PANEL LIGHT'>",
      "breaker": {
        "type": "<MCB|MCCB or null>",
        "rating_a": <int or null>,
        "poles": "<SPN|DP|TPN or null>",
        "ka_rating": <int or null>,
        "characteristic": "<B|C|D or null>"
      },
      "cable": "<full cable spec string or null>",
      "qty": <int or null>,
      "load_type": "<lighting|power|aircon|spare|motor|other or null>"
    }
  ],
  "client_info": {
    "name": "<company name or null>",
    "address": "<premises address or null>",
    "lew_name": "<LEW name or null>",
    "lew_licence": "<licence number or null>",
    "contractor": "<M&E contractor or null>",
    "main_contractor": "<main contractor or null>"
  }
}
```

## Rules
1. Extract ONLY information explicitly present in the user's input.
2. Use `null` for any field that is uncertain or not mentioned.
3. Normalize breaker types: "MCB", "MCCB", "ACB" (uppercase).
4. Normalize phase: "single_phase" or "three_phase".
5. Normalize poles: "SPN", "DP", "TPN", "4P" (uppercase).
6. For load_type, classify based on description keywords:
   - lighting/light/lamp/LED → "lighting"
   - socket/power/outlet → "power"
   - aircon/AC/air-con → "aircon"
   - spare → "spare"
   - motor/pump/compressor → "motor"
   - everything else → "other"
7. If kVA is not stated but can be calculated from breaker rating × voltage, calculate it.
8. Output ONLY valid JSON — no markdown, no explanation, no code fences."""


# JSON schema for Gemini API structured output
SLD_OUTPUT_JSON_SCHEMA = {
    "type": "object",
    "properties": {
        "incoming": {
            "type": "object",
            "properties": {
                "kva": {"type": ["number", "null"]},
                "phase": {"type": ["string", "null"]},
                "voltage": {"type": ["integer", "null"]},
                "main_breaker": {
                    "type": "object",
                    "properties": {
                        "type": {"type": ["string", "null"]},
                        "rating_a": {"type": ["integer", "null"]},
                        "poles": {"type": ["string", "null"]},
                        "ka_rating": {"type": ["integer", "null"]},
                        "characteristic": {"type": ["string", "null"]},
                    },
                },
                "cable": {
                    "type": "object",
                    "properties": {
                        "size_mm2": {"type": ["string", "null"]},
                        "earth_mm2": {"type": ["string", "null"]},
                        "type": {"type": ["string", "null"]},
                        "cores": {"type": ["string", "null"]},
                        "description": {"type": ["string", "null"]},
                    },
                },
                "elcb": {
                    "type": "object",
                    "properties": {
                        "type": {"type": ["string", "null"]},
                        "rating_a": {"type": ["integer", "null"]},
                        "poles": {"type": ["integer", "null"]},
                        "sensitivity_ma": {"type": ["integer", "null"]},
                    },
                },
                "busbar": {
                    "type": "object",
                    "properties": {
                        "rating_a": {"type": ["integer", "null"]},
                        "type": {"type": ["string", "null"]},
                    },
                },
                "metering": {
                    "type": "object",
                    "properties": {
                        "type": {"type": ["string", "null"]},
                        "ct_ratio": {"type": ["string", "null"]},
                        "isolator_rating_a": {"type": ["integer", "null"]},
                        "has_indicator_lights": {"type": ["boolean", "null"]},
                        "has_elr": {"type": ["boolean", "null"]},
                        "has_shunt_trip": {"type": ["boolean", "null"]},
                    },
                },
                "earth_protection": {"type": ["boolean", "null"]},
            },
        },
        "outgoing_circuits": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": ["string", "null"]},
                    "description": {"type": ["string", "null"]},
                    "breaker": {
                        "type": "object",
                        "properties": {
                            "type": {"type": ["string", "null"]},
                            "rating_a": {"type": ["integer", "null"]},
                            "poles": {"type": ["string", "null"]},
                            "ka_rating": {"type": ["integer", "null"]},
                            "characteristic": {"type": ["string", "null"]},
                        },
                    },
                    "cable": {"type": ["string", "null"]},
                    "qty": {"type": ["integer", "null"]},
                    "load_type": {"type": ["string", "null"]},
                    "phase": {"type": ["string", "null"]},
                },
            },
        },
        "distribution_boards": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "name": {"type": ["string", "null"]},
                    "fed_from": {"type": ["string", "null"]},
                    "breaker": {
                        "type": "object",
                        "properties": {
                            "type": {"type": ["string", "null"]},
                            "rating_a": {"type": ["integer", "null"]},
                            "poles": {"type": ["string", "null"]},
                            "ka_rating": {"type": ["integer", "null"]},
                            "characteristic": {"type": ["string", "null"]},
                        },
                    },
                    "elcb": {
                        "type": "object",
                        "properties": {
                            "type": {"type": ["string", "null"]},
                            "rating_a": {"type": ["integer", "null"]},
                            "poles": {"type": ["integer", "null"]},
                            "sensitivity_ma": {"type": ["integer", "null"]},
                        },
                    },
                    "busbar": {
                        "type": "object",
                        "properties": {
                            "rating_a": {"type": ["integer", "null"]},
                            "type": {"type": ["string", "null"]},
                        },
                    },
                    "protection_groups": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "phase": {"type": ["string", "null"]},
                                "rccb": {
                                    "type": "object",
                                    "properties": {
                                        "type": {"type": ["string", "null"]},
                                        "rating_a": {"type": ["integer", "null"]},
                                        "poles": {"type": ["integer", "null"]},
                                        "sensitivity_ma": {"type": ["integer", "null"]},
                                    },
                                },
                                "circuits": {
                                    "type": "array",
                                    "items": {
                                        "type": "object",
                                        "properties": {
                                            "id": {"type": ["string", "null"]},
                                            "description": {"type": ["string", "null"]},
                                            "breaker": {
                                                "type": "object",
                                                "properties": {
                                                    "type": {"type": ["string", "null"]},
                                                    "rating_a": {"type": ["integer", "null"]},
                                                    "poles": {"type": ["string", "null"]},
                                                    "ka_rating": {"type": ["integer", "null"]},
                                                    "characteristic": {"type": ["string", "null"]},
                                                },
                                            },
                                            "cable": {"type": ["string", "null"]},
                                            "load_type": {"type": ["string", "null"]},
                                        },
                                    },
                                },
                            },
                        },
                    },
                    "outgoing_circuits": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "id": {"type": ["string", "null"]},
                                "description": {"type": ["string", "null"]},
                                "breaker": {
                                    "type": "object",
                                    "properties": {
                                        "type": {"type": ["string", "null"]},
                                        "rating_a": {"type": ["integer", "null"]},
                                        "poles": {"type": ["string", "null"]},
                                        "ka_rating": {"type": ["integer", "null"]},
                                        "characteristic": {"type": ["string", "null"]},
                                    },
                                },
                                "cable": {"type": ["string", "null"]},
                                "load_type": {"type": ["string", "null"]},
                                "phase": {"type": ["string", "null"]},
                            },
                        },
                    },
                },
            },
        },
        "client_info": {
            "type": "object",
            "properties": {
                "name": {"type": ["string", "null"]},
                "address": {"type": ["string", "null"]},
                "lew_name": {"type": ["string", "null"]},
                "lew_licence": {"type": ["string", "null"]},
                "contractor": {"type": ["string", "null"]},
                "main_contractor": {"type": ["string", "null"]},
            },
        },
    },
}


# ─────────────────────────────────────────────────────────────────────
# 3. Extraction Functions
# ─────────────────────────────────────────────────────────────────────

def parse_extracted_json(raw_json: dict | str) -> SldExtractedData:
    """
    Parse raw JSON (from Gemini or manual input) into validated Pydantic models.

    Args:
        raw_json: dict or JSON string from Gemini extraction.

    Returns:
        SldExtractedData with validated/defaulted fields.
    """
    if isinstance(raw_json, str):
        raw_json = json.loads(raw_json)

    return SldExtractedData.model_validate(raw_json)


def parse_and_validate(raw_json: dict | str) -> dict:
    """
    Parse extracted JSON and validate against Singapore standards.

    Combines Pydantic schema validation with sld_spec.py standards validation.

    Args:
        raw_json: dict or JSON string from Gemini extraction.

    Returns:
        dict with keys:
            - extracted: The parsed SldExtractedData as dict
            - validation: ValidationResult as dict
            - corrected: Requirements dict with auto-corrections applied
            - generation_ready: Requirements in generate_sld() format
    """
    # Step 1: Pydantic parsing
    extracted = parse_extracted_json(raw_json)
    extracted_dict = extracted.model_dump(exclude_none=True)

    # Step 2: Convert to sld_spec validation format
    spec_format = _to_spec_validation_format(extracted)

    # Step 3: Validate against sld_spec tables
    validation = validate_sld_requirements(spec_format)

    # Step 4: Apply auto-corrections
    corrected = apply_corrections(spec_format, validation)

    # Step 5: Build generation-ready format
    generation_ready = normalize_to_generation_format(extracted, corrected)

    return {
        "extracted": extracted_dict,
        "validation": {
            "valid": validation.valid,
            "errors": validation.errors,
            "warnings": validation.warnings,
            "corrections": validation.corrections,
        },
        "corrected": corrected,
        "generation_ready": generation_ready,
    }


def _to_spec_validation_format(extracted: SldExtractedData) -> dict:
    """
    Convert SldExtractedData to the flat dict format expected by
    sld_spec.validate_sld_requirements().

    Maps nested schema → flat keys:
        incoming.kva → kva
        incoming.phase → supply_type
        incoming.main_breaker.rating_a → breaker_rating
        incoming.main_breaker.type → breaker_type
        incoming.main_breaker.poles → breaker_poles
        incoming.main_breaker.ka_rating → breaker_ka
        incoming.cable.size_mm2 → cable_size
        incoming.metering.type → metering
        outgoing_circuits → circuits
    """
    result: dict[str, Any] = {}

    if extracted.incoming:
        inc = extracted.incoming
        if inc.kva is not None:
            result["kva"] = inc.kva
        if inc.phase:
            result["supply_type"] = inc.phase
        if inc.voltage is not None:
            result["voltage"] = inc.voltage

        if inc.main_breaker:
            mb = inc.main_breaker
            if mb.rating_a is not None:
                result["breaker_rating"] = mb.rating_a
            if mb.type:
                result["breaker_type"] = mb.type
            if mb.poles:
                result["breaker_poles"] = mb.poles
            if mb.ka_rating is not None:
                result["breaker_ka"] = mb.ka_rating

        if inc.cable and inc.cable.size_mm2:
            result["cable_size"] = inc.cable.size_mm2

        if inc.metering and inc.metering.type:
            result["metering"] = inc.metering.type

    # Convert outgoing circuits to sld_spec format
    if extracted.outgoing_circuits:
        circuits = []
        for oc in extracted.outgoing_circuits:
            circuit: dict[str, Any] = {}
            if oc.description:
                circuit["name"] = oc.description
            if oc.breaker:
                if oc.breaker.rating_a is not None:
                    circuit["breaker_rating"] = oc.breaker.rating_a
                if oc.breaker.type:
                    circuit["breaker_type"] = oc.breaker.type
            if oc.cable:
                circuit["cable_size"] = oc.cable
            circuits.append(circuit)
        result["circuits"] = circuits

    return result


def normalize_to_generation_format(
    extracted: SldExtractedData,
    corrected: dict | None = None,
) -> dict:
    """
    Convert extracted SLD data to the requirements format expected by generate_sld().

    generate_sld() expects:
        supply_type: str
        kva: float
        main_breaker: {type, rating/rating_A, poles, fault_kA, breaker_characteristic}
        busbar_rating: int
        sub_circuits: [{name, breaker_type, breaker_rating, breaker_characteristic,
                        fault_kA, cable}]
        elcb: {rating, sensitivity_ma, poles, type}
        earth_protection: bool
        metering: str
        incoming_cable: str

    Args:
        extracted: Parsed SldExtractedData.
        corrected: Optional corrected dict from validation (used for auto-filled values).

    Returns:
        dict in generate_sld() requirements format.
    """
    requirements: dict[str, Any] = {}

    inc = extracted.incoming
    if inc:
        # Supply type — prefer corrected value
        requirements["supply_type"] = (
            (corrected or {}).get("supply_type")
            or inc.phase
            or ""
        )

        # kVA
        requirements["kva"] = inc.kva or 0

        # Supply source (landlord vs building_riser vs sp_powergrid)
        if inc.supply_source:
            requirements["supply_source"] = inc.supply_source
        if inc.incoming_label:
            requirements["incoming_label"] = inc.incoming_label
            # Auto-correct supply_source from incoming_label when Gemini returns
            # generic "landlord" but the label explicitly says "BUILDING RISER".
            _label_upper = inc.incoming_label.upper()
            if "BUILDING RISER" in _label_upper and requirements.get("supply_source") != "building_riser":
                requirements["supply_source"] = "building_riser"

        # Main breaker
        mb = inc.main_breaker
        if mb:
            main_breaker: dict[str, Any] = {}
            main_breaker["type"] = (
                (corrected or {}).get("breaker_type")
                or mb.type
                or ""
            )
            main_breaker["rating"] = (
                (corrected or {}).get("breaker_rating")
                or mb.rating_a
                or 0
            )
            main_breaker["poles"] = (
                (corrected or {}).get("breaker_poles")
                or mb.poles
                or ""
            )
            main_breaker["fault_kA"] = (
                (corrected or {}).get("breaker_ka")
                or mb.ka_rating
                or 0
            )
            if mb.characteristic:
                main_breaker["breaker_characteristic"] = mb.characteristic
            requirements["main_breaker"] = main_breaker
        else:
            # Build from corrected values if no breaker specified
            if corrected:
                requirements["main_breaker"] = {
                    "type": corrected.get("breaker_type", ""),
                    "rating": corrected.get("breaker_rating", 0),
                    "poles": corrected.get("breaker_poles", ""),
                    "fault_kA": corrected.get("breaker_ka", 0),
                }

        # Busbar
        if inc.busbar and inc.busbar.rating_a:
            requirements["busbar_rating"] = inc.busbar.rating_a
        elif mb and mb.rating_a:
            # Default: busbar rating = max(main_breaker_rating, 100)
            requirements["busbar_rating"] = max(mb.rating_a, 100)
        elif corrected and corrected.get("breaker_rating"):
            requirements["busbar_rating"] = max(corrected["breaker_rating"], 100)

        # ELCB/RCCB
        if inc.elcb:
            elcb: dict[str, Any] = {}
            if inc.elcb.rating_a is not None:
                elcb["rating"] = inc.elcb.rating_a
            if inc.elcb.sensitivity_ma is not None:
                elcb["sensitivity_ma"] = inc.elcb.sensitivity_ma
            if inc.elcb.poles is not None:
                elcb["poles"] = inc.elcb.poles
            if inc.elcb.type:
                elcb["type"] = inc.elcb.type
            requirements["elcb"] = elcb

        # Earth protection
        requirements["earth_protection"] = inc.earth_protection if inc.earth_protection is not None else True

        # Metering
        # Landlord supply has no SP metering — only an isolator inside the unit.
        # If AI incorrectly extracted sp_meter for landlord, discard it.
        is_landlord = requirements.get("supply_source") == "landlord"
        metering_type = (corrected or {}).get("metering") or ""
        if not metering_type and inc.metering and inc.metering.type:
            metering_type = inc.metering.type
        if is_landlord and metering_type == "sp_meter":
            metering_type = ""  # Landlord supply: no SP meter board
        if metering_type:
            requirements["metering"] = metering_type
            # Propagate detailed metering fields for CT/SP metering sections
            if inc.metering:
                m = inc.metering
                metering_detail: dict = {}
                if m.ct_ratio:
                    metering_detail["ct_ratio"] = m.ct_ratio
                if m.protection_ct_ratio:
                    metering_detail["protection_ct_ratio"] = m.protection_ct_ratio
                if m.protection_ct_class:
                    metering_detail["protection_ct_class"] = m.protection_ct_class
                if m.metering_ct_class:
                    metering_detail["metering_ct_class"] = m.metering_ct_class
                if m.has_ammeter is not None:
                    metering_detail["has_ammeter"] = m.has_ammeter
                if m.has_voltmeter is not None:
                    metering_detail["has_voltmeter"] = m.has_voltmeter
                if m.has_elr is not None:
                    metering_detail["has_elr"] = m.has_elr
                if m.has_indicator_lights is not None:
                    metering_detail["has_indicator_lights"] = m.has_indicator_lights
                if m.elr_spec:
                    metering_detail["elr_spec"] = m.elr_spec
                if m.voltmeter_range:
                    metering_detail["voltmeter_range"] = m.voltmeter_range
                if m.ammeter_range:
                    metering_detail["ammeter_range"] = m.ammeter_range
                if metering_detail:
                    requirements["metering_detail"] = metering_detail

        # Incoming cable
        if inc.cable and inc.cable.description:
            requirements["incoming_cable"] = inc.cable.description
        elif corrected and corrected.get("cable_size"):
            cable_size = corrected["cable_size"]
            requirements["incoming_cable"] = f"Cable size: {cable_size}"

        # Outgoing cable (post-isolator, e.g. landlord supply with different cable to DB)
        if inc.outgoing_cable and inc.outgoing_cable.description:
            requirements["outgoing_cable"] = inc.outgoing_cable.description

    # -- Multi-DB path --
    if extracted.distribution_boards:
        from app.sld.circuit_normalizer import normalize_phase_name

        db_list = []
        for db_data in extracted.distribution_boards:
            db_req: dict[str, Any] = {}
            if db_data.name:
                db_req["name"] = db_data.name
            if db_data.fed_from:
                db_req["fed_from"] = db_data.fed_from

            # DB breaker
            if db_data.breaker:
                db_req["breaker"] = {
                    "type": db_data.breaker.type or "MCB",
                    "rating": db_data.breaker.rating_a or 0,
                    "poles": db_data.breaker.poles or "",
                    "fault_kA": db_data.breaker.ka_rating or 10,
                }
                if db_data.breaker.characteristic:
                    db_req["breaker"]["breaker_characteristic"] = db_data.breaker.characteristic

            # DB ELCB
            if db_data.elcb:
                db_elcb: dict[str, Any] = {}
                if db_data.elcb.rating_a is not None:
                    db_elcb["rating"] = db_data.elcb.rating_a
                if db_data.elcb.sensitivity_ma is not None:
                    db_elcb["sensitivity_ma"] = db_data.elcb.sensitivity_ma
                if db_data.elcb.poles is not None:
                    db_elcb["poles"] = db_data.elcb.poles
                if db_data.elcb.type:
                    db_elcb["type"] = db_data.elcb.type
                db_req["elcb"] = db_elcb

            # DB busbar
            if db_data.busbar and db_data.busbar.rating_a:
                db_req["busbar_rating"] = db_data.busbar.rating_a

            # Protection groups (per-phase RCCB)
            if db_data.protection_groups:
                # Safety net: if all protection_groups share the same 4P RCCB,
                # merge them back — a 4P RCCB is a single device, not per-phase.
                _all_4p = (
                    len(db_data.protection_groups) >= 2
                    and all(
                        pg.rccb and pg.rccb.poles in (4, "4P")
                        for pg in db_data.protection_groups
                    )
                )
                _first_rccb = db_data.protection_groups[0].rccb if db_data.protection_groups else None
                _all_same_spec = _all_4p and _first_rccb and all(
                    pg.rccb
                    and pg.rccb.rating_a == _first_rccb.rating_a
                    and pg.rccb.sensitivity_ma == _first_rccb.sensitivity_ma
                    for pg in db_data.protection_groups
                )

                if _all_same_spec and _first_rccb:
                    # Merge: flatten all protection_group circuits into outgoing
                    merged = []
                    for pg in db_data.protection_groups:
                        for c in pg.circuits:
                            if pg.phase and not c.phase:
                                c.phase = pg.phase
                            merged.append(c)
                    # Prepend merged circuits to existing outgoing
                    db_data.outgoing_circuits = merged + list(db_data.outgoing_circuits or [])
                    db_data.protection_groups = []
                    # Restore board-level ELCB
                    if not db_data.elcb:
                        db_data.elcb = ElcbSpec(
                            type=_first_rccb.type or "RCCB",
                            rating_a=_first_rccb.rating_a,
                            poles=4,
                            sensitivity_ma=_first_rccb.sensitivity_ma,
                        )
                    logger.info(
                        "normalize: merged 4P RCCB protection_groups for DB '%s' (%d circuits)",
                        db_data.name or "?",
                        len(merged),
                    )

                else:
                    pg_list = []
                    for pg in db_data.protection_groups:
                        pg_req: dict[str, Any] = {
                            "phase": normalize_phase_name(pg.phase or ""),
                        }
                        if pg.rccb:
                            pg_req["rccb"] = {
                                "type": pg.rccb.type or "RCCB",
                                "rating": pg.rccb.rating_a or 0,
                                "sensitivity_ma": pg.rccb.sensitivity_ma or 30,
                                "poles": pg.rccb.poles or 2,
                            }
                        pg_req["circuits"] = _convert_circuits(pg.circuits)
                        pg_list.append(pg_req)
                    db_req["protection_groups"] = pg_list

            # DB outgoing circuits (not in protection groups)
            if db_data.outgoing_circuits:
                db_req["sub_circuits"] = _convert_circuits(db_data.outgoing_circuits)

            # B1 fix: populate 5 fields consumed by engine.py
            if db_data.incoming_breaker:
                db_req["incoming_breaker"] = {
                    "type": db_data.incoming_breaker.type or "MCB",
                    "rating": db_data.incoming_breaker.rating_a or 0,
                    "poles": db_data.incoming_breaker.poles or "",
                    "fault_kA": db_data.incoming_breaker.ka_rating or 10,
                }
                if db_data.incoming_breaker.characteristic:
                    db_req["incoming_breaker"]["breaker_characteristic"] = db_data.incoming_breaker.characteristic
            if db_data.feeder_breaker:
                db_req["feeder_breaker"] = {
                    "type": db_data.feeder_breaker.type or "MCB",
                    "rating": db_data.feeder_breaker.rating_a or 0,
                    "poles": db_data.feeder_breaker.poles or "",
                    "fault_kA": db_data.feeder_breaker.ka_rating or 10,
                }
            if db_data.feeder_cable:
                db_req["feeder_cable"] = db_data.feeder_cable.description or ""
            if db_data.main_mcb:
                db_req["main_mcb"] = {
                    "type": db_data.main_mcb.type or "MCB",
                    "rating": db_data.main_mcb.rating_a or 0,
                    "poles": db_data.main_mcb.poles or "",
                    "fault_kA": db_data.main_mcb.ka_rating or 10,
                }
            if db_data.meter_board:
                db_req["meter_board"] = db_data.meter_board

            db_list.append(db_req)

        requirements["distribution_boards"] = db_list
        # B2 fix: only set db_topology if hierarchy was actually detected;
        # otherwise let engine.py auto-detect from fed_from fields
        detected_topology = _build_db_hierarchy(db_list)
        if detected_topology == "hierarchical":
            requirements["db_topology"] = "hierarchical"
        # else: omit db_topology → engine.py will auto-detect
        return requirements

    # -- Single-DB path (backward compatible) --
    sub_circuits = _convert_circuits(extracted.outgoing_circuits)
    requirements["sub_circuits"] = sub_circuits

    return requirements


def _build_db_hierarchy(db_list: list[dict[str, Any]]) -> str:
    """Detect feeder circuits and build parent→child hierarchy.

    Scans each DB's sub_circuits for feeder patterns (e.g., "Feeder to DB2").
    When found, marks the feeder circuit with _is_feeder=True and sets the
    child DB's fed_from to the parent name.

    Returns "hierarchical" if at least one parent→child relationship is found,
    "parallel" otherwise.
    """
    import re

    db_names = {db.get("name", "").upper(): db.get("name", "") for db in db_list if db.get("name")}

    hierarchy_found = False

    for db in db_list:
        parent_name = db.get("name", "")
        circuits = db.get("sub_circuits", [])
        for ckt in circuits:
            desc = (ckt.get("name", "") or ckt.get("description", "") or "").upper()

            # Pattern matching for feeder circuits
            matched_child = None

            # Pattern 1: "Feeder to DB2", "FEEDER TO DB 2"
            m = re.search(r"FEEDER\s+TO\s+(.+)", desc)
            if m:
                child_name_raw = m.group(1).strip()
                # Try exact match first, then fuzzy
                for db_key, db_original in db_names.items():
                    if db_key != parent_name.upper() and (
                        child_name_raw.upper() == db_key
                        or child_name_raw.upper().replace(" ", "") == db_key.replace(" ", "")
                    ):
                        matched_child = db_original
                        break

            # Pattern 2: circuit description contains another DB name (e.g., "DB2", "DB 2")
            if not matched_child:
                for db_key, db_original in db_names.items():
                    if db_key == parent_name.upper():
                        continue
                    # Check if DB name appears in description
                    pattern = re.escape(db_key)
                    # Also match with/without space: "DB2" matches "DB 2"
                    pattern_nospace = re.escape(db_key.replace(" ", ""))
                    desc_nospace = desc.replace(" ", "")
                    if re.search(pattern, desc) or (
                        len(pattern_nospace) >= 3 and re.search(pattern_nospace, desc_nospace)
                    ):
                        matched_child = db_original
                        break

            if matched_child:
                ckt["_is_feeder"] = True
                ckt["_feeds_db"] = matched_child
                # Set fed_from on the child DB
                for child_db in db_list:
                    if child_db.get("name") == matched_child and not child_db.get("fed_from"):
                        child_db["fed_from"] = parent_name
                        hierarchy_found = True

    # Also check if Gemini already set fed_from
    if not hierarchy_found:
        for db in db_list:
            if db.get("fed_from"):
                hierarchy_found = True
                break

    return "hierarchical" if hierarchy_found else "parallel"


def _convert_circuits(circuits: list) -> list[dict[str, Any]]:
    """Convert a list of OutgoingCircuit Pydantic models to flat dicts."""
    from app.sld.circuit_normalizer import normalize_phase_name

    result: list[dict[str, Any]] = []
    for oc in circuits:
        sc: dict[str, Any] = {}
        sc["name"] = oc.description or ""

        # Preserve explicit circuit ID from schedule upload (e.g., "L1S1", "ISOL1")
        if oc.id:
            sc["circuit_id"] = oc.id

        if oc.breaker:
            sc["breaker_type"] = oc.breaker.type or "MCB"
            sc["breaker_rating"] = oc.breaker.rating_a or 0
            if oc.breaker.characteristic:
                sc["breaker_characteristic"] = oc.breaker.characteristic
            sc["fault_kA"] = oc.breaker.ka_rating or 6
        else:
            sc["breaker_type"] = "MCB"
            sc["breaker_rating"] = 0
            sc["fault_kA"] = 6

        sc["cable"] = oc.cable or ""

        if oc.qty:
            sc["name"] = f"{oc.description or ''} ({oc.qty} nos)"

        # Phase normalization (R/Y/B → L1/L2/L3)
        if hasattr(oc, "phase") and oc.phase:
            sc["phase"] = normalize_phase_name(oc.phase)

        result.append(sc)
    return result


async def extract_sld_from_text(user_input: str) -> dict:
    """
    Extract structured SLD data from user text using Gemini API.

    Calls Gemini with the SLD_EXTRACTION_PROMPT to analyze the input and
    return structured JSON. Then validates and corrects against Singapore standards.

    Args:
        user_input: Free-form text describing SLD requirements,
                    or structured description of an SLD diagram.

    Returns:
        dict with keys: extracted, validation, corrected, generation_ready
    """
    import google.generativeai as genai

    from app.config import settings

    # Configure Gemini
    genai.configure(api_key=settings.gemini_api_key)
    model = genai.GenerativeModel(
        model_name=settings.gemini_pro_model,
        system_instruction=SLD_EXTRACTION_PROMPT,
        generation_config=genai.GenerationConfig(
            response_mime_type="application/json",
            temperature=0.0,
        ),
    )

    logger.info("Calling Gemini for SLD data extraction")

    # Call Gemini
    response = await model.generate_content_async(user_input)
    raw_text = response.text

    logger.info("Gemini extraction response received (%d chars)", len(raw_text))

    # Parse and validate
    try:
        raw_json = json.loads(raw_text)
    except json.JSONDecodeError as e:
        logger.error("Gemini returned invalid JSON: %s", e)
        return {
            "extracted": {},
            "validation": {
                "valid": False,
                "errors": [f"Gemini returned invalid JSON: {e}"],
                "warnings": [],
                "corrections": {},
            },
            "corrected": {},
            "generation_ready": {},
        }

    return parse_and_validate(raw_json)


def format_extraction_result(result: dict) -> str:
    """
    Format extraction result as a human-readable summary for the user.

    Args:
        result: Output from parse_and_validate() or extract_sld_from_text().

    Returns:
        Formatted string summary.
    """
    lines: list[str] = []

    # Extracted data summary
    extracted = result.get("extracted", {})
    incoming = extracted.get("incoming", {})

    if incoming:
        lines.append("## Extracted Incoming Supply")
        if incoming.get("kva"):
            lines.append(f"- **kVA**: {incoming['kva']}")
        if incoming.get("phase"):
            phase_label = "Three-Phase 400V" if incoming["phase"] == "three_phase" else "Single-Phase 230V"
            lines.append(f"- **Supply**: {phase_label}")
        mb = incoming.get("main_breaker", {})
        if mb:
            parts = []
            if mb.get("type"):
                parts.append(mb["type"])
            if mb.get("rating_a"):
                parts.append(f"{mb['rating_a']}A")
            if mb.get("poles"):
                parts.append(mb["poles"])
            if mb.get("ka_rating"):
                parts.append(f"{mb['ka_rating']}kA")
            if mb.get("characteristic"):
                parts.append(f"Type {mb['characteristic']}")
            if parts:
                lines.append(f"- **Main Breaker**: {' / '.join(parts)}")

    # Outgoing circuits
    circuits = extracted.get("outgoing_circuits", [])
    if circuits:
        lines.append(f"\n## Outgoing Circuits ({len(circuits)} total)")
        for i, oc in enumerate(circuits, 1):
            desc = oc.get("description", "Unknown")
            breaker = oc.get("breaker", {})
            br_str = ""
            if breaker:
                parts = [breaker.get("type", ""), f"{breaker.get('rating_a', '?')}A"]
                br_str = " ".join(p for p in parts if p)
            lines.append(f"  {i}. {desc} — {br_str}")

    # Validation summary
    validation = result.get("validation", {})
    if validation.get("errors"):
        lines.append(f"\n## ❌ Errors ({len(validation['errors'])})")
        for err in validation["errors"]:
            lines.append(f"  - {err}")

    if validation.get("warnings"):
        lines.append(f"\n## ⚠️ Warnings ({len(validation['warnings'])})")
        for warn in validation["warnings"]:
            lines.append(f"  - {warn}")

    if validation.get("corrections"):
        lines.append(f"\n## ✅ Auto-Corrections ({len(validation['corrections'])})")
        for key, detail in validation["corrections"].items():
            lines.append(
                f"  - **{key}**: {detail.get('original', '?')} → "
                f"{detail.get('corrected', '?')} ({detail.get('reason', '')})"
            )

    return "\n".join(lines)
