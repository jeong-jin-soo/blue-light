"""
SLD Layout Engine -- automatic component placement (v5).

Uses a top-down tree-based approach:
1. Incoming supply at the top (3-phase / single-phase line representation)
   with current flow direction arrow
2. Isolator (disconnect switch) for >= 45kVA
3. CT Metering for >= 45kVA (Current Transformer + kWh Meter)
4. SP kWh Meter (direct metering for < 45kVA)
5. Main breaker below (with kA fault rating & pole configuration)
6. Main busbar horizontally (double-line professional representation)
7. ELCB standalone branch on far-left of busbar
8. Sub-circuit breakers with circuit IDs and cable annotations
9. Earth bar with dashed conductor connections + conductor size label

Key features:
- Cable dict auto-formatting (handles both str and dict input)
- Compact incoming chain spacing (3mm component gaps)
- Isolator auto-added for >= 45kVA installations
- CT metering auto-added for >= 45kVA installations
- kA fault level & pole configuration on main breaker
- Earth conductor size annotation (per SS 638 Table 54A)
- Current flow direction arrow at incoming supply
- ELCB label positioned to the LEFT to avoid sub-circuit overlap
- Phase labels (L1, L2, L3, N) or (L, N) for single-phase
- Circuit naming scheme (LS/LP/IS/SP)
- Location labels with approved load info
- Earth bar dynamically positioned above cable schedule
- Professional busbar double-line representation
"""

from __future__ import annotations

from dataclasses import dataclass, field


# -- Cable formatting helper --

def format_cable_spec(cable_input) -> str:
    """
    Format cable specification into a standard string.

    Handles:
    - str: returned as-is (e.g., "4C x 16mm2 XLPE/SWA")
    - dict: formatted from keys like {cores, type, size_mm2}
    - None/empty: returns empty string
    """
    if not cable_input:
        return ""

    if isinstance(cable_input, str):
        return cable_input

    if isinstance(cable_input, dict):
        cores = cable_input.get("cores", 4)
        cable_type = cable_input.get("type", "XLPE/SWA")
        size = cable_input.get("size_mm2", cable_input.get("size", ""))
        if size:
            return f"1x{cores}C {size}mm² {cable_type}"
        return f"{cores}C {cable_type}"

    return str(cable_input)


# -- Data classes --

@dataclass
class LayoutConfig:
    """Layout configuration parameters (all in mm)."""

    # Drawing area (A3 landscape minus margins and title block)
    drawing_width: float = 380
    drawing_height: float = 240

    # Component spacing (increased for professional look)
    vertical_spacing: float = 40      # Between components vertically
    horizontal_spacing: float = 50    # Between sub-circuits (maximum)
    min_horizontal_spacing: float = 28  # Minimum spacing between sub-circuits
    busbar_margin: float = 25         # Margin from edges of busbar

    # Sub-circuit row layout
    max_circuits_per_row: int = 12    # Max sub-circuits in a single busbar row (A3 fits up to 12)
    row_spacing: float = 65           # Vertical spacing between sub-circuit rows

    # Starting position
    start_x: float = 190             # Center of drawing
    start_y: float = 270             # Top of drawing (below margin)

    # Drawing boundaries (A3 landscape with 10mm margin + title block reserve)
    min_x: float = 15
    max_x: float = 405
    min_y: float = 62                # Title block occupies bottom ~55mm

    # Symbol dimension references (must match symbols/*.py)
    breaker_w: float = 14
    breaker_h: float = 20
    mcb_w: float = 10
    mcb_h: float = 16
    meter_size: float = 20
    isolator_h: float = 18
    ct_size: float = 14              # CT (Current Transformer) symbol size
    stub_len: float = 5              # Connection stub length


@dataclass
class PlacedComponent:
    """A component placed at a specific position in the layout."""

    symbol_name: str
    x: float
    y: float
    label: str = ""
    rating: str = ""
    cable_annotation: str = ""
    circuit_id: str = ""     # e.g., "CB-01", "LS1"
    load_info: str = ""      # e.g., "15kW / 21.7A"
    rotation: float = 0.0    # Text rotation for vertical labels


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

    # Symbols used -- for dynamic legend generation
    symbols_used: set[str] = field(default_factory=set)


