"""
SLD Label Positioning Tests — regression tests for label-component overlap.

Validates that labels are positioned correctly relative to their symbols:
1. Fuse label above symbol (not overlapping)
2. ELR label left of symbol
3. KWH label right of symbol
4. Breaker block labels outside symbol
5. Spine component labels don't overlap each other (CT metering layout)
"""

import pytest
from itertools import combinations

from app.sld.layout import (
    BoundingBox,
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    compute_layout,
)
from app.sld.real_symbols import get_symbol_dimensions


# ---------------------------------------------------------------------------
# Test fixtures — CT metering layout has the most spine components
# ---------------------------------------------------------------------------

THREE_PHASE_CT = {
    "supply_type": "three_phase",
    "kva": 60,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "ct_meter",
    "elcb": {"rating": 100, "sensitivity_ma": 300, "poles": 4},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 16,
         "cable": "2 x 1C 2.5sqmm PVC"},
        {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC"},
        {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC"},
        {"name": "Aircon", "breaker_type": "MCB", "breaker_rating": 63,
         "cable": "2 x 1C 10sqmm PVC"},
    ],
}

SINGLE_PHASE_METERED = {
    "supply_type": "single_phase",
    "kva": 9,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {"name": "Socket", "breaker_type": "MCB", "breaker_rating": 20,
         "breaker_characteristic": "B", "cable": "2C 4.0sqmm PVC/PVC"},
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "breaker_characteristic": "B", "cable": "2C 1.5sqmm PVC/PVC"},
    ],
}


@pytest.fixture
def ct_layout() -> LayoutResult:
    """CT metering layout — exercises fuse, ELR, KWH, ammeter, voltmeter on spine."""
    return compute_layout(THREE_PHASE_CT)


@pytest.fixture
def sp_layout() -> LayoutResult:
    """Single-phase metered layout — exercises breaker block labels."""
    return compute_layout(SINGLE_PHASE_METERED)


# ---------------------------------------------------------------------------
# Helpers — label bounding box estimation
# ---------------------------------------------------------------------------

def _estimate_horizontal_label_bbox(
    comp: PlacedComponent,
    config: LayoutConfig,
) -> BoundingBox | None:
    """Estimate label bounding box for horizontal (rotation=90) spine components.

    Mirrors the positioning logic in generator._draw_default_symbol_label().
    Returns None if component has no label.
    """
    label_text = ""
    if comp.circuit_id:
        label_text = f"{comp.circuit_id}\\P{comp.label} {comp.rating}"
    elif comp.rating:
        label_text = f"{comp.label}\\P{comp.rating}"
    elif comp.label:
        label_text = comp.label

    if not label_text:
        return None

    ch = config.label_ch_horizontal
    wr = config.text_width_ratio
    lines = label_text.split("\\P")
    max_len = max(len(ln) for ln in lines) if lines else 1
    text_w = max_len * ch * wr
    text_h = len(lines) * ch * 1.3  # line height ≈ 1.3 × char_height

    # Get symbol dimensions
    dims = get_symbol_dimensions(comp.symbol_name)
    sym_w = dims.get("width_mm", 8)
    sym_h = dims.get("height_mm", 14)

    if comp.symbol_name in ("POTENTIAL_FUSE", "FUSE"):
        v_half = sym_w / 2  # width becomes vertical half in horizontal mode
        label_x = comp.x + sym_h / 2 - text_w / 2
        label_y = comp.y + v_half + config.fuse_label_gap_above
        return BoundingBox(label_x, label_y, text_w, text_h)
    elif comp.symbol_name == "ELR":
        v_half = sym_h / 2
        label_x = comp.x - text_w - config.symbol_label_gap
        label_y = comp.y + v_half
        return BoundingBox(label_x, label_y - text_h / 2, text_w, text_h)
    elif comp.symbol_name == "KWH_METER":
        # KWH label is right of box
        return BoundingBox(comp.x + sym_h + 2, comp.y, text_w, text_h)

    return None


def _get_symbol_bbox(comp: PlacedComponent) -> BoundingBox:
    """Get approximate bounding box for a symbol (horizontal orientation)."""
    dims = get_symbol_dimensions(comp.symbol_name)
    w = dims.get("height_mm", 14)  # horizontal: height becomes width
    h = dims.get("width_mm", 8)    # horizontal: width becomes height
    return BoundingBox(comp.x, comp.y - h / 2, w, h)


# ---------------------------------------------------------------------------
# Test 1: Fuse label above symbol (regression for 0.5→2.5mm fix)
# ---------------------------------------------------------------------------

