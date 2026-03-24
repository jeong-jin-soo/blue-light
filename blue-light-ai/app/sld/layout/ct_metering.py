"""
SLD Layout CT metering section — vertical layout for >=125A 3-phase installations.

Extracted from sections.py. Contains:
- CT_METERING_SPINE_ORDER constant
- _derive_ammeter_range()
- _place_ct_metering_section()
- _place_bi_crossbar_circuits()
- _place_metering_branch()
"""

from __future__ import annotations

import logging
import re

from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent, _LayoutContext
from app.sld.locale import SG_LOCALE

logger = logging.getLogger(__name__)


# ── Electrical Flow Specification (Singapore CT Metering) ──
# Defines the CORRECT order of spine components from supply (bottom) to
# load (top) in a CT metering installation (≥125A, 3-phase, non-landlord).
#
# Each entry: (symbol_name, role_description)
# This order is validated by test_spine_flow_order.py to prevent regressions.
CT_METERING_SPINE_ORDER: list[tuple[str, str]] = [
    # --- Supply side (bottom, lowest Y) ---
    ("CB_MCCB",         "Main breaker — overcurrent protection after supply"),
    ("CT",              "Protection CT — for Earth Leakage Relay (ELR)"),
    # ELR branches LEFT from Protection CT (not on spine)
    ("CT",              "Metering CT — for kWh meter, ammeter, voltmeter"),
    # ASS+Ammeter LEFT, VSS+Voltmeter RIGHT, KWH RIGHT (branches, not on spine)
    ("BI_CONNECTOR",    "Busbar Interconnect — connects metering to distribution"),
    # --- Load side (top, highest Y) ---
    #
    # NOTE: 2A POTENTIAL_FUSE is NOT on the spine. Per 150A/400A TPN reference
    # DWGs, fuses are horizontal branch elements that depart rightward from
    # T-junctions on the spine. The vertical spine LINE runs uninterrupted
    # past fuse junction points.
    # Ref: 150A DXF — FUSE INSERT at X≈23494 (1100 DU right of spine X≈22385),
    #      horizontal LINE (22385,6376)→(23230,6376) connects spine to fuse.
]


def _derive_ammeter_range(ct_ratio: str) -> str:
    """Derive ammeter range from CT ratio primary current.

    '100/5A' → '0 - 100A', '200/5A' → '0 - 200A'.
    Falls back to '0 - 500A' when ratio is absent or unparseable.
    Reference format uses spaces around hyphen: '0 - 100A'.
    """
    if not ct_ratio:
        return "0 - 500A"
    m = re.match(r"(\d+)/", ct_ratio)
    return f"0 - {m.group(1)}A" if m else "0 - 500A"


