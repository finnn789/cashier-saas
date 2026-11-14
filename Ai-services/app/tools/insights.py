"""Owner-only, read-only business insight tools (sales summary, restock, anomalies) via gRPC."""

from datetime import date
from typing import Any

from app.integrations.grpc.client import CashierGrpcClient
from app.integrations.grpc.config import load_grpc_settings
from app.tools.base import BaseTool

UNAVAILABLE = {
    "status": "unavailable",
    "message": "Data tidak tersedia; fitur ini hanya untuk owner (pemilik usaha).",
}


def _valid_iso_date(value: Any) -> bool:
    try:
        date.fromisoformat(str(value).strip())
        return True
    except ValueError:
        return False


class _InsightTool(BaseTool):
    """Shared lazy client + owner-only fallback for the insight tools."""

    def __init__(self, client: CashierGrpcClient | None = None) -> None:
        self._client = client

    def _grpc(self) -> CashierGrpcClient:
        if self._client is None:
            self._client = CashierGrpcClient(load_grpc_settings())
        return self._client

    @staticmethod
    def _bad_input(field: str) -> dict[str, str]:
        return {
            "status": "invalid_input",
            "message": f"Parameter {field} harus berformat tanggal YYYY-MM-DD.",
        }


_DATE_NOTE = "Format YYYY-MM-DD, dalam zona waktu outlet."


class SalesSummaryTool(_InsightTool):
    @property
    def name(self) -> str:
        return "sales_summary"

    @property
    def description(self) -> str:
        return (
            "Ringkasan penjualan per outlet untuk rentang tanggal (inklusif), khusus owner: omzet, "
            "transaksi, diskon, pajak, HPP, laba kotor, refund, pembatalan, pengeluaran kas, selisih "
            "kas, rincian per metode pembayaran, dan 5 menu terlaris. Tanggal memakai zona waktu "
            "outlet. Untuk membandingkan periode (misal minggu ini vs minggu lalu), panggil tool ini "
            "dua kali dengan rentang berbeda. Data hanya baca, tidak mengubah apa pun."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "start_date": {"type": "string", "description": f"Tanggal awal. {_DATE_NOTE}"},
                "end_date": {"type": "string", "description": f"Tanggal akhir. {_DATE_NOTE}"},
            },
            "required": ["start_date", "end_date"],
        }

    async def execute(
        self,
        *,
        tenant_id: str,
        user_id: str | None = None,
        start_date: str = "",
        end_date: str = "",
        **kwargs: Any,
    ) -> Any:
        for field, value in (("start_date", start_date), ("end_date", end_date)):
            if not _valid_iso_date(value):
                return self._bad_input(field)
        if not user_id:
            return UNAVAILABLE
        result = await self._grpc().get_sales_summary(
            tenant_id=tenant_id,
            user_id=user_id,
            start_date=start_date.strip(),
            end_date=end_date.strip(),
        )
        return UNAVAILABLE if result is None else result


class RestockSuggestionsTool(_InsightTool):
    @property
    def name(self) -> str:
        return "restock_suggestions"

    @property
    def description(self) -> str:
        return (
            "Saran belanja ulang bahan baku per outlet, khusus owner: stok saat ini, batas minimum, "
            "rata-rata pemakaian harian (14 hari terakhir), jumlah yang disarankan untuk dibeli, dan "
            "status stok (NEGATIVE, OUT, LOW, OK). Data hanya baca."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {"type": "object", "properties": {}}

    async def execute(
        self, *, tenant_id: str, user_id: str | None = None, **kwargs: Any
    ) -> Any:
        if not user_id:
            return UNAVAILABLE
        result = await self._grpc().get_restock_suggestions(tenant_id=tenant_id, user_id=user_id)
        return UNAVAILABLE if result is None else result


class CashierAnomaliesTool(_InsightTool):
    @property
    def name(self) -> str:
        return "cashier_anomalies"

    @property
    def description(self) -> str:
        return (
            "Deteksi kejanggalan kasir pada satu tanggal, khusus owner: pembatalan, diskon, atau "
            "selisih kas yang lebih dari 2x rata-rata harian kasir tersebut selama 30 hari sebelumnya "
            "(kind: CANCELLATIONS, DISCOUNTS, CASH_DIFFERENCE). Tanggal memakai zona waktu outlet. "
            "Daftar kosong berarti tidak ada kejanggalan. Data hanya baca."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "date": {"type": "string", "description": f"Tanggal yang diperiksa. {_DATE_NOTE}"}
            },
            "required": ["date"],
        }

    async def execute(
        self, *, tenant_id: str, user_id: str | None = None, date: str = "", **kwargs: Any
    ) -> Any:
        if not _valid_iso_date(date):
            return self._bad_input("date")
        if not user_id:
            return UNAVAILABLE
        result = await self._grpc().get_anomalies(
            tenant_id=tenant_id, user_id=user_id, date=date.strip()
        )
        return UNAVAILABLE if result is None else result
