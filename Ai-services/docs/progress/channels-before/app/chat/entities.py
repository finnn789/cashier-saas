"""Mappings for the five tables created by migration 0001_telegram.

These classes describe the existing schema. Importing them does not create tables.
"""

from datetime import datetime
from uuid import UUID

from sqlalchemy import (
    BigInteger, CheckConstraint, DateTime, ForeignKeyConstraint, Identity,
    Index, Integer, String, Text, UniqueConstraint, func, text,
)
from sqlalchemy.dialects.postgresql import UUID as PostgreSQLUUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class TelegramIdentity(Base):
    __tablename__ = "telegram_identities"
    __table_args__ = (
        CheckConstraint("bot_id > 0"),
        CheckConstraint("telegram_user_id > 0"),
        UniqueConstraint("scope", "bot_id", "telegram_user_id"),
        UniqueConstraint("scope", "bot_id", "id"),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    scope: Mapped[str] = mapped_column(String(128), nullable=False)
    bot_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    telegram_user_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    username: Mapped[str | None] = mapped_column(Text, nullable=True)
    first_name: Mapped[str] = mapped_column(Text, nullable=False)
    last_name: Mapped[str | None] = mapped_column(Text, nullable=True)
    display_name: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    last_seen_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class ConversationSession(Base):
    __tablename__ = "conversation_sessions"
    __table_args__ = (
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id"],
            ["telegram_identities.scope", "telegram_identities.bot_id", "telegram_identities.id"],
        ),
        UniqueConstraint("scope", "bot_id", "identity_id", "chat_id", "id"),
        CheckConstraint("status IN ('ACTIVE','CLOSED')"),
        CheckConstraint("last_sequence >= 0"),
        CheckConstraint("(status='ACTIVE' AND closed_at IS NULL) OR (status='CLOSED' AND closed_at IS NOT NULL)"),
        Index(
            "one_active_session", "scope", "bot_id", "identity_id", "chat_id",
            unique=True, postgresql_where=text("status='ACTIVE'"),
        ),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    scope: Mapped[str] = mapped_column(String(128), nullable=False)
    bot_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    identity_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    chat_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default=text("'ACTIVE'"))
    last_sequence: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=text("0"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    last_activity_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    closed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class TelegramInbox(Base):
    __tablename__ = "telegram_inbox"
    __table_args__ = (
        UniqueConstraint("scope", "bot_id", "update_id"),
        UniqueConstraint("scope", "bot_id", "identity_id", "chat_id", "id"),
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id"],
            ["telegram_identities.scope", "telegram_identities.bot_id", "telegram_identities.id"],
        ),
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id", "chat_id", "session_id"],
            ["conversation_sessions.scope", "conversation_sessions.bot_id", "conversation_sessions.identity_id", "conversation_sessions.chat_id", "conversation_sessions.id"],
        ),
        CheckConstraint("kind IN ('TEXT','START','NEW','SESSION','INVALID','UNSUPPORTED')"),
        CheckConstraint("status IN ('PENDING','PROCESSING','RETRY','READY','DONE','DELIVERY_FAILED')"),
        CheckConstraint("attempts >= 0"),
        Index("inbox_pending", "scope", "bot_id", "status", "next_attempt_at", "received_order"),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    scope: Mapped[str] = mapped_column(String(128), nullable=False)
    bot_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    update_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    identity_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    chat_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    message_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    received_order: Mapped[int] = mapped_column(BigInteger, Identity(always=True), nullable=False, unique=True)
    kind: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False, server_default=text("'PENDING'"))
    session_id: Mapped[UUID | None] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    next_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class ConversationMessage(Base):
    __tablename__ = "conversation_messages"
    __table_args__ = (
        UniqueConstraint("scope", "bot_id", "session_id", "sequence"),
        UniqueConstraint("scope", "bot_id", "identity_id", "chat_id", "session_id", "id"),
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id", "chat_id", "session_id"],
            ["conversation_sessions.scope", "conversation_sessions.bot_id", "conversation_sessions.identity_id", "conversation_sessions.chat_id", "conversation_sessions.id"],
        ),
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id", "chat_id", "inbox_id"],
            ["telegram_inbox.scope", "telegram_inbox.bot_id", "telegram_inbox.identity_id", "telegram_inbox.chat_id", "telegram_inbox.id"],
        ),
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id", "chat_id", "session_id", "reply_to_id"],
            ["conversation_messages.scope", "conversation_messages.bot_id", "conversation_messages.identity_id", "conversation_messages.chat_id", "conversation_messages.session_id", "conversation_messages.id"],
        ),
        CheckConstraint("sequence > 0"),
        CheckConstraint("role IN ('USER','ASSISTANT')"),
        CheckConstraint("(role='USER' AND reply_to_id IS NULL AND model IS NULL AND provider_response_id IS NULL) OR (role='ASSISTANT' AND reply_to_id IS NOT NULL AND model IS NOT NULL)"),
        Index("one_user_per_inbox", "scope", "bot_id", "inbox_id", unique=True, postgresql_where=text("role='USER'")),
        Index("one_assistant_per_user", "scope", "bot_id", "reply_to_id", unique=True, postgresql_where=text("role='ASSISTANT'")),
        Index("message_history", "scope", "bot_id", "session_id", "sequence"),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    scope: Mapped[str] = mapped_column(String(128), nullable=False)
    bot_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    identity_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    chat_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    session_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    sequence: Mapped[int] = mapped_column(BigInteger, nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    inbox_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    inbound_update_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    reply_to_id: Mapped[UUID | None] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=True)
    model: Mapped[str | None] = mapped_column(String(256), nullable=True)
    provider_response_id: Mapped[str | None] = mapped_column(String(256), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class TelegramOutbox(Base):
    __tablename__ = "telegram_outbox"
    __table_args__ = (
        UniqueConstraint("scope", "bot_id", "inbox_id", "response_kind", "chunk_number"),
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id", "chat_id", "session_id"],
            ["conversation_sessions.scope", "conversation_sessions.bot_id", "conversation_sessions.identity_id", "conversation_sessions.chat_id", "conversation_sessions.id"],
        ),
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id", "chat_id", "inbox_id"],
            ["telegram_inbox.scope", "telegram_inbox.bot_id", "telegram_inbox.identity_id", "telegram_inbox.chat_id", "telegram_inbox.id"],
        ),
        ForeignKeyConstraint(
            ["scope", "bot_id", "identity_id", "chat_id", "session_id", "assistant_message_id"],
            ["conversation_messages.scope", "conversation_messages.bot_id", "conversation_messages.identity_id", "conversation_messages.chat_id", "conversation_messages.session_id", "conversation_messages.id"],
        ),
        CheckConstraint("response_kind IN ('AI','COMMAND','ERROR')"),
        CheckConstraint("chunk_number >= 0"),
        CheckConstraint("status IN ('PENDING','SENDING','SENT','FAILED','UNKNOWN')"),
        CheckConstraint("attempts >= 0"),
        CheckConstraint("retry_after_seconds >= 0"),
        CheckConstraint("(response_kind='AI' AND assistant_message_id IS NOT NULL) OR (response_kind IN ('COMMAND','ERROR') AND assistant_message_id IS NULL)"),
        CheckConstraint("status<>'SENT' OR telegram_message_id IS NOT NULL"),
        Index("outbox_pending", "scope", "bot_id", "status", "next_attempt_at"),
    )

    id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), primary_key=True)
    scope: Mapped[str] = mapped_column(String(128), nullable=False)
    bot_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    identity_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    chat_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    session_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    inbox_id: Mapped[UUID] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=False)
    assistant_message_id: Mapped[UUID | None] = mapped_column(PostgreSQLUUID(as_uuid=True), nullable=True)
    response_kind: Mapped[str] = mapped_column(String(16), nullable=False)
    chunk_number: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default=text("'PENDING'"))
    telegram_message_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    next_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    retry_after_seconds: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
