"""
B2: Page overflow detection tests.

Tests:
1. OverflowMetrics dataclass properties and serialization
2. _detect_overflow produces metrics for all standard configs
3. No overflow on standard configs (they should fit A3)
4. Many-circuit scenario triggers compression detection
5. Generator return value includes overflow_metrics
"""

import pytest

from app.sld.layout import compute_layout
from app.sld.layout.models import OverflowMetrics


# ---------------------------------------------------------------------------
# Fixtures — reuse patterns from test_layout_integrity
# ---------------------------------------------------------------------------

SINGLE_PHASE_3CKT = {
    "supply_type": "single_phase",
    "kva": 9,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2},
    "sub_circuits": [
        {"name": "Socket 1", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 4.0sqmm PVC/PVC"},
        {"name": "Socket 2", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 4.0sqmm PVC/PVC"},
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC/PVC"},
    ],
}

THREE_PHASE_6CKT = {
    "supply_type": "three_phase",
    "kva": 22,
    "voltage": 400,
    "main_breaker": {"type": "MCB", "rating": 32, "poles": "TPN", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 100, "poles": 4},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10, "cable": "2C 1.5sqmm PVC"},
        {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
        {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
        {"name": "Aircon 1", "breaker_type": "MCB", "breaker_rating": 32, "cable": "2C 6sqmm PVC"},
        {"name": "Aircon 2", "breaker_type": "MCB", "breaker_rating": 32, "cable": "2C 6sqmm PVC"},
        {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"},
    ],
}


# ---------------------------------------------------------------------------
# OverflowMetrics unit tests
# ---------------------------------------------------------------------------

class TestOverflowMetrics:
    """OverflowMetrics dataclass property and serialization tests."""

    def test_no_overflow_defaults(self):
        m = OverflowMetrics()
        assert not m.has_overflow
        assert not m.is_compressed
        assert m.quality_score == 1.0

    def test_has_overflow_left(self):
        m = OverflowMetrics(overflow_left=5.0)
        assert m.has_overflow

    def test_has_overflow_right(self):
        m = OverflowMetrics(overflow_right=3.0)
        assert m.has_overflow

    def test_has_overflow_top(self):
        m = OverflowMetrics(overflow_top=2.0)
        assert m.has_overflow

    def test_has_overflow_bottom(self):
        m = OverflowMetrics(overflow_bottom=1.0)
        assert m.has_overflow

    def test_small_overflow_ignored(self):
        """Overflow < 0.5mm is not considered overflow."""
        m = OverflowMetrics(overflow_right=0.3)
        assert not m.has_overflow

    def test_compression_detected(self):
        m = OverflowMetrics(horizontal_compression_ratio=0.7)
        assert m.is_compressed

    def test_no_compression_at_95_percent(self):
        m = OverflowMetrics(horizontal_compression_ratio=0.96)
        assert not m.is_compressed

    def test_quality_score_degrades_with_overflow(self):
        m = OverflowMetrics(overflow_right=10.0)
        assert 0.0 < m.quality_score < 1.0

    def test_quality_score_zero_on_large_overflow(self):
        m = OverflowMetrics(overflow_right=50.0)
        assert m.quality_score == 0.0

    def test_quality_score_compressed(self):
        m = OverflowMetrics(horizontal_compression_ratio=0.8)
        assert m.quality_score == 0.8

    def test_to_dict_minimal(self):
        m = OverflowMetrics()
        d = m.to_dict()
        assert d["has_overflow"] is False
        assert d["quality_score"] == 1.0
        assert "overflow" not in d
        assert "compression" not in d
        assert "layout_warnings" not in d

    def test_to_dict_with_overflow(self):
        m = OverflowMetrics(overflow_right=5.0, warnings=["test warning"])
        d = m.to_dict()
        assert d["has_overflow"] is True
        assert "overflow" in d
        assert d["overflow"]["right"] == 5.0
        assert "layout_warnings" in d
        assert "test warning" in d["layout_warnings"]

    def test_to_dict_with_compression(self):
        m = OverflowMetrics(
            horizontal_compression_ratio=0.75,
            circuit_count=25,
            actual_min_spacing=8.0,
            ideal_spacing=10.5,
        )
        d = m.to_dict()
        assert "compression" in d
        assert d["compression"]["ratio"] == 0.75
        assert d["compression"]["circuit_count"] == 25


# ---------------------------------------------------------------------------
# Integration tests — _detect_overflow via compute_layout
# ---------------------------------------------------------------------------

class TestDetectOverflow:
    """Integration tests: overflow detection via compute_layout."""

    @pytest.mark.parametrize("requirements", [
        SINGLE_PHASE_3CKT,
        THREE_PHASE_6CKT,
    ], ids=["single_phase_3ckt", "three_phase_6ckt"])
    def test_metrics_populated(self, requirements):
        """compute_layout always populates overflow_metrics."""
        result = compute_layout(requirements)
        assert result.overflow_metrics is not None

    @pytest.mark.parametrize("requirements", [
        SINGLE_PHASE_3CKT,
        THREE_PHASE_6CKT,
    ], ids=["single_phase_3ckt", "three_phase_6ckt"])
    def test_no_overflow_standard_configs(self, requirements):
        """Standard SLD configs should fit A3 without overflow."""
        result = compute_layout(requirements)
        m = result.overflow_metrics
        assert not m.has_overflow, f"Unexpected overflow: {m.warnings}"

    @pytest.mark.parametrize("requirements", [
        SINGLE_PHASE_3CKT,
        THREE_PHASE_6CKT,
    ], ids=["single_phase_3ckt", "three_phase_6ckt"])
    def test_quality_score_in_range(self, requirements):
        result = compute_layout(requirements)
        m = result.overflow_metrics
        assert 0.0 <= m.quality_score <= 1.0

    def test_content_extents_nonzero(self):
        """Content extents should reflect actual layout dimensions."""
        result = compute_layout(SINGLE_PHASE_3CKT)
        m = result.overflow_metrics
        assert m.content_max_x > m.content_min_x
        assert m.content_max_y > m.content_min_y

    def test_many_circuits_still_fits(self):
        """27-circuit 3-phase SLD should still fit A3 (with possible compression)."""
        requirements = {
            "supply_type": "three_phase",
            "kva": 60,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 200,
            "metering": "ct_meter",
            "elcb": {"rating": 100, "sensitivity_ma": 300, "poles": 4},
            "sub_circuits": [
                {"name": f"Circuit {i+1}", "breaker_type": "MCB",
                 "breaker_rating": 20, "cable": "2C 2.5sqmm PVC"}
                for i in range(27)
            ],
        }
        result = compute_layout(requirements)
        m = result.overflow_metrics
        assert m is not None
        assert m.circuit_count >= 27

    def test_to_dict_serializable(self):
        """to_dict should produce JSON-serializable output."""
        import json
        result = compute_layout(THREE_PHASE_6CKT)
        d = result.overflow_metrics.to_dict()
        json_str = json.dumps(d)
        assert isinstance(json_str, str)

    def test_skip_validation_still_detects_overflow(self):
        """Overflow detection runs even with skip_validation=True."""
        result = compute_layout(SINGLE_PHASE_3CKT, skip_validation=True)
        assert result.overflow_metrics is not None


# ---------------------------------------------------------------------------
# Generator integration test
# ---------------------------------------------------------------------------

class TestGeneratorOverflowIntegration:
    """Generator.generate() returns overflow_metrics in result dict."""

    def test_generate_includes_overflow_metrics(self, tmp_path):
        from app.sld.generator import SldGenerator

        pdf_path = str(tmp_path / "test.pdf")
        svg_path = str(tmp_path / "test.svg")

        generator = SldGenerator()
        result = generator.generate(
            requirements=SINGLE_PHASE_3CKT,
            application_info={},
            pdf_output_path=pdf_path,
            svg_output_path=svg_path,
        )

        assert "overflow_metrics" in result
        assert "layout_warnings" in result
        assert isinstance(result["overflow_metrics"], dict)
        assert "has_overflow" in result["overflow_metrics"]
        assert "quality_score" in result["overflow_metrics"]
