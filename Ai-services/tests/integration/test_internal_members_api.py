"""ASGI-level tests of the internal member API.

`AsgiMemberApiTests` runs everywhere: the real FastAPI app and the real storage
code are exercised through httpx with a recording fake connection.
`PostgresMemberApiTests` needs a real PostgreSQL and runs only when
`AI_TEST_DATABASE_URL` is set (a URL whose role may CREATE DATABASE). It creates
a scratch database, applies Alembic 0001 and 0002 to it, and drops it afterwards.
"""
import asyncio
import json
import os
import subprocess
import sys
import unittest
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import httpx
from sqlalchemy import text
from sqlalchemy.engine import URL, make_url
from sqlalchemy.exc import DBAPIError, IntegrityError, OperationalError
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

import app.chat.members as members
from app.application import app

ROOT = Path(__file__).resolve().parents[2]
EXAMPLES = json.loads(
    (ROOT.parent / "docs" / "contracts" / "member-sync.example.json").read_text(encoding="utf-8")
)
KEY = "integration-internal-key-0123456789"
HEADERS = {"X-Internal-Service-Key": KEY}
TENANT = uuid.UUID("3f2b8c1e-5a47-4c6e-9d11-0a1b2c3d4e5f")
OTHER_TENANT = uuid.UUID("0b8f2c4d-1e3a-4f5b-8c6d-7e8f9a0b1c2d")
USER = uuid.UUID("7a9d4e21-8b3c-4f60-a512-9e8d7c6b5a40")
PHONE = "6281234567890"
DATABASE_URL = os.getenv("AI_TEST_DATABASE_URL", "").strip()


def member_path(tenant_id: uuid.UUID, user_id: uuid.UUID) -> str:
    return f"/api/v1/internal/tenants/{tenant_id}/members/{user_id}"


def body(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "tenant_name": "Kopi Senja",
        "full_name": "Budi Santoso",
        "phone_number": PHONE,
        "role": "OWNERMERCHANT",
        "status": "ACTIVE",
    }
    payload.update(overrides)
    return payload


class AppStateMixin:
    """Gives the real app a database the way the Telegram channel does at runtime."""

    def install_database(self, database: object) -> None:
        app.state.telegram_channel = SimpleNamespace(database=database)
        self.addCleanup(self.remove_database)  # type: ignore[attr-defined]
        environment = patch.dict(os.environ, {"AI_INTERNAL_API_KEY": KEY})
        environment.start()
        self.addCleanup(environment.stop)  # type: ignore[attr-defined]

    @staticmethod
    def remove_database() -> None:
        if hasattr(app.state, "telegram_channel"):
            del app.state.telegram_channel

    @staticmethod
    def client() -> httpx.AsyncClient:
        return httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://ai-services.test"
        )


class RecordingDatabase:
    def __init__(self) -> None:
        self.events: list[str] = []
        self.statements: list[str] = []
        self.error: Exception | None = None

    @asynccontextmanager
    async def transaction(self):
        self.events.append("begin")
        database = self

        class Connection:
            async def execute(self, statement: object) -> object:
                if database.error is not None:
                    raise database.error
                database.statements.append(str(statement.compile()))  # type: ignore[attr-defined]
                return SimpleNamespace(rowcount=1)

        try:
            yield Connection()
        except BaseException:
            self.events.append("rollback")
            raise
        self.events.append("commit")


