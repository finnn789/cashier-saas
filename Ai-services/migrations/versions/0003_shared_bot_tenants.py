"""One shared Telegram bot for many tenants: tenant-scoped conversations,
verified memberships linked to the Java staff member, and a per-user tenant choice.

Revision ID: 0003_shared_bot_tenants
Revises: 0002_tenant_members

Upgrade keeps every existing row: each conversation inherits the tenant of the
channel it was created on, then the channel stops being bound to a tenant.
Downgrade restores the channel tenant from its conversations and refuses (raises,
transaction rolled back) when a channel holds conversations of more than one
tenant, because the 0002 schema cannot represent that without mixing tenants.
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0003_shared_bot_tenants"
down_revision = "0002_tenant_members"
branch_labels = None
depends_on = None

UUID = postgresql.UUID(as_uuid=True)
TIMESTAMPTZ = sa.DateTime(timezone=True)

OLD_ACTIVE_COLUMNS = ["channel_id", "platform", "user_id", "external_chat_id"]
NEW_ACTIVE_COLUMNS = ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id"]

DOWNGRADE_GUARD = """
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM conversations c
        JOIN channel_accounts ca ON ca.id = c.channel_id AND ca.platform = c.platform
        GROUP BY ca.id
        HAVING count(DISTINCT c.tenant_id) > 1
            OR bool_or(ca.tenant_id IS NOT NULL AND ca.tenant_id <> c.tenant_id)
    ) THEN
        RAISE EXCEPTION 'downgrade 0003 refused: a channel holds conversations of several tenants';
    END IF;
END
$$;
"""


def upgrade() -> None:
    # Conversations become the tenant boundary (backfilled from their channel).
    op.add_column("conversations", sa.Column("tenant_id", UUID, nullable=True))
    op.execute(
        "UPDATE conversations SET tenant_id = channel_accounts.tenant_id "
        "FROM channel_accounts "
        "WHERE channel_accounts.id = conversations.channel_id "
        "AND channel_accounts.platform = conversations.platform"
    )
    op.alter_column("conversations", "tenant_id", existing_type=UUID, nullable=False)
    op.create_foreign_key(
        "conversation_tenant", "conversations", "tenants", ["tenant_id"], ["id"]
    )
    op.drop_index("one_active_conversation", table_name="conversations")
    op.create_index(
        "one_active_conversation", "conversations", NEW_ACTIVE_COLUMNS,
        unique=True,
        postgresql_where=sa.text("status='ACTIVE'"),
    )

    # The bot is shared: channel accounts are no longer bound to one tenant.
    op.alter_column("channel_accounts", "tenant_id", existing_type=UUID, nullable=True)
    op.execute("UPDATE channel_accounts SET tenant_id = NULL")

    # A verified membership remembers the Java staff member it was proven against,
    # so every message can re-check that this member is still ACTIVE.
    op.add_column("tenant_memberships", sa.Column("java_user_id", UUID, nullable=True))
    op.create_foreign_key(
        "tenant_membership_member", "tenant_memberships", "tenant_members",
        ["tenant_id", "java_user_id"], ["tenant_id", "java_user_id"],
        ondelete="CASCADE",
    )
    op.create_index("tenant_membership_user", "tenant_memberships", ["user_id"])

    # The tenant a user chose; only a verified membership can be chosen.
    op.create_table(
        "tenant_selections",
        sa.Column("user_id", UUID, primary_key=True),
        sa.Column("tenant_id", UUID, nullable=False),
        sa.Column("selected_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(["user_id"], ["channel_users.id"]),
        sa.ForeignKeyConstraint(
            ["tenant_id", "user_id"],
            ["tenant_memberships.tenant_id", "tenant_memberships.user_id"],
            ondelete="CASCADE",
        ),
    )


def downgrade() -> None:
    op.drop_table("tenant_selections")
    op.drop_index("tenant_membership_user", table_name="tenant_memberships")
    op.drop_constraint("tenant_membership_member", "tenant_memberships", type_="foreignkey")
    op.drop_column("tenant_memberships", "java_user_id")

    op.execute(DOWNGRADE_GUARD)
    op.execute(
        "UPDATE channel_accounts SET tenant_id = ("
        "SELECT c.tenant_id FROM conversations c "
        "WHERE c.channel_id = channel_accounts.id AND c.platform = channel_accounts.platform "
        "LIMIT 1) "
        "WHERE tenant_id IS NULL"
    )
    # A shared channel without conversations has no tenant to return to; the 0002
    # service re-registers its bot at startup, so only this registration row goes.
    op.execute("DELETE FROM channel_accounts WHERE tenant_id IS NULL")
    op.alter_column("channel_accounts", "tenant_id", existing_type=UUID, nullable=False)

    op.drop_index("one_active_conversation", table_name="conversations")
    op.create_index(
        "one_active_conversation", "conversations", OLD_ACTIVE_COLUMNS,
        unique=True,
        postgresql_where=sa.text("status='ACTIVE'"),
    )
    op.drop_constraint("conversation_tenant", "conversations", type_="foreignkey")
    op.drop_column("conversations", "tenant_id")
