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

    async def get_catalog_items(
        self,
        *,
        tenant_id: str,
        query: str = "",
        limit: int = 10,
    ) -> list[dict[str, Any]]:
        """Fetch catalog items matching query for tenant via gRPC."""
        try:
            await self.get_channel()
            if self._stub is None:
                return []
            request = cashier_service_pb2.GetCatalogItemRequest(
                tenant_id=tenant_id,
                query=query,
                limit=limit,
            )
            response = await self._stub.GetCatalogItem(
                request,
                timeout=self._settings.timeout_seconds,
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
            logger.warning(
                "gRPC GetCatalogItem failed for tenant: code=%s",
                err.code(),
            )
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
            logger.warning(
                "gRPC GetOrderStatus failed for tenant: code=%s",
                err.code(),
            )
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
