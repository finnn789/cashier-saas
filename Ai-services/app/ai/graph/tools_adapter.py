"""Adapter bridging Cashier SaaS BaseTool instances into LangChain StructuredTools."""

import asyncio
import concurrent.futures
import logging
from contextvars import ContextVar
from typing import Any

from langchain_core.tools import StructuredTool
from pydantic import BaseModel, Field, create_model

from app.tools.base import BaseTool
from app.tools.registry import ToolRegistry

logger = logging.getLogger(__name__)

# ContextVar storing tenant_id for the current execution context
current_tenant_id: ContextVar[str] = ContextVar("current_tenant_id", default="")


def json_schema_to_pydantic_model(
    model_name: str,
    parameters: dict[str, Any] | None,
) -> type[BaseModel]:
    """Convert JSON Schema parameters dictionary into a Pydantic BaseModel.

    tenant_id is strictly filtered out so that LLMs cannot view or tamper with
    tenant context.
    """
    if not parameters or not isinstance(parameters, dict):
        return create_model(model_name)

    properties: dict[str, Any] = parameters.get("properties", {})
    required: set[str] = set(parameters.get("required", []))

    # Strict tenant isolation guard: never expose tenant_id to LLM schema
    safe_properties = {k: v for k, v in properties.items() if k != "tenant_id"}
    safe_required = {r for r in required if r != "tenant_id"}

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
        # Never pass tenant_id from kwargs, only from trusted context
        safe_kwargs = {k: v for k, v in kwargs.items() if k != "tenant_id"}
        return await tool.execute(tenant_id=effective_tenant_id, **safe_kwargs)

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

    return tools


def adapt_tool_registry_to_dict(
    registry: ToolRegistry | list[BaseTool] | dict[str, BaseTool],
    tenant_id: str | None = None,
) -> dict[str, StructuredTool]:
    """Convert registered BaseTools into a dictionary mapping tool name to StructuredTool."""
    structured_tools = adapt_tool_registry(registry, tenant_id=tenant_id)
    return {t.name: t for t in structured_tools}
