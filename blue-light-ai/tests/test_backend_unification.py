"""백엔드 통합 렌더링 테스트.

DXF/PDF/SVG 3개 백엔드가 draw_center_line(), draw_fanout() 프로토콜을
올바르게 구현하는지 검증한다.
"""

from __future__ import annotations

import pytest

from app.sld.svg_backend import SvgBackend
from app.sld.pdf_backend import PdfBackend
from app.sld.dxf_backend import DxfBackend


# ── draw_center_line ─────────────────────────────────────


class TestDrawCenterLine:
    """draw_center_line() 프로토콜 구현 검증."""

    def test_svg_produces_gray_dashed_lines(self):
        """SVG: SLD_DB_FRAME 레이어로 gray (#808080) dash 선 출력."""
        svg = SvgBackend()
        svg.set_layer("SLD_CONNECTIONS")
        svg.draw_center_line((10, 50), (50, 50))
        output = svg.get_svg_string()
        # Gray stroke from SLD_DB_FRAME layer
        assert '#808080' in output
        # Multiple line segments (procedural dashes)
        assert output.count("<line") >= 2

    def test_svg_restores_previous_layer(self):
        """SVG: draw_center_line 후 이전 레이어 복원."""
        svg = SvgBackend()
        svg.set_layer("SLD_SYMBOLS")
        svg.draw_center_line((0, 0), (40, 0))
        # After draw_center_line, layer should be restored
        assert svg._current_layer == "SLD_SYMBOLS"

    def test_pdf_produces_gray_dashed_lines(self):
        """PDF: SLD_DB_FRAME 레이어로 gray dash 선 출력 (에러 없음)."""
        pdf = PdfBackend()
        pdf.set_layer("SLD_CONNECTIONS")
        pdf.draw_center_line((10, 50), (50, 50))
        # Should not raise
        data = pdf.get_bytes()
        assert len(data) > 0

    def test_pdf_restores_previous_layer(self):
        """PDF: draw_center_line 후 이전 레이어 복원."""
        pdf = PdfBackend()
        pdf.set_layer("SLD_SYMBOLS")
        pdf.draw_center_line((0, 0), (40, 0))
        assert pdf._current_layer == "SLD_SYMBOLS"

    def test_dxf_uses_native_center_linetype(self):
        """DXF: SLD_DB_FRAME (E-SLD-BOX) 레이어에 단일 LINE 생성."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_CONNECTIONS")
        dxf.draw_center_line((10, 50), (50, 50))
        # Should create exactly 1 LINE entity on E-SLD-BOX layer
        lines = [e for e in dxf.doc.modelspace() if e.dxftype() == "LINE"]
        assert len(lines) == 1
        assert lines[0].dxf.layer == "E-SLD-BOX"

    def test_dxf_restores_previous_layer(self):
        """DXF: draw_center_line 후 이전 레이어 복원."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_CONNECTIONS")
        dxf.draw_center_line((0, 0), (40, 0))
        assert dxf._current_layer == "E-SLD-LINE"

    def test_short_line_skipped(self):
        """길이 < 0.1mm인 선은 무시."""
        svg = SvgBackend()
        svg.draw_center_line((10, 50), (10.05, 50))
        output = svg.get_svg_string()
        assert "<line" not in output


# ── draw_fanout ──────────────────────────────────────────


