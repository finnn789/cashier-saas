"""Tools management package for AI Customer Service."""

from app.tools.base import BaseTool
from app.tools.catalog import CatalogSearchTool
from app.tools.order import OrderStatusTool
from app.tools.registry import ToolRegistry


def get_default_tool_registry() -> ToolRegistry:
    """Create a default ToolRegistry with built-in tools registered."""
    registry = ToolRegistry()
    registry.register(CatalogSearchTool())
    registry.register(OrderStatusTool())
    return registry


__all__ = [
    "BaseTool",
    "CatalogSearchTool",
    "OrderStatusTool",
    "ToolRegistry",
    "get_default_tool_registry",
]
