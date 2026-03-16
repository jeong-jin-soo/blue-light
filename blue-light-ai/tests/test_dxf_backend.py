"""
DXF backend unit tests.

Tests for DxfBackend: document creation, layer setup, drawing primitives,
block import from reference DXF, and output serialization.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.sld.dxf_backend import DxfBackend
from app.sld.page_config import A3_LANDSCAPE, PageConfig

# Reference DXF path (same as generator.py)
_REFERENCE_DXF_PATH = (
    Path(__file__).resolve().parent.parent
    / "data" / "sld-info" / "slds-dxf" / "100A TPN SLD 1 DWG.dxf"
)

# Expected DXF layers matching i2R LEW convention (_LAYER_CONFIG in dxf_backend.py)
_EXPECTED_DXF_LAYERS = [
    "E-SLD-SYM",
    "E-SLD-LINE",
    "E-SLD-BUSBAR",
    "E-SLD-TXT",
    "E-SLD-TITLE",
    "E-SLD-BOX",
    "E-SLD-FRAME",
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
        """All i2R-convention E-SLD-* layers are created on init."""
        dxf = DxfBackend()
        layer_names = [layer.dxf.name for layer in dxf.doc.layers]
        for expected in _EXPECTED_DXF_LAYERS:
            assert expected in layer_names, f"Missing layer: {expected}"

    def test_text_style_exists(self):
        """REAL_SLD text style is created on init."""
        dxf = DxfBackend()
        style_names = [s.dxf.name for s in dxf.doc.styles]
        assert "REAL_SLD" in style_names

    def test_set_layer(self):
        """set_layer maps logical name to i2R DXF layer name."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_CONNECTIONS")
        dxf.add_line((0, 0), (10, 10))
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "E-SLD-LINE"

    def test_set_layer_direct_dxf_name(self):
        """set_layer accepts E-SLD-* names directly."""
        dxf = DxfBackend()
        dxf.set_layer("E-SLD-TXT")
        dxf.add_line((0, 0), (10, 10))
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "E-SLD-TXT"


# ── TestDxfBackendPageConfig ─────────────────────────────────────


class TestDxfBackendPageConfig:
    """Page configuration support."""

    def test_default_page_config_is_a3(self):
        """Default DxfBackend uses A3 landscape page config."""
        dxf = DxfBackend()
        assert dxf._page_config is A3_LANDSCAPE
        assert dxf._page_config.page_width == 420.0
        assert dxf._page_config.page_height == 297.0

    def test_custom_page_config_stored(self):
        """Custom PageConfig is stored and used."""
        custom = PageConfig(page_width=594.0, page_height=420.0)  # A2 landscape
        dxf = DxfBackend(page_config=custom)
        assert dxf._page_config is custom
        assert dxf._page_config.page_width == 594.0
        assert dxf._page_config.page_height == 420.0


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


# ── TestDxfBlockLibraryImport ─────────────────────────────────────


# Consolidated block library DXF path
_BLOCK_LIBRARY_DXF = (
    Path(__file__).resolve().parent.parent
    / "data" / "templates" / "symbols" / "sld_block_library.dxf"
)

# All five blocks expected in the consolidated library
_LIBRARY_BLOCKS = ["MCCB", "RCCB", "DP ISOL", "3P ISOL", "SLD-CT"]

# Expected entity composition per block
_EXPECTED_ENTITIES = {
    "MCCB": {"CIRCLE": 2, "LWPOLYLINE": 1},
    "RCCB": {"CIRCLE": 2, "LINE": 2, "LWPOLYLINE": 1},
    "DP ISOL": {"LINE": 2, "LWPOLYLINE": 1},
    "3P ISOL": {"LINE": 3, "LWPOLYLINE": 1},
    "SLD-CT": {"LINE": 1, "LWPOLYLINE": 1},
}


