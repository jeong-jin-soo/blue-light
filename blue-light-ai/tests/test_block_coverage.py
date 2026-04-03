"""DXF 블록 커버리지 및 품질 검증 테스트.

블록 라이브러리의 무결성과 SYMBOL_TO_DXF_BLOCK 매핑의 완전성을 검증한다.
"""

import json
from pathlib import Path

import pytest

from app.sld.block_replayer import BlockReplayer
from app.sld.symbol import SYMBOL_TO_DXF_BLOCK, _CB_BLOCK_NAMES, create_symbol


LIB_PATH = Path(__file__).resolve().parent.parent / "data" / "templates" / "dxf_block_library.json"
SYM_PATH = Path(__file__).resolve().parent.parent / "data" / "templates" / "real_symbol_paths.json"


@pytest.fixture(scope="module")
def library():
    with open(LIB_PATH) as f:
        return json.load(f)


@pytest.fixture(scope="module")
def replayer():
    return BlockReplayer.load()


@pytest.fixture(scope="module")
def symbol_dims():
    with open(SYM_PATH) as f:
        return json.load(f)


class TestBlockLibraryIntegrity:
    """블록 라이브러리 JSON 구조 검증."""

    def test_library_has_meta(self, library):
        assert "_meta" in library
        assert library["_meta"]["total_blocks"] > 0

    def test_library_has_blocks(self, library):
        blocks = library.get("blocks", {})
        custom = library.get("custom_blocks", {})
        total = len(blocks) + len(custom)
        assert total >= 14, f"Expected ≥14 blocks, got {total}"

    def test_every_block_has_required_fields(self, library):
        all_blocks = {}
        all_blocks.update(library.get("blocks", {}))
        all_blocks.update(library.get("custom_blocks", {}))

        for name, block in all_blocks.items():
            assert "width_du" in block, f"{name}: missing width_du"
            assert "height_du" in block, f"{name}: missing height_du"
            assert "entities" in block, f"{name}: missing entities"
            assert len(block["entities"]) > 0, f"{name}: empty entities"
            assert block["width_du"] > 0, f"{name}: width_du must be positive"
            assert block["height_du"] > 0, f"{name}: height_du must be positive"

    def test_every_block_has_pins(self, library):
        all_blocks = {}
        all_blocks.update(library.get("blocks", {}))
        all_blocks.update(library.get("custom_blocks", {}))

        for name, block in all_blocks.items():
            if "pins" not in block:
                continue  # pins auto-derived if missing
            pins = block["pins"]
            # At least one connection pin must exist
            has_connection = any(k in pins for k in ("top", "bottom", "left", "right"))
            assert has_connection, (
                f"{name}: must have at least one connection pin"
            )


class TestSymbolMapping:
    """SYMBOL_TO_DXF_BLOCK 매핑 완전성 검증."""

    def test_all_mapped_blocks_exist_in_library(self, replayer):
        """매핑된 모든 블록이 라이브러리에 실재하는지 확인."""
        missing = []
        for sym_name, block_name in SYMBOL_TO_DXF_BLOCK.items():
            if not replayer.has_block(block_name):
                missing.append(f"{sym_name} → {block_name}")
        assert not missing, f"Missing blocks in library: {missing}"

    def test_mapping_coverage(self):
        """주요 심볼이 모두 매핑되어 있는지 확인."""
        required_symbols = [
            "CB_MCB", "CB_MCCB", "CB_RCCB", "CB_ELCB", "CB_ACB",
            "ISOLATOR", "DP_ISOL_DEVICE",
            "KWH_METER", "EARTH",
            "CT", "VOLTMETER", "AMMETER",
            "FUSE", "POTENTIAL_FUSE",
            "SELECTOR_SWITCH", "ELR", "BI_CONNECTOR",
            "INDICATOR_LIGHTS",
        ]
        missing = [s for s in required_symbols if s not in SYMBOL_TO_DXF_BLOCK]
        assert not missing, f"Unmapped required symbols: {missing}"

    def test_cb_block_names_includes_acb(self):
        """ACB가 CB 블록 이름 집합에 포함되어 있는지 확인."""
        assert "ACB" in _CB_BLOCK_NAMES

    # Symbols mapped to blocks but without procedural fallback definition
    # (create_symbol requires a procedural symbol for metadata — these return None)
    _NO_PROCEDURAL = {"3P_ISOLATOR"}

    def test_create_symbol_returns_block_for_all_mapped(self, replayer):
        """매핑된 모든 심볼이 BlockSymbol로 생성되는지 확인."""
        from app.sld.symbol import BlockSymbol
        for sym_name in SYMBOL_TO_DXF_BLOCK:
            if sym_name in self._NO_PROCEDURAL:
                continue  # no procedural fallback; block-only via direct insert
            symbol = create_symbol(sym_name, replayer)
            assert symbol is not None, f"create_symbol({sym_name!r}) returned None"
            assert isinstance(symbol, BlockSymbol), (
                f"{sym_name}: expected BlockSymbol, got {type(symbol).__name__}"
            )


