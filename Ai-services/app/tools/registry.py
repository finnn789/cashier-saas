"""Tool registry for registering and dispatching AI tools."""

import json
import logging
import time
from typing import Any

from app.tools.base import BaseTool

logger = logging.getLogger(__name__)


class ToolRegistry:
    """Manages registration, discovery, and execution of AI tools."""

    def __init__(self) -> None:
        self._tools: dict[str, BaseTool] = {}

    def register(self, tool: BaseTool) -> None:
        """Register a new tool instance."""
        if tool.name in self._tools:
            raise ValueError(f"Tool with name '{tool.name}' is already registered.")
        self._tools[tool.name] = tool
        logger.info(
            "Tool registered",
            extra={"event": "tool_registered", "tool_name": tool.name},
        )

    def get_tool(self, name: str) -> BaseTool | None:
        """Retrieve a registered tool by name."""
        return self._tools.get(name)

    def get_openrouter_schemas(self) -> list[dict[str, Any]]:
        """Return the list of tool definitions ready to send to OpenRouter API."""
        return [tool.to_openrouter_schema() for tool in self._tools.values()]

    async def dispatch(
        self,
        *,
        tool_name: str,
        arguments_json: str,
        tenant_id: str,
        user_id: str | None = None,
    ) -> dict[str, Any]:
        """Parse arguments and securely execute the designated tool."""
        tool = self.get_tool(tool_name)
        if not tool:
            logger.warning(
                "Tool not found",
                extra={"event": "tool_not_found", "tool_name": tool_name},
            )
            return {"error": f"Tool '{tool_name}' not found."}

        try:
            kwargs = json.loads(arguments_json) if arguments_json else {}
            if not isinstance(kwargs, dict):
                return {"error": "Invalid tool arguments: must be a JSON object."}
        except json.JSONDecodeError:
            return {"error": "Malformed JSON arguments."}

        started = time.perf_counter()
        logger.info(
            "Tool dispatch started",
            extra={"event": "tool_dispatch_started", "tool_name": tool_name},
        )
        try:
            kwargs.pop("user_id", None)  # trusted context only, never model-supplied
            result = await tool.execute(tenant_id=tenant_id, user_id=user_id, **kwargs)
            logger.info(
                "Tool dispatch finished",
                extra={
                    "event": "tool_dispatch_finished",
                    "tool_name": tool_name,
                    "elapsed_ms": int((time.perf_counter() - started) * 1000),
                },
            )
            return {"success": True, "data": result}
        except Exception as e:
            logger.error(
                "Failed to execute tool %s: %s",
                tool_name,
                str(e),
                extra={
                    "event": "tool_dispatch_failed",
                    "tool_name": tool_name,
                    "error_type": type(e).__name__,
                    "elapsed_ms": int((time.perf_counter() - started) * 1000),
                },
            )
            return {"error": "Tool execution failed."}
