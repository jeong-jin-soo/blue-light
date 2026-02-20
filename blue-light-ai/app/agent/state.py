"""
LangGraph agent state schema for SLD generation.
"""

from typing import Annotated, TypedDict

from langchain_core.messages import BaseMessage
from langgraph.graph.message import add_messages


class SldAgentState(TypedDict):
    """State maintained throughout the SLD generation conversation."""

    # Conversation messages (LangGraph manages append via add_messages)
    messages: Annotated[list[BaseMessage], add_messages]

    # Application context
    application_seq: int
    user_seq: int

    # Application details from Spring Boot (kVA, address, building type, etc.)
    application_info: dict

    # Gathered SLD requirements
    sld_requirements: dict
    requirements_complete: bool

    # Generated files
    generated_dxf_path: str | None
    generated_svg: str | None
    generated_file_id: str | None

    # Conversation phase: gathering → reviewing → generating → revising
    phase: str
