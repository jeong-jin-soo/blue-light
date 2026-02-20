"""
Singapore electrical standards reference data.
SS 638, CP 5, IEC 60617 lookup tables.
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
