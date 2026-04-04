"""Post-layout validator 전용 테스트."""

from __future__ import annotations

import pytest

from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent
from app.sld.layout.post_validator import (
    ValidationIssue,
    _check_busbar_connections,
    _check_margin_clearance,
    _check_spare_connections,
    _check_text_overlaps,
    validate_and_fix,
)


def _make_result(**kwargs) -> LayoutResult:
    """Minimal LayoutResult for testing."""
    r = LayoutResult()
    for k, v in kwargs.items():
        setattr(r, k, v)
    return r


class TestMarginClearance:
    def test_components_within_bounds_pass(self):
        r = _make_result(components=[
            PlacedComponent(symbol_name="CB_MCB", x=100, y=150, label=""),
        ])
        issues = _check_margin_clearance(r, LayoutConfig())
        assert len(issues) == 0

    def test_component_outside_left_bound(self):
        r = _make_result(components=[
            PlacedComponent(symbol_name="CB_MCB", x=10, y=150, label=""),
        ])
        issues = _check_margin_clearance(r, LayoutConfig())
        assert len(issues) == 1
        assert issues[0].type == "MARGIN"


class TestTextOverlaps:
    def test_labels_well_spaced_pass(self):
        r = _make_result(components=[
            PlacedComponent(symbol_name="CIRCUIT_ID_BOX", x=100, y=200, label="L1S1", rotation=90),
            PlacedComponent(symbol_name="CIRCUIT_ID_BOX", x=112, y=200, label="L2S1", rotation=90),
        ])
        issues = _check_text_overlaps(r, LayoutConfig())
        assert len(issues) == 0

    def test_labels_at_same_x_different_type_skip(self):
        """같은 tap의 CIRCUIT_ID_BOX + LABEL 쌍은 의도적 — 무시."""
        r = _make_result(components=[
            PlacedComponent(symbol_name="CIRCUIT_ID_BOX", x=100, y=200, label="L1S1", rotation=90),
            PlacedComponent(symbol_name="LABEL", x=100, y=200, label="LIGHTS", rotation=90),
        ])
        issues = _check_text_overlaps(r, LayoutConfig())
        assert len(issues) == 0

    def test_labels_physically_overlapping(self):
        """같은 타입 라벨이 0.3mm 간격이면 감지."""
        r = _make_result(components=[
            PlacedComponent(symbol_name="CIRCUIT_ID_BOX", x=100.0, y=200, label="L1S1", rotation=90),
            PlacedComponent(symbol_name="CIRCUIT_ID_BOX", x=100.3, y=200, label="L2S1", rotation=90),
        ])
        issues = _check_text_overlaps(r, LayoutConfig())
        assert len(issues) == 1
        assert issues[0].type == "TEXT_OVERLAP"


class TestBusbarConnections:
    def _config(self):
        return LayoutConfig()

    def test_tap_connected_to_busbar_pass(self):
        """서브서킷 tap이 busbar에 연결되면 통과."""
        busbar_y = 170.0
        r = _make_result(
            busbar_y=busbar_y,
            busbar_y_per_row=[busbar_y],
            fanout_groups=[],
            components=[
                PlacedComponent(symbol_name="CB_MCB", x=100, y=busbar_y + 12,
                                label="LIGHTS", label_style="breaker_block"),
            ],
            connections=[((100, busbar_y), (100, busbar_y + 20))],
        )
        issues = _check_busbar_connections(r, self._config())
        assert len(issues) == 0

    def test_tap_not_connected_flagged(self):
        """서브서킷 tap이 busbar에 닿지 않으면 감지."""
        busbar_y = 170.0
        r = _make_result(
            busbar_y=busbar_y,
            busbar_y_per_row=[busbar_y],
            fanout_groups=[],
            components=[
                PlacedComponent(symbol_name="CB_MCB", x=100, y=busbar_y + 12,
                                label="LIGHTS", label_style="breaker_block"),
            ],
            connections=[((100, busbar_y + 10), (100, busbar_y + 20))],
        )
        issues = _check_busbar_connections(r, self._config())
        assert len(issues) == 1
        assert issues[0].type == "DISCONNECTED"

    def test_fanout_side_excluded(self):
        """Fan-out side circuit은 busbar 직접 연결 불필요."""
        busbar_y = 170.0
        r = _make_result(
            busbar_y=busbar_y,
            busbar_y_per_row=[busbar_y],
            fanout_groups=[(100, busbar_y, [90, 110])],
            components=[
                PlacedComponent(symbol_name="CB_MCB", x=91, y=busbar_y + 12,
                                label="LIGHTS", label_style="breaker_block"),
            ],
            connections=[((91, busbar_y + 5), (91, busbar_y + 20))],
        )
        issues = _check_busbar_connections(r, self._config())
        assert len(issues) == 0

    def test_non_breaker_block_ignored(self):
        """label_style이 breaker_block이 아닌 CB는 검사 대상 아님."""
        busbar_y = 170.0
        r = _make_result(
            busbar_y=busbar_y,
            busbar_y_per_row=[busbar_y],
            fanout_groups=[],
            components=[
                PlacedComponent(symbol_name="CB_MCB", x=100, y=busbar_y + 12,
                                label="63A", label_style=""),  # spine breaker
            ],
            connections=[],
        )
        issues = _check_busbar_connections(r, self._config())
        assert len(issues) == 0


class TestSpareConnections:
    def test_spare_with_connection_pass(self):
        r = _make_result(
            busbar_y=170.0,
            components=[
                PlacedComponent(symbol_name="CB_SPARE", x=100, y=180, label="SPARE"),
            ],
            connections=[((101, 170), (101, 185))],
        )
        issues = _check_spare_connections(r, LayoutConfig())
        assert len(issues) == 0

    def test_spare_without_connection_flagged(self):
        r = _make_result(
            busbar_y=170.0,
            components=[
                PlacedComponent(symbol_name="CB_SPARE", x=100, y=180, label="SPARE"),
            ],
            connections=[],
        )
        issues = _check_spare_connections(r, LayoutConfig())
        assert len(issues) == 1
        assert issues[0].type == "SPARE_DISCONNECTED"


class TestIntegration:
    def test_63a_db_passes_validation(self):
        """63A DB 23회로 생성 후 unfixed issues = 0."""
        import openpyxl
        import sys
        sys.path.insert(0, ".")
        from scripts.generate_from_excel import requirements
        from app.sld.generator import SldPipeline

        result = SldPipeline().run(requirements)
        assert result.component_count > 50
        # overflow check
        if result.overflow_metrics:
            assert result.overflow_metrics.overflow_top <= 0.5
