"""Repository contracts over captured PostgreSQL SQLAlchemy expressions."""

import re
import unittest
from typing import Any
from unittest.mock import AsyncMock, Mock
from uuid import UUID, uuid4

from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlalchemy.sql import Executable
from sqlalchemy.sql.elements import TextClause

from app.chat.repositories import Repository


class RepositoryTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.repository = Repository("unit-scope", 71)
        self.connection = Mock(spec=AsyncConnection)
        self.connection.execute = AsyncMock()
        self.connection.scalar = AsyncMock(return_value=3)
        self.inbox: dict[str, Any] = {
            "id": uuid4(), "identity_id": uuid4(), "chat_id": 7, "update_id": 81
        }

    def result(self, row: dict[str, Any] | None) -> Mock:
        result = Mock()
        result.mappings.return_value.first.return_value = row
        return result

    def statement(self, index: int) -> tuple[str, dict[str, Any]]:
        call = self.connection.execute.await_args_list[index]
        self.assertEqual(len(call.args), 1)
        self.assertFalse(call.kwargs)
        return self.compiled(call.args[0])

    def compiled(self, statement: Executable) -> tuple[str, dict[str, Any]]:
        self.assertNotIsInstance(statement, TextClause)
        compiled = statement.compile(
            dialect=postgresql.dialect(),
            compile_kwargs={"render_postcompile": True},
        )
        return re.sub(r"\s+", " ", str(compiled)).strip(), dict(compiled.params)

    def assert_scoped(self, sql: str, parameters: dict[str, Any]) -> None:
        self.assertRegex(sql, r"\bscope\s*=")
        self.assertRegex(sql, r"\bbot_id\s*=")
        self.assertIn("unit-scope", parameters.values())
        self.assertIn(71, parameters.values())

    async def test_identity_upsert_retains_returned_identity_and_refreshes_profile(self) -> None:
        identity = uuid4()
        for username, name in (("old", "Old"), ("new", "New")):
            self.connection.execute.side_effect = [self.result({"id": identity}), self.result(None)]
            await self.repository.intake(
                self.connection, 7, username, name, None, name,
                81, 7, 21, "TEXT", "hello",
            )
        for index, username, name in ((0, "old", "Old"), (2, "new", "New")):
            sql, parameters = self.statement(index)
            self.assertIn("ON CONFLICT (scope, bot_id, telegram_user_id) DO UPDATE", sql)
            for column in ("username", "first_name", "last_name", "display_name"):
                self.assertIn(f"{column} = excluded.{column}", sql)
            self.assertIn("updated_at = now()", sql)
            self.assertIn("last_seen_at = now()", sql)
            self.assertEqual(parameters["scope"], "unit-scope")
            self.assertEqual(parameters["bot_id"], 71)
            self.assertEqual(parameters["username"], username)
            self.assertEqual(parameters["first_name"], name)
            self.assertEqual(parameters["display_name"], name)
            self.assertEqual(parameters["telegram_user_id"], 7)
            inbox_sql, inbox_parameters = self.statement(index + 1)
            self.assertIn("ON CONFLICT (scope, bot_id, update_id) DO NOTHING", inbox_sql)
            self.assertEqual(inbox_parameters["scope"], "unit-scope")
            self.assertEqual(inbox_parameters["bot_id"], 71)
            self.assertEqual(inbox_parameters["identity_id"], identity)
            self.assertEqual(
                (inbox_parameters["update_id"], inbox_parameters["chat_id"], inbox_parameters["message_id"]),
                (81, 7, 21),
            )

    async def test_missing_identity_stops_before_inbox_insert(self) -> None:
        self.connection.execute.return_value = self.result(None)
        with self.assertRaisesRegex(RuntimeError, "^IDENTITY_NOT_RETURNED$"):
            await self.repository.intake(
                self.connection, 7, None, "Name", None, "Name",
                81, 7, 21, "TEXT", "hello",
            )
        self.assertEqual(self.connection.execute.await_count, 1)

    async def test_active_session_reuse_and_scoped_new_reset(self) -> None:
        session = {"id": uuid4()}
        self.connection.execute.return_value = self.result(session)
        self.assertEqual(await self.repository.session(self.connection, self.inbox), session)
        self.assertEqual(self.connection.execute.await_count, 1)
        sql, parameters = self.statement(0)
        self.assert_scoped(sql, parameters)
        self.assertIn("ACTIVE", parameters.values())
        self.assertIn(self.inbox["identity_id"], parameters.values())
        self.assertIn(7, parameters.values())
        self.connection.execute.reset_mock()
        self.connection.execute.side_effect = [self.result(None), self.result(None), self.result(session)]
        self.assertEqual(
            await self.repository.session(self.connection, self.inbox, reset=True), session
        )
        reset_sql, reset_parameters = self.statement(0)
        self.assertIn("UPDATE conversation_sessions", reset_sql)
        self.assert_scoped(reset_sql, reset_parameters)
        self.assertEqual(reset_parameters["status"], "CLOSED")
        self.assertIn("ACTIVE", reset_parameters.values())
        self.assertIn(self.inbox["identity_id"], reset_parameters.values())
        self.assertIn(7, reset_parameters.values())
        insert_sql, insert_parameters = self.statement(2)
        self.assertIn("INSERT INTO conversation_sessions", insert_sql)
        self.assertIsInstance(insert_parameters["id"], UUID)
        self.assertEqual(insert_parameters["identity_id"], self.inbox["identity_id"])
        self.assertEqual(insert_parameters["chat_id"], 7)
        self.assertEqual(insert_parameters["scope"], "unit-scope")
        self.assertEqual(insert_parameters["bot_id"], 71)

    async def test_sequence_scope_mismatch_never_inserts_message(self) -> None:
        session = uuid4()
        self.connection.scalar.return_value = None
        with self.assertRaisesRegex(RuntimeError, "^SESSION_SCOPE_MISMATCH$"):
            await self.repository.message(
                self.connection, self.inbox, session, "USER", "hello"
            )
        self.connection.execute.assert_not_awaited()
        statement = self.connection.scalar.await_args.args[0]
        sql, parameters = self.compiled(statement)
        self.assertIn("UPDATE conversation_sessions", sql)
        self.assertIn("RETURNING conversation_sessions.last_sequence", sql)
        self.assert_scoped(sql, parameters)
        self.assertIn(session, parameters.values())
        self.assertIn(self.inbox["identity_id"], parameters.values())
        self.assertIn(7, parameters.values())
        self.assertRegex(sql, r"last_sequence\s*=\s*conversation_sessions\.last_sequence\s*\+")

    async def test_reply_and_outbox_preserve_scope_and_order(self) -> None:
        session, user = uuid4(), uuid4()
        assistant = await self.repository.message(
            self.connection, self.inbox, session, "ASSISTANT", "answer",
            reply_to=user, model="model", response_id="provider-id",
        )
        sql, parameters = self.statement(0)
        self.assertIn("INSERT INTO conversation_messages", sql)
        self.assertIsInstance(assistant, UUID)
        self.assertEqual(parameters["id"], assistant)
        self.assertEqual(
            (parameters["sequence"], parameters["reply_to_id"], parameters["model"], parameters["provider_response_id"]),
            (3, user, "model", "provider-id"),
        )
        self.assertEqual(parameters["scope"], "unit-scope")
        self.assertEqual(parameters["bot_id"], 71)
        await self.repository.outbox(
            self.connection, self.inbox, session, "AI", ["first", "second"], assistant
        )
        for index, number, content in ((1, 0, "first"), (2, 1, "second")):
            sql, parameters = self.statement(index)
            self.assertIn(
                "ON CONFLICT (scope, bot_id, inbox_id, response_kind, chunk_number) DO NOTHING",
                sql,
            )
            self.assertEqual(
                (parameters["scope"], parameters["bot_id"], parameters["identity_id"],
                 parameters["chat_id"], parameters["session_id"], parameters["inbox_id"]),
                ("unit-scope", 71, self.inbox["identity_id"], 7, session, self.inbox["id"]),
            )
            self.assertEqual(
                (parameters["assistant_message_id"], parameters["chunk_number"], parameters["content"]),
                (assistant, number, content),
            )

    async def test_completed_history_is_fully_scoped_and_chronological(self) -> None:
        result = Mock()
        result.mappings.return_value.all.return_value = [
            {"user_content": "new", "answer": "answer-new"},
            {"user_content": "old", "answer": "answer-old"},
        ]
        self.connection.execute.return_value = result
        session = uuid4()
        pairs = await self.repository.completed_history(self.connection, self.inbox, session)
        self.assertEqual(pairs, [("old", "answer-old"), ("new", "answer-new")])
        sql, parameters = self.statement(0)
        self.assertIn("JOIN conversation_messages AS assistant_message ON", sql)
        self.assertIn("JOIN telegram_inbox AS completed_inbox ON", sql)
        for column in ("scope", "bot_id", "identity_id", "chat_id", "session_id"):
            self.assertIn(
                f"assistant_message.{column} = user_message.{column}", sql
            )
        self.assertIn("assistant_message.reply_to_id = user_message.id", sql)
        for column in ("scope", "bot_id", "identity_id", "chat_id"):
            self.assertIn(f"completed_inbox.{column} = user_message.{column}", sql)
        self.assertRegex(sql, r"user_message\.inbox_id\s*!=\s*%\(")
        self.assertIn("ORDER BY user_message.sequence DESC", sql)
        self.assertIn("DONE", parameters.values())
        self.assertIn("USER", parameters.values())
        self.assertIn("ASSISTANT", parameters.values())
        self.assertIn(self.inbox["id"], parameters.values())
        self.assertIn(session, parameters.values())
        self.assertIn(self.inbox["identity_id"], parameters.values())
        self.assertIn(7, parameters.values())
        self.assertIn(32, parameters.values())
        self.assert_scoped(sql, parameters)

    async def test_existing_user_and_queue_reads_are_scoped(self) -> None:
        self.connection.execute.return_value = self.result({"id": uuid4()})
        session = uuid4()
        await self.repository.existing_user_message(self.connection, self.inbox, session)
        sql, parameters = self.statement(0)
        self.assert_scoped(sql, parameters)
        for value in (self.inbox["identity_id"], 7, session, self.inbox["id"], "USER"):
            self.assertIn(value, parameters.values())
        await self.repository.next_inbox(self.connection)
        sql, parameters = self.statement(1)
        self.assert_scoped(sql, parameters)
        self.assertIn("ORDER BY telegram_inbox.received_order", sql)
        for status in ("PENDING", "RETRY", "READY"):
            self.assertIn(status, parameters.values())
        await self.repository.pending_outbox(self.connection, self.inbox)
        sql, parameters = self.statement(2)
        self.assert_scoped(sql, parameters)
        self.assertIn("ORDER BY telegram_outbox.chunk_number", sql)
        for value in (self.inbox["identity_id"], 7, self.inbox["id"], "PENDING"):
            self.assertIn(value, parameters.values())

    async def test_recovery_correlates_earlier_failed_chunks_with_same_queue(self) -> None:
        await self.repository.recover_sending(self.connection)
        await self.repository.suppress_tails_after_unknown(self.connection)
        sending_sql, sending_parameters = self.statement(0)
        self.assert_scoped(sending_sql, sending_parameters)
        for value in ("SENDING", "UNKNOWN", "CRASH_DURING_SEND"):
            self.assertIn(value, sending_parameters.values())
        tail_sql, tail_parameters = self.statement(1)
        self.assert_scoped(tail_sql, tail_parameters)
        self.assertIn("EXISTS (SELECT", tail_sql)
        for column in ("scope", "bot_id", "inbox_id", "response_kind"):
            self.assertIn(f"previous.{column} = telegram_outbox.{column}", tail_sql)
        self.assertIn("previous.chunk_number < telegram_outbox.chunk_number", tail_sql)
        for value in ("PENDING", "FAILED", "UNKNOWN", "PREVIOUS_CHUNK_UNKNOWN"):
            self.assertIn(value, tail_parameters.values())

    async def test_terminal_delivery_and_tail_updates_keep_owner_scope(self) -> None:
        chunk = {"id": uuid4(), "response_kind": "AI", "chunk_number": 0}
        await self.repository.mark_sending(self.connection, self.inbox, chunk)
        await self.repository.mark_outbox_result(
            self.connection, self.inbox, chunk, "UNKNOWN", "SAFE_CODE", None, None, 0
        )
        await self.repository.fail_later_chunks(self.connection, self.inbox, chunk)
        for index in range(3):
            sql, parameters = self.statement(index)
            self.assert_scoped(sql, parameters)
            for value in (self.inbox["identity_id"], 7, self.inbox["id"], chunk["id"]):
                if index < 2 or value != chunk["id"]:
                    self.assertIn(value, parameters.values())
        tail_sql, tail_parameters = self.statement(2)
        self.assertIn("telegram_outbox.chunk_number >", tail_sql)
        for value in ("AI", 0, "PENDING", "FAILED", "PREVIOUS_CHUNK_FAILED"):
            self.assertIn(value, tail_parameters.values())
