"""
SLD AI Agent — FastAPI Application

Provides AI-powered Single Line Diagram generation for LicenseKaki.
Communicates with the Spring Boot backend via REST/SSE.
"""

import asyncio
import glob as glob_module
import json
import logging
import os
import subprocess
import time
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import AsyncGenerator

from fastapi import Depends, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse

from app.agent.graph import get_agent, process_message
from app.agent.checkpointer import get_checkpointer, close_checkpointer
from app.config import settings
from app.dependencies import verify_service_key
from app.models.schemas import (
    ChatRequest,
    ChatResponse,
    FileInfo,
    HealthResponse,
    ResetRequest,
)

# ── Logging ──────────────────────────────────────────

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)


# ── Temp File Cleanup ────────────────────────────────

TEMP_FILE_MAX_AGE_HOURS = 24  # 24시간 이상 된 임시 파일 자동 삭제


async def _temp_file_cleanup_scheduler():
    """Background task: 1시간마다 오래된 임시 파일 및 만료된 템플릿 캐시를 삭제."""
    while True:
        await asyncio.sleep(3600)  # 1시간 간격
        try:
            cleanup_old_temp_files()
        except Exception as e:
            logger.error(f"Temp file cleanup scheduler error: {e}")
        try:
            from app.sld.template_cache import cleanup_expired
            cleanup_expired()
        except Exception as e:
            logger.error(f"Template cache cleanup error: {e}")


def cleanup_old_temp_files() -> dict:
    """TEMP_FILE_MAX_AGE_HOURS보다 오래된 임시 파일 삭제."""
    cutoff = time.time() - (TEMP_FILE_MAX_AGE_HOURS * 3600)
    deleted = []
    errors = []

    for pattern in ["*.pdf", "*.svg"]:
        for file_path in glob_module.glob(
            os.path.join(settings.temp_file_dir, pattern)
        ):
            try:
                if os.path.getmtime(file_path) < cutoff:
                    os.remove(file_path)
                    deleted.append(os.path.basename(file_path))
            except Exception as e:
                errors.append({"file": os.path.basename(file_path), "error": str(e)})

    if deleted:
        logger.info(f"Temp cleanup: deleted {len(deleted)} old files: {deleted}")
    if errors:
        logger.warning(f"Temp cleanup errors: {errors}")

    return {"deleted": deleted, "errors": errors}


def cleanup_temp_file(file_id: str) -> dict:
    """특정 file_id의 임시 파일(PDF + SVG) 삭제."""
    deleted = []
    not_found = []

    for ext in ["pdf", "svg", "dxf"]:
        file_path = os.path.join(settings.temp_file_dir, f"{file_id}.{ext}")
        if os.path.exists(file_path):
            try:
                os.remove(file_path)
                deleted.append(f"{file_id}.{ext}")
            except Exception as e:
                logger.error(f"Failed to delete temp file {file_path}: {e}")
        else:
            not_found.append(f"{file_id}.{ext}")

    if deleted:
        logger.info(f"Temp file cleanup: deleted {deleted}")
    if not_found:
        logger.debug(f"Temp file cleanup: not found {not_found}")

    return {"deleted": deleted, "not_found": not_found}


# ── Lifespan ─────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application startup/shutdown lifecycle."""
    # Startup
    os.makedirs(settings.temp_file_dir, exist_ok=True)
    os.makedirs(os.path.dirname(settings.sqlite_db_path) or ".", exist_ok=True)
    logger.info("SLD Agent service started (port 8100)")
    logger.info(f"Gemini model: {settings.gemini_model}")
    logger.info(f"Spring Boot URL: {settings.spring_boot_url}")

    # 임시 파일 자동 삭제 스케줄러 시작
    cleanup_task = asyncio.create_task(_temp_file_cleanup_scheduler())
    logger.info(f"Temp file cleanup scheduler started (max age: {TEMP_FILE_MAX_AGE_HOURS}h)")

    yield

    # Shutdown
    cleanup_task.cancel()
    await close_checkpointer()
    logger.info("SLD Agent service shutting down")


