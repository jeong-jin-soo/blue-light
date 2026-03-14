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
    _emit_db_box_rect_and_labels,
    _parse_requirements,
    _place_ct_metering_section,
    _place_ct_pre_mccb_fuse,
    _place_db_box,
    _place_earth_bar,
    _place_elcb,
    _place_incoming_supply,
    _place_internal_cable,
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

    dbs = requirements.get("distribution_boards")
    is_multi_db = dbs and len(dbs) > 1
    if is_multi_db:
        topology = requirements.get("db_topology")
        if not topology:
            # Auto-detect: if any DB has fed_from, use hierarchical topology
            topology = "parallel"
            for db in dbs:
                if db.get("fed_from"):
                    topology = "hierarchical"
                    break
    else:
        topology = "parallel"

    if is_multi_db:
        # ═══ MULTI-DB PATH: Board-Unit Independent Rendering ═══
        # Each board is rendered independently with its own LayoutResult,
        # then composed into a single result.  Post-processing (overlap
        # resolution, phase fan-out) runs PER BOARD with correct spine_x.

        ctx.distribution_boards = dbs
        ctx.db_topology = topology
        plan = _plan_layout(ctx, dbs, topology=topology)

        # 1. Place incoming section (once, for root board)
        incoming_result = LayoutResult()
        incoming_ctx = _LayoutContext(
            result=incoming_result,
            config=config,
            cx=plan.incoming_cx if topology == "hierarchical" else cx,
            y=y,
            requirements=requirements,
            application_info=application_info or {},
        )
        _parse_requirements(incoming_ctx, requirements, application_info)
        _place_incoming_supply(incoming_ctx)
        if incoming_ctx.metering == "ct_meter":
            # CT metering section goes INSIDE the root board, not the incoming section.
            # Force isolator placement by temporarily clearing metering flag
            # (normally _place_unit_isolator skips when metering is set).
            saved_metering = incoming_ctx.metering
            incoming_ctx.metering = ""
            _place_unit_isolator(incoming_ctx)
            incoming_ctx.metering = saved_metering
        else:
            _place_meter_board(incoming_ctx)
            _place_unit_isolator(incoming_ctx)

        # For parallel topology, place shared main breaker/ELCB/busbar
        # in incoming result (these belong to the installation, not a sub-DB)
        main_busbar_y = None
        if topology != "hierarchical":
            _place_main_breaker(incoming_ctx)
            _place_elcb(incoming_ctx)
            _place_internal_cable(incoming_ctx)
            _place_main_busbar(incoming_ctx)
            main_busbar_y = incoming_result.busbar_y

        # Board start Y: after incoming section
        board_start_y = incoming_ctx.y
        if topology != "hierarchical" and main_busbar_y is not None:
            # For parallel, boards start above the main busbar + BI connector gap
            board_start_y = main_busbar_y + 5 + 10 + 3  # bi_gap + bi_h + gap

        # 2. Render each board independently
        # Propagate top-level keys to root board (MSB) so _parse_board_requirements
        # can find main_breaker, elcb, post_elcb_mcb, internal_cable, isolator, etc.
        _PROPAGATE_KEYS = (
            "main_breaker", "breaker", "elcb", "post_elcb_mcb",
            "internal_cable", "isolator", "isolator_rating",
            "busbar_rating",
        )
        top_kva = requirements.get("kva", 0)
        root_idx = plan.root_db_idx
        board_results: list["BoardResult"] = []
        for db_idx, db in enumerate(dbs):
            if db_idx == 0 and top_kva and not db.get("kva"):
                db = {**db, "kva": top_kva}
            # Propagate top-level requirement keys into root board dict
            # so that render_board → _parse_board_requirements can consume them.
            if db_idx == root_idx:
                for key in _PROPAGATE_KEYS:
                    if key not in db and key in requirements:
                        db = {**db, key: requirements[key]}
            # Inject CT metering config into root board so render_board
            # places the CT metering section INSIDE the board (not incoming).
            if db_idx == root_idx and incoming_ctx.metering == "ct_meter":
                # Merge metering_config (manual) with metering_detail (from extraction)
                mc = {**requirements.get("metering_detail", {}),
                      **requirements.get("metering_config", {})}
                db = {
                    **db,
                    "_ct_metering": True,
                    "_metering_config": mc,
                }
            region = plan.db_regions[db_idx] if db_idx < len(plan.db_regions) else None
            br = render_board(
                db, db_idx, region, config,
                global_supply_type=ctx.supply_type,
                start_y=board_start_y,
                application_info=application_info,
            )
            board_results.append(br)

        # 3. Compose all boards + incoming into single LayoutResult
        merged = _compose_boards(
            board_results,
            incoming_result=incoming_result,
            plan=plan,
            spine_x=plan.incoming_cx if topology == "hierarchical" else cx,
        )

        # 4. Add inter-board connections
        if topology == "hierarchical":
            root_idx = plan.root_db_idx
            root_br = board_results[root_idx]
            child_brs = [br for br in board_results if br.board_idx != root_idx]
            feeder_circuits = [
                c for c in dbs[root_idx].get("sub_circuits", [])
                if c.get("_is_feeder")
            ]
            _add_hierarchical_connections(merged, root_br, child_brs, feeder_circuits, dbs)
        else:
            assert main_busbar_y is not None
            _add_parallel_connections(merged, board_results, main_busbar_y)

        # 5. Post-composition: DB boxes, earth bar, centering
        topmost = max(br.topmost_busbar_y for br in board_results)
        final_ctx = _LayoutContext(
            result=merged,
            config=config,
            cx=cx,
            y=y,
            requirements=requirements,
            application_info=application_info or {},
        )
        _parse_requirements(final_ctx, requirements, application_info)
        final_ctx.plan = plan
        merged.layout_regions = list(plan.db_regions)

        db_box_right = _place_multi_db_boxes(final_ctx, dbs, topmost)

        # Set merged.busbar_y from root board so _place_earth_bar positions
        # the earth symbol relative to the actual busbar, not the default 0.
        root_br = board_results[plan.root_db_idx]
        merged.busbar_y = root_br.layout.busbar_y

        _place_earth_bar(final_ctx, db_box_right)

        from app.sld.layout.connectivity import validate_connectivity
        validate_connectivity(merged, config)

        _center_vertically(merged, config)
        _detect_overflow(merged, config)

        return merged

    else:
        # ═══ SINGLE-DB PATH ═══
        _place_incoming_supply(ctx)
        if ctx.metering == "ct_meter" and ctx.supply_source != "landlord":
            # CT metering: spine order (supply → load):
            #   MCCB → Protection CT → Metering CT → BI
            # Fuses are horizontal RIGHT branches (not on spine).
            # Ref: CT_METERING_SPINE_ORDER in sections.py, 150A/400A TPN DWGs
            _place_unit_isolator(ctx)
            # Add isolator-to-DB gap before CT metering section
            _gap = config.isolator_to_db_gap
            result.connections.append(((cx, ctx.y), (cx, ctx.y + _gap)))
            ctx.y += _gap
            _ct_box_start_y = ctx.y - 1
            # Pre-MCCB fuse as horizontal RIGHT branch from spine
            ctx._ct_pre_mccb_fuse = True
            _place_ct_pre_mccb_fuse(ctx)
            _place_main_breaker(ctx, skip_gap=True)
            _place_ct_metering_section(ctx)
            # Detect and resolve CT metering branch/label overlaps.
            from app.sld.layout.ct_overlap import validate_ct_metering_overlaps
            validate_ct_metering_overlaps(ctx.result, ctx.config)
            # Override db_box_start_y to include CT metering in DB box
            ctx.db_box_start_y = _ct_box_start_y
        else:
            _place_meter_board(ctx)
            _place_unit_isolator(ctx)
            _place_main_breaker(ctx)
            _place_ct_pre_mccb_fuse(ctx)
        _place_elcb(ctx)
        _place_internal_cable(ctx)
        _place_main_busbar(ctx)
        busbar_y_row = _place_sub_circuits_rows(ctx)

        # Store spine_x BEFORE resolve_overlaps for deterministic incoming chain detection
        ctx.result.spine_x = cx

        resolve_overlaps(ctx.result, ctx.config)
        _add_phase_fanout(ctx.result, ctx.config, ctx.supply_type)
        _add_cable_leader_lines(ctx.result, ctx.config)
        _add_isolator_device_symbols(ctx.result, ctx.config)

        db_box_right = _place_db_box(ctx, busbar_y_row)
        _place_earth_bar(ctx, db_box_right)

        # Post-layout: snap connection endpoints to actual symbol pin positions
        from app.sld.layout.connectivity import validate_connectivity
        validate_connectivity(ctx.result, ctx.config)

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
    # Collect all Y coordinates (including rendered text extents)
    all_ys: list[float] = []
    for comp in result.components:
        all_ys.append(comp.y)
        # Vertical text extends upward from comp.y
        if comp.symbol_name == "LABEL" and abs(comp.rotation - 90.0) < 0.1:
            # Handle \\P line breaks: only longest line contributes to Y extent
            lines = comp.label.split("\\P")
            max_line_len = max(len(line) for line in lines)
            text_extent = max_line_len * config.char_w_label
            all_ys.append(comp.y + text_extent)
    for jx, jy, jdir in result.junction_arrows:
        all_ys.append(jy)
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

    if content_height <= area_height:
        # Content fits: clamp to prevent any overflow
        if content_max_y + shift > config.max_y - 2:
            shift = config.max_y - 2 - content_max_y
        if content_min_y + shift < config.min_y + 2:
            shift = config.min_y + 2 - content_min_y
    else:
        # Content doesn't fit in drawing area.  Position content so that:
        # 1. TOP (circuit labels) stays within page — most critical for readability
        # 2. BOTTOM overflow goes into title block area (OK) but try to stay
        #    above y=0 (content below y=0 is invisible in SVG/PDF)
        #
        # Strategy: anchor content_max at page_height - 1 (top of page in layout
        # coords), letting the overflow extend downward.  If content fits within
        # the page (0 to page_height), center it.
        page_height = 297.0  # A3 height
        # Anchor top: content_max_y + shift = page_height - 1
        shift = page_height - 1 - content_max_y
        # Check bottom: if content fits on page, shift to center on page
        if content_min_y + shift >= 1.0:
            # Fits on page — center within page
            page_center = page_height / 2
            shift = page_center - current_center

    if abs(shift) < 1.0:
        return  # Already centered enough

    # Apply vertical shift to all elements
    for comp in result.components:
        comp.y += shift
        if comp.label_y_override is not None:
            comp.label_y_override += shift
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
    for i, (jx, jy, jdir) in enumerate(result.junction_arrows):
        result.junction_arrows[i] = (jx, jy + shift, jdir)

    # Update stored Y references
    result.busbar_y += shift
    result.busbar_y_per_row = [by + shift for by in result.busbar_y_per_row]
    result.fanout_groups = [
        (cx, by + shift, sxs) for cx, by, sxs in result.fanout_groups
    ]
    result.db_box_start_y += shift
    result.db_box_end_y += shift


