"""TASK-080: internal daily summary endpoint; fake channel, gRPC client and LLM, no network or DB."""

import json
import re
import unittest
from contextlib import asynccontextmanager
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, create_autospec, patch
from uuid import UUID

from fastapi.testclient import TestClient
from sqlalchemy.dialects import postgresql
from sqlalchemy.exc import OperationalError
from sqlalchemy.ext.asyncio import AsyncConnection

import app.api.internal_daily_summaries as endpoint
from app.api.internal_daily_summaries import get_grpc_client
from app.api.internal_members import MAX_BODY_BYTES, get_internal_api_settings
from app.application import app
from app.channels.telegram.sender import Delivery
from app.chat.repositories import list_telegram_owner_recipients
from app.config import InternalApiSettings
from app.integrations.grpc.client import CashierGrpcClient

KEY = "unit-test-internal-key-0123456789"
HEADERS = {"X-Internal-Service-Key": KEY}
PATH = "/api/v1/internal/daily-summaries"
TENANT = "3f2b8c1e-5a47-4c6e-9d11-0a1b2c3d4e5f"
OUTLET = "9c1d2e3f-0000-4000-8000-000000000001"
OTHER_OUTLET = "9c1d2e3f-0000-4000-8000-000000000002"
OWNER_A = UUID("7a9d4e21-8b3c-4f60-a512-9e8d7c6b5a40")
OWNER_B = UUID("7a9d4e21-8b3c-4f60-a512-9e8d7c6b5a41")
CHAT_A = 5550001
CHAT_B = 5550002
BODY = {"tenant_id": TENANT, "outlet_id": OUTLET, "business_date": "2026-11-07"}
LOGGER = "app.api.internal_daily_summaries"


def sales(outlet_id: str = OUTLET, name: str = "Kedai Senja") -> dict[str, object]:
    return {
        "outlet_id": outlet_id,
        "outlet_name": name,
        "transaction_count": 12,
        "net_sales": "150000",
        "labels": [{"method": "CASH", "label": "Tunai", "count": 12, "net_sales": "150000"}],
    }


def only(*sections: dict[str, object]) -> dict[str, object]:
    return {"outlets": list(sections)}


class FakeGrpc:
    """Same call shapes as CashierGrpcClient, scripted per user id (string)."""

    def __init__(self) -> None:
        self.sales: dict[str, object] = {}
        self.restock: dict[str, object] = {}
        self.anomalies: dict[str, object] = {}
        self.fail: set[str] = set()
        self.calls: list[tuple[str, ...]] = []

    async def __aenter__(self) -> "FakeGrpc":
        return self

    async def __aexit__(self, *args: object) -> None:
        return None

    async def get_sales_summary(self, tenant_id: str, user_id: str, start_date: str, end_date: str):
        self.calls.append(("sales", tenant_id, user_id, start_date, end_date))
        if user_id in self.fail:
            raise RuntimeError("boom")
        return self.sales.get(user_id)

    async def get_restock_suggestions(self, tenant_id: str, user_id: str):
        self.calls.append(("restock", tenant_id, user_id))
        return self.restock.get(user_id)

    async def get_anomalies(self, tenant_id: str, user_id: str, date: str):
        self.calls.append(("anomalies", tenant_id, user_id, date))
        return self.anomalies.get(user_id)


class FakeChannel:
    """Telegram channel double: records sent chunks, statuses scripted per chat id."""

    def __init__(self, is_ready: bool = True) -> None:
        self.is_ready = is_ready
        self.sent: list[tuple[int, str]] = []
        self.attempts: list[tuple[int, int]] = []
        self.script: dict[int, list[Delivery]] = {}
        self.intake = SimpleNamespace(sender=self)
        self.database = SimpleNamespace(transaction=self._transaction)

    async def ready(self) -> bool:
        return self.is_ready

    @asynccontextmanager
    async def _transaction(self):
        yield object()

    async def send(self, chat_id: int, content: str, attempts: int) -> Delivery:
        self.attempts.append((chat_id, attempts))
        queue = self.script.get(chat_id)
        delivery = queue.pop(0) if queue else Delivery("SENT")
        if delivery.status == "SENT":
            self.sent.append((chat_id, content))
        return delivery


