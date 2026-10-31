"""Real worker orchestration with transaction markers and captured query contracts."""
import asyncio
import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any
from uuid import UUID, uuid4
from unittest.mock import AsyncMock, MagicMock, Mock, patch

from sqlalchemy.ext.asyncio import AsyncConnection
from sqlalchemy.sql.elements import TextClause
import app.chat.worker as worker_module
from app.ai.openrouter import AIError, Completion, OpenRouterClient
from app.channels.telegram.sender import Delivery, Sender
from app.chat.repositories import Repository
from app.chat.worker import Worker
from app.core.database import Database


class TransactionMarkers:
    def __init__(self, connection: Mock) -> None:
        self.connection = connection
        self.active = False
        self.number = 0
        self.events: list[tuple[str, int]] = []

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[Mock]:
        if self.active:
            raise AssertionError("Nested transaction")
        self.number += 1
        number = self.number
        self.active = True
        self.events.append(("begin", number))
        try:
            yield self.connection
        except BaseException:
            self.events.append(("rollback", number))
            raise
        else:
            self.events.append(("commit", number))
        finally:
            self.active = False


class ConversationTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.connection = Mock(spec=AsyncConnection)
        self.connection.execute = AsyncMock()
        self.boundary = TransactionMarkers(self.connection)
        self.database = Mock(spec=Database)
        self.database.transaction = Mock(side_effect=self.boundary.transaction)
        self.repository = Mock(spec=Repository)
        self.repository.parameters.side_effect = self.parameters
        self.repository.one = AsyncMock()
        self.repository.session = AsyncMock()
        self.repository.message = AsyncMock()
        self.repository.outbox = AsyncMock(side_effect=self.capture_outbox)
        self.repository.execute = AsyncMock(side_effect=self.capture_execute)
        self.ai = Mock(spec=OpenRouterClient)
        self.ai.reply = AsyncMock(side_effect=self.complete)
        self.sender = Mock(spec=Sender)
        self.sender.send = AsyncMock(side_effect=self.send)
        self.worker = Worker(self.database, self.repository, self.ai, self.sender)
        self.session_id, self.user_id, self.assistant_id = uuid4(), uuid4(), uuid4()
        self.inbox: dict[str, Any] = {"id": uuid4(), "identity_id": uuid4(), "chat_id": 7,
            "update_id": 81, "session_id": None, "kind": "TEXT", "content": "current",
            "attempts": 0, "status": "PENDING", "next_attempt_at": None}
        self.identity = {"display_name": "Name", "username": "handle"}
        self.writes: list[tuple[str, dict[str, Any], int]] = []
        self.outboxes: list[tuple[tuple[Any, ...], int]] = []
        self.messages: list[tuple[str, int]] = []
        self.repository.session.return_value = {"id": self.session_id}
        self.repository.message.side_effect = self.capture_message
        self.repository.one.side_effect = [self.identity, None]
        result = MagicMock()
        result.mappings.return_value.all.return_value = [
            {"user_content": "new", "answer": "answer-new"},
            {"user_content": "old", "answer": "answer-old"}]
        result.mappings.return_value.__iter__.return_value = iter([])
        self.connection.execute.return_value = result

    def parameters(self, **values: Any) -> dict[str, Any]:
        return {"scope": "unit-scope", "bot": 71, **values}

    async def capture_execute(self, connection: Mock, sql: str, **values: Any) -> None:
        self.assertTrue(self.boundary.active)
        self.assertIs(connection, self.connection)
        self.writes.append((" ".join(sql.split()), values, self.boundary.number))

    async def capture_message(
        self, connection: Mock, inbox: dict[str, Any], session_id: UUID,
        role: str, content: str, reply_to: UUID | None = None,
        model: str | None = None, response_id: str | None = None,
    ) -> UUID:
        self.assertTrue(self.boundary.active)
        self.assertIs(connection, self.connection)
        self.assertIs(inbox, self.inbox)
        self.assertEqual(session_id, self.session_id)
        self.messages.append((role, self.boundary.number))
        if role == "USER":
            return self.user_id
        if role == "ASSISTANT":
            return self.assistant_id
        raise AssertionError("Unexpected message role")

    async def capture_outbox(self, *args: Any) -> None:
        self.assertTrue(self.boundary.active)
        self.outboxes.append((args, self.boundary.number))

    async def complete(self, messages: list[dict[str, str]]) -> Completion:
        self.assertFalse(self.boundary.active)
        self.assertEqual(self.boundary.events[-1][0], "commit")
        self.assertIn("status='PROCESSING'", self.writes[-1][0])
        return Completion("answer", "model", "provider-id")

    async def send(self, chat: int, content: str, attempts: int) -> Delivery:
        self.assertFalse(self.boundary.active)
        self.assertIn("status='SENDING'", self.writes[-1][0])
        self.assertEqual(self.boundary.events[-1][0], "commit")
        return Delivery("SENT", message_id=91)

    def chunk(self) -> dict[str, Any]:
        return {"id": uuid4(), "content": "answer", "attempts": 0,
                "next_attempt_at": None, "response_kind": "AI", "chunk_number": 0}

    async def test_current_user_history_order_and_atomic_answer(self) -> None:
        await self.worker.process(self.inbox)
        messages = self.ai.reply.await_args.args[0]
        self.assertEqual(messages[2:], [
            {"role": "user", "content": "old"}, {"role": "assistant", "content": "answer-old"},
            {"role": "user", "content": "new"}, {"role": "assistant", "content": "answer-new"},
            {"role": "user", "content": "current"}])
        self.assertEqual(sum(item["content"] == "current" for item in messages), 1)
        statement, parameters = self.connection.execute.await_args.args
        self.assertIsInstance(statement, TextClause)
        sql = " ".join(str(statement).split())
        self.assertIn("i.status='DONE' AND u.inbox_id<>:inbox", sql)
        self.assertIn("ORDER BY u.sequence DESC LIMIT 32", sql)
        self.assertIn("a.reply_to_id=u.id", sql)
        self.assertEqual(parameters, self.parameters(identity=self.inbox["identity_id"], chat=7,
                                                      session=self.session_id, inbox=self.inbox["id"]))
        assistant_call = self.repository.message.await_args_list[1]
        self.assertEqual(assistant_call.args[3:5], ("ASSISTANT", "answer"))
        self.assertEqual(assistant_call.kwargs, {"reply_to": self.user_id, "model": "model", "response_id": "provider-id"})
        self.assertEqual(self.outboxes[0][0][3:], ("AI", ["answer"], self.assistant_id))
        self.assertEqual(self.messages, [("USER", 1), ("ASSISTANT", 2)])
        self.assertEqual(self.outboxes[0][1], 2)
        self.assertEqual(self.writes[-1][2], 2)
        self.assertEqual(self.outboxes[0][1], self.writes[-1][2])
        self.assertIn("status='READY'", self.writes[-1][0])
        self.assertEqual(self.boundary.events, [("begin", 1), ("commit", 1), ("begin", 2), ("commit", 2)])

    async def test_outbox_failure_rolls_back_answer_transaction_without_ready_or_provider_repeat(self) -> None:
        async def failed_outbox(
            connection: Mock, inbox: dict[str, Any], session_id: UUID,
            kind: str, chunks: list[str], assistant_id: UUID | None = None,
        ) -> None:
            self.assertTrue(self.boundary.active)
            self.assertIs(connection, self.connection)
            self.assertIs(inbox, self.inbox)
            self.assertEqual(session_id, self.session_id)
            self.assertEqual((kind, chunks, assistant_id), ("AI", ["answer"], self.assistant_id))
            self.outboxes.append(((connection, inbox, session_id, kind, chunks, assistant_id),
                                  self.boundary.number))
            raise RuntimeError("unit-outbox-failure")

        self.repository.outbox.side_effect = failed_outbox
        with self.assertRaisesRegex(RuntimeError, "^unit-outbox-failure$"):
            await self.worker.process(self.inbox)
        self.assertEqual(self.messages, [("USER", 1), ("ASSISTANT", 2)])
        self.assertEqual(self.outboxes[0][1], 2)
        self.assertEqual(self.boundary.events,
                         [("begin", 1), ("commit", 1), ("begin", 2), ("rollback", 2)])
        self.assertFalse(self.boundary.active)
        self.assertEqual(len(self.writes), 1)
        self.assertIn("status='PROCESSING'", self.writes[0][0])
        self.assertEqual(self.writes[0][2], 1)
        for sql, values, transaction in self.writes:
            self.assertNotIn("status='READY'", sql)
        self.ai.reply.assert_awaited_once()
        self.repository.outbox.assert_awaited_once()
        self.sender.send.assert_not_awaited()

    async def test_commands_reset_only_new_and_never_call_provider(self) -> None:
        for kind, reset in (("START", False), ("SESSION", False), ("NEW", True)):
            with self.subTest(kind=kind):
                self.repository.one.side_effect = None
                self.repository.one.return_value = self.identity
                self.inbox["kind"] = kind
                self.repository.session.reset_mock()
                await self.worker.process(self.inbox)
                self.repository.session.assert_awaited_once_with(self.connection, self.inbox, reset=reset)
                self.assertEqual(self.outboxes[-1][0][3], "COMMAND")
                self.assertIn(str(self.session_id), self.outboxes[-1][0][4][0])
                self.assertIn("status='READY'", self.writes[-1][0])
        self.ai.reply.assert_not_awaited()
        self.repository.message.assert_not_awaited()

    async def test_retry_reuses_existing_user_and_session(self) -> None:
        self.inbox["session_id"] = self.session_id
        self.inbox["attempts"] = 1
        self.repository.one.side_effect = [self.identity, {"id": self.user_id}]
        self.repository.message.side_effect = None
        self.repository.message.return_value = self.assistant_id
        await self.worker.process(self.inbox)
        self.repository.session.assert_not_awaited()
        self.repository.message.assert_awaited_once()
        self.assertEqual(self.repository.message.await_args.args[3], "ASSISTANT")
        self.assertEqual(self.repository.message.await_args.kwargs["reply_to"], self.user_id)
        lookup = " ".join(self.repository.one.await_args_list[1].args[1].split())
        self.assertIn("inbox_id=:inbox AND role='USER'", lookup)

    async def test_transient_provider_schedules_retry_without_assistant(self) -> None:
        self.ai.reply.side_effect = AIError("AI_RATE_LIMIT", retryable=True, delay=17)
        before = datetime.now(timezone.utc)
        await self.worker.process(self.inbox)
        sql, values, transaction = self.writes[-1]
        self.assertIn("status='RETRY'", sql)
        self.assertEqual(values["code"], "AI_RATE_LIMIT")
        self.assertGreaterEqual(values["next"], before + timedelta(seconds=17))
        self.assertEqual(self.repository.message.await_count, 1)
        self.assertEqual(self.repository.message.await_args.args[3], "USER")
        self.repository.outbox.assert_not_awaited()

    async def test_hard_and_exhausted_errors_create_error_without_assistant(self) -> None:
        for code, retryable, attempts in (("AI_TRANSPORT_UNKNOWN", False, 0),
                                          ("AI_TEMPORARY", True, 2)):
            with self.subTest(code=code):
                self.repository.one.side_effect = [self.identity, {"id": self.user_id}]
                self.inbox["session_id"] = self.session_id
                self.inbox["attempts"] = attempts
                self.ai.reply.side_effect = AIError(code, retryable=retryable)
                self.repository.message.reset_mock()
                await self.worker.process(self.inbox)
                self.repository.message.assert_not_awaited()
                self.assertEqual(self.outboxes[-1][0][3], "ERROR")
                self.assertIn("status='READY'", self.writes[-1][0])
                self.assertEqual(self.writes[-1][1]["code"], code)
                self.assertEqual(self.outboxes[-1][1], self.writes[-1][2])

    async def test_ready_delivery_marks_sending_before_send_and_persists_result(self) -> None:
        self.inbox["status"] = "READY"
        chunk = self.chunk()
        self.repository.one.side_effect = [chunk]
        await self.worker.deliver(self.inbox)
        self.ai.reply.assert_not_awaited()
        self.sender.send.assert_awaited_once_with(7, "answer", 1)
        self.assertEqual(self.writes[-1][1], {"status": "SENT", "code": None,
            "message": 91, "next": None, "retry": 0, "id": chunk["id"]})
        query = " ".join(self.repository.one.await_args.args[1].split())
        self.assertIn("identity_id=:identity AND chat_id=:chat AND inbox_id=:inbox", query)

    async def test_future_retry_waits_outside_transaction_without_busy_query(self) -> None:
        chunk = self.chunk()
        chunk["next_attempt_at"] = datetime.now(timezone.utc) + timedelta(seconds=60)
        self.repository.one.side_effect = [chunk]
        sleeps: list[float] = []

        async def stop_after_sleep(delay: float) -> None:
            self.assertFalse(self.boundary.active)
            sleeps.append(delay)
            self.worker.stopping.set()

        proxy = SimpleNamespace(sleep=stop_after_sleep)
        with patch.object(worker_module, "asyncio", proxy):
            await self.worker.deliver(self.inbox)
        self.assertEqual(sleeps, [1.0])
        self.repository.one.assert_awaited_once()
        self.repository.execute.assert_not_awaited()
        self.sender.send.assert_not_awaited()
        self.ai.reply.assert_not_awaited()

    async def test_unknown_and_fatal_delivery_suppress_tail_without_resending(self) -> None:
        for status in ("UNKNOWN", "FAILED"):
            with self.subTest(status=status):
                self.repository.one.side_effect = [self.chunk(), None, {"id": uuid4()}]
                async def classified_send(chat: int, content: str, attempts: int) -> Delivery:
                    self.assertFalse(self.boundary.active)
                    self.assertEqual(self.boundary.events[-1][0], "commit")
                    self.assertIn("status='SENDING'", self.writes[-1][0])
                    return Delivery(status, "SAFE_CODE")

                self.sender.send.side_effect = classified_send
                self.sender.send.reset_mock()
                await self.worker.deliver(self.inbox)
                tail_sql, tail_values, transaction = self.writes[-1]
                self.assertIn("error_code='PREVIOUS_CHUNK_FAILED'", tail_sql)
                self.assertIn("chunk_number>:number AND status='PENDING'", tail_sql)
                self.assertEqual((tail_values["inbox"], tail_values["kind"], tail_values["number"]),
                                 (self.inbox["id"], "AI", 0))
                await self.worker.deliver(self.inbox)
                self.sender.send.assert_awaited_once()
                self.assertEqual(self.writes[-1][1]["status"], "DELIVERY_FAILED")
        self.ai.reply.assert_not_awaited()

    async def test_all_successful_delivery_finishes_done(self) -> None:
        self.repository.one.side_effect = [None, None]
        await self.worker.deliver(self.inbox)
        self.assertEqual(self.writes[-1][1], {"status": "DONE", "id": self.inbox["id"]})
        self.sender.send.assert_not_awaited()
        self.ai.reply.assert_not_awaited()

    async def test_processing_crash_recovers_error_and_unknown_send_suppresses_tail(self) -> None:
        self.inbox["session_id"] = self.session_id
        result = MagicMock()
        result.mappings.return_value.__iter__.return_value = iter([self.inbox])
        self.connection.execute.return_value = result
        await self.worker.recover()
        self.assertIn("status='UNKNOWN', error_code='CRASH_DURING_SEND'", self.writes[0][0])
        self.assertIn("status='SENDING'", self.writes[0][0])
        self.assertIn("error_code='PREVIOUS_CHUNK_UNKNOWN'", self.writes[1][0])
        self.assertIn("p.status IN ('UNKNOWN','FAILED')", self.writes[1][0])
        statement, parameters = self.connection.execute.await_args.args
        self.assertIsInstance(statement, TextClause)
        self.assertIn("status='PROCESSING'", str(statement))
        self.assertEqual(parameters, self.parameters())
        self.assertEqual(self.outboxes[0][0][3], "ERROR")
        self.assertIn("error_code='AI_CRASH_UNKNOWN'", self.writes[-1][0])
        self.assertTrue(self.worker.healthy)
        self.assertEqual({item[2] for item in self.writes}, {1})
        self.ai.reply.assert_not_awaited()
        self.sender.send.assert_not_awaited()
        self.repository.message.assert_not_awaited()