def _plan_layout(ctx: _LayoutContext, dbs: list[dict], *, topology: str = "parallel") -> "LayoutPlan":
    """Pre-compute global layout plan with strict region boundaries.

    Three-phase algorithm (Walker/Reingold-Tilford + Flexbox hybrid):

    Phase 1 — Bottom-Up Measurement:
        Traverse the SLD tree bottom-up computing natural widths:
        circuits → protection groups → DBs → total.
        Uses text-aware bounding boxes and readability thresholds.

    Phase 2 — Top-Down Width Allocation (Flexbox):
        If total exceeds page width, compress proportionally with
        automatic multi-row splitting for DBs that can't fit.
        Allocate strict non-overlapping LayoutRegion per DB.

    Phase 3 — Adjust:
        Compute vertical estimates accounting for multi-row layout.

    Returns a LayoutPlan with:
    - db_regions: strict [min_x, max_x] per DB (enforced by all placement functions)
    - db_cx_positions: center X per DB (derived from regions)
    - scale_factor: compression ratio if page width exceeded
    - db_plans[].num_rows: row count per DB for multi-row placement

    Backward compatibility: When ctx.plan is None (single-DB), this is not called.
    """
    from app.sld.circuit_normalizer import normalize_phase_name
    from app.sld.layout.helpers import _should_use_triplets as _triplet_check
    from app.sld.layout.models import DBPlan, LayoutPlan, LayoutRegion, ProtectionGroupPlan

    config = ctx.config
    avail_width = config.max_x - config.min_x  # ~370mm for A3

    # ── Text-aware minimum spacing ──
    # Each circuit occupies: symbol (~7mm) + rotated label requiring horizontal clearance.
    # Readable minimum: 10mm (matches config.min_horizontal_spacing).
    # Target spacing for comfortable readability: 12mm.
    MIN_READABLE_SPACING = 8.0   # mm — minimum readable spacing (matches sections.py)
    TARGET_SPACING = 12.0        # mm — comfortable spacing for labels
    GAP_BETWEEN_DBS = 20         # mm gap between DB regions

    # ═══ Phase 1: Bottom-Up Measurement ═══
    # Compute circuit counts and protection group structure for each DB.
    db_plans: list[DBPlan] = []
    _db_use_triplets: list[bool] = []  # Per-DB triplet flag, parallel to db_plans
    for db in dbs:
        pgroups = db.get("protection_groups", [])
        circuits = db.get("sub_circuits", [])

        # Per-DB triplet detection: PG boards are per-phase → no triplets.
        # Non-PG boards detect from circuit phase data (interleaved vs phase-grouped).
        db_triplets = False if pgroups else _triplet_check(circuits, ctx.supply_type)
        _db_use_triplets.append(db_triplets)

        # Total circuit count (direct + inside protection groups)
        n = len(circuits) + sum(len(g.get("circuits", [])) for g in pgroups)
        if db_triplets and ctx.supply_type == "three_phase" and n % 3 != 0:
            n += 3 - (n % 3)
        n = max(n, 3)

        has_elcb = bool(db.get("elcb"))

        # Build ProtectionGroupPlan list
        pg_plans: list[ProtectionGroupPlan] = []
        if pgroups:
            pg_gap = 8  # mm gap between groups
            for pg in pgroups:
                pg_circuits = pg.get("circuits", [])
                pg_n = len(pg_circuits)
                # PG circuits are single-phase — no triplet rounding needed.
                # (Triplet rounding only applies to interleaved L1/L2/L3 boards.)
                pg_n = max(pg_n, 1)
                pg_plans.append(ProtectionGroupPlan(
                    phase=normalize_phase_name(pg.get("phase", "")),
                    rccb=pg.get("rccb", {}),
                    circuit_count=pg_n,
                    estimated_width=0.0,  # computed after we know region width
                    busbar_rating=pg.get("busbar_rating", 0),
                ))

        db_plans.append(DBPlan(
            name=db.get("name", ""),
            circuit_count=n,
            estimated_width=0.0,  # computed in Phase 2
            estimated_height=0.0,  # computed in Phase 3
            has_elcb=has_elcb,
            protection_groups=pg_plans,
            num_rows=1,
            circuits_per_row=n,
        ))

    # ═══ Phase 2: Top-Down Width Allocation ═══
    # Strategy: Adaptive spacing — start at TARGET and compress toward
    # MIN_READABLE to prefer single-row placement (matching reference LEW
    # style where all circuits fit in one row with tight but readable spacing).

    gap_space = max(0, len(db_plans) - 1) * GAP_BETWEEN_DBS
    allocable = avail_width - gap_space

    # --- Helper: compute DB width at a given spacing ---
    PG_GAP = 8.0         # mm gap between protection groups (synced with _place_protection_groups)
    PG_MARGIN = 3.0      # mm margin per PG side (much smaller than busbar_margin)

    def _db_width_at_spacing(p: DBPlan, spacing: float) -> float:
        if p.protection_groups:
            num_pg = len(p.protection_groups)
            pg_overhead = max(0, num_pg - 1) * PG_GAP + num_pg * 2 * PG_MARGIN
            total_pg_circuits = sum(pg.circuit_count for pg in p.protection_groups)
            return max(total_pg_circuits * spacing + pg_overhead, 60)
        else:
            return max(p.circuit_count * spacing + 2 * config.busbar_margin, 60)

    # Try progressively tighter spacing until everything fits single-row.
    # This avoids multi-row when tighter spacing (matching reference) would work.
    chosen_spacing = TARGET_SPACING
    for try_spacing in [TARGET_SPACING, 10.0, 9.0, 8.0, MIN_READABLE_SPACING]:
        total_w = sum(_db_width_at_spacing(p, try_spacing) for p in db_plans)
        if total_w <= allocable:
            chosen_spacing = try_spacing
            break
        chosen_spacing = try_spacing
    else:
        # Even at MIN_READABLE, total exceeds allocable — will need scaling
        pass

    # Assign widths at chosen spacing
    for p in db_plans:
        p.estimated_width = _db_width_at_spacing(p, chosen_spacing)

    # Scale proportionally if still over budget
    total_w = sum(p.estimated_width for p in db_plans)
    if total_w > allocable:
        scale = allocable / total_w
        for p in db_plans:
            p.estimated_width = max(p.estimated_width * scale, 60)

    # Check if multi-row is needed at the final allocated widths
    for pi, p in enumerate(db_plans):
        _p_triplets = _db_use_triplets[pi] if pi < len(_db_use_triplets) else True
        if p.protection_groups:
            num_pg = len(p.protection_groups)
            pg_overhead = max(0, num_pg - 1) * PG_GAP + num_pg * 2 * PG_MARGIN
            pg_avail = p.estimated_width - pg_overhead
            per_pg_w = pg_avail / max(num_pg, 1)
            max_per_group = max(3, int(per_pg_w / MIN_READABLE_SPACING))
            if _p_triplets and ctx.supply_type == "three_phase" and max_per_group % 3 != 0:
                max_per_group = max(3, (max_per_group // 3) * 3)
            biggest_group = max(pg.circuit_count for pg in p.protection_groups)
            if biggest_group > max_per_group:
                p.num_rows = (biggest_group + max_per_group - 1) // max_per_group
                p.circuits_per_row = max_per_group
            else:
                p.circuits_per_row = biggest_group
        else:
            usable = p.estimated_width - 2 * config.busbar_margin
            max_per_row = max(3, int(usable / MIN_READABLE_SPACING))
            if _p_triplets and ctx.supply_type == "three_phase" and max_per_row % 3 != 0:
                max_per_row = max(3, (max_per_row // 3) * 3)
            if p.circuit_count > max_per_row:
                p.num_rows = (p.circuit_count + max_per_row - 1) // max_per_row
                p.circuits_per_row = (p.circuit_count + p.num_rows - 1) // p.num_rows
                if _p_triplets and ctx.supply_type == "three_phase" and p.circuits_per_row % 3 != 0:
                    p.circuits_per_row = ((p.circuits_per_row + 2) // 3) * 3
            else:
                p.circuits_per_row = min(p.circuit_count, max_per_row)

    # Final scaling if total still exceeds available
    total_width = sum(p.estimated_width for p in db_plans) + gap_space
    scale = min(1.0, avail_width / total_width) if total_width > 0 else 1.0

    # Allocate strict non-overlapping regions
    scaled_total = total_width * scale
    start_x = ctx.cx - scaled_total / 2
    if start_x < config.min_x:
        start_x = config.min_x

    cx_positions: list[float] = []
    db_regions: list[LayoutRegion] = []
    x_cursor = start_x
    scaled_gap = GAP_BETWEEN_DBS * scale

    for p in db_plans:
        w = p.estimated_width * scale
        p.estimated_width = w

        region_min_x = max(x_cursor, config.min_x)
        region_max_x = min(x_cursor + w, config.max_x)

        db_regions.append(LayoutRegion(
            min_x=region_min_x,
            max_x=region_max_x,
            name=p.name,
        ))
        cx_positions.append((region_min_x + region_max_x) / 2)
        x_cursor = region_max_x + scaled_gap

    # ═══ Phase 3: Adjust — vertical estimates with multi-row ═══
    main_section_h = 70.0
    for p in db_plans:
        base_h = 15 + (21 if p.has_elcb else 0) + 12 + 55
        if p.protection_groups:
            base_h += 25  # RCCB + sub-busbar layer
        # Additional height for multi-row: each extra row adds row_spacing
        extra_rows_h = max(0, p.num_rows - 1) * config.row_spacing
        p.estimated_height = base_h + extra_rows_h

    max_db_h = max(p.estimated_height for p in db_plans) if db_plans else 0.0
    total_height = main_section_h + max_db_h

    logger.info(
        "Layout plan: %d DBs, total_w=%.0f, avail=%.0f, scale=%.2f, "
        "regions=%s, rows=%s",
        len(db_plans), total_width, avail_width, scale,
        [f"{r.name}:[{r.min_x:.0f},{r.max_x:.0f}]w={r.width:.0f}" for r in db_regions],
        [f"{p.name}:{p.num_rows}rows×{p.circuits_per_row}ckt" for p in db_plans],
    )

    # Determine root DB index and incoming_cx for hierarchical mode
    root_idx = 0
    if topology == "hierarchical":
        for i, db in enumerate(dbs):
            if not db.get("fed_from"):
                root_idx = i
                break

    incoming_cx = cx_positions[root_idx] if cx_positions else ctx.cx

    # For hierarchical mode, ensure incoming_cx has enough clearance
    # from page edge for meter board (~80mm wide, centered on incoming_cx).
    if topology == "hierarchical":
        meter_half_w = 40  # meter board extends ~40mm left of cx
        min_incoming_cx = config.min_x + meter_half_w
        if incoming_cx < min_incoming_cx:
            incoming_cx = min_incoming_cx

    return LayoutPlan(
        total_width=scaled_total,
        total_height=total_height,
        db_plans=db_plans,
        main_section_height=main_section_h,
        scale_factor=scale,
        db_cx_positions=cx_positions,
        db_regions=db_regions,
        topology=topology,
        root_db_idx=root_idx,
        incoming_cx=incoming_cx,
    )


def _parse_board_requirements(
    ctx: _LayoutContext,
    board: dict,
    *,
    global_supply_type: str = "three_phase",
) -> None:
    """Populate ctx fields from a board specification dict.

    Extracts breaker, ELCB, circuits, and busbar info from a board dict
    into the shared context.  Used by render_board() for per-board setup.
    """
    # Breaker — priority chain: incoming_breaker > main_mcb > breaker > main_breaker
    breaker = (
        board.get("incoming_breaker")
        or board.get("main_mcb")
        or board.get("breaker")
        or board.get("main_breaker")
        or {}
    )
    ctx.breaker_type = breaker.get("type", "MCCB")
    ctx.breaker_rating = breaker.get("rating", 100)
    ctx.breaker_poles = breaker.get("poles", "TPN")
    ctx.breaker_fault_kA = breaker.get("fault_kA", 10)
    ctx.main_breaker_char = breaker.get("breaker_characteristic", "")

    # ELCB
    elcb = board.get("elcb", {})
    if elcb:
        ctx.elcb_config = elcb
        ctx.elcb_rating = elcb.get("rating", 0)
        ctx.elcb_ma = elcb.get("sensitivity_ma", 30)
        ctx.elcb_type_str = elcb.get("type", "ELCB")
    else:
        ctx.elcb_config = {}
        ctx.elcb_rating = 0

    # Circuits: merge sub_circuits + protection_group circuits
    all_circuits = list(board.get("sub_circuits", []))
    for pg in board.get("protection_groups", []):
        all_circuits.extend(pg.get("circuits", []))
    for c in all_circuits:
        c["_skip_section_pad"] = True
    ctx.sub_circuits = all_circuits
    ctx.busbar_rating = board.get("busbar_rating", 100)

    # kVA — per-board or inherited from global requirements
    ctx.kva = board.get("kva", 0)

    # Supply type inherited from global
    ctx.supply_type = global_supply_type
    ctx.voltage = 400 if global_supply_type == "three_phase" else 230

    # Post-ELCB MCB (RCCB+MCB serial structure)
    ctx.post_elcb_mcb = board.get("post_elcb_mcb", {})

    # Feeder connection metadata (hierarchical topology)
    ctx.feeder_breaker = board.get("feeder_breaker", {})
    ctx.feeder_cable = board.get("feeder_cable", "")

    # Internal cable (MCCB→busbar segment label)
    ctx.internal_cable = board.get("internal_cable", "")

    # Meter board label
    ctx.meter_board_label = board.get("meter_board", "")

    # Isolator — flatten dict form {"rating": N, "type": "4P", "location_text": ...}
    # into flat keys that _place_unit_isolator reads from ctx.requirements
    isolator = board.get("isolator", {})
    if isinstance(isolator, dict) and isolator:
        if "isolator_rating" not in board:
            board["isolator_rating"] = isolator.get("rating", 0)
        if "isolator_label" not in board:
            board["isolator_label"] = isolator.get("location_text", "")


def _merge_layout_into(target: "LayoutResult", source: "LayoutResult") -> None:
    """Append all elements from source into target (no coordinate transform)."""
    target.components.extend(source.components)
    target.connections.extend(source.connections)
    target.thick_connections.extend(source.thick_connections)
    target.dashed_connections.extend(source.dashed_connections)
    target.junction_dots.extend(source.junction_dots)
    target.junction_arrows.extend(source.junction_arrows)
    target.solid_boxes.extend(source.solid_boxes)
    target.arrow_points.extend(source.arrow_points)
    target.symbols_used.update(source.symbols_used)
    target.busbar_y_per_row.extend(source.busbar_y_per_row)
    target.busbar_x_per_row.update(source.busbar_x_per_row)
    target.fanout_groups.extend(source.fanout_groups)


def _compose_boards(
    board_results: list["BoardResult"],
    *,
    incoming_result: "LayoutResult | None" = None,
    plan: "LayoutPlan | None" = None,
    spine_x: float = 210.0,
) -> "LayoutResult":
    """Merge independently-rendered boards into a single LayoutResult.

    Each board is already rendered in page coordinates (render_board uses
    region.cx as its spine).  Composition is primarily concatenation,
    plus building db_box_ranges for _place_multi_db_boxes.
    """
    from app.sld.layout.models import LayoutResult

    merged = LayoutResult()

    # Start with incoming section if provided
    if incoming_result:
        _merge_layout_into(merged, incoming_result)

    # Merge each board's layout and build db_box_ranges
    for br in board_results:
        _merge_layout_into(merged, br.layout)

        # Layout regions from per-board PG regions
        if br.layout.layout_regions:
            merged.layout_regions.extend(br.layout.layout_regions)

        merged.db_box_ranges.append({
            "db_idx": br.board_idx,
            "name": br.board_name,
            "db_box_start_y": br.db_box_start_y,
            "busbar_y_row": br.topmost_busbar_y,
            "cx": br.spine_x,
            "busbar_start_x": br.busbar_start_x,
            "busbar_end_x": br.busbar_end_x,
            "breaker_rating": br.breaker_rating,
            "db_info_label": br.db_info_label,
            "db_info_text": br.db_info_text,
            "db_location_text": br.db_location_text,
            "_has_ct_metering": br.board_spec.get("_ct_metering", False),
        })

    # Set merged result metadata
    merged.db_count = len(board_results)
    merged.spine_x = spine_x
    if plan:
        # Add plan-level regions (for boards without PG sub-regions)
        for region in plan.db_regions:
            if not any(r.name == region.name for r in merged.layout_regions):
                merged.layout_regions.append(region)
    if board_results:
        merged.supply_type = board_results[0].effective_supply_type
        merged.voltage = board_results[0].layout.voltage

    return merged


def render_board(
    board: dict,
    board_idx: int,
    region: "LayoutRegion | None",
    config: "LayoutConfig",
    *,
    global_supply_type: str = "three_phase",
    start_y: float | None = None,
    application_info: dict | None = None,
) -> "BoardResult":
    """Render a single distribution board independently.

    Creates a fresh _LayoutContext with its own LayoutResult, places all
    board sections, and runs per-board post-processing.  Returns a
    BoardResult with coordinates already in page space (cx = region.cx).

    Key improvement over old save/restore pattern:
    - Each board gets a FRESH context — no state leakage between boards
    - resolve_overlaps() uses THIS board's spine_x (not page center)
    - _add_phase_fanout() uses THIS board's effective supply_type
    """
    from app.sld.layout.models import BoardResult, LayoutResult, _LayoutContext
    from app.sld.layout.helpers import _should_use_triplets
    from app.sld.layout.sections import (
        _place_ct_metering_section,
        _place_ct_pre_mccb_fuse,
        _place_elcb,
        _place_internal_cable,
        _place_main_breaker,
        _place_main_busbar,
        _place_sub_circuits_rows,
    )
    from app.sld.layout.overlap import (
        resolve_overlaps,
        _add_phase_fanout,
        _add_cable_leader_lines,
        _add_isolator_device_symbols,
    )

    result = LayoutResult()

    # Board spine X: center of region if constrained, else page center
    cx = region.cx if region else config.start_x

    # Starting Y: provided by caller (after incoming section) or default
    y = start_y if start_y is not None else config.min_y + 15

    ctx = _LayoutContext(
        result=result,
        config=config,
        cx=cx,
        y=y,
        requirements=board,
        application_info=application_info or {},
    )

    # Set region constraint
    if region:
        ctx.active_region = region
        ctx.constrained_width = region.width

    ctx.current_db_idx = board_idx

    # Parse board-level requirements
    _parse_board_requirements(ctx, board, global_supply_type=global_supply_type)

    # Detect triplet arrangement for this board.
    # PG boards: each PG is single-phase, so triplet fan-out never applies within PGs.
    # Non-PG boards: detect from circuit phase data (interleaved vs phase-grouped).
    pgroups = board.get("protection_groups", [])
    if pgroups:
        ctx.use_triplets = False  # PG = per-phase groups → no triplets within
    else:
        ctx.use_triplets = _should_use_triplets(ctx.sub_circuits, ctx.supply_type)

    # CT metering section — placed INSIDE the root board for ct_meter installations.
    # The _ct_metering flag is injected by the multi-DB orchestrator for the root board.
    _has_ct_metering = board.get("_ct_metering")
    if _has_ct_metering:
        mc = board.get("_metering_config", {})
        ctx.metering = "ct_meter"
        ctx.ct_ratio = mc.get("ct_ratio", "")
        ctx.metering_ct_class = mc.get("metering_ct_class", "CL1 5VA")
        ctx.protection_ct_ratio = mc.get("protection_ct_ratio", "")
        ctx.protection_ct_class = mc.get("protection_ct_class", "5P10 20VA")
        ctx.has_ammeter = mc.get("has_ammeter", True)
        ctx.has_voltmeter = mc.get("has_voltmeter", True)
        ctx.has_elr = mc.get("has_elr", True)
        ctx.elr_spec = mc.get("elr_spec", "")
        ctx.voltmeter_range = mc.get("voltmeter_range", "")
        ctx.ammeter_range = mc.get("ammeter_range", "")
        # Add the standard gap BEFORE CT metering — this ensures cable annotation
        # (tick mark + text) and location text from the incoming isolator stay
        # clearly OUTSIDE and BELOW the DB dashed box with proper spacing.
        _gap = config.isolator_to_db_gap
        result.connections.append(((cx, ctx.y), (cx, ctx.y + _gap)))
        ctx.y += _gap
        # DB box bottom starts AFTER the gap (so cable annotation stays outside)
        _ct_box_start_y = ctx.y - 1
        # CT metering: spine order (supply → load):
        #   MCCB → Protection CT → Metering CT → BI
        # Fuses are horizontal RIGHT branches (not on spine).
        # Ref: CT_METERING_SPINE_ORDER in sections.py, 150A/400A TPN DWGs
        ctx._ct_pre_mccb_fuse = True
        _place_ct_pre_mccb_fuse(ctx)
        _place_main_breaker(ctx, skip_gap=True)
        _place_ct_metering_section(ctx)
        # Detect and resolve CT metering branch/label overlaps.
        from app.sld.layout.ct_overlap import validate_ct_metering_overlaps
        validate_ct_metering_overlaps(ctx.result, ctx.config)
    else:
        _place_main_breaker(ctx)
        _place_ct_pre_mccb_fuse(ctx)
    _place_elcb(ctx)
    _place_internal_cable(ctx)
    _place_main_busbar(ctx)

    # Override db_box_start_y to include CT metering section inside the DB box.
    # _place_main_breaker sets it at the breaker position, but we need the box
    # to extend down to the CT metering entry point (after the gap).
    if _has_ct_metering:
        ctx.db_box_start_y = _ct_box_start_y

    # Protection groups or regular sub-circuits
    if pgroups:
        topmost_y = _place_protection_groups(ctx, board, cx)
    else:
        topmost_y = _place_sub_circuits_rows(ctx)

    # Store spine_x for this board BEFORE post-processing
    result.spine_x = cx
    result.supply_type = ctx.supply_type
    result.voltage = ctx.voltage
    result.db_count = 1  # Single board in this result
    result.use_triplets = ctx.use_triplets

    # === PER-BOARD POST-PROCESSING ===
    # Key fix: each board gets its own resolve_overlaps with correct spine_x
    resolve_overlaps(result, config)
    _add_phase_fanout(result, config, ctx.supply_type)
    _add_cable_leader_lines(result, config)
    _add_isolator_device_symbols(result, config)

    return BoardResult(
        layout=result,
        board_spec=board,
        board_idx=board_idx,
        board_name=board.get("name") or board.get("db_name") or f"DB{board_idx + 1}",
        effective_supply_type=ctx.supply_type,
        spine_x=cx,
        region=region,
        busbar_y=result.busbar_y,
        busbar_start_x=result.busbar_start_x,
        busbar_end_x=result.busbar_end_x,
        db_box_start_y=ctx.db_box_start_y,
        topmost_busbar_y=topmost_y,
        db_info_label=ctx.db_info_label,
        db_info_text=ctx.db_info_text,
        db_location_text=ctx.db_location_text,
        breaker_rating=ctx.breaker_rating,
    )


def _add_hierarchical_connections(
    merged: "LayoutResult",
    root_result: "BoardResult",
    child_results: list["BoardResult"],
    feeder_circuits: list[dict],
    dbs: list[dict] | None = None,
) -> None:
    """Add feeder connections from root board busbar to child boards.

    Places junction dots, cable runs, feeder MCBs, BI_CONNECTORs, incoming MCBs,
    SUPPLY FROM labels, and cable spec labels between root board and each child board.

    Reference layout (top→bottom, Y increases upward):
        root busbar
            ↓ junction dot
        feeder MCB (from root's feeder_breaker)
            ↓
        BI_CONNECTOR
            ↓
        incoming MCB (from child's incoming_breaker)
            ↓ cable + label
        child board top
    """
    from app.sld.layout.models import PlacedComponent

    root_busbar_y = root_result.busbar_y
    root_busbar_sx = root_result.busbar_start_x
    root_busbar_ex = root_result.busbar_end_x
    root_name = root_result.board_name
    bi_h = 10
    bi_w = 16
    mcb_w = 7.2
    mcb_h = 13.0
    stub = 3.0

    for child in child_results:
        child_cx = child.spine_x

        # Find feeder circuit for this child
        feeder_ckt = None
        for fc in feeder_circuits:
            if fc.get("_feeds_db", "").upper() == child.board_name.upper():
                feeder_ckt = fc
                break

        # Find child DB dict for incoming_breaker
        child_db = None
        if dbs:
            for db in dbs:
                db_name = (db.get("name") or db.get("db_name") or "").upper()
                if db_name == child.board_name.upper():
                    child_db = db
                    break

        # Junction on root busbar
        tap_x = max(root_busbar_sx + 2, min(child_cx, root_busbar_ex - 2))
        merged.junction_dots.append((tap_x, root_busbar_y))

        # Horizontal run if needed
        if abs(tap_x - child_cx) > 1.0:
            cable_run_y = root_busbar_y - 8
            merged.connections.append(((tap_x, root_busbar_y), (tap_x, cable_run_y)))
            merged.connections.append(((tap_x, cable_run_y), (child_cx, cable_run_y)))
            connect_y = cable_run_y
        else:
            connect_y = root_busbar_y

        # ── Feeder MCB (root side) ──
        # Priority: feeder circuit → root board dict → child DB dict
        feeder_brk = feeder_ckt.get("feeder_breaker", {}) if feeder_ckt else {}
        if not feeder_brk:
            # Check root board dict (MSB) for feeder_breaker
            root_db = dbs[root_result.board_idx] if dbs and root_result.board_idx < len(dbs) else None
            if root_db:
                feeder_brk = root_db.get("feeder_breaker", {})
        if not feeder_brk and child_db:
            feeder_brk = child_db.get("feeder_breaker", {})
        if feeder_brk and feeder_brk.get("rating"):
            fmcb_y = connect_y - stub - mcb_h
            merged.connections.append(((child_cx, connect_y), (child_cx, fmcb_y + mcb_h)))
            f_type = feeder_brk.get("type", "MCB")
            f_char = feeder_brk.get("breaker_characteristic", "")
            f_rating = feeder_brk.get("rating", 0)
            f_poles = feeder_brk.get("poles", "TPN")
            f_kA = feeder_brk.get("fault_kA", 10)
            if f_char:
                f_label = f"{f_rating}A {f_poles} Type {f_char} {f_type} ({f_kA}KA)"
            else:
                f_label = f"{f_rating}A {f_poles} {f_type} ({f_kA}KA)"
            merged.components.append(PlacedComponent(
                symbol_name=f"CB_{f_type}",
                x=child_cx - mcb_w / 2,
                y=fmcb_y,
                label=f_label,
                rating=f"{f_rating}A",
                poles=f_poles,
                breaker_type_str=f_type,
            ))
            merged.symbols_used.add(f_type)
            connect_y = fmcb_y - stub
        else:
            # No feeder MCB — direct connection to BI_CONNECTOR
            pass

        # ── BI_CONNECTOR ──
        bi_y = connect_y - bi_h
        merged.connections.append(((child_cx, connect_y), (child_cx, bi_y + bi_h)))
        # BI CONNECTOR label includes root breaker rating (e.g., "100A BI CONN.")
        bi_rating = root_result.breaker_rating or ""
        bi_label = f"{bi_rating}A BI CONN." if bi_rating else "BI CONN."
        merged.components.append(PlacedComponent(
            symbol_name="BI_CONNECTOR",
            x=child_cx - bi_w / 2,
            y=bi_y,
            label=bi_label,
        ))
        merged.symbols_used.add("BI_CONNECTOR")
        connect_y = bi_y - stub

        # ── Incoming MCB (child side) ──
        incoming_brk = child_db.get("incoming_breaker", {}) if child_db else {}
        if incoming_brk and incoming_brk.get("rating"):
            imcb_y = connect_y - mcb_h
            merged.connections.append(((child_cx, connect_y), (child_cx, imcb_y + mcb_h)))
            i_type = incoming_brk.get("type", "MCB")
            i_char = incoming_brk.get("breaker_characteristic", "")
            i_rating = incoming_brk.get("rating", 0)
            i_poles = incoming_brk.get("poles", "TPN")
            i_kA = incoming_brk.get("fault_kA", 10)
            if i_char:
                i_label = f"{i_rating}A {i_poles} Type {i_char} {i_type} ({i_kA}KA)"
            else:
                i_label = f"{i_rating}A {i_poles} {i_type} ({i_kA}KA)"
            merged.components.append(PlacedComponent(
                symbol_name=f"CB_{i_type}",
                x=child_cx - mcb_w / 2,
                y=imcb_y,
                label=i_label,
                rating=f"{i_rating}A",
                poles=i_poles,
                breaker_type_str=i_type,
            ))
            merged.symbols_used.add(i_type)
            connect_y = imcb_y - stub

        # ── Labels ──
        merged.components.append(PlacedComponent(
            symbol_name="LABEL",
            x=child_cx + 5,
            y=connect_y - 3,
            label=f"SUPPLY FROM {root_name}",
        ))

        # Cable spec label
        cable_spec = ""
        if feeder_ckt:
            cable_spec = feeder_ckt.get("cable_size", "") or feeder_ckt.get("cable", "")
        if not cable_spec:
            # Check root board dict (MSB) for feeder_cable
            root_db = dbs[root_result.board_idx] if dbs and root_result.board_idx < len(dbs) else None
            if root_db:
                cable_spec = root_db.get("feeder_cable", "")
        if not cable_spec and child_db:
            cable_spec = child_db.get("feeder_cable", "")
        if cable_spec:
            merged.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=child_cx + 5,
                y=connect_y - 6,
                label=str(cable_spec),
            ))

        # Connection to child board top
        merged.connections.append(((child_cx, connect_y), (child_cx, connect_y - 3)))


def _add_parallel_connections(
    merged: "LayoutResult",
    board_results: list["BoardResult"],
    main_busbar_y: float,
) -> None:
    """Add BI_CONNECTOR connections from main busbar to each sub-DB.

    Places junction dot on main busbar, vertical line to BI_CONNECTOR,
    and connection from BI_CONNECTOR to each board's top.
    """
    from app.sld.layout.models import PlacedComponent

    bi_h = 10
    bi_w = 16

    for br in board_results:
        db_cx = br.spine_x

        # Junction dot on main busbar
        merged.junction_dots.append((db_cx, main_busbar_y))

        # Connection from main busbar to BI_CONNECTOR
        bi_y = main_busbar_y + 5
        merged.connections.append(((db_cx, main_busbar_y), (db_cx, bi_y)))

        # BI_CONNECTOR symbol
        merged.components.append(PlacedComponent(
            symbol_name="BI_CONNECTOR",
            x=db_cx - bi_w / 2,
            y=bi_y,
            label="BI CONN.",
        ))
        merged.symbols_used.add("BI_CONNECTOR")

        # Connection from BI_CONNECTOR to sub-DB
        sub_start_y = bi_y + bi_h
        merged.connections.append(((db_cx, sub_start_y), (db_cx, sub_start_y + 3)))


def _place_protection_groups(
    ctx: _LayoutContext,
    db: dict,
    db_cx: float,
) -> float:
    """Place per-phase RCCB protection groups within a sub-DB.

    Layout (DB2 pattern with 3 per-phase RCCBs):

        [circuits L1]  [circuits L2]  [circuits L3]
              |              |              |
        [busbar L1]    [busbar L2]    [busbar L3]
              |              |              |
        [RCCB 40A 2P]  [RCCB 40A 2P]  [RCCB 40A 2P]
              └──────────────┼──────────────┘
                       [DB main busbar]   ← result.busbar_y (already placed)

    Each group gets: junction dot on main busbar → vertical line → RCCB →
    sub-busbar → sub-circuits placed upward.

    Returns the topmost Y (for DB box sizing).
    """
    from app.sld.layout.models import PlacedComponent
    from app.sld.layout.sections import _place_sub_circuits_rows

    result = ctx.result
    config = ctx.config
    pgroups = db.get("protection_groups", [])
    if not pgroups:
        return _place_sub_circuits_rows(ctx)

    main_busbar_y = result.busbar_y  # DB's main busbar Y
    num_groups = len(pgroups)

    # ── Determine available width for this DB ──
    # Priority: active_region (strict) > plan width > fallback
    db_available_width: float | None = None
    if ctx.active_region:
        db_available_width = ctx.active_region.width
    elif ctx.plan and ctx.plan.db_plans:
        db_idx = ctx.current_db_idx
        if 0 <= db_idx < len(ctx.plan.db_plans):
            db_available_width = ctx.plan.db_plans[db_idx].estimated_width

    # Fallback: half the page width for multi-DB
    if not db_available_width:
        db_available_width = (config.max_x - config.min_x) / max(result.db_count, 1)

    # ── Calculate per-group widths and positions ──
    group_circuit_counts = []
    for pg in pgroups:
        n = len(pg.get("circuits", []))
        # PG circuits are single-phase — no triplet rounding.
        group_circuit_counts.append(max(n, 1))

    total_circuits = sum(group_circuit_counts)
    gap = 8  # mm gap between groups — visual PG separation (synced with PG_GAP in _plan_layout)
    # Minimal margins for protection groups (3mm per side = 6mm total per group)
    pg_margin = 3  # mm per side for each group busbar
    margin_overhead = num_groups * 2 * pg_margin + max(0, num_groups - 1) * gap
    # STRICT: never exceed actual available width (no inflation by min_spacing)
    usable_for_circuits = max(db_available_width - margin_overhead, total_circuits * 3)

    # Direct spacing: divide available space among circuits
    direct_spacing = usable_for_circuits / max(total_circuits, 1)
    overall_spacing = max(3.0, min(direct_spacing, config.max_horizontal_spacing))

    # Distribute width proportionally per group
    group_widths = []
    for n in group_circuit_counts:
        w = n * overall_spacing + 2 * pg_margin
        group_widths.append(max(w, 25))

    # Ensure total groups width fits within DB allocation
    total_groups_width = sum(group_widths) + max(0, num_groups - 1) * gap
    if total_groups_width > db_available_width:
        # Compress to fit
        compress = db_available_width / total_groups_width
        group_widths = [w * compress for w in group_widths]
        total_groups_width = db_available_width
        # Recalculate spacing
        overall_spacing *= compress

    groups_start_x = db_cx - total_groups_width / 2
    # Clamp to active region
    if ctx.active_region:
        clamp_left = ctx.active_region.min_x
        clamp_right = ctx.active_region.max_x
        if groups_start_x < clamp_left:
            groups_start_x = clamp_left
        if groups_start_x + total_groups_width > clamp_right:
            groups_start_x = clamp_right - total_groups_width

    # Group center X positions
    group_cxs: list[float] = []
    x_cursor = groups_start_x
    for w in group_widths:
        group_cxs.append(x_cursor + w / 2)
        x_cursor += w + gap

    # ── Place each protection group ──
    topmost_y = main_busbar_y
    rccb_h = config.rccb_h  # RCCB symbol height
    rccb_w = config.rccb_w  # RCCB symbol width

    for pg_idx, (pg, pg_cx, pg_width) in enumerate(
        zip(pgroups, group_cxs, group_widths)
    ):
        rccb_spec = pg.get("rccb", {})
        phase = pg.get("phase", f"L{pg_idx + 1}")
        pg_circuits = pg.get("circuits", [])

        # Junction dot on main busbar
        result.junction_dots.append((pg_cx, main_busbar_y))

        # Vertical connection from main busbar to RCCB
        rccb_y = main_busbar_y + 5  # small gap above busbar
        result.connections.append(((pg_cx, main_busbar_y), (pg_cx, rccb_y)))

        # RCCB symbol
        rccb_rating = rccb_spec.get("rating", 40)
        rccb_ma = rccb_spec.get("sensitivity_ma", 30)
        rccb_poles = rccb_spec.get("poles", 2)
        rccb_type = rccb_spec.get("type", "RCCB")

        result.components.append(PlacedComponent(
            symbol_name="RCCB",
            x=pg_cx - rccb_w / 2,
            y=rccb_y,
            label=f"{rccb_rating}A {rccb_ma}mA",
            rating=f"{rccb_rating}A",
            poles=f"{rccb_poles}P",
            breaker_type_str=rccb_type,
            label_style="breaker_block",
            no_ditto=True,  # Per-phase RCCBs always show full label (ref: I2R-ETR-NLB)
        ))
        result.symbols_used.add("RCCB")

        # Connection from RCCB top to sub-busbar
        rccb_top_y = rccb_y + rccb_h
        sub_busbar_gap = 3
        result.connections.append(((pg_cx, rccb_top_y), (pg_cx, rccb_top_y + sub_busbar_gap)))

        # ── Place sub-circuits for this group ──
        # Save/restore ctx to place circuits at the group's CX
        # CRITICAL: Set per-group active_region so row splitting and
        # spacing use the group width, not the parent DB's region.
        saved_cx = ctx.cx
        saved_y = ctx.y
        saved_sub_circuits = ctx.sub_circuits
        saved_busbar_y = result.busbar_y
        saved_bus_sx = result.busbar_start_x
        saved_bus_ex = result.busbar_end_x
        saved_active_region = ctx.active_region
        saved_constrained_width = ctx.constrained_width

        ctx.cx = pg_cx
        sub_busbar_y_pos = rccb_top_y + sub_busbar_gap
        ctx.y = sub_busbar_y_pos

        # PG circuits are single-phase — no triplet padding needed.
        # Just use the circuits as-is.
        pg_padded = list(pg_circuits)
        for c in pg_padded:
            c["_skip_section_pad"] = True
        ctx.sub_circuits = pg_padded

        # Manually place sub-busbar constrained to group width
        # (Don't call _place_main_busbar() — it uses full-page spacing)
        half_w = pg_width / 2
        sub_bus_sx = pg_cx - half_w
        sub_bus_ex = pg_cx + half_w

        # Set per-group region constraint (prevents circuits from using DB-level width)
        from app.sld.layout.models import LayoutRegion
        ctx.active_region = LayoutRegion(
            min_x=sub_bus_sx, max_x=sub_bus_ex,
            name=f"PG-{phase}",
        )
        ctx.constrained_width = pg_width

        result.busbar_y = sub_busbar_y_pos
        result.busbar_start_x = sub_bus_sx
        result.busbar_end_x = sub_bus_ex

        # Sub-busbar component (phase-specific label matching reference SLD)
        phase_upper = phase.upper() if phase else f"L{pg_idx + 1}"
        sub_busbar_label = f"{db.get('busbar_rating', 80)}A BUSBAR ({phase_upper})"
        result.components.append(PlacedComponent(
            symbol_name="BUSBAR",
            x=sub_bus_sx,
            y=sub_busbar_y_pos,
            label=sub_busbar_label,
            rating="",
        ))

        # Place sub-circuits within constrained busbar range
        sub_busbar_y = _place_sub_circuits_rows(ctx)
        topmost_y = max(topmost_y, sub_busbar_y)

        # NOTE: PG sub-busbars all share the same Y, so we use
        # layout_regions (PG-level) instead of busbar_x_per_row for
        # overlap resolution. Don't store in busbar_x_per_row to avoid
        # dict key collision (all PGs at same Y would overwrite each other).

        # Restore (including region constraint)
        ctx.cx = saved_cx
        ctx.y = saved_y
        ctx.sub_circuits = saved_sub_circuits
        result.busbar_y = saved_busbar_y
        ctx.active_region = saved_active_region
        ctx.constrained_width = saved_constrained_width
        result.busbar_start_x = saved_bus_sx
        result.busbar_end_x = saved_bus_ex

    # Register PG-level regions for overlap resolution.
    # Without this, all PG circuits are processed as one group,
    # causing incorrect spacing. Each PG's circuits should be
    # processed independently within their sub-busbar bounds.
    db_idx = ctx.current_db_idx
    # Remove DB-level region if present (replace with PG regions)
    if db_idx >= 0 and result.layout_regions:
        db_name = db.get("name", "")
        result.layout_regions = [
            r for r in result.layout_regions if r.name != db_name
        ]
    # Always insert PG-level regions
    for pg_idx, (pg_cx, pg_width) in enumerate(zip(group_cxs, group_widths)):
        pg_region = LayoutRegion(
            min_x=pg_cx - pg_width / 2,
            max_x=pg_cx + pg_width / 2,
            name=f"PG-{pgroups[pg_idx].get('phase', f'L{pg_idx + 1}')}",
        )
        result.layout_regions.append(pg_region)

    # Update DB busbar_start_x/end_x to span all groups, clamped to region
    if group_cxs:
        result.busbar_start_x = min(group_cxs) - group_widths[0] / 2
        result.busbar_end_x = max(group_cxs) + group_widths[-1] / 2
        # Clamp to active region (strict) or DB allocated width (fallback)
        if ctx.active_region:
            result.busbar_start_x = max(result.busbar_start_x, ctx.active_region.min_x)
            result.busbar_end_x = min(result.busbar_end_x, ctx.active_region.max_x)
        else:
            db_half = db_available_width / 2
            result.busbar_start_x = max(result.busbar_start_x, db_cx - db_half)
            result.busbar_end_x = min(result.busbar_end_x, db_cx + db_half)

    return topmost_y


def _place_multi_db_boxes(ctx: _LayoutContext, dbs: list[dict],
                          topmost_busbar_y: float) -> float:
    """Place dashed DB boxes for each sub-DB. Returns rightmost DB box edge."""
    result = ctx.result
    config = ctx.config
    rightmost_x = 0.0

    # Pre-compute midpoints between adjacent DBs to prevent box overlap
    db_ranges = result.db_box_ranges
    midpoints: list[float] = []
    for i in range(len(db_ranges) - 1):
        right_edge = db_ranges[i]["busbar_end_x"]
        left_edge = db_ranges[i + 1]["busbar_start_x"]
        midpoints.append((right_edge + left_edge) / 2)

    for range_idx, db_range in enumerate(db_ranges):
        start_y = db_range["db_box_start_y"]
        busbar_y_row = db_range["busbar_y_row"]
        bus_start_x = db_range["busbar_start_x"]
        bus_end_x = db_range["busbar_end_x"]
        breaker_rating = db_range["breaker_rating"]
        db_name = db_range["name"]

        db_info_text = db_range.get("db_info_text", "")
        _has_ct = db_range.get("_has_ct_metering", False)

        # Vertical extents
        if _has_ct:
            box_start_y = start_y  # No downward extension for CT metering
        else:
            box_start_y = start_y - config.db_info_height(db_info_text)

        box_end_y = (busbar_y_row + config.db_box_busbar_margin
                     + config.mcb_h + config.stub_len
                     + config.db_box_tail_margin + config.db_box_label_margin)

        # Horizontal extents — region-based (preferred) or midpoint-based
        box_left = bus_start_x - 10
        box_right = bus_end_x + 10
        if ctx.plan and ctx.plan.db_regions and range_idx < len(ctx.plan.db_regions):
            region = ctx.plan.db_regions[range_idx]
            box_left = region.min_x
            box_right = region.max_x
        else:
            box_left = max(box_left, config.min_x + 1)
            box_right = min(box_right, config.max_x - 1)
            if range_idx > 0 and midpoints:
                box_left = max(box_left, midpoints[range_idx - 1] + 2)
            if range_idx < len(midpoints):
                box_right = min(box_right, midpoints[range_idx] - 2)

        # Emit dashed rect + info labels + location text (shared with _place_db_box)
        db_display_label = db_name if db_name else db_range.get("db_info_label", f"{breaker_rating}A DB")
        _emit_db_box_rect_and_labels(
            result, config,
            box_start_y=box_start_y, box_end_y=box_end_y,
            box_left=box_left, box_right=box_right,
            text_anchor_y=start_y,
            display_label=db_display_label,
            info_text=db_info_text,
            location_text=db_range.get("db_location_text", ""),
            has_ct_metering=_has_ct,
        )

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

    all_xs: list[float] = []
    all_ys: list[float] = []

    for comp in result.components:
        all_xs.append(comp.x)
        all_ys.append(comp.y)
        # Vertical text extends upward from comp.y
        if comp.symbol_name == "LABEL" and abs(comp.rotation - 90.0) < 0.1:
            lines = comp.label.split("\\P")
            max_line_len = max(len(line) for line in lines)
            all_ys.append(comp.y + max_line_len * config.char_w_label)

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
    for jx, jy, jdir in result.junction_arrows:
        all_xs.append(jx)
        all_ys.append(jy)

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

    # Measure sub-circuit spacing from tap positions on busbar.
    # Group CBs by Y-level (±2mm tolerance) to avoid comparing CBs at
    # different vertical levels (main breakers vs sub-circuits), which
    # gives misleading minimum-spacing values.
    from collections import defaultdict
    cb_by_row: dict[float, list[float]] = defaultdict(list)
    for comp in result.components:
        if comp.symbol_name.startswith("CB_"):
            # Round Y to nearest 5mm to group by row
            row_key = round(comp.y / 5) * 5
            cb_by_row[row_key].append(comp.x)

    # Find the busbar row with the most CBs (= sub-circuit row)
    if cb_by_row:
        main_row_y = max(cb_by_row, key=lambda k: len(cb_by_row[k]))
        tap_xs = sorted(set(cb_by_row[main_row_y]))
    else:
        tap_xs = []

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
