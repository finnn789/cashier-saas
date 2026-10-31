"""Minimal channel-aware persistence mappings for AI Services."""

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    DateTime,
    ForeignKeyConstraint,
    Identity,
    Index,
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
        UniqueConstraint("platform", "id"),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    tenant_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    platform: Mapped[str] = mapped_column(String(16), nullable=False)
    external_account_id: Mapped[str] = mapped_column(String(128), nullable=False)
    username: Mapped[str | None] = mapped_column(String(256), nullable=True)


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
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class Conversation(Base):
    __tablename__ = "conversations"
    __table_args__ = (
        ForeignKeyConstraint(
            ["platform", "channel_id"],
            ["channel_accounts.platform", "channel_accounts.id"],
        ),
        ForeignKeyConstraint(
            ["platform", "user_id"], ["channel_users.platform", "channel_users.id"]
        ),
        CheckConstraint("status IN ('ACTIVE','CLOSED')"),
        Index(
            "one_active_conversation",
            "channel_id", "platform", "user_id", "external_chat_id",
            unique=True,
            postgresql_where=text("status='ACTIVE'"),
        ),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    channel_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    platform: Mapped[str] = mapped_column(String(16), nullable=False)
    user_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    external_chat_id: Mapped[str] = mapped_column(String(128), nullable=False)
    status: Mapped[str] = mapped_column(
        String(16), nullable=False, server_default=text("'ACTIVE'")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    last_message_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )


class Message(Base):
    __tablename__ = "messages"
    __table_args__ = (
        ForeignKeyConstraint(["conversation_id"], ["conversations.id"]),
        CheckConstraint("role IN ('USER','ASSISTANT')"),
        Index(
            "conversation_external_message",
            "conversation_id", "external_message_id",
            unique=True,
            postgresql_where=text("external_message_id IS NOT NULL"),
        ),
        Index("message_history", "conversation_id", "id"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=True), primary_key=True)
    conversation_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    external_message_id: Mapped[str | None] = mapped_column(String(512), nullable=True)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class TenantMembership(Base):
    __tablename__ = "tenant_memberships"
    __table_args__ = (
        ForeignKeyConstraint(["tenant_id"], ["tenants.id"]),
        ForeignKeyConstraint(["user_id"], ["channel_users.id"]),
        CheckConstraint("role IN ('OWNERMERCHANT','CASHIER')"),
    )

    tenant_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    user_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    role: Mapped[str] = mapped_column(String(32), nullable=False)
    verified_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
