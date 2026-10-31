"""Unit tests for tool definitions, registry, and system tools."""

import asyncio
import unittest

from app.tools import CatalogSearchTool, CpuUsageTool, ToolRegistry, get_default_tool_registry


class ToolsTests(unittest.IsolatedAsyncioTestCase):
    async def test_cpu_usage_tool_schema_and_execution(self) -> None:
        tool = CpuUsageTool()
        schema = tool.to_openrouter_schema()
        self.assertEqual(schema["type"], "function")
        self.assertEqual(schema["function"]["name"], "check_laptop_cpu_usage")

        result = await tool.execute(tenant_id="tenant-123")
        self.assertIn("cpu_usage_percent", result)
        self.assertIn("status", result)
        self.assertIsInstance(result["cpu_usage_percent"], float)
        self.assertGreaterEqual(result["cpu_usage_percent"], 0.0)
        self.assertLessEqual(result["cpu_usage_percent"], 100.0)

    async def test_registry_registration_and_dispatch(self) -> None:
        registry = ToolRegistry()
        tool = CpuUsageTool()
        registry.register(tool)

        # Duplicate registration raises ValueError
        with self.assertRaises(ValueError):
            registry.register(tool)

        schemas = registry.get_openrouter_schemas()
        self.assertEqual(len(schemas), 1)
        self.assertEqual(schemas[0]["function"]["name"], "check_laptop_cpu_usage")

        # Dispatch valid tool
        dispatch_res = await registry.dispatch(
            tool_name="check_laptop_cpu_usage",
            arguments_json="{}",
            tenant_id="tenant-123",
        )
        self.assertTrue(dispatch_res["success"])
        self.assertIn("cpu_usage_percent", dispatch_res["data"])

        # Dispatch unknown tool
        unknown_res = await registry.dispatch(
            tool_name="unknown_tool",
            arguments_json="{}",
            tenant_id="tenant-123",
        )
        self.assertIn("error", unknown_res)

        # Dispatch malformed json
        bad_json_res = await registry.dispatch(
            tool_name="check_laptop_cpu_usage",
            arguments_json="invalid json",
            tenant_id="tenant-123",
        )
        self.assertIn("error", bad_json_res)

    async def test_default_tool_registry(self) -> None:
        registry = get_default_tool_registry()
        self.assertIsNotNone(registry.get_tool("check_laptop_cpu_usage"))
        self.assertIsNotNone(registry.get_tool("search_catalog_menu"))
        self.assertIsNotNone(registry.get_tool("check_order_status"))
        schemas = registry.get_openrouter_schemas()
        self.assertEqual(len(schemas), 3)