# ── FastAPI App ──────────────────────────────────────

app = FastAPI(
    title="SLD AI Agent",
    description="AI-powered Single Line Diagram generation service",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=[settings.spring_boot_url],
    allow_credentials=True,
    allow_methods=["GET", "POST", "DELETE"],
    allow_headers=["*"],
)


# ── Version Info (프로세스 시작 시점의 Git 상태 캡처) ──

def _capture_git_info() -> dict:
    """프로세스 시작 시점의 Git 커밋/브랜치/dirty 상태를 캡처.

    로컬 개발 시 --reload 없이 실행하면 코드가 변경되어도 프로세스에
    반영되지 않는 문제를 진단하기 위한 엔드포인트용 정보.
    """
    info = {
        "commit": "unknown",
        "branch": "unknown",
        "dirty": False,
        "started_at": datetime.now(timezone.utc).isoformat(),
    }
    try:
        info["commit"] = subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"],
            stderr=subprocess.DEVNULL,
        ).decode().strip()
    except Exception:
        pass
    try:
        info["branch"] = subprocess.check_output(
            ["git", "rev-parse", "--abbrev-ref", "HEAD"],
            stderr=subprocess.DEVNULL,
        ).decode().strip()
    except Exception:
        pass
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"],
            capture_output=True, text=True,
            timeout=5,
        )
        info["dirty"] = bool(result.stdout.strip())
    except Exception:
        pass
    return info


_VERSION_INFO = _capture_git_info()


# ── Health Check ─────────────────────────────────────

@app.get("/health", response_model=HealthResponse)
async def health():
    """Health check endpoint."""
    return HealthResponse()


@app.get("/api/version")
async def version():
    """프로세스 시작 시점의 Git 버전 정보 반환.

    commit, branch, dirty 상태, 프로세스 시작 시각을 반환하여
    현재 실행 중인 코드가 어떤 버전인지 확인할 수 있다.
    """
    return _VERSION_INFO


# ── Chat Endpoints ───────────────────────────────────

@app.post("/api/chat/stream")
async def chat_stream(
    request: ChatRequest,
    _: str = Depends(verify_service_key),
):
    """
    SSE streaming chat endpoint.
    Processes user message through the LangGraph agent and streams events.
    """
    # 동일 application_seq → 동일 thread_id → LangGraph 체크포인터가 대화 이력을 유지
    thread_id = request.thread_id or f"sld-{request.application_seq}"

    async def event_generator() -> AsyncGenerator[str, None]:
        event_count = 0
        try:
            logger.info(f"SSE stream started: thread_id={thread_id}, app_seq={request.application_seq}")
            # Send thread_id first
            yield _sse_event("session", {"type": "session", "thread_id": thread_id})

            # Wrap process_message with heartbeat to keep SSE connection alive
            # during long Gemini API calls (prevents WebClient ReadTimeout)
            async for event in _with_heartbeat(
                process_message(
                    application_seq=request.application_seq,
                    user_seq=request.user_seq,
                    message=request.message,
                    thread_id=thread_id,
                    application_info=request.application_info,
                    system_prompt=request.system_prompt,
                    api_key=request.api_key,
                ),
                interval=15,
            ):
                event_type = event.get("type", "token")
                event_count += 1
                if event_type != "token":
                    logger.info(f"SSE event #{event_count}: type={event_type}")
                yield _sse_event(event_type, event)

            # Signal completion
            logger.info(f"SSE stream completed: thread_id={thread_id}, events={event_count}")
            yield _sse_event("done", {"type": "done"})

        except Exception as e:
            logger.error(f"Chat stream error (thread_id={thread_id}, events={event_count}): {e}", exc_info=True)
            yield _sse_event("error", {"type": "error", "content": str(e)})

    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.post("/api/chat", response_model=ChatResponse)
