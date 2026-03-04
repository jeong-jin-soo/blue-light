"""
LangGraph StateGraph for the SLD AI Agent.

Manages the conversation flow:
  gathering → reviewing → generating → revising → END
"""

import asyncio
import base64
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
_cached_api_key: str | None = None


def _get_llm(api_key: str | None = None) -> ChatGoogleGenerativeAI:
    """Get or create the Gemini LLM instance with tool bindings (cached).

    Args:
        api_key: Gemini API key from Spring Boot (DB-managed).
                 If provided, overrides the env var setting.
                 LLM is re-created when the key changes.
    """
    global _cached_llm, _cached_api_key

    resolved_key = api_key or settings.gemini_api_key

    # Re-create LLM if key changed or not yet created
    if _cached_llm is None or resolved_key != _cached_api_key:
        llm = ChatGoogleGenerativeAI(
            model=settings.gemini_model,
            google_api_key=resolved_key,
            max_output_tokens=settings.gemini_max_tokens,
            temperature=settings.gemini_temperature,
        )
        _cached_llm = llm.bind_tools(ALL_TOOLS)
        _cached_api_key = resolved_key
        logger.info(f"LLM instance created: model={settings.gemini_model}, key_source={'db' if api_key else 'env'}")
    return _cached_llm


# ── Graph Nodes ─────────────────────────────────────


def _extract_template_info(messages: list) -> dict | None:
    """Scan messages for find_matching_templates ToolMessage and extract template info.

    Returns dict with reference_image_path, best_template_filename, similarity_score
    or None if not found.
    """
    from langchain_core.messages import ToolMessage

    for msg in reversed(messages):
        if isinstance(msg, ToolMessage) and msg.name == "find_matching_templates":
            try:
                data = json.loads(msg.content)
                image_path = data.get("reference_image_path")
                if image_path:
                    return {
                        "reference_image_path": image_path,
                        "best_template_filename": data.get("best_template_filename", ""),
                        "similarity_score": data.get("similarity_score", 0),
                    }
            except (json.JSONDecodeError, TypeError):
                pass
    return None


