"""DXF 인제스트 파이프라인 — FastAPI API 라우터.

CLI 전용이던 DxfIngestPipeline을 웹 API로 노출한다.

Endpoints:
    GET  /api/dxf-ingest/status          — 라이브러리 현황
    GET  /api/dxf-ingest/blocks          — 전체 블록 목록
    GET  /api/dxf-ingest/blocks/{name}   — 블록 상세 조회
    POST /api/dxf-ingest/scan            — DXF 스캔 (신규/전체)
    POST /api/dxf-ingest/upload          — DXF 파일 업로드 후 즉시 스캔
"""

from __future__ import annotations

import logging
import shutil
import tempfile
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, UploadFile, File, Query

from app.dependencies import verify_service_key
from app.sld.dxf_ingest.pipeline import DxfIngestPipeline

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/dxf-ingest", tags=["dxf-ingest"])

# Singleton pipeline (uses default paths).
_pipeline: DxfIngestPipeline | None = None


def _get_pipeline() -> DxfIngestPipeline:
    global _pipeline
    if _pipeline is None:
        _pipeline = DxfIngestPipeline()
    return _pipeline


# ── Status ──────────────────────────────────────────

@router.get("/status")
async def ingest_status(
    _: str = Depends(verify_service_key),
) -> dict[str, Any]:
    """라이브러리 현황 조회.

    Returns:
        last_updated, total_source_files, total_blocks,
        blocks[], custom_blocks[], unprocessed_files[]
    """
    pipeline = _get_pipeline()
    return pipeline.status_dict()


# ── Block list / detail ─────────────────────────────

@router.get("/blocks")
async def list_blocks(
    _: str = Depends(verify_service_key),
) -> dict[str, Any]:
    """전체 블록 이름 목록 반환."""
    pipeline = _get_pipeline()
    names = pipeline.list_blocks()
    return {"count": len(names), "blocks": names}


@router.get("/blocks/{block_name}")
async def inspect_block(
    block_name: str,
    _: str = Depends(verify_service_key),
) -> dict[str, Any]:
    """블록 상세 정보 조회.

    Raises 404 if block not found.
    """
    pipeline = _get_pipeline()
    info = pipeline.inspect_dict(block_name)
    if info is None:
        available = pipeline.list_blocks()
        raise HTTPException(
            status_code=404,
            detail={
                "error": f"Block '{block_name}' not found",
                "available_blocks": available,
            },
        )
    return info


# ── Scan ────────────────────────────────────────────

@router.post("/scan")
async def scan_files(
    file: str | None = Query(None, description="특정 파일명만 스캔"),
    force: bool = Query(False, description="전체 재스캔 (이미 처리된 파일 포함)"),
    _: str = Depends(verify_service_key),
) -> dict[str, Any]:
    """DXF 디렉터리 스캔.

    - 기본: 신규(미처리) 파일만 스캔
    - ``file``: 특정 파일만 스캔
    - ``force=true``: 전체 재스캔 (기존 결과 덮어쓰기)

    Returns:
        {scanned, skipped, new_blocks, updated_blocks}
    """
    pipeline = _get_pipeline()
    try:
        stats = pipeline.scan(target_file=file, force=force)
    except Exception as exc:
        logger.error("DXF scan failed: %s", exc, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Scan failed: {exc}")
    return stats


# ── Upload + Scan ───────────────────────────────────

@router.post("/upload")
async def upload_and_scan(
    file: UploadFile = File(..., description="DXF 파일 (.dxf)"),
    _: str = Depends(verify_service_key),
) -> dict[str, Any]:
    """DXF 파일을 업로드하고 즉시 스캔.

    업로드된 파일은 ``data/sld-info/slds-dxf/`` 디렉터리에 저장된 후
    파이프라인으로 스캔된다.

    Returns:
        {filename, scan_result: {scanned, new_blocks, ...}}
    """
    if not file.filename:
        raise HTTPException(status_code=400, detail="Filename is required")

    # Validate extension
    suffix = Path(file.filename).suffix.lower()
    if suffix not in (".dxf",):
        raise HTTPException(
            status_code=400,
            detail=f"Only .dxf files are supported (got '{suffix}'). "
                   "Convert DWG to DXF before uploading.",
        )

    pipeline = _get_pipeline()
    target_path = pipeline.dxf_dir / file.filename

    # Save uploaded file
    try:
        pipeline.dxf_dir.mkdir(parents=True, exist_ok=True)
        with open(target_path, "wb") as f:
            shutil.copyfileobj(file.file, f)
        logger.info("Uploaded DXF file: %s (%d bytes)", file.filename, target_path.stat().st_size)
    except Exception as exc:
        logger.error("Failed to save uploaded file: %s", exc)
        raise HTTPException(status_code=500, detail=f"File save failed: {exc}")

    # Scan the uploaded file
    try:
        stats = pipeline.scan(target_file=file.filename)
    except Exception as exc:
        logger.error("Scan of uploaded file failed: %s", exc, exc_info=True)
        raise HTTPException(status_code=500, detail=f"Scan failed: {exc}")

    return {
        "filename": file.filename,
        "size_bytes": target_path.stat().st_size,
        "scan_result": stats,
    }
