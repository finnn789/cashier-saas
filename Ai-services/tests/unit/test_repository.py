"""Minimal repository contracts over compiled PostgreSQL expressions."""

import re
import unittest
from unittest.mock import AsyncMock, Mock
from uuid import UUID, uuid4

from sqlalchemy import CheckConstraint, ForeignKeyConstraint, Index, UniqueConstraint
from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlalchemy.sql import Executable
from sqlalchemy.sql.elements import TextClause

from app.chat.entities import Base, ChannelAccount, ChannelUser, Conversation, Message, TenantMembership
from app.chat.repositories import Repository, register_telegram_channel


def compiled(statement: Executable) -> tuple[str, dict[str, object]]:
    assert not isinstance(statement, TextClause)
    result = statement.compile(
        dialect=postgresql.dialect(), compile_kwargs={"render_postcompile": True}
    )
    return re.sub(r"\s+", " ", str(result)).strip(), dict(result.params)


def captured(mock: AsyncMock, index: int = 0) -> tuple[str, dict[str, object]]:
    call = mock.await_args_list[index]
    assert len(call.args) == 1 and not call.kwargs
    return compiled(call.args[0])


class RepositoryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.tenant_id = uuid4()
        self.channel_id = uuid4()
        self.user_id = uuid4()
        self.conversation_id = uuid4()
        self.repository = Repository(self.tenant_id, self.channel_id, "TELEGRAM")
        self.connection = Mock(spec=AsyncConnection)
        self.connection.scalar = AsyncMock()
        self.connection.execute = AsyncMock()

    def assert_scope(self, sql: str, params: dict[str, object]) -> None:
        for value in (self.tenant_id, self.channel_id, "TELEGRAM"):
            self.assertIn(value, params.values())
        self.assertIn("channel_accounts", sql)
        self.assertIn("tenant_id", sql)
        self.assertIn("platform", sql)

    def test_constructor_rejects_unbound_scope_and_unknown_platform(self) -> None:
        with self.assertRaisesRegex(ValueError, "REPOSITORY_SCOPE_INVALID"):
            Repository("tenant", self.channel_id, "TELEGRAM")  # type: ignore[arg-type]
        with self.assertRaisesRegex(ValueError, "REPOSITORY_PLATFORM_INVALID"):
            Repository(self.tenant_id, self.channel_id, "OTHER")

    async def test_user_upsert_requires_owned_channel_and_separates_platform_identity(self) -> None:
        self.connection.scalar.side_effect = [True, self.user_id]
        result = await self.repository.upsert_user(self.connection, "71", "handle", "Name")
        self.assertEqual(result, self.user_id)
        guard_sql, guard_params = captured(self.connection.scalar, 0)
        self.assertIn("EXISTS", guard_sql)
        self.assert_scope(guard_sql, guard_params)
        insert_sql, insert_params = captured(self.connection.scalar, 1)
        self.assertIn("INSERT INTO channel_users", insert_sql)
        self.assertIn("ON CONFLICT (platform, external_user_id) DO UPDATE", insert_sql)
        self.assertIn("username = excluded.username", insert_sql)
        self.assertIn("first_name = excluded.first_name", insert_sql)
        self.assertIn("RETURNING channel_users.id", insert_sql)
        for value in ("TELEGRAM", "71", "handle", "Name"):
            self.assertIn(value, insert_params.values())
        self.assertIsInstance(insert_params["id"], UUID)

        other = Repository(self.tenant_id, self.channel_id, "WHATSAPP")
        other_connection = Mock(spec=AsyncConnection)
        other_connection.scalar = AsyncMock(side_effect=[True, uuid4()])
        await other.upsert_user(other_connection, "71", None, None)
        other_sql, other_params = captured(other_connection.scalar, 1)
        self.assertIn("ON CONFLICT (platform, external_user_id)", other_sql)
        self.assertIn("WHATSAPP", other_params.values())
        self.assertNotEqual(insert_params["platform"], other_params["platform"])

    async def test_missing_owned_channel_prevents_user_write(self) -> None:
        self.connection.scalar.return_value = False
        with self.assertRaisesRegex(RuntimeError, "CHANNEL_ACCOUNT_UNAVAILABLE"):
            await self.repository.upsert_user(self.connection, "71", None, None)
        self.assertEqual(self.connection.scalar.await_count, 1)
        self.connection.execute.assert_not_awaited()

    async def test_registration_cannot_retarget_existing_bot(self) -> None:
        self.connection.scalar.return_value = self.channel_id
        result = await register_telegram_channel(self.connection, self.tenant_id, 71, "bot")
        self.assertEqual(result, self.channel_id)
        self.assertEqual(self.connection.execute.await_count, 3)
        tenant_sql, _ = captured(self.connection.execute, 0)
        account_sql, account_params = captured(self.connection.execute, 1)
        lookup_sql, lookup_params = captured(self.connection.scalar)
        update_sql, update_params = captured(self.connection.execute, 2)
        self.assertIn("ON CONFLICT (id) DO NOTHING", tenant_sql)
        self.assertIn("ON CONFLICT (platform, external_account_id) DO NOTHING", account_sql)
        self.assertIn("TELEGRAM", account_params.values())
        self.assertIn("71", account_params.values())
        for sql, params in ((lookup_sql, lookup_params), (update_sql, update_params)):
            for value in (self.tenant_id, self.channel_id if sql == update_sql else "71", "TELEGRAM"):
                self.assertIn(value, params.values())
        self.assertIn("UPDATE channel_accounts", update_sql)
        self.assertIn("username", update_sql)
        set_clause = update_sql.split(" SET ", 1)[1].split(" WHERE ", 1)[0]
        self.assertNotIn("tenant_id =", set_clause)
        self.assertNotIn("external_account_id =", set_clause)

    async def test_duplicate_lookup_crosses_sessions_but_stays_with_owner_and_chat(self) -> None:
        self.connection.scalar.return_value = 9
        self.assertTrue(await self.repository.find_duplicate_incoming(
            self.connection, self.user_id, "private-chat", "provider-1"
        ))
        sql, params = captured(self.connection.scalar)
        self.assertIn("JOIN conversations", sql)
        self.assertIn("JOIN channel_accounts", sql)
        self.assert_scope(sql, params)
        for value in (self.user_id, "private-chat", "provider-1", "USER"):
            self.assertIn(value, params.values())
        self.assertNotIn(self.conversation_id, params.values())
        self.assertIn("LIMIT", sql)
        self.connection.scalar.return_value = None
        self.assertFalse(await self.repository.find_duplicate_incoming(
            self.connection, self.user_id, "private-chat", "provider-1"
        ))

    async def test_active_conversation_reused_and_new_closes_only_owned_active(self) -> None:
        self.connection.scalar.side_effect = [True, self.conversation_id]
        self.assertEqual(await self.repository.resolve_conversation(
            self.connection, self.user_id, "chat"
        ), self.conversation_id)
        self.connection.execute.assert_not_awaited()
        reuse_sql, reuse_params = captured(self.connection.scalar, 1)
        self.assert_scope(reuse_sql, reuse_params)
        for value in (self.user_id, "chat", "ACTIVE"):
            self.assertIn(value, reuse_params.values())

        self.connection.scalar.reset_mock(side_effect=True)
        self.connection.scalar.side_effect = [True, None, self.conversation_id]
        self.connection.execute.reset_mock()
        self.assertEqual(await self.repository.resolve_conversation(
            self.connection, self.user_id, "chat", reset=True
        ), self.conversation_id)
        reset_sql, reset_params = captured(self.connection.execute)
        self.assertIn("UPDATE conversations", reset_sql)
        self.assert_scope(reset_sql, reset_params)
        for value in (self.user_id, "chat", "ACTIVE", "CLOSED"):
            self.assertIn(value, reset_params.values())
        insert_sql, insert_params = captured(self.connection.scalar, 2)
        self.assertIn("INSERT INTO conversations", insert_sql)
        self.assertEqual(insert_params["channel_id"], self.channel_id)
        self.assertEqual(insert_params["platform"], "TELEGRAM")
        self.assertEqual(insert_params["user_id"], self.user_id)
        self.assertEqual(insert_params["external_chat_id"], "chat")
        self.assertIsInstance(insert_params["id"], UUID)

    async def test_wrong_conversation_never_inserts_message(self) -> None:
        self.connection.scalar.return_value = None
        with self.assertRaisesRegex(RuntimeError, "CONVERSATION_SCOPE_MISMATCH"):
            await self.repository.insert_message(
                self.connection, self.conversation_id, self.user_id, "chat",
                "USER", "hello", "provider-1"
            )
        self.assertEqual(self.connection.scalar.await_count, 1)
        self.connection.execute.assert_not_awaited()
        sql, params = captured(self.connection.scalar)
        self.assert_scope(sql, params)
        for value in (self.conversation_id, self.user_id, "chat", "ACTIVE"):
            self.assertIn(value, params.values())

    async def test_insert_returns_integer_and_updates_timestamp_under_owner_scope(self) -> None:
        updated = Mock(rowcount=1)
        self.connection.scalar.side_effect = [self.conversation_id, 42]
        self.connection.execute.return_value = updated
        self.assertEqual(await self.repository.insert_message(
            self.connection, self.conversation_id, self.user_id, "chat",
            "USER", "hello", "provider-1"
        ), 42)
        insert_sql, insert_params = captured(self.connection.scalar, 1)
        self.assertIn("INSERT INTO messages", insert_sql)
        self.assertIn("RETURNING messages.id", insert_sql)
        self.assertEqual(insert_params["conversation_id"], self.conversation_id)
        self.assertEqual(insert_params["external_message_id"], "provider-1")
        self.assertEqual(insert_params["role"], "USER")
        timestamp_sql, timestamp_params = captured(self.connection.execute)
        self.assertIn("UPDATE conversations", timestamp_sql)
        self.assertIn("last_message_at", timestamp_sql)
        self.assert_scope(timestamp_sql, timestamp_params)
        for value in (self.conversation_id, self.user_id, "chat", "ACTIVE"):
            self.assertIn(value, timestamp_params.values())

    async def test_assistant_has_null_external_id_and_timestamp_failure_raises(self) -> None:
        self.connection.scalar.side_effect = [self.conversation_id, 43]
        self.connection.execute.return_value = Mock(rowcount=0)
        with self.assertRaisesRegex(RuntimeError, "CONVERSATION_SCOPE_MISMATCH"):
            await self.repository.insert_message(
                self.connection, self.conversation_id, self.user_id, "chat",
                "ASSISTANT", "answer"
            )
        sql, params = captured(self.connection.scalar, 1)
        self.assertIn("INSERT INTO messages", sql)
        self.assertEqual(params["role"], "ASSISTANT")
        self.assertIsNone(params["external_message_id"])

    async def test_history_is_bounded_excludes_current_and_pairs_ordered_roles(self) -> None:
        chronological = [
            ("ASSISTANT", "orphan-answer"),
            ("USER", "orphan-user"),
            ("USER", "old"),
            ("ASSISTANT", "answer-old"),
            ("USER", "dangling"),
            ("USER", "new"),
            ("ASSISTANT", "answer-new"),
        ]
        self.connection.execute.return_value = Mock(**{"all.return_value": list(reversed(chronological))})
        pairs = await self.repository.completed_history(
            self.connection, self.conversation_id, self.user_id, "chat", 101
        )
        self.assertEqual(pairs, [("old", "answer-old"), ("new", "answer-new")])
        sql, params = captured(self.connection.execute)
        self.assertIn("JOIN conversations", sql)
        self.assertIn("JOIN channel_accounts", sql)
        self.assert_scope(sql, params)
        for value in (self.conversation_id, self.user_id, "chat", 101, 64):
            self.assertIn(value, params.values())
        self.assertIn("messages.id <", sql)
        self.assertIn("ORDER BY messages.id DESC", sql)
        self.assertIn("LIMIT", sql)
        with self.assertRaisesRegex(ValueError, "HISTORY_LIMIT_INVALID"):
            await self.repository.completed_history(
                self.connection, self.conversation_id, self.user_id, "chat", 101, limit=65
            )

    def test_minimal_metadata_constraints_are_declared(self) -> None:
        tables = Base.metadata.tables
        self.assertEqual(set(tables), {
            "tenants", "channel_accounts", "channel_users", "conversations",
            "messages", "tenant_memberships",
        })
        self.assertEqual(sum(len(table.columns) for table in tables.values()), 31)
        self.assertEqual(sum(
            isinstance(item, ForeignKeyConstraint)
            for table in tables.values() for item in table.constraints
        ), 6)
        indexes = {index.name for table in tables.values() for index in table.indexes}
        self.assertEqual(indexes, {
            "one_active_conversation", "conversation_external_message", "message_history"
        })
        self.assertTrue(any(
            isinstance(item, UniqueConstraint) and
            [column.name for column in item.columns] == ["platform", "external_user_id"]
            for item in ChannelUser.__table__.constraints
        ))
        self.assertTrue(any(
            isinstance(item, UniqueConstraint) and
            [column.name for column in item.columns] == ["platform", "external_account_id"]
            for item in ChannelAccount.__table__.constraints
        ))
        self.assertIsNone(Conversation.__table__.c.last_message_at.server_default)
        self.assertTrue(Conversation.__table__.c.last_message_at.nullable)
        self.assertIsNone(TenantMembership.__table__.c.verified_at.server_default)
        self.assertFalse(TenantMembership.__table__.c.verified_at.nullable)
        self.assertTrue(any(
            isinstance(item, CheckConstraint) and "ASSISTANT" in str(item.sqltext)
            for item in Message.__table__.constraints
        ))
        self.assertTrue(all(isinstance(index, Index) for index in Message.__table__.indexes))
