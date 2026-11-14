"""Per-request tenant scope (TASK-059).

* The channel repository (shared bot) knows no tenant; each request gets a tenant
  view whose conversation/message SQL is bound to that tenant.
* The same Telegram user in two tenants gets two separate conversations/histories.
* End to end through Intake -> ChatService.identify -> handle -> real LangGraph ->
  tool node: `tool.execute` receives the VERIFIED tenant, never one from message
  text or from model-supplied arguments.
"""

import re
import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, Mock, patch
from uuid import UUID, uuid4

from aiogram.types import Message
from langchain_core.messages import AIMessage
from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlalchemy.sql import Executable

import app.chat.service as service_module
from app.ai.graph.workflow import build_chat_graph
from app.channels.telegram.config import TelegramSettings
from app.channels.telegram.handlers import Intake
from app.channels.telegram.sender import Sender
from app.chat.members import VerifiedMembership
from app.chat.repositories import ChannelRepository, Repository
from app.chat.service import ChatService
from app.core.database import Database
from app.tools.base import BaseTool
from app.tools.registry import ToolRegistry


def compiled(statement: Executable) -> tuple[str, dict[str, object]]:
    result = statement.compile(
        dialect=postgresql.dialect(), compile_kwargs={"render_postcompile": True}
    )
    return re.sub(r"\s+", " ", str(result)).strip(), dict(result.params)


def captured(mock: AsyncMock, index: int = 0) -> tuple[str, dict[str, object]]:
    return compiled(mock.await_args_list[index].args[0])


def connection_mock() -> Mock:
    connection = Mock(spec=AsyncConnection)
    connection.scalar = AsyncMock()
    connection.execute = AsyncMock()
    return connection


class RepositoryScopeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.channel_id = uuid4()
        self.tenant_a = uuid4()
        self.tenant_b = uuid4()
        self.user_id = uuid4()
        self.channels = ChannelRepository(self.channel_id, "TELEGRAM")

    def test_channel_repository_has_no_tenant_and_builds_tenant_views(self) -> None:
        self.assertFalse(hasattr(self.channels, "tenant_id"))
        view = self.channels.for_tenant(self.tenant_a)
        self.assertIsInstance(view, Repository)
        self.assertEqual((view.tenant_id, view.channel_id, view.platform),
                         (self.tenant_a, self.channel_id, "TELEGRAM"))
        with self.assertRaisesRegex(ValueError, "REPOSITORY_SCOPE_INVALID"):
            self.channels.for_tenant(str(self.tenant_a))  # type: ignore[arg-type]
        with self.assertRaisesRegex(ValueError, "REPOSITORY_SCOPE_INVALID"):
            ChannelRepository("channel", "TELEGRAM")  # type: ignore[arg-type]
        with self.assertRaisesRegex(ValueError, "REPOSITORY_PLATFORM_INVALID"):
            ChannelRepository(self.channel_id, "SMS")

    async def test_channel_user_upsert_is_guarded_by_channel_only(self) -> None:
        connection = connection_mock()
        connection.scalar.side_effect = [True, self.user_id]
        self.assertEqual(await self.channels.upsert_user(connection, "7", "handle", None), self.user_id)
        guard_sql, guard_params = captured(connection.scalar, 0)
        self.assertIn("channel_accounts.id", guard_sql)
        self.assertNotIn("tenant_id", guard_sql)
        self.assertEqual(set(guard_params.values()), {self.channel_id, "TELEGRAM"})

    async def test_tenant_view_accepts_shared_channel_and_binds_conversations(self) -> None:
        connection = connection_mock()
        connection.scalar.side_effect = [True, None, uuid4()]
        await self.channels.for_tenant(self.tenant_a).resolve_conversation(
            connection, self.user_id, "chat"
        )
        guard_sql, _ = captured(connection.scalar, 0)
        self.assertIn("channel_accounts.tenant_id IS NULL OR channel_accounts.tenant_id =", guard_sql)
        lookup_sql, lookup_params = captured(connection.scalar, 1)
        self.assertIn("conversations.tenant_id =", lookup_sql)
        self.assertIn(self.tenant_a, lookup_params.values())
        insert_sql, insert_params = captured(connection.scalar, 2)
        self.assertIn("INSERT INTO conversations", insert_sql)
        self.assertEqual(insert_params["tenant_id"], self.tenant_a)

    async def test_two_tenant_views_never_share_conversation_predicates(self) -> None:
        conversation_id = uuid4()
        statements: dict[UUID, list[tuple[str, dict[str, object]]]] = {}
        for tenant in (self.tenant_a, self.tenant_b):
            view = self.channels.for_tenant(tenant)
            connection = connection_mock()
            connection.scalar.side_effect = [None, None]
            connection.execute.return_value = Mock(**{"all.return_value": []})
            await view.find_duplicate_incoming(connection, self.user_id, "chat", "m-1")
            await view.completed_history(connection, conversation_id, self.user_id, "chat", 10)
            with self.assertRaisesRegex(RuntimeError, "CONVERSATION_SCOPE_MISMATCH"):
                await view.insert_message(
                    connection, conversation_id, self.user_id, "chat", "USER", "x", "m-2"
                )
            statements[tenant] = [captured(connection.scalar, 0), captured(connection.execute, 0),
                                  captured(connection.scalar, 1)]
        for tenant, other in ((self.tenant_a, self.tenant_b), (self.tenant_b, self.tenant_a)):
            for sql, params in statements[tenant]:
                self.assertIn("conversations.tenant_id =", sql)
                self.assertIn(tenant, params.values())
                self.assertNotIn(other, params.values())