class DailySummaryEndpointTests(unittest.TestCase):
    def setUp(self) -> None:
        self.settings = InternalApiSettings(KEY)
        self.grpc = FakeGrpc()
        self.channel = FakeChannel()
        self.recipients = AsyncMock(return_value=[(OWNER_A, CHAT_A)])
        self.insight = AsyncMock(return_value="Penjualan hari ini stabil.")
        app.dependency_overrides[get_internal_api_settings] = lambda: self.settings
        app.dependency_overrides[get_grpc_client] = lambda: self.grpc
        app.state.telegram_channel = self.channel
        for target in (
            patch.object(endpoint, "list_telegram_owner_recipients", self.recipients),
            patch.object(endpoint, "request_insight", self.insight),
            patch.object(endpoint, "asyncio", SimpleNamespace(sleep=AsyncMock())),  # no real waits
        ):
            target.start()
            self.addCleanup(target.stop)
        self.grpc.sales[str(OWNER_A)] = only(sales())
        # No context manager: the lifespan (Telegram, PostgreSQL) is deliberately not started.
        self.client = TestClient(app)

    def tearDown(self) -> None:
        app.dependency_overrides.clear()
        if hasattr(app.state, "telegram_channel"):
            del app.state.telegram_channel

    def post(self, body: object = None, headers: dict[str, str] | None = None):
        payload = BODY if body is None else body
        content = payload if isinstance(payload, (bytes, str)) else json.dumps(payload)
        return self.client.post(
            PATH,
            content=content,
            headers={"Content-Type": "application/json", **(HEADERS if headers is None else headers)},
        )

    # --- auth and validation -------------------------------------------------

    def test_missing_or_wrong_key_is_401_and_nothing_runs(self) -> None:
        for headers in ({}, {"X-Internal-Service-Key": KEY[:-1] + "x"}, {"X-Internal-Service-Key": KEY[:-1]}):
            response = self.post(headers=headers)
            self.assertEqual(response.status_code, 401)
            self.assertEqual(response.json(), {"error": "unauthorized"})
        self.recipients.assert_not_awaited()
        self.assertEqual(self.grpc.calls, [])
        self.assertEqual(self.channel.sent, [])

    def test_unset_key_is_503_even_with_a_header(self) -> None:
        self.settings = InternalApiSettings("")
        self.assertEqual(self.post().status_code, 503)
        self.assertEqual(self.post(headers={}).status_code, 503)
        self.recipients.assert_not_awaited()

    def test_key_is_checked_before_channel_state(self) -> None:
        self.channel.is_ready = False
        self.assertEqual(self.post(headers={}).status_code, 401)

    def test_invalid_body_is_422_with_field_names_only(self) -> None:
        cases = {
            "tenant_id": {**BODY, "tenant_id": "not-a-uuid"},
            "outlet_id": {**BODY, "outlet_id": "nope"},
            "business_date": {**BODY, "business_date": "07/11/2026"},
        }
        for field, body in cases.items():
            with self.subTest(field=field):
                response = self.post(body)
                self.assertEqual(response.status_code, 422)
                self.assertEqual(response.json(), {"error": "validation_error", "fields": [field]})
                self.assertNotIn("not-a-uuid", response.text)
        missing = self.post({"tenant_id": TENANT})
        self.assertEqual(missing.status_code, 422)
        self.assertEqual(missing.json()["fields"], ["business_date", "outlet_id"])
        self.assertEqual(self.post("{not json").status_code, 422)
        self.assertEqual(self.post([1, 2]).status_code, 422)
        oversized = self.post(json.dumps({**BODY, "padding": "x" * (MAX_BODY_BYTES + 1)}))
        self.assertEqual(oversized.status_code, 422)
        self.assertEqual(oversized.json(), {"error": "validation_error", "fields": ["body"]})
        self.recipients.assert_not_awaited()

    def test_validation_runs_before_channel_check(self) -> None:
        self.channel.is_ready = False
        self.assertEqual(self.post({"tenant_id": "x"}).status_code, 422)

    # --- 503 -----------------------------------------------------------------

    def test_channel_missing_or_not_ready_is_503(self) -> None:
        self.channel.is_ready = False
        response = self.post()
        self.assertEqual((response.status_code, response.json()), (503, {"error": "service_unavailable"}))
        del app.state.telegram_channel
        self.assertEqual(self.post().status_code, 503)
        self.recipients.assert_not_awaited()
        self.assertEqual(self.channel.sent, [])

    def test_recipient_lookup_database_error_is_503(self) -> None:
        self.recipients.side_effect = OperationalError("select", {}, Exception("db down"))
        response = self.post()
        self.assertEqual(response.status_code, 503)
        self.assertNotIn("db down", response.text)

    # --- delivery ------------------------------------------------------------

    def test_delivers_to_each_owner_and_counts(self) -> None:
        self.recipients.return_value = [(OWNER_A, CHAT_A), (OWNER_B, CHAT_B)]
        self.grpc.sales[str(OWNER_B)] = only(sales())

        with self.assertLogs(LOGGER, level="INFO") as logs:
            response = self.post()

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"delivered": 2, "skipped": 0})
        self.assertEqual([chat for chat, _ in self.channel.sent], [CHAT_A, CHAT_B])
        text = self.channel.sent[0][1]
        self.assertIn("Ringkasan harian Kedai Senja - 07-11-2026", text)
        self.assertIn("Omzet bersih: Rp150.000", text)
        self.assertTrue(text.endswith("Catatan: Penjualan hari ini stabil."))
        self.recipients.assert_awaited_once()
        self.assertEqual(self.recipients.await_args.args[1], UUID(TENANT))
        self.assertIn(("sales", TENANT, str(OWNER_A), "2026-11-07", "2026-11-07"), self.grpc.calls)
        self.assertIn(("restock", TENANT, str(OWNER_A)), self.grpc.calls)
        self.assertIn(("anomalies", TENANT, str(OWNER_A), "2026-11-07"), self.grpc.calls)
        joined = "\n".join(logs.output) + response.text
        for secret in (KEY, str(CHAT_A), str(CHAT_B)):
            self.assertNotIn(secret, joined)

    def test_no_recipients_gives_zero_counts(self) -> None:
        self.recipients.return_value = []
        response = self.post()
        self.assertEqual((response.status_code, response.json()), (200, {"delivered": 0, "skipped": 0}))
        self.assertEqual(self.grpc.calls, [])

    def test_uses_only_the_requested_outlet_section(self) -> None:
        self.grpc.sales[str(OWNER_A)] = only(
            sales(OTHER_OUTLET, "Kedai Lain"), sales(OUTLET, "Kedai Senja")
        )
        stock = lambda name, outlet: {  # noqa: E731
            "outlet_id": outlet,
            "items": [{"name": name, "unit": "kg", "quantity_on_hand": "1", "suggested_quantity": "2", "stock_status": "LOW"}],
        }
        self.grpc.restock[str(OWNER_A)] = only(stock("Gula Lain", OTHER_OUTLET), stock("Susu Senja", OUTLET))
        flag = lambda name, outlet: {  # noqa: E731
            "outlet_id": outlet,
            "anomalies": [{"full_name": name, "kind": "CANCELLATIONS", "value": "5", "baseline_daily_average": "1"}],
        }
        self.grpc.anomalies[str(OWNER_A)] = only(flag("Kasir Lain", OTHER_OUTLET), flag("Kasir Senja", OUTLET))

        self.assertEqual(self.post().json(), {"delivered": 1, "skipped": 0})

        text = self.channel.sent[0][1]
        self.assertIn("Kedai Senja", text)
        self.assertIn("Susu Senja", text)
        self.assertIn("Kasir Senja", text)
        for foreign in ("Kedai Lain", "Gula Lain", "Kasir Lain"):
            self.assertNotIn(foreign, text)

    def test_missing_restock_and_anomaly_sections_still_send_the_summary(self) -> None:
        self.assertEqual(self.post().json(), {"delivered": 1, "skipped": 0})
        text = self.channel.sent[0][1]
        self.assertNotIn("Stok perlu perhatian", text)
        self.assertNotIn("Perlu dicek", text)

    def test_recipient_without_the_outlet_or_without_data_is_skipped(self) -> None:
        owner_c = UUID("7a9d4e21-8b3c-4f60-a512-9e8d7c6b5a42")
        self.recipients.return_value = [(OWNER_A, CHAT_A), (OWNER_B, CHAT_B), (owner_c, 5550003)]
        self.grpc.sales[str(OWNER_B)] = only(sales(OTHER_OUTLET))  # no section for the request
        # owner_c: client returns None (PERMISSION_DENIED / error)

        response = self.post()

        self.assertEqual(response.json(), {"delivered": 1, "skipped": 2})
        self.assertEqual([chat for chat, _ in self.channel.sent], [CHAT_A])
        self.assertNotIn(("restock", TENANT, str(OWNER_B)), self.grpc.calls)

    def test_one_failing_recipient_does_not_stop_the_others(self) -> None:
        self.recipients.return_value = [(OWNER_A, CHAT_A), (OWNER_B, CHAT_B)]
        self.grpc.sales[str(OWNER_B)] = only(sales())
        self.grpc.fail.add(str(OWNER_A))

        with self.assertLogs(LOGGER, level="WARNING") as logs:
            response = self.post()

        self.assertEqual(response.json(), {"delivered": 1, "skipped": 1})
        self.assertEqual([chat for chat, _ in self.channel.sent], [CHAT_B])
        self.assertTrue(any("RuntimeError" in line for line in logs.output))
        self.assertFalse(any("boom" in line for line in logs.output))

    def test_llm_failure_sends_the_summary_without_the_note(self) -> None:
        self.insight.return_value = None
        self.assertEqual(self.post().json(), {"delivered": 1, "skipped": 0})
        text = self.channel.sent[0][1]
        self.assertNotIn("Catatan", text)
        self.assertIn("Omzet bersih: Rp150.000", text)

    # --- sender statuses -----------------------------------------------------

    def test_pending_is_retried_then_delivered(self) -> None:
        self.channel.script[CHAT_A] = [Delivery("PENDING", retry_after=2), Delivery("SENT")]
        self.assertEqual(self.post().json(), {"delivered": 1, "skipped": 0})
        self.assertEqual(self.channel.attempts, [(CHAT_A, 1), (CHAT_A, 2)])

    def test_pending_forever_gives_up_after_three_attempts(self) -> None:
        self.channel.script[CHAT_A] = [Delivery("PENDING", retry_after=1)] * 5
        self.assertEqual(self.post().json(), {"delivered": 0, "skipped": 1})
        self.assertEqual(len(self.channel.attempts), endpoint.MAX_SEND_ATTEMPTS)
        self.assertEqual(self.channel.sent, [])

    def test_failed_delivery_is_skipped_without_retry(self) -> None:
        self.channel.script[CHAT_A] = [Delivery("FAILED", code="blocked")]
        self.assertEqual(self.post().json(), {"delivered": 0, "skipped": 1})
        self.assertEqual(len(self.channel.attempts), 1)

    def test_every_chunk_must_be_sent(self) -> None:
        self.channel.script[CHAT_A] = [Delivery("SENT"), Delivery("FAILED")]
        with patch.object(endpoint, "split_chunks", lambda text: ["part-1", "part-2", "part-3"]):
            response = self.post()
        self.assertEqual(response.json(), {"delivered": 0, "skipped": 1})
        self.assertEqual(self.channel.sent, [(CHAT_A, "part-1")])  # stops at the failed chunk

    # --- contract with the real client ----------------------------------------

    def test_calls_match_the_real_client_signatures(self) -> None:
        """Autospec enforces keyword-only parameters; a mismatch raises TypeError and skips."""
        real = create_autospec(CashierGrpcClient, instance=True)
        real.get_sales_summary.return_value = only(sales())
        real.get_restock_suggestions.return_value = None
        real.get_anomalies.return_value = None
        app.dependency_overrides[get_grpc_client] = lambda: real

        response = self.post()

        self.assertEqual(response.json(), {"delivered": 1, "skipped": 0})
        real.get_sales_summary.assert_awaited_once()
        real.get_restock_suggestions.assert_awaited_once()
        real.get_anomalies.assert_awaited_once()


