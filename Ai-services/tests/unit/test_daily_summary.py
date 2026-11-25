"""TASK-080: daily summary template, LLM insight fallback and the dated system prompt."""

import asyncio
import unittest
from datetime import date, datetime, timedelta, timezone
from unittest.mock import AsyncMock, patch

from app.ai.openrouter import AIError, Completion
from app.ai.prompts import SYSTEM, system_prompt
from app.chat import daily_summary
from app.chat.daily_summary import build_summary, format_rupiah, request_insight

DAY = date(2026, 11, 7)


def sample_summary(**overrides: object) -> dict[str, object]:
    summary: dict[str, object] = {
        "outlet_id": "o1",
        "outlet_name": "Kedai Senja",
        "transaction_count": 12,
        "net_sales": "150000.00",
        "expenses_total": "20000",
        "cost_of_goods_sold": "60000",
        "gross_profit": "90000",
        "items_without_cost": 0,
        "cash_difference_total": "-500",
        "closed_shift_count": 2,
        "cancellation_count": 1,
        "discounted_order_count": 3,
        "refund_count": 1,
        "refund_amount": "12500",
        "labels": [
            {"method": "CASH", "label": "Tunai", "count": 7, "net_sales": "90000"},
            {"method": "QRIS", "label": "QRIS Bank X", "count": 5, "net_sales": "60000"},
        ],
        "top_products": [
            {"name": f"Menu {index}", "quantity": 10 - index} for index in range(1, 6)
        ],
    }
    summary.update(overrides)
    return summary


class FormatRupiahTests(unittest.TestCase):
    def test_dot_separators_and_prefix(self) -> None:
        self.assertEqual(format_rupiah("12500"), "Rp12.500")
        self.assertEqual(format_rupiah(12500), "Rp12.500")
        self.assertEqual(format_rupiah("1234567.00"), "Rp1.234.567")
        self.assertEqual(format_rupiah("0"), "Rp0")

    def test_rounds_half_up_and_keeps_negative_sign(self) -> None:
        self.assertEqual(format_rupiah("12500.50"), "Rp12.501")
        self.assertEqual(format_rupiah("12500.49"), "Rp12.500")
        self.assertEqual(format_rupiah("-500"), "-Rp500")

    def test_garbage_or_missing_value_is_zero(self) -> None:
        self.assertEqual(format_rupiah(None), "Rp0")
        self.assertEqual(format_rupiah("abc"), "Rp0")


