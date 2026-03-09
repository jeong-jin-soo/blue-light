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

from pydantic import BaseModel, Field

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


class IncomingData(BaseModel):
    """Incoming supply section (labels A–H from SLD Drawing Information.pdf)."""
    kva: Optional[float] = Field(None, description="Total load capacity in kVA")
    phase: Optional[str] = Field(None, description="single_phase or three_phase")
    voltage: Optional[int] = Field(None, description="Supply voltage (230 or 400)")
    main_breaker: Optional[BreakerSpec] = Field(None, description="[A] Main Breaker")
    cable: Optional[CableSpec] = Field(None, description="[B] Incoming Cable")
    elcb: Optional[ElcbSpec] = Field(None, description="[G] ELCB/RCCB")
    busbar: Optional[BusbarSpec] = Field(None, description="[H] Busbar")
    metering: Optional[MeteringSpec] = Field(None, description="[F] Metering Section")
    earth_protection: Optional[bool] = Field(None, description="[E] Earth Protection present")


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


class ClientInfo(BaseModel):
    """Client / project information from the title block."""
    name: Optional[str] = Field(None, description="Client company name")
    address: Optional[str] = Field(None, description="Premises address")
    lew_name: Optional[str] = Field(None, description="Licensed Electrical Worker name")
    lew_licence: Optional[str] = Field(None, description="LEW licence number")
    contractor: Optional[str] = Field(None, description="M&E contractor name")
    main_contractor: Optional[str] = Field(None, description="Main contractor name")


class SldExtractedData(BaseModel):
    """Complete SLD extraction output schema."""
    incoming: Optional[IncomingData] = None
    outgoing_circuits: list[OutgoingCircuit] = Field(default_factory=list)
    client_info: Optional[ClientInfo] = None


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
        metering_type = (corrected or {}).get("metering") or ""
        if not metering_type and inc.metering and inc.metering.type:
            metering_type = inc.metering.type
        if metering_type:
            requirements["metering"] = metering_type

        # Incoming cable
        if inc.cable and inc.cable.description:
            requirements["incoming_cable"] = inc.cable.description
        elif corrected and corrected.get("cable_size"):
            cable_size = corrected["cable_size"]
            requirements["incoming_cable"] = f"Cable size: {cable_size}"

    # Sub-circuits
    sub_circuits = []
    for oc in extracted.outgoing_circuits:
        sc: dict[str, Any] = {}
        sc["name"] = oc.description or ""

        # Preserve explicit circuit ID from schedule upload (e.g., "L1S1", "ISOL1")
        # so _assign_circuit_ids() can use it for correct phase grouping
        if oc.id:
            sc["circuit_id"] = oc.id

        if oc.breaker:
            sc["breaker_type"] = oc.breaker.type or "MCB"
            sc["breaker_rating"] = oc.breaker.rating_a or 0
            if oc.breaker.characteristic:
                sc["breaker_characteristic"] = oc.breaker.characteristic
            sc["fault_kA"] = oc.breaker.ka_rating or 6  # Default 6kA for sub-circuits
        else:
            sc["breaker_type"] = "MCB"
            sc["breaker_rating"] = 0
            sc["fault_kA"] = 6

        sc["cable"] = oc.cable or ""

        if oc.qty:
            sc["name"] = f"{oc.description or ''} ({oc.qty} nos)"

        sub_circuits.append(sc)

    requirements["sub_circuits"] = sub_circuits

    return requirements


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
        model_name=settings.gemini_model,
        system_instruction=SLD_EXTRACTION_PROMPT,
        generation_config=genai.GenerationConfig(
            response_mime_type="application/json",
            temperature=0.1,
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
