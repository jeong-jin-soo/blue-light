"""
Pydantic request/response models for the SLD AI service.
"""

from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    """Incoming chat message from Spring Boot."""

    application_seq: int = Field(..., description="Application ID")
    user_seq: int = Field(..., description="User ID (LEW)")
    message: str = Field(..., min_length=1, max_length=4000, description="User message")
    thread_id: str | None = Field(None, description="Conversation thread ID for resuming")
    application_info: dict | None = Field(
        None,
        description="Application details from Spring Boot (kVA, address, building type, etc.)",
    )
    system_prompt: str | None = Field(
        None,
        description="SLD system prompt from DB (overrides default if provided)",
    )
    api_key: str | None = Field(
        None,
        description="Gemini API key from DB (passed by Spring Boot)",
    )
    attached_file: dict | None = Field(
        None,
        description="Attached file for circuit schedule extraction: "
        '{"filename": "xxx.xlsx", "content_base64": "...", "mime_type": "..."}',
    )


class ChatResponse(BaseModel):
    """Non-streaming chat response."""

    thread_id: str
    message: str
    phase: str = "gathering"
    has_file: bool = False
    file_id: str | None = None


class ResetRequest(BaseModel):
    """Reset conversation state."""

    application_seq: int


class FileInfo(BaseModel):
    """Generated file information."""

    file_id: str
    file_name: str
    file_type: str  # "dxf" or "svg"
    file_size: int


class HealthResponse(BaseModel):
    """Health check response."""

    status: str = "healthy"
    service: str = "sld-agent"