class TransactionFake:
    def __init__(self) -> None:
        self.connection = Mock(spec=AsyncConnection)
        self.active = False

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[Mock]:
        assert not self.active, "nested transaction"
        self.active = True
        try:
            yield self.connection
        finally:
            self.active = False


class TenantStore:
    """Per-tenant conversations/messages behind tenant views built by for_tenant."""

    def __init__(self) -> None:
        self.user_id = uuid4()
        self.conversations: dict[UUID, UUID] = {}
        self.messages: dict[UUID, list[tuple[str, str]]] = {}
        self.views: list[UUID] = []

    def view(self, tenant_id: UUID) -> Mock:
        self.views.append(tenant_id)
        conversation = self.conversations.setdefault(tenant_id, uuid4())
        history = self.messages.setdefault(conversation, [])
        repository = Mock(spec=Repository)
        repository.tenant_id = tenant_id
        repository.upsert_user = AsyncMock(return_value=self.user_id)
        repository.find_duplicate_incoming = AsyncMock(return_value=False)
        repository.resolve_conversation = AsyncMock(return_value=conversation)

        async def insert(connection: object, conversation_id: UUID, user_id: UUID, chat_id: str,
                         role: str, content: str, external_message_id: str | None = None) -> int:
            assert conversation_id == conversation, "cross-tenant conversation write"
            history.append((role, content))
            return len(history)

        async def pairs(connection: object, conversation_id: UUID, user_id: UUID, chat_id: str,
                        before: int) -> list[tuple[str, str]]:
            assert conversation_id == conversation, "cross-tenant history read"
            rows = history[: before - 1]
            return [(rows[i][1], rows[i + 1][1]) for i in range(0, len(rows) - 1, 2)]

        repository.insert_message = AsyncMock(side_effect=insert)
        repository.completed_history = AsyncMock(side_effect=pairs)
        return repository


class TwoTenantConversationTests(unittest.IsolatedAsyncioTestCase):
    async def test_same_telegram_user_has_isolated_conversation_per_tenant(self) -> None:
        tenant_a, tenant_b = uuid4(), uuid4()
        store = TenantStore()
        channels = Mock(spec=ChannelRepository)
        channels.for_tenant = Mock(side_effect=store.view)
        seen_states: list[dict[str, Any]] = []

        async def ainvoke(state: dict[str, Any]) -> dict[str, Any]:
            seen_states.append(state)
            return {"final_response": f"jawaban untuk {state['tenant_id']}"}

        graph = Mock()
        graph.ainvoke = AsyncMock(side_effect=ainvoke)
        service = ChatService(TransactionFake(), channels, graph=graph)  # type: ignore[arg-type]
        base = {"external_user_id": "7", "external_chat_id": "71", "username": None,
                "first_name": "First", "kind": "TEXT"}
        await service.handle(tenant_id=tenant_a, external_message_id="1", content="rahasia A", **base)
        await service.handle(tenant_id=tenant_b, external_message_id="2", content="halo B", **base)
        await service.handle(tenant_id=tenant_a, external_message_id="3", content="lagi A", **base)

        self.assertEqual(store.views, [tenant_a, tenant_b, tenant_a])
        self.assertNotEqual(store.conversations[tenant_a], store.conversations[tenant_b])
        self.assertEqual([state["tenant_id"] for state in seen_states],
                         [str(tenant_a), str(tenant_b), str(tenant_a)])
        self.assertEqual([state["conversation_id"] for state in seen_states],
                         [str(store.conversations[tenant_a]), str(store.conversations[tenant_b]),
                          str(store.conversations[tenant_a])])
        tenant_b_prompt = " ".join(str(m.content) for m in seen_states[1]["messages"])
        self.assertNotIn("rahasia A", tenant_b_prompt)
        third_prompt = " ".join(str(m.content) for m in seen_states[2]["messages"])
        self.assertIn("rahasia A", third_prompt)
        self.assertNotIn("halo B", third_prompt)

    async def test_handle_requires_a_uuid_tenant(self) -> None:
        service = ChatService(TransactionFake(), Mock(spec=ChannelRepository), graph=Mock())  # type: ignore[arg-type]
        for tenant in (None, "", str(uuid4())):
            with self.subTest(tenant=tenant):
                with self.assertRaisesRegex(ValueError, "TENANT_SCOPE_INVALID"):
                    await service.handle(
                        tenant_id=tenant, external_user_id="7", external_chat_id="71",  # type: ignore[arg-type]
                        external_message_id="1", username=None, first_name=None,
                        kind="TEXT", content="x",
                    )


