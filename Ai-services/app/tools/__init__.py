"""Tools management package for AI Customer Service."""

from app.tools.base import BaseTool
from app.tools.catalog import CatalogSearchTool
from app.tools.insights import (
    CashierAnomaliesTool,
    LeakageReportTool,
    MenuMarginsTool,
    RestockSuggestionsTool,
    SalesSummaryTool,
)
from app.tools.order import OrderStatusTool
from app.tools.product_analysis import ProductAnalysisTool
from app.tools.registry import ToolRegistry


def get_default_tool_registry() -> ToolRegistry:
    """Create a default ToolRegistry with built-in tools registered."""
    registry = ToolRegistry()
    registry.register(CatalogSearchTool())
    registry.register(OrderStatusTool())
    registry.register(SalesSummaryTool())
    registry.register(RestockSuggestionsTool())
    registry.register(CashierAnomaliesTool())
    registry.register(LeakageReportTool())
    registry.register(MenuMarginsTool())
    registry.register(ProductAnalysisTool())
    return registry


__all__ = [
    "BaseTool",
    "CashierAnomaliesTool",
    "CatalogSearchTool",
    "LeakageReportTool",
    "MenuMarginsTool",
    "OrderStatusTool",
    "ProductAnalysisTool",
    "RestockSuggestionsTool",
    "SalesSummaryTool",
    "ToolRegistry",
    "get_default_tool_registry",
]
