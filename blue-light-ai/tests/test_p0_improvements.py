"""Tests for P0 SLD quality improvements.

Phase 1: Cable Extension SLD
Phase 2: BI Connector Multi-Row
Phase 3: CT Meter ratio label
"""

import pytest

from app.sld.layout import compute_layout, LayoutConfig


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

def _base_requirements(supply_type="three_phase", breaker_rating=63, num_circuits=6):
    """Build a minimal valid requirements dict."""
    circuits = [
        {"name": f"Circuit {i+1}", "breaker": {"type": "MCB", "rating": 20}, "load": "Lighting"}
        for i in range(num_circuits)
    ]
    return {
        "supply_type": supply_type,
        "kva": 0,  # skip kVA validation for layout tests
        "main_breaker": {"type": "MCCB", "rating": breaker_rating, "poles": "TPN"},
        "sub_circuits": circuits,
    }


# ===========================================================================
# Phase 1: Cable Extension SLD
# ===========================================================================

class TestCableExtension:
    """Cable Extension SLD should have no isolator and a distinct supply label."""

    def _cable_ext_req(self, num_circuits=4):
        req = _base_requirements(num_circuits=num_circuits)
        req["is_cable_extension"] = True
        req["supply_source"] = "landlord"
        return req

    def test_cable_extension_no_isolator(self):
        """Cable extension SLD must NOT contain an ISOLATOR component."""
        req = self._cable_ext_req()
        result = compute_layout(req, skip_validation=True)
        isolators = [c for c in result.components if c.symbol_name == "ISOLATOR"]
        assert len(isolators) == 0, f"Expected no ISOLATOR but found {len(isolators)}"

    def test_cable_extension_supply_label(self):
        """Cable extension SLD must show 'FROM POWER SUPPLY ON SITE' label."""
        req = self._cable_ext_req()
        result = compute_layout(req, skip_validation=True)
        labels = [c for c in result.components if c.symbol_name == "LABEL"]
        label_texts = [l.label for l in labels]
        assert any("POWER SUPPLY ON SITE" in t for t in label_texts), (
            f"Expected 'FROM POWER SUPPLY ON SITE' label, got: {label_texts}"
        )

    def test_cable_extension_auto_landlord(self):
        """is_cable_extension=True should auto-set supply_source to landlord."""
        req = _base_requirements()
        req["is_cable_extension"] = True
        # supply_source defaults to sp_powergrid, should be overridden
        result = compute_layout(req, skip_validation=True)
        # Verify it behaves like landlord: no meter board (non-metered)
        # Cable extension with auto-landlord should have the power supply label
        labels = [c for c in result.components if c.symbol_name == "LABEL"]
        label_texts = [l.label for l in labels]
        assert any("POWER SUPPLY ON SITE" in t for t in label_texts), (
            f"Expected auto-landlord cable extension label, got: {label_texts}"
        )

    def test_landlord_still_has_isolator(self):
        """Regular landlord supply with requires_isolator must have ISOLATOR."""
        req = _base_requirements()
        req["supply_source"] = "landlord"
        req["requires_isolator"] = True
        result = compute_layout(req, skip_validation=True)
        isolators = [c for c in result.components if c.symbol_name == "ISOLATOR"]
        assert len(isolators) > 0, "Regular landlord supply should have ISOLATOR"


# ===========================================================================
# Phase 2: BI Connector Multi-Row
# ===========================================================================

