"""
SLD Layout Engine — automatic component placement.

Uses a top-down tree-based approach:
1. Incoming supply at the top
2. Main breaker below
3. Busbar horizontally
4. Sub-circuits fan out below the busbar
"""

from dataclasses import dataclass, field


@dataclass
class LayoutConfig:
    """Layout configuration parameters (all in mm)."""

    # Drawing area (A3 landscape minus margins and title block)
    drawing_width: float = 380
    drawing_height: float = 240

    # Component spacing
    vertical_spacing: float = 30     # Between components vertically
    horizontal_spacing: float = 40   # Between sub-circuits
    busbar_margin: float = 30        # Margin from edges of busbar

    # Starting position
    start_x: float = 190            # Center of drawing
    start_y: float = 230            # Top of drawing (below margin)


@dataclass
class PlacedComponent:
    """A component placed at a specific position in the layout."""

    symbol_name: str
    x: float
    y: float
    label: str = ""
    rating: str = ""
    cable_annotation: str = ""


@dataclass
class LayoutResult:
    """Result of the layout computation."""

    components: list[PlacedComponent] = field(default_factory=list)
    connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    busbar_y: float = 0
    busbar_start_x: float = 0
    busbar_end_x: float = 0


def compute_layout(requirements: dict, config: LayoutConfig | None = None) -> LayoutResult:
    """
    Compute the layout for an SLD based on requirements.

    Args:
        requirements: SLD requirements dict with keys:
            - supply_type: "single_phase" or "three_phase"
            - kva: int
            - main_breaker: {"type": str, "rating": int}
            - busbar_rating: int
            - sub_circuits: [{"name": str, "breaker_type": str, "breaker_rating": int, "cable": str}]
            - metering: str (optional)
            - earth_protection: str (optional)
    """
    if config is None:
        config = LayoutConfig()

    result = LayoutResult()
    cx = config.start_x
    y = config.start_y

    # ── 1. Incoming Supply Label ─────────────────────
    supply_type = requirements.get("supply_type", "three_phase")
    kva = requirements.get("kva", 0)
    voltage = 400 if supply_type == "three_phase" else 230

    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=cx - 30,
        y=y + 10,
        label=f"SP PowerGrid\\P{voltage}V {'3-Phase' if supply_type == 'three_phase' else 'Single Phase'}\\P50Hz",
    ))

    # Vertical line from supply
    result.connections.append(((cx, y), (cx, y - 8)))
    y -= 8

    # ── 2. Metering (if specified) ───────────────────
    metering = requirements.get("metering", "sp_meter")
    if metering:
        result.components.append(PlacedComponent(
            symbol_name="KWH_METER",
            x=cx - 8,
            y=y - 16,
            label="SP kWh Meter",
        ))
        result.connections.append(((cx, y), (cx, y - 3)))
        y -= 16 + 6
        result.connections.append(((cx, y + 3), (cx, y - 5)))
        y -= 5

    # ── 3. Main Circuit Breaker ──────────────────────
    main_breaker = requirements.get("main_breaker", {})
    breaker_type = main_breaker.get("type", "MCCB")
    breaker_rating = main_breaker.get("rating", 0)

    cb_symbol = f"CB_{breaker_type}"
    result.components.append(PlacedComponent(
        symbol_name=cb_symbol,
        x=cx - 5,
        y=y - 16,
        label=f"{breaker_type}",
        rating=f"{breaker_rating}A",
    ))
    result.connections.append(((cx, y), (cx, y - 3)))
    y -= 16 + 6
    result.connections.append(((cx, y + 3), (cx, y - 5)))
    y -= 5

    # ── 4. Main Busbar ───────────────────────────────
    sub_circuits = requirements.get("sub_circuits", [])
    num_circuits = max(len(sub_circuits), 1)

    # Calculate busbar width based on number of sub-circuits
    bus_width = max(num_circuits * config.horizontal_spacing + 2 * config.busbar_margin, 120)
    bus_start_x = cx - bus_width / 2
    bus_end_x = cx + bus_width / 2

    busbar_rating = requirements.get("busbar_rating", breaker_rating)

    result.busbar_y = y
    result.busbar_start_x = bus_start_x
    result.busbar_end_x = bus_end_x

    result.components.append(PlacedComponent(
        symbol_name="BUSBAR",
        x=bus_start_x,
        y=y,
        label=f"MAIN SWITCHBOARD (MSB) — {voltage}V",
        rating=f"{busbar_rating}A Busbar",
    ))

    # Connection from main breaker to busbar
    result.connections.append(((cx, y + 5), (cx, y)))

    # ── 5. Sub-circuits ──────────────────────────────
    for i, circuit in enumerate(sub_circuits):
        # Calculate tap point on busbar
        tap_x = bus_start_x + config.busbar_margin + i * config.horizontal_spacing
        if num_circuits > 1:
            usable = bus_width - 2 * config.busbar_margin
            tap_x = bus_start_x + config.busbar_margin + i * (usable / (num_circuits - 1))

        sc_y = y - 5

        # Vertical drop from busbar
        result.connections.append(((tap_x, y), (tap_x, sc_y)))

        # Sub-circuit breaker
        sc_breaker_type = circuit.get("breaker_type", "MCB")
        sc_breaker_rating = circuit.get("breaker_rating", 32)
        sc_name = circuit.get("name", f"DB-{i + 1}")
        sc_cable = circuit.get("cable", "")

        cb_sym = f"CB_{sc_breaker_type}"
        result.components.append(PlacedComponent(
            symbol_name=cb_sym,
            x=tap_x - 5,
            y=sc_y - 16,
            label=f"{sc_breaker_type}",
            rating=f"{sc_breaker_rating}A",
            cable_annotation=sc_cable,
        ))

        # Sub-circuit label below breaker
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=tap_x - 12,
            y=sc_y - 32,
            label=sc_name,
        ))

        # Connection from busbar to breaker top
        result.connections.append(((tap_x, sc_y), (tap_x, sc_y - 3)))

        # Tail from breaker bottom
        result.connections.append(((tap_x, sc_y - 19), (tap_x, sc_y - 28)))

    # ── 6. Earth Bar ─────────────────────────────────
    earth_y = y - 55
    result.components.append(PlacedComponent(
        symbol_name="EARTH",
        x=bus_start_x + 10,
        y=earth_y,
        label="EARTH BAR",
    ))

    return result
