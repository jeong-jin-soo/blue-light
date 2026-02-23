"""
SLD Layout Engine — automatic component placement.

Uses a top-down tree-based approach:
1. Incoming supply at the top (3-phase / single-phase line representation)
2. Metering (SP kWh Meter)
3. Main breaker below
4. Main busbar horizontally
5. Sub-circuits fan out below the busbar (multi-row if >8)
6. Earth bar with dotted connections

Improvements over v1:
- Dynamic horizontal spacing based on circuit count
- Multi-row sub-circuit layout (max 8 per row)
- Circuit ID labeling (CB-01, CB-02, ...)
- Load info display (kW/A values)
- 3-phase incoming supply lines
- Earth conductor dashed connections
- Drawing area boundary guardrails
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class LayoutConfig:
    """Layout configuration parameters (all in mm)."""

    # Drawing area (A3 landscape minus margins and title block)
    drawing_width: float = 380
    drawing_height: float = 240

    # Component spacing
    vertical_spacing: float = 30     # Between components vertically
    horizontal_spacing: float = 40   # Between sub-circuits (maximum)
    min_horizontal_spacing: float = 28  # Minimum spacing between sub-circuits
    busbar_margin: float = 20        # Margin from edges of busbar

    # Sub-circuit row layout
    max_circuits_per_row: int = 8    # Max sub-circuits in a single busbar row
    row_spacing: float = 55          # Vertical spacing between sub-circuit rows

    # Starting position
    start_x: float = 190            # Center of drawing
    start_y: float = 230            # Top of drawing (below margin)

    # Drawing boundaries (A3 landscape with 10mm margin + title block reserve)
    min_x: float = 15
    max_x: float = 405
    min_y: float = 60               # Title block occupies bottom 55mm


@dataclass
class PlacedComponent:
    """A component placed at a specific position in the layout."""

    symbol_name: str
    x: float
    y: float
    label: str = ""
    rating: str = ""
    cable_annotation: str = ""
    circuit_id: str = ""    # e.g., "CB-01", "DB-1"
    load_info: str = ""     # e.g., "15kW / 21.7A"


@dataclass
class LayoutResult:
    """Result of the layout computation."""

    components: list[PlacedComponent] = field(default_factory=list)
    connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    dashed_connections: list[tuple[tuple[float, float], tuple[float, float]]] = field(default_factory=list)
    busbar_y: float = 0
    busbar_start_x: float = 0
    busbar_end_x: float = 0

    # Supply info for rendering
    supply_type: str = "three_phase"
    voltage: int = 400


def compute_layout(requirements: dict, config: LayoutConfig | None = None) -> LayoutResult:
    """
    Compute the layout for an SLD based on requirements.

    Args:
        requirements: SLD requirements dict with keys:
            - supply_type: "single_phase" or "three_phase"
            - kva: int
            - main_breaker: {"type": str, "rating"|"rating_A": int}
            - busbar_rating: int
            - sub_circuits: [{"name": str, "breaker_type": str, "breaker_rating": int, "cable": str, "load_kw": float}]
            - metering: str (optional)
            - earth_protection: str (optional)
    """
    if config is None:
        config = LayoutConfig()

    result = LayoutResult()
    cx = config.start_x
    y = config.start_y

    # ── 1. Incoming Supply ────────────────────────────
    supply_type = requirements.get("supply_type", "three_phase")
    kva = requirements.get("kva", 0)
    voltage = 400 if supply_type == "three_phase" else 230
    result.supply_type = supply_type
    result.voltage = voltage

    # Supply label (positioned to the left of center line)
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=cx - 40,
        y=y + 10,
        label=f"INCOMING SUPPLY\\P{kva} kVA, {voltage}V, {'3-Phase' if supply_type == 'three_phase' else '1-Phase'}\\P50Hz, SP PowerGrid",
    ))

    # 3-phase supply lines (3 short parallel lines) or single line
    if supply_type == "three_phase":
        # Three parallel incoming lines
        for offset in [-2, 0, 2]:
            result.connections.append(((cx + offset, y + 3), (cx + offset, y - 3)))
        # Merge to single line
        result.connections.append(((cx - 2, y - 3), (cx + 2, y - 3)))
        result.connections.append(((cx, y - 3), (cx, y - 8)))
    else:
        result.connections.append(((cx, y + 3), (cx, y - 8)))
    y -= 8

    # Incoming cable annotation
    incoming_cable = requirements.get("incoming_cable", "")
    if incoming_cable:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=cx + 8,
            y=y + 5,
            label=incoming_cable,
        ))

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
    breaker_type = main_breaker.get("type", "MCCB").upper()
    breaker_rating = main_breaker.get("rating", 0) or main_breaker.get("rating_A", 0)

    cb_symbol = f"CB_{breaker_type}"
    result.components.append(PlacedComponent(
        symbol_name=cb_symbol,
        x=cx - 5,
        y=y - 16,
        label=f"Main {breaker_type}",
        rating=f"{breaker_rating}A",
        circuit_id="CB-MAIN",
    ))
    result.connections.append(((cx, y), (cx, y - 3)))
    y -= 16 + 6
    result.connections.append(((cx, y + 3), (cx, y - 5)))
    y -= 5

    # ── 4. Main Busbar ───────────────────────────────
    sub_circuits = requirements.get("sub_circuits", [])
    num_circuits = max(len(sub_circuits), 1)

    # Dynamic horizontal spacing calculation
    h_spacing = config.horizontal_spacing
    if num_circuits > config.max_circuits_per_row:
        # Use max per row for busbar width calculation
        effective_count = config.max_circuits_per_row
    else:
        effective_count = num_circuits

    # Adjust spacing to fit within drawing area
    max_bus_width = config.max_x - config.min_x - 40  # Leave margin on sides
    desired_width = effective_count * h_spacing + 2 * config.busbar_margin
    if desired_width > max_bus_width:
        h_spacing = max((max_bus_width - 2 * config.busbar_margin) / effective_count,
                        config.min_horizontal_spacing)
        desired_width = effective_count * h_spacing + 2 * config.busbar_margin

    bus_width = max(desired_width, 120)
    bus_start_x = cx - bus_width / 2
    bus_end_x = cx + bus_width / 2

    # Clamp to drawing boundaries
    if bus_start_x < config.min_x:
        bus_start_x = config.min_x
        bus_end_x = bus_start_x + bus_width
    if bus_end_x > config.max_x:
        bus_end_x = config.max_x
        bus_start_x = bus_end_x - bus_width

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

    # ── 5. Sub-circuits (multi-row support) ──────────
    rows = _split_into_rows(sub_circuits, config.max_circuits_per_row)

    for row_idx, row_circuits in enumerate(rows):
        row_count = len(row_circuits)
        # First row uses the main busbar; subsequent rows get their own sub-busbar
        if row_idx == 0:
            busbar_y = y
            row_bus_start = bus_start_x
            row_bus_end = bus_end_x
        else:
            busbar_y = y - row_idx * config.row_spacing
            # Sub-busbar for additional rows
            row_bus_width = row_count * h_spacing + 2 * config.busbar_margin
            row_bus_start = cx - row_bus_width / 2
            row_bus_end = cx + row_bus_width / 2

            # Draw sub-busbar
            result.components.append(PlacedComponent(
                symbol_name="BUSBAR",
                x=row_bus_start,
                y=busbar_y,
                label="",
                rating="",
            ))
            result.busbar_start_x = min(result.busbar_start_x, row_bus_start)
            result.busbar_end_x = max(result.busbar_end_x, row_bus_end)

            # Connection from main busbar to sub-busbar
            result.connections.append(((cx, y - 2), (cx, busbar_y)))

        _place_sub_circuits(
            result, row_circuits, row_idx, row_count,
            busbar_y, row_bus_start, row_bus_end,
            h_spacing, config, sub_circuits,
        )

    # ── 6. Earth Bar ─────────────────────────────────
    # Position earth bar below the lowest row of sub-circuits
    lowest_busbar_y = y - max(0, len(rows) - 1) * config.row_spacing
    earth_y = lowest_busbar_y - 55
    earth_y = max(earth_y, config.min_y + 5)  # Don't overlap title block

    earth_x = bus_start_x + 10
    result.components.append(PlacedComponent(
        symbol_name="EARTH",
        x=earth_x,
        y=earth_y,
        label="EARTH BAR",
    ))

    # Dashed earth conductor from busbar to earth bar
    result.dashed_connections.append(((earth_x + 5, y - 2), (earth_x + 5, earth_y + 10)))

    return result


# ── Helper functions ──────────────────────────────────


def _split_into_rows(sub_circuits: list[dict], max_per_row: int) -> list[list[dict]]:
    """Split sub-circuits into rows of max_per_row each."""
    if not sub_circuits:
        return [[]]
    rows = []
    for i in range(0, len(sub_circuits), max_per_row):
        rows.append(sub_circuits[i:i + max_per_row])
    return rows


def _place_sub_circuits(
    result: LayoutResult,
    row_circuits: list[dict],
    row_idx: int,
    row_count: int,
    busbar_y: float,
    bus_start_x: float,
    bus_end_x: float,
    h_spacing: float,
    config: LayoutConfig,
    all_circuits: list[dict],
) -> None:
    """Place a row of sub-circuits below a busbar."""
    bus_width = bus_end_x - bus_start_x

    for i, circuit in enumerate(row_circuits):
        # Calculate global circuit index for ID
        global_idx = row_idx * config.max_circuits_per_row + i

        # Calculate tap point on busbar (evenly distributed)
        if row_count == 1:
            tap_x = (bus_start_x + bus_end_x) / 2
        elif row_count > 1:
            usable = bus_width - 2 * config.busbar_margin
            tap_x = bus_start_x + config.busbar_margin + i * (usable / (row_count - 1))
        else:
            tap_x = bus_start_x + config.busbar_margin

        # Clamp tap_x to drawing bounds
        tap_x = max(tap_x, config.min_x + 15)
        tap_x = min(tap_x, config.max_x - 15)

        sc_y = busbar_y - 5

        # Vertical drop from busbar
        result.connections.append(((tap_x, busbar_y), (tap_x, sc_y)))

        # Sub-circuit breaker
        sc_breaker_type = circuit.get("breaker_type", "MCB").upper()
        sc_breaker_rating = circuit.get("breaker_rating", 32)
        sc_name = circuit.get("name", f"DB-{global_idx + 1}")
        sc_cable = circuit.get("cable", "")
        sc_load_kw = circuit.get("load_kw", 0)

        # Generate circuit ID
        circuit_id = f"CB-{global_idx + 1:02d}"

        # Calculate load current (if load_kw provided)
        load_info = ""
        if sc_load_kw and sc_load_kw > 0:
            voltage = 400  # Default 3-phase
            current = round(sc_load_kw * 1000 / (voltage * 1.732), 1)
            load_info = f"{sc_load_kw}kW / {current}A"

        cb_sym = f"CB_{sc_breaker_type}"
        result.components.append(PlacedComponent(
            symbol_name=cb_sym,
            x=tap_x - 5,
            y=sc_y - 16,
            label=f"{sc_breaker_type}",
            rating=f"{sc_breaker_rating}A",
            cable_annotation=sc_cable,
            circuit_id=circuit_id,
            load_info=load_info,
        ))

        # Sub-circuit name label below breaker
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=tap_x - 15,
            y=sc_y - 36,
            label=f"{circuit_id}: {sc_name}",
        ))

        # Load info label (below circuit name)
        if load_info:
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=tap_x - 15,
                y=sc_y - 40,
                label=load_info,
            ))

        # Connection from busbar to breaker top
        result.connections.append(((tap_x, sc_y), (tap_x, sc_y - 3)))

        # Tail from breaker bottom
        result.connections.append(((tap_x, sc_y - 19), (tap_x, sc_y - 32)))