class TestBIConnectorMultiRow:
    """Multi-row SLD should use BI_CONNECTOR between busbar rows."""

    def test_bi_connector_multirow(self):
        """31+ circuits (multi-row with max_circuits_per_row=30) must include BI_CONNECTOR."""
        req = _base_requirements(num_circuits=31)
        result = compute_layout(req, skip_validation=True)
        bi_connectors = [c for c in result.components if c.symbol_name == "BI_CONNECTOR"]
        assert len(bi_connectors) >= 1, (
            f"Expected at least 1 BI_CONNECTOR for multi-row, found {len(bi_connectors)}"
        )

    def test_bi_connector_not_single_row(self):
        """26 circuits (single row) must NOT include BI_CONNECTOR."""
        req = _base_requirements(num_circuits=26)
        result = compute_layout(req, skip_validation=True)
        bi_connectors = [c for c in result.components if c.symbol_name == "BI_CONNECTOR"]
        assert len(bi_connectors) == 0, (
            f"Expected no BI_CONNECTOR for single-row, found {len(bi_connectors)}"
        )

    def test_bi_connector_symbols_used(self):
        """Multi-row layout should register BI_CONNECTOR in symbols_used."""
        req = _base_requirements(num_circuits=31)
        result = compute_layout(req, skip_validation=True)
        assert "BI_CONNECTOR" in result.symbols_used, (
            f"Expected BI_CONNECTOR in symbols_used, got: {result.symbols_used}"
        )

    def test_bi_connector_position_between_busbars(self):
        """BI_CONNECTOR Y position should be between the two busbar rows."""
        req = _base_requirements(num_circuits=31)
        result = compute_layout(req, skip_validation=True)
        bi_connectors = [c for c in result.components if c.symbol_name == "BI_CONNECTOR"]
        assert len(bi_connectors) >= 1

        # Verify BI connector is between busbar Y values
        busbar_ys = result.busbar_y_per_row
        assert len(busbar_ys) >= 2
        bi = bi_connectors[0]
        bi_center_y = bi.y + 5  # half of bi_h=10
        assert busbar_ys[0] < bi_center_y < busbar_ys[1], (
            f"BI_CONNECTOR center Y ({bi_center_y}) should be between "
            f"busbar rows ({busbar_ys[0]}, {busbar_ys[1]})"
        )


# ===========================================================================
# Phase 3: CT Meter Ratio Label
# ===========================================================================

class TestCTMeterLabel:
    """CT meter should display ratio when provided."""

    def _ct_req(self, ct_ratio=None):
        req = _base_requirements(breaker_rating=200)
        req["metering"] = "ct_meter"
        if ct_ratio:
            req["ct"] = {"ratio": ct_ratio}
        return req

    def test_ct_ratio_label(self):
        """CT component should include ratio in label when ct.ratio is provided.

        With correct flow order (Protection CT → Metering CT), the metering CT
        that carries the ratio label is the SECOND CT component (index 1).
        """
        req = self._ct_req(ct_ratio="200/5A")
        result = compute_layout(req, skip_validation=True)
        ct_components = [c for c in result.components if c.symbol_name == "CT"]
        assert len(ct_components) >= 2, "Expected ≥2 CT components (protection + metering)"
        metering_ct = ct_components[1]
        assert "200/5A" in metering_ct.label, f"Expected '200/5A' in metering CT label, got: '{metering_ct.label}'"

    def test_ct_default_label(self):
        """CT component should use default 'CT BY SP' label when ratio not specified.

        With correct flow order, metering CT is at index 1 (after protection CT).
        """
        req = self._ct_req()
        result = compute_layout(req, skip_validation=True)
        ct_components = [c for c in result.components if c.symbol_name == "CT"]
        assert len(ct_components) >= 2, "Expected ≥2 CT components (protection + metering)"
        metering_ct = ct_components[1]
        assert "CT BY SP" in metering_ct.label, f"Expected 'CT BY SP' in metering CT label, got: '{metering_ct.label}'"

    def test_ct_ratio_string_input(self):
        """CT ratio should work when ct is passed as a plain string.

        With correct flow order, metering CT is at index 1 (after protection CT).
        """
        req = _base_requirements(breaker_rating=200)
        req["metering"] = "ct_meter"
        req["ct"] = "300/5A"
        result = compute_layout(req, skip_validation=True)
        ct_components = [c for c in result.components if c.symbol_name == "CT"]
        assert len(ct_components) >= 2, "Expected ≥2 CT components (protection + metering)"
        metering_ct = ct_components[1]
        assert "300/5A" in metering_ct.label, f"Expected '300/5A' in metering CT label, got: '{metering_ct.label}'"
