"""Unit tests for gRPC client integration, catalog & order tools, and registry."""

import unittest
from unittest.mock import AsyncMock, Mock, patch

import grpc
import grpc.aio

from app.integrations.grpc.client import CashierGrpcClient
from app.integrations.grpc.config import GrpcSettings
from app.integrations.grpc.generated import cashier_service_pb2
from app.tools.catalog import CatalogSearchTool
from app.tools.cpu import CpuUsageTool
from app.tools.order import OrderStatusTool
from app.tools.registry import ToolRegistry
from app.tools import get_default_tool_registry


class GrpcClientTests(unittest.IsolatedAsyncioTestCase):
    """Tests for CashierGrpcClient lifecycle, channel creation, and error handling."""

    def test_settings_defaults(self) -> None:
        settings = GrpcSettings()
        self.assertEqual(settings.host, "127.0.0.1")
        self.assertEqual(settings.port, 9090)
        self.assertEqual(settings.timeout_seconds, 5.0)
        self.assertFalse(settings.use_tls)

    async def test_client_lazy_channel_and_close(self) -> None:
        settings = GrpcSettings(host="127.0.0.1", port=9999, use_tls=False)
        client = CashierGrpcClient(settings)
        self.assertIsNone(client._channel)

        # Calling close on uninitialized channel is safe
        await client.close()
        self.assertIsNone(client._channel)

        # Context manager initializes channel and closes cleanly
        async with CashierGrpcClient(settings) as managed_client:
            channel = await managed_client.get_channel()
            self.assertIsNotNone(channel)
            self.assertIsNotNone(managed_client._stub)
        self.assertIsNone(managed_client._channel)

    async def test_catalog_offline_fallback(self) -> None:
        """When gRPC target is unreachable, client returns [] instead of raising."""
        settings = GrpcSettings(host="127.0.0.1", port=59999, timeout_seconds=0.2)
        client = CashierGrpcClient(settings)
        try:
            items = await client.get_catalog_items(tenant_id="tenant-1", query="kopi")
            self.assertEqual(items, [])
        finally:
            await client.close()

    async def test_order_status_offline_fallback(self) -> None:
        """When gRPC target is unreachable, client returns None instead of raising."""
        settings = GrpcSettings(host="127.0.0.1", port=59999, timeout_seconds=0.2)
        client = CashierGrpcClient(settings)
        try:
            status = await client.get_order_status(tenant_id="tenant-1", order_number="ORD-1")
            self.assertIsNone(status)
        finally:
            await client.close()

    async def test_catalog_rpc_error_handling(self) -> None:
        settings = GrpcSettings(host="127.0.0.1", port=9090)
        client = CashierGrpcClient(settings)
        mock_stub = Mock()
        call_mock = AsyncMock(side_effect=grpc.aio.AioRpcError(
            code=grpc.StatusCode.UNAVAILABLE,
            initial_metadata=(),
            trailing_metadata=(),
            details="Backend down",
            debug_error_string="",
        ))
        mock_stub.GetCatalogItem = call_mock
        client._stub = mock_stub
        client._channel = Mock()

        items = await client.get_catalog_items(tenant_id="tenant-1", query="mie")
        self.assertEqual(items, [])

    async def test_order_status_not_found_rpc_error(self) -> None:
        settings = GrpcSettings(host="127.0.0.1", port=9090)
        client = CashierGrpcClient(settings)
        mock_stub = Mock()
        call_mock = AsyncMock(side_effect=grpc.aio.AioRpcError(
            code=grpc.StatusCode.NOT_FOUND,
            initial_metadata=(),
            trailing_metadata=(),
            details="Order not found",
            debug_error_string="",
        ))
        mock_stub.GetOrderStatus = call_mock
        client._stub = mock_stub
        client._channel = Mock()

        status = await client.get_order_status(tenant_id="tenant-1", order_number="ORD-NONE")
        self.assertIsNone(status)


