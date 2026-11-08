"""Staff members pushed by the Java backend (SQLAlchemy Core, tenant-scoped).

The phone number is personal data: it is stored only here, never returned by the
lookups below as part of a repr, and never logged.
"""

from dataclasses import dataclass, field
from uuid import UUID

from sqlalchemy import RowMapping, and_, delete, func, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncConnection

from app.chat.entities import Tenant, TenantMember, TenantMembership, TenantSelection

TENANTS = Tenant.__table__
MEMBERS = TenantMember.__table__
MEMBERSHIPS = TenantMembership.__table__
SELECTIONS = TenantSelection.__table__
ROLES = frozenset(("OWNERMERCHANT", "CASHIER"))
STATUSES = frozenset(("ACTIVE", "DISABLED"))


@dataclass(frozen=True)
class MemberMatch:
    """A member row joined with its tenant name; carries no phone number."""

    tenant_id: UUID
    java_user_id: UUID
    tenant_name: str | None
    role: str
    status: str
    full_name: str = field(repr=False)


def _match(row: RowMapping) -> MemberMatch:
    return MemberMatch(
        tenant_id=row["tenant_id"],
        java_user_id=row["java_user_id"],
        tenant_name=row["tenant_name"],
        role=row["role"],
        status=row["status"],
        full_name=row["full_name"],
    )


async def upsert_tenant(connection: AsyncConnection, tenant_id: UUID, name: str | None) -> None:
    """Create the tenant row when absent; a given name overwrites, a missing one keeps it."""
    if not isinstance(tenant_id, UUID):
        raise ValueError("TENANT_ID_INVALID")
    insert = pg_insert(TENANTS).values(id=tenant_id, name=name)
    await connection.execute(
        insert.on_conflict_do_update(
            index_elements=[TENANTS.c.id],
            set_={"name": func.coalesce(insert.excluded.name, TENANTS.c.name)},
        )
    )


async def upsert_member(
    connection: AsyncConnection,
    tenant_id: UUID,
    java_user_id: UUID,
    full_name: str,
    phone_normalized: str,
    role: str,
    status: str = "ACTIVE",
    tenant_name: str | None = None,
) -> None:
    """Idempotently store one staff member, creating the tenant row on demand."""
    if not isinstance(tenant_id, UUID) or not isinstance(java_user_id, UUID):
        raise ValueError("MEMBER_ID_INVALID")
    if role not in ROLES or status not in STATUSES:
        raise ValueError("MEMBER_ROLE_OR_STATUS_INVALID")
    if not full_name or not phone_normalized:
        raise ValueError("MEMBER_FIELDS_INVALID")
    await upsert_tenant(connection, tenant_id, tenant_name)
    insert = pg_insert(MEMBERS).values(
        tenant_id=tenant_id,
        java_user_id=java_user_id,
        full_name=full_name,
        phone_normalized=phone_normalized,
        role=role,
        status=status,
    )
    await connection.execute(
        insert.on_conflict_do_update(
            index_elements=[MEMBERS.c.tenant_id, MEMBERS.c.java_user_id],
            set_={
                "full_name": insert.excluded.full_name,
                "phone_normalized": insert.excluded.phone_normalized,
                "role": insert.excluded.role,
                "status": insert.excluded.status,
                "updated_at": func.now(),
            },
        )
    )


async def find_active_members_by_phone(
    connection: AsyncConnection, phone_normalized: str
) -> list[MemberMatch]:
    """All ACTIVE members with this phone across tenants, in a stable order."""
    if not phone_normalized:
        return []
    result = await connection.execute(
        select(
            MEMBERS.c.tenant_id,
            MEMBERS.c.java_user_id,
            MEMBERS.c.full_name,
            MEMBERS.c.role,
            MEMBERS.c.status,
            TENANTS.c.name.label("tenant_name"),
        )
        .join(TENANTS, TENANTS.c.id == MEMBERS.c.tenant_id)
        .where(MEMBERS.c.phone_normalized == phone_normalized, MEMBERS.c.status == "ACTIVE")
        .order_by(TENANTS.c.name.asc().nulls_last(), MEMBERS.c.tenant_id, MEMBERS.c.java_user_id)
    )
    return [_match(row) for row in result.mappings()]


async def get_member(
    connection: AsyncConnection, tenant_id: UUID, java_user_id: UUID
) -> MemberMatch | None:
    """One member of exactly this tenant (any status), or None."""
    result = await connection.execute(
        select(
            MEMBERS.c.tenant_id,
            MEMBERS.c.java_user_id,
            MEMBERS.c.full_name,
            MEMBERS.c.role,
            MEMBERS.c.status,
            TENANTS.c.name.label("tenant_name"),
        )
        .join(TENANTS, TENANTS.c.id == MEMBERS.c.tenant_id)
        .where(MEMBERS.c.tenant_id == tenant_id, MEMBERS.c.java_user_id == java_user_id)
    )
    row = result.mappings().first()
    if row is None:
        return None
    return _match(row)


async def disable_member(
    connection: AsyncConnection, tenant_id: UUID, java_user_id: UUID
) -> bool:
    """Mark exactly this tenant's member DISABLED; True when a row was changed."""
    result = await connection.execute(
        update(MEMBERS)
        .where(MEMBERS.c.tenant_id == tenant_id, MEMBERS.c.java_user_id == java_user_id)
        .values(status="DISABLED", updated_at=func.now())
    )
    return result.rowcount > 0


