"""font_util 폰트 실측 유틸리티 테스트."""

import pytest

from app.sld.layout.font_util import (
    measure_mtext_size,
    measure_mtext_width,
    measure_text_width,
)


class TestMeasureTextWidth:
    def test_empty_string(self):
        assert measure_text_width("", 2.8) == 0.0

    def test_proportional_width(self):
        """넓은 문자(W)가 좁은 문자(I)보다 넓어야 한다."""
        w_wide = measure_text_width("WWWW", 2.8)
        w_narrow = measure_text_width("IIII", 2.8)
        assert w_wide > w_narrow

    def test_positive_for_text(self):
        assert measure_text_width("63A TPN MCB", 2.8) > 0

    def test_height_scales_width(self):
        """cap_height가 클수록 텍스트 폭도 커야 한다."""
        w_small = measure_text_width("TEST", 2.0)
        w_large = measure_text_width("TEST", 4.0)
        assert w_large > w_small


class TestMeasureMtextWidth:
    def test_multiline(self):
        """멀티라인 MTEXT의 최대 줄 폭을 반환."""
        w = measure_mtext_width("SHORT\\PVERY LONG LINE TEXT", 2.8)
        w_long = measure_text_width("VERY LONG LINE TEXT", 2.8)
        assert w == pytest.approx(w_long)

    def test_single_line(self):
        w1 = measure_mtext_width("HELLO", 2.8)
        w2 = measure_text_width("HELLO", 2.8)
        assert w1 == pytest.approx(w2)


class TestMeasureMtextSize:
    def test_single_line_size(self):
        w, h = measure_mtext_size("TEST", 2.8)
        assert w > 0
        assert h == pytest.approx(2.8)

    def test_two_line_size(self):
        w, h = measure_mtext_size("LINE1\\PLINE2", 2.8, line_gap=0.5)
        assert h == pytest.approx(2.8 * 2 + 0.5)

    def test_empty(self):
        w, h = measure_mtext_size("", 2.8)
        assert w == 0.0
        # Empty string still has 1 "line" → height = cap_height
        assert h == pytest.approx(2.8)