async def chat(
    request: ChatRequest,
    _: str = Depends(verify_service_key),
):
    """
    Non-streaming chat endpoint (synchronous response).
    """
    thread_id = request.thread_id or f"sld-{request.application_seq}"

    full_response = ""
    phase = "gathering"
    has_file = False
    file_id = None

    async for event in process_message(
        application_seq=request.application_seq,
        user_seq=request.user_seq,
        message=request.message,
        thread_id=thread_id,
        application_info=request.application_info,
        system_prompt=request.system_prompt,
        api_key=request.api_key,
    ):
        event_type = event.get("type")
        if event_type == "token":
            content = event.get("content", "")
            if isinstance(content, str):
                full_response += content
            elif isinstance(content, list):
                full_response += "".join(str(c) for c in content)
        elif event_type == "phase_change":
            phase = event.get("phase", phase)
        elif event_type == "file_generated":
            has_file = True
            file_id = event.get("fileId")

    return ChatResponse(
        thread_id=thread_id,
        message=full_response,
        phase=phase,
        has_file=has_file,
        file_id=file_id,
    )


@app.get("/api/chat/history/{application_seq}")
async def chat_history(
    application_seq: int,
    _: str = Depends(verify_service_key),
):
    """
    Retrieve conversation history from LangGraph checkpoints.
    """
    checkpointer = await get_checkpointer()
    # Find the latest thread for this application
    # Thread IDs follow the pattern: sld-{application_seq}-{random}
    # For now, return empty — will be populated when checkpointer is fully wired
    return {"application_seq": application_seq, "messages": []}


@app.post("/api/chat/reset/{application_seq}")
async def chat_reset(
    application_seq: int,
    _: str = Depends(verify_service_key),
):
    """
    Reset conversation state for an application.
    Clears the LangGraph checkpoint (SQLite) and temp files.
    After reset, the next message will start a completely fresh AI conversation.
    """
    thread_id = f"sld-{application_seq}"
    logger.info(f"Chat reset: application_seq={application_seq}, thread_id={thread_id}")

    checkpoint_cleared = False
    temp_files_cleaned = 0

    # 1. Clear LangGraph checkpoint (conversation memory)
    try:
        checkpointer = await get_checkpointer()
        # AsyncSqliteSaver stores data in 'checkpoints' and 'writes' tables
        if hasattr(checkpointer, "conn"):
            cursor = await checkpointer.conn.execute(
                "DELETE FROM checkpoints WHERE thread_id = ?", (thread_id,)
            )
            deleted_checkpoints = cursor.rowcount
            cursor = await checkpointer.conn.execute(
                "DELETE FROM writes WHERE thread_id = ?", (thread_id,)
            )
            deleted_writes = cursor.rowcount
            await checkpointer.conn.commit()
            checkpoint_cleared = True
            logger.info(
                f"Checkpoint cleared: thread_id={thread_id}, "
                f"checkpoints={deleted_checkpoints}, writes={deleted_writes}"
            )
    except Exception as e:
        logger.warning(f"Failed to clear checkpoint: {e}")

    # 2. Clean up temp files (PDF/SVG generated for this conversation)
    try:
        import glob
        temp_dir = settings.temp_file_dir
        for pattern in ("*.pdf", "*.svg"):
            for fpath in glob.glob(os.path.join(temp_dir, pattern)):
                try:
                    os.remove(fpath)
                    temp_files_cleaned += 1
                except OSError:
                    pass
        if temp_files_cleaned:
            logger.info(f"Cleaned {temp_files_cleaned} temp files from {temp_dir}")
    except Exception as e:
        logger.warning(f"Failed to clean temp files: {e}")

    return {
        "status": "reset",
        "application_seq": application_seq,
        "checkpoint_cleared": checkpoint_cleared,
        "temp_files_cleaned": temp_files_cleaned,
    }


# ── File Endpoints ───────────────────────────────────