class BuildSummaryTests(unittest.TestCase):
    def test_core_lines_use_exact_numbers_in_indonesian(self) -> None:
        text = build_summary(sample_summary(), DAY)
        lines = text.splitlines()

        self.assertEqual(lines[0], "Ringkasan harian Kedai Senja - 07-11-2026")
        for expected in (
            "Omzet bersih: Rp150.000",
            "Transaksi: 12",
            "Tunai: Rp90.000",
            "QRIS Bank X: Rp60.000 (5 transaksi)",
            "Pengeluaran kas kecil: Rp20.000",
            "HPP: Rp60.000 | Laba kotor: Rp90.000",
            "Selisih kas: -Rp500 (2 shift ditutup)",
            "Pembatalan: 1 | Order diskon: 3 | Refund: 1 (Rp12.500)",
        ):
            self.assertIn(expected, lines)
        self.assertNotIn("Tunai: Rp90.000 (", text)  # cash is not repeated as a label line

    def test_items_without_cost_note_only_when_positive(self) -> None:
        self.assertNotIn("tanpa HPP", build_summary(sample_summary(), DAY))
        text = build_summary(sample_summary(items_without_cost=3), DAY)
        self.assertIn("(3 item tanpa HPP)", text)

    def test_only_three_best_sellers(self) -> None:
        text = build_summary(sample_summary(), DAY)
        self.assertIn("Menu terlaris:", text)
        self.assertIn("1. Menu 1 (9 terjual)", text)
        self.assertIn("3. Menu 3 (7 terjual)", text)
        self.assertNotIn("Menu 4", text)

    def test_empty_optional_sections_are_omitted(self) -> None:
        text = build_summary(sample_summary(top_products=[], labels=[]), DAY, None, None)
        self.assertNotIn("Menu terlaris", text)
        self.assertNotIn("Stok perlu perhatian", text)
        self.assertNotIn("Perlu dicek", text)
        self.assertIn("Tunai: Rp0", text)
        no_flags = build_summary(sample_summary(), DAY, {"items": []}, {"anomalies": []})
        self.assertNotIn("Stok perlu perhatian", no_flags)
        self.assertNotIn("Perlu dicek", no_flags)

    def test_stock_section_lists_problem_items_with_restock_suggestion(self) -> None:
        restock = {
            "items": [
                {"name": "Susu", "unit": "L", "quantity_on_hand": "1.500", "suggested_quantity": "6.250", "stock_status": "LOW"},
                {"name": "Gula", "unit": "kg", "quantity_on_hand": "-2", "suggested_quantity": "10", "stock_status": "NEGATIVE"},
                {"name": "Kopi", "unit": "kg", "quantity_on_hand": "0", "suggested_quantity": "5", "stock_status": "OUT"},
                {"name": "Es Batu", "unit": "kg", "quantity_on_hand": "50", "suggested_quantity": "1", "stock_status": "OK"},
            ]
        }
        text = build_summary(sample_summary(), DAY, restock)

        self.assertIn("Stok perlu perhatian:", text)
        self.assertIn("- Susu (menipis): sisa 1,5 L, saran beli 6,25 L", text)
        self.assertIn("- Gula (minus): sisa -2 kg, saran beli 10 kg", text)
        self.assertIn("- Kopi (habis): sisa 0 kg, saran beli 5 kg", text)
        self.assertNotIn("Es Batu", text)

    def test_stock_lines_are_capped_with_remainder_count(self) -> None:
        items = [
            {"name": f"Bahan {i}", "unit": "kg", "quantity_on_hand": "1", "suggested_quantity": "1", "stock_status": "LOW"}
            for i in range(10)
        ]
        text = build_summary(sample_summary(), DAY, {"items": items})
        self.assertIn("- Bahan 7 (menipis)", text)
        self.assertNotIn("Bahan 8 (menipis)", text)
        self.assertIn("- dan 2 bahan lain", text)

    def test_anomalies_format_counts_and_cash_difference_money(self) -> None:
        anomalies = {
            "anomalies": [
                {"full_name": "Sari", "kind": "CANCELLATIONS", "value": "5", "baseline_daily_average": "1"},
                {"full_name": "Budi", "kind": "CASH_DIFFERENCE", "value": "25000", "baseline_daily_average": "4000"},
            ]
        }
        text = build_summary(sample_summary(), DAY, None, anomalies)

        self.assertIn("Perlu dicek (di atas 2x rata-rata 30 hari):", text)
        self.assertIn("- Sari: pembatalan 5 (biasanya 1)", text)
        self.assertIn("- Budi: selisih kas Rp25.000 (biasanya Rp4.000)", text)

    def test_anomaly_lines_are_capped_at_six(self) -> None:
        flagged = [
            {"full_name": f"Kasir {i}", "kind": "DISCOUNTS", "value": "9", "baseline_daily_average": "2"}
            for i in range(8)
        ]
        text = build_summary(sample_summary(), DAY, None, {"anomalies": flagged})
        self.assertIn("- Kasir 5: order diskon 9", text)
        self.assertNotIn("Kasir 6", text)

    def test_missing_fields_do_not_raise(self) -> None:
        text = build_summary({}, DAY)
        self.assertIn("Omzet bersih: Rp0", text)
        self.assertIn("Transaksi: 0", text)


class FakeClient:
    """Stands in for OpenRouterClient; records the messages and returns/raises on demand."""

    outcome: object = Completion(content="Penjualan stabil.", model="m", response_id=None)
    delay = 0.0
    calls: list[tuple[list[dict[str, str]], object]] = []

    def __init__(self, settings: object, session: object) -> None:
        pass

    async def reply(self, messages: list[dict[str, str]], tools: object = None) -> Completion:
        type(self).calls.append((messages, tools))
        if type(self).delay:
            await asyncio.sleep(type(self).delay)
        if isinstance(type(self).outcome, BaseException):
            raise type(self).outcome
        return type(self).outcome  # type: ignore[return-value]


class RequestInsightTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        FakeClient.outcome = Completion(content="Penjualan stabil.", model="m", response_id=None)
        FakeClient.delay = 0.0
        FakeClient.calls = []
        self.grpc = AsyncMock()
        for target in (
            patch.object(daily_summary, "OpenRouterClient", FakeClient),
            patch.object(daily_summary, "load_ai_settings", lambda: object()),
        ):
            target.start()
            self.addCleanup(target.stop)

    def complete(self, content: str) -> None:
        FakeClient.outcome = Completion(content=content, model="m", response_id=None)

    async def test_returns_the_sentence_and_sends_summary_without_tools(self) -> None:
        FakeClient.outcome = Completion(
            content="Penjualan stabil.", model="m", response_id=None, input_tokens=120, output_tokens=30
        )
        insight = await request_insight("RINGKASAN Rp150.000", self.grpc, "t1")

        self.assertEqual(insight, "Penjualan stabil.")
        messages, tools = FakeClient.calls[0]
        self.assertEqual([m["role"] for m in messages], ["system", "user"])
        self.assertEqual(messages[1]["content"], "RINGKASAN Rp150.000")
        self.assertIsNone(tools)
        # Tokens are recorded for the tenant; the insight never consumes a question.
        self.grpc.record_ai_usage.assert_awaited_once_with(
            tenant_id="t1", input_tokens=120, output_tokens=30
        )
        self.grpc.consume_ai_question.assert_not_called()

    async def test_only_first_non_empty_line_is_used_and_stripped(self) -> None:
        self.complete("\n  Omzet naik tipis.  \nBaris kedua diabaikan.")
        self.assertEqual(await request_insight("x", self.grpc, "t1"), "Omzet naik tipis.")

    async def test_empty_or_overlong_answer_gives_none(self) -> None:
        self.complete("  \n ")
        self.assertIsNone(await request_insight("x", self.grpc, "t1"))
        self.complete("a" * (daily_summary.MAX_INSIGHT_CHARS + 1))
        self.assertIsNone(await request_insight("x", self.grpc, "t1"))
        self.complete("a" * daily_summary.MAX_INSIGHT_CHARS)
        self.assertIsNotNone(await request_insight("x", self.grpc, "t1"))

    async def test_llm_failure_gives_none_and_logs_only_the_error_type(self) -> None:
        for error in (AIError("AI_HTTP_402"), RuntimeError("secret-detail"), aiohttp_error()):
            with self.subTest(error=type(error).__name__):
                FakeClient.outcome = error
                with self.assertLogs("app.chat.daily_summary", level="INFO") as logs:
                    self.assertIsNone(await request_insight("x", self.grpc, "t1"))
                self.assertEqual(logs.records[-1].error_type, type(error).__name__)
                self.grpc.record_ai_usage.assert_not_awaited()
                self.assertFalse(any("secret-detail" in line for line in logs.output))

    async def test_timeout_gives_none(self) -> None:
        FakeClient.delay = 5
        with patch.object(daily_summary, "INSIGHT_TIMEOUT_SECONDS", 0.01):
            self.assertIsNone(await asyncio.wait_for(request_insight("x", self.grpc, "t1"), 2))


def aiohttp_error() -> Exception:
    import aiohttp

    return aiohttp.ClientConnectionError("down")


class SystemPromptTests(unittest.TestCase):
    def test_contains_todays_jakarta_date_and_base_prompt(self) -> None:
        today = datetime.now(timezone(timedelta(hours=7))).date().isoformat()
        prompt = system_prompt()
        self.assertTrue(prompt.startswith(SYSTEM))
        self.assertIn(f"Tanggal hari ini (Asia/Jakarta): {today}", prompt)

    def test_utc_evening_rolls_over_to_next_jakarta_day(self) -> None:
        late = datetime(2026, 11, 14, 18, 30, tzinfo=timezone.utc)  # 01:30 WIB on the 15th
        early = datetime(2026, 11, 14, 16, 59, tzinfo=timezone.utc)  # 23:59 WIB on the 14th
        self.assertIn("2026-11-15", system_prompt(late))
        self.assertIn("2026-11-14", system_prompt(early))


if __name__ == "__main__":
    unittest.main()
