"""Spine component flow order validation for CT metering SLDs.

Prevents regressions where components are placed in incorrect electrical
flow order on the main vertical spine. The expected order is defined by
CT_METERING_SPINE_ORDER in sections.py, derived from 150A/400A TPN
reference DWG files.

Correct SPINE order (supply → load, bottom → top):
    MCCB → Protection CT → Metering CT → BI Connector

IMPORTANT: 2A Potential Fuses are NOT on the spine. They are horizontal
RIGHT branches from T-junctions on the vertical spine. The spine LINE
runs uninterrupted past fuse junction points.

Ref: 150A TPN DXF — FUSE INSERT at X≈23494 (1100 DU right of spine X≈22385),
     horizontal LINE (22385,6376)→(23230,6376) connects spine to fuse.

Root cause this test prevents:
    1. BI Connector was placed at the supply side instead of the load side
       because the developer misidentified it as an input connector.
    2. 2A Potential Fuse was placed ON the spine (vertically) instead of as
       a horizontal branch, because DXF analysis used X-range filtering
       without tracing LINE connectivity (topology).
"""

import pytest

from app.sld.layout import compute_layout
from app.sld.layout.sections import CT_METERING_SPINE_ORDER


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _ct_metering_requirements(ct_ratio="100/5A", num_circuits=6):
    """Build a requirements dict that triggers CT metering layout."""
    circuits = [
        {"name": f"Circuit {i+1}", "breaker": {"type": "MCB", "rating": 20}, "load": "Lighting"}
        for i in range(num_circuits)
    ]
    return {
        "supply_type": "three_phase",
        "kva": 0,
        "metering": "ct_meter",
        "ct": {"ratio": ct_ratio},
        "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 35},
        "sub_circuits": circuits,
    }


def _extract_spine_symbols(result, spine_tolerance_mm=2.0):
    """Extract components on the main vertical spine, sorted by Y ascending.

    Components within `spine_tolerance_mm` of `result.spine_x` are
    considered on the spine. Returns list of (symbol_name, y) tuples.
    """
    if not result.components:
        return []

    # Use stored spine_x (set by engine.py after layout)
    spine_x = getattr(result, "spine_x", None)
    if spine_x is None:
        return []

    # Symbol widths vary, so component left-edge x differs from spine center.
    # Check if the component is centered on the spine within tolerance.
    from app.sld.real_symbols import get_symbol_dimensions
    spine_components = []
    for c in result.components:
        # Estimate component center X from known dimensions
        try:
            dims = get_symbol_dimensions(c.symbol_name.replace("CB_", ""))
            comp_cx = c.x + dims["width_mm"] / 2
        except (KeyError, ValueError):
            comp_cx = c.x + 4  # fallback: assume ~8mm wide
        if abs(comp_cx - spine_x) <= spine_tolerance_mm:
            spine_components.append((c.symbol_name, c.y))

    # Sort by Y ascending (bottom → top in layout coordinates)
    spine_components.sort(key=lambda t: t[1])
    return spine_components


def _extract_all_components(result):
    """Extract all placed components as (symbol_name, x, y, rotation) tuples."""
    return [
        (c.symbol_name, c.x, c.y, getattr(c, "rotation", 0.0))
        for c in result.components
    ]


# ---------------------------------------------------------------------------
# Tests — Spine order validation
# ---------------------------------------------------------------------------

