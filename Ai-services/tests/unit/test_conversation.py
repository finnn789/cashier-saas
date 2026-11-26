"""Direct ChatService behavior and short transaction boundaries."""

import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any
from unittest.mock import AsyncMock, Mock
from uuid import UUID, uuid4

from sqlalchemy.ext.asyncio import AsyncConnection

from app.ai.prompts import MAX_INPUT_CHARS
from app.chat.repositories import ChannelRepository, Repository
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
        # The tenant is per request: ChatService asks the channel for this tenant view.
        self.tenant_id = uuid4()
        self.channels = Mock(spec=ChannelRepository)
        self.channels.for_tenant = Mock(return_value=self.repository)
        self.user_id = uuid4()
        self.conversation_id = uuid4()
        self.repository.upsert_user = AsyncMock(side_effect=self.upsert_user)
        self.repository.find_duplicate_incoming = AsyncMock(return_value=False)
        self.repository.resolve_conversation = AsyncMock(side_effect=self.resolve_conversation)
        self.repository.insert_message = AsyncMock()
        self.repository.completed_history = AsyncMock(side_effect=self.completed_history)
        self.graph = Mock()
        self.graph.ainvoke = AsyncMock(return_value={"final_response": "answer"})
        self.grpc = Mock()
        self.grpc.consume_ai_question = AsyncMock(return_value={
            "allowed": True, "reason": "", "used": 1, "limit": 300, "warn": False,
        })
        self.grpc.record_ai_usage = AsyncMock()
        self.service = ChatService(
            self.database, self.channels, graph=self.graph, grpc_client=self.grpc
        )
        self.input: dict[str, Any] = {
            "tenant_id": self.tenant_id,
            "external_user_id": "71",
            "external_chat_id": "private-chat",
            "external_message_id": "provider-1",
            "username": "handle",
            "first_name": "Name",
            "kind": "TEXT",
            "content": "current",
        }

    def record(self, connection: Mock) -> None:
        self.assertTrue(self.boundary.active)
        self.assertIs(connection, self.connection)

    async def upsert_user(
        self, connection: Mock, external_user_id: str,
        username: str | None, first_name: str | None,
    ) -> UUID:
        self.record(connection)
        self.assertEqual((external_user_id, username, first_name), ("71", "handle", "Name"))
        return self.user_id

    async def resolve_conversation(
        self, connection: Mock, user_id: UUID, chat_id: str, reset: bool = False,
    ) -> UUID:
        self.record(connection)
        self.assertEqual((user_id, chat_id), (self.user_id, "private-chat"))
        return self.conversation_id

    async def completed_history(
        self, connection: Mock, conversation_id: UUID, user_id: UUID,
        chat_id: str, before_id: int,
    ) -> list[tuple[str, str]]:
        self.record(connection)
        self.assertEqual(
            (conversation_id, user_id, chat_id, before_id),
            (self.conversation_id, self.user_id, "private-chat", 101),
        )
        return [("old", "answer-old"), ("new", "answer-new")]

    async def test_duplicate_text_skips_session_provider_and_response(self) -> None:
        self.repository.find_duplicate_incoming.return_value = True
        self.assertIsNone(await self.service.handle(**self.input))
        self.repository.upsert_user.assert_awaited_once()
        self.repository.find_duplicate_incoming.assert_awaited_once_with(
            self.connection, self.user_id, "private-chat", "provider-1"
        )
        self.repository.resolve_conversation.assert_not_awaited()
        self.repository.insert_message.assert_not_awaited()
        self.graph.ainvoke.assert_not_awaited()
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
        self.graph.ainvoke.assert_not_awaited()
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
        self.graph.ainvoke.assert_not_awaited()

    async def test_assistant_persistence_failure_propagates_after_user_commit(self) -> None:
        async def failed_insert(
            connection: Mock, conversation_id: object, user_id: object,
            chat_id: str, role: str, content: str,
            external_message_id: str | None = None,
        ) -> int:
            self.record(connection)
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
        self.graph.ainvoke.assert_awaited_once()
