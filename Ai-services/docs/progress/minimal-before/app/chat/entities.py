"""Channel-aware persistence mappings for the initial AI Services schema."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKeyConstraint,
    Identity,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import UUID as PostgreSQLUUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class Tenant(Base):
    __tablename__ = "tenants"

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class ChannelAccount(Base):
    __tablename__ = "channel_accounts"
    __table_args__ = (
        ForeignKeyConstraint(["tenant_id"], ["tenants.id"]),
        CheckConstraint("platform IN ('TELEGRAM','WHATSAPP')"),
        UniqueConstraint("platform", "external_account_id"),
        UniqueConstraint("tenant_id", "platform", "id"),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    tenant_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    platform: Mapped[str] = mapped_column(String(16), nullable=False)
    external_account_id: Mapped[str] = mapped_column(String(128), nullable=False)
    username: Mapped[str | None] = mapped_column(String(256), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class ChannelUser(Base):
    __tablename__ = "channel_users"
    __table_args__ = (
        CheckConstraint("platform IN ('TELEGRAM','WHATSAPP')"),
        UniqueConstraint("platform", "external_user_id"),
        UniqueConstraint("platform", "id"),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    platform: Mapped[str] = mapped_column(String(16), nullable=False)
    external_user_id: Mapped[str] = mapped_column(String(128), nullable=False)
    username: Mapped[str | None] = mapped_column(String(256), nullable=True)
    first_name: Mapped[str | None] = mapped_column(String(256), nullable=True)
    last_name: Mapped[str | None] = mapped_column(String(256), nullable=True)
    display_name: Mapped[str] = mapped_column(String(512), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    last_seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class Conversation(Base):
    __tablename__ = "conversations"
    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "platform", "channel_id"],
            ["channel_accounts.tenant_id", "channel_accounts.platform", "channel_accounts.id"],
        ),
        ForeignKeyConstraint(
            ["platform", "user_id"], ["channel_users.platform", "channel_users.id"]
        ),
        UniqueConstraint(
            "tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "id"
        ),
        CheckConstraint("status IN ('ACTIVE','CLOSED')"),
        CheckConstraint("last_sequence >= 0"),
        CheckConstraint(
            "(status='ACTIVE' AND closed_at IS NULL) OR "
            "(status='CLOSED' AND closed_at IS NOT NULL)"
        ),
        Index(
            "one_active_conversation",
            "tenant_id", "channel_id", "platform", "user_id", "external_chat_id",
            unique=True,
            postgresql_where=text("status='ACTIVE'"),
        ),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    tenant_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    channel_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    platform: Mapped[str] = mapped_column(String(16), nullable=False)
    user_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    external_chat_id: Mapped[str] = mapped_column(String(128), nullable=False)
    status: Mapped[str] = mapped_column(
        String(16), nullable=False, server_default=text("'ACTIVE'")
    )
    summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    last_sequence: Mapped[int] = mapped_column(
        BigInteger, nullable=False, server_default=text("0")
    )
    last_message_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class MessageInbox(Base):
    __tablename__ = "message_inbox"
    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "platform", "channel_id"],
            ["channel_accounts.tenant_id", "channel_accounts.platform", "channel_accounts.id"],
        ),
        ForeignKeyConstraint(
            ["platform", "user_id"], ["channel_users.platform", "channel_users.id"]
        ),
        ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id"],
            ["conversations.tenant_id", "conversations.channel_id", "conversations.platform", "conversations.user_id", "conversations.external_chat_id", "conversations.id"],
        ),
        UniqueConstraint("tenant_id", "channel_id", "external_event_id"),
        UniqueConstraint(
            "tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "id"
        ),
        CheckConstraint("kind IN ('TEXT','START','NEW','SESSION','INVALID','UNSUPPORTED')"),
        CheckConstraint(
            "status IN ('PENDING','PROCESSING','RETRY','READY','DONE','DELIVERY_FAILED')"
        ),
        CheckConstraint("attempts >= 0"),
        Index(
            "inbox_pending", "tenant_id", "channel_id", "status", "next_attempt_at",
            "received_order",
        ),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    tenant_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    channel_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    platform: Mapped[str] = mapped_column(String(16), nullable=False)
    user_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    external_chat_id: Mapped[str] = mapped_column(String(128), nullable=False)
    external_event_id: Mapped[str] = mapped_column(String(512), nullable=False)
    external_message_id: Mapped[str] = mapped_column(String(512), nullable=False)
    received_order: Mapped[int] = mapped_column(
        BigInteger, Identity(always=True), nullable=False, unique=True
    )
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(
        String(24), nullable=False, server_default=text("'PENDING'")
    )
    conversation_id: Mapped[UUID | None] = mapped_column(
        PostgreSQLUUID(as_uuid=True), nullable=True
    )
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    next_attempt_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class Message(Base):
    __tablename__ = "messages"
    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id"],
            ["conversations.tenant_id", "conversations.channel_id", "conversations.platform", "conversations.user_id", "conversations.external_chat_id", "conversations.id"],
        ),
        ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "inbox_id"],
            ["message_inbox.tenant_id", "message_inbox.channel_id", "message_inbox.platform", "message_inbox.user_id", "message_inbox.external_chat_id", "message_inbox.id"],
        ),
        ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id", "reply_to_id"],
            ["messages.tenant_id", "messages.channel_id", "messages.platform", "messages.user_id", "messages.external_chat_id", "messages.conversation_id", "messages.id"],
        ),
        UniqueConstraint("tenant_id", "channel_id", "conversation_id", "sequence"),
        UniqueConstraint(
            "tenant_id", "channel_id", "platform", "user_id", "external_chat_id",
            "conversation_id", "id",
        ),
        CheckConstraint("sequence > 0"),
        CheckConstraint("role IN ('USER','ASSISTANT')"),
        CheckConstraint("token_count IS NULL OR token_count >= 0"),
        CheckConstraint(
            "(role='USER' AND reply_to_id IS NULL AND model IS NULL AND "
            "provider_response_id IS NULL) OR (role='ASSISTANT' AND "
            "reply_to_id IS NOT NULL AND model IS NOT NULL)"
        ),
        Index(
            "conversation_external_message", "conversation_id", "external_message_id",
            unique=True,
            postgresql_where=text("external_message_id IS NOT NULL"),
        ),
        Index(
            "one_user_per_inbox", "tenant_id", "channel_id", "inbox_id",
            unique=True,
            postgresql_where=text("role='USER'"),
        ),
        Index(
            "one_assistant_per_user", "tenant_id", "channel_id", "reply_to_id",
            unique=True,
            postgresql_where=text("role='ASSISTANT'"),
        ),
        Index("message_history", "tenant_id", "channel_id", "conversation_id", "sequence"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=True), primary_key=True)
    tenant_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    channel_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    platform: Mapped[str] = mapped_column(String(16), nullable=False)
    user_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    external_chat_id: Mapped[str] = mapped_column(String(128), nullable=False)
    conversation_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    sequence: Mapped[int] = mapped_column(BigInteger, nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    inbox_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    external_message_id: Mapped[str | None] = mapped_column(String(512), nullable=True)
    token_count: Mapped[int | None] = mapped_column(Integer, nullable=True)
    reply_to_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    model: Mapped[str | None] = mapped_column(String(256), nullable=True)
    provider_response_id: Mapped[str | None] = mapped_column(String(256), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class MessageOutbox(Base):
    __tablename__ = "message_outbox"
    __table_args__ = (
        ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id"],
            ["conversations.tenant_id", "conversations.channel_id", "conversations.platform", "conversations.user_id", "conversations.external_chat_id", "conversations.id"],
        ),
        ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "inbox_id"],
            ["message_inbox.tenant_id", "message_inbox.channel_id", "message_inbox.platform", "message_inbox.user_id", "message_inbox.external_chat_id", "message_inbox.id"],
        ),
        ForeignKeyConstraint(
            ["tenant_id", "channel_id", "platform", "user_id", "external_chat_id", "conversation_id", "assistant_message_id"],
            ["messages.tenant_id", "messages.channel_id", "messages.platform", "messages.user_id", "messages.external_chat_id", "messages.conversation_id", "messages.id"],
        ),
        UniqueConstraint("tenant_id", "channel_id", "inbox_id", "response_kind", "chunk_number"),
        CheckConstraint("response_kind IN ('AI','COMMAND','ERROR')"),
        CheckConstraint("chunk_number >= 0"),
        CheckConstraint("status IN ('PENDING','SENDING','SENT','FAILED','UNKNOWN')"),
        CheckConstraint("attempts >= 0"),
        CheckConstraint("retry_after_seconds >= 0"),
        CheckConstraint(
            "(response_kind='AI' AND assistant_message_id IS NOT NULL) OR "
            "(response_kind IN ('COMMAND','ERROR') AND assistant_message_id IS NULL)"
        ),
        CheckConstraint("status<>'SENT' OR external_message_id IS NOT NULL"),
        Index("outbox_pending", "tenant_id", "channel_id", "status", "next_attempt_at"),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    tenant_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    channel_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    platform: Mapped[str] = mapped_column(String(16), nullable=False)
    user_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    external_chat_id: Mapped[str] = mapped_column(String(128), nullable=False)
    conversation_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    inbox_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    assistant_message_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    response_kind: Mapped[str] = mapped_column(String(16), nullable=False)
    chunk_number: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(
        String(16), nullable=False, server_default=text("'PENDING'")
    )
    external_message_id: Mapped[str | None] = mapped_column(String(512), nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    next_attempt_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    retry_after_seconds: Mapped[int] = mapped_column(
        Integer, nullable=False, server_default=text("0")
    )
    error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class TenantMembership(Base):
    __tablename__ = "tenant_memberships"
    __table_args__ = (
        ForeignKeyConstraint(["tenant_id"], ["tenants.id"]),
        ForeignKeyConstraint(["user_id"], ["channel_users.id"]),
        CheckConstraint("role IN ('OWNERMERCHANT','CASHIER')"),
    )

    tenant_id: Mapped[UUID] = mapped_column(
        PostgreSQLUUID(as_uuid=True), primary_key=True
    )
    user_id: Mapped[UUID] = mapped_column(
        PostgreSQLUUID(as_uuid=True), primary_key=True
    )
    role: Mapped[str] = mapped_column(String(32), nullable=False)
    verified_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
