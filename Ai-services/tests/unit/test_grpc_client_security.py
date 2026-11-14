"""Offline unit tests for gRPC client service-key authentication (TASK-057)."""

import os
import unittest
from unittest.mock import AsyncMock, Mock, patch

import grpc
import grpc.aio

from app.integrations.grpc.client import (
    SERVICE_KEY_HEADER,
    CashierGrpcClient,
)
from app.integrations.grpc.config import GrpcSettings, load_grpc_settings

CLIENT_LOGGER = "app.integrations.grpc.client"
SECRET = "unit-test-secret-key-0123456789"
USER = "user-1"


def _rpc_error(code: grpc.StatusCode, details: str = "denied") -> grpc.aio.AioRpcError:
    return grpc.aio.AioRpcError(
        code=code,
        initial_metadata=(),
        trailing_metadata=(),
        details=details,
        debug_error_string="",
    )


def _client(service_key: str = SECRET) -> CashierGrpcClient:
    client = CashierGrpcClient(
        GrpcSettings(host="127.0.0.1", port=9090, service_key=service_key)
    )
    client._channel = Mock()
    client._stub = Mock()
    return client


def _catalog_response() -> Mock:
    item = Mock()
    item.id = "item-1"
    item.name = "Kopi"
    item.price_amount = "18000.00"
    item.is_available = True
    item.category = "BEVERAGE"
    response = Mock()
    response.items = [item]
    return response


def _order_response() -> Mock:
    response = Mock()
    response.tenant_id = "tenant-1"
    response.order_number = "ORD-1"
    response.status = "PROCESSING"
    response.payment_status = "PAID"
    response.total = "45000.00"
    response.created_at = "2026-11-02T10:00:00Z"
    return response


class GrpcSettingsServiceKeyTests(unittest.TestCase):
    def test_default_service_key_is_empty(self) -> None:
        self.assertEqual(GrpcSettings().service_key, "")

    def test_repr_does_not_leak_service_key(self) -> None:
        settings = GrpcSettings(service_key=SECRET)
        self.assertNotIn(SECRET, repr(settings))
        self.assertNotIn(SECRET, str(settings))
        self.assertEqual(settings.service_key, SECRET)

    def test_load_from_env_strips_whitespace(self) -> None:
        with patch.dict(os.environ, {"CASHIER_GRPC_SERVICE_KEY": f"  {SECRET}\n"}):
            settings = load_grpc_settings()
        self.assertEqual(settings.service_key, SECRET)
        self.assertNotIn(SECRET, repr(settings))

    def test_load_without_env_yields_empty_key(self) -> None:
        with patch.dict(os.environ, {}, clear=False):
            os.environ.pop("CASHIER_GRPC_SERVICE_KEY", None)
            settings = load_grpc_settings()
        self.assertEqual(settings.service_key, "")

    def test_load_blank_env_yields_empty_key(self) -> None:
        with patch.dict(os.environ, {"CASHIER_GRPC_SERVICE_KEY": "   "}):
            settings = load_grpc_settings()
        self.assertEqual(settings.service_key, "")


class GrpcClientMetadataTests(unittest.IsolatedAsyncioTestCase):
    async def test_catalog_sends_service_key_metadata(self) -> None:
        client = _client()
        client._stub.GetCatalogItem = AsyncMock(return_value=_catalog_response())

        items = await client.get_catalog_items(tenant_id="tenant-1", user_id=USER, query="kopi")

        self.assertEqual(len(items), 1)
        self.assertEqual(items[0]["id"], "item-1")
        client._stub.GetCatalogItem.assert_awaited_once()
        kwargs = client._stub.GetCatalogItem.await_args.kwargs
        self.assertEqual(kwargs["metadata"], ((SERVICE_KEY_HEADER, SECRET),))
        self.assertEqual(SERVICE_KEY_HEADER, "x-internal-service-key")

    async def test_order_status_sends_service_key_metadata(self) -> None:
        client = _client()
        client._stub.GetOrderStatus = AsyncMock(return_value=_order_response())

        status = await client.get_order_status(tenant_id="tenant-1", user_id=USER, order_number="ORD-1")

        self.assertIsNotNone(status)
        self.assertEqual(status["order_number"], "ORD-1")
        self.assertEqual(status["payment_status"], "PAID")
        kwargs = client._stub.GetOrderStatus.await_args.kwargs
        self.assertEqual(kwargs["metadata"], ((SERVICE_KEY_HEADER, SECRET),))


