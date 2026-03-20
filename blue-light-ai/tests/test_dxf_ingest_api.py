"""DXF 인제스트 API 엔드포인트 테스트.

Phase 7: DxfIngestPipeline의 API용 메서드 + FastAPI 라우터 검증.
"""

from __future__ import annotations

import json
import pytest
from pathlib import Path
from unittest.mock import patch, MagicMock

from app.sld.dxf_ingest.pipeline import DxfIngestPipeline, LIBRARY_PATH


# ---------------------------------------------------------------------------
# Pipeline API methods
# ---------------------------------------------------------------------------


class TestStatusDict:
    """status_dict() — 라이브러리 현황을 dict로 반환."""

    def test_returns_expected_keys(self):
        pipeline = DxfIngestPipeline()
        result = pipeline.status_dict()

        assert "last_updated" in result
        assert "total_source_files" in result
        assert "total_blocks" in result
        assert "total_custom_blocks" in result
        assert "blocks" in result
        assert "custom_blocks" in result
        assert "unprocessed_files" in result
        assert isinstance(result["blocks"], list)
        assert isinstance(result["custom_blocks"], list)

    def test_blocks_have_expected_fields(self):
        pipeline = DxfIngestPipeline()
        result = pipeline.status_dict()

        if result["blocks"]:
            blk = result["blocks"][0]
            assert "name" in blk
            assert "source_count" in blk
            assert "height_du" in blk
            assert "width_du" in blk
            assert "entity_count" in blk

    def test_total_blocks_matches_blocks_list(self):
        pipeline = DxfIngestPipeline()
        result = pipeline.status_dict()

        assert result["total_blocks"] == len(result["blocks"])


class TestInspectDict:
    """inspect_dict() — 블록 상세 정보 dict 반환."""

    def test_known_block_mccb(self):
        pipeline = DxfIngestPipeline()
        info = pipeline.inspect_dict("MCCB")

        if info is None:
            pytest.skip("MCCB block not in library")

        assert info["name"] == "MCCB"
        assert info["is_custom"] is False
        assert info["width_du"] > 0
        assert info["height_du"] > 0
        assert "pins" in info
        assert "entity_count" in info
        assert info["entity_count"] > 0

    def test_custom_block_kwh(self):
        pipeline = DxfIngestPipeline()
        info = pipeline.inspect_dict("KWH_METER")

        if info is None:
            pytest.skip("KWH_METER custom block not in library")

        assert info["name"] == "KWH_METER"
        assert info["is_custom"] is True

    def test_unknown_block_returns_none(self):
        pipeline = DxfIngestPipeline()
        info = pipeline.inspect_dict("NONEXISTENT_BLOCK_XYZ")

        assert info is None

    def test_entity_types_populated(self):
        pipeline = DxfIngestPipeline()
        info = pipeline.inspect_dict("MCCB")

        if info is None:
            pytest.skip("MCCB block not in library")

        assert isinstance(info["entity_types"], dict)
        assert sum(info["entity_types"].values()) == info["entity_count"]


class TestListBlocks:
    """list_blocks() — 전체 블록 이름 목록."""

    def test_returns_sorted_list(self):
        pipeline = DxfIngestPipeline()
        names = pipeline.list_blocks()

        assert isinstance(names, list)
        assert names == sorted(names)

    def test_includes_custom_blocks(self):
        pipeline = DxfIngestPipeline()
        names = pipeline.list_blocks()

        # Custom blocks (KWH_METER, EARTH) should be present
        # if the library has them
        status = pipeline.status_dict()
        custom_names = [c["name"] for c in status["custom_blocks"]]
        for cn in custom_names:
            assert cn in names


# ---------------------------------------------------------------------------
# FastAPI router (unit-level, no HTTP server)
# ---------------------------------------------------------------------------


class TestDxfIngestRouter:
    """Router module imports and structure."""

    def test_router_import(self):
        from app.sld.dxf_ingest.router import router
        assert router.prefix == "/api/dxf-ingest"

    def test_router_has_expected_routes(self):
        from app.sld.dxf_ingest.router import router

        paths = [route.path for route in router.routes]
        prefix = router.prefix
        assert f"{prefix}/status" in paths
        assert f"{prefix}/blocks" in paths
        assert f"{prefix}/blocks/{{block_name}}" in paths
        assert f"{prefix}/scan" in paths
        assert f"{prefix}/upload" in paths

    def test_main_app_includes_router(self):
        """main.py가 dxf_ingest_router를 포함하는지 확인."""
        from app.main import app

        all_paths = [route.path for route in app.routes]
        assert "/api/dxf-ingest/status" in all_paths
        assert "/api/dxf-ingest/blocks" in all_paths
        assert "/api/dxf-ingest/scan" in all_paths