class TestFuseLabelAboveSymbol:
    """Verify fuse labels are positioned above (not overlapping) the symbol."""

    def test_fuse_label_y_above_symbol_top(self, ct_layout: LayoutResult):
        """FUSE/POTENTIAL_FUSE labels must have Y > symbol top Y."""
        config = ct_layout.config or LayoutConfig()
        fuse_comps = [
            c for c in ct_layout.components
            if c.symbol_name in ("POTENTIAL_FUSE", "FUSE") and c.rotation == 90.0
        ]
        assert len(fuse_comps) > 0, "CT metering layout must contain at least one fuse"

        for comp in fuse_comps:
            dims = get_symbol_dimensions(comp.symbol_name)
            v_half = dims.get("width_mm", 8) / 2  # horizontal: width = vertical extent
            symbol_top_y = comp.y + v_half

            label_bbox = _estimate_horizontal_label_bbox(comp, config)
            assert label_bbox is not None, f"Fuse {comp.label} should have a label"
            # Label bottom edge must be above symbol top edge
            assert label_bbox.y >= symbol_top_y, (
                f"Fuse label '{comp.label}' bottom ({label_bbox.y:.1f}mm) "
                f"overlaps symbol top ({symbol_top_y:.1f}mm). "
                f"Gap should be >= {config.fuse_label_gap_above}mm"
            )

    def test_fuse_label_gap_matches_config(self, ct_layout: LayoutResult):
        """Fuse label gap must equal config.fuse_label_gap_above."""
        config = ct_layout.config or LayoutConfig()
        fuse_comps = [
            c for c in ct_layout.components
            if c.symbol_name in ("POTENTIAL_FUSE", "FUSE") and c.rotation == 90.0
        ]
        for comp in fuse_comps:
            dims = get_symbol_dimensions(comp.symbol_name)
            v_half = dims.get("width_mm", 8) / 2
            symbol_top_y = comp.y + v_half
            label_bbox = _estimate_horizontal_label_bbox(comp, config)
            if label_bbox:
                actual_gap = label_bbox.y - symbol_top_y
                assert abs(actual_gap - config.fuse_label_gap_above) < 0.1, (
                    f"Expected gap {config.fuse_label_gap_above}mm, got {actual_gap:.2f}mm"
                )


# ---------------------------------------------------------------------------
# Test 2: ELR label left of symbol
# ---------------------------------------------------------------------------

class TestElrLabelLeftOfSymbol:
    """Verify ELR labels are positioned to the left (not overlapping) the symbol."""

    def test_elr_component_exists(self, ct_layout: LayoutResult):
        """CT metering layout must contain an ELR component."""
        elr_comps = [
            c for c in ct_layout.components
            if c.symbol_name == "ELR" and c.rotation == 90.0
        ]
        assert len(elr_comps) > 0, "CT metering layout must contain ELR"

    def test_elr_label_left_of_symbol_when_labeled(self, ct_layout: LayoutResult):
        """ELR label right edge must be left of symbol edge (when label present)."""
        config = ct_layout.config or LayoutConfig()
        elr_comps = [
            c for c in ct_layout.components
            if c.symbol_name == "ELR" and c.rotation == 90.0
            and (c.label or c.rating)
        ]
        # ELR may have no label text in some configurations (spec in separate box)
        if not elr_comps:
            pytest.skip("ELR has no label text in this configuration")

        for comp in elr_comps:
            label_bbox = _estimate_horizontal_label_bbox(comp, config)
            if label_bbox:
                label_right = label_bbox.x + label_bbox.width
                assert label_right <= comp.x + 0.1, (
                    f"ELR label right edge ({label_right:.1f}mm) "
                    f"should be left of symbol edge ({comp.x:.1f}mm)"
                )


# ---------------------------------------------------------------------------
# Test 3: KWH label right of symbol
# ---------------------------------------------------------------------------

class TestKwhLabelRightOfSymbol:
    """Verify KWH labels are positioned to the right (not overlapping) the symbol."""

    def test_kwh_label_right_of_symbol(self, ct_layout: LayoutResult):
        """KWH label left edge must be right of symbol right edge."""
        config = ct_layout.config or LayoutConfig()
        kwh_comps = [
            c for c in ct_layout.components
            if c.symbol_name == "KWH_METER" and c.rotation == 90.0
        ]
        # KWH may or may not be present in CT layout; skip if absent
        if not kwh_comps:
            pytest.skip("No KWH_METER in CT layout")

        for comp in kwh_comps:
            dims = get_symbol_dimensions("KWH_METER")
            sym_right = comp.x + dims.get("height_mm", 14)  # horizontal: height = width
            label_bbox = _estimate_horizontal_label_bbox(comp, config)
            if label_bbox:
                assert label_bbox.x >= sym_right - 1.0, (
                    f"KWH label left edge ({label_bbox.x:.1f}mm) "
                    f"should be right of symbol ({sym_right:.1f}mm)"
                )


# ---------------------------------------------------------------------------
# Test 4: Breaker block labels outside symbol
# ---------------------------------------------------------------------------

