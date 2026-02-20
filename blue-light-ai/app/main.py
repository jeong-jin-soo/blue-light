"""
SLD AI Agent — FastAPI Application

Provides AI-powered Single Line Diagram generation for LicenseKaki.
Communicates with the Spring Boot backend via REST/SSE.
"""

import json
import logging
import os
import uuid
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import Depends, FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, StreamingResponse

from app.agent.graph import get_agent, process_message
from app.agent.checkpointer import get_checkpointer
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

    yield

    # Shutdown
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
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)


# ── Health Check ─────────────────────────────────────

@app.get("/health", response_model=HealthResponse)
async def health():
    """Health check endpoint."""
    return HealthResponse()


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
        try:
            # Send thread_id first
            yield _sse_event("session", {"type": "session", "thread_id": thread_id})

            async for event in process_message(
                application_seq=request.application_seq,
                user_seq=request.user_seq,
                message=request.message,
                thread_id=thread_id,
                application_info=request.application_info,
            ):
                event_type = event.get("type", "token")
                yield _sse_event(event_type, event)

            # Signal completion
            yield _sse_event("done", {"type": "done"})

        except Exception as e:
            logger.error(f"Chat stream error: {e}", exc_info=True)
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
    Clears the LangGraph checkpoint (SQLite).
    """
    thread_id = f"sld-{application_seq}"
    logger.info(f"Chat reset: application_seq={application_seq}, thread_id={thread_id}")

    try:
        checkpointer = await get_checkpointer()
        # AsyncSqliteSaver stores data in 'checkpoints' and 'checkpoint_writes' tables
        if hasattr(checkpointer, "conn"):
            await checkpointer.conn.execute(
                "DELETE FROM checkpoints WHERE thread_id = ?", (thread_id,)
            )
            await checkpointer.conn.execute(
                "DELETE FROM checkpoint_writes WHERE thread_id = ?", (thread_id,)
            )
            await checkpointer.conn.commit()
            logger.info(f"Checkpoint cleared: thread_id={thread_id}")
    except Exception as e:
        logger.warning(f"Failed to clear checkpoint (non-critical): {e}")

    return {"status": "reset", "application_seq": application_seq}


# ── File Endpoints ───────────────────────────────────

@app.get("/api/files/{file_id}")
async def download_file(
    file_id: str,
    _: str = Depends(verify_service_key),
):
    """
    Download a generated file (DXF or SVG).
    """
    # Check temp directory for the file
    for ext in ["dxf", "svg"]:
        file_path = os.path.join(settings.temp_file_dir, f"{file_id}.{ext}")
        if os.path.exists(file_path):
            media_type = "application/dxf" if ext == "dxf" else "image/svg+xml"
            return FileResponse(
                file_path,
                media_type=media_type,
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


# ── Helpers ──────────────────────────────────────────

def _sse_event(event_name: str, data: dict) -> str:
    """Format an SSE event string."""
    return f"event: {event_name}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
