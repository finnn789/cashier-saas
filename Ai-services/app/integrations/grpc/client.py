"""Async gRPC client for Cashier SaaS backend communication."""

import asyncio
import logging
from typing import Any

import grpc
import grpc.aio

from app.integrations.grpc.config import GrpcSettings
from app.integrations.grpc.generated import cashier_service_pb2
from app.integrations.grpc.generated.cashier_service_pb2_grpc import CashierServiceStub

logger = logging.getLogger(__name__)

SERVICE_KEY_HEADER = "x-internal-service-key"
DEFAULT_CATALOG_LIMIT = 10
MAX_CATALOG_LIMIT = 50


def _clamp_catalog_limit(limit: int) -> int:
    """Clamp the catalog limit to 1..50 (non-positive or invalid -> default)."""
    try:
        value = int(limit)
    except (TypeError, ValueError):
        return DEFAULT_CATALOG_LIMIT
    if value <= 0:
        return DEFAULT_CATALOG_LIMIT
    return min(value, MAX_CATALOG_LIMIT)


class CashierGrpcClient:
    """Asynchronous client wrapper for CashierService gRPC stubs."""

    def __init__(self, settings: GrpcSettings) -> None:
        self._settings = settings
        self._channel: grpc.aio.Channel | None = None
        self._stub: CashierServiceStub | None = None
        self._lock = asyncio.Lock()

    async def get_channel(self) -> grpc.aio.Channel:
        """Return the active gRPC channel or lazily initialize a new one."""
        if self._channel is None:
            async with self._lock:
                if self._channel is None:
                    target = f"{self._settings.host}:{self._settings.port}"
                    if self._settings.use_tls:
                        credentials = grpc.ssl_channel_credentials()
                        self._channel = grpc.aio.secure_channel(target, credentials)
                    else:
                        self._channel = grpc.aio.insecure_channel(target)
                    self._stub = CashierServiceStub(self._channel)
        return self._channel

    async def close(self) -> None:
        """Close the active channel if initialized."""
        if self._channel is not None:
            async with self._lock:
                if self._channel is not None:
                    await self._channel.close()
                    self._channel = None
                    self._stub = None

    def _call_metadata(self) -> tuple[tuple[str, str], ...]:
        """Per-call metadata carrying the internal service key."""
        return ((SERVICE_KEY_HEADER, self._settings.service_key),)

    def _has_service_key(self, rpc_name: str) -> bool:
        """Fail closed: report a missing key without ever logging its value."""
        if self._settings.service_key:
            return True
        logger.warning(
            "gRPC %s skipped: CASHIER_GRPC_SERVICE_KEY is not configured",
            rpc_name,
        )
        return False

    @staticmethod
    def _log_rpc_error(rpc_name: str, err: grpc.aio.AioRpcError) -> None:
        """Log only the status code; never details, metadata or the key."""
        code = err.code()
        code_name = getattr(code, "name", str(code))
        logger.warning("gRPC %s failed for tenant: code=%s", rpc_name, code_name)

    async def get_catalog_items(
        self,
        *,
        tenant_id: str,
        query: str = "",
        limit: int = DEFAULT_CATALOG_LIMIT,
    ) -> list[dict[str, Any]]:
        """Fetch catalog items matching query for tenant via gRPC."""
        if not self._has_service_key("GetCatalogItem"):
            return []
        try:
            await self.get_channel()
            if self._stub is None:
                return []
            request = cashier_service_pb2.GetCatalogItemRequest(
                tenant_id=tenant_id,
                query=query,
                limit=_clamp_catalog_limit(limit),
            )
            response = await self._stub.GetCatalogItem(
                request,
                timeout=self._settings.timeout_seconds,
                metadata=self._call_metadata(),
            )
            return [
                {
                    "id": item.id,
                    "name": item.name,
                    "price": item.price,
                    "is_available": item.is_available,
                    "category": item.category,
                }
                for item in response.items
            ]
        except grpc.aio.AioRpcError as err:
            self._log_rpc_error("GetCatalogItem", err)
            return []
        except Exception as err:
            logger.warning(
                "Unexpected error during gRPC GetCatalogItem: %s",
                type(err).__name__,
            )
            return []

    async def get_order_status(
        self,
        *,
        tenant_id: str,
        order_number: str,
    ) -> dict[str, Any] | None:
        """Fetch status of an order for tenant via gRPC."""
        if not self._has_service_key("GetOrderStatus"):
            return None
        try:
            await self.get_channel()
            if self._stub is None:
                return None
            request = cashier_service_pb2.GetOrderStatusRequest(
                tenant_id=tenant_id,
                order_number=order_number,
            )
            response = await self._stub.GetOrderStatus(
                request,
                timeout=self._settings.timeout_seconds,
                metadata=self._call_metadata(),
            )
            if not response.order_number:
                return None
            return {
                "tenant_id": response.tenant_id,
                "order_number": response.order_number,
                "status": response.status,
                "payment_status": response.payment_status,
                "total_amount": response.total_amount,
                "created_at": response.created_at,
            }
        except grpc.aio.AioRpcError as err:
            if err.code() == grpc.StatusCode.NOT_FOUND:
                return None
            self._log_rpc_error("GetOrderStatus", err)
            return None
        except Exception as err:
            logger.warning(
                "Unexpected error during gRPC GetOrderStatus: %s",
                type(err).__name__,
            )
            return None

    async def __aenter__(self) -> "CashierGrpcClient":
        await self.get_channel()
        return self

    async def __aexit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        await self.close()
