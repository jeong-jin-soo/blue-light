"""Tests for app.sld.dwg_converter module."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app.sld.dwg_converter import (
    analyze_dxf,
    batch_analyze,
    convert_dwg_to_dxf,
    extract_dwg_text,
)

# ---------------------------------------------------------------------------
# Test data paths
# ---------------------------------------------------------------------------

DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "sld-info"
DWG_DIR = DATA_DIR / "slds-dwg"
DXF_DIR = DATA_DIR / "slds-dxf"

SAMPLE_DWG = DWG_DIR / "63A TPN SLD 1 DWG.dwg"
SAMPLE_DXF = DXF_DIR / "63A TPN SLD 1 DWG.dxf"


# ---------------------------------------------------------------------------
# 1. Binary text extraction from DWG
# ---------------------------------------------------------------------------

class TestExtractDwgText:
    @pytest.mark.skipif(not SAMPLE_DWG.is_file(), reason="DWG sample not available")
    def test_returns_dict_with_expected_keys(self):
        result = extract_dwg_text(SAMPLE_DWG)
        assert "ascii_strings" in result
        assert "utf16_strings" in result
        assert "electrical_specs" in result

    @pytest.mark.skipif(not SAMPLE_DWG.is_file(), reason="DWG sample not available")
    def test_ascii_strings_non_empty(self):
        result = extract_dwg_text(SAMPLE_DWG)
        assert len(result["ascii_strings"]) > 0, "Expected some ASCII strings from DWG"

    @pytest.mark.skipif(not SAMPLE_DWG.is_file(), reason="DWG sample not available")
    def test_electrical_specs_has_ratings(self):
        result = extract_dwg_text(SAMPLE_DWG)
        specs = result["electrical_specs"]
        assert len(specs["ratings"]) > 0, "Expected at least one rating in 63A TPN SLD"

    @pytest.mark.skipif(not SAMPLE_DWG.is_file(), reason="DWG sample not available")
    def test_electrical_specs_structure(self):
        """Verify electrical_specs dict has all expected keys (values may be empty for binary extraction)."""
        result = extract_dwg_text(SAMPLE_DWG)
        specs = result["electrical_specs"]
        for key in ("ratings", "cable_sizes", "breaker_types", "phase", "supply_source"):
            assert key in specs, f"Missing key: {key}"
            assert isinstance(specs[key], list)

    def test_nonexistent_file_returns_empty(self):
        result = extract_dwg_text("/tmp/nonexistent.dwg")
        assert result["ascii_strings"] == []
        assert result["utf16_strings"] == []
        assert result["electrical_specs"] == {}


# ---------------------------------------------------------------------------
# 2. DXF analysis via ezdxf
# ---------------------------------------------------------------------------

class TestAnalyzeDxf:
    @pytest.mark.skipif(not SAMPLE_DXF.is_file(), reason="DXF sample not available")
    def test_returns_dict_with_expected_keys(self):
        result = analyze_dxf(SAMPLE_DXF)
        assert "file" in result
        assert "blocks" in result
        assert "texts" in result
        assert "layers" in result
        assert "entity_counts" in result
        assert "electrical_specs" in result

    @pytest.mark.skipif(not SAMPLE_DXF.is_file(), reason="DXF sample not available")
    def test_texts_have_coordinates(self):
        result = analyze_dxf(SAMPLE_DXF)
        assert len(result["texts"]) > 0, "Expected text entities in DXF"
        for t in result["texts"][:5]:
            assert "x" in t and "y" in t, "Text entry must have x, y coordinates"
            assert "text" in t
            assert "layer" in t

    @pytest.mark.skipif(not SAMPLE_DXF.is_file(), reason="DXF sample not available")
    def test_layers_non_empty(self):
        result = analyze_dxf(SAMPLE_DXF)
        assert len(result["layers"]) > 0

    @pytest.mark.skipif(not SAMPLE_DXF.is_file(), reason="DXF sample not available")
    def test_entity_counts_non_empty(self):
        result = analyze_dxf(SAMPLE_DXF)
        assert len(result["entity_counts"]) > 0

    @pytest.mark.skipif(not SAMPLE_DXF.is_file(), reason="DXF sample not available")
    def test_electrical_specs_extracted(self):
        result = analyze_dxf(SAMPLE_DXF)
        specs = result["electrical_specs"]
        assert isinstance(specs, dict)
        assert "ratings" in specs
        assert "breaker_types" in specs

    def test_nonexistent_file_returns_error(self):
        result = analyze_dxf("/tmp/nonexistent.dxf")
        assert "error" in result


# ---------------------------------------------------------------------------
# 3. DWG → DXF conversion
# ---------------------------------------------------------------------------

class TestConvertDwgToDxf:
    def test_nonexistent_file_returns_none(self):
        result = convert_dwg_to_dxf("/tmp/nonexistent.dwg")
        assert result is None

    @pytest.mark.skipif(not SAMPLE_DWG.is_file(), reason="DWG sample not available")
    def test_conversion_returns_path_or_none(self):
        """Conversion may fail (LibreDWG handles only some files) but should not raise."""
        result = convert_dwg_to_dxf(SAMPLE_DWG, output_dir="/tmp/test_dwg_conv")
        assert result is None or result.is_file()


# ---------------------------------------------------------------------------
# 4. Batch analyze
# ---------------------------------------------------------------------------

class TestBatchAnalyze:
    @pytest.mark.skipif(not DXF_DIR.is_dir(), reason="DXF directory not available")
    def test_batch_returns_results(self):
        # Analyze DXF directory only (fast, no conversion needed)
        results = batch_analyze(DXF_DIR)
        assert len(results) > 0

    @pytest.mark.skipif(not DXF_DIR.is_dir(), reason="DXF directory not available")
    def test_batch_output_json(self, tmp_path):
        out_file = tmp_path / "analysis.json"
        results = batch_analyze(DXF_DIR, output_json=out_file)
        assert out_file.is_file()
        data = json.loads(out_file.read_text())
        assert len(data) == len(results)

    def test_nonexistent_dir_returns_empty(self):
        results = batch_analyze("/tmp/nonexistent_dir_12345")
        assert results == []