class CatalogSearchToolTests(unittest.IsolatedAsyncioTestCase):
    """Tests for CatalogSearchTool schema and execution."""

    def test_tool_schema(self) -> None:
        tool = CatalogSearchTool()
        self.assertEqual(tool.name, "search_catalog_menu")
        self.assertIn("katalog", tool.description.lower())
        schema = tool.to_openrouter_schema()
        self.assertEqual(schema["type"], "function")
        self.assertEqual(schema["function"]["name"], "search_catalog_menu")
        self.assertIn("query", schema["function"]["parameters"]["required"])

    async def test_execute_with_items_returned(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_catalog_items = AsyncMock(return_value=[
            {
                "id": "item-101",
                "name": "Es Kopi Susu Aren",
                "price": 18000,
                "is_available": True,
                "category": "BEVERAGE",
            },
            {
                "id": "item-102",
                "name": "Kopi Hitam Tubruk",
                "price": 12000,
                "is_available": True,
                "category": "BEVERAGE",
            },
        ])
        tool = CatalogSearchTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", query="kopi")

        self.assertEqual(result["query"], "kopi")
        self.assertEqual(result["total"], 2)
        self.assertEqual(len(result["items"]), 2)
        self.assertIn("Ditemukan 2 menu", result["message"])
        mock_client.get_catalog_items.assert_awaited_once_with(
            tenant_id="tenant-abc", query="kopi"
        )

    async def test_execute_empty_or_offline(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_catalog_items = AsyncMock(return_value=[])
        tool = CatalogSearchTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", query="pizza")

        self.assertEqual(result["query"], "pizza")
        self.assertEqual(result["total"], 0)
        self.assertEqual(result["items"], [])
        self.assertIn("Tidak ditemukan menu dengan kata kunci 'pizza'", result["message"])

    async def test_lazy_client_initialization(self) -> None:
        tool = CatalogSearchTool(client=None)
        self.assertIsNone(tool._client)
        with patch.object(CashierGrpcClient, "get_catalog_items", new_callable=AsyncMock) as mock_rpc:
            mock_rpc.return_value = []
            res = await tool.execute(tenant_id="tenant-xyz", query="roti")
            self.assertEqual(res["total"], 0)
            self.assertIsNotNone(tool._client)
            mock_rpc.assert_awaited_once_with(tenant_id="tenant-xyz", query="roti")


class OrderStatusToolTests(unittest.IsolatedAsyncioTestCase):
    """Tests for OrderStatusTool schema and execution."""

    def test_tool_schema(self) -> None:
        tool = OrderStatusTool()
        self.assertEqual(tool.name, "check_order_status")
        self.assertIn("status", tool.description.lower())
        schema = tool.to_openrouter_schema()
        self.assertEqual(schema["type"], "function")
        self.assertEqual(schema["function"]["name"], "check_order_status")
        self.assertIn("order_number", schema["function"]["parameters"]["required"])

    async def test_execute_order_found(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_order_status = AsyncMock(return_value={
            "tenant_id": "tenant-abc",
            "order_number": "ORD-20261022-001",
            "status": "PROCESSING",
            "payment_status": "PAID",
            "total_amount": 45000,
            "created_at": "2026-10-22T10:15:00Z",
        })
        tool = OrderStatusTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", order_number="ORD-20261022-001")

        self.assertEqual(result["order_number"], "ORD-20261022-001")
        self.assertEqual(result["status"], "PROCESSING")
        self.assertEqual(result["payment_status"], "PAID")
        self.assertEqual(result["total_amount"], 45000)
        self.assertEqual(result["created_at"], "2026-10-22T10:15:00Z")
        self.assertIn("berstatus PROCESSING", result["message"])
        self.assertIn("status pembayaran PAID", result["message"])
        mock_client.get_order_status.assert_awaited_once_with(
            tenant_id="tenant-abc", order_number="ORD-20261022-001"
        )

    async def test_execute_order_not_found_or_offline(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_order_status = AsyncMock(return_value=None)
        tool = OrderStatusTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", order_number="ORD-404")

        self.assertEqual(result["order_number"], "ORD-404")
        self.assertEqual(result["status"], "not_found_or_unavailable")
        self.assertIn("tidak ditemukan atau sistem backend sedang tidak dapat dihubungi", result["message"])

    async def test_execute_empty_order_number(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_order_status = AsyncMock()
        tool = OrderStatusTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", order_number="   ")

        self.assertEqual(result["status"], "not_found_or_unavailable")
        mock_client.get_order_status.assert_not_awaited()

    async def test_lazy_client_initialization(self) -> None:
        tool = OrderStatusTool(client=None)
        self.assertIsNone(tool._client)
        with patch.object(CashierGrpcClient, "get_order_status", new_callable=AsyncMock) as mock_rpc:
            mock_rpc.return_value = None
            res = await tool.execute(tenant_id="tenant-xyz", order_number="ORD-999")
            self.assertEqual(res["status"], "not_found_or_unavailable")
            self.assertIsNotNone(tool._client)
            mock_rpc.assert_awaited_once_with(tenant_id="tenant-xyz", order_number="ORD-999")


class DefaultToolRegistryTests(unittest.IsolatedAsyncioTestCase):
    """Tests for ToolRegistry with the default tools suite."""

    def test_default_tool_registry_contains_all_tools(self) -> None:
        registry = get_default_tool_registry()

        cpu_tool = registry.get_tool("check_laptop_cpu_usage")
        catalog_tool = registry.get_tool("search_catalog_menu")
        order_tool = registry.get_tool("check_order_status")

        self.assertIsNotNone(cpu_tool)
        self.assertIsInstance(cpu_tool, CpuUsageTool)

        self.assertIsNotNone(catalog_tool)
        self.assertIsInstance(catalog_tool, CatalogSearchTool)

        self.assertIsNotNone(order_tool)
        self.assertIsInstance(order_tool, OrderStatusTool)

        schemas = registry.get_openrouter_schemas()
        self.assertEqual(len(schemas), 3)
        names = {s["function"]["name"] for s in schemas}
        self.assertEqual(names, {"check_laptop_cpu_usage", "search_catalog_menu", "check_order_status"})

    async def test_dispatch_catalog_and_order_tools(self) -> None:
        registry = ToolRegistry()
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_catalog_items = AsyncMock(return_value=[
            {"id": "c1", "name": "Teh Manis", "price": 5000, "is_available": True, "category": "DRINK"}
        ])
        mock_client.get_order_status = AsyncMock(return_value={
            "order_number": "ORD-555",
            "status": "COMPLETED",
            "payment_status": "PAID",
            "total_amount": 5000,
            "created_at": "2026-10-22T11:00:00Z",
        })

        registry.register(CatalogSearchTool(client=mock_client))
        registry.register(OrderStatusTool(client=mock_client))

        # Dispatch catalog
        cat_res = await registry.dispatch(
            tool_name="search_catalog_menu",
            arguments_json='{"query": "teh"}',
            tenant_id="tenant-100",
        )
        self.assertTrue(cat_res["success"])
        self.assertEqual(cat_res["data"]["total"], 1)

        # Dispatch order
        ord_res = await registry.dispatch(
            tool_name="check_order_status",
            arguments_json='{"order_number": "ORD-555"}',
            tenant_id="tenant-100",
        )
        self.assertTrue(ord_res["success"])
        self.assertEqual(ord_res["data"]["status"], "COMPLETED")
