"""Integration tests for Cashier gRPC client and in-process async gRPC server.

Spins up a real async gRPC server listening on an ephemeral port (127.0.0.1:0),
implements CashierServiceServicer, connects CashierGrpcClient, and validates
the full end-to-end request-response cycle over network sockets.
"""

import unittest
from typing import Any

import grpc
import grpc.aio

from app.integrations.grpc.client import CashierGrpcClient
from app.integrations.grpc.config import GrpcSettings
from app.integrations.grpc.generated import cashier_service_pb2, cashier_service_pb2_grpc


class InMemoryCashierServicer(cashier_service_pb2_grpc.CashierServiceServicer):
    """In-memory test servicer implementing CashierService RPCs."""

    def __init__(self) -> None:
        self.received_catalog_requests: list[cashier_service_pb2.GetCatalogItemRequest] = []
        self.received_order_requests: list[cashier_service_pb2.GetOrderStatusRequest] = []

    async def GetCatalogItem(
        self,
        request: cashier_service_pb2.GetCatalogItemRequest,
        context: grpc.aio.ServicerContext,
    ) -> cashier_service_pb2.GetCatalogItemResponse:
        self.received_catalog_requests.append(request)

        # Mock database of catalog items
        all_items = [
            cashier_service_pb2.CatalogItem(
                id="item-int-001",
                name="Nasi Goreng Spesial",
                price=25000,
                is_available=True,
                category="Food",
            ),
            cashier_service_pb2.CatalogItem(
                id="item-int-002",
                name="Nasi Uduk Betawi",
                price=18000,
                is_available=True,
                category="Food",
            ),
            cashier_service_pb2.CatalogItem(
                id="item-int-003",
                name="Es Teh Manis",
                price=5000,
                is_available=True,
                category="Beverage",
            ),
        ]

        # Filter items matching query if provided
        filtered = [
            item for item in all_items
            if not request.query or request.query.lower() in item.name.lower()
        ]
        if request.limit > 0:
            filtered = filtered[:request.limit]

        return cashier_service_pb2.GetCatalogItemResponse(
            tenant_id=request.tenant_id,
            items=filtered,
        )

    async def GetOrderStatus(
        self,
        request: cashier_service_pb2.GetOrderStatusRequest,
        context: grpc.aio.ServicerContext,
    ) -> cashier_service_pb2.GetOrderStatusResponse:
        self.received_order_requests.append(request)

        # Simulate 404 NOT_FOUND for unknown orders
        if request.order_number == "ORD-NOT-FOUND":
            await context.abort(grpc.StatusCode.NOT_FOUND, f"Order {request.order_number} not found")

        return cashier_service_pb2.GetOrderStatusResponse(
            tenant_id=request.tenant_id,
            order_number=request.order_number,
            status="COMPLETED",
            payment_status="PAID",
            total_amount=43000,
            created_at="2026-10-30T11:00:00Z",
        )


