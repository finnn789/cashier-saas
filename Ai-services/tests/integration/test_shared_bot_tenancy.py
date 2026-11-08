"""Real-PostgreSQL tests of revision 0003 and per-tenant conversation scoping (TASK-059-F).

Gated: they run only when `AI_TEST_DATABASE_URL` is set to a URL whose role may
CREATE DATABASE (same convention and scratch-database helpers as
`test_internal_members_api.py`). Each class creates scratch databases, runs Alembic
through a subprocess, and drops them afterwards. No network besides that database.
"""

import asyncio
import unittest
import uuid
from collections.abc import Awaitable, Callable
from typing import TypeVar

from sqlalchemy import text
from sqlalchemy.engine import URL
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncConnection, create_async_engine
from sqlalchemy.pool import NullPool

import app.chat.members as members
from app.chat.repositories import ChannelRepository, register_telegram_channel
from app.chat.service import CHOOSE, REVOKED, VERIFIED, ChatService
from tests.integration.test_internal_members_api import (
    DATABASE_URL,
    EngineDatabase,
    create_scratch_database,
    drop_scratch_database,
    run_alembic,
)

HEAD = "0003_shared_bot_tenants"
PHONE = "6281234567890"
T = TypeVar("T")


def on_database(url: URL, work: Callable[[AsyncConnection], Awaitable[T]]) -> T:
    async def run() -> T:
        engine = create_async_engine(url, poolclass=NullPool)
        try:
            async with engine.begin() as connection:
                return await work(connection)
        finally:
            await engine.dispose()

    return asyncio.run(run())


def scalar(url: URL, statement: str, **parameters: object) -> object:
    async def work(connection: AsyncConnection) -> object:
        return await connection.scalar(text(statement), parameters)

    return on_database(url, work)


@unittest.skipUnless(DATABASE_URL, "AI_TEST_DATABASE_URL is not set; real PostgreSQL test skipped")
class PostgresMigration0003Tests(unittest.TestCase):
    def scratch(self) -> URL:
        base, scratch = create_scratch_database()
        self.addCleanup(drop_scratch_database, base, scratch)
        return scratch

    def test_single_tenant_data_survives_0001_to_0003_and_back(self) -> None:
        url = self.scratch()
        run_alembic(url, "upgrade", "0001_channels")
        tenant, channel, user = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
        active, closed = uuid.uuid4(), uuid.uuid4()

        async def seed(connection: AsyncConnection) -> None:
            await connection.execute(text("INSERT INTO tenants (id) VALUES (:t)"), {"t": tenant})
            await connection.execute(text(
                "INSERT INTO channel_accounts (id, tenant_id, platform, external_account_id, username) "
                "VALUES (:c, :t, 'TELEGRAM', '71', 'bot')"), {"c": channel, "t": tenant})
            await connection.execute(text(
                "INSERT INTO channel_users (id, platform, external_user_id) VALUES (:u, 'TELEGRAM', '7')"
            ), {"u": user})
            for conversation, status in ((active, "ACTIVE"), (closed, "CLOSED")):
                await connection.execute(text(
                    "INSERT INTO conversations (id, channel_id, platform, user_id, external_chat_id, status) "
                    "VALUES (:k, :c, 'TELEGRAM', :u, '71', :s)"),
                    {"k": conversation, "c": channel, "u": user, "s": status})
            for index, (conversation, role) in enumerate(
                ((closed, "USER"), (active, "USER"), (active, "ASSISTANT"))
            ):
                await connection.execute(text(
                    "INSERT INTO messages (conversation_id, external_message_id, role, content) "
                    "VALUES (:k, :e, :r, 'isi')"),
                    {"k": conversation, "e": None if role == "ASSISTANT" else f"m{index}", "r": role})

        on_database(url, seed)
        run_alembic(url, "upgrade", HEAD)

        self.assertEqual(scalar(url, "SELECT version_num FROM alembic_version"), HEAD)
        self.assertEqual(scalar(url, "SELECT count(*) FROM conversations WHERE tenant_id = :t", t=tenant), 2)
        self.assertEqual(scalar(url, "SELECT count(*) FROM messages"), 3)
        self.assertIsNone(scalar(url, "SELECT tenant_id FROM channel_accounts WHERE id = :c", c=channel))
        self.assertEqual(scalar(url, "SELECT status FROM conversations WHERE id = :k", k=active), "ACTIVE")
        self.assertEqual(scalar(
            url, "SELECT is_nullable FROM information_schema.columns "
                 "WHERE table_name = 'conversations' AND column_name = 'tenant_id'"), "NO")
        definition = str(scalar(
            url, "SELECT indexdef FROM pg_indexes WHERE indexname = 'one_active_conversation'"))
        self.assertIn("(tenant_id, channel_id, platform, user_id, external_chat_id)", definition)

        run_alembic(url, "downgrade", "0002_tenant_members")
        self.assertEqual(scalar(url, "SELECT tenant_id FROM channel_accounts WHERE id = :c", c=channel), tenant)
        self.assertEqual(scalar(url, "SELECT count(*) FROM conversations"), 2)
        self.assertEqual(scalar(url, "SELECT count(*) FROM messages"), 3)
        self.assertEqual(scalar(
            url, "SELECT count(*) FROM information_schema.columns "
                 "WHERE table_name = 'conversations' AND column_name = 'tenant_id'"), 0)
        self.assertEqual(scalar(
            url, "SELECT count(*) FROM information_schema.tables WHERE table_name = 'tenant_selections'"), 0)

        run_alembic(url, "upgrade", HEAD)
        self.assertEqual(scalar(url, "SELECT count(*) FROM conversations WHERE tenant_id = :t", t=tenant), 2)

    def test_downgrade_refuses_a_channel_with_two_tenants(self) -> None:
        url = self.scratch()
        run_alembic(url, "upgrade", HEAD)
        first, second, channel, user = (uuid.uuid4() for _ in range(4))

        async def seed(connection: AsyncConnection) -> None:
            for tenant in (first, second):
                await connection.execute(text("INSERT INTO tenants (id) VALUES (:t)"), {"t": tenant})
            await connection.execute(text(
                "INSERT INTO channel_accounts (id, platform, external_account_id) VALUES (:c, 'TELEGRAM', '71')"
            ), {"c": channel})
            await connection.execute(text(
                "INSERT INTO channel_users (id, platform, external_user_id) VALUES (:u, 'TELEGRAM', '7')"
            ), {"u": user})
            for tenant in (first, second):
                await connection.execute(text(
                    "INSERT INTO conversations (id, tenant_id, channel_id, platform, user_id, external_chat_id) "
                    "VALUES (:k, :t, :c, 'TELEGRAM', :u, '71')"),
                    {"k": uuid.uuid4(), "t": tenant, "c": channel, "u": user})

        on_database(url, seed)
        with self.assertRaises(AssertionError):
            run_alembic(url, "downgrade", "0002_tenant_members")
        self.assertEqual(scalar(url, "SELECT version_num FROM alembic_version"), HEAD)
        self.assertEqual(scalar(url, "SELECT count(*) FROM conversations"), 2)
        self.assertIsNone(scalar(url, "SELECT tenant_id FROM channel_accounts"))

    def test_downgrade_prunes_shared_channel_without_conversations(self) -> None:
        url = self.scratch()
        run_alembic(url, "upgrade", HEAD)

        async def seed(connection: AsyncConnection) -> None:
            await register_telegram_channel(connection, 71, "bot")

        on_database(url, seed)
        run_alembic(url, "downgrade", "0002_tenant_members")
        self.assertEqual(scalar(url, "SELECT count(*) FROM channel_accounts"), 0)


