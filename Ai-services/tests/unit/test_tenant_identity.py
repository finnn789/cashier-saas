"""ChatService identity use cases (TASK-059): contact verification, 0/1/many tenant
matches, tenant switching, per-message re-check with DISABLED revocation, and the
AI_TENANT_ID development fallback. The membership store is an in-memory fake that
replaces the `app.chat.members` functions; every call must happen inside one
transaction on the channel-scoped repository (no tenant is known yet)."""

import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, Mock, patch
from uuid import UUID, uuid4

from sqlalchemy.ext.asyncio import AsyncConnection

import app.chat.service as service_module
from app.chat.members import MemberMatch, VerifiedMembership
from app.chat.repositories import ChannelRepository
from app.chat.service import (
    CHOOSE,
    FALLBACK,
    INVALID_CHOICE,
    LISTED,
    NOT_REGISTERED,
    REVOKED,
    UNVERIFIED,
    VERIFIED,
    ChatService,
    Identity,
)
from app.core.database import Database

PHONE = "6281234567890"


class FakeMembers:
    """In-memory stand-in for the members.py functions ChatService uses."""

    def __init__(self, test: "IdentityTests") -> None:
        self.test = test
        # (tenant_id, java_user_id) -> {"phone", "role", "status", "tenant_name"}
        self.staff: dict[tuple[UUID, UUID], dict[str, str | None]] = {}
        # user_id -> {tenant_id: java_user_id}
        self.memberships: dict[UUID, dict[UUID, UUID]] = {}
        self.selection: dict[UUID, UUID] = {}
        self.calls: list[str] = []

    def add_staff(self, tenant_id: UUID, name: str | None, role: str = "CASHIER",
                  phone: str = PHONE, status: str = "ACTIVE") -> UUID:
        java_user_id = uuid4()
        self.staff[(tenant_id, java_user_id)] = {
            "phone": phone, "role": role, "status": status, "tenant_name": name,
        }
        return java_user_id

    def set_status(self, tenant_id: UUID, status: str) -> None:
        for (tenant, _), row in self.staff.items():
            if tenant == tenant_id:
                row["status"] = status

    def _check(self, name: str, connection: object) -> None:
        self.test.assertTrue(self.test.in_transaction, f"{name} outside a transaction")
        self.test.assertIs(connection, self.test.connection)
        self.calls.append(name)

    def _ordered(self, tenant_ids: list[UUID]) -> list[UUID]:
        def name_of(tenant_id: UUID) -> str | None:
            return next(
                row["tenant_name"] for (tenant, _), row in self.staff.items() if tenant == tenant_id
            )
        return sorted(tenant_ids, key=lambda t: (name_of(t) is None, name_of(t) or "", str(t)))

    async def find_active_members_by_phone(self, connection: object, phone: str) -> list[MemberMatch]:
        self._check("find", connection)
        found = [
            MemberMatch(tenant, java, row["tenant_name"], str(row["role"]), "ACTIVE", "Nama Staf")
            for (tenant, java), row in self.staff.items()
            if row["phone"] == phone and row["status"] == "ACTIVE"
        ]
        order = self._ordered(list({item.tenant_id for item in found}))
        return sorted(found, key=lambda item: (order.index(item.tenant_id), str(item.java_user_id)))

    async def replace_memberships(self, connection: object, user_id: UUID,
                                  matches: list[MemberMatch]) -> None:
        self._check("replace", connection)
        self.memberships[user_id] = {item.tenant_id: item.java_user_id for item in matches}
        if self.selection.get(user_id) not in self.memberships[user_id]:
            self.selection.pop(user_id, None)  # ON DELETE CASCADE

    async def list_memberships(self, connection: object, user_id: UUID) -> list[VerifiedMembership]:
        self._check("list", connection)
        result = []
        for tenant_id in self._ordered(list(self.memberships.get(user_id, {}))):
            java_user_id = self.memberships[user_id][tenant_id]
            row = self.staff.get((tenant_id, java_user_id))
            result.append(VerifiedMembership(
                tenant_id=tenant_id,
                tenant_name=row["tenant_name"] if row else None,
                java_user_id=java_user_id,
                role=str(row["role"]) if row else None,
                active=bool(row and row["status"] == "ACTIVE"),
            ))
        return result

    async def revoke_memberships(self, connection: object, user_id: UUID,
                                 tenant_ids: list[UUID]) -> None:
        self._check("revoke", connection)
        for tenant_id in tenant_ids:
            self.memberships.get(user_id, {}).pop(tenant_id, None)
            if self.selection.get(user_id) == tenant_id:
                self.selection.pop(user_id)  # ON DELETE CASCADE

    async def get_selected_tenant(self, connection: object, user_id: UUID) -> UUID | None:
        self._check("get_selected", connection)
        return self.selection.get(user_id)

    async def select_tenant(self, connection: object, user_id: UUID, tenant_id: UUID) -> None:
        self._check("select", connection)
        # Mirrors the FK to tenant_memberships: only a verified tenant can be chosen.
        self.test.assertIn(tenant_id, self.memberships.get(user_id, {}))
        self.selection[user_id] = tenant_id

    async def clear_selected_tenant(self, connection: object, user_id: UUID) -> None:
        self._check("clear", connection)
        self.selection.pop(user_id, None)


class IdentityTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.connection = Mock(spec=AsyncConnection)
        self.in_transaction = False
        self.transactions = 0
        self.database = Mock(spec=Database)
        self.database.transaction = Mock(side_effect=self.transaction)
        self.user_id = uuid4()
        self.channels = Mock(spec=ChannelRepository)
        self.channels.upsert_user = AsyncMock(side_effect=self.upsert_user)
        self.channels.for_tenant = Mock(side_effect=AssertionError("no tenant view for identity"))
        self.fake = FakeMembers(self)
        patcher = patch.multiple(
            service_module.members,
            **{
                name: AsyncMock(side_effect=getattr(self.fake, name))
                for name in (
                    "find_active_members_by_phone", "replace_memberships", "list_memberships",
                    "revoke_memberships", "get_selected_tenant", "select_tenant",
                    "clear_selected_tenant",
                )
            },
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        self.tenant_a = uuid4()
        self.tenant_b = uuid4()
        self.profile = {"external_user_id": "7", "username": "handle", "first_name": "First"}

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[Mock]:
        self.assertFalse(self.in_transaction, "nested transaction")
        self.in_transaction = True
        self.transactions += 1
        try:
            yield self.connection
        finally:
            self.in_transaction = False

    async def upsert_user(self, connection: object, external_user_id: str,
                          username: str | None, first_name: str | None) -> UUID:
        self.assertTrue(self.in_transaction)
        self.assertEqual((external_user_id, username, first_name), ("7", "handle", "First"))
        return self.user_id

    def service(self, fallback: UUID | None = None) -> ChatService:
        return ChatService(self.database, self.channels, fallback_tenant_id=fallback)

    async def verify(self, service: ChatService, phone: str = PHONE) -> Identity:
        return await service.verify_contact(**self.profile, phone_normalized=phone)

    # --- contact verification: 0 / 1 / many ---------------------------------------------

    async def test_unknown_phone_is_not_registered_and_binds_nothing(self) -> None:
        self.fake.add_staff(self.tenant_a, "Kopi Senja", phone="6289999999999")
        identity = await self.verify(self.service())
        self.assertEqual(identity, Identity(NOT_REGISTERED))
        self.assertEqual(self.fake.memberships[self.user_id], {})
        self.assertNotIn(self.user_id, self.fake.selection)
        self.assertEqual(self.transactions, 1)

    async def test_one_match_verifies_and_selects_that_tenant(self) -> None:
        java_user = self.fake.add_staff(self.tenant_a, "Kopi Senja", role="OWNERMERCHANT")
        identity = await self.verify(self.service())
        self.assertEqual(identity.status, VERIFIED)
        self.assertEqual((identity.tenant_id, identity.role), (self.tenant_a, "OWNERMERCHANT"))
        self.assertEqual([option.name for option in identity.options], ["Kopi Senja"])
        self.assertEqual(self.fake.memberships[self.user_id], {self.tenant_a: java_user})
        self.assertEqual(self.fake.selection[self.user_id], self.tenant_a)
        self.assertEqual(self.transactions, 1)

    async def test_many_matches_require_an_explicit_choice(self) -> None:
        self.fake.add_staff(self.tenant_a, "Warung Biru")
        self.fake.add_staff(self.tenant_b, "Kopi Senja", role="OWNERMERCHANT")
        self.fake.selection[self.user_id] = self.tenant_a  # stale choice must not be reused
        self.fake.memberships[self.user_id] = {self.tenant_a: uuid4()}
        identity = await self.verify(self.service())
        self.assertEqual(identity.status, CHOOSE)
        self.assertIsNone(identity.tenant_id)
        self.assertEqual([option.name for option in identity.options], ["Kopi Senja", "Warung Biru"])
        self.assertEqual(set(self.fake.memberships[self.user_id]), {self.tenant_a, self.tenant_b})
        self.assertNotIn(self.user_id, self.fake.selection)
        # never guess: messages now ask to choose
        follow_up = await self.service().identify(**self.profile)
        self.assertEqual(follow_up.status, CHOOSE)
        self.assertFalse(follow_up.can_chat)

    async def test_two_staff_rows_in_one_tenant_keep_the_least_privileged(self) -> None:
        self.fake.add_staff(self.tenant_a, "Kopi Senja", role="OWNERMERCHANT")
        cashier = self.fake.add_staff(self.tenant_a, "Kopi Senja", role="CASHIER")
        identity = await self.verify(self.service())
        self.assertEqual((identity.status, identity.role), (VERIFIED, "CASHIER"))
        self.assertEqual(self.fake.memberships[self.user_id], {self.tenant_a: cashier})

    async def test_reverification_drops_tenants_that_no_longer_match(self) -> None:
        self.fake.add_staff(self.tenant_a, "Kopi Senja")
        self.fake.add_staff(self.tenant_b, "Warung Biru", phone="6280000000000")
        self.fake.memberships[self.user_id] = {self.tenant_b: uuid4()}
        self.fake.selection[self.user_id] = self.tenant_b
        identity = await self.verify(self.service())
        self.assertEqual((identity.status, identity.tenant_id), (VERIFIED, self.tenant_a))
        self.assertEqual(set(self.fake.memberships[self.user_id]), {self.tenant_a})

    async def test_disabled_member_is_not_matched(self) -> None:
        self.fake.add_staff(self.tenant_a, "Kopi Senja", status="DISABLED")
        self.assertEqual((await self.verify(self.service())).status, NOT_REGISTERED)

    async def test_phone_must_be_normalized_digits(self) -> None:
        for phone in ("", "+6281234567890", "0812 3456 7890"):
            with self.subTest(phone=phone):
                with self.assertRaisesRegex(ValueError, "PHONE_INVALID"):
                    await self.verify(self.service(), phone)
        self.assertEqual(self.transactions, 0)

    # --- per-message resolution ----------------------------------------------------------

    async def test_unverified_user_cannot_chat(self) -> None:
        identity = await self.service().identify(**self.profile)
        self.assertEqual(identity, Identity(UNVERIFIED))
        self.assertFalse(identity.can_chat)
        self.assertNotIn("select", self.fake.calls)

    async def test_selected_tenant_and_current_role_are_resolved_each_message(self) -> None:
        self.fake.add_staff(self.tenant_a, "Kopi Senja")
        self.fake.add_staff(self.tenant_b, "Warung Biru")
        await self.verify(self.service())
        service = self.service()
        await service.choose_tenant(**self.profile, choice=2)
        first = await service.identify(**self.profile)
        self.assertEqual((first.status, first.tenant_id, first.role), (VERIFIED, self.tenant_b, "CASHIER"))
        # The member row changes between messages; the next message sees it.
        for row in self.fake.staff.values():
            row["role"] = "OWNERMERCHANT"
        second = await service.identify(**self.profile)
        self.assertEqual((second.tenant_id, second.role), (self.tenant_b, "OWNERMERCHANT"))
        self.assertEqual(self.fake.calls.count("list"), 3)  # re-checked on every message

    async def test_single_remaining_membership_is_selected_automatically(self) -> None:
        java_user = self.fake.add_staff(self.tenant_a, "Kopi Senja")
        self.fake.memberships[self.user_id] = {self.tenant_a: java_user}
        identity = await self.service().identify(**self.profile)
        self.assertEqual((identity.status, identity.tenant_id), (VERIFIED, self.tenant_a))
        self.assertEqual(self.fake.selection[self.user_id], self.tenant_a)

    async def test_disabled_member_is_revoked_and_must_reverify(self) -> None:
        self.fake.add_staff(self.tenant_a, "Kopi Senja")
        service = self.service()
        self.assertEqual((await self.verify(service)).status, VERIFIED)
        self.fake.set_status(self.tenant_a, "DISABLED")
        identity = await service.identify(**self.profile)
        self.assertEqual(identity.status, REVOKED)
        self.assertFalse(identity.can_chat)
        self.assertIsNone(identity.tenant_id)
        self.assertEqual(self.fake.memberships[self.user_id], {})
        self.assertNotIn(self.user_id, self.fake.selection)
        self.assertIn("revoke", self.fake.calls)

    async def test_disabled_selected_tenant_does_not_fall_through_to_another(self) -> None:
        self.fake.add_staff(self.tenant_a, "Kopi Senja")
        self.fake.add_staff(self.tenant_b, "Warung Biru")
        service = self.service()
        await self.verify(service)
        await service.choose_tenant(**self.profile, choice=1)  # Kopi Senja = tenant_a
        self.fake.set_status(self.tenant_a, "DISABLED")
        identity = await service.identify(**self.profile)
        self.assertEqual(identity.status, REVOKED)
        self.assertIsNone(identity.tenant_id)
        self.assertEqual([option.tenant_id for option in identity.options], [self.tenant_b])
        self.assertEqual(set(self.fake.memberships[self.user_id]), {self.tenant_b})

    async def test_missing_member_row_counts_as_revoked(self) -> None:
        self.fake.memberships[self.user_id] = {self.tenant_a: uuid4()}  # no staff row
        self.fake.selection[self.user_id] = self.tenant_a
        self.fake.staff[(self.tenant_a, uuid4())] = {
            "phone": "x", "role": "CASHIER", "status": "ACTIVE", "tenant_name": "Kopi Senja",
        }
        identity = await self.service().identify(**self.profile)
        self.assertEqual(identity.status, REVOKED)

    # --- tenant switching ----------------------------------------------------------------

    async def test_tenant_switch_lists_selects_and_rejects_bad_choices(self) -> None:
        self.fake.add_staff(self.tenant_a, "Warung Biru")
        self.fake.add_staff(self.tenant_b, "Kopi Senja")
        service = self.service()
        await self.verify(service)
        listed = await service.choose_tenant(**self.profile, choice=None)
        self.assertEqual((listed.status, listed.tenant_id), (LISTED, None))
        self.assertEqual([option.name for option in listed.options], ["Kopi Senja", "Warung Biru"])
        chosen = await service.choose_tenant(**self.profile, choice=2)
        self.assertEqual((chosen.status, chosen.tenant_id), (VERIFIED, self.tenant_a))
        self.assertEqual(self.fake.selection[self.user_id], self.tenant_a)
        listed = await service.choose_tenant(**self.profile, choice=None)
        self.assertEqual((listed.status, listed.tenant_id), (LISTED, self.tenant_a))
        for bad in (0, 3, 999):
            with self.subTest(choice=bad):
                result = await service.choose_tenant(**self.profile, choice=bad)
                self.assertEqual(result.status, INVALID_CHOICE)
                self.assertEqual(self.fake.selection[self.user_id], self.tenant_a)
        switched = await service.choose_tenant(**self.profile, choice=1)
        self.assertEqual(switched.tenant_id, self.tenant_b)
        self.assertEqual((await service.identify(**self.profile)).tenant_id, self.tenant_b)

    async def test_tenant_command_without_verification(self) -> None:
        self.assertEqual(
            (await self.service().choose_tenant(**self.profile, choice=1)).status, UNVERIFIED
        )
        self.assertNotIn("select", self.fake.calls)
        java_user = self.fake.add_staff(self.tenant_a, "Kopi Senja", status="DISABLED")
        self.fake.memberships[self.user_id] = {self.tenant_a: java_user}
        self.assertEqual(
            (await self.service().choose_tenant(**self.profile, choice=1)).status, REVOKED
        )
        self.assertEqual(self.fake.memberships[self.user_id], {})

    # --- AI_TENANT_ID development fallback --------------------------------------------------

    async def test_fallback_applies_only_to_users_without_any_membership(self) -> None:
        fallback = uuid4()
        identity = await self.service(fallback).identify(**self.profile)
        self.assertEqual((identity.status, identity.tenant_id, identity.role), (FALLBACK, fallback, None))
        self.assertTrue(identity.can_chat)

    async def test_fallback_never_overrides_a_verified_tenant(self) -> None:
        fallback = uuid4()
        self.fake.add_staff(self.tenant_a, "Kopi Senja")
        service = self.service(fallback)
        await self.verify(service)
        identity = await service.identify(**self.profile)
        self.assertEqual((identity.status, identity.tenant_id), (VERIFIED, self.tenant_a))

    async def test_fallback_is_not_used_while_a_choice_is_pending_or_after_revocation(self) -> None:
        fallback = uuid4()
        self.fake.add_staff(self.tenant_a, "Kopi Senja")
        self.fake.add_staff(self.tenant_b, "Warung Biru")
        service = self.service(fallback)
        await self.verify(service)
        self.assertEqual((await service.identify(**self.profile)).status, CHOOSE)
        await service.choose_tenant(**self.profile, choice=1)
        self.fake.set_status(self.tenant_a, "DISABLED")
        self.fake.set_status(self.tenant_b, "DISABLED")
        revoked = await service.identify(**self.profile)
        self.assertEqual((revoked.status, revoked.tenant_id), (REVOKED, None))
        # Documented dev behavior: with no membership left, later messages use the fallback.
        later = await service.identify(**self.profile)
        self.assertEqual((later.status, later.tenant_id), (FALLBACK, fallback))

    def test_fallback_must_be_a_uuid(self) -> None:
        with self.assertRaisesRegex(ValueError, "FALLBACK_TENANT_INVALID"):
            ChatService(self.database, self.channels, fallback_tenant_id="tenant")  # type: ignore[arg-type]


if __name__ == "__main__":
    unittest.main()
