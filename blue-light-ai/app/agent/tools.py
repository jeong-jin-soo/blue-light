"""
LangGraph agent tools for SLD generation.

Tools:
1. get_application_details — Return application info (from state) + standard specs
2. get_standard_specs — Singapore electrical standards lookup
3. validate_sld_requirements — Check if requirements are complete
4. generate_sld — Generate PDF + SVG files
5. generate_preview — Generate SVG preview from existing file
6. extract_sld_data — Extract structured SLD JSON from user text/description
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
            result["NEXT_ACTION_REQUIRED"] = (
                "⚠️ DO NOT propose any design yet. "
                "Your NEXT action MUST be to call `find_matching_templates` "
                "with supply_type and kva inferred from the user's message "
                "and the application context. "
                "You are BLOCKED from outputting any design proposal "
                "until find_matching_templates has been called."
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
    # Log full requirements for diagnostics
    logger.info(f"validate_sld_requirements called with: {json.dumps(requirements, ensure_ascii=False, default=str)}")

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
    _br_raw = (main_breaker.get("rating") or main_breaker.get("rating_A") or 0) if isinstance(main_breaker, dict) else 0
    if isinstance(_br_raw, str):
        import re as _re
        _br_digits = _re.sub(r"[^\d]", "", _br_raw)
        breaker_rating = int(_br_digits) if _br_digits else 0
    else:
        breaker_rating = int(_br_raw) if _br_raw else 0

    if not breaker_type:
        missing.append("main_breaker.type (ACB / MCCB / MCB)")
    if not breaker_rating:
        missing.append("main_breaker.rating (in Amps)")

    busbar_rating_raw = requirements.get("busbar_rating", 0)
    # Normalize busbar_rating to int (Gemini may send "200A", "200", or 200)
    if isinstance(busbar_rating_raw, str):
        import re as _re
        _digits = _re.sub(r"[^\d]", "", busbar_rating_raw)
        busbar_rating = int(_digits) if _digits else 0
    else:
        busbar_rating = int(busbar_rating_raw) if busbar_rating_raw else 0
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

    # 5a. Three-phase supply with single-phase ELCB/RCCB indicators → ERROR (blocks generation)
    elcb = requirements.get("elcb")
    if supply_type == "three_phase" and elcb and isinstance(elcb, dict):
        elcb_poles = elcb.get("poles", 4)
        elcb_rating = elcb.get("rating", 0)
        elcb_sensitivity = elcb.get("sensitivity_ma", 0)

        # Check 1: 2-pole RCCB/ELCB is ALWAYS single-phase
        if elcb_poles == 2:
            errors.append(
                f"CRITICAL MISMATCH: ELCB/RCCB is 2-pole (single-phase) "
                f"but supply_type is 'three_phase'. "
                f"2-pole protection devices require single_phase 230V supply. "
                f"Change supply_type to 'single_phase', voltage to 230, "
                f"and use 2-core cables (e.g., '2C x 2.5mm2 PVC')."
            )

        # Check 2: 30mA sensitivity is for personal protection (residential single-phase)
        # Three-phase distribution boards use 100mA or 300mA — 30mA + 3-phase is almost always wrong
        if elcb_sensitivity and elcb_sensitivity <= 30:
            errors.append(
                f"CRITICAL MISMATCH: ELCB/RCCB sensitivity is {elcb_sensitivity}mA "
                f"(personal protection — residential single-phase) "
                f"but supply_type is 'three_phase'. "
                f"30mA sensitivity RCD is for single-phase 230V residential installations. "
                f"Three-phase distribution boards typically use 100mA or 300mA sensitivity. "
                f"If the user specified '30mA', this strongly indicates single-phase 230V. "
                f"Change supply_type to 'single_phase', ELCB/RCCB poles to 2, "
                f"voltage to 230, kVA estimated as RCCB_rating × 230 ÷ 1000."
            )

        # Check 3: RCCB ≤ 63A rating with three-phase is suspicious
        if elcb_rating and elcb_rating <= 63:
            errors.append(
                f"CRITICAL MISMATCH: ELCB/RCCB rating is {elcb_rating}A "
                f"but supply_type is 'three_phase'. "
                f"For three-phase 400V, {elcb_rating}A RCCB can only protect up to "
                f"~{round(elcb_rating * 400 * 1.732 / 1000)}kVA. "
                f"A ≤63A RCCB/ELCB almost always indicates single-phase 230V residential. "
                f"Change supply_type to 'single_phase', poles to 2, voltage to 230."
            )

    # 5a-2. Residential load pattern detection: all sub-circuits are lighting/fan/socket ≤ 32A MCB
    if supply_type == "three_phase" and sub_circuits:
        import re as _re
        residential_keywords = {"lighting", "light", "fan", "socket", "spare"}
        all_residential = True
        all_small_mcb = True
        for sc in sub_circuits:
            sc_name = str(sc.get("name", "")).lower()
            sc_type = str(sc.get("breaker_type", "")).upper()
            sc_rating = sc.get("breaker_rating", 0)
            if isinstance(sc_rating, str):
                sc_rating = int(_re.sub(r"[^\d]", "", sc_rating) or "0")

            # Check if name contains only residential keywords
            name_words = set(_re.findall(r'[a-z]+', sc_name))
            if not name_words.intersection(residential_keywords) and sc_name.strip():
                all_residential = False
            # Check if all breakers are small MCBs
            if sc_type != "MCB" or (sc_rating and sc_rating > 32):
                all_small_mcb = False

        if all_residential and all_small_mcb and len(sub_circuits) <= 6:
            errors.append(
                f"RESIDENTIAL LOAD PATTERN DETECTED: All {len(sub_circuits)} sub-circuits "
                f"are small MCBs (≤32A) with residential loads (lighting/fan/socket/spare), "
                f"but supply_type is 'three_phase'. "
                f"This load pattern strongly indicates a single-phase 230V residential installation. "
                f"Change supply_type to 'single_phase', voltage to 230, ELCB/RCCB poles to 2, "
                f"main breaker to MCB (≤63A), and use 2-core cables."
            )

    # 5b. ELCB/RCCB missing check — MANDATORY per SS 638
    if not elcb:
        errors.append(
            "SS 638: Earth leakage protection (ELCB/RCCB) is MANDATORY. "
            "Add 'elcb' dict with 'rating', 'sensitivity_ma', 'poles'. "
            "If user specified RCCB, include '\"type\": \"RCCB\"'."
        )

    # 5c. Sub-circuit breaker_characteristic validation
    for i, sc in enumerate(sub_circuits):
        bc = sc.get("breaker_characteristic", "")
        if bc and bc.upper() not in ("B", "C", "D", ""):
            warnings.append(
                f"sub_circuits[{i}] '{sc.get('name', '')}': "
                f"breaker_characteristic '{bc}' is not a valid IEC 60898-1 type (B/C/D)."
            )

    # 6. Incoming cable should be specified
    if not requirements.get("incoming_cable"):
        warnings.append(
            "Incoming cable not specified — recommend specifying per SS 638 "
            "Table 4D1A for the installation's kVA tier"
        )

    # ── Optional but recommended ─────────────────────────
    supply_source = requirements.get("supply_source", "sp_powergrid")
    if not requirements.get("metering") and supply_source != "landlord":
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
def generate_sld(
    requirements: dict,
    application_info: dict | None = None,
    application_seq: int = 0,
) -> str:
    """
    Generate a Single Line Diagram as PDF with SVG preview.
    Returns the file ID for download and the SVG preview string.

    If a template was cached by find_matching_templates, it will be
    AUTOMATICALLY deep-merged with requirements before generation.
    This ensures professional cable specs, busbar config, etc. are preserved.

    IMPORTANT: Always pass application_seq so the template auto-merge works.

    Args:
        requirements: SLD requirements dictionary. Can contain only user-specified
            fields — missing fields will be inherited from the cached template.
        application_info: Optional application details (address, postal code, etc.)
        application_seq: Application ID for template cache lookup (required for auto-merge)
    """
    # Log full requirements for diagnostics
    logger.info(f"generate_sld called with requirements: {json.dumps(requirements, ensure_ascii=False, default=str)}")
    if application_info:
        logger.info(f"generate_sld application_info: {json.dumps(application_info, ensure_ascii=False, default=str)}")

    # ── Template auto-merge ─────────────────────────────
    # Resolve application_seq from parameter or application_info
    app_seq = application_seq or (application_info or {}).get("application_seq", 0)
    if isinstance(app_seq, str):
        try:
            app_seq = int(app_seq)
        except (ValueError, TypeError):
            app_seq = 0

    if app_seq:
        from app.sld.template_cache import retrieve
        cached_template = retrieve(app_seq)

        if cached_template:
            from app.sld.template_merge import deep_merge_requirements
            logger.info(
                "Auto-merging template with user requirements (app_seq=%d). "
                "User circuits=%d, Template circuits=%d",
                app_seq,
                len(requirements.get("sub_circuits", [])),
                len(cached_template.get("sub_circuits", [])),
            )
            requirements = deep_merge_requirements(cached_template, requirements)
            logger.info(
                "Post-merge requirements: %s",
                json.dumps(requirements, ensure_ascii=False, default=str),
            )
        else:
            logger.info("No cached template for app_seq=%d, using requirements as-is", app_seq)

    # 입력 검증: 필수 필드 확인
    required_fields = ["supply_type", "kva", "main_breaker", "busbar_rating", "sub_circuits"]
    missing = [f for f in required_fields if f not in requirements or not requirements[f]]
    if missing:
        return json.dumps({
            "success": False,
            "error": f"Missing required fields: {missing}. "
                     "Please gather all requirements before generating.",
        })

    # ELCB/RCCB is MANDATORY per SS 638 — block generation if missing
    elcb = requirements.get("elcb")
    if not elcb or not isinstance(elcb, dict) or not elcb.get("rating"):
        return json.dumps({
            "success": False,
            "error": "ELCB/RCCB is MANDATORY per SS 638. "
                     "Add 'elcb' dict with 'rating', 'sensitivity_ma', 'poles'. "
                     "If user specified RCCB, include '\"type\": \"RCCB\"'. "
                     "Generation blocked until earth leakage protection is specified.",
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

    # ── Defensive SS 638 compliance check ──────────────────────
    # Even if agent skipped validate_sld_requirements step, block non-compliant generation.
    from app.sld.sld_spec import validate_sld_requirements as spec_validate
    spec_input = {
        "kva": requirements.get("kva", 0),
        "supply_type": requirements.get("supply_type", ""),
        "breaker_rating": (main_breaker.get("rating") or main_breaker.get("rating_A") or 0),
        "breaker_type": main_breaker.get("type", ""),
        "breaker_poles": main_breaker.get("poles", ""),
        "breaker_ka": main_breaker.get("fault_kA", 0),
        "circuits": [
            {"name": sc.get("name", ""), "breaker_rating": sc.get("breaker_rating", 0),
             "breaker_type": sc.get("breaker_type", "MCB")}
            for sc in sub_circuits
        ],
    }
    spec_result = spec_validate(spec_input)
    if spec_result.errors:
        return json.dumps({
            "success": False,
            "error": "SS 638 compliance check failed. Call validate_sld_requirements first.",
            "spec_errors": spec_result.errors,
            "spec_warnings": spec_result.warnings,
        })

    from app.sld.generator import SldGenerator
    from app.sld.track_router import decide_track

    file_id = uuid.uuid4().hex[:12]
    pdf_path = os.path.join(settings.temp_file_dir, f"{file_id}.pdf")
    svg_path = os.path.join(settings.temp_file_dir, f"{file_id}.svg")
    dxf_path = os.path.join(settings.temp_file_dir, f"{file_id}.dxf")

    try:
        # ── Track A/B routing ──────────────────────
        matched_template = None
        if app_seq:
            from app.sld.template_cache import retrieve_matched_template
            matched_template = retrieve_matched_template(app_seq)

        track_decision = decide_track(requirements, matched_template)
        logger.info(f"Track decision: {track_decision.track} — {track_decision.reason}")

        if track_decision.track == "A" and track_decision.template_path:
            # Track A: Use real LEW PDF with title block replacement
            from app.sld.pdf_editor import PdfTemplateEditor
            app = application_info or {}
            with PdfTemplateEditor(track_decision.template_path) as editor:
                editor.replace_title_block({
                    "client_name": app.get("clientName", ""),
                    "address": app.get("address", ""),
                    "project_name": app.get("address", ""),
                    "lew_name": app.get("assignedLewName", ""),
                    "lew_licence": app.get("assignedLewLicenceNo", ""),
                    "date": "",
                })
                editor.save(pdf_path)
            component_count = 0
            track_info = "Track A (template reuse)"
        else:
            # Track B: Generate new SLD with DXF + ReportLab + SVG
            generator = SldGenerator()
            result = generator.generate(
                requirements=requirements,
                application_info=application_info or {},
                pdf_output_path=pdf_path,
                svg_output_path=svg_path,
                backend_type="dxf",
            )
            component_count = result.get("component_count", 0)
            track_info = "Track B (generated)"

        # Generation succeeded — clean up template cache
        if app_seq:
            from app.sld.template_cache import remove
            remove(app_seq)

        return json.dumps({
            "success": True,
            "file_id": file_id,
            "component_count": component_count,
            "track": track_decision.track,
            "message": f"SLD generated successfully ({track_info}, {component_count} components). "
                       "The SVG preview is now displayed in the preview panel on the right. "
                       "The user can review it there. Do NOT include or describe any SVG code in your response.",
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
        return json.dumps({
            "success": True,
            "file_id": file_id,
            "message": "SVG preview is now displayed in the preview panel on the right. "
                       "Do NOT include or describe any SVG code in your response.",
        })

    return json.dumps({
        "success": False,
        "error": f"No SVG file found for file_id={file_id}",
    })


# ── Tool 6: Extract SLD Data ──────────────────────────

@tool
def extract_sld_data(user_input: str) -> str:
    """
    Extract structured SLD data from user-provided text or description.
    Analyzes the input to identify incoming supply specs, outgoing circuits,
    and client information, then validates against SS 638/CP 5 standards.

    Use this tool when the user provides SLD information as text or
    describes an existing electrical installation for SLD generation.

    The extracted data is automatically validated and corrected per
    Singapore electrical standards. Returns a structured JSON with:
    - extracted: Raw parsed data
    - validation: Errors, warnings, auto-corrections
    - generation_ready: Requirements dict ready for generate_sld()

    Args:
        user_input: Free-form text describing SLD requirements or installation details.
            Can include breaker ratings, cable sizes, circuit descriptions, kVA, etc.
    """
    logger.info("extract_sld_data called with input (%d chars)", len(user_input))

    from app.sld.extraction_schema import (
        format_extraction_result,
        parse_and_validate,
    )

    # For direct text parsing (without Gemini API), attempt JSON parse first
    try:
        raw_json = json.loads(user_input)
        result = parse_and_validate(raw_json)
    except (json.JSONDecodeError, TypeError):
        # Not JSON — use Gemini for extraction
        import asyncio
        from app.sld.extraction_schema import extract_sld_from_text

        try:
            loop = asyncio.get_event_loop()
            if loop.is_running():
                # We're already in an async context (LangGraph)
                import concurrent.futures
                with concurrent.futures.ThreadPoolExecutor() as pool:
                    result = loop.run_in_executor(
                        pool,
                        lambda: asyncio.run(extract_sld_from_text(user_input)),
                    )
                    # Since we can't await here in a sync tool, use synchronous approach
                    result = _sync_extract_sld(user_input)
            else:
                result = asyncio.run(extract_sld_from_text(user_input))
        except RuntimeError:
            result = _sync_extract_sld(user_input)

    # Format for display
    summary = format_extraction_result(result)

    validation = result.get("validation", {})
    has_errors = bool(validation.get("errors"))

    return json.dumps({
        "success": True,
        "pipeline_steps_completed": ["input", "analysis", "validation"],
        "next_step": (
            "Fix the errors above, then re-extract or re-validate."
            if has_errors
            else "Proceed to generate_sld with generation_ready data."
        ),
        "summary": summary,
        "extracted": result.get("extracted", {}),
        "validation": validation,
        "generation_ready": result.get("generation_ready", {}),
    }, ensure_ascii=False, default=str)


def _sync_extract_sld(user_input: str) -> dict:
    """Synchronous wrapper for Gemini SLD extraction."""
    import google.generativeai as genai

    from app.sld.extraction_schema import (
        SLD_EXTRACTION_PROMPT,
        parse_and_validate,
    )

    genai.configure(api_key=settings.gemini_api_key)
    model = genai.GenerativeModel(
        model_name=settings.gemini_model,
        system_instruction=SLD_EXTRACTION_PROMPT,
        generation_config=genai.GenerationConfig(
            response_mime_type="application/json",
            temperature=0.1,
        ),
    )

    response = model.generate_content(user_input)
    raw_text = response.text

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


# ── Tool 7: Find Matching Templates ─────────────────

@tool
def find_matching_templates(
    supply_type: str,
    kva: float,
    application_seq: int = 0,
    circuit_count: int = 0,
    main_breaker_type: str = "",
    metering_type: str = "",
) -> str:
    """
    Find 2-3 most similar real-world SLD templates from the database.
    The best match is automatically cached and will be deep-merged with
    your requirements when generate_sld is called (no manual merge needed).

    The reference PDF image is also provided for visual layout reference.

    ALWAYS pass application_seq so the template can be cached for auto-merge.

    Args:
        supply_type: "single_phase" or "three_phase" (required)
        kva: Installation capacity in kVA (required)
        application_seq: Application ID for template caching (required for auto-merge)
        circuit_count: Number of sub-circuits (0 = unknown, optional)
        main_breaker_type: "MCB", "MCCB", or "ACB" (optional)
        metering_type: "sp_meter" or "ct_meter" (optional)
    """
    from app.sld.template_matcher import (
        convert_pdf_to_image,
        find_similar_templates,
        normalize_template_to_requirements,
    )

    spec = {
        "supply_type": supply_type,
        "kva": kva,
        "circuit_count": circuit_count,
        "main_breaker_type": main_breaker_type,
        "metering_type": metering_type,
    }

    templates = find_similar_templates(spec, limit=3)

    if not templates:
        return json.dumps({
            "matched": False,
            "message": "No similar template found. Proceed with standard generation from scratch.",
            "templates": [],
        }, ensure_ascii=False)

    best = templates[0]

    # 최상위 템플릿 PDF → 이미지 변환 (Vision 참조용)
    image_path = convert_pdf_to_image(best["absolute_path"])
    if image_path:
        best["reference_image_path"] = image_path

    # 템플릿을 generator format으로 정규화
    normalized_base = normalize_template_to_requirements(best["detail"])

    # ── 캐시에 저장 (generate_sld에서 자동 딥 머지 + Track A 라우팅에 사용) ──
    if application_seq and normalized_base:
        from app.sld.template_cache import store
        # Store both normalized template and original matched metadata (for Track A)
        matched_meta = {
            "match_score": best.get("similarity_score", 0),
            "spec": best.get("detail", {}),
            "pdf_path": best.get("absolute_path"),
            "filename": best.get("filename"),
        }
        store(application_seq, normalized_base, matched_template=matched_meta)
        logger.info(
            "Template cached for auto-merge: app_seq=%d, template=%s, score=%.2f",
            application_seq,
            best["filename"],
            best.get("similarity_score", 0),
        )

    # ── Gemini에게는 메타데이터만 반환 (template JSON은 주지 않는다) ──
    # 이유: Gemini가 template JSON을 보면 그대로 복사하여 requirements에 넣음
    #       → deep merge가 no-op이 됨 (user 값이 이미 모든 필드를 채우므로)
    # 해결: template JSON은 캐시에만 저장, Gemini에게는 이미지 + 메타데이터만 제공
    #       → Gemini는 사용자 요청만으로 최소 requirements 생성
    #       → generate_sld에서 template과 자동 deep merge
    result = {
        "matched": True,
        "best_template_filename": best["filename"],
        "similarity_score": best["similarity_score"],
        "best_template_kva": best["kva"],
        "best_template_circuit_count": best["circuit_count"],
        "best_template_main_breaker": best["detail"].get("main_breaker", {}).get("type", ""),
        "reference_image_path": best.get("reference_image_path"),
        "other_templates_summary": [
            {
                "filename": t["filename"],
                "kva": t["kva"],
                "circuit_count": t["circuit_count"],
                "similarity_score": t["similarity_score"],
            }
            for t in templates[1:]
        ],
        "instruction": (
            "A real-world template has been cached and will be AUTOMATICALLY deep-merged "
            "with your requirements when you call generate_sld. "
            "DO NOT generate cable specifications, busbar ratings, metering, or ELCB/RCCB values "
            "unless the user EXPLICITLY specified them. These will be automatically inherited "
            "from the matched template. "
            "Your requirements should contain ONLY: "
            "1) supply_type and kva, "
            "2) sub_circuits with ONLY name, breaker_type, and breaker_rating (NO cable field), "
            "3) main_breaker ONLY if user specified it, "
            "4) elcb ONLY if user specified it. "
            "OMIT all other fields — they will be auto-filled from the template."
        ),
    }

    return json.dumps(result, ensure_ascii=False, default=str)


# ── Tool Registry ───────────────────────────────────

ALL_TOOLS = [
    get_application_details,
    get_standard_specs,
    validate_sld_requirements,
    generate_sld,
    generate_preview,
    extract_sld_data,
    find_matching_templates,
]
