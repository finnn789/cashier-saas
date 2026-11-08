"""Node functions and handlers for LangGraph customer service workflow."""

import json
import logging
import time
from typing import Any, Callable

from langchain_core.messages import AIMessage, BaseMessage, SystemMessage, ToolMessage
from pydantic import SecretStr
from langchain_openai import ChatOpenAI

from app.ai.graph.error_codes import classify_llm_error
from app.ai.graph.state import AgentState
from app.ai.graph.tools_adapter import (
    adapt_tool_registry,
    current_tenant_id,
)
from app.ai.prompts import SYSTEM
from app.tools.base import BaseTool
from app.tools.registry import ToolRegistry

logger = logging.getLogger(__name__)


def create_agent_node(
    openrouter_key: str,
    ai_model: str,
    tools: ToolRegistry | list[BaseTool] | None = None,
) -> Callable[[AgentState], Any]:
    """Factory creating an agent node using ChatOpenAI configured for OpenRouter.

    Args:
        openrouter_key: OpenRouter API key for authentication.
        ai_model: OpenRouter model identifier string.
        tools: Optional ToolRegistry or list of BaseTools to bind.

    Returns:
        Async agent node function accepting AgentState.
    """
    llm = ChatOpenAI(
        model=ai_model or "google/gemini-2.5-flash",
        api_key=SecretStr(openrouter_key or "sk-dummy"),
        base_url="https://openrouter.ai/api/v1",
        temperature=0.2,
        default_headers={
            "HTTP-Referer": "https://cashier-saas.local",
            "X-Title": "Cashier SaaS AI Customer Service",
        },
    )

    if tools is not None:
        lc_tools = adapt_tool_registry(tools)
        llm_with_tools = llm.bind_tools(lc_tools) if lc_tools else llm
    else:
        llm_with_tools = llm

    async def agent_node_fn(state: AgentState) -> dict[str, Any]:
        """Invoke LLM with conversation history and bound tools."""
        raw_messages: list[BaseMessage] = list(state.get("messages", []))

        # Ensure SystemMessage is present at index 0
        if not raw_messages or not isinstance(raw_messages[0], SystemMessage):
            system_content = SYSTEM
            display_name = state.get("display_name")
            if display_name:
                profile_json = json.dumps(
                    {"untrusted_profile": {"display_name": str(display_name)[:160]}},
                    ensure_ascii=True,
                )
                system_content = f"{SYSTEM}\n{profile_json}"
            messages_to_send = [SystemMessage(content=system_content)] + raw_messages
        else:
            messages_to_send = raw_messages

        started = time.perf_counter()
        logger.info(
            "Agent node started",
            extra={
                "event": "agent_node_started",
                "messages_count": len(messages_to_send),
                "tools_bound": llm_with_tools is not llm,
            },
        )
        try:
            response: AIMessage = await llm_with_tools.ainvoke(messages_to_send)

            tool_calls = getattr(response, "tool_calls", None)
            logger.info(
                "Agent node finished",
                extra={
                    "event": "agent_node_finished",
                    "elapsed_ms": int((time.perf_counter() - started) * 1000),
                    "tool_calls_count": len(tool_calls) if tool_calls else 0,
                    "tool_names": [
                        str(call.get("name", "")) for call in (tool_calls or [])
                    ],
                    "has_final_response": not tool_calls,
                },
            )
            if not tool_calls:
                content_text = (
                    response.content
                    if isinstance(response.content, str)
                    else str(response.content)
                )
                return {
                    "messages": [response],
                    "final_response": content_text,
                    "error_code": None,
                }

            return {
                "messages": [response],
            }
        except Exception as exc:
            code = classify_llm_error(exc)
            logger.error(
                "LLM agent node invocation failed: %s",
                exc,
                exc_info=True,
                extra={
                    "event": "llm_invocation_failed",
                    "error_code": code,
                    "error_type": type(exc).__name__,
                },
            )
            return {"error_code": code}

    return agent_node_fn


