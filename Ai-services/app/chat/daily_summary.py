"""Daily owner summary: deterministic Indonesian template plus one optional LLM sentence.

All numbers come from the Java gRPC read models; the LLM only adds a one-sentence
insight and its failure never blocks the summary (TASK-080 D2).
"""

import asyncio
import logging
from datetime import date
from decimal import Decimal
from typing import Any

import aiohttp

from app.ai.config import load_ai_settings
from app.analysis.money import format_number as _number
from app.analysis.money import format_rupiah
from app.analysis.money import to_decimal as _decimal
from app.ai.openrouter import OpenRouterClient

logger = logging.getLogger(__name__)

INSIGHT_TIMEOUT_SECONDS = 8
MAX_INSIGHT_CHARS = 300
MAX_STOCK_LINES = 8
MAX_ANOMALY_LINES = 6
MAX_LEAKAGE_LINES = 5
MAX_MARGIN_LINES = 5
_STOCK_LABELS = {"NEGATIVE": "minus", "OUT": "habis", "LOW": "menipis"}
_ANOMALY_LABELS = {
    "CANCELLATIONS": "pembatalan",
    "DISCOUNTS": "order diskon",
    "CASH_DIFFERENCE": "selisih kas",
}
_INSIGHT_SYSTEM = (
    "Kamu asisten bisnis untuk pemilik kedai. Dari ringkasan harian berikut, tulis TEPAT SATU "
    "kalimat wawasan singkat (maksimal 25 kata) dalam bahasa Indonesia. Hanya gunakan angka yang "
    "ada di ringkasan, jangan mengarang, tanpa salam dan tanpa daftar."
)


def _int(value: Any) -> int:
    return int(_decimal(value))


def _count(value: Any) -> str:
    return str(_int(value))


