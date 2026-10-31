"""Worker orchestration with named repository methods and transaction markers."""

import asyncio
import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, Mock, patch
from uuid import UUID, uuid4

from sqlalchemy.ext.asyncio import AsyncConnection

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
        self.boundary = TransactionMarkers(self.connection)
        self.database = Mock(spec=Database)
        self.database.transaction = Mock(side_effect=self.boundary.transaction)
        self.repository = Mock(spec=Repository)
        self.ai = Mock(spec=OpenRouterClient)
        self.ai.reply = AsyncMock(side_effect=self.complete)
        self.sender = Mock(spec=Sender)
        self.sender.send = AsyncMock(side_effect=self.send)
        self.worker = Worker(self.database, self.repository, self.ai, self.sender)
        self.session_id, self.user_id, self.assistant_id = uuid4(), uuid4(), uuid4()
        self.inbox: dict[str, Any] = {
            "id": uuid4(), "identity_id": uuid4(), "chat_id": 7,
            "update_id": 81, "session_id": None, "kind": "TEXT",
            "content": "current", "attempts": 0, "status": "PENDING",
            "next_attempt_at": None,
        }
        self.identity = {"display_name": "Name", "username": "handle"}
        self.operations: list[tuple[str, tuple[Any, ...], int]] = []
        self.messages: list[tuple[str, int]] = []
        self.outboxes: list[tuple[tuple[Any, ...], int]] = []
        self.repository.recover_sending = AsyncMock(side_effect=self.recover_sending)
        self.repository.suppress_tails_after_unknown = AsyncMock(side_effect=self.suppress_tails)
        self.repository.processing_inboxes = AsyncMock(return_value=[])
        self.repository.next_inbox = AsyncMock()
        self.repository.identity = AsyncMock(return_value=self.identity)
        self.repository.session = AsyncMock(return_value={"id": self.session_id})
        self.repository.existing_user_message = AsyncMock(return_value=None)
        self.repository.completed_history = AsyncMock(
            return_value=[("old", "answer-old"), ("new", "answer-new")]
        )
        self.repository.message = AsyncMock(side_effect=self.capture_message)
        self.repository.outbox = AsyncMock(side_effect=self.capture_outbox)
        self.repository.mark_processing = AsyncMock(side_effect=self.mark_processing)
        self.repository.mark_retry = AsyncMock(side_effect=self.mark_retry)
        self.repository.mark_ready = AsyncMock(side_effect=self.mark_ready)
        self.repository.mark_command_ready = AsyncMock(side_effect=self.mark_command_ready)
        self.repository.pending_outbox = AsyncMock(return_value=None)
        self.repository.has_failed_outbox = AsyncMock(return_value=False)
        self.repository.mark_done = AsyncMock(side_effect=self.mark_done)
        self.repository.mark_delivery_failed = AsyncMock(side_effect=self.mark_delivery_failed)
        self.repository.mark_sending = AsyncMock(side_effect=self.mark_sending)
        self.repository.mark_outbox_result = AsyncMock(side_effect=self.mark_outbox_result)
        self.repository.fail_later_chunks = AsyncMock(side_effect=self.fail_later_chunks)

    def record(self, name: str, connection: Mock, *values: Any) -> None:
        self.assertTrue(self.boundary.active)
        self.assertIs(connection, self.connection)
        self.operations.append((name, values, self.boundary.number))

    async def recover_sending(self, connection: Mock) -> None:
        self.record("recover_sending", connection)

    async def suppress_tails(self, connection: Mock) -> None:
        self.record("suppress_tails", connection)

    async def capture_message(
        self, connection: Mock, inbox: dict[str, Any], session_id: UUID,
        role: str, content: str, reply_to: UUID | None = None,
        model: str | None = None, response_id: str | None = None,
    ) -> UUID:
        self.record("message", connection, role, content, reply_to, model, response_id)
        self.assertIs(inbox, self.inbox)
        self.assertEqual(session_id, self.session_id)
        self.messages.append((role, self.boundary.number))
        if role == "USER":
            return self.user_id
        if role == "ASSISTANT":
            return self.assistant_id
        raise AssertionError("Unexpected message role")

    async def capture_outbox(
        self, connection: Mock, inbox: dict[str, Any], session_id: UUID,
        kind: str, chunks: list[str], assistant_id: UUID | None = None,
    ) -> None:
        self.record("outbox", connection, kind, chunks, assistant_id)
        self.assertIs(inbox, self.inbox)
        self.assertEqual(session_id, self.session_id)
        self.outboxes.append(((connection, inbox, session_id, kind, chunks, assistant_id), self.boundary.number))

    async def mark_processing(self, connection: Mock, inbox: dict[str, Any], session_id: UUID) -> None:
        self.record("processing", connection, inbox, session_id)

    async def mark_retry(
        self, connection: Mock, inbox: dict[str, Any], code: str, next_attempt: datetime,
    ) -> None:
        self.record("retry", connection, inbox, code, next_attempt)

    async def mark_ready(
        self, connection: Mock, inbox: dict[str, Any], error_code: str | None,
    ) -> None:
        self.record("ready", connection, inbox, error_code)

    async def mark_command_ready(
        self, connection: Mock, inbox: dict[str, Any], session_id: UUID,
    ) -> None:
        self.record("command_ready", connection, inbox, session_id)

    async def mark_done(self, connection: Mock, inbox: dict[str, Any]) -> None:
        self.record("done", connection, inbox)

    async def mark_delivery_failed(self, connection: Mock, inbox: dict[str, Any]) -> None:
        self.record("delivery_failed", connection, inbox)

    async def mark_sending(
        self, connection: Mock, inbox: dict[str, Any], chunk: dict[str, Any],
    ) -> None:
        self.record("sending", connection, inbox, chunk)

    async def mark_outbox_result(
        self, connection: Mock, inbox: dict[str, Any], chunk: dict[str, Any],
        status: str, code: str | None, message_id: int | None,
        next_attempt: datetime | None, retry_after: int,
    ) -> None:
        self.record(
            "outbox_result", connection, inbox, chunk, status, code,
            message_id, next_attempt, retry_after,
        )

    async def fail_later_chunks(
        self, connection: Mock, inbox: dict[str, Any], chunk: dict[str, Any],
    ) -> None:
        self.record("fail_later_chunks", connection, inbox, chunk)

    async def complete(self, messages: list[dict[str, str]]) -> Completion:
        self.assertFalse(self.boundary.active)
        self.assertEqual(self.boundary.events[-1], ("commit", 1))
        self.assertEqual(self.operations[-1][0], "processing")
        self.assertEqual(self.operations[-1][2], 1)
        return Completion("answer", "model", "provider-id")

    async def send(self, chat: int, content: str, attempts: int) -> Delivery:
        self.assertFalse(self.boundary.active)
        self.assertEqual(self.operations[-1][0], "sending")
        self.assertEqual(self.boundary.events[-1][0], "commit")
        return Delivery("SENT", message_id=91)

    def chunk(self) -> dict[str, Any]:
        return {
            "id": uuid4(), "content": "answer", "attempts": 0,
            "next_attempt_at": None, "response_kind": "AI", "chunk_number": 0,
        }

    async def test_current_user_history_order_and_atomic_answer(self) -> None:
        await self.worker.process(self.inbox)
        messages = self.ai.reply.await_args.args[0]
        self.assertEqual(messages[2:], [
            {"role": "user", "content": "old"},
            {"role": "assistant", "content": "answer-old"},
            {"role": "user", "content": "new"},
            {"role": "assistant", "content": "answer-new"},
            {"role": "user", "content": "current"},
        ])
        self.assertEqual(sum(item["content"] == "current" for item in messages), 1)
        self.repository.completed_history.assert_awaited_once_with(
            self.connection, self.inbox, self.session_id
        )
        assistant_call = self.repository.message.await_args_list[1]
        self.assertEqual(assistant_call.args[3:5], ("ASSISTANT", "answer"))
        self.assertEqual(
            assistant_call.kwargs,
            {"reply_to": self.user_id, "model": "model", "response_id": "provider-id"},
        )
        self.assertEqual(self.outboxes[0][0][3:], ("AI", ["answer"], self.assistant_id))
        self.assertEqual(self.messages, [("USER", 1), ("ASSISTANT", 2)])
        self.assertEqual(self.operations[-2][0], "outbox")
        self.assertEqual(self.operations[-1], ("ready", (self.inbox, None), 2))
        self.assertEqual(self.outboxes[0][1], 2)
        self.assertEqual(
            self.boundary.events,
            [("begin", 1), ("commit", 1), ("begin", 2), ("commit", 2)],
        )

    async def test_outbox_failure_rolls_back_answer_transaction_without_ready_or_provider_repeat(self) -> None:
        async def failed_outbox(
            connection: Mock, inbox: dict[str, Any], session_id: UUID,
            kind: str, chunks: list[str], assistant_id: UUID | None = None,
        ) -> None:
            await self.capture_outbox(connection, inbox, session_id, kind, chunks, assistant_id)
            self.assertEqual((kind, chunks, assistant_id), ("AI", ["answer"], self.assistant_id))
            raise RuntimeError("unit-outbox-failure")

        self.repository.outbox.side_effect = failed_outbox
        with self.assertRaisesRegex(RuntimeError, "^unit-outbox-failure$"):
            await self.worker.process(self.inbox)
        self.assertEqual(self.messages, [("USER", 1), ("ASSISTANT", 2)])
        self.assertEqual(self.outboxes[0][1], 2)
        self.assertEqual(
            self.boundary.events,
            [("begin", 1), ("commit", 1), ("begin", 2), ("rollback", 2)],
        )
        self.assertFalse(self.boundary.active)
        self.assertEqual([item[0] for item in self.operations if item[0] == "processing"], ["processing"])
        self.assertFalse(any(item[0] == "ready" for item in self.operations))
        self.ai.reply.assert_awaited_once()
        self.repository.outbox.assert_awaited_once()
        self.sender.send.assert_not_awaited()

    async def test_commands_reset_only_new_and_never_call_provider(self) -> None:
        for kind, reset in (("START", False), ("SESSION", False), ("NEW", True)):
            with self.subTest(kind=kind):
                self.inbox["kind"] = kind
                self.inbox["session_id"] = None
                self.repository.session.reset_mock()
                await self.worker.process(self.inbox)
                self.repository.session.assert_awaited_once_with(
                    self.connection, self.inbox, reset=reset
                )
                self.assertEqual(self.outboxes[-1][0][3], "COMMAND")
                self.assertIn(str(self.session_id), self.outboxes[-1][0][4][0])
                self.assertEqual(self.operations[-1][0], "command_ready")
                self.assertEqual(self.operations[-1][1], (self.inbox, self.session_id))
        self.ai.reply.assert_not_awaited()
        self.repository.message.assert_not_awaited()

    async def test_retry_reuses_existing_user_and_session(self) -> None:
        self.inbox["session_id"] = self.session_id
        self.inbox["attempts"] = 1
        self.repository.existing_user_message.return_value = {"id": self.user_id}
        await self.worker.process(self.inbox)
        self.repository.session.assert_not_awaited()
        self.repository.existing_user_message.assert_awaited_once_with(
            self.connection, self.inbox, self.session_id
        )
        self.repository.message.assert_awaited_once()
        self.assertEqual(self.repository.message.await_args.args[3], "ASSISTANT")
        self.assertEqual(self.repository.message.await_args.kwargs["reply_to"], self.user_id)

    async def test_transient_provider_schedules_retry_without_assistant(self) -> None:
        self.ai.reply.side_effect = AIError("AI_RATE_LIMIT", retryable=True, delay=17)
        before = datetime.now(timezone.utc)
        await self.worker.process(self.inbox)
        self.repository.mark_retry.assert_awaited_once()
        args = self.repository.mark_retry.await_args.args
        self.assertEqual(args[:3], (self.connection, self.inbox, "AI_RATE_LIMIT"))
        self.assertGreaterEqual(args[3], before + timedelta(seconds=17))
        self.assertEqual(self.operations[-1][0], "retry")
        self.assertEqual(self.operations[-1][2], 2)
        self.assertEqual(self.repository.message.await_count, 1)
        self.assertEqual(self.repository.message.await_args.args[3], "USER")
        self.repository.outbox.assert_not_awaited()
        self.repository.mark_ready.assert_not_awaited()

    async def test_hard_and_exhausted_errors_create_error_without_assistant(self) -> None:
        for code, retryable, attempts in (
            ("AI_TRANSPORT_UNKNOWN", False, 0),
            ("AI_TEMPORARY", True, 2),
        ):
            with self.subTest(code=code):
                self.repository.existing_user_message.return_value = {"id": self.user_id}
                self.inbox["session_id"] = self.session_id
                self.inbox["attempts"] = attempts
                self.ai.reply.side_effect = AIError(code, retryable=retryable)
                self.repository.message.reset_mock()
                await self.worker.process(self.inbox)
                self.repository.message.assert_not_awaited()
                self.assertEqual(self.outboxes[-1][0][3], "ERROR")
                self.assertEqual(self.operations[-1][0], "ready")
                self.assertEqual(self.operations[-1][1], (self.inbox, code))
                self.assertEqual(self.outboxes[-1][1], self.operations[-1][2])
        self.repository.mark_retry.assert_not_awaited()

    async def test_ready_delivery_marks_sending_before_send_and_persists_result(self) -> None:
        self.inbox["status"] = "READY"
        chunk = self.chunk()
        self.repository.pending_outbox.return_value = chunk
        await self.worker.deliver(self.inbox)
        self.ai.reply.assert_not_awaited()
        self.repository.pending_outbox.assert_awaited_once_with(self.connection, self.inbox)
        self.repository.mark_sending.assert_awaited_once_with(self.connection, self.inbox, chunk)
        self.sender.send.assert_awaited_once_with(7, "answer", 1)
        self.repository.mark_outbox_result.assert_awaited_once_with(
            self.connection, self.inbox, chunk, "SENT", None, 91, None, 0
        )
        self.assertEqual(self.operations[-1][0], "outbox_result")
        self.assertEqual(self.operations[-1][2], 2)
        self.assertEqual(
            self.boundary.events,
            [("begin", 1), ("commit", 1), ("begin", 2), ("commit", 2)],
        )

    async def test_future_retry_waits_outside_transaction_without_busy_query(self) -> None:
        chunk = self.chunk()
        chunk["next_attempt_at"] = datetime.now(timezone.utc) + timedelta(seconds=60)
        self.repository.pending_outbox.return_value = chunk
        sleeps: list[float] = []

        async def stop_after_sleep(delay: float) -> None:
            self.assertFalse(self.boundary.active)
            sleeps.append(delay)
            self.worker.stopping.set()

        proxy = SimpleNamespace(sleep=stop_after_sleep)
        with patch.object(worker_module, "asyncio", proxy):
            await self.worker.deliver(self.inbox)
        self.assertEqual(sleeps, [1.0])
        self.repository.pending_outbox.assert_awaited_once()
        self.repository.mark_sending.assert_not_awaited()
        self.repository.mark_outbox_result.assert_not_awaited()
        self.repository.has_failed_outbox.assert_not_awaited()
        self.sender.send.assert_not_awaited()
        self.ai.reply.assert_not_awaited()
        self.assertEqual(self.boundary.events, [("begin", 1), ("commit", 1)])

    async def test_unknown_and_fatal_delivery_suppress_tail_without_resending(self) -> None:
        for status in ("UNKNOWN", "FAILED"):
            with self.subTest(status=status):
                chunk = self.chunk()
                self.repository.pending_outbox.side_effect = [chunk, None]
                self.repository.has_failed_outbox.return_value = True

                async def classified_send(chat: int, content: str, attempts: int) -> Delivery:
                    self.assertFalse(self.boundary.active)
                    self.assertEqual(self.boundary.events[-1][0], "commit")
                    self.assertEqual(self.operations[-1][0], "sending")
                    return Delivery(status, "SAFE_CODE")

                self.sender.send.side_effect = classified_send
                self.sender.send.reset_mock()
                await self.worker.deliver(self.inbox)
                self.repository.fail_later_chunks.assert_awaited_with(
                    self.connection, self.inbox, chunk
                )
                self.assertEqual(self.operations[-1][0], "fail_later_chunks")
                self.assertEqual(self.operations[-1][2], self.operations[-2][2])
                await self.worker.deliver(self.inbox)
                self.sender.send.assert_awaited_once()
                self.repository.mark_delivery_failed.assert_awaited_with(
                    self.connection, self.inbox
                )
                self.assertEqual(self.operations[-1][0], "delivery_failed")
        self.ai.reply.assert_not_awaited()

    async def test_all_successful_delivery_finishes_done(self) -> None:
        self.repository.pending_outbox.return_value = None
        self.repository.has_failed_outbox.return_value = False
        await self.worker.deliver(self.inbox)
        self.repository.mark_done.assert_awaited_once_with(self.connection, self.inbox)
        self.repository.mark_delivery_failed.assert_not_awaited()
        self.assertEqual(self.operations[-1][0], "done")
        self.sender.send.assert_not_awaited()
        self.ai.reply.assert_not_awaited()

    async def test_processing_crash_recovers_error_and_unknown_send_suppresses_tail(self) -> None:
        self.inbox["session_id"] = self.session_id
        self.repository.processing_inboxes.return_value = [self.inbox]
        await self.worker.recover()
        self.repository.recover_sending.assert_awaited_once_with(self.connection)
        self.repository.suppress_tails_after_unknown.assert_awaited_once_with(self.connection)
        self.repository.processing_inboxes.assert_awaited_once_with(self.connection)
        self.assertEqual(
            [item[0] for item in self.operations],
            ["recover_sending", "suppress_tails", "outbox", "ready"],
        )
        self.assertEqual({item[2] for item in self.operations}, {1})
        self.assertEqual(self.outboxes[0][0][3], "ERROR")
        self.assertEqual(self.operations[-1][1], (self.inbox, "AI_CRASH_UNKNOWN"))
        self.assertEqual(self.boundary.events, [("begin", 1), ("commit", 1)])
        self.assertTrue(self.worker.healthy)
        self.ai.reply.assert_not_awaited()
        self.sender.send.assert_not_awaited()
        self.repository.message.assert_not_awaited()
