"""Adapter bridging Cashier SaaS BaseTool instances into LangChain StructuredTools."""

import asyncio
import concurrent.futures
import logging
import time
from contextvars import ContextVar
from typing import Any

from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field, create_model

from app.tools.base import BaseTool
from app.tools.registry import ToolRegistry

logger = logging.getLogger(__name__)

# ContextVar storing tenant_id for the current execution context
current_tenant_id: ContextVar[str] = ContextVar("current_tenant_id", default="")
# ContextVar storing the verified Java user id (sent as user_id to the backend)
current_java_user_id: ContextVar[str] = ContextVar("current_java_user_id", default="")


def json_schema_to_pydantic_model(
    model_name: str,
    parameters: dict[str, Any] | None,
) -> type[BaseModel]:
    """Convert JSON Schema parameters dictionary into a Pydantic BaseModel.

    tenant_id and user_id are strictly filtered out so that LLMs cannot view or
    tamper with tenant or user context.
    """
    if not parameters or not isinstance(parameters, dict):
        return create_model(model_name)

    properties: dict[str, Any] = parameters.get("properties", {})
    required: set[str] = set(parameters.get("required", []))

    # Strict isolation guard: never expose tenant_id/user_id to LLM schema
    hidden = {"tenant_id", "user_id"}
    safe_properties = {k: v for k, v in properties.items() if k not in hidden}
    safe_required = {r for r in required if r not in hidden}

    type_map: dict[str, Any] = {
        "string": str,
        "integer": int,
        "number": float,
        "boolean": bool,
        "object": dict,
        "array": list,
    }

    fields: dict[str, Any] = {}
    for prop_name, prop_def in safe_properties.items():
        if not isinstance(prop_def, dict):
            prop_def = {}
        raw_type = prop_def.get("type", "string")
        py_type = type_map.get(raw_type, Any)
        description = prop_def.get("description", "")

        if prop_name in safe_required:
            fields[prop_name] = (py_type, Field(description=description))
        else:
            fields[prop_name] = (
                py_type | None,
                Field(default=None, description=description),
            )

    return create_model(model_name, **fields)


def adapt_tool(
    tool: BaseTool,
    tenant_id: str | None = None,
) -> StructuredTool:
    """Adapt a domain BaseTool into a LangChain StructuredTool.

    Args:
        tool: Instance of BaseTool (e.g. CatalogSearchTool, OrderStatusTool).
        tenant_id: Optional static tenant_id. If omitted, tenant_id is resolved
            at invocation time from current_tenant_id ContextVar.

    Returns:
        StructuredTool compatible with LangChain ChatOpenAI and LangGraph.
    """
    args_schema = json_schema_to_pydantic_model(
        f"{tool.name}_args",
        tool.parameters,
    )

    async def _async_exec(**kwargs: Any) -> Any:
        effective_tenant_id = (tenant_id or current_tenant_id.get()).strip()
        if not effective_tenant_id:
            raise ValueError(
                f"Tenant ID context is required to execute tool '{tool.name}' "
                "but was not provided in static binding or current_tenant_id context."
            )
        # Never pass tenant_id/user_id from kwargs, only from trusted context
        safe_kwargs = {k: v for k, v in kwargs.items() if k not in ("tenant_id", "user_id")}
        effective_user_id = current_java_user_id.get().strip() or None
        started = time.perf_counter()
        logger.info(
            "Adapted tool execution started",
            extra={"event": "adapted_tool_started", "tool_name": tool.name},
        )
        try:
            result = await tool.execute(
                tenant_id=effective_tenant_id, user_id=effective_user_id, **safe_kwargs
            )
        except Exception as exc:
            logger.warning(
                "Adapted tool execution failed",
                extra={
                    "event": "adapted_tool_finished",
                    "tool_name": tool.name,
                    "outcome": "error",
                    "error_type": type(exc).__name__,
                    "elapsed_ms": int((time.perf_counter() - started) * 1000),
                },
            )
            raise
        logger.info(
            "Adapted tool execution finished",
            extra={
                "event": "adapted_tool_finished",
                "tool_name": tool.name,
                "outcome": "ok",
                "elapsed_ms": int((time.perf_counter() - started) * 1000),
            },
        )
        return result

    def _sync_exec(**kwargs: Any) -> Any:
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            loop = None

        if loop and loop.is_running():
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
                return pool.submit(asyncio.run, _async_exec(**kwargs)).result()
        return asyncio.run(_async_exec(**kwargs))

    return StructuredTool.from_function(
        func=_sync_exec,
        coroutine=_async_exec,
        name=tool.name,
        description=tool.description,
        args_schema=args_schema,
    )


def adapt_tool_registry(
    registry: ToolRegistry | list[BaseTool] | dict[str, BaseTool],
    tenant_id: str | None = None,
) -> list[StructuredTool]:
    """Convert registered BaseTools into a list of LangChain StructuredTools."""
    tools: list[StructuredTool] = []
    if isinstance(registry, ToolRegistry):
        source_tools = list(registry._tools.values())
    elif isinstance(registry, dict):
        source_tools = list(registry.values())
    elif isinstance(registry, list):
        source_tools = registry
    else:
        return []

    for tool in source_tools:
        if isinstance(tool, BaseTool):
            tools.append(adapt_tool(tool, tenant_id=tenant_id))

    logger.info(
        "Tools adapted",
        extra={
            "event": "tools_adapted",
            "tools_count": len(tools),
            "tool_names": [t.name for t in tools],
        },
    )
    return tools


def adapt_tool_registry_to_dict(
    registry: ToolRegistry | list[BaseTool] | dict[str, BaseTool],
    tenant_id: str | None = None,
) -> dict[str, StructuredTool]:
    """Convert registered BaseTools into a dictionary mapping tool name to StructuredTool."""
    structured_tools = adapt_tool_registry(registry, tenant_id=tenant_id)
    return {t.name: t for t in structured_tools}
