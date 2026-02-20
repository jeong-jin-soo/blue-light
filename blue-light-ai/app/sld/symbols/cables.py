"""
Cable annotation helpers.
Not a symbol block per se, but used to annotate cable runs on the SLD.
"""


def format_cable_annotation(
    cable_type: str = "XLPE/SWA",
    cores: int = 4,
    size_mm2: float = 16,
    length_m: float | None = None,
) -> str:
    """
    Format a cable annotation string per Singapore convention.

    Examples:
        "4C x 16mm2 XLPE/SWA"
        "4C x 35mm2 PVC/SWA (25m)"
    """
    text = f"{cores}C x {size_mm2}mm2 {cable_type}"
    if length_m:
        text += f" ({length_m}m)"
    return text


def recommend_cable_size(current_a: float, cable_type: str = "XLPE/SWA") -> float:
    """
    Recommend cable size based on current rating.
    Uses approximate current-carrying capacity per SS 638.
    """
    # Approximate XLPE/SWA cable ratings (4-core, buried/tray)
    cable_ratings = [
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

    for size, rating in cable_ratings:
        if rating >= current_a:
            return size

    return cable_ratings[-1][0]  # Largest available
