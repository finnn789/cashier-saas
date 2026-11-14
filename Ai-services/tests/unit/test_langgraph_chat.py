"""Unit tests for ChatService integrated with LangGraph workflow and short DB transactions."""

import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any
from unittest.mock import AsyncMock, Mock
from uuid import UUID, uuid4

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from sqlalchemy.ext.asyncio import AsyncConnection

from app.ai.config import OpenRouterSettings
from app.ai.openrouter import AIError, OpenRouterClient
from app.chat.repositories import ChannelRepository, Repository
from app.chat.service import ChatService
from app.core.database import Database


class TransactionMarkers:
    """Tracks database transaction boundaries and detects nested transactions."""

    def __init__(self, connection: Mock) -> None:
        self.connection = connection
        self.active = False
        self.number = 0
        self.events: list[tuple[str, int]] = []

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[Mock]:
        if self.active:
            raise AssertionError("Nested transaction detected")
        self.number += 1
        num = self.number
        self.active = True
        self.events.append(("begin", num))
        try:
            yield self.connection
        except BaseException:
            self.events.append(("rollback", num))
            raise
        else:
            self.events.append(("commit", num))
        finally:
            self.active = False


class LangGraphChatServiceTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.connection = Mock(spec=AsyncConnection)
        self.boundary = TransactionMarkers(self.connection)
        self.database = Mock(spec=Database)
        self.database.transaction = Mock(side_effect=self.boundary.transaction)

        self.tenant_id = uuid4()
        self.channel_id = uuid4()
        self.user_id = uuid4()
        self.conversation_id = uuid4()

        self.repository = Mock(spec=Repository)
        self.repository.tenant_id = self.tenant_id
        self.repository.channel_id = self.channel_id
        self.repository.platform = "TELEGRAM"

        self.repository.upsert_user = AsyncMock(side_effect=self._upsert_user)
        self.repository.find_duplicate_incoming = AsyncMock(return_value=False)
        self.repository.resolve_conversation = AsyncMock(side_effect=self._resolve_conversation)
        self.repository.insert_message = AsyncMock(side_effect=self._insert_message)
        self.repository.completed_history = AsyncMock(side_effect=self._completed_history)
        # The tenant is per request: ChatService asks the channel for this tenant view.
        self.channels = Mock(spec=ChannelRepository)
        self.channels.for_tenant = Mock(return_value=self.repository)

        self.db_events: list[tuple[str, int]] = []
        self.history_pairs: list[tuple[str, str]] = [
            ("Halo bot", "Halo! Ada yang bisa dibantu?"),
        ]

        self.input: dict[str, Any] = {
            "tenant_id": self.tenant_id,
            "external_user_id": "tg-user-100",
            "external_chat_id": "tg-chat-200",
            "external_message_id": "tg-msg-300",
            "username": "customer_a",
            "first_name": "Customer",
            "kind": "TEXT",
            "content": "Status pesanan saya bagaimana?",
        }

    def _record(self, name: str, connection: Mock) -> None:
        self.assertTrue(self.boundary.active)
        self.assertIs(connection, self.connection)
        self.db_events.append((name, self.boundary.number))

    async def _upsert_user(
        self, connection: Mock, external_user_id: str, username: str | None, first_name: str | None
    ) -> UUID:
        self._record("upsert", connection)
        return self.user_id

    async def _resolve_conversation(
        self, connection: Mock, user_id: UUID, chat_id: str, reset: bool = False
    ) -> UUID:
        self._record("resolve", connection)
        return self.conversation_id

    async def _insert_message(
        self,
        connection: Mock,
        conversation_id: UUID,
        user_id: UUID,
        chat_id: str,
        role: str,
        content: str,
        external_message_id: str | None = None,
    ) -> int:
        self._record(role, connection)
        if role == "USER":
            return 101
        return 102

    async def _completed_history(
        self,
        connection: Mock,
        conversation_id: UUID,
        user_id: UUID,
        chat_id: str,
        before_id: int,
    ) -> list[tuple[str, str]]:
        self._record("history", connection)
        return list(self.history_pairs)

    # -------------------------------------------------------------------------
    # 1. Basic conversation without tool calls
    # -------------------------------------------------------------------------

    async def test_basic_conversation_invokes_graph_and_commits_two_transactions(self) -> None:
        mock_graph = AsyncMock()
        invoked_state_holder: list[dict[str, Any]] = []

        async def fake_ainvoke(state: dict[str, Any]) -> dict[str, Any]:
            # Verify graph executes outside of active DB transaction
            self.assertFalse(self.boundary.active)
            self.assertEqual(self.boundary.events[-1], ("commit", 1))
            invoked_state_holder.append(state)
            return {
                "messages": [
                    HumanMessage(content="Status pesanan saya bagaimana?"),
                    AIMessage(content="Pesanan Anda sedang diproses oleh dapur."),
                ],
                "final_response": "Pesanan Anda sedang diproses oleh dapur.",
                "tenant_id": str(self.tenant_id),
                "user_id": self.user_id,
                "conversation_id": str(self.conversation_id),
                "error_code": None,
            }

        mock_graph.ainvoke = AsyncMock(side_effect=fake_ainvoke)
        service = ChatService(self.database, self.channels, graph=mock_graph)

        result = await service.handle(**self.input)

        self.assertEqual(result, "Pesanan Anda sedang diproses oleh dapur.")
        # Verify sequence of DB transactions:
        # Tx 1: upsert, resolve, USER insert, history
        # Tx 2: ASSISTANT insert
        self.assertEqual(
            self.db_events,
            [("upsert", 1), ("resolve", 1), ("USER", 1), ("history", 1), ("ASSISTANT", 2)],
        )
        self.assertEqual(
            self.boundary.events,
            [("begin", 1), ("commit", 1), ("begin", 2), ("commit", 2)],
        )

        # Verify graph input state
        self.assertEqual(len(invoked_state_holder), 1)
        passed_state = invoked_state_holder[0]
        self.assertEqual(passed_state["tenant_id"], str(self.tenant_id))
        self.assertEqual(passed_state["user_id"], self.user_id)
        self.assertEqual(passed_state["conversation_id"], str(self.conversation_id))
        self.assertEqual(passed_state["display_name"], "Customer")

        # Verify LangChain message conversion
        messages = passed_state["messages"]
        self.assertEqual(len(messages), 3)
        self.assertIsInstance(messages[0], HumanMessage)
        self.assertEqual(messages[0].content, "Halo bot")
        self.assertIsInstance(messages[1], AIMessage)
        self.assertEqual(messages[1].content, "Halo! Ada yang bisa dibantu?")
        self.assertIsInstance(messages[2], HumanMessage)
        self.assertEqual(messages[2].content, "Status pesanan saya bagaimana?")

        # Verify assistant message inserted in DB in Tx 2
        self.repository.insert_message.assert_awaited_with(
            self.connection,
            self.conversation_id,
            self.user_id,
            "tg-chat-200",
            "ASSISTANT",
            "Pesanan Anda sedang diproses oleh dapur.",
        )

    async def test_final_response_fallback_to_last_ai_message(self) -> None:
        """Verify extraction from last AIMessage when final_response key is absent."""
        mock_graph = AsyncMock()

        async def fake_ainvoke(state: dict[str, Any]) -> dict[str, Any]:
            return {
                "messages": [
                    HumanMessage(content="Hai"),
                    AIMessage(content="Jawaban dari AIMessage langsung."),
                ],
                # final_response is intentionally None / omitted
                "final_response": None,
            }

        mock_graph.ainvoke = AsyncMock(side_effect=fake_ainvoke)
        service = ChatService(self.database, self.channels, graph=mock_graph)

        result = await service.handle(**self.input)
        self.assertEqual(result, "Jawaban dari AIMessage langsung.")
        self.assertEqual(self.boundary.events, [
            ("begin", 1), ("commit", 1), ("begin", 2), ("commit", 2)
        ])

    # -------------------------------------------------------------------------
    # 2. Error handling: AI unavailable, timeouts, and error codes
    # -------------------------------------------------------------------------

    async def test_graph_exceptions_return_localized_message_and_persist_no_assistant(self) -> None:
        cases = (
            (AIError("AI_NOT_CONFIGURED"), "AI belum dikonfigurasi"),
            (AIError("AI_HTTP_401"), "Akses AI ditolak"),
            (AIError("AI_HTTP_402"), "Saldo layanan AI tidak mencukupi"),
            # A bare TimeoutError is classified as AI_TRANSPORT_UNKNOWN (transport message).
            (TimeoutError("OpenRouter timeout after 45s"), "Koneksi AI terputus"),
            # An exception that classifies as AI_UNKNOWN yields the default text.
            (RuntimeError("boom 401 402 403"), "Layanan AI belum dapat menjawab"),
        )
        for error, expected in cases:
            with self.subTest(error=f"{type(error).__name__}: {error}"):
                self.setUp()
                mock_graph = AsyncMock()
                mock_graph.ainvoke.side_effect = error

                service = ChatService(self.database, self.channels, graph=mock_graph)
                result = await service.handle(**self.input)

                self.assertIn(expected, result)
                # Transaction 1 committed USER, but Transaction 2 was never started
                self.assertEqual(self.boundary.events, [("begin", 1), ("commit", 1)])
                self.assertEqual(self.db_events[-1], ("history", 1))
                self.assertEqual(self._assistant_inserts(), [])

    def _assistant_inserts(self) -> list[Any]:
        return [
            call
            for call in self.repository.insert_message.await_args_list
            if len(call.args) > 4 and call.args[4] == "ASSISTANT"
        ]

    async def test_graph_state_error_code_returns_localized_message_and_persists_nothing(self) -> None:
        cases = (
            (
                {"messages": [HumanMessage(content="Halo")], "error_code": "AI_HTTP_402"},
                "Saldo layanan AI tidak mencukupi",
            ),
            (
                {
                    "messages": [AIMessage(content="Jawaban yang tidak boleh disimpan.")],
                    "final_response": "Jawaban yang tidak boleh disimpan.",
                    "error_code": "weird_code",
                },
                "Layanan AI belum dapat menjawab",
            ),
            (
                {"messages": [], "final_response": None, "error_code": "AI_TRANSPORT_UNKNOWN"},
                "Koneksi AI terputus",
            ),
        )
        for state, expected in cases:
            with self.subTest(error_code=state["error_code"]):
                self.setUp()
                mock_graph = AsyncMock()
                mock_graph.ainvoke.return_value = state

                service = ChatService(self.database, self.channels, graph=mock_graph)
                result = await service.handle(**self.input)

                self.assertIn(expected, result)
                self.assertEqual(self._assistant_inserts(), [])
                self.assertEqual(self.boundary.events, [("begin", 1), ("commit", 1)])

    async def test_graph_state_without_error_code_still_persists_assistant_answer(self) -> None:
        mock_graph = AsyncMock()
        mock_graph.ainvoke.return_value = {
            "messages": [AIMessage(content="Pesanan siap.")],
            "final_response": "Pesanan siap.",
        }

        service = ChatService(self.database, self.channels, graph=mock_graph)
        result = await service.handle(**self.input)

        self.assertEqual(result, "Pesanan siap.")
        self.assertEqual(len(self._assistant_inserts()), 1)
        self.assertEqual(
            self.boundary.events, [("begin", 1), ("commit", 1), ("begin", 2), ("commit", 2)]
        )

    # -------------------------------------------------------------------------
    # 3. Constructor Backward Compatibility & Graph Auto-construction
    # -------------------------------------------------------------------------

    async def test_init_with_unconfigured_ai_settings(self) -> None:
        ai_settings = OpenRouterSettings(openrouter_key="", ai_model="")
        ai_client = OpenRouterClient(ai_settings, session=Mock())
        service = ChatService(self.database, self.channels, ai=ai_client)

        # Unconfigured key still compiles graph or detects unconfigured at runtime
        result = await service.handle(**self.input)
        self.assertIn("AI belum dikonfigurasi", result)
