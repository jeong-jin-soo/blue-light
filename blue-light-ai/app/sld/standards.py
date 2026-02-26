"""
Singapore electrical standards reference data.
SS 638:2018, CP 5:2018, IEC 60617 lookup tables.
"""

# Standard breaker ratings (Amps)
STANDARD_BREAKER_RATINGS = [
    6, 10, 16, 20, 25, 32, 40, 50, 63, 80,
    100, 125, 160, 200, 250, 315, 400, 500, 630,
    800, 1000, 1250, 1600, 2000, 2500, 3200,
]

# Cable sizing reference (4-core XLPE/SWA, installed in tray/buried)
# (size_mm2, current_rating_A)
CABLE_RATINGS_XLPE_SWA = [
    (1.5, 18),
    (2.5, 25),
    (4, 34),
    (6, 43),
    (10, 60),
    (16, 80),
    (25, 105),
    (35, 130),
    (50, 155),
    (70, 195),
    (95, 235),
    (120, 270),
    (150, 310),
    (185, 350),
    (240, 410),
    (300, 460),
    (400, 530),
    (500, 600),
]

# Earth conductor sizing per SS 638 Table 54A
# (main_conductor_max_mm2, earth_conductor_mm2)
# Rule: ≤16mm² → same as main, 16-35mm² → 16mm², >35mm² → half of main
EARTH_CONDUCTOR_TABLE = [
    (1.5, 1.5),
    (2.5, 2.5),
    (4, 4),
    (6, 6),
    (10, 10),
    (16, 16),
    (25, 16),
    (35, 16),
    (50, 25),
    (70, 35),
    (95, 50),
    (120, 70),
    (150, 70),
    (185, 95),
    (240, 120),
    (300, 150),
    (400, 185),
    (500, 240),
]

# Standard fault levels (kA) for main breakers — Singapore SP PowerGrid LV network
FAULT_LEVEL_DEFAULTS = {
    "MCB": 10,
    "MCCB": 25,
    "ACB": 50,
}

# Sub-circuit fault levels (kA) — per actual SLD sample analysis
# Sub-circuit MCBs in Singapore residential/commercial use 6kA (not 10kA)
SUB_CIRCUIT_FAULT_DEFAULTS = {
    "MCB": 6,
    "MCCB": 25,
    "ACB": 50,
}


def get_breaker_rating(current_a: float) -> int:
    """Get the next standard breaker rating above the given current."""
    for rating in STANDARD_BREAKER_RATINGS:
        if rating >= current_a:
            return rating
    return STANDARD_BREAKER_RATINGS[-1]


def get_cable_size(current_a: float) -> float:
    """Get the minimum cable size for the given current rating."""
    for size, rating in CABLE_RATINGS_XLPE_SWA:
        if rating >= current_a:
            return size
    return CABLE_RATINGS_XLPE_SWA[-1][0]


def get_earth_conductor_size(main_cable_mm2: float) -> float:
    """
    Get the minimum earth conductor size per SS 638 Table 54A.

    Rule:
    - Main ≤ 16mm²: same size as main
    - Main 16–35mm²: 16mm²
    - Main > 35mm²: half of main (rounded to next standard size)
    """
    for main_size, earth_size in EARTH_CONDUCTOR_TABLE:
        if main_size >= main_cable_mm2:
            return earth_size
    return EARTH_CONDUCTOR_TABLE[-1][1]


def get_breaker_poles(supply_type: str = "three_phase", breaker_type: str = "MCCB") -> str:
    """
    Get pole configuration based on supply type.

    - Single-phase: 2P (1P + N)
    - Three-phase: 3P or 4P (4P includes neutral)
    For main breakers and ELCBs, 4P is standard for three-phase.
    """
    if supply_type == "single_phase":
        return "2P"
    return "4P"


def calculate_current(kva: float, voltage: float = 400, phase: str = "three_phase") -> float:
    """Calculate full load current from kVA rating."""
    if phase == "three_phase":
        return kva * 1000 / (voltage * 1.732)
    return kva * 1000 / voltage


def get_breaker_type(current_a: float) -> str:
    """Determine breaker type based on current rating."""
    if current_a > 630:
        return "ACB"
    elif current_a > 100:
        return "MCCB"
    else:
        return "MCB"


def get_fault_level(breaker_type: str, kva: float = 0) -> int:
    """
    Get appropriate fault level (kA) for a breaker.

    Based on typical Singapore SP PowerGrid LV network prospective
    fault currents and breaker capabilities.
    """
    if kva >= 500:
        return 65
    if kva >= 200:
        return 36
    return FAULT_LEVEL_DEFAULTS.get(breaker_type.upper(), 25)
