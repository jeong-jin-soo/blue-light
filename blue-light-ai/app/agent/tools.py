"""
LangGraph agent tools for SLD generation.

Tools:
1. get_application_details — Return application info (from state) + standard specs
2. get_standard_specs — Singapore electrical standards lookup
3. validate_sld_requirements — Check if requirements are complete
4. generate_sld — Generate PDF + SVG files
5. generate_preview — Generate SVG preview from existing file
"""

import json
import logging
import os
import uuid

from langchain_core.tools import tool

from app.config import settings

logger = logging.getLogger(__name__)


# ── Tool 1: Get Application Details + Standard Specs ─

@tool
def get_application_details(application_seq: int) -> str:
    """
    Retrieve application details and recommended standard specifications.

    The application info (kVA, address, building type, etc.) is already available
    in the conversation context — provided by Spring Boot when the session started.
    This tool returns that info combined with the Singapore electrical standards
    (SS 638 / CP 5) for the application's kVA capacity.

    ALWAYS call this tool at the start of the conversation to get the full context.

    Args:
        application_seq: The application ID to look up.
    """
    # Application info is injected into the system message by graph.py.
    # Here we look up the standard specs for the kVA tier to provide
    # a complete picture in a single tool call.

    # Load standards data
    standards_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(__file__))),
        "data", "standards", "cable_sizing.json",
    )

    # Provide a helpful combined result
    result = {
        "note": "Application info is available in the system context above. "
                "Below are the recommended standard specs for this kVA tier.",
    }

    try:
        with open(standards_path, encoding="utf-8") as f:
            standards = json.load(f)

        # Try to find kVA from system context — the agent should already know it
        # The standards lookup is the real value this tool provides
        tiers = standards.get("tiers", [])
        if tiers:
            result["available_tiers"] = [t["max_kva"] for t in tiers]
            result["standards_data"] = tiers
            result["instruction"] = (
                "Match the application's selectedKva to the closest tier. "
                "Use the tier's main_breaker, recommended_busbar_A, and "
                "typical_sub_circuits as the default design proposal."
            )
    except FileNotFoundError:
        result["warning"] = "Standards file not found — use fallback calculations."

    return json.dumps(result, ensure_ascii=False)


# ── Tool 2: Get Standard Specs ──────────────────────

@tool
def get_standard_specs(kva: int, supply_type: str = "three_phase") -> str:
    """
    Look up Singapore electrical standards (SS 638 / CP 5) for a given kVA capacity.
    Returns recommended cable sizes, breaker ratings, and protection requirements.

    Args:
        kva: Installation capacity in kVA (e.g., 45, 100, 200, 500, 1000)
        supply_type: "single_phase" or "three_phase"
    """
    # Load standards data
    standards_path = os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(__file__))),
        "data", "standards", "cable_sizing.json",
    )

    try:
        with open(standards_path, encoding="utf-8") as f:
            standards = json.load(f)
    except FileNotFoundError:
        return json.dumps(_get_fallback_specs(kva, supply_type))

    # Find matching tier
    tiers = standards.get("tiers", [])
    matched = None
    for tier in tiers:
        if kva <= tier["max_kva"]:
            matched = tier
            break

    if not matched:
        matched = tiers[-1] if tiers else None

    if not matched:
        return json.dumps(_get_fallback_specs(kva, supply_type))

    return json.dumps(matched, ensure_ascii=False)


def _get_fallback_specs(kva: int, supply_type: str) -> dict:
    """Fallback specs when standards file is not available."""
    # Calculate approximate current
    if supply_type == "three_phase":
        voltage = 400
        current = round(kva * 1000 / (voltage * 1.732))
    else:
        voltage = 230
        current = round(kva * 1000 / voltage)

    # Determine main breaker type
    if current > 630:
        breaker_type = "ACB"
    elif current > 100:
        breaker_type = "MCCB"
    else:
        breaker_type = "MCB"

    # Standard breaker ratings
    standard_ratings = [16, 20, 25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500]
    breaker_rating = next((r for r in standard_ratings if r >= current), standard_ratings[-1])

    return {
        "kva": kva,
        "supply_type": supply_type,
        "voltage": voltage,
        "calculated_current_A": current,
        "main_breaker": {
            "type": breaker_type,
            "rating_A": breaker_rating,
        },
        "recommended_busbar_A": breaker_rating,
        "note": "Fallback calculation — verify against SS 638 tables",
    }


