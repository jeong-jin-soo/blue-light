"""
SLD Layout validation — SS 638 compliance validation and auto-correction.

Extracted from engine.py. Contains _validate_and_correct() which validates
requirements against SS 638 and applies auto-corrections.
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)


def _validate_and_correct(requirements: dict) -> dict:
    """Validate requirements against SS 638 and apply auto-corrections.

    Converts from compute_layout's nested format to sld_spec's flat format,
    runs validation, and propagates corrections back.

    Returns a (possibly corrected) copy of requirements. Never mutates the original.

    Raises:
        ValueError: If validation finds hard errors (missing kVA + breaker rating, etc.).
    """
    from app.sld.sld_spec import apply_corrections, validate_sld_requirements

    # ── Normalise metering field ──
    # metering MUST be a string ("ct_meter", "sp_meter", "none", "").
    # If a dict is passed (common mis-specification), extract its "type" key
    # and move the rest to metering_config so downstream CT/SP logic works.
    _raw_metering = requirements.get("metering")
    if isinstance(_raw_metering, dict):
        _m_type = (_raw_metering.get("type") or "").lower().strip()
        # Normalise common aliases → canonical string
        if _m_type in ("ct", "ct_meter", "ct meter"):
            requirements["metering"] = "ct_meter"
        elif _m_type in ("sp", "sp_meter", "sp meter"):
            requirements["metering"] = "sp_meter"
        else:
            requirements["metering"] = _m_type or ""
        # Merge dict contents into metering_config (preserving existing)
        _existing_mc = requirements.get("metering_config", {})
        _merged_mc = {**{k: v for k, v in _raw_metering.items() if k != "type"}, **_existing_mc}
        if _merged_mc:
            requirements["metering_config"] = _merged_mc
        logger.warning(
            "metering was dict — normalised to string %r + metering_config",
            requirements["metering"],
        )

    # Build flat validation input from nested requirements
    main_breaker = requirements.get("main_breaker", {})
    if not isinstance(main_breaker, dict):
        main_breaker = {}
    breaker_rating = (
        main_breaker.get("rating", 0)
        or main_breaker.get("rating_A", 0)
        or requirements.get("breaker_rating", 0)  # top-level fallback
    )
    sub_circuits = requirements.get("sub_circuits", []) or requirements.get("circuits", [])

    # Cable extension uses landlord supply path for validation
    supply_source = requirements.get("supply_source", "")
    if requirements.get("is_cable_extension") and supply_source != "landlord":
        supply_source = "landlord"

    spec_input = {
        "kva": requirements.get("kva", 0),
        "supply_type": requirements.get("supply_type", ""),
        "supply_source": supply_source,
        "breaker_rating": breaker_rating,
        "breaker_type": main_breaker.get("type", "") or requirements.get("breaker_type", ""),
        "breaker_poles": main_breaker.get("poles", "") or requirements.get("breaker_poles", ""),
        "breaker_ka": (
            main_breaker.get("fault_kA", 0)
            or requirements.get("breaker_ka", 0)
            or requirements.get("fault_kA", 0)
        ),
        "metering": requirements.get("metering", ""),
        "is_cable_extension": bool(requirements.get("is_cable_extension")),
        "circuits": [
            {
                "name": sc.get("name", ""),
                "breaker_rating": sc.get("breaker_rating", 0),
                "breaker_type": sc.get("breaker_type", "MCB"),
            }
            for sc in (sub_circuits if isinstance(sub_circuits, list) else [])
        ],
    }

    result = validate_sld_requirements(spec_input)

    # Hard errors → raise ValueError
    if result.errors:
        error_msg = "; ".join(result.errors)
        logger.error("SLD validation failed: %s", error_msg)
        raise ValueError(f"SLD requirements validation failed: {error_msg}")

    # Log warnings (non-blocking)
    for w in result.warnings:
        logger.warning("SLD validation: %s", w)

    # Apply corrections + ensure validated values propagate to main_breaker
    corrected_spec = apply_corrections(spec_input, result) if result.corrections else spec_input
    requirements = dict(requirements)  # Shallow copy
    mb = dict(main_breaker)  # Copy main_breaker too

    # Ensure main_breaker dict has validated values (from corrections or user input)
    mb.setdefault("rating", 0)
    mb.setdefault("type", "")
    mb.setdefault("fault_kA", 0)
    mb.setdefault("poles", "")

    if result.corrections:
        if "breaker_rating" in result.corrections:
            mb["rating"] = corrected_spec["breaker_rating"]
            if "rating_A" in mb:
                mb["rating_A"] = corrected_spec["breaker_rating"]
        if "breaker_type" in result.corrections:
            mb["type"] = corrected_spec["breaker_type"]
        if "breaker_ka" in result.corrections:
            mb["fault_kA"] = corrected_spec["breaker_ka"]
        if "breaker_poles" in result.corrections:
            mb["poles"] = corrected_spec["breaker_poles"]
        # Propagate metering correction to requirements
        if "metering" in result.corrections:
            requirements["metering"] = corrected_spec["metering"]

    # Fill from validated spec_input for fields not already set in main_breaker
    if not mb["rating"] and corrected_spec.get("breaker_rating"):
        mb["rating"] = corrected_spec["breaker_rating"]
    if not mb["type"] and corrected_spec.get("breaker_type"):
        mb["type"] = corrected_spec["breaker_type"]
    if not mb["fault_kA"] and corrected_spec.get("breaker_ka"):
        mb["fault_kA"] = corrected_spec["breaker_ka"]
    if not mb["poles"] and corrected_spec.get("breaker_poles"):
        mb["poles"] = corrected_spec["breaker_poles"]

    # Fill metering from validated spec if not already set
    if not requirements.get("metering") and corrected_spec.get("metering"):
        requirements["metering"] = corrected_spec["metering"]

    requirements["main_breaker"] = mb

    return requirements