def _place_ct_metering_section(ctx: _LayoutContext) -> None:
    """Place vertical CT metering section (≥125A 3-phase, non-landlord).

    Called AFTER _place_ct_pre_mccb_fuse and _place_main_breaker in engine.py.

    Correct flow order (bottom → top, supply → load):
      (Pre-MCCB fuse branch RIGHT + MCCB already placed by engine.py)
      → PROTECTION_CT [ELR LEFT]
      → METERING_CT
          [ASS+Ammeter LEFT, VSS+Voltmeter RIGHT — same height]
          [KWH RIGHT — below CT center]
      → 2A POTENTIAL_FUSE (RIGHT branch, NOT on spine)
      → BI_CONNECTOR (exit to ELCB/busbar)

    Components on the main vertical spine:
      CT (protection), CT (metering), BI_CONNECTOR
    Horizontal branches from spine:
      LEFT:  ELR (at Protection CT center), ASS→Ammeter (at Metering CT center)
      RIGHT: VSS→Voltmeter (at Metering CT center), KWH (below Metering CT),
             Pre-MCCB fuse (before MCCB), Post-CT fuse (after Metering CT)

    DXF reference: 2A FUSE is always a horizontal branch element. The vertical
    spine LINE runs uninterrupted past fuse junction points.
    Ref: CT_METERING_SPINE_ORDER constant, 150A/400A TPN reference DWGs.
    """
    result = ctx.result
    config = ctx.config
    cx = ctx.cx
    y = ctx.y

    result.sections_rendered["ct_metering_section"] = True

    ct_ratio = ctx.ct_ratio  # may be empty — "CT BY SP" label used when empty
    metering_ct_class = ctx.metering_ct_class or "CL1 5VA"
    protection_ct_ratio = ctx.protection_ct_ratio or ct_ratio
    protection_ct_class = ctx.protection_ct_class or "5P10 20VA"

    # Symbol dimensions from ComponentCatalog (single source of truth)
    from app.sld.layout.section_base import sym_dims
    bi_w, bi_h, bi_stub = sym_dims("BI_CONNECTOR")
    ct_w, ct_h, ct_stub = sym_dims("CT")
    pf_w, pf_h, pf_stub = sym_dims("POTENTIAL_FUSE")

    # Spacing constants — centralized in LayoutConfig (Phase 4: magic number → config)
    entry_gap = ctx.config.ct_entry_gap
    ct_to_ct_gap = ctx.config.ct_to_ct_gap
    ct_to_branch_gap = ctx.config.ct_to_branch_gap
    # Compact mode (multi-DB): tighter branch spacing
    _compact = ct_to_ct_gap < 2.0
    branch_arm_len = 10.0 if _compact else 15.0  # horizontal arm from spine
    branch_gap = 2.0 if _compact else 3.0        # gap between components on a branch
    # Branch direction: ELR/ASS always LEFT, KWH/VSS/fuse always RIGHT.
    # This matches reference DWG convention regardless of single/multi-DB.
    ct_to_pf_gap = 0.5 if _compact else 1.0      # gap between metering CT and potential fuse
    pf_to_bi_gap = 0.5 if _compact else 1.0      # gap between potential fuse and BI connector

    # ═══════════════════════════════════════════════════════════════════════
    # Correct flow order (bottom → top, supply → load):
    #   Protection CT → Metering CT → BI_CONNECTOR
    # Ref: CT_METERING_SPINE_ORDER constant
    #
    # Drawing strategy: ONE straight spine line from MCCB exit to BI
    # Connector. All CT components and branches overlay on this backbone.
    # ═══════════════════════════════════════════════════════════════════════

    spine_bottom = y  # entry point (after MCCB)

    # --- 1. Calculate component positions along spine ---
    cursor = y

    # Protection CT (on spine, closest to supply/MCCB)
    prot_ct_y = cursor
    prot_ct_center_y = cursor + ct_h / 2
    if ctx.has_elr:
        cursor += ct_h + ct_stub + ct_to_ct_gap

    # Reserve space for KWH branch arm between Protection CT and Metering CT.
    # The arm exits the spine at _kwh_branch_y; metering CT bottom stub must
    # be above that line so the arm doesn't visually cross the metering CT.
    from app.sld.real_symbols import get_real_symbol as _get_kwh_pre
    _kwh_pre = _get_kwh_pre("KWH_METER")
    _kwh_rect_h_pre = getattr(_kwh_pre, '_rect_h', 3.9)
    # KWH branch Y needs enough clearance from Protection CT hooks
    _kwh_arm_y_pre = prot_ct_center_y + 3.0 + _kwh_rect_h_pre / 2 + 1.5
    _min_metering_start = _kwh_arm_y_pre + ct_stub + 1.0
    cursor = max(cursor, _min_metering_start)

    # Metering CT (on spine)
    metering_ct_y = cursor
    metering_ct_center_y = cursor + ct_h / 2
    cursor += ct_h

    # Advance past branches — reserve enough height for fuse + VSS + ASS branches
    _branch_reserve = 14.0 if _compact else 20.0
    highest_branch = metering_ct_center_y + ct_to_branch_gap * 2 + _branch_reserve
    cursor = max(cursor, highest_branch)
    cursor += ct_to_ct_gap

    # BI Connector (on spine, closest to distribution busbar/load)
    bi_y = cursor
    bi_center_y = bi_y + bi_h / 2
    # Spine backbone terminates at crossbar (BI center), not BI top + stub.
    spine_line_top = bi_center_y
    # But ctx.y (next component start) stays above BI connector for proper spacing.
    spine_top = bi_y + bi_h + bi_stub

    # --- 2. Spine backbone — ONE straight line, MCCB exit → crossbar ---
    result.connections.append(((cx, spine_bottom), (cx, spine_line_top)))

    # --- 3. Place components on spine ---

    # Protection CT
    if ctx.has_elr:
        if protection_ct_ratio:
            prot_ct_label = f"{protection_ct_ratio} CT\\P{protection_ct_class}"
        else:
            prot_ct_label = f"CT\\P{protection_ct_class}"
        result.components.append(PlacedComponent(
            symbol_name="CT", x=cx - ct_w / 2, y=prot_ct_y, label=prot_ct_label,
        ))

        # Branch from Protection CT (LEFT): ELR
        # "ELR" is drawn inside the box by the symbol itself.
        # Label shows only the spec text to the left (reference format: "0-3A\P0.2 SEC").
        elr_label = ctx.elr_spec if ctx.elr_spec else ""
        _place_metering_branch(
            result, cx, prot_ct_center_y, direction="left",
            components=[("ELR", elr_label, 12.0)],
            arm_len=branch_arm_len, gap=branch_gap,
        )
        result.junction_arrows.append((cx, prot_ct_center_y, "left"))
        result.symbols_used.add("ELR")

        # ELR sensing line: bottom exit → down → right to MCCB contacts
        # Reference: line exits from ELR bottom center, not left side
        if ctx.main_breaker_arc_center_y:
            from app.sld.real_symbols import get_real_symbol
            elr_sym = get_real_symbol("ELR")
            elr_hp = elr_sym.horizontal_pins(0, 0)
            elr_stub = getattr(elr_sym, '_stub', 2.0)
            elr_body = elr_hp["right"][0] - elr_hp["left"][0] - 2 * elr_stub
            # ELR bottom center X
            elr_comp_x = cx - branch_arm_len - elr_body
            elr_bottom_cx = elr_comp_x + elr_sym.width / 2
            # ELR bottom stub tip Y
            elr_bottom_y = prot_ct_center_y - elr_sym.height / 2 - elr_stub
            mccb_y = ctx.main_breaker_arc_center_y
            # Vertical down from bottom stub tip
            result.connections.append(((elr_bottom_cx, elr_bottom_y), (elr_bottom_cx, mccb_y)))
            # Horizontal right — stops just before MCCB contact gap
            result.connections.append(((elr_bottom_cx, mccb_y), (cx - 1.0, mccb_y)))

    # Metering CT
    if ct_ratio:
        ct_label = f"{ct_ratio}\\P{metering_ct_class}"
    else:
        ct_label = SG_LOCALE.meter_board.ct_by_sp
    # --- 4. Branches from Metering CT ---
    # Reference DWG order (bottom → top): VSS/Voltmeter, ASS/Ammeter, 2A fuse, BI.
    branch_y = metering_ct_center_y

    # Pre-compute KWH and instrument fuse Y to place ASS at midpoint.
    # KWH branch Y (same formula as below, computed early for ASS positioning):
    from app.sld.real_symbols import get_real_symbol as _get_kwh_pre_calc
    _kwh_pre_calc = _get_kwh_pre_calc("KWH_METER")
    _kwh_rect_h_pre_calc = getattr(_kwh_pre_calc, '_rect_h', 3.9)
    _kwh_return_y_pre = prot_ct_center_y + 3.0
    _elr_box_h_pre = 6.0
    _kwh_branch_y_pre = _kwh_return_y_pre + _kwh_rect_h_pre_calc / 2 + _elr_box_h_pre
    # Instrument fuse Y:
    _inst_fuse_y_pre = bi_y - 4.0

    # ASS branch Y: midpoint between KWH (below) and instrument fuse (above)
    # This evenly spaces hooks to prevent overlap.
    ass_branch_y = (_kwh_branch_y_pre + _inst_fuse_y_pre) / 2
    # Fallback: at least ct_to_branch_gap above metering CT
    ass_branch_y = max(ass_branch_y, branch_y + ct_to_branch_gap)
    # VSS Y: default fallback (overridden below when instrument fuse is present)
    vss_branch_y = ass_branch_y + ct_to_branch_gap

    # Place Metering CT — label aligned with ASS branch height
    result.components.append(PlacedComponent(
        symbol_name="CT", x=cx - ct_w / 2, y=metering_ct_y, label=ct_label,
        label_y_override=ass_branch_y + 3,
    ))
    result.symbols_used.add("CT")

    # Branch 1 (RIGHT): VSS → Voltmeter
    # Reference DWG: VSS branch originates from the instrument fuse branch
    # (midpoint between fuse and LED), NOT from the spine.
    # Diagonal connection goes from fuse branch down to VSS at vss_branch_y.
    # Placement is deferred until after the instrument fuse branch is placed,
    # so we can compute the fuse-LED midpoint coordinates.
    # (See "VSS diagonal branch" section below the instrument fuse.)

    # Branch 2 (LEFT): ASS → Ammeter (circle with "A" inside + range label)
    # Always LEFT — reference DWG shows ASS/Ammeter on left side of spine.
    if ctx.has_ammeter:
        _place_metering_branch(
            result, cx, ass_branch_y, direction="left",
            components=[
                ("SELECTOR_SWITCH", "ASS", 8.0),
                ("AMMETER", ctx.ammeter_range or "0-500A", 7.6),
            ],
            arm_len=9.0, gap=7.0,
        )
        result.junction_arrows.append((cx, ass_branch_y, "left"))

    # Branch 3 (RIGHT): KWH Meter (no MCB, no right stub — per reference DWG)
    # Return line connects 3mm above ELR hook (prot_ct_center_y).
    from app.sld.real_symbols import get_real_symbol as _get_kwh_sym
    _kwh_sym = _get_kwh_sym("KWH_METER")
    _kwh_rect_w = getattr(_kwh_sym, '_rect_w', 7.8)
    _kwh_rect_h = getattr(_kwh_sym, '_rect_h', 3.9)
    _kwh_hp = _kwh_sym.horizontal_pins(0, 0)

    _kwh_return_y = prot_ct_center_y + 3.0      # 3mm above ELR hook
    # KWH branch Y: box bottom → return line drop = ELR box height (6mm)
    # so the vertical line below KWH matches ELR box proportions.
    _elr_box_h = 6.0  # ELR box height from real_symbol_paths.json
    _kwh_branch_y = _kwh_return_y + _kwh_rect_h / 2 + _elr_box_h

    _kwh_label = "SPPG\\PKWH METER" if ctx.supply_source != "landlord" else "KWH METER"

    # Place KWH component (right of spine, no right stub)
    _kwh_comp_x = cx + branch_arm_len  # rect left edge (same as _place_metering_branch)
    result.components.append(PlacedComponent(
        symbol_name="KWH_METER",
        x=_kwh_comp_x,
        y=_kwh_branch_y,
        label=_kwh_label,
        rotation=90.0,
        no_right_stub=True,
    ))
    result.symbols_used.add("KWH_METER")

    # Arm connection: spine → KWH left pin
    result.connections.append(((cx, _kwh_branch_y), (cx + branch_arm_len, _kwh_branch_y)))
    result.junction_arrows.append((cx, _kwh_branch_y, "right"))

    # KWH return connection: bottom of KWH box → down → left to spine.
    # Creates CT measurement loop per reference DWG (SP Group §6.9.6).
    _kwh_bottom_cx = _kwh_comp_x + _kwh_rect_w / 2  # bottom center X of rect
    _kwh_bottom_y = _kwh_branch_y - _kwh_rect_h / 2  # bottom edge Y of rect
    # Vertical: KWH bottom → down to return Y
    result.connections.append((
        (_kwh_bottom_cx, _kwh_bottom_y),
        (_kwh_bottom_cx, _kwh_return_y),
    ))
    # Horizontal: return point → left to spine
    result.connections.append((
        (_kwh_bottom_cx, _kwh_return_y),
        (cx, _kwh_return_y),
    ))

    # Instrument fuse (RIGHT branch below BI Connector)
    # Reference DWG: 2A fuse + indicator lights for instrument protection
    # (ammeter/voltmeter circuits). Same layout as pre-MCCB fuse branch.
    if ctx._ct_pre_mccb_fuse:  # instrument fuse present when CT metering is active
        # Position very close to BI so VSS diagonal doesn't overlap KWH box
        _inst_fuse_y = bi_y - 4.0  # 4mm below BI connector
        # VSS Y: below fuse branch with longer diagonal for visual clarity
        vss_branch_y = _inst_fuse_y - 10.0
        _inst_fuse_components: list[tuple[str, str, float]] = [
            ("POTENTIAL_FUSE", "2A", pf_h),
        ]
        if ctx.has_indicator_lights:
            from app.sld.real_symbols import get_symbol_dimensions as _get_il_dims
            _il_dims = _get_il_dims("INDICATOR_LIGHTS")
            _inst_fuse_components.append(("INDICATOR_LIGHTS", "", _il_dims["width_mm"]))
        _inst_fuse_gap = 10.0  # wider gap for VSS diagonal start visibility
        _place_metering_branch(
            result, cx, _inst_fuse_y, direction="right",
            components=_inst_fuse_components,
            arm_len=8.0,
            gap=_inst_fuse_gap,
        )
        result.junction_dots.append((cx, _inst_fuse_y))
        result.symbols_used.add("POTENTIAL_FUSE")
        if ctx.has_indicator_lights:
            result.symbols_used.add("INDICATOR_LIGHTS")

        # --- VSS diagonal branch (from fuse-LED midpoint) ---
        # Reference DWG: diagonal originates at midpoint between fuse right stub
        # tip and LED left stub tip, going down-right to VSS left stub tip.
        if ctx.has_voltmeter:
            from app.sld.real_symbols import get_real_symbol as _get_vss_sym
            _pf_sym = _get_vss_sym("POTENTIAL_FUSE")
            _pf_hp = _pf_sym.horizontal_pins(0, 0)
            _pf_stub = getattr(_pf_sym, '_stub', 1.5)
            _pf_body_w = _pf_hp["right"][0] - _pf_hp["left"][0] - 2 * _pf_stub

            _il_sym = _get_vss_sym("INDICATOR_LIGHTS")
            _il_stub = getattr(_il_sym, '_stub', 3.0)  # default 3.0 in _place_metering_branch

            # Fuse right stub tip = cx + arm + body_w + stub
            _fuse_right_stub_tip = cx + 8.0 + _pf_body_w + _pf_stub
            # LED left stub tip = cx + arm + body_w + gap - il_stub
            _led_left_stub_tip = cx + 8.0 + _pf_body_w + _inst_fuse_gap - _il_stub
            # Exact midpoint between the two stub tips
            _diag_start_x = (_fuse_right_stub_tip + _led_left_stub_tip) / 2
            _diag_start_y = _inst_fuse_y

            # VSS + Voltmeter placed horizontally at vss_branch_y
            _vss_sym = _get_vss_sym("SELECTOR_SWITCH")
            _vss_r = getattr(_vss_sym, '_radius', 2.0)
            _vss_stub = getattr(_vss_sym, '_stub', 2.0)
            _vss_body_w = 2 * _vss_r  # 4.0mm

            _vm_sym = _get_vss_sym("VOLTMETER")
            _vm_r = getattr(_vm_sym, '_radius', 2.0)
            _vm_stub = getattr(_vm_sym, '_stub', 2.0)
            _vm_body_w = 2 * _vm_r  # 4.0mm

            # 45° diagonal: horizontal shift = vertical drop.
            # Diagonal end connects to VSS left stub tip.
            _vert_drop = _diag_start_y - vss_branch_y
            # diag_end_x = diag_start_x + vert_drop (true 45°)
            _diag_end_x = _diag_start_x + _vert_drop
            _diag_end_y = vss_branch_y
            # VSS body starts at stub distance right of diagonal end
            _vss_body_x = _diag_end_x + _vss_stub

            # Diagonal connection: fuse-LED midpoint → VSS left stub tip
            # v2 architecture: validate_connectivity removed, so this can
            # be a regular connection without risk of post-hoc snapping.
            result.connections.append((
                (_diag_start_x, _diag_start_y),
                (_diag_end_x, _diag_end_y),
            ))

            # Place VSS component
            result.components.append(PlacedComponent(
                symbol_name="SELECTOR_SWITCH",
                x=_vss_body_x,
                y=vss_branch_y,
                label="VSS",
                rotation=90.0,
            ))
            result.symbols_used.add("SELECTOR_SWITCH")

            # Gap connection: VSS right pin → Voltmeter left pin
            _vss_right_pin_x = _vss_body_x + _vss_body_w + _vss_stub
            _vm_body_x = _vss_right_pin_x + branch_gap
            _vm_left_pin_x = _vm_body_x - _vm_stub
            result.connections.append((
                (_vss_right_pin_x, vss_branch_y),
                (_vm_left_pin_x, vss_branch_y),
            ))

            # Place Voltmeter component (last on branch → no right stub)
            result.components.append(PlacedComponent(
                symbol_name="VOLTMETER",
                x=_vm_body_x,
                y=vss_branch_y,
                label=ctx.voltmeter_range or "0-500V",
                rotation=90.0,
                no_right_stub=True,
            ))
            result.symbols_used.add("VOLTMETER")

    # BI Connector — include isolator/busbar rating (e.g., "100A BI CONNECTOR")
    # Reference: crossbar line through BI connector matches busbar length.
    # The crossbar line is drawn AFTER busbar placement (in render_board) so it
    # can match busbar_start_x/busbar_end_x exactly.
    _bi_rating = ctx.busbar_rating or ctx.breaker_rating or 0
    _bi_label = f"{_bi_rating}A BI CONNECTOR" if _bi_rating else "BI CONNECTOR"
    result.components.append(PlacedComponent(
        symbol_name="BI_CONNECTOR", x=cx - bi_w / 2, y=bi_y, label=_bi_label,
        crossbar_extend=0,  # crossbar drawn separately after busbar placement
    ))
    result.symbols_used.add("BI_CONNECTOR")

    # Store BI connector center Y for post-busbar crossbar line
    ctx.bi_center_y = bi_y + bi_h / 2

    ctx.y = spine_top


