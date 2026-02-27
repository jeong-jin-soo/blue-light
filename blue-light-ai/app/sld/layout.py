"""
SLD Layout Engine -- automatic component placement (v6 LEW-style).

Bottom-up layout matching real LEW (Licensed Electrical Worker) SLD conventions:
1. Incoming supply at the BOTTOM (just above title block)
   with current flow direction arrow pointing upward
2. Isolator (disconnect switch) for >= 45kVA
3. CT Metering for >= 45kVA (Current Transformer + kWh Meter)
4. SP kWh Meter (direct metering for < 45kVA)
5. Main breaker above metering (with kA fault rating & pole configuration)
6. Main busbar horizontally (double-line professional representation)
7. ELCB standalone branch on far-left of busbar (hanging BELOW per SG SLD samples)
8. Sub-circuit breakers branching UPWARD from busbar
   with vertical text labels and multi-line breaker blocks
9. Earth bar at bottom-left with dashed conductor connections

Key v6 changes from v5:
- Layout direction inverted (incoming at bottom, circuits branch upward)
- Vertical text (90-degree rotation) for circuit descriptions + cable specs
- Breaker label block format (rating / poles / type / kA as separate lines)
- Cable schedule table removed (inline annotations instead)
- Legend removed (standard symbols are self-explanatory to LEWs)
- Dense packing: horizontal_spacing 22mm, max 20 circuits per row
"""

from __future__ import annotations

from dataclasses import dataclass, field


# -- Cable formatting helper --

def format_cable_spec(cable_input) -> str:
    """
    Format cable specification into Singapore SLD standard format.

    Standard format: "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING"

    Handles:
    - str: returned as-is (e.g., "2 x 1C 16mm XLPE/SWA + 10mm CPC IN CABLE TRAY")
    - dict: formatted from keys like {cores, type, size_mm2, earth_mm2, method}
    - None/empty: returns empty string
    """
    if not cable_input:
        return ""

    if isinstance(cable_input, str):
        return cable_input

    if isinstance(cable_input, dict):
        cores = cable_input.get("cores", 2)
        cable_type = cable_input.get("type", "PVC")
        size = cable_input.get("size_mm2", cable_input.get("size", ""))
        earth = cable_input.get("earth_mm2", "")
        method = cable_input.get("method", "")
        if size:
            base = f"{cores} x 1C {size}sqmm {cable_type}"
            if earth:
                base += f" + {earth}sqmm PVC CPC"
            if method:
                base += f" IN {method}"
            return base
        return f"{cores}C {cable_type}"

    return str(cable_input)


# -- Data classes --

@dataclass
class LayoutConfig:
    """Layout configuration parameters (all in mm)."""

    # Drawing area (A3 landscape minus margins and title block)
    drawing_width: float = 380
    drawing_height: float = 240

    # Component spacing (dense packing for LEW-style)
    vertical_spacing: float = 25      # Between components vertically (was 40)
    horizontal_spacing: float = 35    # Between sub-circuits (needs room for vertical text columns)
    min_horizontal_spacing: float = 25  # Minimum spacing between sub-circuits
    busbar_margin: float = 18         # Margin from edges of busbar (was 25)

    # Sub-circuit row layout
    max_circuits_per_row: int = 20    # Max sub-circuits in a single row (was 12)
    row_spacing: float = 55           # Vertical spacing between sub-circuit rows (was 65)

    # Starting position
    start_x: float = 210             # Center of drawing
    start_y: float = 275             # Top of drawing (below margin)

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
    rotation: float = 0.0    # Text rotation for vertical labels (90 = vertical)
    # -- LEW-style breaker block fields --
    poles: str = ""              # e.g., "SPN", "TPN", "4P"
    breaker_type_str: str = ""   # e.g., "MCB", "MCCB"
    fault_kA: int = 0            # e.g., 6, 10, 25
    label_style: str = "default" # "default" | "breaker_block"
    breaker_characteristic: str = ""  # e.g., "B", "C", "D" (IEC 60898-1 trip curve)


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

    # v6: rendering flags (cable schedule & legend disabled by default)
    render_cable_schedule: bool = False
    render_legend: bool = False


