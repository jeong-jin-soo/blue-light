"""
DXF backend unit tests.

Tests for DxfBackend: document creation, layer setup, drawing primitives,
block import from reference DXF, and output serialization.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.sld.dxf_backend import DxfBackend

# Reference DXF path (same as generator.py)
_REFERENCE_DXF_PATH = (
    Path(__file__).resolve().parent.parent
    / "data" / "sld-info" / "slds-dxf" / "100A TPN SLD 1 DWG.dxf"
)

# Expected layers matching _LAYER_CONFIG in dxf_backend.py
_EXPECTED_LAYERS = [
    "SLD_SYMBOLS",
    "SLD_CONNECTIONS",
    "SLD_POWER_MAIN",
    "SLD_ANNOTATIONS",
    "SLD_TITLE_BLOCK",
]


# ── TestDxfBackendBasics ─────────────────────────────────────────


class TestDxfBackendBasics:
    """Document creation, layers, and text style."""

    def test_creates_document(self):
        """DxfBackend() produces a valid ezdxf document."""
        dxf = DxfBackend()
        assert dxf.doc is not None
        # modelspace should exist
        assert dxf.doc.modelspace() is not None

    def test_all_layers_exist(self):
        """All 5 SLD layers are created on init."""
        dxf = DxfBackend()
        layer_names = [layer.dxf.name for layer in dxf.doc.layers]
        for expected in _EXPECTED_LAYERS:
            assert expected in layer_names, f"Missing layer: {expected}"

    def test_text_style_exists(self):
        """REAL_SLD text style is created on init."""
        dxf = DxfBackend()
        style_names = [s.dxf.name for s in dxf.doc.styles]
        assert "REAL_SLD" in style_names

    def test_set_layer(self):
        """set_layer changes the current drawing layer."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_CONNECTIONS")
        dxf.add_line((0, 0), (10, 10))
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "SLD_CONNECTIONS"


# ── TestDxfBackendDrawing ────────────────────────────────────────