def _place_bi_crossbar_circuits(
    result: LayoutResult,
    ctx,
    spine_cx: float,
    bi_y: float, bi_w: float, bi_h: float,
    circuits: list[dict],
    spacing: float = 15.0,
    busbar_end_x: float = 0,
    busbar_y: float = 0,
) -> float:
    """Place circuits branching UPWARD from BI Connector crossbar.

    Reference: The BI Connector crossbar acts as a secondary sub-busbar.
    Circuits (SPARE, DB2 feeder) branch upward from it at the RIGHT END
    of the crossbar, beyond the main busbar sub-circuits.  Their vertical
    lines extend upward past the DB box top so they can connect to child
    boards positioned to the right of the MSB.

    Returns the rightmost X coordinate used (for crossbar line extension).
    """
    from app.sld.layout.section_base import sym_dims as _sym_dims
    config = ctx.config

    bi_center_y = bi_y + bi_h / 2
    mcb_w, mcb_h, mcb_stub = _sym_dims("MCB")

    # Position crossbar circuits at the RIGHT END of the crossbar,
    # WITHIN the busbar range (reference: SPARE + DB feeder are under
    # the busbar, not beyond it).  Place them right-aligned at busbar_end_x.
    n_circuits = len(circuits)
    _right_margin = 1.0
    _bi_h_shift = bi_h  # shift right by BI connector height (~5.5mm)
    if busbar_end_x:
        # Right-align circuits at crossbar end, shifted right by BI height
        crossbar_start_x = busbar_end_x - n_circuits * spacing + spacing / 2 - _right_margin + _bi_h_shift
    else:
        crossbar_start_x = spine_cx + bi_w / 2 + 3 + _bi_h_shift

    # Height target: circuits go up past the DB box top (above busbar + circuit area).
    # busbar_y is the main busbar Y.  Busbar circuits go ~40-50mm above it.
    # Crossbar circuit tails should reach a similar height.
    _target_top_y = busbar_y + 45.0 if busbar_y else bi_center_y + 80.0

    rightmost_x = crossbar_start_x

    for i, ckt in enumerate(circuits):
        ckt_x = crossbar_start_x + i * spacing
        rightmost_x = ckt_x
        ckt_type = (ckt.get("type") or ckt.get("breaker_type") or "MCB").upper()
        ckt_rating = ckt.get("rating", ckt.get("breaker_rating", 0))
        ckt_id = (ckt.get("id") or ckt.get("circuit_id") or "").upper()
        ckt_name = (ckt.get("name") or "").upper()
        is_spare = ckt_type == "SPARE" or ckt_id == "SPARE" or ckt_name == "SPARE"
        is_feeder = ckt.get("_is_feeder", False)

        # Junction dot on crossbar
        result.junction_dots.append((ckt_x, bi_center_y))

        # MCB placement (branching upward from crossbar)
        # SPARE also gets MCB symbol (per reference DWG), with dashed tail above
        # MCB body starts above crossbar; bottom pin = body_y - stub
        mcb_bottom_y = bi_center_y + mcb_stub + 1
        mcb_bottom_pin_y = mcb_bottom_y - mcb_stub   # bottom pin tip
        mcb_top_pin_y = mcb_bottom_y + mcb_h + mcb_stub  # top pin tip
        # Connection: crossbar junction → MCB bottom pin
        result.connections.append(((ckt_x, bi_center_y), (ckt_x, mcb_bottom_pin_y)))

        # Build MCB label
        feeder_brk = ckt.get("feeder_breaker", {})
        if is_spare:
            # SPARE MCB: use same spec as feeder MCBs on this crossbar
            # Find feeder breaker spec from sibling circuits
            _spare_brk = {}
            for _sib in circuits:
                _sib_fb = _sib.get("feeder_breaker", {})
                if _sib_fb and _sib_fb.get("rating"):
                    _spare_brk = _sib_fb
                    break
            f_rating = _spare_brk.get("rating", 0)
            f_type = _spare_brk.get("type", "MCB")
            f_char = _spare_brk.get("characteristic", _spare_brk.get("breaker_characteristic", ""))
            f_poles = _spare_brk.get("poles", "TPN")
            f_kA = _spare_brk.get("fault_kA", 10)
        elif feeder_brk and feeder_brk.get("rating"):
            f_rating = feeder_brk["rating"]
            f_type = feeder_brk.get("type", "MCB")
            f_char = feeder_brk.get("characteristic", feeder_brk.get("breaker_characteristic", ""))
            f_poles = feeder_brk.get("poles", "TPN")
            f_kA = feeder_brk.get("fault_kA", 10)
        else:
            f_rating = ckt_rating
            f_type = ckt_type if ckt_type != "SPARE" else "MCB"
            f_char = ckt.get("characteristic", ckt.get("breaker_characteristic", ""))
            f_poles = ckt.get("poles", "SPN")
            f_kA = ckt.get("fault_kA", 6)

        sym_name = f"CB_{f_type}"
        # Build stacked label (e.g., "63A\PTYPE C\PMCB\P10KA")
        _label_parts = []
        if f_rating:
            _label_parts.append(f"{f_rating}A")
        if f_char:
            _label_parts.append(f"TYPE {f_char}")
        if f_type:
            _label_parts.append(f_type)
        if f_kA:
            _ka = "KA" if f_kA >= 10 else "kA"
            _label_parts.append(f"{f_kA}{_ka}")
        _xbar_label = "\\P".join(_label_parts) if _label_parts else ""
        result.components.append(PlacedComponent(
            symbol_name=sym_name,
            x=ckt_x - mcb_w / 2,
            y=mcb_bottom_y,
            label=_xbar_label,
            no_trip_arrow=True,  # BI crossbar MCB: no trip arrow per reference DWG
            label_side="left",   # Label on -X side (user's left)
        ))
        result.symbols_used.add(f_type)

        # Conductor tail above MCB — extends to target height (past DB box top)
        tail_top_y = max(mcb_top_pin_y + 12, _target_top_y)

        if is_spare:
            # SPARE: short-dashed tail line + "SPARE" label (no cable tick)
            result.short_dashed_connections.append(((ckt_x, mcb_top_pin_y), (ckt_x, tail_top_y)))
            result.components.append(PlacedComponent(
                symbol_name="LABEL",
                x=ckt_x - 3,
                y=tail_top_y + 5,
                label="SPARE",
            ))
        else:
            # Solid tail line — stop at feeder symbol bottom if present
            _tail_end = tail_top_y
            if is_feeder:
                _tail_end = tail_top_y  # feeder symbol sits ON tail_top_y; its stub connects
            result.connections.append(((ckt_x, mcb_top_pin_y), (ckt_x, _tail_end)))

            # Cable tick + cable spec label
            cable_spec = ckt.get("cable", "")
            if not cable_spec and is_feeder:
                cable_spec = ckt.get("incoming_cable", "")
            if cable_spec:
                if is_feeder:
                    # Feeder: tick + leader line below feeder symbol,
                    # between symbol bottom and DB box top boundary
                    _tick_y = tail_top_y - 4
                    _tick_half = 1.5
                    result.connections.append(((ckt_x - _tick_half, _tick_y - _tick_half),
                                               (ckt_x + _tick_half, _tick_y + _tick_half)))
                    # Horizontal leader line from tick to the right
                    _leader_len = 8.0
                    result.connections.append(((ckt_x, _tick_y),
                                               (ckt_x + _leader_len, _tick_y)))
                    # Cable spec text above leader line
                    result.components.append(PlacedComponent(
                        symbol_name="LABEL",
                        x=ckt_x + _leader_len + 1,
                        y=_tick_y,
                        label=cable_spec.upper(),
                        rotation=90.0,
                    ))
                else:
                    # Normal crossbar circuit: tick near MCB with rotated label
                    _tick_y = mcb_top_pin_y + 4
                    _tick_half = 1.5
                    result.connections.append(((ckt_x - _tick_half, _tick_y - _tick_half),
                                               (ckt_x + _tick_half, _tick_y + _tick_half)))
                    result.components.append(PlacedComponent(
                        symbol_name="LABEL",
                        x=ckt_x - 5,
                        y=_tick_y + 2,
                        label=cable_spec.upper(),
                        rotation=90.0,
                    ))

            # Feeder destination: FEEDER_POINT symbol + DB name label
            # Per reference DXF: rectangle with half-filled triangle (7×3.2mm),
            # positioned on conductor with "DB2" label to the left.
            if is_feeder:
                feeds_db = ckt.get("_feeds_db", ckt_id)
                _fp_w = 7.0
                _fp_h = 3.2
                # Place at top of feeder line — bottom edge at tail_top_y
                _fp_y = tail_top_y
                result.components.append(PlacedComponent(
                    symbol_name="FEEDER_POINT",
                    x=ckt_x - _fp_w / 2,
                    y=_fp_y,
                    label="",
                ))
                result.symbols_used.add("FEEDER_POINT")
                # DB name label above feeder symbol, vertical (90° rotation)
                # Offset x by -char_height so label center aligns with symbol center
                _label_ch = 2.5  # approx LayoutConfig.label_char_height
                result.components.append(PlacedComponent(
                    symbol_name="LABEL",
                    x=ckt_x - _label_ch,
                    y=_fp_y + _fp_h + 2,
                    label=feeds_db,
                    rotation=90.0,
                ))
                result.crossbar_feeder_exits[feeds_db.upper()] = (ckt_x, tail_top_y)
            else:
                load_desc = ckt.get("load", "")
                if load_desc:
                    result.components.append(PlacedComponent(
                        symbol_name="LABEL",
                        x=ckt_x - 5,
                        y=tail_top_y + 2,
                        label=load_desc,
                        rotation=90.0,
                    ))

    return rightmost_x + spacing / 2  # return rightmost extent for crossbar line


