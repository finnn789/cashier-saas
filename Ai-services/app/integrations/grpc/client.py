"""Async gRPC client for Cashier SaaS backend communication."""

import asyncio
import logging
import time
from collections.abc import Iterator
from contextlib import contextmanager
from decimal import Decimal
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


class _RpcSpan:
    """Mutable holder for the outcome fields of one RPC log span."""

    __slots__ = ("status", "items_count")

    def __init__(self) -> None:
        self.status = "ERROR"
        self.items_count: int | None = None


def _clamp_catalog_limit(limit: int) -> int:
    """Clamp the catalog limit to 1..50 (non-positive or invalid -> default)."""
    try:
        value = int(limit)
    except (TypeError, ValueError):
        return DEFAULT_CATALOG_LIMIT
    if value <= 0:
        return DEFAULT_CATALOG_LIMIT
    return min(value, MAX_CATALOG_LIMIT)


def _message_to_dict(message: Any) -> dict[str, Any]:
    """Protobuf message -> plain dict (int64 stay ints, money strings stay strings)."""
    result: dict[str, Any] = {}
    for field in message.DESCRIPTOR.fields:
        value = getattr(message, field.name)
        repeated = field.label == field.LABEL_REPEATED  # protobuf<6 pinned; is_repeated needs 6.x
        if field.message_type is None:
            result[field.name] = list(value) if repeated else value
        elif repeated:
            result[field.name] = [_message_to_dict(item) for item in value]
        else:
            result[field.name] = _message_to_dict(value)
    return result


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
                    logger.info(
                        "gRPC channel opened",
                        extra={
                            "event": "grpc_channel_opened",
                            "target": target,
                            "secure": bool(self._settings.use_tls),
                        },
                    )
        return self._channel

    async def close(self) -> None:
        """Close the active channel if initialized."""
        if self._channel is not None:
            async with self._lock:
                if self._channel is not None:
                    await self._channel.close()
                    self._channel = None
                    self._stub = None
                    logger.info("gRPC channel closed", extra={"event": "grpc_channel_closed"})

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
            extra={"event": "grpc_service_key_missing", "rpc": rpc_name},
        )
        return False

    @contextmanager
    def _rpc_span(self, rpc_name: str) -> Iterator[_RpcSpan]:
        """Log start/finish of one RPC; exceptions propagate unchanged."""
        span = _RpcSpan()
        started = time.perf_counter()
        logger.info(
            "gRPC call started",
            extra={
                "event": "grpc_call_started",
                "rpc": rpc_name,
                "timeout_seconds": self._settings.timeout_seconds,
            },
        )
        try:
            yield span
            span.status = "OK"
        except grpc.aio.AioRpcError as err:
            code = err.code()
            span.status = getattr(code, "name", str(code))
            raise
        finally:
            fields: dict[str, Any] = {
                "event": "grpc_call_finished",
                "rpc": rpc_name,
                "elapsed_ms": int((time.perf_counter() - started) * 1000),
                "status": span.status,
            }
            if span.items_count is not None:
                fields["items_count"] = span.items_count
            logger.info("gRPC call finished", extra=fields)

    @staticmethod
    def _log_rpc_error(rpc_name: str, err: grpc.aio.AioRpcError) -> None:
        """Log only the status code; never details, metadata or the key."""
        code = err.code()
        code_name = getattr(code, "name", str(code))
        logger.warning(
            "gRPC %s failed for tenant: code=%s",
            rpc_name,
            code_name,
            extra={
                "event": "grpc_call_failed",
                "rpc": rpc_name,
                "error_type": type(err).__name__,
                "code": code_name,
            },
        )

    async def get_catalog_items(
        self,
        *,
        tenant_id: str,
        user_id: str,
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
                user_id=user_id,
                query=query,
                limit=_clamp_catalog_limit(limit),
            )
            with self._rpc_span("GetCatalogItem") as span:
                response = await self._stub.GetCatalogItem(
                    request,
                    timeout=self._settings.timeout_seconds,
                    metadata=self._call_metadata(),
                )
                span.items_count = len(response.items)
            return [
                {
                    "id": item.id,
                    "name": item.name,
                    "price": Decimal(item.price_amount),
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
                extra={
                    "event": "grpc_call_unexpected_error",
                    "rpc": "GetCatalogItem",
                    "error_type": type(err).__name__,
                },
            )
            return []

    async def get_order_status(
        self,
        *,
        tenant_id: str,
        user_id: str,
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
                user_id=user_id,
                order_number=order_number,
            )
            with self._rpc_span("GetOrderStatus") as span:
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
                "total_amount": Decimal(response.total),
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
                extra={
                    "event": "grpc_call_unexpected_error",
                    "rpc": "GetOrderStatus",
                    "error_type": type(err).__name__,
                },
            )
            return None

    async def _call_insight(self, rpc_name: str, request: Any) -> dict[str, Any] | None:
        """Run one read-only owner insight RPC; plain dict on success, None on any error."""
        if not self._has_service_key(rpc_name):
            return None
        try:
            await self.get_channel()
            if self._stub is None:
                return None
            with self._rpc_span(rpc_name):
                response = await getattr(self._stub, rpc_name)(
                    request,
                    timeout=self._settings.timeout_seconds,
                    metadata=self._call_metadata(),
                )
            return _message_to_dict(response)
        except grpc.aio.AioRpcError as err:
            self._log_rpc_error(rpc_name, err)  # PERMISSION_DENIED (non-owner) lands here too
            return None
        except Exception as err:
            logger.warning(
                "Unexpected error during gRPC %s: %s",
                rpc_name,
                type(err).__name__,
                extra={
                    "event": "grpc_call_unexpected_error",
                    "rpc": rpc_name,
                    "error_type": type(err).__name__,
                },
            )
            return None

    async def get_sales_summary(
        self, *, tenant_id: str, user_id: str, start_date: str, end_date: str
    ) -> dict[str, Any] | None:
        """Owner-only sales summary per outlet for an inclusive ISO date range."""
        return await self._call_insight(
            "GetSalesSummary",
            cashier_service_pb2.GetSalesSummaryRequest(
                tenant_id=tenant_id, user_id=user_id, start_date=start_date, end_date=end_date
            ),
        )

    async def get_restock_suggestions(
        self, *, tenant_id: str, user_id: str
    ) -> dict[str, Any] | None:
        """Owner-only restock suggestions per outlet."""
        return await self._call_insight(
            "GetRestockSuggestions",
            cashier_service_pb2.GetRestockSuggestionsRequest(tenant_id=tenant_id, user_id=user_id),
        )

    async def get_anomalies(
        self, *, tenant_id: str, user_id: str, date: str
    ) -> dict[str, Any] | None:
        """Owner-only cashier anomalies per outlet for one ISO date."""
        return await self._call_insight(
            "GetAnomalies",
            cashier_service_pb2.GetAnomaliesRequest(tenant_id=tenant_id, user_id=user_id, date=date),
        )

    async def consume_ai_question(self, *, tenant_id: str, user_id: str) -> dict[str, Any] | None:
        """Owner-only quota check + count of one AI question.

        Dict {allowed, reason, used, limit, warn} on success; None on any gRPC or
        configuration error, so the caller can fail closed.
        """
        return await self._call_insight(
            "ConsumeAiQuestion",
            cashier_service_pb2.ConsumeAiQuestionRequest(tenant_id=tenant_id, user_id=user_id),
        )

    async def record_ai_usage(
        self, *, tenant_id: str, input_tokens: int, output_tokens: int
    ) -> None:
        """Report LLM token usage; never raises (errors are logged by the shared RPC helper)."""
        await self._call_insight(
            "RecordAiUsage",
            cashier_service_pb2.RecordAiUsageRequest(
                tenant_id=tenant_id, input_tokens=input_tokens, output_tokens=output_tokens
            ),
        )

    async def __aenter__(self) -> "CashierGrpcClient":
        await self.get_channel()
        return self

    async def __aexit__(self, exc_type: Any, exc_val: Any, exc_tb: Any) -> None:
        await self.close()