# ── Tool 3: Validate SLD Requirements ───────────────

@tool
def validate_sld_requirements(requirements: dict) -> str:
    """
    Validate whether the gathered SLD requirements are complete and comply
    with Singapore standards (SS 638:2018 / CP 5:2018) for generation.
    Returns a validation result with missing fields, compliance errors, and warnings.

    Args:
        requirements: Dictionary of SLD requirements gathered from the LEW conversation.
            Expected keys: supply_type, kva, main_breaker, busbar_rating, sub_circuits,
            elcb, earth_protection, metering, incoming_cable
    """
    missing = []
    errors = []      # Compliance violations — block generation
    warnings = []

    # ── Required fields ──────────────────────────────────
    supply_type = requirements.get("supply_type", "")
    if not supply_type:
        missing.append("supply_type (single_phase / three_phase)")

    kva = requirements.get("kva", 0)
    if not kva:
        missing.append("kva (installation capacity)")

    main_breaker = requirements.get("main_breaker", {})
    breaker_type = str(main_breaker.get("type", "")).upper() if isinstance(main_breaker, dict) else ""
    breaker_rating = (main_breaker.get("rating") or main_breaker.get("rating_A") or 0) if isinstance(main_breaker, dict) else 0

    if not breaker_type:
        missing.append("main_breaker.type (ACB / MCCB / MCB)")
    if not breaker_rating:
        missing.append("main_breaker.rating (in Amps)")

    busbar_rating = requirements.get("busbar_rating", 0)
    if not busbar_rating:
        missing.append("busbar_rating (in Amps)")

    sub_circuits = requirements.get("sub_circuits", [])
    if not sub_circuits:
        missing.append("sub_circuits (at least one sub-circuit required)")
    else:
        for i, sc in enumerate(sub_circuits):
            if not sc.get("name"):
                missing.append(f"sub_circuits[{i}].name")
            if not sc.get("breaker_type"):
                missing.append(f"sub_circuits[{i}].breaker_type")
            if not sc.get("breaker_rating"):
                missing.append(f"sub_circuits[{i}].breaker_rating")

    # ── SS 638 Compliance Checks ─────────────────────────

    # 1. Busbar rating >= main breaker rating
    if busbar_rating and breaker_rating and busbar_rating < breaker_rating:
        errors.append(
            f"SS 638: busbar_rating ({busbar_rating}A) must be ≥ "
            f"main_breaker rating ({breaker_rating}A)"
        )

    # 2. Main breaker type must match current range
    if breaker_type and breaker_rating:
        if breaker_rating > 630 and breaker_type != "ACB":
            errors.append(
                f"SS 638: ACB required for rating > 630A "
                f"(current: {breaker_type} {breaker_rating}A)"
            )
        elif breaker_rating > 63 and breaker_type == "MCB":
            errors.append(
                f"SS 638: MCB max rating is 63A, use MCCB for {breaker_rating}A"
            )

    # 3. ELCB is mandatory per SS 638
    elcb = requirements.get("elcb")
    if not elcb:
        warnings.append(
            "SS 638: ELCB is mandatory — add 'elcb' with 'rating' and "
            "'sensitivity_ma' (e.g., 100mA for distribution, 30mA for sockets)"
        )

    # 4. Sub-circuit cable specs must be specified
    for i, sc in enumerate(sub_circuits):
        if not sc.get("cable"):
            warnings.append(
                f"SS 638: sub_circuits[{i}] '{sc.get('name', '')}' — "
                f"cable specification missing (required per SS 638 Table 4D1A)"
            )

    # 5. Single-phase supply for > 24 kVA is unusual
    if supply_type == "single_phase" and kva and kva > 24:
        warnings.append(
            f"SS 638: Single-phase is unusual for {kva} kVA — "
            f"three-phase 400V recommended for installations > 24 kVA"
        )

    # 6. Incoming cable should be specified
    if not requirements.get("incoming_cable"):
        warnings.append(
            "Incoming cable not specified — recommend specifying per SS 638 "
            "Table 4D1A for the installation's kVA tier"
        )

    # ── Optional but recommended ─────────────────────────
    if not requirements.get("metering"):
        warnings.append("metering not specified — will use standard SP kWh meter")

    # ── Result ───────────────────────────────────────────
    is_valid = len(missing) == 0 and len(errors) == 0

    result = {
        "valid": is_valid,
        "missing_fields": missing,
        "errors": errors,
        "warnings": warnings,
        "total_sub_circuits": len(sub_circuits),
    }

    if not is_valid and errors:
        result["action_required"] = (
            "Fix the compliance errors above before generating. "
            "These violate SS 638:2018 / CP 5:2018 requirements."
        )

    return json.dumps(result, ensure_ascii=False)