class OwnerRecipientQueryTests(unittest.IsolatedAsyncioTestCase):
    async def run_query(self, rows: list[tuple[object, object]]):
        connection = Mock(spec=AsyncConnection)
        connection.execute = AsyncMock(return_value=Mock(**{"all.return_value": rows}))
        tenant = UUID(TENANT)
        found = await list_telegram_owner_recipients(connection, tenant)
        statement = connection.execute.await_args.args[0]
        compiled = statement.compile(
            dialect=postgresql.dialect(), compile_kwargs={"render_postcompile": True}
        )
        return found, re.sub(r"\s+", " ", str(compiled)), dict(compiled.params), tenant

    async def test_query_is_tenant_scoped_to_active_telegram_owners(self) -> None:
        _, sql, params, tenant = await self.run_query([])

        self.assertIn("tenant_memberships.tenant_id =", sql)
        self.assertIn("tenant_memberships.java_user_id IS NOT NULL", sql)
        self.assertIn("tenant_members.role =", sql)
        self.assertIn("tenant_members.status =", sql)
        self.assertIn("channel_users.platform =", sql)
        # staff of the same tenant or owners of other tenants must not match
        self.assertIn("tenant_members.tenant_id = tenant_memberships.tenant_id", sql)
        self.assertIn("tenant_members.java_user_id = tenant_memberships.java_user_id", sql)
        values = list(params.values())
        for expected in (tenant, "OWNERMERCHANT", "ACTIVE", "TELEGRAM"):
            self.assertIn(expected, values)
        self.assertNotIn("CASHIER", values)
        self.assertNotIn("phone", sql)

    async def test_returns_numeric_chat_ids_and_skips_non_numeric(self) -> None:
        found, *_ = await self.run_query(
            [(OWNER_A, "5550001"), (OWNER_B, "not-a-number"), (OWNER_A, "-1001234")]
        )
        self.assertEqual(found, [(OWNER_A, 5550001), (OWNER_A, -1001234)])


if __name__ == "__main__":
    unittest.main()
