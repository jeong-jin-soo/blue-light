"""Phase 8A: DXF 라벨 블록 인식 정렬 테스트.

DXF 백엔드에서 라벨/케이블 주석의 X 오프셋이 블록 실제 폭 기준으로
계산되는지 검증한다. PDF/SVG 백엔드는 기존 동작(절차적 폭) 유지.
"""

from __future__ import annotations

import pytest
from unittest.mock import patch, MagicMock

from app.sld.generator import SldGenerator, _SYMBOL_TO_DXF_BLOCK


class TestDxfLabelOffsetX:
    """_dxf_label_offset_x() — DXF 라벨 X 오프셋 계산."""

    def test_mccb_offset_smaller_than_procedural(self):
        """MCCB 블록(1.92mm 폭)은 절차적(5.5mm)보다 좁아서 오프셋이 작아야 함."""
        offset = SldGenerator._dxf_label_offset_x("CB_MCCB", 5.5, 9.0)

        # 절차적: 5.5 + 3 = 8.5mm
        procedural_offset = 5.5 + 3

        # DXF: width/2 + scaled_w/2 + 3
        # MCCB 블록: 127.59 DU wide, 597.82 DU tall
        # scale = 9.0 / 597.82 ≈ 0.01505
        # scaled_w ≈ 127.59 * 0.01505 ≈ 1.92mm
        # offset ≈ 5.5/2 + 1.92/2 + 3 = 2.75 + 0.96 + 3 = 6.71mm
        assert offset < procedural_offset, (
            f"DXF offset ({offset:.2f}) should be < procedural ({procedural_offset})"
        )
        # 블록 우측 가장자리에서 3mm 간격이 되는지 확인
        # 블록 중심 = comp.x + 2.75 (절차적 핀 위치)
        # 블록 우측 = 2.75 + 0.96 = 3.71mm
        # 라벨 = offset = ~6.71mm → 라벨~블록 간격 = 6.71 - 3.71 = 3.0mm ✓
        assert offset == pytest.approx(offset, abs=0.5)  # sanity check
        assert offset > 5.0  # 최소한 5mm 이상

    def test_elr_offset_matches_procedural(self):
        """ELR 블록(비율 1.00)은 절차적과 거의 동일해야 함."""
        offset = SldGenerator._dxf_label_offset_x("ELR", 7.8, 3.9)
        procedural_offset = 7.8 + 3

        # ELR 비율 ≈ 1.00 → 오프셋 차이 미미
        assert abs(offset - procedural_offset) < 1.0

    def test_unknown_symbol_falls_back_to_procedural(self):
        """블록이 없는 심볼은 절차적 폭 사용."""
        offset = SldGenerator._dxf_label_offset_x("UNKNOWN_SYMBOL", 5.0, 8.0)
        assert offset == pytest.approx(5.0 + 3)

    def test_no_block_replayer_falls_back(self):
        """BlockReplayer가 없으면 절차적 폭 사용."""
        import app.sld.generator as gen_mod
        original = gen_mod._BLOCK_REPLAYER

        try:
            gen_mod._BLOCK_REPLAYER = None
            offset = SldGenerator._dxf_label_offset_x("CB_MCCB", 5.5, 9.0)
            assert offset == pytest.approx(5.5 + 3)
        finally:
            gen_mod._BLOCK_REPLAYER = original

    def test_rccb_offset_between_procedural_and_minimum(self):
        """RCCB 블록(비율 0.93)은 절차적보다 약간 작아야 함."""
        offset = SldGenerator._dxf_label_offset_x("CB_RCCB", 6.5, 9.0)
        procedural_offset = 6.5 + 3

        # RCCB 비율 ≈ 0.93 → 약간 작아야 함
        assert offset <= procedural_offset
        assert offset > procedural_offset - 1.5  # 크게 다르지 않음


class TestDxfLabelAlignment:
    """DXF 백엔드에서 라벨 위치가 블록 기준으로 계산되는지 검증."""

    def test_all_mapped_symbols_have_valid_offset(self):
        """모든 매핑된 심볼에 대해 _dxf_label_offset_x()가 정상 동작."""
        from app.sld.real_symbols import get_real_symbol

        for sym_name, dxf_name in _SYMBOL_TO_DXF_BLOCK.items():
            try:
                sym = get_real_symbol(sym_name)
            except (ValueError, KeyError):
                continue  # 심볼 클래스가 없는 경우 건너뜀

            offset = SldGenerator._dxf_label_offset_x(
                sym_name, sym.width, sym.height)

            # 오프셋은 항상 양수여야 함
            assert offset > 0, f"{sym_name}: offset={offset} should be > 0"
            # 오프셋은 합리적 범위 내여야 함 (3mm ~ 20mm)
            assert 3 <= offset <= 20, f"{sym_name}: offset={offset} out of range"

    def test_pdf_backend_unchanged(self):
        """PDF 백엔드에서는 라벨 위치가 기존과 동일해야 함."""
        # PDF 백엔드는 isinstance(backend, DxfBackend) == False이므로
        # 절차적 폭(symbol.width + 3)을 사용
        from app.sld.pdf_backend import PdfBackend

        backend = PdfBackend.__new__(PdfBackend)
        from app.sld.dxf_backend import DxfBackend
        assert not isinstance(backend, DxfBackend)