class AsgiMemberApiTests(AppStateMixin, unittest.IsolatedAsyncioTestCase):
    async def test_request_flows_through_app_to_one_database_transaction(self) -> None:
        database = RecordingDatabase()
        self.install_database(database)
        async with self.client() as client:
            response = await client.put(member_path(TENANT, USER), json=body(), headers=HEADERS)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json(),
            {"tenant_id": str(TENANT), "java_user_id": str(USER), "status": "ACTIVE"},
        )
        self.assertNotIn(PHONE, response.text)
        self.assertEqual(database.events, ["begin", "commit"])
        self.assertEqual(len(database.statements), 2)
        self.assertIn("INSERT INTO tenants", database.statements[0])
        self.assertIn("INSERT INTO tenant_members", database.statements[1])
        self.assertIn("ON CONFLICT", database.statements[1])

    async def test_contract_examples_are_accepted_end_to_end(self) -> None:
        database = RecordingDatabase()
        self.install_database(database)
        async with self.client() as client:
            for name, example in EXAMPLES.items():
                with self.subTest(example=name):
                    response = await client.put(example["path"], json=example["body"], headers=HEADERS)
                    self.assertEqual(response.status_code, 200)
        self.assertEqual(database.events.count("commit"), len(EXAMPLES))

    async def test_authentication_failures_never_reach_the_database(self) -> None:
        database = RecordingDatabase()
        self.install_database(database)
        async with self.client() as client:
            missing = await client.put(member_path(TENANT, USER), json=body())
            wrong = await client.put(
                member_path(TENANT, USER), json=body(), headers={"X-Internal-Service-Key": "x" * 20}
            )
            with patch.dict(os.environ, {"AI_INTERNAL_API_KEY": ""}):
                unset = await client.put(member_path(TENANT, USER), json=body(), headers=HEADERS)
        self.assertEqual([missing.status_code, wrong.status_code, unset.status_code], [401, 401, 503])
        self.assertEqual(database.events, [])

    async def test_validation_failures_never_reach_the_database(self) -> None:
        database = RecordingDatabase()
        self.install_database(database)
        async with self.client() as client:
            bad_role = await client.put(
                member_path(TENANT, USER), json=body(role="ADMIN"), headers=HEADERS
            )
            bad_phone = await client.put(
                member_path(TENANT, USER), json=body(phone_number="0812-3456"), headers=HEADERS
            )
            bad_uuid = await client.put(
                f"/api/v1/internal/tenants/nope/members/{USER}", json=body(), headers=HEADERS
            )
        self.assertEqual([r.status_code for r in (bad_role, bad_phone, bad_uuid)], [422, 422, 422])
        self.assertNotIn("0812", bad_phone.text)
        self.assertEqual(database.events, [])

    async def test_database_failure_is_a_generic_503_and_rolls_back(self) -> None:
        database = RecordingDatabase()
        database.error = OperationalError("INSERT secret", {"phone": PHONE}, Exception("down"))
        self.install_database(database)
        async with self.client() as client:
            with self.assertLogs("app.api.internal_members", level="ERROR") as captured:
                response = await client.put(member_path(TENANT, USER), json=body(), headers=HEADERS)
        self.assertEqual(response.status_code, 503)
        self.assertEqual(response.json(), {"error": "service_unavailable"})
        self.assertEqual(database.events, ["begin", "rollback"])
        self.assertNotIn(PHONE, response.text + "\n".join(captured.output))

    async def test_no_running_channel_means_503(self) -> None:
        self.install_database(None)
        async with self.client() as client:
            response = await client.put(member_path(TENANT, USER), json=body(), headers=HEADERS)
        self.assertEqual(response.status_code, 503)


def asyncpg_url(raw: str) -> URL:
    return make_url(raw).set(drivername="postgresql+asyncpg")


def render(url: URL) -> str:
    return url.render_as_string(hide_password=False)


async def _admin(url: URL, statement: str) -> None:
    engine = create_async_engine(url, poolclass=NullPool, isolation_level="AUTOCOMMIT")
    try:
        async with engine.connect() as connection:
            await connection.execute(text(statement))
    finally:
        await engine.dispose()


def run_alembic(url: URL, *arguments: str) -> None:
    environment = {**os.environ, "DATABASE_URL": render(url)}
    completed = subprocess.run(
        [sys.executable, "-m", "alembic", *arguments],
        cwd=ROOT, env=environment, capture_output=True, text=True, timeout=120, check=False,
    )
    if completed.returncode != 0:
        # stderr may carry connection details; keep only the last line for the test log.
        raise AssertionError(f"alembic {' '.join(arguments)} failed: {completed.stderr[-300:]}")


def create_scratch_database() -> tuple[URL, URL]:
    base = asyncpg_url(DATABASE_URL)
    name = f"ai_members_{uuid.uuid4().hex[:12]}"
    asyncio.run(_admin(base, f'CREATE DATABASE "{name}"'))
    return base, base.set(database=name)


def drop_scratch_database(base: URL, scratch: URL) -> None:
    asyncio.run(_admin(base, f'DROP DATABASE IF EXISTS "{scratch.database}" WITH (FORCE)'))


class EngineDatabase:
    """Same `transaction()` shape as the owner-connection Database, on a plain engine."""

    def __init__(self, engine: object) -> None:
        self.engine = engine

    @asynccontextmanager
    async def transaction(self):
        async with self.engine.begin() as connection:  # type: ignore[attr-defined]
            yield connection


