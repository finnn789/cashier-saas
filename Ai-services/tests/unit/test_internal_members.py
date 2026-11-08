"""Internal member sync API with a fake member store; no database, network or .env."""
import importlib.util
import io
import json
import os
import unittest
from contextlib import asynccontextmanager
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
from uuid import UUID

from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.operations import Operations
from alembic.script import ScriptDirectory
from fastapi.testclient import TestClient
from sqlalchemy.dialects import postgresql
from sqlalchemy.exc import OperationalError

import app.chat.members as members
from app.api.internal_members import (
    DatabaseMemberStore,
    MemberUpsertRequest,
    get_internal_api_settings,
    get_member_store,
)
from app.application import app
from app.chat.entities import Base, Tenant, TenantMember
from app.config import InternalApiSettings, Settings, load_internal_api_settings
from app.core.database import OwnershipLost

ROOT = Path(__file__).resolve().parents[2]
EXAMPLE = json.loads(
    (ROOT.parent / "docs" / "contracts" / "member-sync.example.json").read_text(encoding="utf-8")
)
KEY = "unit-test-internal-key-0123456789"
TENANT = "3f2b8c1e-5a47-4c6e-9d11-0a1b2c3d4e5f"
USER = "7a9d4e21-8b3c-4f60-a512-9e8d7c6b5a40"
PATH = f"/api/v1/internal/tenants/{TENANT}/members/{USER}"
PHONE = "6281234567890"
HEADERS = {"X-Internal-Service-Key": KEY}


def valid_body(**overrides: object) -> dict[str, object]:
    body: dict[str, object] = {
        "tenant_name": "Kopi Senja",
        "full_name": "Budi Santoso",
        "phone_number": PHONE,
        "role": "OWNERMERCHANT",
        "status": "ACTIVE",
    }
    body.update(overrides)
    return body


class FakeStore:
    """In-memory stand-in for the member store: keyed like the real primary key."""

    def __init__(self) -> None:
        self.members: dict[tuple[UUID, UUID], dict[str, object]] = {}
        self.tenant_names: dict[UUID, str | None] = {}
        self.calls = 0
        self.error: Exception | None = None

    async def upsert(self, tenant_id: UUID, java_user_id: UUID, member: MemberUpsertRequest) -> None:
        self.calls += 1
        if self.error is not None:
            raise self.error
        known = self.tenant_names.get(tenant_id)
        self.tenant_names[tenant_id] = member.tenant_name if member.tenant_name else known
        self.members[(tenant_id, java_user_id)] = {
            "full_name": member.full_name,
            "phone": member.phone_number,
            "role": member.role,
            "status": member.status,
        }


class InternalMembersApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.store = FakeStore()
        self.settings = InternalApiSettings(KEY)
        app.dependency_overrides[get_member_store] = lambda: self.store
        app.dependency_overrides[get_internal_api_settings] = lambda: self.settings
        # No context manager: the lifespan (Telegram, PostgreSQL) is deliberately not started.
        self.client = TestClient(app)

    def tearDown(self) -> None:
        app.dependency_overrides.clear()

    def put(self, body: object = None, headers: dict[str, str] | None = None, path: str = PATH):
        payload = valid_body() if body is None else body
        content = payload if isinstance(payload, (bytes, str)) else json.dumps(payload)
        return self.client.put(
            path,
            content=content,
            headers={"Content-Type": "application/json", **(HEADERS if headers is None else headers)},
        )

    def test_success_returns_contract_body_without_phone_or_key(self) -> None:
        response = self.put()
        self.assertEqual(response.status_code, 200)
        self.assertEqual(
            response.json(), {"tenant_id": TENANT, "java_user_id": USER, "status": "ACTIVE"}
        )
        self.assertNotIn(PHONE, response.text)
        self.assertNotIn(KEY, response.text)
        stored = self.store.members[(UUID(TENANT), UUID(USER))]
        self.assertEqual(stored["phone"], PHONE)
        self.assertEqual(self.store.tenant_names[UUID(TENANT)], "Kopi Senja")

    def test_contract_example_payloads_are_accepted(self) -> None:
        for name, example in EXAMPLE.items():
            with self.subTest(example=name):
                response = self.put(example["body"], path=example["path"])
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json()["status"], example["body"]["status"])
                self.assertNotIn(example["body"]["phone_number"], response.text)
        self.assertEqual(len(self.store.members), len(EXAMPLE))

    def test_missing_wrong_and_unset_key(self) -> None:
        missing = self.put(headers={})
        wrong = self.put(headers={"X-Internal-Service-Key": KEY[:-1] + "x"})
        shorter = self.put(headers={"X-Internal-Service-Key": KEY[:-1]})
        non_ascii = self.put(headers={"X-Internal-Service-Key": "kunci-é".encode("latin-1")})
        self.assertEqual(
            [missing.status_code, wrong.status_code, shorter.status_code, non_ascii.status_code],
            [401, 401, 401, 401],
        )
        for response in (missing, wrong, shorter):
            self.assertEqual(response.json(), {"error": "unauthorized"})
        self.assertEqual(self.store.calls, 0)

        self.settings = InternalApiSettings("")
        unset = self.put()
        self.assertEqual(unset.status_code, 503)
        self.assertEqual(self.put(headers={}).status_code, 503)
        self.assertEqual(self.store.calls, 0)

    def test_key_shorter_than_sixteen_chars_fails_closed(self) -> None:
        self.settings = InternalApiSettings("short-key")
        response = self.put(headers={"X-Internal-Service-Key": "short-key"})
        self.assertEqual(response.status_code, 503)
        self.assertEqual(self.store.calls, 0)

    def test_authorization_precedes_validation(self) -> None:
        response = self.put(body="{not json", headers={}, path=f"/api/v1/internal/tenants/x/members/y")
        self.assertEqual(response.status_code, 401)

    def test_key_comparison_is_constant_time(self) -> None:
        with patch("app.api.internal_members.hmac.compare_digest", return_value=True) as compare:
            response = self.put(headers={"X-Internal-Service-Key": "anything"})
        self.assertEqual(response.status_code, 200)
        compare.assert_called_once()
        self.assertTrue(all(isinstance(value, bytes) for value in compare.call_args.args))

    def test_invalid_path_uuids(self) -> None:
        for path in (
            f"/api/v1/internal/tenants/not-a-uuid/members/{USER}",
            f"/api/v1/internal/tenants/{TENANT}/members/not-a-uuid",
            "/api/v1/internal/tenants/1/members/2",
        ):
            with self.subTest(path=path):
                response = self.put(path=path)
                self.assertEqual(response.status_code, 422)
                self.assertNotIn(PHONE, response.text)
        self.assertEqual(self.store.calls, 0)

    def test_invalid_bodies_are_rejected_without_echoing_input(self) -> None:
        cases = {
            "role": valid_body(role="ADMIN"),
            "status": valid_body(status="DELETED"),
            "phone_number": valid_body(phone_number="+6281234567890"),
            "phone_short": valid_body(phone_number="12345678"),
            "phone_long": valid_body(phone_number="1234567890123456"),
            "phone_letters": valid_body(phone_number="08123abc7890"),
            "phone_number_type": valid_body(phone_number=6281234567890),
            "full_name_blank": valid_body(full_name="   "),
            "full_name_long": valid_body(full_name="x" * 161),
            "tenant_name_blank": valid_body(tenant_name=""),
            "tenant_name_long": valid_body(tenant_name="x" * 161),
        }
        for name, body in cases.items():
            with self.subTest(case=name):
                response = self.put(body)
                self.assertEqual(response.status_code, 422)
                self.assertEqual(response.json()["error"], "validation_error")
                self.assertNotIn("0812", response.text)
                self.assertNotIn(PHONE, response.text)
        self.assertEqual(self.store.calls, 0)

    def test_missing_required_fields_report_field_names_only(self) -> None:
        body = valid_body()
        del body["role"]
        del body["phone_number"]
        response = self.put(body)
        self.assertEqual(response.status_code, 422)
        self.assertEqual(response.json()["fields"], ["phone_number", "role"])

    def test_malformed_json_and_oversized_body(self) -> None:
        self.assertEqual(self.put("{not json").status_code, 422)
        self.assertEqual(self.put("[]").status_code, 422)
        self.assertEqual(self.put("").status_code, 422)
        self.assertEqual(self.put(valid_body(full_name="x" * (20 * 1024))).status_code, 422)
        self.assertEqual(self.store.calls, 0)

    def test_status_defaults_to_active_and_tenant_name_is_optional(self) -> None:
        body = valid_body()
        del body["status"]
        del body["tenant_name"]
        response = self.put(body)
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "ACTIVE")
        self.assertEqual(self.store.members[(UUID(TENANT), UUID(USER))]["status"], "ACTIVE")

    def test_full_name_and_tenant_name_are_trimmed(self) -> None:
        self.put(valid_body(full_name="  Budi  ", tenant_name="  Kopi Senja "))
        self.assertEqual(self.store.members[(UUID(TENANT), UUID(USER))]["full_name"], "Budi")
        self.assertEqual(self.store.tenant_names[UUID(TENANT)], "Kopi Senja")

    def test_repeat_is_idempotent(self) -> None:
        first = self.put()
        snapshot = dict(self.store.members)
        second = self.put()
        self.assertEqual(first.status_code, second.status_code)
        self.assertEqual(first.json(), second.json())
        self.assertEqual(self.store.members, snapshot)
        self.assertEqual(len(self.store.members), 1)

    def test_changed_fields_overwrite_and_missing_tenant_name_keeps_the_old_one(self) -> None:
        self.put()
        body = valid_body(full_name="Budi S.", phone_number="628999888777", role="CASHIER")
        del body["tenant_name"]
        self.assertEqual(self.put(body).status_code, 200)
        stored = self.store.members[(UUID(TENANT), UUID(USER))]
        self.assertEqual(stored["full_name"], "Budi S.")
        self.assertEqual(stored["phone"], "628999888777")
        self.assertEqual(stored["role"], "CASHIER")
        self.assertEqual(self.store.tenant_names[UUID(TENANT)], "Kopi Senja")
        self.assertEqual(len(self.store.members), 1)

    def test_disabled_status_is_stored_and_returned(self) -> None:
        response = self.put(valid_body(status="DISABLED"))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["status"], "DISABLED")
        self.assertEqual(self.store.members[(UUID(TENANT), UUID(USER))]["status"], "DISABLED")

    def test_same_user_id_in_two_tenants_stays_separate(self) -> None:
        other = "11111111-2222-4333-8444-555555555555"
        self.put()
        self.put(valid_body(full_name="Lain"), path=f"/api/v1/internal/tenants/{other}/members/{USER}")
        self.assertEqual(len(self.store.members), 2)
        self.assertEqual(self.store.members[(UUID(TENANT), UUID(USER))]["full_name"], "Budi Santoso")

    def test_storage_failures_become_generic_503(self) -> None:
        errors = (
            OperationalError("SELECT secret-phone-6281234567890", {}, Exception("boom")),
            OwnershipLost("DB_OWNER_LOST"),
        )
        for error in errors:
            with self.subTest(error=type(error).__name__):
                self.store.error = error
                with self.assertLogs("app.api.internal_members", level="ERROR") as captured:
                    response = self.put()
                self.assertEqual(response.status_code, 503)
                self.assertEqual(response.json(), {"error": "service_unavailable"})
                self.assertNotIn(PHONE, response.text)
                logged = "\n".join(captured.output)
                self.assertNotIn(PHONE, logged)
                self.assertNotIn(KEY, logged)

    def test_store_is_unavailable_without_a_running_channel(self) -> None:
        app.dependency_overrides.pop(get_member_store)
        response = self.put()
        self.assertEqual(response.status_code, 503)

    def test_logs_never_contain_phone_key_or_body(self) -> None:
        with self.assertNoLogs("app", level="DEBUG"):
            self.assertEqual(self.put().status_code, 200)
        with self.assertLogs("app.api.internal_members", level="DEBUG") as captured:
            self.put(headers={"X-Internal-Service-Key": "wrong-key-value-123456"})
            self.settings = InternalApiSettings("")
            self.put()
        logged = "\n".join(captured.output)
        for secret in (PHONE, KEY, "wrong-key-value-123456", "Budi Santoso", "Kopi Senja"):
            self.assertNotIn(secret, logged)

    def test_route_is_wired_into_the_real_app_next_to_health_routes(self) -> None:
        self.settings = InternalApiSettings("")
        self.assertEqual(self.client.get("/health").json(), {"status": "ok"})
        self.assertEqual(self.client.get("/").json(), {"status": "ok"})
        self.assertEqual(self.client.put(PATH, json=valid_body()).status_code, 503)
        self.assertEqual(self.client.get(PATH).status_code, 405)
        spec = self.client.get("/openapi.json").json()
        self.assertIn("/api/v1/internal/tenants/{tenant_id}/members/{java_user_id}", spec["paths"])