class GrpcClientFailClosedTests(unittest.IsolatedAsyncioTestCase):
    async def test_catalog_with_empty_key_does_not_call_backend(self) -> None:
        client = _client(service_key="")
        client._stub.GetCatalogItem = AsyncMock()

        with patch.object(client, "get_channel", new_callable=AsyncMock) as get_channel:
            with self.assertLogs(CLIENT_LOGGER, level="WARNING") as logs:
                items = await client.get_catalog_items(tenant_id="tenant-1", user_id=USER, query="kopi")

        self.assertEqual(items, [])
        client._stub.GetCatalogItem.assert_not_called()
        get_channel.assert_not_awaited()
        self.assertTrue(any("GetCatalogItem" in line for line in logs.output))

    async def test_order_status_with_empty_key_does_not_call_backend(self) -> None:
        client = _client(service_key="")
        client._stub.GetOrderStatus = AsyncMock()

        with patch.object(client, "get_channel", new_callable=AsyncMock) as get_channel:
            with self.assertLogs(CLIENT_LOGGER, level="WARNING") as logs:
                status = await client.get_order_status(tenant_id="tenant-1", user_id=USER, order_number="ORD-1")

        self.assertIsNone(status)
        client._stub.GetOrderStatus.assert_not_called()
        get_channel.assert_not_awaited()
        self.assertTrue(any("GetOrderStatus" in line for line in logs.output))


class GrpcClientLimitClampTests(unittest.IsolatedAsyncioTestCase):
    async def _sent_limit(self, requested: int) -> int:
        client = _client()
        client._stub.GetCatalogItem = AsyncMock(return_value=_catalog_response())
        await client.get_catalog_items(tenant_id="tenant-1", user_id=USER, query="kopi", limit=requested)
        request = client._stub.GetCatalogItem.await_args.args[0]
        return request.limit

    async def test_non_positive_limit_uses_default(self) -> None:
        self.assertEqual(await self._sent_limit(0), 10)
        self.assertEqual(await self._sent_limit(-5), 10)

    async def test_oversized_limit_is_capped(self) -> None:
        self.assertEqual(await self._sent_limit(500), 50)
        self.assertEqual(await self._sent_limit(51), 50)

    async def test_in_range_limit_is_kept(self) -> None:
        self.assertEqual(await self._sent_limit(1), 1)
        self.assertEqual(await self._sent_limit(25), 25)
        self.assertEqual(await self._sent_limit(50), 50)

    async def test_default_limit_is_ten(self) -> None:
        client = _client()
        client._stub.GetCatalogItem = AsyncMock(return_value=_catalog_response())
        await client.get_catalog_items(tenant_id="tenant-1", user_id=USER, query="kopi")
        request = client._stub.GetCatalogItem.await_args.args[0]
        self.assertEqual(request.limit, 10)


class GrpcClientSafeErrorTests(unittest.IsolatedAsyncioTestCase):
    SAFE_CODES = (
        grpc.StatusCode.UNAUTHENTICATED,
        grpc.StatusCode.PERMISSION_DENIED,
        grpc.StatusCode.RESOURCE_EXHAUSTED,
    )

    async def test_catalog_auth_errors_return_empty_without_leaking_key(self) -> None:
        for code in self.SAFE_CODES:
            with self.subTest(code=code.name):
                client = _client()
                # Server-side details deliberately echo the key to prove it is never logged.
                client._stub.GetCatalogItem = AsyncMock(
                    side_effect=_rpc_error(code, details=f"rejected {SECRET}")
                )
                with self.assertLogs(CLIENT_LOGGER, level="WARNING") as logs:
                    items = await client.get_catalog_items(tenant_id="tenant-1", user_id=USER, query="kopi")

                self.assertEqual(items, [])
                joined = "\n".join(logs.output)
                self.assertIn(code.name, joined)
                self.assertNotIn(SECRET, joined)

    async def test_order_status_auth_errors_return_none_without_leaking_key(self) -> None:
        for code in self.SAFE_CODES:
            with self.subTest(code=code.name):
                client = _client()
                client._stub.GetOrderStatus = AsyncMock(
                    side_effect=_rpc_error(code, details=f"rejected {SECRET}")
                )
                with self.assertLogs(CLIENT_LOGGER, level="WARNING") as logs:
                    status = await client.get_order_status(
                        tenant_id="tenant-1", user_id=USER, order_number="ORD-1"
                    )

                self.assertIsNone(status)
                joined = "\n".join(logs.output)
                self.assertIn(code.name, joined)
                self.assertNotIn(SECRET, joined)

    async def test_missing_key_warning_never_contains_key_value(self) -> None:
        client = _client(service_key="")
        with self.assertLogs(CLIENT_LOGGER, level="WARNING") as logs:
            await client.get_catalog_items(tenant_id="tenant-1", user_id=USER, query="kopi")
        self.assertNotIn(SECRET, "\n".join(logs.output))

    async def test_order_not_found_still_returns_none(self) -> None:
        client = _client()
        client._stub.GetOrderStatus = AsyncMock(
            side_effect=_rpc_error(grpc.StatusCode.NOT_FOUND, details="missing")
        )
        status = await client.get_order_status(tenant_id="tenant-1", user_id=USER, order_number="ORD-X")
        self.assertIsNone(status)


if __name__ == "__main__":
    unittest.main()