async def agent_node(state: SldAgentState) -> dict:
    """
    Main agent node — calls the LLM with the current state.
    The LLM decides whether to call tools or respond to the user.

    Dynamically builds the system message with application context
    so the agent already knows kVA, address, building type, etc.
    """
    llm = _get_llm(api_key=state.get("api_key"))

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

    # Inject template reference image for Gemini Vision — ONCE only.
    # After first injection, state["template_image_path"] is set to "__injected__"
    # to prevent re-sending the large base64 image on every subsequent turn.
    already_injected = state.get("template_image_path") == "__injected__"
    state_update = {}

    if not already_injected:
        template_info = _extract_template_info(messages)
        if template_info and os.path.exists(template_info["reference_image_path"]):
            try:
                with open(template_info["reference_image_path"], "rb") as f:
                    image_b64 = base64.b64encode(f.read()).decode("utf-8")

                image_text = (
                    "Below is a reference image of a real-world SLD template that closely matches "
                    "the current requirements. Use this as your BASE layout reference. "
                    "Preserve its visual structure, notation style, and component arrangement."
                )
                tmpl_filename = template_info.get("best_template_filename", "")
                tmpl_score = template_info.get("similarity_score", 0)
                if settings.environment != "production" and tmpl_filename:
                    image_text += (
                        f"\n\n📋 [DEV] Base template: \"{tmpl_filename}\" "
                        f"(similarity: {tmpl_score:.2f}). "
                        "You MUST mention this template filename in your response "
                        "so the developer can verify which template was used."
                    )

                image_msg = HumanMessage(content=[
                    {
                        "type": "text",
                        "text": image_text,
                    },
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/png;base64,{image_b64}"},
                    },
                ])
                insert_pos = 1 if messages and isinstance(messages[0], SystemMessage) else 0
                messages.insert(insert_pos, image_msg)
                # Mark as injected so subsequent agent_node calls skip image
                state_update["template_image_path"] = "__injected__"
                logger.info(f"Template reference image injected (ONCE): {template_info['reference_image_path']} "
                            f"(filename={tmpl_filename}, score={tmpl_score})")
            except Exception as e:
                logger.warning(f"Failed to inject template image: {e}")

    response = await llm.ainvoke(messages)

    return {"messages": [response], **state_update}


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
    api_key: str | None = None,
) -> AsyncGenerator[dict, None]:
    """
    Process a user message through the LangGraph agent.
    Yields SSE-compatible event dictionaries.

    Args:
        application_info: Application details from Spring Boot (kVA, address, etc.)
        api_key: Gemini API key from Spring Boot (DB-managed).
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
        "api_key": api_key,
    }

    # Retry logic for transient Gemini API errors (503 Service Unavailable)
    max_retries = 3
    retry_delay = 5  # seconds

    for attempt in range(1, max_retries + 1):
        had_error = False
        try:
            logger.info(f"Agent processing: thread_id={thread_id}, attempt={attempt}/{max_retries}")
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

                    # Capture template image path for Vision injection
                    if tool_name == "find_matching_templates":
                        try:
                            tmpl_result = json.loads(output)
                            img_path = tmpl_result.get("reference_image_path")
                            if img_path and os.path.exists(img_path):
                                # Store in state via a separate SSE event
                                # The actual state update happens via the ToolNode mechanism
                                yield {
                                    "type": "template_matched",
                                    "filename": tmpl_result.get("best_template_filename", ""),
                                    "similarity_score": tmpl_result.get("similarity_score", 0),
                                }
                        except (json.JSONDecodeError, TypeError):
                            pass

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
            # Check if this is a retryable error (Gemini 503, rate limit, etc.)
            error_str = str(e)
            is_retryable = any(code in error_str for code in ["503", "429", "UNAVAILABLE", "RESOURCE_EXHAUSTED", "overloaded"])

            if is_retryable and attempt < max_retries:
                logger.warning(f"Retryable error (attempt {attempt}/{max_retries}): {e}")
                yield {"type": "status", "content": f"AI service temporarily busy. Retrying... ({attempt}/{max_retries})"}
                await asyncio.sleep(retry_delay * attempt)  # exponential-ish backoff
                had_error = True
            else:
                if is_retryable:
                    logger.error(f"Agent processing error after {max_retries} retries: {e}")
                    yield {"type": "error", "content": "AI service is temporarily unavailable due to high demand. Please try again in a few minutes."}
                else:
                    logger.error(f"Agent processing error: {e}", exc_info=True)
                    yield {"type": "error", "content": f"Processing error: {e}"}
                return  # Exit the generator

        if not had_error:
            return  # Success — exit the retry loop


def _tool_description(tool_name: str) -> str:
    """Human-readable description for tool execution (with pipeline step numbers)."""
    descriptions = {
        "get_application_details": "[Step 1/6] Fetching application details & specifications...",
        "get_standard_specs": "[Step 1/6] Looking up Singapore electrical standards...",
        "find_matching_templates": "[Step 2/6] Searching for matching SLD templates...",
        "extract_sld_data": "[Step 1-3/6] Extracting & structuring SLD data...",
        "validate_sld_requirements": "[Step 4/6] Validating against SS 638 standards...",
        "generate_sld": "[Step 5/6] Generating SLD drawing (PDF + SVG)...",
        "generate_preview": "[Step 6/6] Creating SLD preview...",
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

        if tool_name == "find_matching_templates":
            if data.get("matched"):
                score = data.get("similarity_score", 0)
                filename = data.get("best_template_filename", "?")
                return f"[Step 2] ✅ Template matched: {filename} (score: {score})"
            return "[Step 2] No matching template found — using standards fallback"

        if tool_name == "validate_sld_requirements":
            valid = data.get("valid", False)
            missing = len(data.get("missing_fields", []))
            errors = len(data.get("errors", []))
            warnings = len(data.get("warnings", []))
            if valid:
                suffix = f" ({warnings} warning(s))" if warnings else ""
                return f"[Step 4] ✅ Validated{suffix}"
            parts = []
            if missing:
                parts.append(f"{missing} missing")
            if errors:
                parts.append(f"{errors} error(s)")
            return f"[Step 4] ❌ {', '.join(parts)}"

        if tool_name == "generate_sld":
            if data.get("success"):
                count = data.get("component_count", 0)
                return f"[Step 5] ✅ Generated ({count} components)"
            return f"[Step 5] ❌ {data.get('error', 'unknown')}"

        if tool_name == "generate_preview":
            if data.get("success"):
                return "[Step 6] Preview ready"
            return "[Step 6] Preview failed"

        if tool_name == "extract_sld_data":
            if data.get("success"):
                validation = data.get("validation", {})
                errors = len(validation.get("errors", []))
                warnings = len(validation.get("warnings", []))
                circuits = len(data.get("extracted", {}).get("outgoing_circuits", []))
                if errors:
                    return f"[Step 1-3] Extracted ({circuits} circuits) — {errors} error(s) need attention"
                return f"[Step 1-3] ✅ Extracted ({circuits} circuits, {warnings} warning(s))"
            return "[Step 1-3] ❌ Extraction failed"

    except (json.JSONDecodeError, TypeError):
        pass

    return output[:100] if len(output) > 100 else output