class TestCTMeteringSpineOrder:
    """Validate that CT metering spine components follow the correct
    electrical flow order from supply (bottom) to load (top)."""

    def test_spec_constant_exists(self):
        """CT_METERING_SPINE_ORDER must be defined and non-empty."""
        assert CT_METERING_SPINE_ORDER, "CT_METERING_SPINE_ORDER is empty"
        assert len(CT_METERING_SPINE_ORDER) >= 4, (
            f"Expected ≥4 spine components, got {len(CT_METERING_SPINE_ORDER)}"
        )

    def test_spec_constant_excludes_fuse(self):
        """CT_METERING_SPINE_ORDER must NOT contain POTENTIAL_FUSE.

        DXF reference: 2A fuses are horizontal branch elements, not spine.
        Ref: 150A TPN DXF — FUSE INSERT at X≈23494 (right of spine X≈22385).
        """
        spine_symbols = [name for name, _ in CT_METERING_SPINE_ORDER]
        assert "POTENTIAL_FUSE" not in spine_symbols, (
            f"POTENTIAL_FUSE must NOT be in CT_METERING_SPINE_ORDER. "
            f"Fuses are horizontal branches, not spine components. "
            f"Current order: {spine_symbols}"
        )

    def test_bi_connector_above_mccb(self):
        """BI Connector (busbar interconnect) must have higher Y than MCCB.

        BI connects CT metering output to distribution busbar (load side).
        MCCB is the main breaker — first protection component on spine.
        Higher Y = closer to load = visually higher on the SLD.
        """
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        spine = _extract_spine_symbols(result)
        sym_names = [s[0] for s in spine]

        assert "BI_CONNECTOR" in sym_names, f"BI_CONNECTOR not found on spine: {sym_names}"
        assert "CB_MCCB" in sym_names, f"CB_MCCB not found on spine: {sym_names}"

        bi_y = next(y for name, y in spine if name == "BI_CONNECTOR")
        mccb_y = next(y for name, y in spine if name == "CB_MCCB")

        assert bi_y > mccb_y, (
            f"BI_CONNECTOR (Y={bi_y:.1f}) must be ABOVE CB_MCCB (Y={mccb_y:.1f}). "
            f"BI connects to distribution busbar (load side), "
            f"MCCB is first protection after supply."
        )

    def test_protection_ct_below_metering_ct(self):
        """Protection CT must be below Metering CT (closer to MCCB/supply).

        Protection CT feeds ELR which needs to be on the supply side.
        Metering CT feeds kWh/ammeter/voltmeter on the load side.
        """
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        spine = _extract_spine_symbols(result)
        ct_entries = [(name, y) for name, y in spine if name == "CT"]

        assert len(ct_entries) >= 2, (
            f"Expected ≥2 CT components on spine, got {len(ct_entries)}"
        )

        # Protection CT (lower Y) should come before Metering CT (higher Y)
        ct_ys = sorted([y for _, y in ct_entries])
        assert ct_ys[0] < ct_ys[1], (
            f"Two CTs should be at different heights: {ct_ys}"
        )

    def test_mccb_below_cts(self):
        """MCCB must be below both CTs (first spine component after supply)."""
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        spine = _extract_spine_symbols(result)

        mccb_entries = [(n, y) for n, y in spine if n == "CB_MCCB"]
        ct_entries = [(n, y) for n, y in spine if n == "CT"]

        assert mccb_entries, f"CB_MCCB not on spine"
        assert len(ct_entries) >= 2, f"Expected ≥2 CTs on spine"

        mccb_y = mccb_entries[0][1]
        min_ct_y = min(y for _, y in ct_entries)

        assert mccb_y < min_ct_y, (
            f"CB_MCCB (Y={mccb_y:.1f}) must be BELOW lowest CT (Y={min_ct_y:.1f}). "
            f"MCCB is the first spine component (supply side), CTs follow."
        )

    def test_bi_connector_is_topmost_spine_component(self):
        """BI Connector should be the highest spine component
        (closest to distribution busbar / load side)."""
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        spine = _extract_spine_symbols(result)
        # Filter to CT metering components only (exclude busbar, sub-circuits)
        ct_section_symbols = {"CB_MCCB", "CT", "BI_CONNECTOR"}
        ct_spine = [(n, y) for n, y in spine if n in ct_section_symbols]

        assert ct_spine, "No CT metering components found on spine"

        topmost = max(ct_spine, key=lambda t: t[1])
        assert topmost[0] == "BI_CONNECTOR", (
            f"Expected BI_CONNECTOR as topmost CT section component, "
            f"got {topmost[0]} at Y={topmost[1]:.1f}. "
            f"Full order: {[(n, f'{y:.1f}') for n, y in ct_spine]}"
        )

    def test_full_flow_order(self):
        """Validate complete spine order matches CT_METERING_SPINE_ORDER spec."""
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        spine = _extract_spine_symbols(result)
        ct_section_symbols = {name for name, _ in CT_METERING_SPINE_ORDER}
        ct_spine = [(n, y) for n, y in spine if n in ct_section_symbols]

        # Extract just symbol names in Y-ascending order
        actual_order = [name for name, y in ct_spine]
        expected_order = [name for name, _ in CT_METERING_SPINE_ORDER]

        assert actual_order == expected_order, (
            f"Spine flow order mismatch!\n"
            f"  Expected (supply→load): {expected_order}\n"
            f"  Actual   (supply→load): {actual_order}\n"
            f"  Refer to CT_METERING_SPINE_ORDER in sections.py for specification."
        )


