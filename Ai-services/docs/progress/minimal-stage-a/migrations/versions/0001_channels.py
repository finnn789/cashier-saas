"""Initial minimal channel-aware AI Services schema.

Revision ID: 0001_channels
Revises: none
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "0001_channels"
down_revision = None
branch_labels = None
depends_on = None

UUID = postgresql.UUID(as_uuid=True)
TIMESTAMPTZ = sa.DateTime(timezone=True)


def upgrade() -> None:
    op.create_table(
        "tenants",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
    )
    op.create_table(
        "channel_accounts",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("tenant_id", UUID, nullable=False),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("external_account_id", sa.String(128), nullable=False),
        sa.Column("username", sa.String(256), nullable=True),
        sa.ForeignKeyConstraint(["tenant_id"], ["tenants.id"]),
        sa.CheckConstraint("platform IN ('TELEGRAM','WHATSAPP')"),
        sa.UniqueConstraint("platform", "external_account_id"),
        sa.UniqueConstraint("platform", "id"),
    )
    op.create_table(
        "channel_users",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("external_user_id", sa.String(128), nullable=False),
        sa.Column("username", sa.String(256), nullable=True),
        sa.Column("first_name", sa.String(256), nullable=True),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.CheckConstraint("platform IN ('TELEGRAM','WHATSAPP')"),
        sa.UniqueConstraint("platform", "external_user_id"),
        sa.UniqueConstraint("platform", "id"),
    )
    op.create_table(
        "conversations",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("channel_id", UUID, nullable=False),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("user_id", UUID, nullable=False),
        sa.Column("external_chat_id", sa.String(128), nullable=False),
        sa.Column("status", sa.String(16), nullable=False, server_default=sa.text("'ACTIVE'")),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("last_message_at", TIMESTAMPTZ, nullable=True),
        sa.ForeignKeyConstraint(
            ["platform", "channel_id"],
            ["channel_accounts.platform", "channel_accounts.id"],
        ),
        sa.ForeignKeyConstraint(
            ["platform", "user_id"], ["channel_users.platform", "channel_users.id"]
        ),
        sa.CheckConstraint("status IN ('ACTIVE','CLOSED')"),
    )
    op.create_index(
        "one_active_conversation", "conversations",
        ["channel_id", "platform", "user_id", "external_chat_id"],
        unique=True,
        postgresql_where=sa.text("status='ACTIVE'"),
    )
    op.create_table(
        "messages",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=True), primary_key=True),
        sa.Column("conversation_id", UUID, nullable=False),
        sa.Column("external_message_id", sa.String(512), nullable=True),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(["conversation_id"], ["conversations.id"]),
        sa.CheckConstraint("role IN ('USER','ASSISTANT')"),
    )
    op.create_index(
        "conversation_external_message", "messages",
        ["conversation_id", "external_message_id"],
        unique=True,
        postgresql_where=sa.text("external_message_id IS NOT NULL"),
    )
    op.create_index("message_history", "messages", ["conversation_id", "id"])
    op.create_table(
        "tenant_memberships",
        sa.Column("tenant_id", UUID, primary_key=True),
        sa.Column("user_id", UUID, primary_key=True),
        sa.Column("role", sa.String(32), nullable=False),
        sa.Column("verified_at", TIMESTAMPTZ, nullable=False),
        sa.ForeignKeyConstraint(["tenant_id"], ["tenants.id"]),
        sa.ForeignKeyConstraint(["user_id"], ["channel_users.id"]),
        sa.CheckConstraint("role IN ('OWNERMERCHANT','CASHIER')"),
    )


def downgrade() -> None:
    op.drop_table("tenant_memberships")
    op.drop_index("message_history", table_name="messages")
    op.drop_index("conversation_external_message", table_name="messages")
    op.drop_table("messages")
    op.drop_index("one_active_conversation", table_name="conversations")
    op.drop_table("conversations")
    op.drop_table("channel_users")
    op.drop_table("channel_accounts")
    op.drop_table("tenants")
