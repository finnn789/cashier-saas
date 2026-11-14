"""Tools management package for AI Customer Service."""

from app.tools.base import BaseTool
from app.tools.catalog import CatalogSearchTool
from app.tools.insights import CashierAnomaliesTool, RestockSuggestionsTool, SalesSummaryTool
from app.tools.order import OrderStatusTool
from app.tools.registry import ToolRegistry


def get_default_tool_registry() -> ToolRegistry:
    """Create a default ToolRegistry with built-in tools registered."""
    registry = ToolRegistry()
    registry.register(CatalogSearchTool())
    registry.register(OrderStatusTool())
    registry.register(SalesSummaryTool())
    registry.register(RestockSuggestionsTool())
    registry.register(CashierAnomaliesTool())
    return registry


__all__ = [
    "BaseTool",
    "CashierAnomaliesTool",
    "CatalogSearchTool",
    "OrderStatusTool",
    "RestockSuggestionsTool",
    "SalesSummaryTool",
    "ToolRegistry",
    "get_default_tool_registry",
]
