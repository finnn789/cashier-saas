"""Initial channel-aware AI Services schema.

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
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(["tenant_id"], ["tenants.id"]),
        sa.CheckConstraint("platform IN ('TELEGRAM','WHATSAPP')"),
        sa.UniqueConstraint("platform", "external_account_id"),
        sa.UniqueConstraint("tenant_id", "platform", "id"),
    )
    op.create_table(
        "channel_users",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("external_user_id", sa.String(128), nullable=False),
        sa.Column("username", sa.String(256), nullable=True),
        sa.Column("first_name", sa.String(256), nullable=True),
        sa.Column("last_name", sa.String(256), nullable=True),
        sa.Column("display_name", sa.String(512), nullable=False),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("last_seen_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.CheckConstraint("platform IN ('TELEGRAM','WHATSAPP')"),
        sa.UniqueConstraint("platform", "external_user_id"),
        sa.UniqueConstraint("platform", "id"),
    )
    op.create_table(
        "conversations",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("tenant_id", UUID, nullable=False),
        sa.Column("channel_id", UUID, nullable=False),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("user_id", UUID, nullable=False),
        sa.Column("external_chat_id", sa.String(128), nullable=False),
        sa.Column("status", sa.String(16), nullable=False, server_default=sa.text("'ACTIVE'")),
        sa.Column("summary", sa.Text(), nullable=True),
        sa.Column("last_sequence", sa.BigInteger(), nullable=False, server_default=sa.text("0")),
        sa.Column("last_message_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("closed_at", TIMESTAMPTZ, nullable=True),
        sa.ForeignKeyConstraint(
            ["tenant_id", "platform", "channel_id"],
            ["channel_accounts.tenant_id", "channel_accounts.platform", "channel_accounts.id"],
        ),
        sa.ForeignKeyConstraint(
            ["platform", "user_id"], ["channel_users.platform", "channel_users.id"]
        ),
        sa.UniqueConstraint(
            "tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "id"
        ),
        sa.CheckConstraint("status IN ('ACTIVE','CLOSED')"),
        sa.CheckConstraint("last_sequence >= 0"),
        sa.CheckConstraint(
            "(status='ACTIVE' AND closed_at IS NULL) OR "
            "(status='CLOSED' AND closed_at IS NOT NULL)"
        ),
    )
    op.create_index(
        "one_active_conversation", "conversations",
        ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id"],
        unique=True,
        postgresql_where=sa.text("status='ACTIVE'"),
    )
    op.create_table(
        "message_inbox",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("tenant_id", UUID, nullable=False),
        sa.Column("channel_id", UUID, nullable=False),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("user_id", UUID, nullable=False),
        sa.Column("external_chat_id", sa.String(128), nullable=False),
        sa.Column("external_event_id", sa.String(512), nullable=False),
        sa.Column("external_message_id", sa.String(512), nullable=False),
        sa.Column("received_order", sa.BigInteger(), sa.Identity(always=True), nullable=False, unique=True),
        sa.Column("kind", sa.String(16), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("status", sa.String(24), nullable=False, server_default=sa.text("'PENDING'")),
        sa.Column("conversation_id", UUID, nullable=True),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("next_attempt_at", TIMESTAMPTZ, nullable=True),
        sa.Column("error_code", sa.String(64), nullable=True),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(
            ["tenant_id", "platform", "channel_id"],
            ["channel_accounts.tenant_id", "channel_accounts.platform", "channel_accounts.id"],
        ),
        sa.ForeignKeyConstraint(
            ["platform", "user_id"], ["channel_users.platform", "channel_users.id"]
        ),
        sa.ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id"],
            ["conversations.tenant_id", "conversations.channel_id", "conversations.platform", "conversations.user_id", "conversations.external_chat_id", "conversations.id"],
        ),
        sa.UniqueConstraint("tenant_id", "channel_id", "external_event_id"),
        sa.UniqueConstraint(
            "tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "id"
        ),
        sa.CheckConstraint("kind IN ('TEXT','START','NEW','SESSION','INVALID','UNSUPPORTED')"),
        sa.CheckConstraint(
            "status IN ('PENDING','PROCESSING','RETRY','READY','DONE','DELIVERY_FAILED')"
        ),
        sa.CheckConstraint("attempts >= 0"),
    )
    op.create_index(
        "inbox_pending", "message_inbox",
        ["tenant_id", "channel_id", "status", "next_attempt_at", "received_order"],
    )
    op.create_table(
        "messages",
        sa.Column("id", sa.BigInteger(), sa.Identity(always=True), primary_key=True),
        sa.Column("tenant_id", UUID, nullable=False),
        sa.Column("channel_id", UUID, nullable=False),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("user_id", UUID, nullable=False),
        sa.Column("external_chat_id", sa.String(128), nullable=False),
        sa.Column("conversation_id", UUID, nullable=False),
        sa.Column("sequence", sa.BigInteger(), nullable=False),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("inbox_id", UUID, nullable=False),
        sa.Column("external_message_id", sa.String(512), nullable=True),
        sa.Column("token_count", sa.Integer(), nullable=True),
        sa.Column("reply_to_id", sa.BigInteger(), nullable=True),
        sa.Column("model", sa.String(256), nullable=True),
        sa.Column("provider_response_id", sa.String(256), nullable=True),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id"],
            ["conversations.tenant_id", "conversations.channel_id", "conversations.platform", "conversations.user_id", "conversations.external_chat_id", "conversations.id"],
        ),
        sa.ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "inbox_id"],
            ["message_inbox.tenant_id", "message_inbox.channel_id", "message_inbox.platform", "message_inbox.user_id", "message_inbox.external_chat_id", "message_inbox.id"],
        ),
        sa.ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id", "reply_to_id"],
            ["messages.tenant_id", "messages.channel_id", "messages.platform", "messages.user_id", "messages.external_chat_id", "messages.conversation_id", "messages.id"],
        ),
        sa.UniqueConstraint("tenant_id", "channel_id", "conversation_id", "sequence"),
        sa.UniqueConstraint(
            "tenant_id", "channel_id", "platform", "user_id", "external_chat_id",
            "conversation_id", "id",
        ),
        sa.CheckConstraint("sequence > 0"),
        sa.CheckConstraint("role IN ('USER','ASSISTANT')"),
        sa.CheckConstraint("token_count IS NULL OR token_count >= 0"),
        sa.CheckConstraint(
            "(role='USER' AND reply_to_id IS NULL AND model IS NULL AND "
            "provider_response_id IS NULL) OR (role='ASSISTANT' AND "
            "reply_to_id IS NOT NULL AND model IS NOT NULL)"
        ),
    )
    op.create_index(
        "conversation_external_message", "messages",
        ["conversation_id", "external_message_id"],
        unique=True,
        postgresql_where=sa.text("external_message_id IS NOT NULL"),
    )
    op.create_index(
        "one_user_per_inbox", "messages", ["tenant_id", "channel_id", "inbox_id"],
        unique=True, postgresql_where=sa.text("role='USER'"),
    )
    op.create_index(
        "one_assistant_per_user", "messages", ["tenant_id", "channel_id", "reply_to_id"],
        unique=True, postgresql_where=sa.text("role='ASSISTANT'"),
    )
    op.create_index(
        "message_history", "messages",
        ["tenant_id", "channel_id", "conversation_id", "sequence"],
    )
    op.create_table(
        "message_outbox",
        sa.Column("id", UUID, primary_key=True),
        sa.Column("tenant_id", UUID, nullable=False),
        sa.Column("channel_id", UUID, nullable=False),
        sa.Column("platform", sa.String(16), nullable=False),
        sa.Column("user_id", UUID, nullable=False),
        sa.Column("external_chat_id", sa.String(128), nullable=False),
        sa.Column("conversation_id", UUID, nullable=False),
        sa.Column("inbox_id", UUID, nullable=False),
        sa.Column("assistant_message_id", sa.BigInteger(), nullable=True),
        sa.Column("response_kind", sa.String(16), nullable=False),
        sa.Column("chunk_number", sa.Integer(), nullable=False),
        sa.Column("content", sa.Text(), nullable=False),
        sa.Column("status", sa.String(16), nullable=False, server_default=sa.text("'PENDING'")),
        sa.Column("external_message_id", sa.String(512), nullable=True),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("next_attempt_at", TIMESTAMPTZ, nullable=True),
        sa.Column("retry_after_seconds", sa.Integer(), nullable=False, server_default=sa.text("0")),
        sa.Column("error_code", sa.String(64), nullable=True),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.Column("updated_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id"],
            ["conversations.tenant_id", "conversations.channel_id", "conversations.platform", "conversations.user_id", "conversations.external_chat_id", "conversations.id"],
        ),
        sa.ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "inbox_id"],
            ["message_inbox.tenant_id", "message_inbox.channel_id", "message_inbox.platform", "message_inbox.user_id", "message_inbox.external_chat_id", "message_inbox.id"],
        ),
        sa.ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id", "assistant_message_id"],
            ["messages.tenant_id", "messages.channel_id", "messages.platform", "messages.user_id", "messages.external_chat_id", "messages.conversation_id", "messages.id"],
        ),
        sa.UniqueConstraint("tenant_id", "channel_id", "inbox_id", "response_kind", "chunk_number"),
        sa.CheckConstraint("response_kind IN ('AI','COMMAND','ERROR')"),
        sa.CheckConstraint("chunk_number >= 0"),
        sa.CheckConstraint("status IN ('PENDING','SENDING','SENT','FAILED','UNKNOWN')"),
        sa.CheckConstraint("attempts >= 0"),
        sa.CheckConstraint("retry_after_seconds >= 0"),
        sa.CheckConstraint(
            "(response_kind='AI' AND assistant_message_id IS NOT NULL) OR "
            "(response_kind IN ('COMMAND','ERROR') AND assistant_message_id IS NULL)"
        ),
        sa.CheckConstraint("status<>'SENT' OR external_message_id IS NOT NULL"),
    )
    op.create_index(
        "outbox_pending", "message_outbox",
        ["tenant_id", "channel_id", "status", "next_attempt_at"],
    )
    op.create_table(
        "tenant_memberships",
        sa.Column("tenant_id", UUID, primary_key=True),
        sa.Column("user_id", UUID, primary_key=True),
        sa.Column("role", sa.String(32), nullable=False),
        sa.Column("verified_at", TIMESTAMPTZ, nullable=False),
        sa.Column("created_at", TIMESTAMPTZ, nullable=False, server_default=sa.text("now()")),
        sa.ForeignKeyConstraint(["tenant_id"], ["tenants.id"]),
        sa.ForeignKeyConstraint(["user_id"], ["channel_users.id"]),
        sa.CheckConstraint("role IN ('OWNERMERCHANT','CASHIER')"),
    )


def downgrade() -> None:
    op.drop_table("tenant_memberships")
    op.drop_index("outbox_pending", table_name="message_outbox")
    op.drop_table("message_outbox")
    op.drop_index("message_history", table_name="messages")
    op.drop_index("one_assistant_per_user", table_name="messages")
    op.drop_index("one_user_per_inbox", table_name="messages")
    op.drop_index("conversation_external_message", table_name="messages")
    op.drop_table("messages")
    op.drop_index("inbox_pending", table_name="message_inbox")
    op.drop_table("message_inbox")
    op.drop_index("one_active_conversation", table_name="conversations")
    op.drop_table("conversations")
    op.drop_table("channel_users")
    op.drop_table("channel_accounts")
    op.drop_table("tenants")