@unittest.skipUnless(DATABASE_URL, "AI_TEST_DATABASE_URL is not set; real PostgreSQL test skipped")
class PostgresMemberApiTests(AppStateMixin, unittest.IsolatedAsyncioTestCase):
    base: URL
    scratch: URL

    @classmethod
    def setUpClass(cls) -> None:
        cls.base, cls.scratch = create_scratch_database()
        try:
            run_alembic(cls.scratch, "upgrade", "0002_tenant_members")
        except BaseException:
            drop_scratch_database(cls.base, cls.scratch)
            raise

    @classmethod
    def tearDownClass(cls) -> None:
        drop_scratch_database(cls.base, cls.scratch)

    async def asyncSetUp(self) -> None:
        self.engine = create_async_engine(self.scratch, poolclass=NullPool)
        self.addAsyncCleanup(self.engine.dispose)
        async with self.engine.begin() as connection:
            await connection.execute(text("TRUNCATE tenant_members, tenants CASCADE"))
        self.install_database(EngineDatabase(self.engine))

    async def scalar(self, statement: str, **parameters: object) -> object:
        async with self.engine.connect() as connection:
            return await connection.scalar(text(statement), parameters)

    async def put(self, tenant_id: uuid.UUID, user_id: uuid.UUID, payload: dict[str, object]):
        async with self.client() as client:
            return await client.put(member_path(tenant_id, user_id), json=payload, headers=HEADERS)

    async def test_upsert_creates_tenant_and_member(self) -> None:
        response = await self.put(TENANT, USER, body())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(await self.scalar("SELECT name FROM tenants WHERE id = :t", t=TENANT), "Kopi Senja")
        row = await self.scalar(
            "SELECT full_name || '|' || phone_normalized || '|' || role || '|' || status "
            "FROM tenant_members WHERE tenant_id = :t AND java_user_id = :u",
            t=TENANT, u=USER,
        )
        self.assertEqual(row, f"Budi Santoso|{PHONE}|OWNERMERCHANT|ACTIVE")

    async def test_repeat_is_idempotent_and_update_overwrites(self) -> None:
        self.assertEqual((await self.put(TENANT, USER, body())).status_code, 200)
        created = await self.scalar("SELECT created_at FROM tenant_members")
        self.assertEqual((await self.put(TENANT, USER, body())).status_code, 200)
        self.assertEqual(await self.scalar("SELECT count(*) FROM tenant_members"), 1)
        self.assertEqual(await self.scalar("SELECT count(*) FROM tenants"), 1)

        changed = body(full_name="Budi S.", phone_number="628999888777", role="CASHIER")
        del changed["tenant_name"]
        self.assertEqual((await self.put(TENANT, USER, changed)).status_code, 200)
        self.assertEqual(await self.scalar("SELECT count(*) FROM tenant_members"), 1)
        self.assertEqual(await self.scalar("SELECT full_name FROM tenant_members"), "Budi S.")
        self.assertEqual(await self.scalar("SELECT phone_normalized FROM tenant_members"), "628999888777")
        self.assertEqual(await self.scalar("SELECT role FROM tenant_members"), "CASHIER")
        self.assertEqual(await self.scalar("SELECT created_at FROM tenant_members"), created)
        self.assertTrue(await self.scalar("SELECT updated_at >= created_at FROM tenant_members"))
        self.assertEqual(await self.scalar("SELECT name FROM tenants"), "Kopi Senja")

    async def test_disabled_members_are_excluded_from_phone_lookup(self) -> None:
        await self.put(TENANT, USER, body())
        await self.put(OTHER_TENANT, USER, body(tenant_name="Warung Biru", role="CASHIER"))
        async with EngineDatabase(self.engine).transaction() as connection:
            found = await members.find_active_members_by_phone(connection, PHONE)
        self.assertEqual({item.tenant_id for item in found}, {TENANT, OTHER_TENANT})
        self.assertEqual([item.tenant_name for item in found], ["Kopi Senja", "Warung Biru"])

        disabled = await self.put(OTHER_TENANT, USER, body(status="DISABLED"))
        self.assertEqual(disabled.json()["status"], "DISABLED")
        async with EngineDatabase(self.engine).transaction() as connection:
            found = await members.find_active_members_by_phone(connection, PHONE)
            self.assertEqual([item.tenant_id for item in found], [TENANT])
            self.assertTrue(await members.disable_member(connection, TENANT, USER))
            self.assertFalse(await members.disable_member(connection, TENANT, uuid.uuid4()))
            self.assertEqual(await members.find_active_members_by_phone(connection, PHONE), [])
            reread = await members.get_member(connection, TENANT, USER)
        self.assertEqual(reread.status, "DISABLED")  # type: ignore[union-attr]

    async def test_same_user_in_two_tenants_stays_separate(self) -> None:
        await self.put(TENANT, USER, body())
        await self.put(OTHER_TENANT, USER, body(full_name="Lain", tenant_name="Warung Biru"))
        self.assertEqual(await self.scalar("SELECT count(*) FROM tenant_members"), 2)
        self.assertEqual(
            await self.scalar("SELECT full_name FROM tenant_members WHERE tenant_id = :t", t=OTHER_TENANT),
            "Lain",
        )

    async def test_database_constraints_reject_bad_rows(self) -> None:
        await self.put(TENANT, USER, body())
        insert = (
            "INSERT INTO tenant_members (tenant_id, java_user_id, full_name, phone_normalized, role, status) "
            "VALUES (:t, :u, 'X', :p, :r, :s)"
        )
        cases = {
            "bad role": dict(t=TENANT, u=uuid.uuid4(), p=PHONE, r="ADMIN", s="ACTIVE"),
            "bad status": dict(t=TENANT, u=uuid.uuid4(), p=PHONE, r="CASHIER", s="GONE"),
            "unknown tenant": dict(t=uuid.uuid4(), u=uuid.uuid4(), p=PHONE, r="CASHIER", s="ACTIVE"),
            "duplicate key": dict(t=TENANT, u=USER, p=PHONE, r="CASHIER", s="ACTIVE"),
        }
        for label, parameters in cases.items():
            with self.subTest(label):
                with self.assertRaises(IntegrityError):
                    async with self.engine.begin() as connection:
                        await connection.execute(text(insert), parameters)
        with self.assertRaises(DBAPIError):  # value too long for varchar(15)
            async with self.engine.begin() as connection:
                await connection.execute(
                    text(insert), dict(t=TENANT, u=uuid.uuid4(), p="1" * 16, r="CASHIER", s="ACTIVE")
                )
        self.assertEqual(await self.scalar("SELECT count(*) FROM tenant_members"), 1)

    async def test_status_defaults_to_active_and_partial_index_exists(self) -> None:
        async with self.engine.begin() as connection:
            await connection.execute(text("INSERT INTO tenants (id) VALUES (:t)"), {"t": TENANT})
            await connection.execute(
                text(
                    "INSERT INTO tenant_members (tenant_id, java_user_id, full_name, phone_normalized, role) "
                    "VALUES (:t, :u, 'X', :p, 'CASHIER')"
                ),
                {"t": TENANT, "u": USER, "p": PHONE},
            )
        self.assertEqual(await self.scalar("SELECT status FROM tenant_members"), "ACTIVE")
        definition = await self.scalar(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'tenant_member_active_phone'"
        )
        self.assertIn("phone_normalized", definition)  # type: ignore[arg-type]
        self.assertIn("status", definition)  # type: ignore[arg-type]
        self.assertIn("ACTIVE", definition)  # type: ignore[arg-type]
        self.assertEqual(await self.scalar("SELECT name FROM tenants WHERE id = :t", t=TENANT), None)


