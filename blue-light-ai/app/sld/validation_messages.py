"""
SLD validation error/warning message constants.

Centralized message templates for validation rules shared between:
- ``sld_spec.py`` (defensive pre-generation validation with auto-correction)
- ``tools.py`` (interactive agent validation, strict SS 638 compliance)

Only truly shared or repeated message prefixes/templates are extracted here.
Dynamic (parameterized) messages remain inline as f-strings in their respective modules.
"""


# ── SS 638 Compliance Rule Names ──────────────────────────────
# Prefix constants used for consistent error categorization.

SS638_PREFIX = "SS 638"

# Rules that appear in both sld_spec.py and tools.py
BUSBAR_UNDERSIZED = f"{SS638_PREFIX}: busbar_rating must be >= main_breaker rating"
ACB_REQUIRED_ABOVE_630A = f"{SS638_PREFIX}: ACB required for rating > 630A"
MCB_MAX_100A = f"{SS638_PREFIX}: MCB max rating is 100A"
ELCB_MANDATORY = (
    f"{SS638_PREFIX}: Earth leakage protection (ELCB/RCCB) is MANDATORY. "
    "Add 'elcb' dict with 'rating', 'sensitivity_ma', 'poles'. "
    "If user specified RCCB, include '\"type\": \"RCCB\"'."
)
ELCB_RECOMMENDED = (
    f"{SS638_PREFIX}: ELCB is mandatory — add 'elcb' with 'rating' and "
    "'sensitivity_ma' (e.g., 100mA for distribution, 30mA for sockets)"
)

# Phase mismatch detection
PHASE_MISMATCH_2POLE = "CRITICAL MISMATCH: ELCB/RCCB is 2-pole (single-phase)"
RESIDENTIAL_LOAD_PATTERN = "RESIDENTIAL LOAD PATTERN DETECTED"

# Field requirements
MISSING_KVA_OR_BREAKER = (
    "Either 'kva' or 'breaker_rating' must be provided. "
    "Cannot determine installation specifications."
)

# Metering
METERING_NOT_SPECIFIED = "metering not specified — will use standard SP kWh meter"
INCOMING_CABLE_NOT_SPECIFIED = (
    "Incoming cable not specified — recommend specifying per SS 638 "
    "Table 4D1A for the installation's kVA tier"
)
