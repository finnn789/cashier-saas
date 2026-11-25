"""Owner-only, read-only business insight tools (sales summary, restock, anomalies, leakage, menu margins) via gRPC."""

from datetime import date
from decimal import Decimal, InvalidOperation
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


MAX_LEAKAGE_DAYS = 92  # same cap as the backend GetLeakageReport


def _fmt(value: str, prefix: str = "", suffix: str = "") -> str:
    return f"{prefix}{value}{suffix}" if value else "-"


class LeakageReportTool(_InsightTool):
    @property
    def name(self) -> str:
        return "leakage_report"

    @property
    def description(self) -> str:
        return (
            "Laporan kebocoran bahan per outlet dari stok opname: selisih, persen, dan rugi Rupiah. "
            "Khusus owner. Tanpa parameter = 30 hari terakhir; rentang maksimal 92 hari. Hanya bahan "
            "yang menyimpang (lebih dari 5 persen dari pemakaian teoritis) yang ditampilkan. Jelaskan "
            "hasilnya dalam 3 sampai 5 kalimat dan sebut hanya angka dari tool. Data hanya baca."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "start_date": {"type": "string", "description": f"Tanggal awal (opsional). {_DATE_NOTE}"},
                "end_date": {"type": "string", "description": f"Tanggal akhir (opsional). {_DATE_NOTE}"},
            },
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
        start, end = (str(v or "").strip() for v in (start_date, end_date))
        for field, value in (("start_date", start), ("end_date", end)):
            if value and not _valid_iso_date(value):
                return self._bad_input(field)
        if start and end and not 0 <= (date.fromisoformat(end) - date.fromisoformat(start)).days < MAX_LEAKAGE_DAYS:
            return {
                "status": "invalid_input",
                "message": f"Rentang tanggal tidak valid; maksimal {MAX_LEAKAGE_DAYS} hari.",
            }
        if not user_id:
            return UNAVAILABLE
        result = await self._grpc().get_leakage_report(
            tenant_id=tenant_id, user_id=user_id, date_from=start, date_to=end
        )
        if result is None:
            return UNAVAILABLE
        if not result["outlets"]:
            return "Belum ada stok opname pada periode ini."
        blocks = []
        for outlet in result["outlets"]:
            rows = [
                f"- {line['name']}: pakai teoritis {_fmt(line['theoretical_usage'], suffix=' ' + line['unit'])}, "
                f"selisih {_fmt(line['shortage'], suffix=' ' + line['unit'])}, "
                f"persen {_fmt(line['variance_pct'], suffix='%')}, rugi {_fmt(line['loss_amount'], 'Rp ')}, "
                f"waste {_fmt(line['waste'], suffix=' ' + line['unit'])}"
                for line in outlet["lines"]
            ] or ["- Tidak ada bahan yang menyimpang."]
            blocks.append(
                f"Outlet {outlet['outlet_name']}: {outlet['stock_count_count']} opname, "
                f"total rugi {_fmt(outlet['total_loss'], 'Rp ')}, {outlet['flagged_count']} bahan menyimpang\n"
                + "\n".join(rows)
            )
        return "\n\n".join(blocks)


class MenuMarginsTool(_InsightTool):
    @property
    def name(self) -> str:
        return "menu_margins"

    @property
    def description(self) -> str:
        return (
            "HPP dan margin per menu per outlet, menu di bawah target margin dan saran harga. Khusus "
            "owner. Tanpa parameter = hanya menu di bawah target margin; isi max_margin_pct (0 sampai "
            "100) untuk menampilkan menu dengan margin sama atau di bawah angka itu. Sebut hanya angka "
            "dari tool. Data hanya baca."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "max_margin_pct": {
                    "type": "string",
                    "description": "Batas margin persen (opsional), angka 0 sampai 100, misal 40.",
                }
            },
        }

    async def execute(
        self, *, tenant_id: str, user_id: str | None = None, max_margin_pct: Any = "", **kwargs: Any
    ) -> Any:
        limit = str(max_margin_pct if max_margin_pct is not None else "").strip()
        if limit:
            try:
                valid = Decimal(limit).is_finite() and 0 <= Decimal(limit) <= 100
            except InvalidOperation:
                valid = False
            if not valid:
                return {
                    "status": "invalid_input",
                    "message": "Parameter max_margin_pct harus berupa angka 0 sampai 100.",
                }
        if not user_id:
            return UNAVAILABLE
        result = await self._grpc().get_menu_margins(
            tenant_id=tenant_id, user_id=user_id, max_margin_pct=limit
        )
        if result is None:
            return UNAVAILABLE
        if not result["outlets"]:
            return "Belum ada outlet."
        blocks = []
        for outlet in result["outlets"]:
            rows = [
                f"- {menu['name']}: harga {_fmt(menu['price'], 'Rp ')}, HPP {_fmt(menu['hpp'], 'Rp ')}, "
                f"margin {_fmt(menu['margin_pct'], suffix='%')}, status {menu['status']}, "
                f"saran harga {_fmt(menu['suggested_price'], 'Rp ')}"
                for menu in outlet["menus"]
            ] or ["- Tidak ada menu yang sesuai."]
            blocks.append(
                f"Outlet {outlet['outlet_name']} (target margin {_fmt(outlet['target_margin_pct'], suffix='%')})\n"
                + "\n".join(rows)
            )
        return "\n\n".join(blocks)
