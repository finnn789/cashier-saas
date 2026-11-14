"""Unit tests for gRPC client integration, catalog & order tools, and registry."""

import unittest
from decimal import Decimal
from unittest.mock import AsyncMock, Mock, patch

from app.integrations.grpc.client import CashierGrpcClient
from app.integrations.grpc.config import GrpcSettings
from app.integrations.grpc.generated import cashier_service_pb2
from app.tools.catalog import CatalogSearchTool
from app.tools.order import OrderStatusTool
from app.tools.registry import ToolRegistry
from app.tools import get_default_tool_registry

USER = "11111111-1111-1111-1111-111111111111"


class GrpcClientTests(unittest.IsolatedAsyncioTestCase):
    """Tests for CashierGrpcClient lifecycle, channel creation, and error handling."""

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


class CatalogSearchToolTests(unittest.IsolatedAsyncioTestCase):
    """Tests for CatalogSearchTool schema and execution."""

    async def test_execute_with_items_returned(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_catalog_items = AsyncMock(return_value=[
            {
                "id": "item-101",
                "name": "Es Kopi Susu Aren",
                "price": Decimal("18000.00"),
                "is_available": True,
                "category": "BEVERAGE",
            },
            {
                "id": "item-102",
                "name": "Kopi Hitam Tubruk",
                "price": Decimal("12000.00"),
                "is_available": True,
                "category": "BEVERAGE",
            },
        ])
        tool = CatalogSearchTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", user_id=USER, query="kopi")

        self.assertEqual(result["query"], "kopi")
        self.assertEqual(result["total"], 2)
        self.assertEqual(len(result["items"]), 2)
        self.assertIn("Ditemukan 2 menu", result["message"])
        mock_client.get_catalog_items.assert_awaited_once_with(
            tenant_id="tenant-abc", user_id=USER, query="kopi"
        )

    async def test_execute_without_user_id_skips_grpc(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_catalog_items = AsyncMock()
        result = await CatalogSearchTool(client=mock_client).execute(tenant_id="tenant-abc", query="kopi")

        self.assertEqual(result["total"], 0)
        self.assertEqual(result["items"], [])
        mock_client.get_catalog_items.assert_not_awaited()

    async def test_execute_empty_or_offline(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_catalog_items = AsyncMock(return_value=[])
        tool = CatalogSearchTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", user_id=USER, query="pizza")

        self.assertEqual(result["query"], "pizza")
        self.assertEqual(result["total"], 0)
        self.assertEqual(result["items"], [])
        self.assertIn("Tidak ditemukan menu dengan kata kunci 'pizza'", result["message"])

    async def test_lazy_client_initialization(self) -> None:
        cases = (
            (CatalogSearchTool, "get_catalog_items", [], {"query": "roti"}, "total", 0),
            (
                OrderStatusTool, "get_order_status", None, {"order_number": "ORD-999"},
                "status", "not_found_or_unavailable",
            ),
        )
        for tool_class, rpc, returned, arguments, key, expected in cases:
            with self.subTest(tool=tool_class.__name__):
                tool = tool_class(client=None)
                self.assertIsNone(tool._client)
                with patch.object(CashierGrpcClient, rpc, new_callable=AsyncMock) as mock_rpc:
                    mock_rpc.return_value = returned
                    res = await tool.execute(tenant_id="tenant-xyz", user_id=USER, **arguments)
                    self.assertEqual(res[key], expected)
                    self.assertIsNotNone(tool._client)
                    mock_rpc.assert_awaited_once_with(
                        tenant_id="tenant-xyz", user_id=USER, **arguments
                    )


class OrderStatusToolTests(unittest.IsolatedAsyncioTestCase):
    """Tests for OrderStatusTool schema and execution."""

    async def test_execute_order_found(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_order_status = AsyncMock(return_value={
            "tenant_id": "tenant-abc",
            "order_number": "ORD-20261022-001",
            "status": "COMPLETED",
            "payment_status": "PAID",
            "total_amount": Decimal("45000.00"),
            "created_at": "2026-10-22T10:15:00Z",
        })
        tool = OrderStatusTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", user_id=USER, order_number="ORD-20261022-001")

        self.assertEqual(result["order_number"], "ORD-20261022-001")
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(result["status_label"], "Selesai (sudah dibayar)")
        self.assertEqual(result["payment_status"], "PAID")
        self.assertEqual(result["total_amount"], "45000.00")
        self.assertEqual(result["created_at"], "2026-10-22T10:15:00Z")
        self.assertIn("berstatus Selesai (sudah dibayar)", result["message"])
        self.assertIn("status pembayaran PAID", result["message"])
        mock_client.get_order_status.assert_awaited_once_with(
            tenant_id="tenant-abc", user_id=USER, order_number="ORD-20261022-001"
        )

    async def test_execute_order_status_codes_mapped_to_text(self) -> None:
        cases = (
            ("OPEN", "Belum dibayar", "UNPAID"),
            ("COMPLETED", "Selesai (sudah dibayar)", "PAID"),
            ("CANCELLED", "Dibatalkan", "UNPAID"),
        )
        for code, label, payment in cases:
            with self.subTest(code=code):
                mock_client = Mock(spec=CashierGrpcClient)
                mock_client.get_order_status = AsyncMock(return_value={
                    "status": code,
                    "payment_status": payment,
                    "total_amount": Decimal("1000.00"),
                    "created_at": "2026-11-12T10:00:00Z",
                })
                result = await OrderStatusTool(client=mock_client).execute(
                    tenant_id="tenant-abc", user_id=USER, order_number="ORD-1"
                )
                self.assertEqual(result["status"], code)
                self.assertEqual(result["status_label"], label)
                self.assertIn(f"berstatus {label} ", result["message"])

    async def test_execute_order_not_found_or_offline(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_order_status = AsyncMock(return_value=None)
        tool = OrderStatusTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", user_id=USER, order_number="ORD-404")

        self.assertEqual(result["order_number"], "ORD-404")
        self.assertEqual(result["status"], "not_found_or_unavailable")
        self.assertIn("tidak ditemukan atau sistem backend sedang tidak dapat dihubungi", result["message"])

    async def test_execute_without_user_id_skips_grpc(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_order_status = AsyncMock()
        result = await OrderStatusTool(client=mock_client).execute(
            tenant_id="tenant-abc", order_number="ORD-1"
        )

        self.assertEqual(result["status"], "not_found_or_unavailable")
        mock_client.get_order_status.assert_not_awaited()

    async def test_execute_empty_order_number(self) -> None:
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_order_status = AsyncMock()
        tool = OrderStatusTool(client=mock_client)
        result = await tool.execute(tenant_id="tenant-abc", user_id=USER, order_number="   ")

        self.assertEqual(result["status"], "not_found_or_unavailable")
        mock_client.get_order_status.assert_not_awaited()


class DefaultToolRegistryTests(unittest.IsolatedAsyncioTestCase):
    """Tests for ToolRegistry with the default tools suite."""

    def test_default_tool_registry_contains_all_tools(self) -> None:
        registry = get_default_tool_registry()

        self.assertIsNone(registry.get_tool("check_laptop_cpu_usage"))
        catalog_tool = registry.get_tool("search_catalog_menu")
        order_tool = registry.get_tool("check_order_status")

        self.assertIsNotNone(catalog_tool)
        self.assertIsInstance(catalog_tool, CatalogSearchTool)

        self.assertIsNotNone(order_tool)
        self.assertIsInstance(order_tool, OrderStatusTool)

        schemas = registry.get_openrouter_schemas()
        self.assertEqual(len(schemas), 5)
        names = {s["function"]["name"] for s in schemas}
        self.assertEqual(
            names,
            {
                "search_catalog_menu",
                "check_order_status",
                "sales_summary",
                "restock_suggestions",
                "cashier_anomalies",
            },
        )

    async def test_dispatch_catalog_and_order_tools(self) -> None:
        registry = ToolRegistry()
        mock_client = Mock(spec=CashierGrpcClient)
        mock_client.get_catalog_items = AsyncMock(return_value=[
            {"id": "c1", "name": "Teh Manis", "price": Decimal("5000.00"), "is_available": True, "category": "DRINK"}
        ])
        mock_client.get_order_status = AsyncMock(return_value={
            "order_number": "ORD-555",
            "status": "COMPLETED",
            "payment_status": "PAID",
            "total_amount": Decimal("5000.00"),
            "created_at": "2026-10-22T11:00:00Z",
        })

        registry.register(CatalogSearchTool(client=mock_client))
        registry.register(OrderStatusTool(client=mock_client))

        # Dispatch catalog
        cat_res = await registry.dispatch(
            tool_name="search_catalog_menu",
            arguments_json='{"query": "teh"}',
            tenant_id="tenant-100",
            user_id=USER,
        )
        self.assertTrue(cat_res["success"])
        self.assertEqual(cat_res["data"]["total"], 1)

        # Dispatch order
        ord_res = await registry.dispatch(
            tool_name="check_order_status",
            arguments_json='{"order_number": "ORD-555"}',
            tenant_id="tenant-100",
            user_id=USER,
        )
        self.assertTrue(ord_res["success"])
        self.assertEqual(ord_res["data"]["status"], "COMPLETED")