class TestDxfBackendDrawing:
    """Drawing primitive creation and attributes."""

    def test_add_line_creates_line_entity(self):
        dxf = DxfBackend()
        dxf.add_line((0, 0), (100, 100))
        entities = list(dxf.doc.modelspace())
        lines = [e for e in entities if e.dxftype() == "LINE"]
        assert len(lines) >= 1
        line = lines[-1]
        assert (line.dxf.start.x, line.dxf.start.y) == pytest.approx((0, 0))
        assert (line.dxf.end.x, line.dxf.end.y) == pytest.approx((100, 100))

    def test_add_mtext_creates_mtext_entity(self):
        dxf = DxfBackend()
        dxf.add_mtext("Hello World", insert=(10, 20), char_height=3.0)
        entities = list(dxf.doc.modelspace())
        mtexts = [e for e in entities if e.dxftype() == "MTEXT"]
        assert len(mtexts) >= 1
        mt = mtexts[-1]
        assert mt.dxf.char_height == pytest.approx(3.0)

    def test_add_mtext_rotation(self):
        """MTEXT with rotation > 0.1 gets rotation attribute set."""
        dxf = DxfBackend()
        dxf.add_mtext("Rotated", insert=(10, 20), rotation=90.0)
        entities = list(dxf.doc.modelspace())
        mtexts = [e for e in entities if e.dxftype() == "MTEXT"]
        assert len(mtexts) >= 1
        assert mtexts[-1].dxf.rotation == pytest.approx(90.0)

    def test_add_mtext_no_rotation_when_zero(self):
        """MTEXT with rotation=0 does not set rotation attribute."""
        dxf = DxfBackend()
        dxf.add_mtext("NoRotate", insert=(10, 20), rotation=0.0)
        entities = list(dxf.doc.modelspace())
        mtexts = [e for e in entities if e.dxftype() == "MTEXT"]
        assert len(mtexts) >= 1
        # rotation attribute should not be set (or default to 0)
        assert not mtexts[-1].dxf.hasattr("rotation") or mtexts[-1].dxf.rotation == pytest.approx(0.0)

    def test_add_circle_creates_circle_entity(self):
        dxf = DxfBackend()
        dxf.add_circle((50, 50), radius=10.0)
        entities = list(dxf.doc.modelspace())
        circles = [e for e in entities if e.dxftype() == "CIRCLE"]
        assert len(circles) >= 1
        c = circles[-1]
        assert c.dxf.radius == pytest.approx(10.0)

    def test_add_arc_creates_arc_entity(self):
        dxf = DxfBackend()
        dxf.add_arc((50, 50), radius=10.0, start_angle=0, end_angle=180)
        entities = list(dxf.doc.modelspace())
        arcs = [e for e in entities if e.dxftype() == "ARC"]
        assert len(arcs) >= 1

    def test_add_lwpolyline(self):
        dxf = DxfBackend()
        dxf.add_lwpolyline([(0, 0), (10, 0), (10, 10)], close=True)
        entities = list(dxf.doc.modelspace())
        plines = [e for e in entities if e.dxftype() == "LWPOLYLINE"]
        assert len(plines) >= 1

    def test_add_filled_rect_creates_hatch(self):
        dxf = DxfBackend()
        dxf.add_filled_rect(0, 0, 10, 5)
        entities = list(dxf.doc.modelspace())
        hatches = [e for e in entities if e.dxftype() == "HATCH"]
        assert len(hatches) >= 1

    def test_add_filled_circle_creates_hatch(self):
        dxf = DxfBackend()
        dxf.add_filled_circle((50, 50), radius=5.0)
        entities = list(dxf.doc.modelspace())
        hatches = [e for e in entities if e.dxftype() == "HATCH"]
        assert len(hatches) >= 1

    def test_lineweight_override(self):
        """add_line with explicit lineweight sets it on the entity."""
        dxf = DxfBackend()
        dxf.add_line((0, 0), (10, 10), lineweight=50)
        entities = list(dxf.doc.modelspace())
        lines = [e for e in entities if e.dxftype() == "LINE"]
        assert lines[-1].dxf.lineweight == 50


# ── TestDxfBlockImport ───────────────────────────────────────────


class TestDxfBlockImport:
    """Block import from reference DXF file."""

    def test_has_block_initially_false(self):
        """New DxfBackend has no custom blocks."""
        dxf = DxfBackend()
        assert dxf.has_block("MCCB") is False
        assert dxf.has_block("RCCB") is False
        assert dxf.has_block("DP ISOL") is False

    @pytest.mark.skipif(
        not _REFERENCE_DXF_PATH.exists(),
        reason="Reference DXF not found — skipping block import tests",
    )
    def test_import_symbol_blocks_from_reference(self):
        """Importing from reference DXF makes MCCB, RCCB, DP ISOL available."""
        dxf = DxfBackend()
        dxf.import_symbol_blocks(str(_REFERENCE_DXF_PATH))
        assert dxf.has_block("MCCB") is True
        assert dxf.has_block("RCCB") is True
        assert dxf.has_block("DP ISOL") is True

    @pytest.mark.skipif(
        not _REFERENCE_DXF_PATH.exists(),
        reason="Reference DXF not found — skipping block import tests",
    )
    def test_insert_block_creates_insert_entity(self):
        """insert_block creates an INSERT entity in modelspace."""
        dxf = DxfBackend()
        dxf.import_symbol_blocks(str(_REFERENCE_DXF_PATH))
        dxf.insert_block("MCCB", 100, 200, scale=0.5, rotation=0)
        entities = list(dxf.doc.modelspace())
        inserts = [e for e in entities if e.dxftype() == "INSERT"]
        assert len(inserts) >= 1
        ins = inserts[-1]
        assert ins.dxf.name == "MCCB"
        assert ins.dxf.xscale == pytest.approx(0.5)
        assert ins.dxf.yscale == pytest.approx(0.5)

    def test_import_nonexistent_file_no_crash(self):
        """import_symbol_blocks with nonexistent path does not crash."""
        dxf = DxfBackend()
        dxf.import_symbol_blocks("/nonexistent/path/file.dxf")
        # Should still be functional
        assert dxf.has_block("MCCB") is False

    def test_import_invalid_path_no_crash(self):
        """import_symbol_blocks with invalid path logs warning, no crash."""
        dxf = DxfBackend()
        dxf.import_symbol_blocks("")
        assert dxf.has_block("MCCB") is False