class TestDxfBlockLibraryImport:
    """Tests for importing blocks from the consolidated sld_block_library.dxf."""

    @pytest.mark.skipif(
        not _BLOCK_LIBRARY_DXF.exists(),
        reason="Block library DXF not found — run scripts/build_block_library.py",
    )
    def test_library_contains_all_five_blocks(self):
        """sld_block_library.dxf contains MCCB, RCCB, DP ISOL, 3P ISOL, SLD-CT."""
        dxf = DxfBackend()
        dxf.import_symbol_blocks(str(_BLOCK_LIBRARY_DXF))
        for block_name in _LIBRARY_BLOCKS:
            assert dxf.has_block(block_name), f"Missing block: {block_name}"

    @pytest.mark.skipif(
        not _BLOCK_LIBRARY_DXF.exists(),
        reason="Block library DXF not found — run scripts/build_block_library.py",
    )
    def test_library_block_entity_counts(self):
        """Each block in the library has the expected entity composition."""
        import ezdxf

        doc = ezdxf.readfile(str(_BLOCK_LIBRARY_DXF))
        for block_name, expected in _EXPECTED_ENTITIES.items():
            assert block_name in doc.blocks, f"Block '{block_name}' not in library"
            block = doc.blocks[block_name]
            entities = list(block)
            counts: dict[str, int] = {}
            for e in entities:
                etype = e.dxftype()
                counts[etype] = counts.get(etype, 0) + 1
            assert counts == expected, (
                f"Block '{block_name}': expected {expected}, got {counts}"
            )

    @pytest.mark.skipif(
        not _BLOCK_LIBRARY_DXF.exists(),
        reason="Block library DXF not found — run scripts/build_block_library.py",
    )
    def test_library_blocks_insertable(self):
        """All library blocks can be inserted into a new DXF document."""
        dxf = DxfBackend()
        dxf.import_symbol_blocks(str(_BLOCK_LIBRARY_DXF))
        for idx, block_name in enumerate(_LIBRARY_BLOCKS):
            dxf.insert_block(block_name, x=idx * 20.0, y=0.0)
        entities = list(dxf.doc.modelspace())
        inserts = [e for e in entities if e.dxftype() == "INSERT"]
        assert len(inserts) == len(_LIBRARY_BLOCKS)
        inserted_names = {ins.dxf.name for ins in inserts}
        assert inserted_names == set(_LIBRARY_BLOCKS)

    @pytest.mark.skipif(
        not _BLOCK_LIBRARY_DXF.exists(),
        reason="Block library DXF not found — run scripts/build_block_library.py",
    )
    def test_library_import_idempotent_with_reference(self):
        """Importing library then reference DXF does not duplicate blocks."""
        dxf = DxfBackend()
        dxf.import_symbol_blocks(str(_BLOCK_LIBRARY_DXF))
        # Import reference DXF that also has MCCB, RCCB, DP ISOL
        if _REFERENCE_DXF_PATH.exists():
            dxf.import_symbol_blocks(str(_REFERENCE_DXF_PATH))
        # Blocks should still exist (no duplication error)
        for block_name in _LIBRARY_BLOCKS[:3]:  # MCCB, RCCB, DP ISOL
            assert dxf.has_block(block_name)


# ── TestDxfBlockHeightValidation ──────────────────────────────────


class TestDxfBlockHeightValidation:
    """Validation of BlockReplayer library heights against legacy constants."""

    def test_block_replayer_heights_match_legacy(self):
        """BlockReplayer block heights match legacy _DXF_BLOCK_HEIGHTS within 1%."""
        from app.sld.block_replayer import BlockReplayer
        from app.sld.symbol import _DXF_BLOCK_HEIGHTS

        replayer = BlockReplayer.load()

        for name, legacy_height in _DXF_BLOCK_HEIGHTS.items():
            lib_height = replayer.block_height_du(name)
            assert lib_height > 0, f"Block '{name}' not in library"
            pct_diff = abs(lib_height - legacy_height) / legacy_height * 100
            assert pct_diff <= 1.0, (
                f"Block '{name}': legacy={legacy_height:.2f}, "
                f"library={lib_height:.2f} ({pct_diff:.1f}% diff)"
            )

    def test_block_replayer_loads_all_expected_blocks(self):
        """BlockReplayer loads all blocks referenced in SYMBOL_TO_DXF_BLOCK."""
        from app.sld.block_replayer import BlockReplayer
        from app.sld.symbol import SYMBOL_TO_DXF_BLOCK

        replayer = BlockReplayer.load()

        for symbol_name, block_name in SYMBOL_TO_DXF_BLOCK.items():
            assert replayer.has_block(block_name), (
                f"Block '{block_name}' (for symbol '{symbol_name}') not in library"
            )


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


# ── TestDxfLayerConvention ─────────────────────────────────────


