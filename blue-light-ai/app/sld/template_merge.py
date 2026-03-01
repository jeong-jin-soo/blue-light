"""
Programmatic deep merge of template requirements with user requirements.

Strategy:
    Template is the BASE (real-world, production-quality SLD).
    User requirements are the OVERRIDES (only what the user specified).
    Deep merge ensures template values are preserved for anything the user didn't specify.

This replaces the previous approach of asking the LLM to merge — LLMs cannot
reliably preserve exact strings (cable specs, circuit descriptions) from templates.
"""

import copy
import logging
import re

logger = logging.getLogger(__name__)


def deep_merge_requirements(template: dict, user_req: dict) -> dict:
    """
    Merge template (base) with user requirements (overrides).

    Rules:
        1. Scalars (supply_type, kva, voltage, etc.): user wins if present
        2. main_breaker: field-by-field merge
        3. elcb: field-by-field merge
        4. sub_circuits: user list wins if present, but inherits cable specs
           from template for circuits that don't specify cables
        5. incoming_cable: user wins if present
        6. busbar_rating: user wins if present
        7. metering: user wins if present

    Args:
        template: Normalized template requirements (from template_cache).
        user_req: Requirements built by Gemini from user's conversation.

    Returns:
        Merged requirements dict ready for generator.
    """
    if not template:
        logger.info("deep_merge: no template provided, returning user_req as-is")
        return user_req

    if not user_req:
        logger.info("deep_merge: no user_req provided, returning template as-is")
        return copy.deepcopy(template)

    merged = copy.deepcopy(template)

    # ── 1. Scalar fields ──────────────────────────────
    for key in ("supply_type", "kva", "voltage", "earth_protection", "metering",
                "supply_source", "isolator_rating", "isolator_label"):
        user_val = user_req.get(key)
        if user_val is not None and user_val != "" and user_val != 0:
            merged[key] = user_val

    # ── 2. main_breaker: field-by-field merge ─────────
    user_mb = user_req.get("main_breaker")
    if user_mb and isinstance(user_mb, dict):
        tmpl_mb = merged.get("main_breaker", {})
        if not isinstance(tmpl_mb, dict):
            tmpl_mb = {}
        merged["main_breaker"] = _merge_dict_fields(tmpl_mb, user_mb)
    # If user didn't specify main_breaker, template's stays (already in merged)

    # ── 3. elcb: field-by-field merge ─────────────────
    user_elcb = user_req.get("elcb")
    if user_elcb and isinstance(user_elcb, dict):
        tmpl_elcb = merged.get("elcb", {})
        if not isinstance(tmpl_elcb, dict):
            tmpl_elcb = {}
        merged["elcb"] = _merge_dict_fields(tmpl_elcb, user_elcb)

    # ── 4. busbar_rating ──────────────────────────────
    user_busbar = user_req.get("busbar_rating")
    if user_busbar is not None and user_busbar != "" and user_busbar != 0:
        merged["busbar_rating"] = user_busbar

    # ── 5. incoming_cable ─────────────────────────────
    user_cable = user_req.get("incoming_cable")
    if user_cable is not None and user_cable != "":
        merged["incoming_cable"] = user_cable

    # ── 6. sub_circuits: user list wins, inherit cables ─
    user_circuits = user_req.get("sub_circuits")
    if user_circuits and isinstance(user_circuits, list) and len(user_circuits) > 0:
        tmpl_circuits = template.get("sub_circuits", [])
        merged["sub_circuits"] = _inherit_cable_specs(user_circuits, tmpl_circuits)
    # If user didn't specify sub_circuits, template's stay (already in merged)

    # ── 7. Post-merge SS 638 compliance corrections ──
    _apply_ss638_corrections(merged)

    # ── Logging ───────────────────────────────────────
    tmpl_circuit_count = len(template.get("sub_circuits", []))
    merged_circuit_count = len(merged.get("sub_circuits", []))
    logger.info(
        "deep_merge complete: template=%d circuits, user=%d circuits, merged=%d circuits",
        tmpl_circuit_count,
        len(user_circuits) if user_circuits else 0,
        merged_circuit_count,
    )

    return merged


def _merge_dict_fields(base: dict, override: dict) -> dict:
    """
    Field-by-field merge of two dicts. Override wins for non-empty values.

    Used for main_breaker, elcb, and other nested dicts.
    """
    result = copy.deepcopy(base)
    for key, val in override.items():
        if val is not None and val != "" and val != 0:
            result[key] = val
    return result