def _place_metering_branch(
    result: LayoutResult, cx: float, branch_y: float,
    direction: str,
    components: list[tuple[str, str, float]],
    arm_len: float = 15.0,
    gap: float = 3.0,
) -> None:
    """Place a horizontal branch from the main spine.

    Components occupy their own space (no line through them).
    Connection lines bridge: spine→arm→comp1→gap→comp2→...
    Symbol widths are resolved from the registry to guarantee connectivity.

    Args:
        result: LayoutResult to add components/connections to.
        cx: spine X coordinate.
        branch_y: Y coordinate of the branch.
        direction: 'left' or 'right'.
        components: list of (symbol_name, label, width_hint) tuples.
            width_hint is used as fallback only if the symbol is not found
            in the registry; otherwise the real symbol width is used.
        arm_len: length of the initial arm from spine to first component.
        gap: gap between components on the branch.
    """
    from app.sld.real_symbols import get_real_symbol

    sign = -1 if direction == "left" else 1
    x = cx

    # Initial arm from spine to first component
    x_next = x + sign * arm_len
    result.connections.append(((x, branch_y), (x_next, branch_y)))
    x = x_next

    for i, (symbol_name, label, w_hint) in enumerate(components):
        # Resolve actual horizontal body extent from pin positions.
        # Most symbols: h_extent == height (rotated 90°).
        # Some (ELR, KWH_METER, INDICATOR_LIGHTS) use a different extent.
        try:
            sym = get_real_symbol(symbol_name)
            hp = sym.horizontal_pins(0, 0)
            stub = getattr(sym, '_stub', 3.0)
            w = hp["right"][0] - hp["left"][0] - 2 * stub
        except (ValueError, KeyError):
            w = w_hint

        # Place symbol (horizontal orientation)
        if sign > 0:  # right
            comp_x = x
        else:  # left: symbol extends leftward
            comp_x = x - w

        is_last = (i == len(components) - 1)
        # Last component on branch: suppress outer stub (no dangling line)
        comp_kwargs = {}
        if is_last:
            if direction == "left":
                comp_kwargs["no_left_stub"] = True
            else:
                comp_kwargs["no_right_stub"] = True

        result.components.append(PlacedComponent(
            symbol_name=symbol_name,
            x=comp_x,
            y=branch_y,
            label=label,
            rotation=90.0,
            **comp_kwargs,
        ))
        result.symbols_used.add(symbol_name.replace("CB_", ""))

        # Advance past this component
        x = x + sign * w

        # Gap connection to next component (if not last)
        if not is_last:
            x_next = x + sign * gap
            result.connections.append(((x, branch_y), (x_next, branch_y)))
            x = x_next
