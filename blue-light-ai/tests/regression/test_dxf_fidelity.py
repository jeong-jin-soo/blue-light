"""DXF 출력 충실도 테스트.

generator가 생성한 DXF 파일을 재분석하여 구조적 무결성 검증.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

import pytest

from app.sld.generator import SldPipeline

from .conftest import ALL_CONFIGS


class TestDxfGeneration:
    """DXF 파일 생성 기본 검증."""

    @pytest.mark.parametrize("config_id", ["direct_3phase_63a", "direct_1phase_40a"])
    def test_generates_valid_dxf(self, config_id):
        """DXF 파일이 정상적으로 생성되는지."""
        req = ALL_CONFIGS[config_id]
        _r = SldPipeline().run(req, backend_type="dxf")
        dxf_bytes = _r.dxf_bytes
        assert dxf_bytes is not None, f"[{config_id}] DXF bytes is None"
        assert len(dxf_bytes) > 1000, f"[{config_id}] DXF too small: {len(dxf_bytes)} bytes"

    @pytest.mark.parametrize("config_id", ["direct_3phase_63a", "direct_1phase_40a"])
    def test_generates_valid_pdf(self, config_id):
        """PDF 파일이 정상적으로 생성되는지."""
        req = ALL_CONFIGS[config_id]
        _r = SldPipeline().run(req)
        pdf_bytes = _r.pdf_bytes
        assert pdf_bytes is not None, f"[{config_id}] PDF bytes is None"
        assert len(pdf_bytes) > 1000, f"[{config_id}] PDF too small: {len(pdf_bytes)} bytes"

    @pytest.mark.parametrize("config_id", ["direct_3phase_63a"])
    def test_generates_valid_svg(self, config_id):
        """SVG 문자열이 정상적으로 생성되는지."""
        req = ALL_CONFIGS[config_id]
        _r = SldPipeline().run(req)
        svg_string = _r.svg_string
        assert svg_string is not None, f"[{config_id}] SVG is None"
        assert "<svg" in svg_string, f"[{config_id}] SVG has no <svg> tag"


class TestDxfStructure:
    """DXF 파일 내부 구조 검증."""

    @pytest.mark.parametrize("config_id", ["direct_3phase_63a"])
    def test_dxf_has_correct_layers(self, config_id):
        """DXF 파일에 E-SLD-* 레이어가 있는지."""
        import ezdxf
        req = ALL_CONFIGS[config_id]
        dxf_bytes = SldPipeline().run(req, backend_type="dxf").dxf_bytes

        with tempfile.NamedTemporaryFile(suffix=".dxf", delete=False) as f:
            f.write(dxf_bytes)
            f.flush()
            doc = ezdxf.readfile(f.name)

        layer_names = {layer.dxf.name for layer in doc.layers}
        expected_prefixes = ["E-SLD-"]
        has_sld_layers = any(
            any(ln.startswith(prefix) for prefix in expected_prefixes)
            for ln in layer_names
        )
        assert has_sld_layers, \
            f"[{config_id}] No E-SLD-* layers found. Layers: {layer_names}"

    @pytest.mark.parametrize("config_id", ["direct_3phase_63a"])
    def test_dxf_has_entities(self, config_id):
        """DXF modelspace에 엔티티가 존재하는지."""
        import ezdxf
        req = ALL_CONFIGS[config_id]
        dxf_bytes = SldPipeline().run(req, backend_type="dxf").dxf_bytes

        with tempfile.NamedTemporaryFile(suffix=".dxf", delete=False) as f:
            f.write(dxf_bytes)
            f.flush()
            doc = ezdxf.readfile(f.name)

        msp = doc.modelspace()
        entity_count = len(list(msp))
        assert entity_count > 50, \
            f"[{config_id}] DXF has too few entities: {entity_count}"

    @pytest.mark.parametrize("config_id", ["direct_3phase_63a"])
    def test_dxf_has_text_content(self, config_id):
        """DXF에 텍스트 엔티티가 있는지."""
        import ezdxf
        req = ALL_CONFIGS[config_id]
        dxf_bytes = SldPipeline().run(req, backend_type="dxf").dxf_bytes

        with tempfile.NamedTemporaryFile(suffix=".dxf", delete=False) as f:
            f.write(dxf_bytes)
            f.flush()
            doc = ezdxf.readfile(f.name)

        msp = doc.modelspace()
        text_entities = [e for e in msp if e.dxftype() in ("TEXT", "MTEXT")]
        assert len(text_entities) > 5, \
            f"[{config_id}] DXF has too few text entities: {len(text_entities)}"
