"""DXF 라벨 블록 인식 정렬 테스트.

Symbol.label_offset_x()가 블록 라이브러리 기반으로 일관된 오프셋을
계산하는지 검증한다. 백엔드 독립적 (DXF/PDF/SVG 동일 결과).
"""

from __future__ import annotations

import pytest

from app.sld.symbol import create_symbol, SYMBOL_TO_DXF_BLOCK
from app.sld.block_replayer import BlockReplayer


@pytest.fixture(scope="module")
def replayer():
    return BlockReplayer.load()


class TestSymbolLabelOffsetX:
    """Symbol.label_offset_x() — 블록 기반 라벨 X 오프셋 계산."""

    def test_mccb_block_offset_smaller_than_procedural(self, replayer):
        """MCCB 블록(1.92mm 폭)은 절차적(5.5mm)보다 좁아서 블록 오프셋이 작아야 함."""
        sym = create_symbol("CB_MCCB", replayer)
        offset = sym.label_offset_x()
        procedural_offset = sym.width + 3
        assert offset < procedural_offset
        assert offset > 5.0

    def test_offset_is_backend_independent(self, replayer):
        """label_offset_x()는 backend 인자 유무와 무관하게 동일 결과."""
        sym = create_symbol("CB_MCCB", replayer)
        offset_no_arg = sym.label_offset_x()
        offset_none = sym.label_offset_x(None)
        assert offset_no_arg == pytest.approx(offset_none)

    def test_unknown_symbol_returns_none(self):
        """블록이 없는 심볼은 create_symbol이 None 반환."""
        sym = create_symbol("UNKNOWN_SYMBOL", None)
        assert sym is None

    def test_procedural_symbol_offset(self):
        """BlockReplayer 없는 ProceduralSymbol은 width + 3."""
        sym = create_symbol("CB_MCCB", None)
        assert sym.label_offset_x() == pytest.approx(sym.width + 3)

    def test_rccb_offset_between_procedural_and_minimum(self, replayer):
        """RCCB 블록(비율 0.93)은 절차적보다 약간 작아야 함."""
        sym = create_symbol("CB_RCCB", replayer)
        offset = sym.label_offset_x()
        procedural_offset = sym.width + 3
        assert offset <= procedural_offset
        assert offset > procedural_offset - 1.5


class TestDxfLabelAlignment:
    """모든 매핑된 심볼에 대한 정합성 검증."""

    def test_all_mapped_symbols_have_valid_offset(self, replayer):
        """모든 매핑된 심볼에 대해 label_offset_x()가 정상 동작."""
        for sym_name in SYMBOL_TO_DXF_BLOCK:
            sym = create_symbol(sym_name, replayer)
            if sym is None:
                continue
            offset = sym.label_offset_x()
            assert offset > 0, f"{sym_name}: offset={offset} should be > 0"
            assert 3 <= offset <= 50, f"{sym_name}: offset={offset} out of range"

    def test_block_replayer_loads_all_mapped_blocks(self, replayer):
        """BlockReplayer loads all blocks referenced in SYMBOL_TO_DXF_BLOCK."""
        for sym_name, block_name in SYMBOL_TO_DXF_BLOCK.items():
            assert replayer.has_block(block_name), (
                f"Block '{block_name}' (for symbol '{sym_name}') not in replayer"
            )