# --- Verified Telegram memberships (revision 0003) -------------------------------------
# A membership links one channel user to one tenant after the user shared their OWN
# Telegram contact and its phone matched an ACTIVE member. It records which Java
# member was proven, so each message can re-check that this member is still ACTIVE.


@dataclass(frozen=True)
class VerifiedMembership:
    """A channel user's membership joined with the CURRENT state of its member row."""

    tenant_id: UUID
    tenant_name: str | None
    java_user_id: UUID | None
    role: str | None  # the member row's current role; None when the member row is gone
    active: bool  # member row exists and is ACTIVE


async def list_memberships(
    connection: AsyncConnection, user_id: UUID
) -> list[VerifiedMembership]:
    """All memberships of exactly this channel user, ordered like the tenant list shown."""
    if not isinstance(user_id, UUID):
        raise ValueError("CHANNEL_USER_INVALID")
    result = await connection.execute(
        select(
            MEMBERSHIPS.c.tenant_id,
            MEMBERSHIPS.c.java_user_id,
            TENANTS.c.name.label("tenant_name"),
            MEMBERS.c.role.label("member_role"),
            MEMBERS.c.status.label("member_status"),
        )
        .select_from(
            MEMBERSHIPS.join(TENANTS, TENANTS.c.id == MEMBERSHIPS.c.tenant_id).outerjoin(
                MEMBERS,
                and_(
                    MEMBERS.c.tenant_id == MEMBERSHIPS.c.tenant_id,
                    MEMBERS.c.java_user_id == MEMBERSHIPS.c.java_user_id,
                ),
            )
        )
        .where(MEMBERSHIPS.c.user_id == user_id)
        .order_by(TENANTS.c.name.asc().nulls_last(), MEMBERSHIPS.c.tenant_id)
    )
    return [
        VerifiedMembership(
            tenant_id=row["tenant_id"],
            tenant_name=row["tenant_name"],
            java_user_id=row["java_user_id"],
            role=row["member_role"],
            active=row["member_status"] == "ACTIVE",
        )
        for row in result.mappings()
    ]


async def replace_memberships(
    connection: AsyncConnection, user_id: UUID, matches: list[MemberMatch]
) -> None:
    """Make the user's memberships exactly `matches` (one per tenant), verified now.

    Call only after the contact check. Memberships of tenants not in `matches` are
    removed (their tenant choice goes with them through the ON DELETE CASCADE).
    """
    if not isinstance(user_id, UUID):
        raise ValueError("CHANNEL_USER_INVALID")
    tenant_ids = [match.tenant_id for match in matches]
    if len(set(tenant_ids)) != len(tenant_ids):
        raise ValueError("MEMBERSHIP_TENANT_DUPLICATED")
    if any(match.status != "ACTIVE" or match.role not in ROLES for match in matches):
        raise ValueError("MEMBERSHIP_MEMBER_INVALID")
    stale = delete(MEMBERSHIPS).where(MEMBERSHIPS.c.user_id == user_id)
    if tenant_ids:
        stale = stale.where(MEMBERSHIPS.c.tenant_id.not_in(tenant_ids))
    await connection.execute(stale)
    for match in matches:
        insert = pg_insert(MEMBERSHIPS).values(
            tenant_id=match.tenant_id,
            user_id=user_id,
            role=match.role,
            java_user_id=match.java_user_id,
            verified_at=func.now(),
        )
        await connection.execute(
            insert.on_conflict_do_update(
                index_elements=[MEMBERSHIPS.c.tenant_id, MEMBERSHIPS.c.user_id],
                set_={
                    "role": insert.excluded.role,
                    "java_user_id": insert.excluded.java_user_id,
                    "verified_at": func.now(),
                },
            )
        )


async def revoke_memberships(
    connection: AsyncConnection, user_id: UUID, tenant_ids: list[UUID]
) -> None:
    """Drop exactly these memberships of this user (and, by cascade, a choice of them)."""
    if not isinstance(user_id, UUID):
        raise ValueError("CHANNEL_USER_INVALID")
    if not tenant_ids:
        return
    await connection.execute(
        delete(MEMBERSHIPS).where(
            MEMBERSHIPS.c.user_id == user_id, MEMBERSHIPS.c.tenant_id.in_(tenant_ids)
        )
    )


async def get_selected_tenant(connection: AsyncConnection, user_id: UUID) -> UUID | None:
    """The tenant this user chose, or None."""
    if not isinstance(user_id, UUID):
        raise ValueError("CHANNEL_USER_INVALID")
    return await connection.scalar(
        select(SELECTIONS.c.tenant_id).where(SELECTIONS.c.user_id == user_id)
    )


async def select_tenant(connection: AsyncConnection, user_id: UUID, tenant_id: UUID) -> None:
    """Store the user's choice; the FK to tenant_memberships rejects an unverified tenant."""
    if not isinstance(user_id, UUID) or not isinstance(tenant_id, UUID):
        raise ValueError("TENANT_SELECTION_INVALID")
    insert = pg_insert(SELECTIONS).values(
        user_id=user_id, tenant_id=tenant_id, selected_at=func.now()
    )
    await connection.execute(
        insert.on_conflict_do_update(
            index_elements=[SELECTIONS.c.user_id],
            set_={"tenant_id": insert.excluded.tenant_id, "selected_at": func.now()},
        )
    )


async def clear_selected_tenant(connection: AsyncConnection, user_id: UUID) -> None:
    if not isinstance(user_id, UUID):
        raise ValueError("CHANNEL_USER_INVALID")
    await connection.execute(delete(SELECTIONS).where(SELECTIONS.c.user_id == user_id))