def build_summary(
    summary: dict[str, Any],
    business_date: date,
    restock: dict[str, Any] | None = None,
    anomalies: dict[str, Any] | None = None,
    leakage: dict[str, Any] | None = None,
    margins: dict[str, Any] | None = None,
) -> str:
    """The exact-number summary of one outlet; the optional sections are that outlet's."""
    labels = summary.get("labels", [])
    cash = sum(
        (_decimal(item.get("net_sales")) for item in labels if item.get("method") == "CASH"),
        Decimal(0),
    )
    lines = [
        f"Ringkasan harian {summary.get('outlet_name', '')} - {business_date.strftime('%d-%m-%Y')}",
        "",
        f"Omzet bersih: {format_rupiah(summary.get('net_sales'))}",
        f"Transaksi: {_count(summary.get('transaction_count'))}",
        f"Tunai: {format_rupiah(cash)}",
    ]
    lines += [
        f"{item.get('label', 'Non-tunai')}: {format_rupiah(item.get('net_sales'))}"
        f" ({_count(item.get('count'))} transaksi)"
        for item in labels
        if item.get("method") != "CASH"
    ]
    lines.append(f"Pengeluaran kas kecil: {format_rupiah(summary.get('expenses_total'))}")
    lines.append(
        f"HPP: {format_rupiah(summary.get('cost_of_goods_sold'))}"
        f" | Laba kotor: {format_rupiah(summary.get('gross_profit'))}"
    )
    if _int(summary.get("items_without_cost")) > 0:
        lines.append(f"({_count(summary.get('items_without_cost'))} item tanpa HPP)")
    products = summary.get("top_products", [])[:3]
    if products:
        lines += ["", "Menu terlaris:"]
        lines += [
            f"{index}. {item.get('name', '')} ({_count(item.get('quantity'))} terjual)"
            for index, item in enumerate(products, start=1)
        ]
    lines += [
        "",
        f"Selisih kas: {format_rupiah(summary.get('cash_difference_total'))}"
        f" ({_count(summary.get('closed_shift_count'))} shift ditutup)",
        f"Pembatalan: {_count(summary.get('cancellation_count'))}"
        f" | Order diskon: {_count(summary.get('discounted_order_count'))}"
        f" | Refund: {_count(summary.get('refund_count'))}"
        f" ({format_rupiah(summary.get('refund_amount'))})",
    ]
    stock = [
        item
        for item in (restock or {}).get("items", [])
        if item.get("stock_status") in _STOCK_LABELS
    ]
    if stock:
        lines += ["", "Stok perlu perhatian:"]
        lines += [
            f"- {item.get('name', '')} ({_STOCK_LABELS[item['stock_status']]}): sisa "
            f"{_number(item.get('quantity_on_hand'))} {item.get('unit', '')}, saran beli "
            f"{_number(item.get('suggested_quantity'))} {item.get('unit', '')}"
            for item in stock[:MAX_STOCK_LINES]
        ]
        if len(stock) > MAX_STOCK_LINES:
            lines.append(f"- dan {len(stock) - MAX_STOCK_LINES} bahan lain")
    flagged = (anomalies or {}).get("anomalies", [])
    if flagged:
        lines += ["", "Perlu dicek (di atas 2x rata-rata 30 hari):"]
        for item in flagged[:MAX_ANOMALY_LINES]:
            show = format_rupiah if item.get("kind") == "CASH_DIFFERENCE" else _count
            lines.append(
                f"- {item.get('full_name', '')}: {_ANOMALY_LABELS.get(item.get('kind'), '')} "
                f"{show(item.get('value'))} (biasanya {show(item.get('baseline_daily_average'))})"
            )
    if _int((leakage or {}).get("stock_count_count")) > 0:
        rows = sorted(leakage.get("lines", []), key=lambda item: _decimal(item.get("loss_amount")), reverse=True)
        lines += [
            "",
            f"Kebocoran stok (opname hari ini): rugi {format_rupiah(leakage.get('total_loss'))}, "
            f"{_count(leakage.get('flagged_count'))} bahan menyimpang",
        ]
        lines += [
            f"- {item.get('name', '')}: selisih {_number(item.get('shortage'))} {item.get('unit', '')}, "
            f"rugi {format_rupiah(item.get('loss_amount'))}"
            for item in rows[:MAX_LEAKAGE_LINES]
        ]
        if len(rows) > MAX_LEAKAGE_LINES:
            lines.append(f"- dan {len(rows) - MAX_LEAKAGE_LINES} bahan lain")
    menus = (margins or {}).get("menus", [])
    if menus:
        menus = sorted(menus, key=lambda item: _decimal(item.get("margin_pct")))
        lines += ["", f"Menu di bawah target margin ({_number(margins.get('target_margin_pct'))}%):"]
        lines += [
            f"- {item.get('name', '')}: margin {_number(item.get('margin_pct'))}%, "
            f"saran harga {format_rupiah(item.get('suggested_price'))}"
            for item in menus[:MAX_MARGIN_LINES]
        ]
        if len(menus) > MAX_MARGIN_LINES:
            lines.append(f"- dan {len(menus) - MAX_MARGIN_LINES} menu lain")
    return "\n".join(lines)


async def request_insight(summary_text: str, client: Any, tenant_id: str) -> str | None:
    """One short insight sentence from the LLM, or None on any failure or after 8 seconds.

    Its tokens are reported through `client.record_ai_usage` (never raises); it never
    consumes a question.
    """
    try:
        async with asyncio.timeout(INSIGHT_TIMEOUT_SECONDS):
            async with aiohttp.ClientSession() as session:
                completion = await OpenRouterClient(load_ai_settings(), session).reply(
                    [
                        {"role": "system", "content": _INSIGHT_SYSTEM},
                        {"role": "user", "content": summary_text},
                    ]
                )
    except Exception as error:  # silent fallback by contract; log the type only
        logger.info(
            "Daily summary insight skipped",
            extra={"event": "daily_summary_insight_skipped", "error_type": type(error).__name__},
        )
        return None
    if completion.input_tokens or completion.output_tokens:
        await client.record_ai_usage(
            tenant_id=tenant_id,
            input_tokens=completion.input_tokens,
            output_tokens=completion.output_tokens,
        )
    lines = [line.strip() for line in completion.content.splitlines() if line.strip()]
    insight = lines[0] if lines else ""
    return insight if 0 < len(insight) <= MAX_INSIGHT_CHARS else None