def create_tools_node(
    tools: ToolRegistry | list[BaseTool] | None = None,
) -> Callable[[AgentState], Any]:
    """Factory creating a tools node that dispatches tool calls with tenant isolation.

    Args:
        tools: ToolRegistry or list of BaseTools available for dispatch.

    Returns:
        Async tools node function accepting AgentState and returning ToolMessages.
    """
    tools_by_name: dict[str, BaseTool] = {}
    if isinstance(tools, ToolRegistry):
        tools_by_name = dict(tools._tools)
    elif isinstance(tools, list):
        tools_by_name = {t.name: t for t in tools if isinstance(t, BaseTool)}
    elif isinstance(tools, dict):
        tools_by_name = {
            k: v for k, v in tools.items() if isinstance(v, BaseTool)
        }

    async def tools_node_fn(state: AgentState) -> dict[str, Any]:
        """Dispatch tool calls safely and return ToolMessage entries."""
        messages = state.get("messages", [])
        if not messages:
            return {"messages": []}

        last_message = messages[-1]
        tool_calls = getattr(last_message, "tool_calls", None)
        if not tool_calls:
            return {"messages": []}

        tenant_id = (state.get("tenant_id") or "").strip()
        tool_messages: list[ToolMessage] = []
        node_started = time.perf_counter()
        logger.info(
            "Tools node started",
            extra={"event": "tools_node_started", "tool_calls_count": len(tool_calls)},
        )

        for call in tool_calls:
            call_started = time.perf_counter()
            tool_name = call.get("name", "")
            raw_args = call.get("args") or {}
            tool_call_id = call.get("id", "")

            # Ensure arguments are a dict
            if isinstance(raw_args, str):
                try:
                    tool_args = json.loads(raw_args)
                except json.JSONDecodeError:
                    tool_args = {}
            elif isinstance(raw_args, dict):
                tool_args = dict(raw_args)
            else:
                tool_args = {}

            # Strict tenant isolation guard: purge tenant_id from model-supplied arguments
            safe_args = {k: v for k, v in tool_args.items() if k != "tenant_id"}

            tool = tools_by_name.get(tool_name)
            if tool is None:
                logger.warning(
                    "Tool call finished",
                    extra={
                        "event": "tool_call_finished",
                        "tool_name": tool_name,
                        "elapsed_ms": int((time.perf_counter() - call_started) * 1000),
                        "outcome": "not_found",
                    },
                )
                err_payload = {"error": f"Tool '{tool_name}' not found."}
                tool_messages.append(
                    ToolMessage(
                        content=json.dumps(err_payload),
                        tool_call_id=tool_call_id,
                        name=tool_name,
                    )
                )
                continue

            try:
                token = current_tenant_id.set(tenant_id)
                try:
                    result = await tool.execute(tenant_id=tenant_id, **safe_args)
                finally:
                    current_tenant_id.reset(token)

                if isinstance(result, str):
                    content = result
                else:
                    content = json.dumps(result, ensure_ascii=False)

                tool_messages.append(
                    ToolMessage(
                        content=content,
                        tool_call_id=tool_call_id,
                        name=tool_name,
                    )
                )
                logger.info(
                    "Tool call finished",
                    extra={
                        "event": "tool_call_finished",
                        "tool_name": tool_name,
                        "elapsed_ms": int((time.perf_counter() - call_started) * 1000),
                        "outcome": "ok",
                        "result_chars": len(content),
                    },
                )
            except Exception as exc:
                logger.error(
                    "Tool execution error for tool '%s': %s",
                    tool_name,
                    str(exc),
                    exc_info=True,
                    extra={
                        "event": "tool_call_failed",
                        "tool_name": tool_name,
                        "error_type": type(exc).__name__,
                    },
                )
                logger.warning(
                    "Tool call finished",
                    extra={
                        "event": "tool_call_finished",
                        "tool_name": tool_name,
                        "elapsed_ms": int((time.perf_counter() - call_started) * 1000),
                        "outcome": "error",
                    },
                )
                err_payload = {
                    "error": f"Execution of tool '{tool_name}' encountered an error.",
                    "message": "Sistem sedang tidak dapat mengakses informasi ini.",
                }
                tool_messages.append(
                    ToolMessage(
                        content=json.dumps(err_payload, ensure_ascii=False),
                        tool_call_id=tool_call_id,
                        name=tool_name,
                    )
                )

        logger.info(
            "Tools node finished",
            extra={
                "event": "tools_node_finished",
                "elapsed_ms": int((time.perf_counter() - node_started) * 1000),
            },
        )
        return {"messages": tool_messages}

    return tools_node_fn


async def agent_node(state: AgentState) -> dict[str, Any]:
    """Default agent node function using environment settings and default tools."""
    from app.ai.config import load_ai_settings
    from app.tools import get_default_tool_registry

    settings = load_ai_settings()
    registry = get_default_tool_registry()
    node_fn = create_agent_node(
        openrouter_key=settings.openrouter_key,
        ai_model=settings.ai_model,
        tools=registry,
    )
    return await node_fn(state)


async def tools_node(state: AgentState) -> dict[str, Any]:
    """Default tools node function using default tools registry."""
    from app.tools import get_default_tool_registry

    registry = get_default_tool_registry()
    node_fn = create_tools_node(tools=registry)
    return await node_fn(state)
