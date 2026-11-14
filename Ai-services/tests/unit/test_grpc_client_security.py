"""Offline unit tests for gRPC client service-key authentication (TASK-057)."""

import os
import unittest
from unittest.mock import AsyncMock, Mock, patch

import grpc
import grpc.aio

from app.integrations.grpc.client import CashierGrpcClient
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


class GrpcSettingsServiceKeyTests(unittest.TestCase):
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

    def test_load_without_or_with_blank_env_yields_empty_key(self) -> None:
        for raw in (None, "   "):
            with self.subTest(raw=raw), patch.dict(os.environ):
                if raw is None:
                    os.environ.pop("CASHIER_GRPC_SERVICE_KEY", None)
                else:
                    os.environ["CASHIER_GRPC_SERVICE_KEY"] = raw
                settings = load_grpc_settings()
                self.assertEqual(settings.service_key, "")


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
    async def _sent_limit(self, **limit: int) -> int:
        client = _client()
        client._stub.GetCatalogItem = AsyncMock(return_value=_catalog_response())
        await client.get_catalog_items(tenant_id="tenant-1", user_id=USER, query="kopi", **limit)
        request = client._stub.GetCatalogItem.await_args.args[0]
        return request.limit

    async def test_limit_is_defaulted_capped_or_kept(self) -> None:
        cases = (
            ({}, 10),  # no limit given -> default
            ({"limit": 0}, 10),
            ({"limit": -5}, 10),
            ({"limit": 500}, 50),
            ({"limit": 51}, 50),
            ({"limit": 1}, 1),
            ({"limit": 25}, 25),
            ({"limit": 50}, 50),
        )
        for limit, expected in cases:
            with self.subTest(limit=limit):
                self.assertEqual(await self._sent_limit(**limit), expected)


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


if __name__ == "__main__":
    unittest.main()
