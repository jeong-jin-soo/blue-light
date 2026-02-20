"""
LangGraph StateGraph for the SLD AI Agent.

Manages the conversation flow:
  gathering → reviewing → generating → revising → END
"""

import json
import logging
from typing import AsyncGenerator

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_google_genai import ChatGoogleGenerativeAI
from langgraph.graph import END, StateGraph
from langgraph.prebuilt import ToolNode

from app.agent.checkpointer import get_checkpointer
from app.agent.prompts import SLD_EXPERT_SYSTEM_PROMPT, build_application_context
from app.agent.state import SldAgentState
from app.agent.tools import ALL_TOOLS
from app.config import settings

logger = logging.getLogger(__name__)

# ── LLM Setup ───────────────────────────────────────


def _create_llm() -> ChatGoogleGenerativeAI:
    """Create the Gemini LLM instance with tool bindings."""
    llm = ChatGoogleGenerativeAI(
        model=settings.gemini_model,
        google_api_key=settings.gemini_api_key,
        max_output_tokens=settings.gemini_max_tokens,
        temperature=settings.gemini_temperature,
    )
    return llm.bind_tools(ALL_TOOLS)


# ── Graph Nodes ─────────────────────────────────────


async def agent_node(state: SldAgentState) -> dict:
    """
    Main agent node — calls the LLM with the current state.
    The LLM decides whether to call tools or respond to the user.

    Dynamically builds the system message with application context
    so the agent already knows kVA, address, building type, etc.
    """
    llm = _create_llm()

    # Build dynamic system message with application context
    app_info = state.get("application_info", {})
    system_content = SLD_EXPERT_SYSTEM_PROMPT
    if app_info:
        system_content += "\n\n" + build_application_context(app_info)

    # Ensure system message is present (replace if exists)
    messages = list(state["messages"])
    if messages and isinstance(messages[0], SystemMessage):
        messages[0] = SystemMessage(content=system_content)
    else:
        messages.insert(0, SystemMessage(content=system_content))

    response = await llm.ainvoke(messages)

    return {"messages": [response]}


def should_continue(state: SldAgentState) -> str:
    """
    Routing function: decide whether to call tools or end the turn.
    If the last message has tool_calls → go to tools node.
    Otherwise → end the conversation turn (wait for user input).
    """
    last_message = state["messages"][-1]

    if hasattr(last_message, "tool_calls") and last_message.tool_calls:
        return "tools"

    return END


# ── Graph Construction ──────────────────────────────


def build_graph() -> StateGraph:
    """Build the LangGraph StateGraph for SLD generation."""
    graph = StateGraph(SldAgentState)

    # Add nodes
    graph.add_node("agent", agent_node)
    graph.add_node("tools", ToolNode(ALL_TOOLS))

    # Set entry point
    graph.set_entry_point("agent")

    # Add edges
    graph.add_conditional_edges("agent", should_continue, {"tools": "tools", END: END})
    graph.add_edge("tools", "agent")

    return graph


# ── Compiled Agent ──────────────────────────────────

_compiled_agent = None


async def get_agent():
    """Get or create the compiled LangGraph agent with checkpointer."""
    global _compiled_agent
    if _compiled_agent is None:
        graph = build_graph()
        checkpointer = await get_checkpointer()
        _compiled_agent = graph.compile(checkpointer=checkpointer)
        logger.info("LangGraph agent compiled successfully")
    return _compiled_agent


# ── Message Processing ──────────────────────────────