# ── Tool 4: Generate SLD (PDF + SVG) ─────────────────

@tool
def generate_sld(requirements: dict, application_info: dict | None = None) -> str:
    """
    Generate a Single Line Diagram as PDF with SVG preview.
    Returns the file ID for download and the SVG preview string.

    Args:
        requirements: Complete SLD requirements dictionary containing supply_type,
            kva, main_breaker, busbar_rating, sub_circuits, etc.
        application_info: Optional application details (address, postal code, etc.)
    """
    # 입력 검증: 필수 필드 확인
    required_fields = ["supply_type", "kva", "main_breaker", "busbar_rating", "sub_circuits"]
    missing = [f for f in required_fields if f not in requirements or not requirements[f]]
    if missing:
        return json.dumps({
            "success": False,
            "error": f"Missing required fields: {missing}. "
                     "Please gather all requirements before generating.",
        })

    main_breaker = requirements.get("main_breaker", {})
    if not isinstance(main_breaker, dict):
        return json.dumps({
            "success": False,
            "error": "main_breaker must be a dict with 'type' and 'rating' (or 'rating_A') keys.",
        })

    if not main_breaker.get("type"):
        return json.dumps({
            "success": False,
            "error": "main_breaker.type is required (ACB / MCCB / MCB).",
        })

    if not main_breaker.get("rating") and not main_breaker.get("rating_A"):
        return json.dumps({
            "success": False,
            "error": "main_breaker.rating (or rating_A) is required (in Amps).",
        })

    sub_circuits = requirements.get("sub_circuits", [])
    if not isinstance(sub_circuits, list) or len(sub_circuits) == 0:
        return json.dumps({
            "success": False,
            "error": "At least one sub_circuit is required.",
        })

    from app.sld.generator import SldGenerator

    file_id = uuid.uuid4().hex[:12]
    pdf_path = os.path.join(settings.temp_file_dir, f"{file_id}.pdf")
    svg_path = os.path.join(settings.temp_file_dir, f"{file_id}.svg")

    try:
        generator = SldGenerator()
        result = generator.generate(
            requirements=requirements,
            application_info=application_info or {},
            pdf_output_path=pdf_path,
            svg_output_path=svg_path,
        )

        return json.dumps({
            "success": True,
            "file_id": file_id,
            "pdf_path": pdf_path,
            "svg_path": svg_path,
            "svg_preview": result.get("svg_string", ""),
            "component_count": result.get("component_count", 0),
        }, ensure_ascii=False)

    except Exception as e:
        logger.error(f"SLD generation failed: {e}", exc_info=True)
        return json.dumps({
            "success": False,
            "error": f"Generation failed: {str(e)}",
        })


# ── Tool 5: Generate Preview ────────────────────────

@tool
def generate_preview(file_id: str) -> str:
    """
    Generate or retrieve SVG preview for an existing SLD file.

    Args:
        file_id: The file ID of a previously generated SLD.
    """
    svg_path = os.path.join(settings.temp_file_dir, f"{file_id}.svg")

    if os.path.exists(svg_path):
        with open(svg_path, encoding="utf-8") as f:
            svg_content = f.read()
        return json.dumps({
            "success": True,
            "file_id": file_id,
            "svg": svg_content,
        })

    return json.dumps({
        "success": False,
        "error": f"No SVG file found for file_id={file_id}",
    })


# ── Tool Registry ───────────────────────────────────

ALL_TOOLS = [
    get_application_details,
    get_standard_specs,
    validate_sld_requirements,
    generate_sld,
    generate_preview,
]