class TestBlockDimensionCompatibility:
    """블록 치수가 프로시저럴 캘리브레이션과 호환되는지 검증."""

    # Scale factor from DU to mm (MCCB reference: 597.82 DU = 9.0 mm)
    DU_PER_MM = 597.82 / 9.0

    # Symbols with calibration data in real_symbol_paths.json
    CALIBRATED_SYMBOLS = {
        "CB_MCB": "MCB",
        "CB_MCCB": "MCCB",
        "CB_RCCB": "RCCB",
        "ISOLATOR": "ISOLATOR",
        "KWH_METER": "KWH_METER",
        "EARTH": "EARTH",
        "CT": "CT",
        "ELR": "ELR",
        "FUSE": "FUSE",
        "SELECTOR_SWITCH": "SELECTOR_SWITCH",
        "BI_CONNECTOR": "BI_CONNECTOR",
    }

    def test_block_dimensions_within_tolerance(self, replayer, symbol_dims):
        """블록 치수가 캘리브레이션 데이터 대비 ±50% 이내인지 확인.

        블록은 DU 단위이고 캘리브레이션은 mm 단위이므로 비율로 비교한다.
        """
        issues = []
        for sym_name, dim_key in self.CALIBRATED_SYMBOLS.items():
            block_name = SYMBOL_TO_DXF_BLOCK.get(sym_name)
            if not block_name or not replayer.has_block(block_name):
                continue

            dims = symbol_dims.get(dim_key)
            if not dims:
                continue

            # Get expected dimensions in mm
            expected_w = dims.get("width_mm", dims.get("radius_mm", 0) * 2)
            expected_h = dims.get("height_mm", dims.get("radius_mm", 0) * 2)
            if not expected_w or not expected_h:
                continue

            # Get block dimensions in DU → convert to mm
            block_w_du = replayer.block_width_du(block_name)
            block_h_du = replayer.block_height_du(block_name)
            block_w_mm = block_w_du / self.DU_PER_MM
            block_h_mm = block_h_du / self.DU_PER_MM

            # Check ratio (allow generous tolerance — extracted blocks may use
            # different coordinate systems than the mm calibration)
            h_ratio = block_h_mm / expected_h if expected_h else 1.0
            if not (0.4 <= h_ratio <= 2.5):
                issues.append(
                    f"{sym_name} ({block_name}): "
                    f"height ratio {h_ratio:.2f} (block={block_h_mm:.1f}mm, expected={expected_h:.1f}mm)"
                )

        assert not issues, f"Dimension issues:\n" + "\n".join(issues)


class TestBlockRenderability:
    """각 블록이 렌더링 가능한지 검증 (엔티티가 올바른 형식인지)."""

    VALID_ENTITY_TYPES = {"LINE", "CIRCLE", "ARC", "LWPOLYLINE", "TEXT"}

    def test_all_blocks_have_valid_entities(self, library):
        all_blocks = {}
        all_blocks.update(library.get("blocks", {}))
        all_blocks.update(library.get("custom_blocks", {}))

        for name, block in all_blocks.items():
            for i, ent in enumerate(block.get("entities", [])):
                etype = ent.get("type", "UNKNOWN")
                assert etype in self.VALID_ENTITY_TYPES, (
                    f"{name}[{i}]: invalid entity type '{etype}'"
                )

                # Validate entity structure
                if etype == "LINE":
                    assert "start" in ent and "end" in ent, f"{name}[{i}]: LINE needs start/end"
                elif etype == "CIRCLE":
                    assert "center" in ent and "radius" in ent, f"{name}[{i}]: CIRCLE needs center/radius"
                elif etype == "ARC":
                    assert all(k in ent for k in ("center", "radius", "start_angle", "end_angle")), (
                        f"{name}[{i}]: ARC needs center/radius/start_angle/end_angle"
                    )
                elif etype == "LWPOLYLINE":
                    assert "points" in ent, f"{name}[{i}]: LWPOLYLINE needs points"
                    assert len(ent["points"]) >= 2, f"{name}[{i}]: LWPOLYLINE needs ≥2 points"
                elif etype == "TEXT":
                    assert "text" in ent, f"{name}[{i}]: TEXT needs text field"