def _inherit_cable_specs(
    user_circuits: list[dict],
    template_circuits: list[dict],
) -> list[dict]:
    """
    For each user circuit missing a cable spec, inherit from template.

    Matching priority:
        1. Same breaker_rating AND same breaker_type → exact match
        2. Same breaker_rating (any type) → rating match
        3. Same breaker_type → type match
        4. First template circuit with a cable → fallback

    Args:
        user_circuits: Circuits from Gemini's output (user-specified).
        template_circuits: Circuits from the matched template.

    Returns:
        User circuits with cable specs inherited where missing.
    """
    if not template_circuits:
        return user_circuits

    # Build lookup indexes from template circuits
    # Key: (normalized_rating, breaker_type) → cable
    exact_match: dict[tuple, str] = {}
    # Key: normalized_rating → cable
    rating_match: dict[str, str] = {}
    # Key: breaker_type → cable
    type_match: dict[str, str] = {}
    # Fallback: first template cable
    fallback_cable = ""

    for tc in template_circuits:
        cable = tc.get("cable", "")
        if not cable:
            continue

        if not fallback_cable:
            fallback_cable = cable

        tc_rating = _normalize_rating(tc.get("breaker_rating", ""))
        tc_type = str(tc.get("breaker_type", "")).upper()

        if tc_rating and tc_type:
            key = (tc_rating, tc_type)
            if key not in exact_match:
                exact_match[key] = cable

        if tc_rating and tc_rating not in rating_match:
            rating_match[tc_rating] = cable

        if tc_type and tc_type not in type_match:
            type_match[tc_type] = cable

    # Also inherit breaker_characteristic and fault_kA from template if missing
    tmpl_char_by_rating: dict[str, str] = {}
    tmpl_fault_by_rating: dict[str, int | float] = {}
    for tc in template_circuits:
        tc_rating = _normalize_rating(tc.get("breaker_rating", ""))
        if tc_rating:
            char = tc.get("breaker_characteristic", "")
            if char and tc_rating not in tmpl_char_by_rating:
                tmpl_char_by_rating[tc_rating] = char
            fault = tc.get("fault_kA", 0)
            if fault and tc_rating not in tmpl_fault_by_rating:
                tmpl_fault_by_rating[tc_rating] = fault

    result = []
    inherited_count = 0

    for uc in user_circuits:
        circuit = copy.deepcopy(uc)
        uc_rating = _normalize_rating(uc.get("breaker_rating", ""))
        uc_type = str(uc.get("breaker_type", "")).upper()

        # Inherit cable if missing
        if not circuit.get("cable"):
            cable = None

            # Priority 1: exact match (rating + type)
            if uc_rating and uc_type:
                cable = exact_match.get((uc_rating, uc_type))

            # Priority 2: rating match
            if not cable and uc_rating:
                cable = rating_match.get(uc_rating)

            # Priority 3: type match
            if not cable and uc_type:
                cable = type_match.get(uc_type)

            # Priority 4: fallback
            if not cable:
                cable = fallback_cable

            if cable:
                circuit["cable"] = cable
                inherited_count += 1

        # Inherit breaker_characteristic if missing
        if not circuit.get("breaker_characteristic") and uc_rating:
            char = tmpl_char_by_rating.get(uc_rating, "")
            if char:
                circuit["breaker_characteristic"] = char

        # Inherit fault_kA if missing
        if not circuit.get("fault_kA") and uc_rating:
            fault = tmpl_fault_by_rating.get(uc_rating, 0)
            if fault:
                circuit["fault_kA"] = fault

        result.append(circuit)

    if inherited_count:
        logger.info(
            "Cable inheritance: %d/%d circuits inherited cable specs from template",
            inherited_count,
            len(result),
        )

    return result


def _apply_ss638_corrections(merged: dict) -> None:
    """
    Post-merge corrections to ensure SS 638 compliance.

    Fixes known issues where template values don't meet minimum standards:
    - Main breaker fault_kA: minimum 10kA for MCB per SS 638
    - Busbar rating: minimum 100A for installations ≤ 100A main breaker
    """
    mb = merged.get("main_breaker")
    if isinstance(mb, dict):
        mb_type = str(mb.get("type", "")).upper()
        mb_rating = mb.get("rating", 0)
        if isinstance(mb_rating, str):
            mb_rating = int(re.sub(r"[^\d]", "", mb_rating) or "0")
        fault_ka = mb.get("fault_kA", 0)

        # SS 638: MCB main breaker minimum 10kA fault rating
        if mb_type == "MCB" and fault_ka < 10:
            logger.info(
                "SS 638 correction: main_breaker.fault_kA %s → 10 (minimum for MCB)",
                fault_ka,
            )
            mb["fault_kA"] = 10

        # SS 638: MCCB minimum 25kA fault rating
        if mb_type == "MCCB" and fault_ka < 25:
            logger.info(
                "SS 638 correction: main_breaker.fault_kA %s → 25 (minimum for MCCB)",
                fault_ka,
            )
            mb["fault_kA"] = 25

    # Busbar: minimum 100A for installations with main breaker ≤ 100A
    busbar = merged.get("busbar_rating", 0)
    if isinstance(busbar, str):
        busbar = int(re.sub(r"[^\d]", "", busbar) or "0")
    if busbar and busbar < 100:
        logger.info(
            "SS 638 correction: busbar_rating %s → 100 (minimum per SS 638)",
            busbar,
        )
        merged["busbar_rating"] = 100


def _normalize_rating(rating) -> str:
    """
    Normalize breaker rating to a comparable string.
    Handles: "20A", "20", 20, "B20A" → "20"
    """
    if rating is None:
        return ""
    s = str(rating).strip().upper()
    # Extract numeric part
    digits = re.sub(r"[^\d]", "", s)
    return digits if digits else ""