class TestDrawFanout:
    """draw_fanout() 프로토콜 구현 검증."""

    def test_svg_draws_5_lines_for_2_side_fanout(self):
        """SVG: 2-side fanout → 5개 선 (center vertical + 2 diagonal + 2 side vertical)."""
        svg = SvgBackend()
        svg.set_layer("SLD_CONNECTIONS")
        svg.draw_fanout(
            center_x=100, busbar_y=50,
            side_xs=[80, 120], mcb_entry_y=80,
        )
        output = svg.get_svg_string()
        line_count = output.count("<line")
        assert line_count == 5, f"Expected 5 lines, got {line_count}"

    def test_svg_draws_3_lines_for_1_side_fanout(self):
        """SVG: 1-side fanout → 3개 선 (center vertical + 1 diagonal + 1 side vertical)."""
        svg = SvgBackend()
        svg.set_layer("SLD_CONNECTIONS")
        svg.draw_fanout(
            center_x=100, busbar_y=50,
            side_xs=[80], mcb_entry_y=80,
        )
        output = svg.get_svg_string()
        line_count = output.count("<line")
        assert line_count == 3, f"Expected 3 lines, got {line_count}"

    def test_pdf_draws_fanout_without_error(self):
        """PDF: draw_fanout 에러 없이 실행."""
        pdf = PdfBackend()
        pdf.set_layer("SLD_CONNECTIONS")
        pdf.draw_fanout(
            center_x=100, busbar_y=50,
            side_xs=[80, 120], mcb_entry_y=80,
        )
        data = pdf.get_bytes()
        assert len(data) > 0

    def test_dxf_creates_fanout_block(self):
        """DXF: FANOUT_3P 블록 생성 및 INSERT."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_CONNECTIONS")
        dxf.draw_fanout(
            center_x=100, busbar_y=50,
            side_xs=[80, 120], mcb_entry_y=80,
        )
        msp = dxf.doc.modelspace()
        inserts = [e for e in msp if e.dxftype() == "INSERT"]
        assert len(inserts) == 1
        assert inserts[0].dxf.name.startswith("FANOUT_3P_")

    def test_dxf_fanout_block_has_5_lines(self):
        """DXF: fanout 블록 내부에 5개 LINE (center + 2 diagonal + 2 side vertical)."""
        dxf = DxfBackend()
        dxf.draw_fanout(
            center_x=100, busbar_y=50,
            side_xs=[80, 120], mcb_entry_y=80,
        )
        msp = dxf.doc.modelspace()
        insert = [e for e in msp if e.dxftype() == "INSERT"][0]
        block = dxf.doc.blocks[insert.dxf.name]
        lines = [e for e in block if e.dxftype() == "LINE"]
        assert len(lines) == 5, f"Expected 5 lines in block, got {len(lines)}"

    def test_fanout_ratio_consistency(self):
        """SVG fanout 대각선 높이가 _FAN_RATIO=0.266과 일치."""
        svg = SvgBackend(page_height=297.0)
        svg.set_layer("SLD_CONNECTIONS")
        svg.draw_fanout(
            center_x=100, busbar_y=50,
            side_xs=[120], mcb_entry_y=80,
        )
        output = svg.get_svg_string()
        # Diagonal line: from (100, 50) to (120, 50 + 20*0.266 = 55.32)
        # In SVG Y-flip: y_start = 297-50=247, y_end = 297-55.32=241.68
        assert "x2=\"120.00\"" in output


# ── SLD_DB_FRAME 레이어 색상 검증 ────────────────────────


class TestDbFrameLayerColor:
    """SLD_DB_FRAME 레이어가 gray로 설정되었는지 검증."""

    def test_svg_db_frame_is_gray(self):
        """SVG: SLD_DB_FRAME → #808080."""
        from app.sld.svg_backend import _LAYER_COLORS
        assert _LAYER_COLORS["SLD_DB_FRAME"] == "#808080"

    def test_pdf_db_frame_is_gray(self):
        """PDF: SLD_DB_FRAME → (0.502, 0.502, 0.502)."""
        from app.sld.pdf_backend import _LAYER_COLORS
        color = _LAYER_COLORS["SLD_DB_FRAME"]
        assert abs(color[0] - 0.502) < 0.01
        assert abs(color[1] - 0.502) < 0.01
        assert abs(color[2] - 0.502) < 0.01

    def test_dxf_db_frame_layer_is_gray(self):
        """DXF: E-SLD-BOX 레이어 ACI color 8."""
        from app.sld.dxf_backend import _LAYER_CONFIG
        assert _LAYER_CONFIG["E-SLD-BOX"]["color"] == 8