def compute_layout(requirements: dict, config: LayoutConfig | None = None) -> LayoutResult:
    """
    Compute the layout for an SLD based on requirements.

    Args:
        requirements: SLD requirements dict with keys:
            - supply_type: "single_phase" or "three_phase"
            - kva: int
            - main_breaker: {"type": str, "rating"|"rating_A": int,
                             "poles": str, "fault_kA": int}
            - busbar_rating: int
            - sub_circuits: [{"name": str, "breaker_type": str, "breaker_rating": int,
                              "cable": str|dict, "load_kw": float, "phase": str}]
            - metering: str (optional)
            - earth_protection: str (optional)
            - incoming_cable: str|dict (optional)
            - isolator_rating: int (optional)
            - elcb: {"rating": int, "sensitivity_ma": int} (optional)
            - earth_conductor_mm2: float (optional)
    """
    if config is None:
        config = LayoutConfig()

    result = LayoutResult()
    cx = config.start_x
    y = config.start_y

    # -- 1. Incoming Supply --
    supply_type = requirements.get("supply_type", "three_phase")
    kva = requirements.get("kva", 0)
    voltage = 400 if supply_type == "three_phase" else 230
    result.supply_type = supply_type
    result.voltage = voltage

    # Supply label
    phase_text = "3-Phase 4-Wire" if supply_type == "three_phase" else "1-Phase 2-Wire"
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=cx - 55,
        y=y + 5,
        label=f"INCOMING SUPPLY\\P{kva} kVA, {voltage}V, {phase_text}\\P50Hz, SP PowerGrid",
    ))

    # Current flow direction arrow (downward pointing arrow at incoming)
    result.components.append(PlacedComponent(
        symbol_name="FLOW_ARROW",
        x=cx + 25,
        y=y + 2,
    ))

    # Phase lines with labels
    if supply_type == "three_phase":
        spacing = 5  # 5mm between phase lines
        for offset, label in [(-spacing*1.5, "L1"), (-spacing*0.5, "L2"), (spacing*0.5, "L3"), (spacing*1.5, "N")]:
            # Phase line
            result.connections.append(((cx + offset, y + 5), (cx + offset, y - 5)))
            # Phase label
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=cx + offset - 2,
                y=y + 9,
                label=label,
            ))
        # Merge to single line
        result.connections.append(((cx - spacing * 1.5, y - 5), (cx + spacing * 1.5, y - 5)))
        result.connections.append(((cx, y - 5), (cx, y - 10)))
    else:
        # Single-phase: L and N labels
        spacing = 5
        for offset, label in [(-spacing * 0.5, "L"), (spacing * 0.5, "N")]:
            result.connections.append(((cx + offset, y + 5), (cx + offset, y - 5)))
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=cx + offset - 2,
                y=y + 9,
                label=label,
            ))
        result.connections.append(((cx - spacing * 0.5, y - 5), (cx + spacing * 0.5, y - 5)))
        result.connections.append(((cx, y - 5), (cx, y - 10)))
    y -= 10

    # Incoming cable annotation (with dict formatting fix)
    incoming_cable = requirements.get("incoming_cable", "")
    cable_text = format_cable_spec(incoming_cable)
    if cable_text:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=cx + 12,
            y=y + 5,
            label=cable_text,
        ))

    # -- 2. Isolator (if specified or default for >= 45kVA) --
    isolator_rating = requirements.get("isolator_rating", 0)
    if not isolator_rating and kva >= 45:
        main_breaker = requirements.get("main_breaker", {})
        mb_rating = main_breaker.get("rating", 0) or main_breaker.get("rating_A", 0)
        if mb_rating:
            isolator_rating = _next_standard_rating(mb_rating)

    if isolator_rating:
        result.components.append(PlacedComponent(
            symbol_name="ISOLATOR",
            x=cx - 6,
            y=y - config.isolator_h - 8,
            label="ISOLATOR",
            rating=f"{isolator_rating}A TPN",
        ))
        result.connections.append(((cx, y), (cx, y - 3)))
        y -= config.isolator_h + 8 + 5    # Reduced: align y with bottom stub end
        result.connections.append(((cx, y), (cx, y - 3)))  # 3mm gap
        y -= 3
        result.symbols_used.add("ISOLATOR")

    # -- 3. Metering --
    metering = requirements.get("metering", "sp_meter")

    # CT metering for >= 45kVA installations
    if kva >= 45 and metering:
        # Current Transformer
        ct_r = config.ct_size / 2
        result.components.append(PlacedComponent(
            symbol_name="CT",
            x=cx - ct_r,
            y=y - config.ct_size - 5,
            label="CT",
        ))
        y -= config.ct_size + 5 + 5
        result.connections.append(((cx, y), (cx, y - 3)))
        y -= 3
        result.symbols_used.add("CT")

    if metering:
        meter_r = config.meter_size / 2
        result.components.append(PlacedComponent(
            symbol_name="KWH_METER",
            x=cx - meter_r,
            y=y - config.meter_size - 5,
            label="SP kWh Meter",
        ))
        y -= config.meter_size + 5 + 5    # Reduced: align y with bottom stub end
        result.connections.append(((cx, y), (cx, y - 3)))  # 3mm gap
        y -= 3
        result.symbols_used.add("KWH_METER")

    # -- 4. Main Circuit Breaker --
    main_breaker = requirements.get("main_breaker", {})
    breaker_type = str(main_breaker.get("type", "MCCB")).upper()
    breaker_rating = main_breaker.get("rating", 0) or main_breaker.get("rating_A", 0)
    breaker_poles = main_breaker.get("poles", "")
    breaker_fault_kA = main_breaker.get("fault_kA", 0)

    # Auto-determine poles if not specified
    if not breaker_poles:
        breaker_poles = "4P" if supply_type == "three_phase" else "2P"

    # Auto-determine fault level if not specified
    if not breaker_fault_kA:
        from app.sld.standards import get_fault_level
        breaker_fault_kA = get_fault_level(breaker_type, kva)

    if breaker_type == "ACB":
        cb_w, cb_h = 16, 22
    elif breaker_type == "MCB":
        cb_w, cb_h = config.mcb_w, config.mcb_h
    else:
        cb_w, cb_h = config.breaker_w, config.breaker_h

    cb_symbol = f"CB_{breaker_type}"
    # Rating text with poles and kA fault level
    rating_text = f"{breaker_poles} {breaker_rating}A {breaker_fault_kA}kA"
    result.components.append(PlacedComponent(
        symbol_name=cb_symbol,
        x=cx - cb_w / 2,
        y=y - cb_h - 5,
        label=f"Main {breaker_type}",
        rating=rating_text,
        circuit_id="CB-MAIN",
    ))
    y -= cb_h + 5 + 5    # Reduced: align y with bottom stub end
    result.connections.append(((cx, y), (cx, y - 3)))  # 3mm gap
    y -= 3
    result.symbols_used.add(breaker_type)

    # -- 5. Main Busbar --
    sub_circuits = requirements.get("sub_circuits", [])
    num_circuits = max(len(sub_circuits), 1)

    h_spacing = config.horizontal_spacing
    if num_circuits > config.max_circuits_per_row:
        effective_count = config.max_circuits_per_row
    else:
        effective_count = num_circuits

    max_bus_width = config.max_x - config.min_x - 40
    desired_width = effective_count * h_spacing + 2 * config.busbar_margin
    if desired_width > max_bus_width:
        h_spacing = max((max_bus_width - 2 * config.busbar_margin) / effective_count,
                        config.min_horizontal_spacing)
        desired_width = effective_count * h_spacing + 2 * config.busbar_margin

    bus_width = max(desired_width, 140)
    bus_start_x = cx - bus_width / 2
    bus_end_x = cx + bus_width / 2

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
        label=f"MAIN SWITCHBOARD (MSB)",
        rating=f"{busbar_rating}A Busbar",
    ))

    result.connections.append(((cx, y + 5), (cx, y)))

    # NOTE: "Approved Load" info is already in title_block.py -- no duplicate label here

    # -- 6. ELCB + Sub-circuits --
    elcb_config = requirements.get("elcb", {})
    elcb_rating = elcb_config.get("rating", 0) if isinstance(elcb_config, dict) else 0
    elcb_ma = elcb_config.get("sensitivity_ma", 30) if isinstance(elcb_config, dict) else 30

    rows = _split_into_rows(sub_circuits, config.max_circuits_per_row)

    for row_idx, row_circuits in enumerate(rows):
        row_count = len(row_circuits)
        if row_idx == 0:
            busbar_y_row = y
            row_bus_start = bus_start_x
            row_bus_end = bus_end_x
        else:
            busbar_y_row = y - row_idx * config.row_spacing
            row_bus_width = row_count * h_spacing + 2 * config.busbar_margin
            row_bus_start = cx - row_bus_width / 2
            row_bus_end = cx + row_bus_width / 2

            result.components.append(PlacedComponent(
                symbol_name="BUSBAR",
                x=row_bus_start,
                y=busbar_y_row,
                label="",
                rating="",
            ))
            result.busbar_start_x = min(result.busbar_start_x, row_bus_start)
            result.busbar_end_x = max(result.busbar_end_x, row_bus_end)
            result.connections.append(((cx, y - 2), (cx, busbar_y_row)))

        # Add ELCB as a standalone branch on the far left of busbar
        if elcb_rating and row_idx == 0:
            elcb_tap_x = row_bus_start + 8
            elcb_comp_y = busbar_y_row - 30  # Reduced from -35 to -30
            result.connections.append(((elcb_tap_x, busbar_y_row), (elcb_tap_x, busbar_y_row - 5)))
            # ELCB symbol (no label/rating -- we add a separate LABEL to avoid overlap)
            result.components.append(PlacedComponent(
                symbol_name="CB_ELCB",
                x=elcb_tap_x - 7,
                y=elcb_comp_y,
            ))
            result.connections.append(((elcb_tap_x, busbar_y_row - 5), (elcb_tap_x, elcb_comp_y + 25)))
            # Tail from ELCB bottom
            result.connections.append(((elcb_tap_x, elcb_comp_y - 5), (elcb_tap_x, elcb_comp_y - 12)))
            # ELCB label -- placed BELOW the symbol to avoid overlap
            elcb_poles = elcb_config.get("poles", 4) if isinstance(elcb_config, dict) else 4
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=elcb_tap_x - 20,
                y=elcb_comp_y - 14,
                label=f"ELCB {elcb_rating}A {elcb_poles}P ({elcb_ma}mA)",
            ))
            result.symbols_used.add("ELCB")

        _place_sub_circuits(
            result, row_circuits, row_idx, row_count,
            busbar_y_row, row_bus_start, row_bus_end,
            h_spacing, config, sub_circuits,
        )

    # -- 7. Earth Bar --
    # Compute the lowest Y reached by sub-circuit elements
    lowest_busbar_y = y - max(0, len(rows) - 1) * config.row_spacing
    # Sub-circuit tail ends are about 30mm below busbar (8 drop + 16 MCB + 5 offset + 5 stub)
    # Labels are about 3mm further below
    sub_circuit_bottom_y = lowest_busbar_y - 40

    # Cable schedule occupies Y from 55 to 55 + (num_rows * 5)
    num_schedule_rows = min(len(sub_circuits) + 1, 12)
    cable_schedule_top = 55 + num_schedule_rows * 5.0

    # Earth bar should be between sub-circuits and cable schedule
    earth_y = sub_circuit_bottom_y - 25  # 25mm below sub-circuit labels (clears label text)
    earth_y = max(earth_y, cable_schedule_top + 8)  # At least 8mm above cable schedule

    earth_x = config.min_x + 5  # Far left, away from ELCB and sub-circuits
    result.components.append(PlacedComponent(
        symbol_name="EARTH",
        x=earth_x,
        y=earth_y,
        label="EARTH BAR",
    ))
    result.symbols_used.add("EARTH")

    # Earth conductor size annotation
    earth_conductor_mm2 = requirements.get("earth_conductor_mm2", 0)
    if not earth_conductor_mm2:
        # Auto-calculate from incoming cable size
        inc_cable = requirements.get("incoming_cable", {})
        if isinstance(inc_cable, dict):
            inc_size = inc_cable.get("size_mm2", 0)
        else:
            inc_size = 0
        if inc_size:
            from app.sld.standards import get_earth_conductor_size
            earth_conductor_mm2 = get_earth_conductor_size(inc_size)

    if earth_conductor_mm2:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=earth_x,
            y=earth_y - 5,
            label=f"1x{earth_conductor_mm2}mm² CU/GRN-YEL",
        ))

    # Dashed earth conductor -- vertical line at earth center, up to busbar level
    # Routed at far left to avoid crossing ELCB and sub-circuit symbols
    earth_cx = earth_x + 8
    result.dashed_connections.append(((earth_cx, lowest_busbar_y - 2), (earth_cx, earth_y + 18)))

    return result


