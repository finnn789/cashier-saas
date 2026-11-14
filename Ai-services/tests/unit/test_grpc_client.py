"""Unit tests for CashierGrpcClient.

Tests verifying request construction, response parsing, and error/offline fallback
with mock gRPC stubs and channels.
"""

import unittest
from decimal import Decimal
from unittest.mock import AsyncMock, Mock

import grpc
import grpc.aio

from app.integrations.grpc.client import CashierGrpcClient
from app.integrations.grpc.config import GrpcSettings
from app.integrations.grpc.generated import cashier_service_pb2


USER_ID = "11111111-1111-1111-1111-111111111111"


class TestCashierGrpcClient(unittest.IsolatedAsyncioTestCase):
    """Unit tests for CashierGrpcClient methods and error handling."""

    async def asyncSetUp(self) -> None:
        self.settings = GrpcSettings(
            host="127.0.0.1",
            port=9090,
            timeout_seconds=2.0,
            use_tls=False,
            service_key="test-service-key-0123456789",
        )
        self.client = CashierGrpcClient(self.settings)
        # Mock channel and stub to avoid opening real network sockets in unit tests
        self.mock_channel = Mock(spec=grpc.aio.Channel)
        self.mock_stub = Mock()
        self.client._channel = self.mock_channel
        self.client._stub = self.mock_stub

    async def asyncTearDown(self) -> None:
        await self.client.close()

    async def test_get_catalog_items_success(self) -> None:
        """Verify input parameters produce correct GetCatalogItemRequest and response is transformed to dict list."""
        tenant_id = "tenant-unit-001"
        query = "kopi"
        limit = 5

        # Prepare mock protobuf response
        mock_items = [
            cashier_service_pb2.CatalogItem(
                id="item-001",
                name="Kopi Susu Gula Aren",
                price_amount="18000.00",
                is_available=True,
                category="Beverage",
            ),
            cashier_service_pb2.CatalogItem(
                id="item-002",
                name="Kopi Hitam",
                price_amount="12000.00",
                is_available=False,
                category="Beverage",
            ),
        ]
        mock_response = cashier_service_pb2.GetCatalogItemResponse(
            tenant_id=tenant_id,
            items=mock_items,
        )
        self.mock_stub.GetCatalogItem = AsyncMock(return_value=mock_response)

        print("\n" + "=" * 60)
        print("[UNIT TEST: get_catalog_items]")
        print(f"  --> INPUT: tenant_id='{tenant_id}', query='{query}', limit={limit}")

        result = await self.client.get_catalog_items(
            tenant_id=tenant_id, user_id=USER_ID,
            query=query,
            limit=limit,
        )

        print(f"  <-- OUTPUT: {result}")
        print("=" * 60)

        # Assert stub was called with exact protobuf request
        self.mock_stub.GetCatalogItem.assert_awaited_once()
        sent_request, kwargs = self.mock_stub.GetCatalogItem.call_args
        request_obj = sent_request[0]

        self.assertIsInstance(request_obj, cashier_service_pb2.GetCatalogItemRequest)
        self.assertEqual(request_obj.tenant_id, tenant_id)
        self.assertEqual(request_obj.user_id, USER_ID)
        self.assertEqual(request_obj.query, query)
        self.assertEqual(request_obj.limit, limit)
        self.assertEqual(kwargs.get("timeout"), self.settings.timeout_seconds)
        self.assertEqual(
            kwargs.get("metadata"),
            (("x-internal-service-key", "test-service-key-0123456789"),),
        )

        # Assert transformed output
        expected = [
            {
                "id": "item-001",
                "name": "Kopi Susu Gula Aren",
                "price": Decimal("18000.00"),
                "is_available": True,
                "category": "Beverage",
            },
            {
                "id": "item-002",
                "name": "Kopi Hitam",
                "price": Decimal("12000.00"),
                "is_available": False,
                "category": "Beverage",
            },
        ]
        self.assertEqual(result, expected)

    async def test_get_order_status_success(self) -> None:
        """Verify input parameters produce correct GetOrderStatusRequest and response is transformed to dict."""
        tenant_id = "tenant-unit-001"
        order_number = "ORD-2026-UNIT"

        mock_response = cashier_service_pb2.GetOrderStatusResponse(
            tenant_id=tenant_id,
            order_number=order_number,
            status="COMPLETED",
            payment_status="PAID",
            total="45000.00",
            created_at="2026-10-30T10:15:00Z",
        )
        self.mock_stub.GetOrderStatus = AsyncMock(return_value=mock_response)

        print("\n" + "=" * 60)
        print("[UNIT TEST: get_order_status]")
        print(f"  --> INPUT: tenant_id='{tenant_id}', order_number='{order_number}'")

        result = await self.client.get_order_status(
            tenant_id=tenant_id, user_id=USER_ID,
            order_number=order_number,
        )

        print(f"  <-- OUTPUT: {result}")
        print("=" * 60)

        # Assert stub called with exact protobuf request
        self.mock_stub.GetOrderStatus.assert_awaited_once()
        sent_request, kwargs = self.mock_stub.GetOrderStatus.call_args
        request_obj = sent_request[0]

        self.assertIsInstance(request_obj, cashier_service_pb2.GetOrderStatusRequest)
        self.assertEqual(request_obj.tenant_id, tenant_id)
        self.assertEqual(request_obj.user_id, USER_ID)
        self.assertEqual(request_obj.order_number, order_number)
        self.assertEqual(kwargs.get("timeout"), self.settings.timeout_seconds)
        self.assertEqual(
            kwargs.get("metadata"),
            (("x-internal-service-key", "test-service-key-0123456789"),),
        )

        # Assert transformed output
        expected = {
            "tenant_id": tenant_id,
            "order_number": order_number,
            "status": "COMPLETED",
            "payment_status": "PAID",
            "total_amount": Decimal("45000.00"),
            "created_at": "2026-10-30T10:15:00Z",
        }
        self.assertEqual(result, expected)

    async def test_get_order_status_empty_order_number_returns_none(self) -> None:
        """When response has empty order_number, client returns None."""
        tenant_id = "tenant-unit-001"
        order_number = "ORD-EMPTY"

        mock_response = cashier_service_pb2.GetOrderStatusResponse(order_number="")
        self.mock_stub.GetOrderStatus = AsyncMock(return_value=mock_response)

        print("\n" + "=" * 60)
        print("[UNIT TEST: get_order_status with empty response order_number]")
        print(f"  --> INPUT: tenant_id='{tenant_id}', order_number='{order_number}'")

        result = await self.client.get_order_status(
            tenant_id=tenant_id, user_id=USER_ID,
            order_number=order_number,
        )

        print(f"  <-- OUTPUT: {result}")
        print("=" * 60)

        self.assertIsNone(result)

    async def test_rpc_error_fallback(self) -> None:
        """AioRpcError (UNAVAILABLE / NOT_FOUND) is swallowed: catalog gives [], order status gives None."""
        tenant_id = "tenant-unit-err"
        cases = (
            (
                "get_catalog_items", "GetCatalogItem", grpc.StatusCode.UNAVAILABLE,
                "Service Unavailable", "Transport closed",
                {"query": "roti", "limit": 10}, [],
            ),
            (
                "get_order_status", "GetOrderStatus", grpc.StatusCode.NOT_FOUND,
                "Order not found in Cashier backend", "",
                {"order_number": "ORD-404"}, None,
            ),
            (
                "get_order_status", "GetOrderStatus", grpc.StatusCode.UNAVAILABLE,
                "Connection refused", "",
                {"order_number": "ORD-503"}, None,
            ),
        )
        for method, rpc, code, details, debug, arguments, expected in cases:
            with self.subTest(rpc=rpc, code=code.name):
                setattr(
                    self.mock_stub,
                    rpc,
                    AsyncMock(
                        side_effect=grpc.aio.AioRpcError(
                            code=code,
                            initial_metadata=(),
                            trailing_metadata=(),
                            details=details,
                            debug_error_string=debug,
                        )
                    ),
                )

                result = await getattr(self.client, method)(
                    tenant_id=tenant_id, user_id=USER_ID, **arguments
                )

                self.assertEqual(result, expected)

    async def test_unexpected_generic_exception_handling(self) -> None:
        """When stub raises unexpected non-gRPC exception, client catches and falls back cleanly."""
        tenant_id = "tenant-crash"
        self.mock_stub.GetCatalogItem = AsyncMock(side_effect=RuntimeError("Unexpected OS crash"))
        self.mock_stub.GetOrderStatus = AsyncMock(side_effect=RuntimeError("Unexpected OS crash"))

        print("\n" + "=" * 60)
        print("[UNIT TEST: generic exception handling]")
        print("  --> Testing GetCatalogItem with RuntimeError")
        catalog_result = await self.client.get_catalog_items(tenant_id=tenant_id, user_id=USER_ID)
        print(f"  <-- OUTPUT: {catalog_result}")

        print("  --> Testing GetOrderStatus with RuntimeError")
        order_result = await self.client.get_order_status(tenant_id=tenant_id, user_id=USER_ID, order_number="ORD-X")
        print(f"  <-- OUTPUT: {order_result}")
        print("=" * 60)

        self.assertEqual(catalog_result, [])
        self.assertIsNone(order_result)


if __name__ == "__main__":
    unittest.main()