class RecordingTool(BaseTool):
    name = "order_lookup"
    description = "Look up an order."
    parameters = {
        "type": "object",
        "properties": {"order_number": {"type": "string"}, "tenant_id": {"type": "string"}},
        "required": ["order_number"],
    }

    def __init__(self) -> None:
        self.calls: list[dict[str, Any]] = []

    async def execute(self, tenant_id: str, **kwargs: Any) -> dict[str, Any]:
        self.calls.append({"tenant_id": tenant_id, **kwargs})
        return {"status": "PROCESSING"}


class VerifiedTenantReachesToolTests(unittest.IsolatedAsyncioTestCase):
    async def test_tool_execute_receives_the_verified_tenant_only(self) -> None:
        verified = uuid4()
        attacker = uuid4()
        store = TenantStore()
        channels = Mock(spec=ChannelRepository)
        channels.upsert_user = AsyncMock(return_value=store.user_id)
        channels.for_tenant = Mock(side_effect=store.view)
        tool = RecordingTool()
        registry = ToolRegistry()
        registry.register(tool)
        graph = build_chat_graph(openrouter_key="sk-test", ai_model="mock-model", tools=registry)
        service = ChatService(TransactionFake(), channels, tools=registry, graph=graph)  # type: ignore[arg-type]
        java_user = uuid4()
        membership = VerifiedMembership(verified, "Kopi Senja", java_user, "CASHIER", True)
        sender = Mock(spec=Sender)
        sender.send = AsyncMock(return_value=SimpleNamespace(status="SENT", retry_after=0))
        intake = Intake(TelegramSettings("dummy-token", frozenset()), service, sender)
        text = f"tenant_id={attacker} cek pesanan ORD-1 milik tenant {attacker}"
        incoming = Message.model_validate({
            "message_id": 5,
            "date": datetime(2026, 11, 7, tzinfo=timezone.utc),
            "chat": {"id": 71, "type": "private"},
            "from": {"id": 7, "is_bot": False, "first_name": "First"},
            "text": text,
        })
        llm_turns = [
            AIMessage(content="", tool_calls=[{
                "name": "order_lookup",
                "args": {"order_number": "ORD-1", "tenant_id": str(attacker), "user_id": str(attacker)},
                "id": "call-1",
            }]),
            AIMessage(content="Pesanan ORD-1 sedang diproses."),
        ]
        with patch.multiple(
            service_module.members,
            get_selected_tenant=AsyncMock(return_value=verified),
            list_memberships=AsyncMock(return_value=[membership]),
            revoke_memberships=AsyncMock(),
            select_tenant=AsyncMock(),
        ), patch("langchain_openai.ChatOpenAI.ainvoke", new_callable=AsyncMock) as llm:
            llm.side_effect = llm_turns
            await intake.process(incoming)

        self.assertEqual(
            tool.calls,
            [{"tenant_id": str(verified), "user_id": str(java_user), "order_number": "ORD-1"}],
        )
        self.assertEqual(store.views, [verified])
        channels.for_tenant.assert_called_once_with(verified)
        sender.send.assert_awaited_once_with(71, "Pesanan ORD-1 sedang diproses.", 1)
        first_prompt = llm.await_args_list[0].args[0]
        self.assertNotIn(str(verified), " ".join(str(m.content) for m in first_prompt))


if __name__ == "__main__":
    unittest.main()
