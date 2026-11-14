"""Domain tool: Order status inquiry for Cashier SaaS via gRPC."""

from typing import Any

from app.integrations.grpc.client import CashierGrpcClient
from app.integrations.grpc.config import load_grpc_settings
from app.tools.base import BaseTool

_STATUS_LABELS = {
    "OPEN": "Belum dibayar",
    "COMPLETED": "Selesai (sudah dibayar)",
    "CANCELLED": "Dibatalkan",
}


class OrderStatusTool(BaseTool):
    """Tool for checking cashier order status and details by order number."""

    def __init__(self, client: CashierGrpcClient | None = None) -> None:
        self._client = client

    @property
    def name(self) -> str:
        return "check_order_status"

    @property
    def description(self) -> str:
        return "Mengecek status dan rincian pesanan kasir berdasarkan nomor order pelanggan."

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "order_number": {
                    "type": "string",
                    "description": "Nomor pesanan atau struk order pelanggan, misal: 'ORD-20261022-001'.",
                }
            },
            "required": ["order_number"],
        }

    async def execute(
        self, *, tenant_id: str, user_id: str | None = None, order_number: str = "", **kwargs: Any
    ) -> Any:
        clean_order_number = order_number.strip()
        if not clean_order_number or not user_id:
            return {
                "order_number": order_number,
                "status": "not_found_or_unavailable",
                "message": f"Pesanan dengan nomor '{order_number}' tidak ditemukan atau sistem backend sedang tidak dapat dihubungi.",
            }

        client = self._client
        if client is None:
            client = CashierGrpcClient(load_grpc_settings())
            self._client = client

        status = await client.get_order_status(
            tenant_id=tenant_id, user_id=user_id, order_number=clean_order_number
        )
        if status is None:
            return {
                "order_number": clean_order_number,
                "status": "not_found_or_unavailable",
                "message": f"Pesanan dengan nomor '{clean_order_number}' tidak ditemukan atau sistem backend sedang tidak dapat dihubungi.",
            }

        order_status = status.get("status", "UNKNOWN")
        payment_status = status.get("payment_status", "UNKNOWN")
        total_amount = str(status.get("total_amount", "0"))
        created_at = status.get("created_at", "")
        status_label = _STATUS_LABELS.get(order_status, order_status)

        return {
            "order_number": clean_order_number,
            "status": order_status,
            "status_label": status_label,
            "payment_status": payment_status,
            "total_amount": total_amount,
            "created_at": created_at,
            "message": (
                f"Pesanan '{clean_order_number}' berstatus {status_label} "
                f"dengan status pembayaran {payment_status}."
            ),
        }