class InternalApiSettingsTests(unittest.TestCase):
    def test_key_is_loaded_from_environment_and_hidden_from_repr(self) -> None:
        with patch.dict(os.environ, {"AI_INTERNAL_API_KEY": f"  {KEY}  "}, clear=True):
            settings = load_internal_api_settings()
        self.assertEqual(settings.api_key, KEY)
        self.assertTrue(settings.configured)
        self.assertNotIn(KEY, repr(settings))

    def test_unset_blank_and_short_keys_are_not_configured(self) -> None:
        for env in ({}, {"AI_INTERNAL_API_KEY": "   "}, {"AI_INTERNAL_API_KEY": "short"}):
            with self.subTest(env=env), patch.dict(os.environ, env, clear=True):
                self.assertFalse(load_internal_api_settings().configured)

    def test_settings_carry_the_key_without_leaking_it(self) -> None:
        settings = Settings(Mock(), Mock(), Mock(), internal_api=InternalApiSettings(KEY))
        self.assertEqual(settings.internal_api.api_key, KEY)
        self.assertNotIn(KEY, repr(settings.internal_api))


class RecordingConnection:
    def __init__(self, rows: list[dict[str, object]] | None = None, rowcount: int = 1) -> None:
        self.statements: list[object] = []
        self.rows = rows or []
        self.rowcount = rowcount

    async def execute(self, statement: object) -> SimpleNamespace:
        self.statements.append(statement)
        mappings = Mock()
        mappings.__iter__ = lambda _self: iter(self.rows)
        mappings.first = Mock(return_value=self.rows[0] if self.rows else None)
        return SimpleNamespace(mappings=lambda: mappings, rowcount=self.rowcount)