class TestDxfLayerConvention:
    """Verify i2R LEW DXF layer naming convention (E-SLD-* prefix)."""

    def test_logical_to_dxf_layer_mapping(self):
        """All logical layer names map to the correct E-SLD-* DXF layers."""
        from app.sld.dxf_backend import _LOGICAL_TO_DXF_LAYER

        expected = {
            "SLD_SYMBOLS": "E-SLD-SYM",
            "SLD_CONNECTIONS": "E-SLD-LINE",
            "SLD_POWER_MAIN": "E-SLD-BUSBAR",
            "SLD_ANNOTATIONS": "E-SLD-TXT",
            "SLD_TITLE_BLOCK": "E-SLD-TITLE",
            "SLD_DB_FRAME": "E-SLD-BOX",
        }
        for logical, dxf_name in expected.items():
            assert _LOGICAL_TO_DXF_LAYER[logical] == dxf_name, (
                f"Logical '{logical}' should map to '{dxf_name}', "
                f"got '{_LOGICAL_TO_DXF_LAYER[logical]}'"
            )

    def test_all_mapped_layers_exist_in_document(self):
        """Every DXF layer referenced by the mapping exists in the document."""
        from app.sld.dxf_backend import _LOGICAL_TO_DXF_LAYER

        dxf = DxfBackend()
        doc_layers = {layer.dxf.name for layer in dxf.doc.layers}
        mapped_layers = set(_LOGICAL_TO_DXF_LAYER.values())
        for layer_name in mapped_layers:
            assert layer_name in doc_layers, (
                f"Mapped layer '{layer_name}' not found in DXF document layers"
            )

    def test_symbols_on_sym_layer(self):
        """Entities drawn on SLD_SYMBOLS end up on E-SLD-SYM layer."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_SYMBOLS")
        dxf.add_circle((50, 50), 10)
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "E-SLD-SYM"

    def test_connections_on_line_layer(self):
        """Entities drawn on SLD_CONNECTIONS end up on E-SLD-LINE layer."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_CONNECTIONS")
        dxf.add_line((0, 0), (100, 0))
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "E-SLD-LINE"

    def test_annotations_on_txt_layer(self):
        """MTEXT drawn on SLD_ANNOTATIONS ends up on E-SLD-TXT layer."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_ANNOTATIONS")
        dxf.add_mtext("20A", insert=(10, 20))
        entities = list(dxf.doc.modelspace())
        mtexts = [e for e in entities if e.dxftype() == "MTEXT"]
        assert mtexts[-1].dxf.layer == "E-SLD-TXT"

    def test_title_block_on_title_layer(self):
        """Entities drawn on SLD_TITLE_BLOCK end up on E-SLD-TITLE layer."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_TITLE_BLOCK")
        dxf.add_line((0, 0), (420, 0))
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "E-SLD-TITLE"

    def test_db_frame_on_box_layer(self):
        """Entities drawn on SLD_DB_FRAME end up on E-SLD-BOX layer."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_DB_FRAME")
        dxf.add_line((0, 0), (100, 0))
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "E-SLD-BOX"

    def test_busbar_on_busbar_layer(self):
        """Entities drawn on SLD_POWER_MAIN end up on E-SLD-BUSBAR layer."""
        dxf = DxfBackend()
        dxf.set_layer("SLD_POWER_MAIN")
        dxf.add_line((0, 100), (420, 100))
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "E-SLD-BUSBAR"

    def test_box_layer_has_center_linetype(self):
        """E-SLD-BOX layer uses CENTER linetype for dashed boxes."""
        dxf = DxfBackend()
        layer = dxf.doc.layers.get("E-SLD-BOX")
        assert layer.dxf.linetype == "CENTER"

    def test_box_layer_color_gray(self):
        """E-SLD-BOX layer uses ACI color 8 (gray)."""
        dxf = DxfBackend()
        layer = dxf.doc.layers.get("E-SLD-BOX")
        assert layer.color == 8

    def test_default_layer_is_sym(self):
        """Default current layer on new DxfBackend is E-SLD-SYM."""
        dxf = DxfBackend()
        dxf.add_line((0, 0), (10, 10))
        entities = list(dxf.doc.modelspace())
        assert entities[-1].dxf.layer == "E-SLD-SYM"

    def test_no_old_layer_names_in_document(self):
        """Old SLD_* layer names should NOT exist as DXF layers."""
        dxf = DxfBackend()
        doc_layers = {layer.dxf.name for layer in dxf.doc.layers}
        old_names = {"SLD_SYMBOLS", "SLD_CONNECTIONS", "SLD_POWER_MAIN",
                     "SLD_ANNOTATIONS", "SLD_TITLE_BLOCK", "SLD_DB_FRAME"}
        for old_name in old_names:
            assert old_name not in doc_layers, (
                f"Old layer name '{old_name}' should not exist in DXF document"
            )
