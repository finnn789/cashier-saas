"""Unit tests for tool definitions and registry."""

import json
import unittest
from unittest.mock import AsyncMock, Mock

from app.ai.graph.tools_adapter import json_schema_to_pydantic_model
from app.integrations.grpc.client import CashierGrpcClient
from app.tools import CatalogSearchTool, OrderStatusTool, ToolRegistry, get_default_tool_registry


class ToolsTests(unittest.IsolatedAsyncioTestCase):
    async def test_tool_schema_hides_tenant_and_user(self) -> None:
        for tool in (CatalogSearchTool(), OrderStatusTool()):
            schema = tool.to_openrouter_schema()
            self.assertEqual(schema["type"], "function")
            properties = schema["function"]["parameters"]["properties"]
            self.assertNotIn("tenant_id", properties)
            self.assertNotIn("user_id", properties)

    def test_adapter_schema_drops_tenant_and_user(self) -> None:
        model = json_schema_to_pydantic_model("t_args", {
            "type": "object",
            "properties": {"query": {"type": "string"}, "tenant_id": {"type": "string"}, "user_id": {"type": "string"}},
            "required": ["query", "tenant_id", "user_id"],
        })
        self.assertEqual(set(model.model_fields), {"query"})

    async def test_registry_registration_and_dispatch(self) -> None:
        client = Mock(spec=CashierGrpcClient)
        client.get_catalog_items = AsyncMock(return_value=[])
        registry = ToolRegistry()
        tool = CatalogSearchTool(client=client)
        registry.register(tool)

        # Duplicate registration raises ValueError
        with self.assertRaises(ValueError):
            registry.register(tool)

        schemas = registry.get_openrouter_schemas()
        self.assertEqual(len(schemas), 1)
        self.assertEqual(schemas[0]["function"]["name"], "search_catalog_menu")

        # Dispatch valid tool: user_id comes from the trusted argument, never from the model
        dispatch_res = await registry.dispatch(
            tool_name="search_catalog_menu",
            arguments_json=json.dumps({"query": "kopi", "user_id": "attacker"}),
            tenant_id="tenant-123",
            user_id="user-1",
        )
        self.assertTrue(dispatch_res["success"])
        client.get_catalog_items.assert_awaited_once_with(
            tenant_id="tenant-123", user_id="user-1", query="kopi"
        )

        # Dispatch unknown tool
        unknown_res = await registry.dispatch(
            tool_name="unknown_tool",
            arguments_json="{}",
            tenant_id="tenant-123",
        )
        self.assertIn("error", unknown_res)

        # Dispatch malformed json
        bad_json_res = await registry.dispatch(
            tool_name="search_catalog_menu",
            arguments_json="invalid json",
            tenant_id="tenant-123",
        )
        self.assertIn("error", bad_json_res)

    async def test_default_tool_registry(self) -> None:
        registry = get_default_tool_registry()
        self.assertIsNone(registry.get_tool("check_laptop_cpu_usage"))
        self.assertIsNotNone(registry.get_tool("search_catalog_menu"))
        self.assertIsNotNone(registry.get_tool("check_order_status"))
        for name in ("sales_summary", "restock_suggestions", "cashier_anomalies"):
            self.assertIsNotNone(registry.get_tool(name))
        schemas = registry.get_openrouter_schemas()
        self.assertEqual(len(schemas), 5)
