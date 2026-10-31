"""Public repository contracts over captured SQLAlchemy statements, not a SQL emulator."""
import unittest
from typing import Any
from uuid import UUID, uuid4
from unittest.mock import AsyncMock, Mock

from sqlalchemy.ext.asyncio import AsyncConnection
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

    def captured(self, index: int) -> tuple[str, dict[str, Any]]:
        statement, parameters = self.connection.execute.await_args_list[index].args
        self.assertIsInstance(statement, TextClause)
        self.assertEqual(parameters["scope"], "unit-scope")
        self.assertEqual(parameters["bot"], 71)
        return " ".join(str(statement).split()), parameters

    async def test_identity_upsert_retains_returned_identity_and_refreshes_profile(self) -> None:
        identity = uuid4()
        for username, name in (("old", "Old"), ("new", "New")):
            self.connection.execute.side_effect = [self.result({"id": identity}), self.result(None)]
            await self.repository.intake(self.connection, 7, username, name, None, name, 81, 7, 21, "TEXT", "hello")
        for index, username, name in ((0, "old", "Old"), (2, "new", "New")):
            sql, parameters = self.captured(index)
            self.assertIn("ON CONFLICT (scope, bot_id, telegram_user_id) DO UPDATE", sql)
            for assignment in ("username=EXCLUDED.username", "display_name=EXCLUDED.display_name",
                               "first_name=EXCLUDED.first_name", "last_name=EXCLUDED.last_name"):
                self.assertIn(assignment, sql)
            self.assertEqual((parameters["username"], parameters["display"]), (username, name))
            inbox_sql, inbox_parameters = self.captured(index + 1)
            self.assertEqual(inbox_parameters["identity"], identity)
            self.assertIn("ON CONFLICT (scope, bot_id, update_id) DO NOTHING", inbox_sql)
            self.assertEqual((inbox_parameters["update"], inbox_parameters["chat"], inbox_parameters["message"]), (81, 7, 21))

    async def test_missing_identity_stops_before_inbox_insert(self) -> None:
        self.connection.execute.return_value = self.result(None)
        with self.assertRaisesRegex(RuntimeError, "^IDENTITY_NOT_RETURNED$"):
            await self.repository.intake(self.connection, 7, None, "Name", None, "Name", 81, 7, 21, "TEXT", "hello")
        self.assertEqual(self.connection.execute.await_count, 1)

    async def test_active_session_reuse_and_scoped_new_reset(self) -> None:
        session = {"id": uuid4()}
        self.connection.execute.return_value = self.result(session)
        self.assertEqual(await self.repository.session(self.connection, self.inbox), session)
        self.assertEqual(self.connection.execute.await_count, 1)
        sql, parameters = self.captured(0)
        self.assertIn("status='ACTIVE'", sql)
        self.assertEqual((parameters["identity"], parameters["chat"]), (self.inbox["identity_id"], 7))
        self.connection.execute.reset_mock()
        self.connection.execute.side_effect = [self.result(None), self.result(None), self.result(session)]
        self.assertEqual(await self.repository.session(self.connection, self.inbox, reset=True), session)
        reset_sql, reset_parameters = self.captured(0)
        self.assertIn("SET status='CLOSED'", reset_sql)
        self.assertIn("scope=:scope AND bot_id=:bot AND identity_id=:identity", reset_sql)
        self.assertIn("chat_id=:chat AND status='ACTIVE'", reset_sql)
        self.assertEqual(reset_parameters["identity"], self.inbox["identity_id"])
        insert_sql, insert_parameters = self.captured(2)
        self.assertIn("INSERT INTO conversation_sessions", insert_sql)
        self.assertIsInstance(insert_parameters["id"], UUID)

    async def test_sequence_scope_mismatch_never_inserts_message(self) -> None:
        session = uuid4()
        self.connection.scalar.return_value = None
        with self.assertRaisesRegex(RuntimeError, "^SESSION_SCOPE_MISMATCH$"):
            await self.repository.message(self.connection, self.inbox, session, "USER", "hello")
        self.connection.execute.assert_not_awaited()
        statement, parameters = self.connection.scalar.await_args.args
        self.assertIsInstance(statement, TextClause)
        sql = " ".join(str(statement).split())
        self.assertIn("id=:session AND identity_id=:identity", sql)
        self.assertIn("chat_id=:chat RETURNING last_sequence", sql)
        self.assertEqual(parameters, {"scope": "unit-scope", "bot": 71, "session": session,
                                     "identity": self.inbox["identity_id"], "chat": 7})

    async def test_reply_and_outbox_preserve_scope_and_order(self) -> None:
        session, user = uuid4(), uuid4()
        assistant = await self.repository.message(self.connection, self.inbox, session, "ASSISTANT", "answer",
                                                   reply_to=user, model="model", response_id="provider-id")
        sql, parameters = self.captured(0)
        self.assertIsInstance(assistant, UUID)
        self.assertEqual(parameters["id"], assistant)
        self.assertEqual((parameters["sequence"], parameters["reply"], parameters["model"], parameters["response"]),
                         (3, user, "model", "provider-id"))
        await self.repository.outbox(self.connection, self.inbox, session, "AI", ["first", "second"], assistant)
        for index, number, content in ((1, 0, "first"), (2, 1, "second")):
            sql, parameters = self.captured(index)
            self.assertIn("ON CONFLICT (scope, bot_id, inbox_id, response_kind, chunk_number) DO NOTHING", sql)
            self.assertEqual((parameters["identity"], parameters["chat"], parameters["session"], parameters["inbox"]),
                             (self.inbox["identity_id"], 7, session, self.inbox["id"]))
            self.assertEqual((parameters["assistant"], parameters["number"], parameters["content"]),
                             (assistant, number, content))