def compiled(statement: object) -> tuple[str, dict[str, object]]:
    result = statement.compile(dialect=postgresql.dialect())  # type: ignore[attr-defined]
    return str(result), result.params


class MembersRepositoryTests(unittest.IsolatedAsyncioTestCase):
    async def test_upsert_creates_tenant_first_then_member_with_conflict_update(self) -> None:
        connection = RecordingConnection()
        await members.upsert_member(
            connection,  # type: ignore[arg-type]
            UUID(TENANT), UUID(USER), "Budi", PHONE, "CASHIER", "DISABLED", "Kopi",
        )
        tenant_sql, tenant_params = compiled(connection.statements[0])
        member_sql, member_params = compiled(connection.statements[1])
        self.assertIn("INSERT INTO tenants", tenant_sql)
        self.assertIn("ON CONFLICT (id) DO UPDATE SET name = coalesce(excluded.name, tenants.name)", tenant_sql)
        self.assertIn("INSERT INTO tenant_members", member_sql)
        self.assertIn("ON CONFLICT (tenant_id, java_user_id) DO UPDATE", member_sql)
        self.assertIn("updated_at = now()", member_sql)
        self.assertEqual(member_params["tenant_id"], UUID(TENANT))
        self.assertEqual(member_params["java_user_id"], UUID(USER))
        self.assertEqual(member_params["status"], "DISABLED")
        self.assertEqual(tenant_params["name"], "Kopi")

    async def test_upsert_rejects_bad_arguments_before_touching_the_database(self) -> None:
        connection = RecordingConnection()
        bad_calls = (
            ("tenant", dict(tenant_id=TENANT)),
            ("user", dict(java_user_id=USER)),
            ("role", dict(role="ADMIN")),
            ("status", dict(status="GONE")),
            ("name", dict(full_name="")),
            ("phone", dict(phone_normalized="")),
        )
        for label, override in bad_calls:
            with self.subTest(label), self.assertRaises(ValueError):
                arguments = dict(
                    tenant_id=UUID(TENANT), java_user_id=UUID(USER), full_name="Budi",
                    phone_normalized=PHONE, role="CASHIER",
                )
                arguments.update(override)
                await members.upsert_member(connection, **arguments)  # type: ignore[arg-type]
        self.assertEqual(connection.statements, [])

    async def test_find_active_by_phone_filters_status_and_returns_no_phone(self) -> None:
        row = {
            "tenant_id": UUID(TENANT), "java_user_id": UUID(USER), "full_name": "Budi",
            "role": "CASHIER", "status": "ACTIVE", "tenant_name": "Kopi",
        }
        connection = RecordingConnection([row])
        found = await members.find_active_members_by_phone(connection, PHONE)  # type: ignore[arg-type]
        sql, params = compiled(connection.statements[0])
        self.assertIn("tenant_members.phone_normalized =", sql)
        self.assertIn("tenant_members.status =", sql)
        self.assertIn("ACTIVE", params.values())
        self.assertIn(PHONE, params.values())
        self.assertEqual(found[0].tenant_id, UUID(TENANT))
        self.assertEqual(found[0].tenant_name, "Kopi")
        self.assertNotIn(PHONE, repr(found))
        self.assertNotIn("Budi", repr(found))
        self.assertEqual(await members.find_active_members_by_phone(connection, ""), [])  # type: ignore[arg-type]
        self.assertEqual(len(connection.statements), 1)

    async def test_get_and_disable_are_scoped_to_the_tenant(self) -> None:
        connection = RecordingConnection(rowcount=1)
        self.assertIsNone(await members.get_member(connection, UUID(TENANT), UUID(USER)))  # type: ignore[arg-type]
        self.assertTrue(await members.disable_member(connection, UUID(TENANT), UUID(USER)))  # type: ignore[arg-type]
        for statement in connection.statements:
            sql, params = compiled(statement)
            self.assertIn("tenant_members.tenant_id =", sql)
            self.assertIn("tenant_members.java_user_id =", sql)
            self.assertIn(UUID(TENANT), params.values())
        update_sql, update_params = compiled(connection.statements[1])
        self.assertIn("UPDATE tenant_members SET status=", update_sql)
        self.assertIn("DISABLED", update_params.values())
        self.assertFalse(
            await members.disable_member(RecordingConnection(rowcount=0), UUID(TENANT), UUID(USER))  # type: ignore[arg-type]
        )


