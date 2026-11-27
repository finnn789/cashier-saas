"""Owner-only product analysis (ABC, menu matrix, ranking, growth) over Java product aggregates."""

from datetime import date, timedelta
from typing import Any

from app.analysis.money import format_number, format_rupiah, to_decimal
from app.analysis.product import abc_classes, growth_pct, menu_matrix, rank
from app.tools.insights import UNAVAILABLE, _DATE_NOTE, _InsightTool, _valid_iso_date

KINDS = ("abc", "matrix", "ranking", "growth")
NO_SALES = "Belum ada penjualan."
_QUADRANTS = (
    ("STAR", "Bintang (laris & margin tinggi)"),
    ("PLOWHORSE", "Kuda beban (laris, margin rendah)"),
    ("PUZZLE", "Teka-teki (kurang laris, margin tinggi)"),
    ("DOG", "Anjing (kurang laris, margin rendah)"),
    ("COST_UNKNOWN", "HPP belum lengkap"),
)


def _pct(value: Any) -> str:
    return f"{format_number(value)}%"


def _growth_text(value: Any) -> str:
    return "baru" if value is None else _pct(value)


def _abc(products: list[dict]) -> str:
    return "\n".join(
        f"- {p['name']}: {format_rupiah(p['gross_sales'])} ({_pct(p['share_pct'])}), "
        f"kumulatif {_pct(p['cumulative_pct'])}, kelas {p['abc']}"
        for p in abc_classes(products)
    )


def _matrix(products: list[dict]) -> str:
    groups = menu_matrix(products)
    lines = []
    for key, label in _QUADRANTS:
        for p in groups[key]:
            detail = (
                ""
                if key == "COST_UNKNOWN"
                else f", porsi {_pct(p['mix_pct'])}, margin/unit {format_rupiah(p['unit_margin'])}"
            )
            lines.append(f"- {label}: {p['name']}{detail}")
    return "\n".join(lines)


def _ranking(products: list[dict]) -> str:
    def rows(items: list[dict]) -> str:
        return "\n".join(
            f"- {p['name']}: {p['quantity']} terjual, {format_rupiah(p['gross_sales'])}" for p in items
        )

    return (
        f"Terlaris (jumlah):\n{rows(rank(products, 'quantity', 5))}\n"
        f"Terlaris (omzet):\n{rows(rank(products, 'gross_sales', 5))}\n"
        f"Paling sepi (jumlah):\n{rows(rank(products, 'quantity', 5, worst=True))}"
    )


def _growth(current: list[dict], previous: list[dict]) -> str:
    cur = {p["product_id"]: p for p in current}
    prev = {p["product_id"]: p for p in previous}
    total_cur = sum((to_decimal(p["gross_sales"]) for p in current), to_decimal(0))
    total_prev = sum((to_decimal(p["gross_sales"]) for p in previous), to_decimal(0))
    lines = [
        f"Total omzet: {format_rupiah(total_cur)} vs {format_rupiah(total_prev)} "
        f"({_growth_text(growth_pct(total_cur, total_prev))})"
    ]
    for pid in sorted(cur.keys() | prev.keys(), key=lambda k: (cur.get(k) or prev[k])["name"]):
        now = to_decimal((cur.get(pid) or {}).get("gross_sales"))
        before = to_decimal((prev.get(pid) or {}).get("gross_sales"))
        name = (cur.get(pid) or prev[pid])["name"]
        lines.append(
            f"- {name}: {format_rupiah(now)} vs {format_rupiah(before)} ({_growth_text(growth_pct(now, before))})"
        )
    return "\n".join(lines)


class ProductAnalysisTool(_InsightTool):
    @property
    def name(self) -> str:
        return "product_analysis"

    @property
    def description(self) -> str:
        return (
            "Analisis produk per outlet untuk rentang tanggal (inklusif), khusus owner. kind: abc "
            "(kelas A/B/C berdasar omzet kumulatif), matrix (menu bintang, kuda beban, teka-teki, "
            "anjing), ranking (terlaris dan paling sepi), growth (pertumbuhan omzet vs periode "
            "sebelumnya yang sama panjang). Sebut hanya angka dari tool. Data hanya baca."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "start_date": {"type": "string", "description": f"Tanggal awal. {_DATE_NOTE}"},
                "end_date": {"type": "string", "description": f"Tanggal akhir. {_DATE_NOTE}"},
                "kind": {"type": "string", "enum": list(KINDS), "description": "Jenis analisis."},
            },
            "required": ["start_date", "end_date", "kind"],
        }

    async def execute(
        self,
        *,
        tenant_id: str,
        user_id: str | None = None,
        start_date: str = "",
        end_date: str = "",
        kind: str = "",
        **kwargs: Any,
    ) -> Any:
        for field, value in (("start_date", start_date), ("end_date", end_date)):
            if not _valid_iso_date(value):
                return self._bad_input(field)
        start, end = date.fromisoformat(start_date.strip()), date.fromisoformat(end_date.strip())
        if start > end:
            return {"status": "invalid_input", "message": "start_date tidak boleh setelah end_date."}
        kind = str(kind or "").strip().lower()
        if kind not in KINDS:
            return {"status": "invalid_input", "message": f"Parameter kind harus salah satu dari {', '.join(KINDS)}."}
        if not user_id:
            return UNAVAILABLE

        async def fetch(s: date, e: date) -> dict | None:
            return await self._grpc().get_product_performance(
                tenant_id=tenant_id, user_id=user_id, start_date=s.isoformat(), end_date=e.isoformat()
            )

        current = await fetch(start, end)
        previous = None
        if kind == "growth" and current is not None:
            days = (end - start).days + 1
            previous = await fetch(start - timedelta(days=days), start - timedelta(days=1))
            if previous is None:
                return UNAVAILABLE
        if current is None:
            return UNAVAILABLE
        if not current["outlets"]:
            return "Belum ada outlet."

        prev_by_outlet = {o["outlet_id"]: o for o in (previous or {}).get("outlets", [])}
        blocks = []
        for outlet in current["outlets"]:
            products = outlet.get("products", [])
            before = prev_by_outlet.get(outlet["outlet_id"], {}).get("products", [])
            if kind == "growth":
                body = _growth(products, before) if products or before else NO_SALES
            elif not products:
                body = NO_SALES
            else:
                body = {"abc": _abc, "matrix": _matrix, "ranking": _ranking}[kind](products)
            blocks.append(f"Outlet {outlet['outlet_name']} ({start} s/d {end})\n{body}")
        return "\n\n".join(blocks)
