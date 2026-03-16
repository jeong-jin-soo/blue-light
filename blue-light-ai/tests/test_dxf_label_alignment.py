"""Phase 8A → A1: DXF 라벨 블록 인식 정렬 테스트.

DXF 백엔드에서 라벨/케이블 주석의 X 오프셋이 블록 실제 폭 기준으로
계산되는지 검증한다. Symbol.label_offset_x(backend) 통합 인터페이스 사용.
"""

from __future__ import annotations

import pytest

from app.sld.symbol import create_symbol, SYMBOL_TO_DXF_BLOCK
from app.sld.block_replayer import BlockReplayer


@pytest.fixture(scope="module")
def replayer():
    return BlockReplayer.load()


class TestSymbolLabelOffsetX:
    """Symbol.label_offset_x() — DXF vs PDF 라벨 X 오프셋 계산."""

    def test_mccb_dxf_offset_smaller_than_procedural(self, replayer):
        """MCCB 블록(1.92mm 폭)은 절차적(5.5mm)보다 좁아서 DXF 오프셋이 작아야 함."""
        from app.sld.dxf_backend import DxfBackend
        sym = create_symbol("CB_MCCB", replayer)
        backend = DxfBackend.__new__(DxfBackend)
        offset = sym.label_offset_x(backend)
        procedural_offset = sym.width + 3
        assert offset < procedural_offset
        assert offset > 5.0

    def test_pdf_offset_uses_procedural_width(self, replayer):
        """PDF 백엔드에서는 절차적 폭(width + 3) 사용."""
        from app.sld.pdf_backend import PdfBackend
        sym = create_symbol("CB_MCCB", replayer)
        backend = PdfBackend.__new__(PdfBackend)
        offset = sym.label_offset_x(backend)
        assert offset == pytest.approx(sym.width + 3)

    def test_unknown_symbol_returns_none(self):
        """블록이 없는 심볼은 create_symbol이 None 반환."""
        sym = create_symbol("UNKNOWN_SYMBOL", None)
        assert sym is None

    def test_procedural_symbol_offset(self):
        """BlockReplayer 없는 ProceduralSymbol은 width + 3."""
        from app.sld.pdf_backend import PdfBackend
        sym = create_symbol("CB_MCCB", None)
        backend = PdfBackend.__new__(PdfBackend)
        assert sym.label_offset_x(backend) == pytest.approx(sym.width + 3)

    def test_rccb_offset_between_procedural_and_minimum(self, replayer):
        """RCCB 블록(비율 0.93)은 절차적보다 약간 작아야 함."""
        from app.sld.dxf_backend import DxfBackend
        sym = create_symbol("CB_RCCB", replayer)
        backend = DxfBackend.__new__(DxfBackend)
        offset = sym.label_offset_x(backend)
        procedural_offset = sym.width + 3
        assert offset <= procedural_offset
        assert offset > procedural_offset - 1.5


class TestDxfLabelAlignment:
    """모든 매핑된 심볼에 대한 정합성 검증."""

    def test_all_mapped_symbols_have_valid_offset(self, replayer):
        """모든 매핑된 심볼에 대해 label_offset_x()가 정상 동작."""
        from app.sld.dxf_backend import DxfBackend
        backend = DxfBackend.__new__(DxfBackend)

        for sym_name in SYMBOL_TO_DXF_BLOCK:
            sym = create_symbol(sym_name, replayer)
            if sym is None:
                continue
            offset = sym.label_offset_x(backend)
            assert offset > 0, f"{sym_name}: offset={offset} should be > 0"
            assert 3 <= offset <= 25, f"{sym_name}: offset={offset} out of range"

    def test_block_replayer_loads_all_mapped_blocks(self, replayer):
        """BlockReplayer loads all blocks referenced in SYMBOL_TO_DXF_BLOCK."""
        for sym_name, block_name in SYMBOL_TO_DXF_BLOCK.items():
            assert replayer.has_block(block_name), (
                f"Block '{block_name}' (for symbol '{sym_name}') not in replayer"
            )