def compute_layout(requirements: dict, config: LayoutConfig | None = None, application_info: dict | None = None) -> LayoutResult:
    """
    Compute the layout for an SLD based on requirements.

    v6: Bottom-up layout -- incoming supply at bottom, sub-circuits branch upward.

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
    # Start from BOTTOM -- above title block with clearance for supply label
    y = config.min_y + 15  # ~77mm (extra clearance for 3-line supply label)

    # -- 1. Incoming Supply (at bottom) --
    supply_type = requirements.get("supply_type", "three_phase")
    kva = requirements.get("kva", 0)
    voltage = 400 if supply_type == "three_phase" else 230
    result.supply_type = supply_type
    result.voltage = voltage

    # Supply label (to the LEFT of phase lines, outside the circuit path)
    result.components.append(PlacedComponent(
        symbol_name="LABEL",
        x=cx - 80,
        y=y + 8,
        label=f"INCOMING SUPPLY\\P{kva} kVA, {voltage}V, "
              f"{'3-Phase 4-Wire' if supply_type == 'three_phase' else '1-Phase 2-Wire'}"
              f"\\P50Hz, SP PowerGrid",
    ))

    # Phase lines with labels (at bottom, pointing upward)
    if supply_type == "three_phase":
        spacing = 5  # 5mm between phase lines
        for offset, label in [(-spacing*1.5, "L1"), (-spacing*0.5, "L2"),
                               (spacing*0.5, "L3"), (spacing*1.5, "N")]:
            # Phase line (short vertical segment)
            result.connections.append(((cx + offset, y - 5), (cx + offset, y + 5)))
            # Phase label (below the lines)
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=cx + offset - 2,
                y=y - 8,
                label=label,
            ))
        # Merge to single line (horizontal bar at top of phase lines)
        result.connections.append(((cx - spacing * 1.5, y + 5), (cx + spacing * 1.5, y + 5)))
        result.connections.append(((cx, y + 5), (cx, y + 10)))
    else:
        # Single-phase: L and N labels
        spacing = 5
        for offset, label in [(-spacing * 0.5, "L"), (spacing * 0.5, "N")]:
            result.connections.append(((cx + offset, y - 5), (cx + offset, y + 5)))
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=cx + offset - 2,
                y=y - 8,
                label=label,
            ))
        result.connections.append(((cx - spacing * 0.5, y + 5), (cx + spacing * 0.5, y + 5)))
        result.connections.append(((cx, y + 5), (cx, y + 10)))
    y += 10

    # Current flow direction arrow (upward pointing)
    result.components.append(PlacedComponent(
        symbol_name="FLOW_ARROW_UP",
        x=cx + 25,
        y=y - 4,
    ))

    # Incoming cable annotation
    incoming_cable = requirements.get("incoming_cable", "")
    cable_text = format_cable_spec(incoming_cable)
    if cable_text:
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=cx + 12,
            y=y - 3,
            label=cable_text,
        ))

    # -- Read main breaker info early (needed for meter board components) --
    main_breaker = requirements.get("main_breaker", {})
    breaker_type = str(main_breaker.get("type", "MCCB")).upper()
    breaker_rating = main_breaker.get("rating", 0) or main_breaker.get("rating_A", 0)
    breaker_poles = main_breaker.get("poles", "")
    breaker_fault_kA = main_breaker.get("fault_kA", 0)

    # Auto-determine poles if not specified (DP = Double Pole, TPN = Triple Pole + Neutral)
    if not breaker_poles:
        breaker_poles = "TPN" if supply_type == "three_phase" else "DP"

    # Auto-determine fault level if not specified
    if not breaker_fault_kA:
        from app.sld.standards import get_fault_level
        breaker_fault_kA = get_fault_level(breaker_type, kva)

    meter_poles = "DP" if supply_type == "single_phase" else "TPN"

    # -- 2. Meter Board Section (SP PowerGrid standard — present in ALL installations) --
    # Contains: Meter Isolator + [CT for ≥45kVA] + KWH Meter + Meter MCB TYPE C
    # Located at the building's meter compartment
    metering = requirements.get("metering", "sp_meter")

    if metering:
        meter_board_start_y = y  # Track start of meter board for label positioning
        # Compact spacing for meter board (saves vertical space for sub-circuit labels)
        _stub = 2   # Compact post-component stub (vs config.stub_len = 5)
        _gap = 1    # Compact connection gap (vs 3)

        # 2a. Meter Isolator (DP for single-phase, TPN for 3-phase)
        result.connections.append(((cx, y), (cx, y + _gap)))
        y += _gap
        result.components.append(PlacedComponent(
            symbol_name="ISOLATOR",
            x=cx - 6,
            y=y,
            label=f"{breaker_rating}A {meter_poles}",
            rating="ISOLATOR",
        ))
        y += config.isolator_h + _stub
        result.connections.append(((cx, y), (cx, y + _gap)))
        y += _gap
        result.symbols_used.add("ISOLATOR")

        # 2b. CT metering for >= 45kVA installations
        if kva >= 45:
            ct_r = config.ct_size / 2
            result.components.append(PlacedComponent(
                symbol_name="CT",
                x=cx - ct_r,
                y=y,
                label="CT BY SP",
            ))
            y += config.ct_size + _stub
            result.connections.append(((cx, y), (cx, y + _gap)))
            y += _gap
            result.symbols_used.add("CT")

        # 2c. SP KWH Meter (symbol draws "kWh" label inside circle)
        meter_r = config.meter_size / 2
        result.components.append(PlacedComponent(
            symbol_name="KWH_METER",
            x=cx - meter_r,
            y=y,
        ))
        y += config.meter_size + _stub
        result.connections.append(((cx, y), (cx, y + _gap)))
        y += _gap
        result.symbols_used.add("KWH_METER")

        # 2d. Meter MCB (TYPE C, 10kA — SP protection device)
        meter_mcb_w, meter_mcb_h = config.mcb_w, config.mcb_h
        result.components.append(PlacedComponent(
            symbol_name="CB_MCB",
            x=cx - meter_mcb_w / 2,
            y=y,
            label=f"{breaker_rating}A {meter_poles} MCB",
            rating="10kA TYPE C",
        ))
        y += meter_mcb_h + _stub
        result.connections.append(((cx, y), (cx, y + _gap)))
        y += _gap
        result.symbols_used.add("MCB")

        meter_board_end_y = y  # Track end of meter board section

        # Dashed box around meter board components (per Singapore SLD samples)
        mb_box_left = cx - 20
        mb_box_right = cx + 55  # Extra width to include component labels
        mb_box_bottom = meter_board_start_y + 1
        mb_box_top = meter_board_end_y - 1
        # Four sides of dashed rectangle
        result.dashed_connections.append(((mb_box_left, mb_box_bottom), (mb_box_right, mb_box_bottom)))
        result.dashed_connections.append(((mb_box_left, mb_box_top), (mb_box_right, mb_box_top)))
        result.dashed_connections.append(((mb_box_left, mb_box_bottom), (mb_box_left, mb_box_top)))
        result.dashed_connections.append(((mb_box_right, mb_box_bottom), (mb_box_right, mb_box_top)))

        # METER BOARD label to the left of the dashed box (vertically centered)
        meter_mid_y = (meter_board_start_y + meter_board_end_y) / 2
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=mb_box_left - 50,
            y=meter_mid_y + 3,
            label="METER BOARD\\PLOCATED AT\\PMETER COMPARTMENT",
        ))

    # -- 3. Unit Isolator (for >= 45kVA or explicitly specified) --
    isolator_rating = requirements.get("isolator_rating", 0)
    if not isolator_rating and kva >= 45:
        if breaker_rating:
            isolator_rating = _next_standard_rating(breaker_rating)

    if isolator_rating:
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        result.components.append(PlacedComponent(
            symbol_name="ISOLATOR",
            x=cx - 6,
            y=y,
            label=f"{isolator_rating}A {meter_poles}",
            rating="ISOLATOR",
        ))
        y += config.isolator_h + 2
        result.connections.append(((cx, y), (cx, y + 2)))
        y += 2
        result.symbols_used.add("ISOLATOR")

    # -- 4. Main Circuit Breaker --

    if breaker_type == "ACB":
        cb_w, cb_h = 16, 22
    elif breaker_type == "MCB":
        cb_w, cb_h = config.mcb_w, config.mcb_h
    else:
        cb_w, cb_h = config.breaker_w, config.breaker_h

    cb_symbol = f"CB_{breaker_type}"
    # Main breaker characteristic (B/C/D) — IEC 60898-1 trip curve
    main_breaker_char = str(main_breaker.get("breaker_characteristic", "")).upper()
    # Singapore SLD format:
    #   Line 1: "63A DP MCB"  (rating + poles + type)
    #   Line 2: "TYPE B 10kA" (characteristic + fault level)
    main_label = f"{breaker_rating}A {breaker_poles} {breaker_type}"
    if main_breaker_char:
        main_rating = f"TYPE {main_breaker_char} {breaker_fault_kA}kA"
    else:
        main_rating = f"{breaker_fault_kA}kA"
    result.components.append(PlacedComponent(
        symbol_name=cb_symbol,
        x=cx - cb_w / 2,
        y=y,
        label=main_label,
        rating=main_rating,
    ))
    y += cb_h + 3 + 2
    result.connections.append(((cx, y), (cx, y + 2)))
    y += 2
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

    busbar_rating = requirements.get("busbar_rating", 0)
    if not busbar_rating:
        # Per SG standard: minimum 100A COMB BUSBAR for installations ≤ 100A
        busbar_rating = max(100, breaker_rating)

    result.busbar_y = y
    result.busbar_start_x = bus_start_x
    result.busbar_end_x = bus_end_x

    busbar_label = (
        f"{busbar_rating}A COMB BUSBAR"
        if busbar_rating <= 100
        else f"{busbar_rating}A BUSBAR"
    )
    # Read ELCB config early (needed for label positioning below busbar)
    elcb_config = requirements.get("elcb", {})
    elcb_rating = elcb_config.get("rating", 0) if isinstance(elcb_config, dict) else 0
    elcb_ma = elcb_config.get("sensitivity_ma", 30) if isinstance(elcb_config, dict) else 30

    result.components.append(PlacedComponent(
        symbol_name="BUSBAR",
        x=bus_start_x,
        y=y,
        label=f"{breaker_rating}A DB",
        rating=busbar_label,
    ))

    # -- DB Info Box (dashed box below busbar with load info) --
    if kva:
        approved_kva = kva
    elif supply_type == "three_phase":
        approved_kva = round(breaker_rating * voltage * 1.732 / 1000, 1)
    else:
        approved_kva = round(breaker_rating * voltage / 1000, 1)

    premises_addr = ""
    if application_info:
        premises_addr = application_info.get("address", "")

    # Position: offset right when ELCB hangs below left side
    db_info_x = bus_start_x + (45 if elcb_rating else 0)
    db_info_text = f"APPROVED LOAD: {approved_kva}KVA AT {voltage}V"
    if premises_addr:
        db_info_text += f"\\P(LOCATED AT PREMISES {premises_addr})"

    result.components.append(PlacedComponent(
        symbol_name="DB_INFO_BOX",
        x=db_info_x,
        y=y - 5,
        label=f"{breaker_rating}A DB",
        rating=db_info_text,
    ))

    # Connection from main breaker to busbar
    result.connections.append(((cx, y - 3), (cx, y)))

    # -- 6. ELCB + Sub-circuits (branching UPWARD) --
    # (elcb_config, elcb_rating, elcb_ma already read above for label positioning)

    # Pre-assign circuit IDs (S/P for single-phase, L1P1/L2P1 for 3-phase)
    circuit_ids = _assign_circuit_ids(sub_circuits, supply_type)

    rows = _split_into_rows(sub_circuits, config.max_circuits_per_row)

    for row_idx, row_circuits in enumerate(rows):
        row_count = len(row_circuits)
        if row_idx == 0:
            busbar_y_row = y
            row_bus_start = bus_start_x
            row_bus_end = bus_end_x
        else:
            busbar_y_row = y + (row_idx) * config.row_spacing
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
            # Vertical connection between rows
            result.connections.append(((cx, y + 2), (cx, busbar_y_row)))

        # Add ELCB/RCCB hanging BELOW the busbar on the far left
        # (matching real Singapore SLD samples where ELCB hangs under the busbar)
        if elcb_rating and row_idx == 0:
            elcb_tap_x = row_bus_start + 10
            elcb_h = 20  # ELCB/RCCB symbol height
            elcb_gap = 12  # Gap between busbar and ELCB top (clearance for labels)
            elcb_comp_y = busbar_y_row - elcb_gap - elcb_h  # BELOW busbar

            # Vertical line from busbar DOWN to ELCB top
            elcb_top_y = elcb_comp_y + elcb_h + 5  # top pin of ELCB symbol
            result.connections.append(((elcb_tap_x, busbar_y_row), (elcb_tap_x, elcb_top_y)))

            # Determine symbol: RCCB or ELCB based on elcb_config "type" field
            elcb_type_str = (
                elcb_config.get("type", "ELCB").upper()
                if isinstance(elcb_config, dict) else "ELCB"
            )
            elcb_symbol = "CB_RCCB" if elcb_type_str == "RCCB" else "CB_ELCB"
            result.components.append(PlacedComponent(
                symbol_name=elcb_symbol,
                x=elcb_tap_x - 7,
                y=elcb_comp_y,
            ))

            # Tail extending downward from ELCB bottom
            elcb_bottom_y = elcb_comp_y - 5  # bottom pin of ELCB symbol
            result.connections.append(((elcb_tap_x, elcb_bottom_y), (elcb_tap_x, elcb_bottom_y - 6)))

            # Label -- to the RIGHT of the ELCB symbol (avoiding overlap with DB info box)
            elcb_poles = elcb_config.get("poles", 4) if isinstance(elcb_config, dict) else 4
            elcb_poles_str = "DP" if elcb_poles == 2 else "4P"
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=elcb_tap_x + 12,
                y=elcb_comp_y + 10,
                label=f"{elcb_rating}A {elcb_poles_str}\\P{elcb_type_str}\\P({elcb_ma}mA)",
            ))
            result.symbols_used.add(elcb_type_str)

        # Offset sub-circuit area when ELCB is present (reserve left space)
        sc_bus_start = row_bus_start
        if elcb_rating and row_idx == 0:
            sc_bus_start = row_bus_start + 30  # Skip ELCB area

        _place_sub_circuits_upward(
            result, row_circuits, row_idx, row_count,
            busbar_y_row, sc_bus_start, row_bus_end,
            h_spacing, config, sub_circuits, supply_type, circuit_ids,
        )

    # -- 7. Earth Bar --
    # Position at right side, below busbar (Singapore SLD convention)
    earth_x = min(result.busbar_end_x + 5, config.max_x - 25)
    earth_y = config.min_y + 8  # Near bottom, above title block

    result.components.append(PlacedComponent(
        symbol_name="EARTH",
        x=earth_x,
        y=earth_y,
        label="E",
    ))
    result.symbols_used.add("EARTH")

    # Earth conductor size annotation
    earth_conductor_mm2 = requirements.get("earth_conductor_mm2", 0)
    if not earth_conductor_mm2:
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
            label=f"1 x {earth_conductor_mm2}sqmm CU/GRN-YEL",
        ))

    # Dashed earth conductor -- from earth bar up to busbar level, then to busbar end
    earth_cx = earth_x + 8
    # Vertical dashed line: earth bar → busbar level
    result.dashed_connections.append(((earth_cx, earth_y + 18), (earth_cx, result.busbar_y)))
    # Horizontal dashed line: connect to the busbar end (right side)
    if earth_cx > result.busbar_end_x:
        result.dashed_connections.append(((result.busbar_end_x, result.busbar_y), (earth_cx, result.busbar_y)))

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


def _assign_circuit_ids(sub_circuits: list[dict], supply_type: str) -> list[str]:
    """
    Pre-assign circuit IDs based on Singapore SLD conventions.

    Single-phase: S1, S2 (lighting), P1, P2 (power), SP1, SP2 (spare)
    Three-phase: L1S1, L2S1, L3S1 (lighting round-robin),
                 L1P1, L2P1, L3P1 (power round-robin), SP1, SP2 (spare)
    """
    ids: list[str] = []

    # First pass: categorize circuits
    categories: list[str] = []
    for circuit in sub_circuits:
        name_lower = (str(circuit.get("name", "")) or "").lower()
        if "spare" in name_lower:
            categories.append("spare")
        elif any(kw in name_lower for kw in ("light", "lamp", "led")):
            categories.append("lighting")
        else:
            categories.append("power")

    # Second pass: assign IDs with per-category counters
    s_idx = 0   # lighting counter
    p_idx = 0   # power counter
    sp_idx = 0  # spare counter

    for cat in categories:
        if supply_type == "single_phase":
            if cat == "spare":
                sp_idx += 1
                ids.append(f"SP{sp_idx}")
            elif cat == "lighting":
                s_idx += 1
                ids.append(f"S{s_idx}")
            else:
                p_idx += 1
                ids.append(f"P{p_idx}")
        else:  # three_phase — round-robin phase distribution
            if cat == "spare":
                sp_idx += 1
                ids.append(f"SP{sp_idx}")
            elif cat == "lighting":
                phase = (s_idx % 3) + 1
                num = (s_idx // 3) + 1
                ids.append(f"L{phase}S{num}")
                s_idx += 1
            else:
                phase = (p_idx % 3) + 1
                num = (p_idx // 3) + 1
                ids.append(f"L{phase}P{num}")
                p_idx += 1

    return ids


def _get_circuit_poles(circuit: dict, supply_type: str) -> str:
    """Determine pole configuration for sub-circuit."""
    phase = circuit.get("phase", "")
    if phase:
        phase_lower = phase.lower()
        if "single" in phase_lower or "1" in phase_lower:
            return "SPN"
        if "three" in phase_lower or "3" in phase_lower:
            return "TPN"
    # Default: SPN for all sub-circuits
    # In 3-phase systems, most outgoing circuits serve single-phase loads (SPN).
    # TPN only for explicitly 3-phase loads (set via circuit "phase" field).
    return "SPN"


def _get_circuit_fault_kA(breaker_type: str, circuit: dict | None = None) -> int:
    """Get fault rating for sub-circuit breaker.

    MCB sub-circuits: ALWAYS 6kA per Singapore standard (SS 638).
    - Main MCBs use 10kA (FAULT_LEVEL_DEFAULTS), but sub-circuit MCBs are 6kA.
    - Gemini often sends 10kA for sub-circuits (confusing main/sub defaults),
      so we enforce the correct value for MCBs regardless of explicit input.

    MCCB/ACB sub-circuits: use explicit value if provided, else defaults.
    """
    from app.sld.standards import SUB_CIRCUIT_FAULT_DEFAULTS
    bt = breaker_type.upper()

    # MCB sub-circuits: always 6kA — no override allowed
    if bt == "MCB":
        return SUB_CIRCUIT_FAULT_DEFAULTS.get("MCB", 6)

    # MCCB/ACB: respect explicit user value if provided
    if circuit:
        user_kA = circuit.get("fault_kA", 0)
        if user_kA:
            return int(user_kA)
    return SUB_CIRCUIT_FAULT_DEFAULTS.get(bt, 6)


def _place_sub_circuits_upward(
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
    supply_type: str = "three_phase",
    circuit_ids: list[str] | None = None,
) -> None:
    """Place a row of sub-circuits branching UPWARD from busbar with vertical labels."""
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

        # Sub-circuit info
        sc_name = str(circuit.get("name", f"DB-{global_idx + 1}"))

        # Look up pre-assigned circuit ID
        if circuit_ids and global_idx < len(circuit_ids):
            circuit_id = circuit_ids[global_idx]
        else:
            circuit_id = f"C{global_idx + 1}"

        # Circuit ID box at busbar tap point (small rectangle with ID text)
        result.components.append(PlacedComponent(
            symbol_name="CIRCUIT_ID_BOX",
            x=tap_x,
            y=busbar_y + 2,
            circuit_id=circuit_id,
        ))

        # -- Spare circuit: no breaker, just empty tap + "SPARE" label --
        if "spare" in sc_name.lower():
            # Short vertical line from busbar (upward, past circuit ID box)
            spare_top_y = busbar_y + 15
            result.connections.append(((tap_x, busbar_y), (tap_x, spare_top_y)))
            # "SPARE" label (vertical text, above the tap)
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=tap_x + 3,
                y=spare_top_y + 2,
                label="SPARE",
                rotation=90.0,
            ))
            continue

        # Vertical line UP from busbar to breaker
        sc_y = busbar_y + 8  # 8mm above busbar (past circuit ID box)

        # Vertical drop from busbar (upward)
        result.connections.append(((tap_x, busbar_y), (tap_x, sc_y)))

        # Sub-circuit breaker info
        sc_breaker_type = str(circuit.get("breaker_type", "MCB")).upper()
        sc_breaker_rating = circuit.get("breaker_rating", 32)
        sc_cable = format_cable_spec(circuit.get("cable", ""))
        sc_load_kw = circuit.get("load_kw", 0)
        sc_phase = circuit.get("phase", "")

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

        # Determine poles, fault kA, and breaker characteristic
        sc_poles = _get_circuit_poles(circuit, supply_type)
        sc_fault_kA = _get_circuit_fault_kA(sc_breaker_type, circuit)
        sc_breaker_char = str(circuit.get("breaker_characteristic", "")).upper()

        cb_sym = f"CB_{sc_breaker_type}"
        result.components.append(PlacedComponent(
            symbol_name=cb_sym,
            x=tap_x - sc_cb_w / 2,
            y=sc_y,
            label=sc_name,
            rating=f"{sc_breaker_rating}A",
            cable_annotation=sc_cable,
            circuit_id=circuit_id,
            load_info=load_info,
            rotation=90.0,
            # LEW-style breaker block fields
            poles=sc_poles,
            breaker_type_str=sc_breaker_type,
            fault_kA=sc_fault_kA,
            label_style="breaker_block",
            breaker_characteristic=sc_breaker_char,
        ))
        result.symbols_used.add(sc_breaker_type)

        # Tail from breaker top (extending upward)
        breaker_top_y = sc_y + sc_cb_h + config.stub_len
        tail_end_y = breaker_top_y + 6

        # Connection from breaker top to tail end
        result.connections.append(((tap_x, breaker_top_y), (tap_x, tail_end_y)))

        # Circuit name label (vertical text, above the tail)
        # Circuit ID is already shown in the CIRCUIT_ID_BOX at the busbar tap
        result.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=tap_x + 3,
            y=tail_end_y + 2,
            label=sc_name,
            rotation=90.0,
        ))