class DatabaseMemberStoreTests(unittest.IsolatedAsyncioTestCase):
    async def test_store_runs_the_upsert_inside_one_database_transaction(self) -> None:
        events: list[str] = []
        connection = object()

        @asynccontextmanager
        async def transaction():
            events.append("begin")
            yield connection
            events.append("commit")

        database = SimpleNamespace(transaction=transaction)
        member = MemberUpsertRequest(**valid_body())  # type: ignore[arg-type]
        with patch("app.api.internal_members.upsert_member", new=AsyncMock()) as upsert:
            await DatabaseMemberStore(database).upsert(UUID(TENANT), UUID(USER), member)  # type: ignore[arg-type]
        upsert.assert_awaited_once_with(
            connection, UUID(TENANT), UUID(USER), "Budi Santoso", PHONE,
            "OWNERMERCHANT", "ACTIVE", "Kopi Senja",
        )
        self.assertEqual(events, ["begin", "commit"])

    def test_request_model_repr_hides_the_phone(self) -> None:
        self.assertNotIn(PHONE, repr(MemberUpsertRequest(**valid_body())))  # type: ignore[arg-type]


def load_revision(name: str):
    spec = importlib.util.spec_from_file_location(
        f"migration_{name}", ROOT / "migrations" / "versions" / f"{name}.py"
    )
    module = importlib.util.module_from_spec(spec)  # type: ignore[arg-type]
    spec.loader.exec_module(module)  # type: ignore[union-attr]
    return module