# ── TestDxfBlockHeightValidation ──────────────────────────────────


class TestDxfBlockHeightValidation:
    """Runtime validation of hardcoded _DXF_BLOCK_HEIGHTS."""

    @pytest.mark.skipif(
        not _REFERENCE_DXF_PATH.exists(),
        reason="Reference DXF not found — skipping height validation tests",
    )
    def test_hardcoded_heights_match_reference(self):
        """Hardcoded block heights match actual DXF block measurements within 1%."""
        from app.sld.generator import _DXF_BLOCK_HEIGHTS, _compute_block_heights_from_dxf

        measured = _compute_block_heights_from_dxf(_REFERENCE_DXF_PATH)
        assert len(measured) > 0, "No blocks measured from reference DXF"

        for name, hardcoded in _DXF_BLOCK_HEIGHTS.items():
            actual = measured.get(name)
            assert actual is not None, f"Block '{name}' not found in reference DXF"
            pct_diff = abs(actual - hardcoded) / hardcoded * 100
            assert pct_diff <= 1.0, (
                f"Block '{name}': hardcoded={hardcoded:.2f}, "
                f"measured={actual:.2f} ({pct_diff:.1f}% diff)"
            )

    def test_compute_block_heights_nonexistent_file(self):
        """_compute_block_heights_from_dxf returns empty dict for missing file."""
        from app.sld.generator import _compute_block_heights_from_dxf

        result = _compute_block_heights_from_dxf(Path("/nonexistent/file.dxf"))
        assert result == {}


# ── TestDxfBackendOutput ─────────────────────────────────────────


class TestDxfBackendOutput:
    """Output serialization (get_bytes, save)."""

    def test_get_bytes_not_empty(self):
        """get_bytes() returns non-empty bytes."""
        dxf = DxfBackend()
        dxf.add_line((0, 0), (100, 100))
        data = dxf.get_bytes()
        assert isinstance(data, bytes)
        assert len(data) > 0

    def test_get_bytes_utf8_decodable(self):
        """DXF output is valid UTF-8 text."""
        dxf = DxfBackend()
        dxf.add_mtext("Test text", insert=(10, 20))
        data = dxf.get_bytes()
        text = data.decode("utf-8")
        assert len(text) > 0

    def test_get_bytes_contains_section(self):
        """DXF output contains SECTION keyword (valid DXF structure)."""
        dxf = DxfBackend()
        dxf.add_line((0, 0), (10, 10))
        text = dxf.get_bytes().decode("utf-8")
        assert "SECTION" in text

    def test_get_bytes_contains_entities(self):
        """DXF output contains ENTITIES section."""
        dxf = DxfBackend()
        dxf.add_line((0, 0), (10, 10))
        text = dxf.get_bytes().decode("utf-8")
        assert "ENTITIES" in text

    def test_save_creates_file(self, tmp_path):
        """save() creates a valid DXF file on disk."""
        dxf = DxfBackend()
        dxf.add_line((0, 0), (100, 100))
        dxf.add_mtext("File test", insert=(50, 50))
        output_path = str(tmp_path / "test_output.dxf")
        dxf.save(output_path)
        saved = Path(output_path)
        assert saved.exists()
        assert saved.stat().st_size > 0
        # File should be valid DXF (starts with a section marker)
        content = saved.read_text(encoding="utf-8")
        assert "SECTION" in content