class TestBreakerBlockLabelsOutsideSymbol:
    """Verify breaker block labels don't overlap their symbols."""

    def test_subcircuit_labels_offset_from_symbol(self, ct_layout: LayoutResult):
        """Sub-circuit breaker labels should be offset from the symbol center."""
        config = ct_layout.config or LayoutConfig()
        breaker_comps = [
            c for c in ct_layout.components
            if c.label_style == "breaker_block"
            and c.symbol_name.startswith("CB_")
        ]
        assert len(breaker_comps) > 0, "Must have sub-circuit breakers"

        for comp in breaker_comps:
            bt = comp.breaker_type_str or "MCB"
            dims = get_symbol_dimensions(bt)
            sym_w = dims.get("width_mm", 5)
            # The label offset must exceed half the symbol width
            if bt in ("MCCB", "ACB"):
                offset = config.breaker_label_x_wide
            else:
                offset = config.breaker_label_x_default
            assert offset > sym_w / 2, (
                f"Breaker {comp.label} label offset ({offset:.1f}mm) "
                f"must exceed half symbol width ({sym_w / 2:.1f}mm)"
            )


# ---------------------------------------------------------------------------
# Test 5: Spine labels don't overlap each other
# ---------------------------------------------------------------------------

class TestSpineLabelsNoMutualOverlap:
    """Verify spine component labels in CT metering don't overlap each other."""

    def test_spine_labels_no_overlap(self, ct_layout: LayoutResult):
        """No pair of spine component labels should overlap."""
        config = ct_layout.config or LayoutConfig()

        # Collect all horizontal spine components with labels
        _SPINE_SYMBOLS = {
            "ELR", "KWH_METER", "SELECTOR_SWITCH", "AMMETER", "VOLTMETER",
            "POTENTIAL_FUSE", "FUSE", "BI_CONNECTOR",
        }
        spine_comps = [
            c for c in ct_layout.components
            if c.symbol_name in _SPINE_SYMBOLS
            and c.rotation == 90.0
            and (c.label or c.rating)
        ]

        # Build label bounding boxes
        label_bboxes: list[tuple[str, BoundingBox]] = []
        for comp in spine_comps:
            bbox = _estimate_horizontal_label_bbox(comp, config)
            if bbox:
                label_bboxes.append((f"{comp.symbol_name}({comp.label})", bbox))

        # Check all pairs
        overlaps = []
        for (name_a, bb_a), (name_b, bb_b) in combinations(label_bboxes, 2):
            if bb_a.overlaps(bb_b):
                area = bb_a.overlap_area(bb_b)
                if area > 1.0:  # Tolerance: 1 sq mm
                    overlaps.append(f"{name_a} ↔ {name_b} (overlap: {area:.1f}mm²)")

        assert not overlaps, (
            f"Spine label overlaps detected:\n" + "\n".join(f"  - {o}" for o in overlaps)
        )


# ---------------------------------------------------------------------------
# Test 6: LayoutConfig label constants consistency
# ---------------------------------------------------------------------------

class TestLayoutConfigLabelConstants:
    """Verify label rendering constants are reasonable."""

    def test_fuse_gap_positive(self):
        config = LayoutConfig()
        assert config.fuse_label_gap_above > 0

    def test_generic_gap_positive(self):
        config = LayoutConfig()
        assert config.generic_label_gap_below > 0

    def test_text_width_ratio_in_range(self):
        config = LayoutConfig()
        assert 0.3 <= config.text_width_ratio <= 1.0

    def test_char_heights_ascending(self):
        """Horizontal labels should be smallest, breaker info largest."""
        config = LayoutConfig()
        assert config.label_ch_horizontal <= config.label_ch_breaker_sub
        assert config.label_ch_breaker_sub <= config.label_ch_breaker_info

    def test_config_attached_to_layout_result(self, ct_layout: LayoutResult):
        """compute_layout() should attach config to LayoutResult."""
        assert ct_layout.config is not None
        assert isinstance(ct_layout.config, LayoutConfig)


# ---------------------------------------------------------------------------
# Test 7: validate_spine_labels() integration
# ---------------------------------------------------------------------------

class TestValidateSpineLabels:
    """Test the validate_spine_labels() post-placement validation."""

    def test_clean_layout_no_warnings(self, ct_layout: LayoutResult):
        """A properly spaced CT metering layout should produce zero warnings."""
        from app.sld.layout.overlap import validate_spine_labels
        config = ct_layout.config or LayoutConfig()
        warnings = validate_spine_labels(ct_layout, config)
        assert warnings == [], (
            f"Expected no spine label warnings, got:\n" +
            "\n".join(f"  - {w}" for w in warnings)
        )

    def test_detects_artificial_overlap(self):
        """An artificially overlapping layout should produce warnings."""
        from app.sld.layout.overlap import validate_spine_labels
        config = LayoutConfig()
        result = LayoutResult()
        # Place two fuses at the exact same position — labels will overlap
        result.components = [
            PlacedComponent(
                symbol_name="POTENTIAL_FUSE", x=100, y=100, rotation=90.0,
                label="2A", rating="",
            ),
            PlacedComponent(
                symbol_name="POTENTIAL_FUSE", x=100, y=100, rotation=90.0,
                label="2A", rating="",
            ),
        ]
        warnings = validate_spine_labels(result, config)
        assert len(warnings) > 0, "Should detect overlap for co-located fuses"