class MigrationRevisionTests(unittest.TestCase):
    def test_chain_is_linear_and_0002_follows_0001(self) -> None:
        revision = load_revision("0002_tenant_members")
        self.assertEqual(revision.revision, "0002_tenant_members")
        self.assertEqual(revision.down_revision, "0001_channels")
        script = ScriptDirectory.from_config(Config(str(ROOT / "alembic.ini")))
        self.assertEqual(len(script.get_heads()), 1)
        history = [item.revision for item in script.walk_revisions()]
        self.assertIn("0002_tenant_members", history)
        self.assertEqual(history[-1], "0001_channels")
        self.assertEqual(
            history.index("0001_channels"), history.index("0002_tenant_members") + 1
        )

    def test_migration_0001_is_untouched_and_has_no_member_table(self) -> None:
        text = (ROOT / "migrations" / "versions" / "0001_channels.py").read_text(encoding="utf-8")
        self.assertNotIn('"tenant_members"', text)

    def render(self, direction: str) -> str:
        output = io.StringIO()
        context = MigrationContext.configure(
            dialect_name="postgresql", opts={"as_sql": True, "output_buffer": output}
        )
        revision = load_revision("0002_tenant_members")
        with Operations.context(context):
            getattr(revision, direction)()
        return output.getvalue()

    def test_upgrade_sql_matches_the_models(self) -> None:
        sql = " ".join(self.render("upgrade").split())
        self.assertIn("ALTER TABLE tenants ADD COLUMN name VARCHAR(160);", sql)
        self.assertIn("CREATE TABLE tenant_members", sql)
        table = Base.metadata.tables["tenant_members"]
        for column in table.columns:
            self.assertIn(f"{column.name} ", sql)
        self.assertIn("PRIMARY KEY (tenant_id, java_user_id)", sql)
        self.assertIn("FOREIGN KEY(tenant_id) REFERENCES tenants (id)", sql)
        self.assertIn("CHECK (role IN ('OWNERMERCHANT','CASHIER'))", sql)
        self.assertIn("CHECK (status IN ('ACTIVE','DISABLED'))", sql)
        self.assertIn("status VARCHAR(16) DEFAULT 'ACTIVE' NOT NULL", sql)
        self.assertIn("full_name VARCHAR(160) NOT NULL", sql)
        self.assertIn("phone_normalized VARCHAR(15) NOT NULL", sql)
        self.assertIn("role VARCHAR(32) NOT NULL", sql)
        self.assertIn("created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL", sql)
        self.assertIn("updated_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL", sql)
        self.assertIn(
            "CREATE INDEX tenant_member_active_phone ON tenant_members (phone_normalized) "
            "WHERE status='ACTIVE'",
            sql,
        )

    def test_downgrade_sql_reverses_the_upgrade(self) -> None:
        sql = " ".join(self.render("downgrade").split())
        self.assertIn("DROP INDEX tenant_member_active_phone", sql)
        self.assertIn("DROP TABLE tenant_members", sql)
        self.assertIn("ALTER TABLE tenants DROP COLUMN name", sql)
        self.assertLess(sql.index("DROP TABLE tenant_members"), sql.index("DROP COLUMN name"))

    def test_models_declare_the_documented_shape(self) -> None:
        table = TenantMember.__table__
        self.assertEqual([column.name for column in table.primary_key.columns],
                         ["tenant_id", "java_user_id"])
        self.assertEqual(table.c.full_name.type.length, 160)
        self.assertEqual(table.c.phone_normalized.type.length, 15)
        self.assertEqual(table.c.role.type.length, 32)
        self.assertEqual(table.c.status.type.length, 16)
        self.assertFalse(table.c.full_name.nullable)
        self.assertFalse(table.c.phone_normalized.nullable)
        self.assertEqual(Tenant.__table__.c.name.type.length, 160)
        self.assertTrue(Tenant.__table__.c.name.nullable)
        index = next(item for item in table.indexes if item.name == "tenant_member_active_phone")
        self.assertEqual([column.name for column in index.columns], ["phone_normalized"])
        self.assertEqual(str(index.dialect_options["postgresql"]["where"]), "status='ACTIVE'")


if __name__ == "__main__":
    unittest.main()
