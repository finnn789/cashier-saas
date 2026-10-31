"""Direct ChatService behavior and short transaction boundaries."""

import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, Mock, patch
from uuid import UUID, uuid4

from sqlalchemy.ext.asyncio import AsyncConnection

import app.chat.service as service_module
from app.ai.openrouter import AIError, Completion, OpenRouterClient
from app.ai.prompts import MAX_INPUT_CHARS
from app.chat.repositories import Repository
from app.chat.service import ChatService
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
        self.user_id = uuid4()
        self.conversation_id = uuid4()
        self.repository.upsert_user = AsyncMock(side_effect=self.upsert_user)
        self.repository.find_duplicate_incoming = AsyncMock(return_value=False)
        self.repository.resolve_conversation = AsyncMock(side_effect=self.resolve_conversation)
        self.repository.insert_message = AsyncMock(side_effect=self.insert_message)
        self.repository.completed_history = AsyncMock(side_effect=self.completed_history)
        self.ai = Mock(spec=OpenRouterClient)
        self.ai.reply = AsyncMock(side_effect=self.reply)
        self.service = ChatService(self.database, self.repository, self.ai)
        self.events: list[tuple[str, int]] = []
        self.prompt: list[dict[str, str]] | None = None
        self.input: dict[str, Any] = {
            "external_user_id": "71",
            "external_chat_id": "private-chat",
            "external_message_id": "provider-1",
            "username": "handle",
            "first_name": "Name",
            "kind": "TEXT",
            "content": "current",
        }

    def record(self, name: str, connection: Mock) -> None:
        self.assertTrue(self.boundary.active)
        self.assertIs(connection, self.connection)
        self.events.append((name, self.boundary.number))

    async def upsert_user(
        self, connection: Mock, external_user_id: str,
        username: str | None, first_name: str | None,
    ) -> UUID:
        self.record("upsert", connection)
        self.assertEqual((external_user_id, username, first_name), ("71", "handle", "Name"))
        return self.user_id

    async def resolve_conversation(
        self, connection: Mock, user_id: UUID, chat_id: str, reset: bool = False,
    ) -> UUID:
        self.record("resolve", connection)
        self.assertEqual((user_id, chat_id), (self.user_id, "private-chat"))
        return self.conversation_id

    async def insert_message(
        self, connection: Mock, conversation_id: UUID, user_id: UUID,
        chat_id: str, role: str, content: str, external_message_id: str | None = None,
    ) -> int:
        self.record(role, connection)
        self.assertEqual(
            (conversation_id, user_id, chat_id),
            (self.conversation_id, self.user_id, "private-chat"),
        )
        if role == "USER":
            self.assertEqual((content, external_message_id), ("current", "provider-1"))
            return 101
        self.assertEqual((role, content, external_message_id), ("ASSISTANT", "answer", None))
        return 102

    async def completed_history(
        self, connection: Mock, conversation_id: UUID, user_id: UUID,
        chat_id: str, before_id: int,
    ) -> list[tuple[str, str]]:
        self.record("history", connection)
        self.assertEqual(
            (conversation_id, user_id, chat_id, before_id),
            (self.conversation_id, self.user_id, "private-chat", 101),
        )
        return [("old", "answer-old"), ("new", "answer-new")]

    async def reply(self, prompt: list[dict[str, str]]) -> Completion:
        self.assertFalse(self.boundary.active)
        self.assertEqual(self.boundary.events[-1], ("commit", 1))
        self.prompt = prompt
        return Completion("answer", "model", "provider-id")

    async def test_user_commits_before_ai_and_assistant_commits_before_return(self) -> None:
        self.assertEqual(await self.service.handle(**self.input), "answer")
        self.assertEqual(self.events, [
            ("upsert", 1), ("resolve", 1), ("USER", 1),
            ("history", 1), ("ASSISTANT", 2),
        ])
        self.assertEqual(self.boundary.events, [
            ("begin", 1), ("commit", 1), ("begin", 2), ("commit", 2),
        ])
        self.assertFalse(self.boundary.active)
        self.ai.reply.assert_awaited_once()
        assert self.prompt is not None
        self.assertEqual(self.prompt[2:], [
            {"role": "user", "content": "old"},
            {"role": "assistant", "content": "answer-old"},
            {"role": "user", "content": "new"},
            {"role": "assistant", "content": "answer-new"},
            {"role": "user", "content": "current"},
        ])
        self.assertEqual(sum(item["content"] == "current" for item in self.prompt), 1)
        self.repository.completed_history.assert_awaited_once_with(
            self.connection, self.conversation_id, self.user_id, "private-chat", 101
        )
        self.repository.insert_message.assert_awaited_with(
            self.connection, self.conversation_id, self.user_id,
            "private-chat", "ASSISTANT", "answer"
        )

    async def test_duplicate_text_skips_session_provider_and_response(self) -> None:
        self.repository.find_duplicate_incoming.return_value = True
        self.assertIsNone(await self.service.handle(**self.input))
        self.repository.upsert_user.assert_awaited_once()
        self.repository.find_duplicate_incoming.assert_awaited_once_with(
            self.connection, self.user_id, "private-chat", "provider-1"
        )
        self.repository.resolve_conversation.assert_not_awaited()
        self.repository.insert_message.assert_not_awaited()
        self.ai.reply.assert_not_awaited()
        self.assertEqual(self.boundary.events, [("begin", 1), ("commit", 1)])

    async def test_commands_reset_only_new_and_never_call_ai(self) -> None:
        for kind, reset in (("START", False), ("SESSION", False), ("NEW", True)):
            with self.subTest(kind=kind):
                self.repository.resolve_conversation.reset_mock()
                answer = await self.service.handle(**{**self.input, "kind": kind})
                self.assertIn(str(self.conversation_id), answer)
                self.repository.resolve_conversation.assert_awaited_once_with(
                    self.connection, self.user_id, "private-chat", reset=reset
                )
        self.repository.find_duplicate_incoming.assert_not_awaited()
        self.repository.insert_message.assert_not_awaited()
        self.ai.reply.assert_not_awaited()
        self.assertEqual(self.boundary.number, 3)
        self.assertTrue(all(event[0] != "rollback" for event in self.boundary.events))

    async def test_invalid_and_unsupported_are_ephemeral_and_input_is_bounded(self) -> None:
        for kind, content in (
            ("INVALID", ""),
            ("UNSUPPORTED", ""),
            ("TEXT", " "),
            ("TEXT", "x" * (MAX_INPUT_CHARS + 1)),
        ):
            with self.subTest(kind=kind, length=len(content)):
                answer = await self.service.handle(**{
                    **self.input, "kind": kind, "content": content,
                })
                self.assertIsInstance(answer, str)
                self.assertTrue(answer)
        self.assertEqual(self.boundary.number, 4)
        self.repository.resolve_conversation.assert_not_awaited()
        self.repository.insert_message.assert_not_awaited()
        self.repository.find_duplicate_incoming.assert_not_awaited()
        self.ai.reply.assert_not_awaited()

    async def test_hard_ai_error_is_ephemeral_after_user_commit(self) -> None:
        self.ai.reply.side_effect = AIError("AI_TRANSPORT_UNKNOWN", retryable=False)
        answer = await self.service.handle(**self.input)
        self.assertIn("hasil permintaan tidak diketahui", answer)
        self.assertEqual(self.boundary.events, [("begin", 1), ("commit", 1)])
        self.assertEqual(self.events[-1], ("history", 1))
        self.repository.insert_message.assert_awaited_once()
        self.ai.reply.assert_awaited_once()

    async def test_transient_retry_is_bounded_and_waits_outside_transaction(self) -> None:
        replies = [
            AIError("AI_RATE_LIMIT", retryable=True, delay=0),
            AIError("AI_TEMPORARY", retryable=True, delay=0),
            Completion("answer", "model", "provider-id"),
        ]
        waits: list[float] = []

        async def retry_reply(prompt: list[dict[str, str]]) -> Completion:
            self.assertFalse(self.boundary.active)
            outcome = replies.pop(0)
            if isinstance(outcome, AIError):
                raise outcome
            return outcome

        async def fake_sleep(seconds: float) -> None:
            self.assertFalse(self.boundary.active)
            waits.append(seconds)

        self.ai.reply.side_effect = retry_reply
        with patch.object(service_module, "asyncio", SimpleNamespace(sleep=fake_sleep)):
            self.assertEqual(await self.service.handle(**self.input), "answer")
        self.assertEqual(waits, [5, 10])
        self.assertEqual(self.ai.reply.await_count, 3)
        self.assertEqual(self.boundary.events, [
            ("begin", 1), ("commit", 1), ("begin", 2), ("commit", 2),
        ])
        self.assertEqual(self.repository.insert_message.await_count, 2)

    async def test_transient_exhaustion_never_stores_assistant(self) -> None:
        waits: list[float] = []

        async def fake_sleep(seconds: float) -> None:
            self.assertFalse(self.boundary.active)
            waits.append(seconds)

        self.ai.reply.side_effect = AIError("AI_TEMPORARY", retryable=True, delay=0)
        with patch.object(service_module, "asyncio", SimpleNamespace(sleep=fake_sleep)):
            answer = await self.service.handle(**self.input)
        self.assertIn("Layanan AI", answer)
        self.assertEqual(waits, [5, 10])
        self.assertEqual(self.ai.reply.await_count, 3)
        self.assertEqual(self.repository.insert_message.await_count, 1)
        self.assertEqual(self.boundary.events, [("begin", 1), ("commit", 1)])

    async def test_unknown_transport_never_retries(self) -> None:
        self.ai.reply.side_effect = AIError("AI_TRANSPORT_UNKNOWN", retryable=False)
        sleep = AsyncMock()
        with patch.object(service_module, "asyncio", SimpleNamespace(sleep=sleep)):
            await self.service.handle(**self.input)
        sleep.assert_not_awaited()
        self.ai.reply.assert_awaited_once()
        self.repository.insert_message.assert_awaited_once()

    async def test_assistant_persistence_failure_propagates_after_user_commit(self) -> None:
        async def failed_insert(
            connection: Mock, conversation_id: object, user_id: object,
            chat_id: str, role: str, content: str,
            external_message_id: str | None = None,
        ) -> int:
            self.record(role, connection)
            if role == "ASSISTANT":
                raise RuntimeError("assistant-write-failed")
            return 101

        self.repository.insert_message.side_effect = failed_insert
        with self.assertRaisesRegex(RuntimeError, "assistant-write-failed"):
            await self.service.handle(**self.input)
        self.assertEqual(self.boundary.events, [
            ("begin", 1), ("commit", 1), ("begin", 2), ("rollback", 2),
        ])
        self.assertFalse(self.boundary.active)
        self.ai.reply.assert_awaited_once()