@unittest.skipUnless(DATABASE_URL, "AI_TEST_DATABASE_URL is not set; real PostgreSQL test skipped")
class PostgresMigrationRoundTripTests(unittest.TestCase):
    def test_upgrade_downgrade_upgrade(self) -> None:
        base, scratch = create_scratch_database()
        self.addCleanup(drop_scratch_database, base, scratch)
        run_alembic(scratch, "upgrade", "0002_tenant_members")
        run_alembic(scratch, "downgrade", "0001_channels")

        async def tables() -> set[str]:
            engine = create_async_engine(scratch, poolclass=NullPool)
            try:
                async with engine.connect() as connection:
                    result = await connection.execute(
                        text("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                    )
                    columns = await connection.execute(
                        text(
                            "SELECT column_name FROM information_schema.columns "
                            "WHERE table_name = 'tenants'"
                        )
                    )
                    return {row[0] for row in result} | {f"tenants.{row[0]}" for row in columns}
            finally:
                await engine.dispose()

        after_downgrade = asyncio.run(tables())
        self.assertNotIn("tenant_members", after_downgrade)
        self.assertNotIn("tenants.name", after_downgrade)
        self.assertIn("tenant_memberships", after_downgrade)
        run_alembic(scratch, "upgrade", "0002_tenant_members")
        after_upgrade = asyncio.run(tables())
        self.assertIn("tenant_members", after_upgrade)
        self.assertIn("tenants.name", after_upgrade)


if __name__ == "__main__":
    unittest.main()