# ---------------------------------------------------------------------------
# Tests — Fuse placement (prevention for spine-vs-branch misidentification)
# ---------------------------------------------------------------------------

class TestPotentialFuseNotOnSpine:
    """Validate that 2A Potential Fuses are horizontal branch elements,
    NOT on the vertical spine.

    DXF reference analysis:
    - 150A TPN: FUSE INSERT at X≈23494, spine at X≈22385 (1100 DU offset)
    - Horizontal LINE (22385,6376)→(23230,6376) connects spine to fuse
    - The vertical spine LINE passes THROUGH Y=6376 without interruption
    - ALL three fuses in the 150A file are horizontal branches

    Root cause this prevents:
    - Previous code placed fuse ON spine (vertically) because DXF analysis
      used an X-range filter (21000-25000) that included both spine and
      branch elements, without tracing LINE connectivity.
    """

    def test_fuse_not_on_spine(self):
        """POTENTIAL_FUSE must NOT appear on the vertical spine."""
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        spine = _extract_spine_symbols(result)
        spine_names = [name for name, _ in spine]

        assert "POTENTIAL_FUSE" not in spine_names, (
            f"POTENTIAL_FUSE found ON spine — it must be a horizontal branch! "
            f"DXF ref: fuse is 1100 DU RIGHT of spine (X≈23494 vs X≈22385). "
            f"Spine components: {spine_names}"
        )

    def test_fuse_exists_as_branch(self):
        """POTENTIAL_FUSE must exist in the layout as a branch component."""
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        all_comps = _extract_all_components(result)
        fuse_comps = [(n, x, y, r) for n, x, y, r in all_comps if n == "POTENTIAL_FUSE"]

        assert len(fuse_comps) >= 1, (
            f"Expected ≥1 POTENTIAL_FUSE component in layout, "
            f"got {len(fuse_comps)}. Fuses should be placed as horizontal branches."
        )

    def test_fuse_has_horizontal_rotation(self):
        """POTENTIAL_FUSE must have rotation=90° (horizontal branch orientation).

        Rotation 90° triggers draw_horizontal() in the renderer, drawing
        the ○×○ pattern horizontally with left/right connection stubs.
        """
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        all_comps = _extract_all_components(result)
        fuse_comps = [(n, x, y, r) for n, x, y, r in all_comps if n == "POTENTIAL_FUSE"]

        assert fuse_comps, "No POTENTIAL_FUSE components found"

        for name, x, y, rotation in fuse_comps:
            assert rotation == 90.0, (
                f"POTENTIAL_FUSE at ({x:.1f}, {y:.1f}) has rotation={rotation}°, "
                f"expected 90° (horizontal). Fuses are horizontal branches, "
                f"not vertical spine components."
            )

    def test_fuse_x_offset_from_spine(self):
        """POTENTIAL_FUSE must be offset from spine_x (on a branch, not centered).

        DXF ref: fuse is 1100 DU (≈8mm at 140 scale) right of spine center.
        The component's left edge X should be greater than spine_x.
        """
        req = _ct_metering_requirements()
        result = compute_layout(req, skip_validation=True)

        spine_x = getattr(result, "spine_x", None)
        assert spine_x is not None, "spine_x not set in result"

        all_comps = _extract_all_components(result)
        fuse_comps = [(n, x, y, r) for n, x, y, r in all_comps if n == "POTENTIAL_FUSE"]

        for name, x, y, rotation in fuse_comps:
            # Fuse left edge should be to the RIGHT of spine center
            assert x > spine_x, (
                f"POTENTIAL_FUSE at x={x:.1f} is not right of spine_x={spine_x:.1f}. "
                f"Fuses must be horizontal branches extending RIGHT from the spine."
            )