class TestCashierGrpcIntegration(unittest.IsolatedAsyncioTestCase):
    """Integration test suite executing real gRPC socket calls."""

    async def asyncSetUp(self) -> None:
        # 1. Start real in-process async gRPC server
        self.servicer = InMemoryCashierServicer()
        self.server = grpc.aio.server()
        cashier_service_pb2_grpc.add_CashierServiceServicer_to_server(
            self.servicer,
            self.server,
        )

        # 2. Bind to ephemeral port on 127.0.0.1
        self.bound_port = self.server.add_insecure_port("127.0.0.1:0")
        await self.server.start()

        # 3. Instantiate client connected to ephemeral port
        self.settings = GrpcSettings(
            host="127.0.0.1",
            port=self.bound_port,
            timeout_seconds=5.0,
            use_tls=False,
        )
        self.client = CashierGrpcClient(self.settings)

    async def asyncTearDown(self) -> None:
        # Gracefully stop client and server
        await self.client.close()
        await self.server.stop(grace=0.5)

    async def test_real_get_catalog_items_roundtrip(self) -> None:
        """Integration: verify client query transmits over socket to server and server response translates back."""
        tenant_id = "tenant-int-001"
        query = "nasi"
        limit = 2

        print("\n" + "=" * 65)
        print("[INTEGRATION TEST: Real gRPC get_catalog_items Call]")
        print(f"  Target Server Port: {self.bound_port}")
        print(f"  --> CLIENT SENT (INPUT): tenant_id='{tenant_id}', query='{query}', limit={limit}")

        result: list[dict[str, Any]] = await self.client.get_catalog_items(
            tenant_id=tenant_id,
            query=query,
            limit=limit,
        )

        # Verify server received exact request
        self.assertEqual(len(self.servicer.received_catalog_requests), 1)
        srv_req = self.servicer.received_catalog_requests[0]
        print(f"  --> SERVER RECEIVED: tenant_id='{srv_req.tenant_id}', query='{srv_req.query}', limit={srv_req.limit}")

        self.assertEqual(srv_req.tenant_id, tenant_id)
        self.assertEqual(srv_req.query, query)
        self.assertEqual(srv_req.limit, limit)

        # Verify client parsed response output
        print(f"  <-- CLIENT RECEIVED (OUTPUT): {result}")
        print("=" * 65)

        self.assertEqual(len(result), 2)
        self.assertEqual(result[0]["id"], "item-int-001")
        self.assertEqual(result[0]["name"], "Nasi Goreng Spesial")
        self.assertEqual(result[0]["price"], 25000)
        self.assertEqual(result[0]["is_available"], True)
        self.assertEqual(result[0]["category"], "Food")

        self.assertEqual(result[1]["id"], "item-int-002")
        self.assertEqual(result[1]["name"], "Nasi Uduk Betawi")
        self.assertEqual(result[1]["price"], 18000)

    async def test_real_get_order_status_roundtrip(self) -> None:
        """Integration: verify real get_order_status RPC returns complete order record."""
        tenant_id = "tenant-int-001"
        order_number = "ORD-2026-REAL-888"

        print("\n" + "=" * 65)
        print("[INTEGRATION TEST: Real gRPC get_order_status Call]")
        print(f"  Target Server Port: {self.bound_port}")
        print(f"  --> CLIENT SENT (INPUT): tenant_id='{tenant_id}', order_number='{order_number}'")

        result: dict[str, Any] | None = await self.client.get_order_status(
            tenant_id=tenant_id,
            order_number=order_number,
        )

        # Verify server received exact request
        self.assertEqual(len(self.servicer.received_order_requests), 1)
        srv_req = self.servicer.received_order_requests[0]
        print(f"  --> SERVER RECEIVED: tenant_id='{srv_req.tenant_id}', order_number='{srv_req.order_number}'")

        self.assertEqual(srv_req.tenant_id, tenant_id)
        self.assertEqual(srv_req.order_number, order_number)

        # Verify client output
        print(f"  <-- CLIENT RECEIVED (OUTPUT): {result}")
        print("=" * 65)

        self.assertIsNotNone(result)
        assert result is not None
        self.assertEqual(result["tenant_id"], tenant_id)
        self.assertEqual(result["order_number"], order_number)
        self.assertEqual(result["status"], "COMPLETED")
        self.assertEqual(result["payment_status"], "PAID")
        self.assertEqual(result["total_amount"], 43000)
        self.assertEqual(result["created_at"], "2026-10-30T11:00:00Z")

    async def test_real_get_order_status_not_found(self) -> None:
        """Integration: verify server NOT_FOUND status code is gracefully mapped to None."""
        tenant_id = "tenant-int-001"
        order_number = "ORD-NOT-FOUND"

        print("\n" + "=" * 65)
        print("[INTEGRATION TEST: Real gRPC get_order_status NOT_FOUND]")
        print(f"  --> CLIENT SENT (INPUT): tenant_id='{tenant_id}', order_number='{order_number}'")

        result = await self.client.get_order_status(
            tenant_id=tenant_id,
            order_number=order_number,
        )

        print(f"  <-- CLIENT RECEIVED (OUTPUT): {result} (Mapped to None)")
        print("=" * 65)

        self.assertIsNone(result)

    async def test_real_server_shutdown_offline_fallback(self) -> None:
        """Integration: verify when server stops unexpectedly, client handles unavailable gracefully."""
        # Stop server to simulate server going offline
        await self.server.stop(grace=0)

        tenant_id = "tenant-offline"
        print("\n" + "=" * 65)
        print("[INTEGRATION TEST: Server Stopped Fallback]")
        print("  --> Calling get_catalog_items after server shutdown...")
        catalog_result = await self.client.get_catalog_items(tenant_id=tenant_id, query="kopi")
        print(f"  <-- CLIENT RECEIVED: {catalog_result}")

        print("  --> Calling get_order_status after server shutdown...")
        order_result = await self.client.get_order_status(tenant_id=tenant_id, order_number="ORD-X")
        print(f"  <-- CLIENT RECEIVED: {order_result}")
        print("=" * 65)

        self.assertEqual(catalog_result, [])
        self.assertIsNone(order_result)


if __name__ == "__main__":
    unittest.main()
