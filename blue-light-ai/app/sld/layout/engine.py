"""
SLD Layout engine — main orchestrator.

Contains:
- compute_layout(): the top-level entry point that sequences all section methods
- _center_vertically(): post-layout vertical centering
"""

from __future__ import annotations

import logging

from app.sld.layout.models import LayoutConfig, LayoutResult, _LayoutContext
from app.sld.page_config import PageConfig
from app.sld.layout.overlap import (
    _add_cable_leader_lines,
    _add_isolator_device_symbols,
    _add_phase_fanout,
    _compute_dynamic_spacing,
    resolve_overlaps,
)
from app.sld.layout.sections import (
    _parse_requirements,
    _place_db_box,
    _place_earth_bar,
    _place_elcb,
    _place_incoming_supply,
    _place_main_breaker,
    _place_main_busbar,
    _place_meter_board,
    _place_sub_circuits_rows,
    _place_unit_isolator,
)

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


def compute_layout(
    requirements: dict,
    config: LayoutConfig | None = None,
    application_info: dict | None = None,
    *,
    page_config: PageConfig | None = None,
    skip_validation: bool = False,
) -> LayoutResult:
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
        skip_validation: If True, skip SS 638 compliance validation.
            Default False: validation runs and raises ValueError on hard errors.

    Raises:
        ValueError: If validation finds hard errors (e.g., missing kVA + breaker rating).
    """
    if config is None:
        config = LayoutConfig.from_page_config(page_config) if page_config else LayoutConfig()

    # -- Input validation gate (defense in depth) --
    if not skip_validation:
        requirements = _validate_and_correct(requirements)

    result = LayoutResult()
    cx = config.start_x

    # Start from BOTTOM -- above title block with clearance for supply label
    y = config.min_y + 15  # ~77mm (extra clearance for 3-line supply label)

    ctx = _LayoutContext(
        result=result,
        config=config,
        cx=cx,
        y=y,
        requirements=requirements,
        application_info=application_info or {},
    )

    _parse_requirements(ctx, requirements, application_info)
    _place_incoming_supply(ctx)
    _place_meter_board(ctx)
    _place_unit_isolator(ctx)

    dbs = requirements.get("distribution_boards")
    is_multi_db = dbs and len(dbs) > 1

    if is_multi_db:
        # ── Multi-DB path ──
        ctx.distribution_boards = dbs
        result.db_count = len(dbs)
        _place_main_breaker(ctx)
        _place_elcb(ctx)
        _place_main_busbar(ctx)
        busbar_y_row = _place_sub_dbs(ctx, dbs)
    else:
        # ── Single-DB path (backward compatible) ──
        _place_main_breaker(ctx)
        _place_elcb(ctx)
        _place_main_busbar(ctx)
        busbar_y_row = _place_sub_circuits_rows(ctx)

    # Store spine_x BEFORE resolve_overlaps for deterministic incoming chain detection
    ctx.result.spine_x = cx

    resolve_overlaps(ctx.result, ctx.config)
    _add_phase_fanout(ctx.result, ctx.config, ctx.supply_type)
    _add_cable_leader_lines(ctx.result, ctx.config)
    _add_isolator_device_symbols(ctx.result, ctx.config)

    if is_multi_db:
        db_box_right = _place_multi_db_boxes(ctx, dbs, busbar_y_row)
    else:
        db_box_right = _place_db_box(ctx, busbar_y_row)

    _place_earth_bar(ctx, db_box_right)

    # Post-layout: center content vertically in drawing area
    _center_vertically(ctx.result, ctx.config)

    # Post-layout: detect overflow beyond drawing boundaries
    _detect_overflow(ctx.result, ctx.config)

    return ctx.result


def _center_vertically(result: LayoutResult, config: LayoutConfig) -> None:
    """Shift all content vertically to center it in the drawing area.

    Measures the actual Y extent of all components (including vertical text
    rendering height), connections, and dots, then applies a uniform vertical
    shift to center the content between min_y and max_y.

    Vertical text (rotation=90°) extends UPWARD from comp.y, so its top
    extent = comp.y + len(text) * char_width_estimate.
    """
    _CHAR_W = 2.0  # Approximate character advance width (char_height 2.8 × 0.71)

    # Collect all Y coordinates (including rendered text extents)
    all_ys: list[float] = []
    for comp in result.components:
        all_ys.append(comp.y)
        # Vertical text extends upward from comp.y
        if comp.symbol_name == "LABEL" and abs(comp.rotation - 90.0) < 0.1:
            # Handle \\P line breaks: only longest line contributes to Y extent
            lines = comp.label.split("\\P")
            max_line_len = max(len(line) for line in lines)
            text_extent = max_line_len * _CHAR_W
            all_ys.append(comp.y + text_extent)
    for (sx, sy), (ex, ey) in result.connections:
        all_ys.extend([sy, ey])
    for (sx, sy), (ex, ey) in result.dashed_connections:
        all_ys.extend([sy, ey])
    for (sx, sy), (ex, ey) in result.thick_connections:
        all_ys.extend([sy, ey])
    for x1, y1, x2, y2 in result.solid_boxes:
        all_ys.extend([y1, y2])
    for dx, dy in result.junction_dots:
        all_ys.append(dy)
    for ax, ay in result.arrow_points:
        all_ys.append(ay)

    if not all_ys:
        return

    content_min_y = min(all_ys)
    content_max_y = max(all_ys)
    content_height = content_max_y - content_min_y

    # Available vertical space (with margins)
    margin = 5.0  # mm breathing room from edges
    area_min = config.min_y + margin
    area_max = config.max_y - margin
    area_height = area_max - area_min

    # Calculate shift to center (even if content exceeds area, try to minimize overflow)
    current_center = (content_min_y + content_max_y) / 2
    target_center = (area_min + area_max) / 2
    shift = target_center - current_center

    # Clamp shift: ensure no content exceeds drawing borders after shift
    # Priority: prevent top overflow first (text extends upward), then bottom
    if content_max_y + shift > config.max_y - 2:
        shift = config.max_y - 2 - content_max_y
    if content_min_y + shift < config.min_y + 2:
        shift = config.min_y + 2 - content_min_y

    if abs(shift) < 1.0:
        return  # Already centered enough

    # Apply vertical shift to all elements
    for comp in result.components:
        comp.y += shift
    for i, ((sx, sy), (ex, ey)) in enumerate(result.connections):
        result.connections[i] = ((sx, sy + shift), (ex, ey + shift))
    for i, ((sx, sy), (ex, ey)) in enumerate(result.dashed_connections):
        result.dashed_connections[i] = ((sx, sy + shift), (ex, ey + shift))
    for i, ((sx, sy), (ex, ey)) in enumerate(result.thick_connections):
        result.thick_connections[i] = ((sx, sy + shift), (ex, ey + shift))
    for i, (x1, y1, x2, y2) in enumerate(result.solid_boxes):
        result.solid_boxes[i] = (x1, y1 + shift, x2, y2 + shift)
    for i, (dx, dy) in enumerate(result.junction_dots):
        result.junction_dots[i] = (dx, dy + shift)
    for i, (ax, ay) in enumerate(result.arrow_points):
        result.arrow_points[i] = (ax, ay + shift)

    # Update stored Y references
    result.busbar_y += shift
    result.busbar_y_per_row = [by + shift for by in result.busbar_y_per_row]
    result.db_box_start_y += shift
    result.db_box_end_y += shift


def _place_sub_dbs(ctx: _LayoutContext, dbs: list[dict]) -> float:
    """Place multiple Sub-DBs branching from the main busbar.

    Each Sub-DB gets:
    - A BI_CONNECTOR from the main busbar
    - Its own main breaker, optional ELCB, busbar, and sub-circuits
    - Its own DB box (placed later by _place_multi_db_boxes)

    DBs are placed side-by-side horizontally above the main busbar.

    Returns the topmost busbar Y (for DB box sizing).
    """
    from app.sld.layout.models import PlacedComponent
    from app.sld.layout.sections import (
        _place_elcb,
        _place_main_breaker,
        _place_main_busbar,
        _place_sub_circuits_rows,
    )

    result = ctx.result
    config = ctx.config
    main_busbar_y = result.busbar_y
    num_dbs = len(dbs)

    # ── Compute horizontal layout for each sub-DB ──
    db_circuit_counts = []
    for db in dbs:
        n_circuits = len(db.get("sub_circuits", []))
        # 3-phase: circuits will be padded to triplets
        if ctx.supply_type == "three_phase" and n_circuits % 3 != 0:
            n_circuits += 3 - (n_circuits % 3)
        db_circuit_counts.append(max(n_circuits, 3))

    # Calculate width per DB
    db_widths = []
    for n in db_circuit_counts:
        spacing = _compute_dynamic_spacing(n, config)
        width = n * spacing + 2 * config.busbar_margin + 20  # +margin for labels
        db_widths.append(max(width, 80))  # minimum DB width

    total_width = sum(db_widths) + (num_dbs - 1) * 30  # 30mm gap between DBs
    start_x = ctx.cx - total_width / 2

    # Calculate center X for each DB
    db_cx_positions: list[float] = []
    x_cursor = start_x
    for w in db_widths:
        db_cx_positions.append(x_cursor + w / 2)
        x_cursor += w + 30

    # ── Place each Sub-DB ──
    topmost_busbar_y = main_busbar_y
    bi_h = 10  # BI_CONNECTOR symbol height
    bi_w = 16  # BI_CONNECTOR symbol width

    for db_idx, (db, db_cx) in enumerate(zip(dbs, db_cx_positions)):
        # Connection from main busbar to BI_CONNECTOR
        bi_y = main_busbar_y + 5  # 5mm above main busbar
        result.connections.append(((db_cx, main_busbar_y), (db_cx, bi_y)))

        # BI_CONNECTOR symbol
        result.components.append(PlacedComponent(
            symbol_name="BI_CONNECTOR",
            x=db_cx - bi_w / 2,
            y=bi_y,
            label="BI CONN.",
        ))
        result.symbols_used.add("BI_CONNECTOR")

        # Connection from BI_CONNECTOR to sub-DB breaker
        sub_start_y = bi_y + bi_h
        result.connections.append(((db_cx, sub_start_y), (db_cx, sub_start_y + 3)))
        sub_start_y += 3

        # ── Save context state and override for this sub-DB ──
        saved = {
            "cx": ctx.cx, "y": ctx.y,
            "breaker_type": ctx.breaker_type,
            "breaker_rating": ctx.breaker_rating,
            "breaker_poles": ctx.breaker_poles,
            "breaker_fault_kA": ctx.breaker_fault_kA,
            "main_breaker_char": ctx.main_breaker_char,
            "elcb_config": ctx.elcb_config,
            "elcb_rating": ctx.elcb_rating,
            "elcb_ma": ctx.elcb_ma,
            "elcb_type_str": ctx.elcb_type_str,
            "sub_circuits": ctx.sub_circuits,
            "busbar_rating": ctx.busbar_rating,
            "db_info_label": ctx.db_info_label,
            "db_info_text": ctx.db_info_text,
            "db_box_start_y": ctx.db_box_start_y,
            "busbar_y": result.busbar_y,
            "busbar_start_x": result.busbar_start_x,
            "busbar_end_x": result.busbar_end_x,
        }

        # Apply sub-DB requirements
        ctx.cx = db_cx
        ctx.y = sub_start_y
        ctx.current_db_idx = db_idx
        ctx.db_spine_xs.append(db_cx)

        # Parse sub-DB breaker
        sub_breaker = db.get("breaker", db.get("main_breaker", {}))
        ctx.breaker_type = sub_breaker.get("type", "MCB")
        ctx.breaker_rating = sub_breaker.get("rating", 63)
        ctx.breaker_poles = sub_breaker.get("poles", "TPN")
        ctx.breaker_fault_kA = sub_breaker.get("fault_kA", 10)
        ctx.main_breaker_char = sub_breaker.get("breaker_characteristic", "")

        # Parse sub-DB ELCB
        sub_elcb = db.get("elcb", {})
        if sub_elcb:
            ctx.elcb_config = sub_elcb
            ctx.elcb_rating = sub_elcb.get("rating", 0)
            ctx.elcb_ma = sub_elcb.get("sensitivity_ma", 30)
            ctx.elcb_type_str = sub_elcb.get("type", "ELCB")
        else:
            ctx.elcb_config = {}
            ctx.elcb_rating = 0

        # Parse sub-DB circuits
        ctx.sub_circuits = db.get("sub_circuits", [])
        ctx.busbar_rating = db.get("busbar_rating", 100)

        # Place sub-DB components
        _place_main_breaker(ctx)
        _place_elcb(ctx)
        _place_main_busbar(ctx)
        sub_busbar_y = _place_sub_circuits_rows(ctx)

        topmost_busbar_y = max(topmost_busbar_y, sub_busbar_y)

        # Store DB box range for later
        result.db_box_ranges.append({
            "db_idx": db_idx,
            "name": db.get("name", f"Sub-DB {db_idx + 1}"),
            "db_box_start_y": ctx.db_box_start_y,
            "busbar_y_row": sub_busbar_y,
            "cx": db_cx,
            "busbar_start_x": result.busbar_start_x,
            "busbar_end_x": result.busbar_end_x,
            "breaker_rating": ctx.breaker_rating,
            "db_info_label": ctx.db_info_label,
            "db_info_text": ctx.db_info_text,
        })

        # ── Restore context state ──
        ctx.cx = saved["cx"]
        ctx.y = saved["y"]
        ctx.breaker_type = saved["breaker_type"]
        ctx.breaker_rating = saved["breaker_rating"]
        ctx.breaker_poles = saved["breaker_poles"]
        ctx.breaker_fault_kA = saved["breaker_fault_kA"]
        ctx.main_breaker_char = saved["main_breaker_char"]
        ctx.elcb_config = saved["elcb_config"]
        ctx.elcb_rating = saved["elcb_rating"]
        ctx.elcb_ma = saved["elcb_ma"]
        ctx.elcb_type_str = saved["elcb_type_str"]
        ctx.sub_circuits = saved["sub_circuits"]
        ctx.busbar_rating = saved["busbar_rating"]
        ctx.db_info_label = saved["db_info_label"]
        ctx.db_info_text = saved["db_info_text"]
        ctx.db_box_start_y = saved["db_box_start_y"]
        result.busbar_y = saved["busbar_y"]
        result.busbar_start_x = saved["busbar_start_x"]
        result.busbar_end_x = saved["busbar_end_x"]

    ctx.current_db_idx = -1
    return topmost_busbar_y


def _place_multi_db_boxes(ctx: _LayoutContext, dbs: list[dict],
                          topmost_busbar_y: float) -> float:
    """Place dashed DB boxes for each sub-DB. Returns rightmost DB box edge."""
    from app.sld.layout.models import PlacedComponent

    result = ctx.result
    config = ctx.config
    rightmost_x = 0.0

    for db_range in result.db_box_ranges:
        db_idx = db_range["db_idx"]
        start_y = db_range["db_box_start_y"]
        busbar_y_row = db_range["busbar_y_row"]
        db_cx = db_range["cx"]
        bus_start_x = db_range["busbar_start_x"]
        bus_end_x = db_range["busbar_end_x"]
        breaker_rating = db_range["breaker_rating"]
        db_name = db_range["name"]

        # DB box extents
        db_info_text = db_range.get("db_info_text", "")
        db_info_lines = db_info_text.count("\\P") + 1 if db_info_text else 0
        db_info_height = 5 + db_info_lines * 3
        box_start_y = start_y - db_info_height

        box_end_y = (busbar_y_row + config.db_box_busbar_margin
                     + config.mcb_h + config.stub_len
                     + config.db_box_tail_margin + config.db_box_label_margin)

        box_left = bus_start_x - 10
        box_right = bus_end_x + 10

        # Clamp to drawing bounds
        box_left = max(box_left, config.min_x + 1)
        box_right = min(box_right, config.max_x - 1)

        # Four dashed lines (bottom, top, left, right)
        result.dashed_connections.append(((box_left, box_start_y), (box_right, box_start_y)))
        result.dashed_connections.append(((box_left, box_end_y), (box_right, box_end_y)))
        result.dashed_connections.append(((box_left, box_start_y), (box_left, box_end_y)))
        result.dashed_connections.append(((box_right, box_start_y), (box_right, box_end_y)))

        # DB info label
        db_info_label = db_range.get("db_info_label", f"{breaker_rating}A DB")
        result.components.append(PlacedComponent(
            symbol_name="DB_INFO_BOX",
            x=box_left + 3,
            y=start_y + 8,
            label=db_info_label,
            rating=db_info_text,
        ))

        # DB name label below box
        if db_name:
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=db_cx - len(db_name) * 1.2,
                y=box_start_y - 4,
                label=db_name,
            ))

        rightmost_x = max(rightmost_x, box_right)

    return rightmost_x


def _detect_overflow(result: LayoutResult, config: LayoutConfig) -> None:
    """Detect and report content overflow beyond drawing boundaries.

    Scans all layout elements to measure actual content extents,
    compares against config boundaries, and populates
    result.overflow_metrics with overflow amounts and warnings.

    Called AFTER _center_vertically() so measurements reflect the final state.
    """
    from app.sld.layout.models import OverflowMetrics

    metrics = OverflowMetrics()
    _CHAR_W = 2.0  # Approximate character advance width (matches _center_vertically)

    all_xs: list[float] = []
    all_ys: list[float] = []

    for comp in result.components:
        all_xs.append(comp.x)
        all_ys.append(comp.y)
        # Vertical text extends upward from comp.y
        if comp.symbol_name == "LABEL" and abs(comp.rotation - 90.0) < 0.1:
            lines = comp.label.split("\\P")
            max_line_len = max(len(line) for line in lines)
            all_ys.append(comp.y + max_line_len * _CHAR_W)

    for collection in (result.connections, result.dashed_connections, result.thick_connections):
        for (sx, sy), (ex, ey) in collection:
            all_xs.extend([sx, ex])
            all_ys.extend([sy, ey])
    for x1, y1, x2, y2 in result.solid_boxes:
        all_xs.extend([x1, x2])
        all_ys.extend([y1, y2])
    for dx, dy in result.junction_dots:
        all_xs.append(dx)
        all_ys.append(dy)
    for ax, ay in result.arrow_points:
        all_xs.append(ax)
        all_ys.append(ay)

    if not all_xs or not all_ys:
        result.overflow_metrics = metrics
        return

    metrics.content_min_x = min(all_xs)
    metrics.content_max_x = max(all_xs)
    metrics.content_min_y = min(all_ys)
    metrics.content_max_y = max(all_ys)

    # Compute overflow in each direction
    metrics.overflow_left = max(0.0, config.min_x - metrics.content_min_x)
    metrics.overflow_right = max(0.0, metrics.content_max_x - config.max_x)
    metrics.overflow_bottom = max(0.0, config.min_y - metrics.content_min_y)
    metrics.overflow_top = max(0.0, metrics.content_max_y - config.max_y)

    # Measure sub-circuit spacing from tap positions on busbar
    tap_xs = sorted(set(
        comp.x for comp in result.components
        if comp.symbol_name.startswith("CB_")
    ))
    if len(tap_xs) >= 2:
        spacings = [tap_xs[i + 1] - tap_xs[i] for i in range(len(tap_xs) - 1)]
        metrics.actual_min_spacing = min(spacings)
        metrics.circuit_count = len(tap_xs)
        metrics.ideal_spacing = _compute_dynamic_spacing(len(tap_xs), config)
        if metrics.ideal_spacing > 0 and metrics.actual_min_spacing < metrics.ideal_spacing * 0.95:
            metrics.horizontal_compression_ratio = (
                metrics.actual_min_spacing / metrics.ideal_spacing
            )

    # Generate warnings
    if metrics.overflow_left > 0.5 or metrics.overflow_right > 0.5:
        metrics.warnings.append(
            f"Horizontal overflow: content extends "
            f"{metrics.overflow_left:.1f}mm left, {metrics.overflow_right:.1f}mm right "
            f"beyond drawing boundary"
        )
    if metrics.overflow_top > 0.5 or metrics.overflow_bottom > 0.5:
        metrics.warnings.append(
            f"Vertical overflow: content extends "
            f"{metrics.overflow_top:.1f}mm top, {metrics.overflow_bottom:.1f}mm bottom "
            f"beyond drawing boundary"
        )
    if metrics.is_compressed:
        metrics.warnings.append(
            f"Circuit spacing compressed to {metrics.horizontal_compression_ratio:.0%} "
            f"of ideal ({metrics.actual_min_spacing:.1f}mm vs {metrics.ideal_spacing:.1f}mm). "
            f"Consider reducing circuit count or using multi-row layout."
        )
    if metrics.actual_min_spacing > 0 and metrics.actual_min_spacing < 8.0:
        metrics.warnings.append(
            f"Minimum circuit spacing is {metrics.actual_min_spacing:.1f}mm "
            f"(below 8mm readability threshold). Labels may overlap in print."
        )

    result.overflow_metrics = metrics

    # Log warnings
    for w in metrics.warnings:
        logger.warning("Layout overflow: %s", w)
