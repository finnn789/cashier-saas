"""Compiled-SQL contracts of the verified-membership functions in app.chat.members."""

import re
import unittest
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlalchemy.sql import Executable

import app.chat.members as members
from app.chat.members import MemberMatch


def compiled(statement: Executable) -> tuple[str, dict[str, object]]:
    result = statement.compile(
        dialect=postgresql.dialect(), compile_kwargs={"render_postcompile": True}
    )
    return re.sub(r"\s+", " ", str(result)).strip(), dict(result.params)


class MembershipSqlTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.connection = Mock(spec=AsyncConnection)
        self.connection.execute = AsyncMock()
        self.connection.scalar = AsyncMock()
        self.user_id = uuid4()
        self.tenant_a = uuid4()
        self.tenant_b = uuid4()

    def statement(self, index: int = 0) -> tuple[str, dict[str, object]]:
        return compiled(self.connection.execute.await_args_list[index].args[0])

    def match(self, tenant_id: object, role: str = "CASHIER", status: str = "ACTIVE") -> MemberMatch:
        return MemberMatch(tenant_id, uuid4(), "Kopi", role, status, "Nama")  # type: ignore[arg-type]

    async def test_list_joins_current_member_state_for_this_user_only(self) -> None:
        tenant_a, java_user = self.tenant_a, uuid4()
        rows = [{"tenant_id": tenant_a, "java_user_id": java_user, "tenant_name": "Kopi",
                 "member_role": "OWNERMERCHANT", "member_status": "ACTIVE"},
                {"tenant_id": self.tenant_b, "java_user_id": None, "tenant_name": None,
                 "member_role": None, "member_status": None}]
        self.connection.execute.return_value = Mock(**{"mappings.return_value": rows})
        found = await members.list_memberships(self.connection, self.user_id)
        self.assertEqual([(item.tenant_id, item.role, item.active) for item in found],
                         [(tenant_a, "OWNERMERCHANT", True), (self.tenant_b, None, False)])
        sql, params = self.statement()
        self.assertIn("LEFT OUTER JOIN tenant_members ON tenant_members.tenant_id = "
                      "tenant_memberships.tenant_id AND tenant_members.java_user_id = "
                      "tenant_memberships.java_user_id", sql)
        self.assertIn("WHERE tenant_memberships.user_id =", sql)
        self.assertEqual(list(params.values()), [self.user_id])
        self.assertIn("ORDER BY tenants.name ASC NULLS LAST, tenant_memberships.tenant_id", sql)
        self.assertNotIn("phone", sql)

    async def test_replace_removes_other_tenants_and_upserts_matches(self) -> None:
        matches = [self.match(self.tenant_a, "OWNERMERCHANT"), self.match(self.tenant_b)]
        await members.replace_memberships(self.connection, self.user_id, matches)
        self.assertEqual(self.connection.execute.await_count, 3)
        delete_sql, delete_params = self.statement(0)
        self.assertIn("DELETE FROM tenant_memberships WHERE tenant_memberships.user_id =", delete_sql)
        self.assertIn("tenant_memberships.tenant_id NOT IN", delete_sql)
        self.assertEqual(set(delete_params.values()), {self.user_id, self.tenant_a, self.tenant_b})
        for index, match in ((1, matches[0]), (2, matches[1])):
            sql, params = self.statement(index)
            self.assertIn("INSERT INTO tenant_memberships", sql)
            self.assertIn("ON CONFLICT (tenant_id, user_id) DO UPDATE", sql)
            self.assertIn("verified_at = now()", sql)
            self.assertEqual((params["tenant_id"], params["user_id"], params["role"], params["java_user_id"]),
                             (match.tenant_id, self.user_id, match.role, match.java_user_id))

    async def test_replace_with_no_match_removes_every_membership(self) -> None:
        await members.replace_memberships(self.connection, self.user_id, [])
        sql, params = self.statement()
        self.assertNotIn("NOT IN", sql)
        self.assertEqual(list(params.values()), [self.user_id])
        self.assertEqual(self.connection.execute.await_count, 1)

    async def test_replace_rejects_ambiguous_or_inactive_matches(self) -> None:
        for matches in ([self.match(self.tenant_a), self.match(self.tenant_a)],
                        [self.match(self.tenant_a, status="DISABLED")],
                        [self.match(self.tenant_a, role="SUPERADMIN")]):
            with self.subTest(matches=matches):
                with self.assertRaises(ValueError):
                    await members.replace_memberships(self.connection, self.user_id, matches)
        with self.assertRaisesRegex(ValueError, "CHANNEL_USER_INVALID"):
            await members.replace_memberships(self.connection, "user", [])  # type: ignore[arg-type]
        self.connection.execute.assert_not_awaited()

    async def test_revoke_targets_exact_user_and_tenants(self) -> None:
        await members.revoke_memberships(self.connection, self.user_id, [])
        self.connection.execute.assert_not_awaited()
        await members.revoke_memberships(self.connection, self.user_id, [self.tenant_a])
        sql, params = self.statement()
        self.assertIn("DELETE FROM tenant_memberships", sql)
        self.assertIn("tenant_memberships.user_id =", sql)
        self.assertIn("tenant_memberships.tenant_id IN", sql)
        self.assertEqual(set(params.values()), {self.user_id, self.tenant_a})

    async def test_selection_upsert_read_and_clear(self) -> None:
        await members.select_tenant(self.connection, self.user_id, self.tenant_a)
        sql, params = self.statement()
        self.assertIn("INSERT INTO tenant_selections", sql)
        self.assertIn("ON CONFLICT (user_id) DO UPDATE SET tenant_id = excluded.tenant_id", sql)
        self.assertEqual((params["user_id"], params["tenant_id"]), (self.user_id, self.tenant_a))
        self.connection.scalar.return_value = self.tenant_a
        self.assertEqual(await members.get_selected_tenant(self.connection, self.user_id), self.tenant_a)
        read_sql, read_params = compiled(self.connection.scalar.await_args.args[0])
        self.assertIn("FROM tenant_selections WHERE tenant_selections.user_id =", read_sql)
        self.assertEqual(list(read_params.values()), [self.user_id])
        await members.clear_selected_tenant(self.connection, self.user_id)
        clear_sql, _ = self.statement(1)
        self.assertIn("DELETE FROM tenant_selections WHERE tenant_selections.user_id =", clear_sql)
        with self.assertRaisesRegex(ValueError, "TENANT_SELECTION_INVALID"):
            await members.select_tenant(self.connection, self.user_id, "tenant")  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
