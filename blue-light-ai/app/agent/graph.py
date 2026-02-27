"""
LangGraph StateGraph for the SLD AI Agent.

Manages the conversation flow:
  gathering → reviewing → generating → revising → END
"""

import json
import logging
import os
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

# 모듈 레벨 LLM 캐싱 (매 호출마다 재생성 방지)
_cached_llm = None


def _get_llm() -> ChatGoogleGenerativeAI:
    """Get or create the Gemini LLM instance with tool bindings (cached)."""
    global _cached_llm
    if _cached_llm is None:
        llm = ChatGoogleGenerativeAI(
            model=settings.gemini_model,
            google_api_key=settings.gemini_api_key,
            max_output_tokens=settings.gemini_max_tokens,
            temperature=settings.gemini_temperature,
        )
        _cached_llm = llm.bind_tools(ALL_TOOLS)
        logger.info(f"LLM instance created: model={settings.gemini_model}")
    return _cached_llm


# ── Graph Nodes ─────────────────────────────────────


async def agent_node(state: SldAgentState) -> dict:
    """
    Main agent node — calls the LLM with the current state.
    The LLM decides whether to call tools or respond to the user.

    Dynamically builds the system message with application context
    so the agent already knows kVA, address, building type, etc.
    """
    llm = _get_llm()

    # Build dynamic system message with application context
    # DB에서 전달된 프롬프트가 있으면 사용, 없으면 하드코딩 기본값 사용
    app_info = state.get("application_info", {})
    custom_prompt = state.get("system_prompt")
    system_content = custom_prompt if custom_prompt else SLD_EXPERT_SYSTEM_PROMPT
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
    system_prompt: str | None = None,
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
        "system_prompt": system_prompt,
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

                # Check if SLD was generated or preview requested
                if tool_name in ("generate_sld", "generate_preview"):
                    try:
                        result = json.loads(output)
                        if result.get("success"):
                            file_id = result.get("file_id", "")

                            # Reconstruct SVG path from file_id (tool no longer returns SVG content to keep LLM context clean)
                            svg_path = os.path.join(settings.temp_file_dir, f"{file_id}.svg") if file_id else ""

                            if svg_path:
                                try:
                                    with open(svg_path, encoding="utf-8") as f:
                                        svg_content = f.read()
                                    if svg_content:
                                        yield {
                                            "type": "sld_preview",
                                            "svg": svg_content,
                                        }
                                except FileNotFoundError:
                                    logger.warning(f"SVG file not found: {svg_path}")

                            # Send file generated notification (only for generate_sld)
                            if tool_name == "generate_sld":
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
    """Human-readable description for tool execution (with pipeline step numbers)."""
    descriptions = {
        "get_application_details": "[Step 1/5] 신청 정보 및 규격 조회 중...",
        "get_standard_specs": "[Step 1/5] 싱가포르 전기 규격 조회 중...",
        "extract_sld_data": "[Step 1-2/5] SLD 데이터 추출 및 구조화 중...",
        "validate_sld_requirements": "[Step 3/5] SS 638 규격 검증 중...",
        "generate_sld": "[Step 4/5] SLD 도면 생성 중 (PDF + SVG)...",
        "generate_preview": "[Step 5/5] SLD 미리보기 생성 중...",
    }
    return descriptions.get(tool_name, f"Executing {tool_name}...")


def _summarize_tool_result(tool_name: str, output: str) -> str:
    """Create a concise summary of tool output for the frontend."""
    try:
        data = json.loads(output)

        if tool_name == "get_application_details":
            tiers = data.get("available_tiers", [])
            if tiers:
                return f"[Step 1] Standards loaded ({len(tiers)} tiers: {', '.join(str(t) for t in tiers)} kVA)"
            return "[Step 1] Application context loaded"

        if tool_name == "get_standard_specs":
            kva = data.get("kva", "?")
            breaker = data.get("main_breaker", {})
            return f"[Step 1] {kva} kVA: {breaker.get('type', '?')} {breaker.get('rating_A', '?')}A"

        if tool_name == "validate_sld_requirements":
            valid = data.get("valid", False)
            missing = len(data.get("missing_fields", []))
            errors = len(data.get("errors", []))
            warnings = len(data.get("warnings", []))
            if valid:
                suffix = f" ({warnings} warning(s))" if warnings else ""
                return f"[Step 3] ✅ Validated{suffix}"
            parts = []
            if missing:
                parts.append(f"{missing} missing")
            if errors:
                parts.append(f"{errors} error(s)")
            return f"[Step 3] ❌ {', '.join(parts)}"

        if tool_name == "generate_sld":
            if data.get("success"):
                count = data.get("component_count", 0)
                return f"[Step 4] ✅ Generated ({count} components)"
            return f"[Step 4] ❌ {data.get('error', 'unknown')}"

        if tool_name == "generate_preview":
            if data.get("success"):
                return "[Step 5] Preview ready"
            return "[Step 5] Preview failed"

        if tool_name == "extract_sld_data":
            if data.get("success"):
                validation = data.get("validation", {})
                errors = len(validation.get("errors", []))
                warnings = len(validation.get("warnings", []))
                circuits = len(data.get("extracted", {}).get("outgoing_circuits", []))
                if errors:
                    return f"[Step 1-2] Extracted ({circuits} circuits) — {errors} error(s) need attention"
                return f"[Step 1-2] ✅ Extracted ({circuits} circuits, {warnings} warning(s))"
            return "[Step 1-2] ❌ Extraction failed"

    except (json.JSONDecodeError, TypeError):
        pass

    return output[:100] if len(output) > 100 else output