@app.get("/api/files/{file_id}")
async def download_file(
    file_id: str,
    format: str = "pdf",
    _: str = Depends(verify_service_key),
):
    """
    Download a generated file (PDF, DXF, or SVG).

    Args:
        format: "pdf" (default), "dxf", or "svg"
    """
    # Format-specific download
    format_map = {
        "pdf": ("pdf", "application/pdf"),
        "dxf": ("dxf", "application/dxf"),
        "svg": ("svg", "image/svg+xml"),
    }

    if format in format_map:
        ext, media = format_map[format]
        file_path = os.path.join(settings.temp_file_dir, f"{file_id}.{ext}")
        if os.path.exists(file_path):
            return FileResponse(
                file_path,
                media_type=media,
                filename=f"SLD_{file_id}.{ext}",
            )

    # Fallback: check all formats (prefer PDF)
    for ext, media in [("pdf", "application/pdf"), ("dxf", "application/dxf"), ("svg", "image/svg+xml")]:
        file_path = os.path.join(settings.temp_file_dir, f"{file_id}.{ext}")
        if os.path.exists(file_path):
            return FileResponse(
                file_path,
                media_type=media,
                filename=f"SLD_{file_id}.{ext}",
            )

    raise HTTPException(status_code=404, detail="File not found")


@app.get("/api/files/{file_id}/svg")
async def get_svg_preview(
    file_id: str,
    _: str = Depends(verify_service_key),
):
    """
    Get SVG preview string for a generated SLD.
    """
    svg_path = os.path.join(settings.temp_file_dir, f"{file_id}.svg")
    if not os.path.exists(svg_path):
        raise HTTPException(status_code=404, detail="SVG preview not found")

    with open(svg_path, encoding="utf-8") as f:
        svg_content = f.read()

    return {"file_id": file_id, "svg": svg_content}


@app.delete("/api/files/{file_id}")
async def delete_temp_file(
    file_id: str,
    _: str = Depends(verify_service_key),
):
    """
    Delete temporary files (PDF + SVG) for a given file ID.
    Called by Spring Boot after successfully storing the accepted SLD file.
    """
    result = cleanup_temp_file(file_id)
    return {"status": "cleaned", "file_id": file_id, **result}


# ── Helpers ──────────────────────────────────────────


_SENTINEL = object()


async def _safe_anext(aiter_obj):
    """async generator의 __anext__()를 StopAsyncIteration 안전하게 래핑.

    StopAsyncIteration은 asyncio Task 안에서 RuntimeError로 변환되므로
    sentinel 값으로 대체하여 안전하게 처리한다.
    """
    try:
        return await aiter_obj.__anext__()
    except StopAsyncIteration:
        return _SENTINEL


async def _with_heartbeat(
    aiter: AsyncGenerator[dict, None],
    interval: int = 15,
) -> AsyncGenerator[dict, None]:
    """
    Wrap an async generator with periodic heartbeat events.
    Prevents WebClient ReadTimeout during long Gemini API calls
    by sending keepalive events every `interval` seconds when idle.

    CRITICAL: asyncio.wait (not wait_for) 사용.
    wait_for는 timeout 시 내부 태스크를 cancel하여 async generator를 파괴하지만,
    asyncio.wait는 태스크를 cancel하지 않고 단순히 대기만 중단한다.
    """
    aiter_obj = aiter.__aiter__()
    while True:
        task = asyncio.ensure_future(_safe_anext(aiter_obj))
        try:
            while True:
                done, _ = await asyncio.wait({task}, timeout=interval)
                if done:
                    result = task.result()
                    if result is _SENTINEL:
                        return  # Generator exhausted
                    yield result
                    break  # 다음 이벤트 대기를 위해 outer loop으로
                else:
                    # Timeout — heartbeat 전송, 태스크는 계속 실행 중
                    yield {"type": "heartbeat"}
        except Exception:
            # Task 내부 예외 전파
            if not task.done():
                task.cancel()
            raise


def _sse_event(event_name: str, data: dict) -> str:
    """Format an SSE event string."""
    return f"event: {event_name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