async def process_message(
    application_seq: int,
    user_seq: int,
    message: str,
    thread_id: str,
    application_info: dict | None = None,
) -> AsyncGenerator[dict, None]:
    """
    Process a user message through the LangGraph agent.
    Yields SSE-compatible event dictionaries.

    Args:
        application_info: Application details from Spring Boot (kVA, address, etc.)
    """
    agent = await get_agent()

    config = {"configurable": {"thread_id": thread_id}}

    # LangGraph 체크포인터가 이전 상태를 자동으로 복원하므로,
    # 새 메시지만 전달하고 나머지 필드는 덮어쓰지 않는다.
    # application_info는 매번 Spring Boot에서 최신 정보를 받으므로 항상 갱신한다.
    input_state = {
        "messages": [HumanMessage(content=message)],
        "application_seq": application_seq,
        "user_seq": user_seq,
        "application_info": application_info or {},
    }

    try:
        async for event in agent.astream_events(
            input_state,
            config=config,
            version="v2",
        ):
            kind = event.get("event")

            # Stream LLM tokens
            if kind == "on_chat_model_stream":
                chunk = event.get("data", {}).get("chunk")
                if chunk and hasattr(chunk, "content") and chunk.content:
                    content = chunk.content
                    # Gemini may return content as list of parts
                    if isinstance(content, list):
                        text_parts = [
                            p.get("text", "") if isinstance(p, dict) else str(p)
                            for p in content
                        ]
                        content = "".join(text_parts)
                    if content:
                        yield {"type": "token", "content": content}

            # Tool call started
            elif kind == "on_tool_start":
                tool_name = event.get("name", "unknown")
                logger.info(f"Tool started: {tool_name}")
                yield {
                    "type": "tool_start",
                    "tool": tool_name,
                    "description": _tool_description(tool_name),
                }

            # Tool call completed
            elif kind == "on_tool_end":
                tool_name = event.get("name", "unknown")
                raw_output = event.get("data", {}).get("output", "")

                # ToolMessage has .content attribute; raw string otherwise
                if hasattr(raw_output, "content"):
                    output = raw_output.content
                else:
                    output = str(raw_output)

                logger.info(f"Tool completed: {tool_name}")

                # Check if SLD was generated (file_generated event)
                if tool_name == "generate_sld_dxf":
                    try:
                        result = json.loads(output)
                        if result.get("success"):
                            file_id = result.get("file_id", "")
                            svg_preview = result.get("svg_preview", "")

                            # Send SVG preview
                            if svg_preview:
                                yield {
                                    "type": "sld_preview",
                                    "svg": svg_preview,
                                }

                            # Send file generated notification
                            yield {
                                "type": "file_generated",
                                "fileId": file_id,
                            }
                    except (json.JSONDecodeError, TypeError):
                        pass

                # Send tool result summary
                yield {
                    "type": "tool_result",
                    "tool": tool_name,
                    "summary": _summarize_tool_result(tool_name, output),
                }

    except Exception as e:
        logger.error(f"Agent processing error: {e}", exc_info=True)
        yield {"type": "error", "content": f"Processing error: {e}"}


def _tool_description(tool_name: str) -> str:
    """Human-readable description for tool execution."""
    descriptions = {
        "get_application_details": "Fetching application details...",
        "get_standard_specs": "Looking up Singapore electrical standards...",
        "validate_sld_requirements": "Validating SLD requirements...",
        "generate_sld_dxf": "Generating SLD drawing (DXF + SVG)...",
        "generate_preview": "Generating SLD preview...",
    }
    return descriptions.get(tool_name, f"Executing {tool_name}...")


def _summarize_tool_result(tool_name: str, output: str) -> str:
    """Create a concise summary of tool output for the frontend."""
    try:
        data = json.loads(output)

        if tool_name == "get_application_details":
            tiers = data.get("available_tiers", [])
            if tiers:
                return f"Standards loaded ({len(tiers)} tiers: {', '.join(str(t) for t in tiers)} kVA)"
            return "Application context loaded"

        if tool_name == "get_standard_specs":
            kva = data.get("kva", "?")
            breaker = data.get("main_breaker", {})
            return f"{kva} kVA: {breaker.get('type', '?')} {breaker.get('rating_A', '?')}A"

        if tool_name == "validate_sld_requirements":
            valid = data.get("valid", False)
            missing = len(data.get("missing_fields", []))
            if valid:
                return "All requirements met"
            return f"Missing {missing} field(s)"

        if tool_name == "generate_sld_dxf":
            if data.get("success"):
                count = data.get("component_count", 0)
                return f"SLD generated ({count} components)"
            return f"Generation failed: {data.get('error', 'unknown')}"

        if tool_name == "generate_preview":
            if data.get("success"):
                return "Preview ready"
            return "Preview failed"

    except (json.JSONDecodeError, TypeError):
        pass

    return output[:100] if len(output) > 100 else output