@unittest.skipUnless(DATABASE_URL, "AI_TEST_DATABASE_URL is not set; real PostgreSQL test skipped")
class PostgresTenantScopeTests(unittest.IsolatedAsyncioTestCase):
    base: URL
    url: URL

    @classmethod
    def setUpClass(cls) -> None:
        cls.base, cls.url = create_scratch_database()
        try:
            run_alembic(cls.url, "upgrade", HEAD)
        except BaseException:
            drop_scratch_database(cls.base, cls.url)
            raise

    @classmethod
    def tearDownClass(cls) -> None:
        drop_scratch_database(cls.base, cls.url)

    async def asyncSetUp(self) -> None:
        self.engine = create_async_engine(self.url, poolclass=NullPool)
        self.addAsyncCleanup(self.engine.dispose)
        async with self.engine.begin() as connection:
            await connection.execute(text(
                "TRUNCATE tenant_selections, tenant_memberships, messages, conversations, "
                "channel_users, channel_accounts, tenant_members, tenants CASCADE"))
        self.database = EngineDatabase(self.engine)
        self.tenant_a, self.tenant_b = uuid.uuid4(), uuid.uuid4()
        async with self.database.transaction() as connection:
            channel_id = await register_telegram_channel(connection, 71, "bot")
            await members.upsert_member(connection, self.tenant_a, uuid.uuid4(), "Staf", PHONE,
                                        "CASHIER", tenant_name="Kopi Senja")
            await members.upsert_member(connection, self.tenant_b, uuid.uuid4(), "Staf", PHONE,
                                        "OWNERMERCHANT", tenant_name="Warung Biru")
        self.channels = ChannelRepository(channel_id, "TELEGRAM")
        self.service = ChatService(self.database, self.channels)  # type: ignore[arg-type]
        self.profile = {"external_user_id": "7", "username": "handle", "first_name": "First"}

    async def value(self, statement: str, **parameters: object) -> object:
        async with self.engine.connect() as connection:
            return await connection.scalar(text(statement), parameters)

    async def say(self, tenant: uuid.UUID, message_id: str, content: str) -> None:
        await self.service.handle(
            tenant_id=tenant, external_chat_id="71", external_message_id=message_id,
            kind="TEXT", content=content, **self.profile,
        )

    async def test_same_user_two_tenants_have_isolated_conversations(self) -> None:
        self.assertEqual((await self.service.verify_contact(**self.profile, phone_normalized=PHONE)).status, CHOOSE)
        self.assertEqual((await self.service.identify(**self.profile)).status, CHOOSE)
        first = await self.service.choose_tenant(**self.profile, choice=1)
        self.assertEqual((first.status, first.tenant_id), (VERIFIED, self.tenant_a))
        await self.say(self.tenant_a, "1", "rahasia A")
        second = await self.service.choose_tenant(**self.profile, choice=2)
        self.assertEqual((second.tenant_id, second.role), (self.tenant_b, "OWNERMERCHANT"))
        await self.say(self.tenant_b, "2", "halo B")

        conversation_a = await self.value("SELECT id FROM conversations WHERE tenant_id = :t", t=self.tenant_a)
        conversation_b = await self.value("SELECT id FROM conversations WHERE tenant_id = :t", t=self.tenant_b)
        self.assertIsNotNone(conversation_a)
        self.assertNotEqual(conversation_a, conversation_b)
        self.assertEqual(await self.value(
            "SELECT content FROM messages WHERE conversation_id = :k", k=conversation_b), "halo B")

        user_id = await self.value("SELECT id FROM channel_users WHERE external_user_id = '7'")
        view_a = self.channels.for_tenant(self.tenant_a)
        view_b = self.channels.for_tenant(self.tenant_b)
        async with self.database.transaction() as connection:
            self.assertTrue(await view_a.find_duplicate_incoming(connection, user_id, "71", "1"))
            self.assertFalse(await view_b.find_duplicate_incoming(connection, user_id, "71", "1"))
            self.assertEqual(
                await view_b.completed_history(connection, conversation_a, user_id, "71", 10**9), []
            )
        with self.assertRaisesRegex(RuntimeError, "CONVERSATION_SCOPE_MISMATCH"):
            async with self.database.transaction() as connection:
                await view_b.insert_message(connection, conversation_a, user_id, "71", "USER", "x", "9")
        self.assertEqual(await self.value(
            "SELECT count(*) FROM messages WHERE conversation_id = :k", k=conversation_a), 1)

    async def test_disabled_member_revokes_membership_and_choice(self) -> None:
        async with self.database.transaction() as connection:
            await members.disable_member(connection, self.tenant_b, await self.value(
                "SELECT java_user_id FROM tenant_members WHERE tenant_id = :t", t=self.tenant_b))
        verified = await self.service.verify_contact(**self.profile, phone_normalized=PHONE)
        self.assertEqual((verified.status, verified.tenant_id), (VERIFIED, self.tenant_a))
        self.assertEqual(await self.value("SELECT count(*) FROM tenant_selections"), 1)
        self.assertIsNotNone(await self.value("SELECT java_user_id FROM tenant_memberships"))
        async with self.database.transaction() as connection:
            await members.disable_member(connection, self.tenant_a, await self.value(
                "SELECT java_user_id FROM tenant_members WHERE tenant_id = :t", t=self.tenant_a))
        self.assertEqual((await self.service.identify(**self.profile)).status, REVOKED)
        self.assertEqual(await self.value("SELECT count(*) FROM tenant_memberships"), 0)
        self.assertEqual(await self.value("SELECT count(*) FROM tenant_selections"), 0)

    async def test_choice_of_an_unverified_tenant_is_rejected_by_the_database(self) -> None:
        async with self.database.transaction() as connection:
            user_id = await self.channels.upsert_user(connection, "8", None, None)
        with self.assertRaises(IntegrityError):
            async with self.database.transaction() as connection:
                await members.select_tenant(connection, user_id, self.tenant_a)

    async def test_one_active_conversation_per_tenant_channel_user_chat(self) -> None:
        async with self.database.transaction() as connection:
            user_id = await self.channels.upsert_user(connection, "7", None, None)
            first = await self.channels.for_tenant(self.tenant_a).resolve_conversation(connection, user_id, "71")
            again = await self.channels.for_tenant(self.tenant_a).resolve_conversation(connection, user_id, "71")
            other = await self.channels.for_tenant(self.tenant_b).resolve_conversation(connection, user_id, "71")
        self.assertEqual(first, again)
        self.assertNotEqual(first, other)
        with self.assertRaises(IntegrityError):
            async with self.engine.begin() as connection:
                await connection.execute(text(
                    "INSERT INTO conversations (id, tenant_id, channel_id, platform, user_id, external_chat_id) "
                    "VALUES (:k, :t, :c, 'TELEGRAM', :u, '71')"),
                    {"k": uuid.uuid4(), "t": self.tenant_a, "c": self.channels.channel_id, "u": user_id})


if __name__ == "__main__":
    unittest.main()