# -- Helper functions --

def _split_into_rows(sub_circuits: list[dict], max_per_row: int) -> list[list[dict]]:
    """Split sub-circuits into rows of max_per_row each."""
    if not sub_circuits:
        return [[]]
    rows = []
    for i in range(0, len(sub_circuits), max_per_row):
        rows.append(sub_circuits[i:i + max_per_row])
    return rows


def _next_standard_rating(current: int) -> int:
    """Get the next standard breaker rating above the given value."""
    standard = [16, 20, 25, 32, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000]
    for r in standard:
        if r >= current:
            return r
    return standard[-1]


def _classify_circuit(name: str, index: int) -> str:
    """
    Generate a circuit ID based on the circuit name/purpose.
    LS = Lighting Sub, LP = Power, IS = Isolator, SP = Spare
    """
    name_lower = name.lower() if name else ""
    if "light" in name_lower or "lamp" in name_lower or "led" in name_lower:
        return f"LS{index + 1}"
    elif "spare" in name_lower:
        return f"SP{index + 1}"
    elif "isolat" in name_lower or "motor" in name_lower or "pump" in name_lower or "compressor" in name_lower:
        return f"IS{index + 1:02d}"
    else:
        return f"LP{index + 1}"


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
        global_idx = row_idx * config.max_circuits_per_row + i

        # Calculate tap point on busbar
        if row_count == 1:
            tap_x = (bus_start_x + bus_end_x) / 2
        elif row_count > 1:
            usable = bus_width - 2 * config.busbar_margin
            tap_x = bus_start_x + config.busbar_margin + i * (usable / (row_count - 1))
        else:
            tap_x = bus_start_x + config.busbar_margin

        tap_x = max(tap_x, config.min_x + 20)
        tap_x = min(tap_x, config.max_x - 20)

        sc_y = busbar_y - 8

        # Vertical drop from busbar
        result.connections.append(((tap_x, busbar_y), (tap_x, sc_y)))

        # Sub-circuit breaker info
        sc_breaker_type = str(circuit.get("breaker_type", "MCB")).upper()
        sc_breaker_rating = circuit.get("breaker_rating", 32)
        sc_name = str(circuit.get("name", f"DB-{global_idx + 1}"))
        sc_cable = format_cable_spec(circuit.get("cable", ""))
        sc_load_kw = circuit.get("load_kw", 0)
        sc_phase = circuit.get("phase", "")

        # Generate circuit ID
        circuit_id = _classify_circuit(sc_name, global_idx)

        # Determine breaker dimensions
        if sc_breaker_type in ("MCCB", "ACB"):
            sc_cb_w = config.breaker_w
            sc_cb_h = config.breaker_h
        else:
            sc_cb_w = config.mcb_w
            sc_cb_h = config.mcb_h

        # Load current calculation
        load_info = ""
        if sc_load_kw and sc_load_kw > 0:
            current = round(sc_load_kw * 1000 / (400 * 1.732), 1)
            load_info = f"{sc_load_kw}kW / {current}A"

        cb_sym = f"CB_{sc_breaker_type}"
        result.components.append(PlacedComponent(
            symbol_name=cb_sym,
            x=tap_x - sc_cb_w / 2,
            y=sc_y - sc_cb_h - 5,
            label=f"{sc_breaker_type}",
            rating=f"{sc_breaker_rating}A",
            cable_annotation=sc_cable,
            circuit_id=circuit_id,
            load_info=load_info,
        ))
        result.symbols_used.add(sc_breaker_type)

        # Circuit name + ID label below breaker (horizontal, compact)
        breaker_bottom_y = sc_y - sc_cb_h - 5
        tail_end_y = breaker_bottom_y - 8  # Reduced from 12 to 8

        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=tap_x - 12,
            y=tail_end_y - 3,
            label=f"{circuit_id}: {sc_name}",
        ))

        # Tail from breaker bottom
        result.connections.append(((tap_x, breaker_bottom_y), (tap_x, tail_end_y)))
