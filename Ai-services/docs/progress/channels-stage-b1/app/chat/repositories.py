"""Tenant- and channel-scoped SQLAlchemy Core operations."""

from datetime import datetime
from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import and_, exists, func, insert, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncConnection
from sqlalchemy.sql import Executable
from sqlalchemy.sql.elements import ColumnElement
from sqlalchemy.sql.selectable import FromClause

from app.chat.entities import (
    ChannelAccount,
    ChannelUser,
    Conversation,
    Message,
    MessageInbox,
    MessageOutbox,
    Tenant,
    TenantMembership,
)

Row = dict[str, Any]
TENANTS = Tenant.__table__
CHANNELS = ChannelAccount.__table__
USERS = ChannelUser.__table__
CONVERSATIONS = Conversation.__table__
INBOX = MessageInbox.__table__
MESSAGES = Message.__table__
OUTBOX = MessageOutbox.__table__
MEMBERSHIPS = TenantMembership.__table__
PLATFORMS = frozenset(("TELEGRAM", "WHATSAPP"))


async def register_telegram_channel(
    connection: AsyncConnection,
    tenant_id: UUID,
    external_bot_id: int,
    username: str | None,
) -> UUID:
    """Register the configured tenant and bot without changing account ownership."""
    if external_bot_id <= 0:
        raise ValueError("TELEGRAM_BOT_ID_INVALID")
    external_account_id = str(external_bot_id)
    await connection.execute(
        pg_insert(TENANTS).values(id=tenant_id).on_conflict_do_nothing(
            index_elements=[TENANTS.c.id]
        )
    )
    await connection.execute(
        pg_insert(CHANNELS).values(
            id=uuid4(),
            tenant_id=tenant_id,
            platform="TELEGRAM",
            external_account_id=external_account_id,
            username=username,
        ).on_conflict_do_nothing(
            index_elements=[CHANNELS.c.platform, CHANNELS.c.external_account_id]
        )
    )
    channel_id = await connection.scalar(
        select(CHANNELS.c.id).where(
            CHANNELS.c.tenant_id == tenant_id,
            CHANNELS.c.platform == "TELEGRAM",
            CHANNELS.c.external_account_id == external_account_id,
        )
    )
    if channel_id is None:
        raise RuntimeError("CHANNEL_ACCOUNT_UNAVAILABLE")
    await connection.execute(
        update(CHANNELS).where(
            CHANNELS.c.id == channel_id,
            CHANNELS.c.tenant_id == tenant_id,
            CHANNELS.c.platform == "TELEGRAM",
            CHANNELS.c.external_account_id == external_account_id,
        ).values(username=username, updated_at=func.now())
    )
    return channel_id


class Repository:
    def __init__(self, tenant_id: UUID, channel_id: UUID, platform: str) -> None:
        if not isinstance(tenant_id, UUID) or not isinstance(channel_id, UUID):
            raise ValueError("REPOSITORY_SCOPE_INVALID")
        if platform not in PLATFORMS:
            raise ValueError("REPOSITORY_PLATFORM_INVALID")
        self.tenant_id = tenant_id
        self.channel_id = channel_id
        self.platform = platform

    def _scope(self, table: FromClause) -> ColumnElement[bool]:
        return and_(
            table.c.tenant_id == self.tenant_id,
            table.c.channel_id == self.channel_id,
            table.c.platform == self.platform,
        )

    def _owner(self, table: FromClause, inbox: Row) -> ColumnElement[bool]:
        return and_(
            self._scope(table),
            table.c.user_id == inbox["user_id"],
            table.c.external_chat_id == inbox["external_chat_id"],
        )

    def _same_owner(
        self, left: FromClause, right: FromClause
    ) -> ColumnElement[bool]:
        return and_(
            left.c.tenant_id == right.c.tenant_id,
            left.c.channel_id == right.c.channel_id,
            left.c.platform == right.c.platform,
            left.c.user_id == right.c.user_id,
            left.c.external_chat_id == right.c.external_chat_id,
        )

    async def _first(
        self, connection: AsyncConnection, statement: Executable
    ) -> Row | None:
        result = await connection.execute(statement)
        mapping = result.mappings().first()
        return dict(mapping) if mapping is not None else None

    async def intake(
        self,
        connection: AsyncConnection,
        user_id: str,
        username: str | None,
        first_name: str | None,
        last_name: str | None,
        display_name: str,
        update_id: str,
        chat_id: str,
        message_id: str,
        kind: str,
        content: str,
    ) -> None:
        user_insert = pg_insert(USERS).values(
            id=uuid4(),
            platform=self.platform,
            external_user_id=user_id,
            username=username,
            first_name=first_name,
            last_name=last_name,
            display_name=display_name,
        )
        user = await self._first(
            connection,
            user_insert.on_conflict_do_update(
                index_elements=[USERS.c.platform, USERS.c.external_user_id],
                set_={
                    "username": user_insert.excluded.username,
                    "first_name": user_insert.excluded.first_name,
                    "last_name": user_insert.excluded.last_name,
                    "display_name": user_insert.excluded.display_name,
                    "updated_at": func.now(),
                    "last_seen_at": func.now(),
                },
            ).returning(USERS.c.id),
        )
        if user is None:
            raise RuntimeError("USER_NOT_RETURNED")
        inbox_insert = pg_insert(INBOX).values(
            id=uuid4(),
            tenant_id=self.tenant_id,
            channel_id=self.channel_id,
            platform=self.platform,
            user_id=user["id"],
            external_chat_id=chat_id,
            external_event_id=update_id,
            external_message_id=message_id,
            kind=kind,
            content=content,
        )
        await connection.execute(
            inbox_insert.on_conflict_do_nothing(
                index_elements=[
                    INBOX.c.tenant_id,
                    INBOX.c.channel_id,
                    INBOX.c.external_event_id,
                ]
            )
        )

    async def session(
        self, connection: AsyncConnection, inbox: Row, reset: bool = False
    ) -> Row:
        if reset:
            await connection.execute(
                update(CONVERSATIONS).where(
                    self._owner(CONVERSATIONS, inbox),
                    CONVERSATIONS.c.status == "ACTIVE",
                ).values(status="CLOSED", closed_at=func.now())
            )
        conversation = await self._first(
            connection,
            select(CONVERSATIONS).where(
                self._owner(CONVERSATIONS, inbox),
                CONVERSATIONS.c.status == "ACTIVE",
            ),
        )
        if conversation is None:
            conversation = await self._first(
                connection,
                insert(CONVERSATIONS).values(
                    id=uuid4(),
                    tenant_id=self.tenant_id,
                    channel_id=self.channel_id,
                    platform=self.platform,
                    user_id=inbox["user_id"],
                    external_chat_id=inbox["external_chat_id"],
                ).returning(CONVERSATIONS),
            )
        if conversation is None:
            raise RuntimeError("CONVERSATION_NOT_RETURNED")
        return conversation

    async def message(
        self,
        connection: AsyncConnection,
        inbox: Row,
        session_id: UUID,
        role: str,
        content: str,
        reply_to: int | None = None,
        model: str | None = None,
        response_id: str | None = None,
    ) -> int:
        sequence = await connection.scalar(
            update(CONVERSATIONS).where(
                self._owner(CONVERSATIONS, inbox),
                CONVERSATIONS.c.id == session_id,
            ).values(
                last_sequence=CONVERSATIONS.c.last_sequence + 1,
                last_message_at=func.now(),
            ).returning(CONVERSATIONS.c.last_sequence)
        )
        if sequence is None:
            raise RuntimeError("CONVERSATION_SCOPE_MISMATCH")
        message_id = await connection.scalar(
            insert(MESSAGES).values(
                tenant_id=self.tenant_id,
                channel_id=self.channel_id,
                platform=self.platform,
                user_id=inbox["user_id"],
                external_chat_id=inbox["external_chat_id"],
                conversation_id=session_id,
                sequence=sequence,
                role=role,
                content=content,
                inbox_id=inbox["id"],
                external_message_id=(
                    inbox["external_message_id"] if role == "USER" else None
                ),
                reply_to_id=reply_to,
                model=model,
                provider_response_id=response_id,
            ).returning(MESSAGES.c.id)
        )
        if message_id is None:
            raise RuntimeError("MESSAGE_NOT_RETURNED")
        return message_id

    async def outbox(
        self,
        connection: AsyncConnection,
        inbox: Row,
        session_id: UUID,
        kind: str,
        chunks: list[str],
        assistant_id: int | None = None,
    ) -> None:
        for number, chunk in enumerate(chunks):
            statement = pg_insert(OUTBOX).values(
                id=uuid4(),
                tenant_id=self.tenant_id,
                channel_id=self.channel_id,
                platform=self.platform,
                user_id=inbox["user_id"],
                external_chat_id=inbox["external_chat_id"],
                conversation_id=session_id,
                inbox_id=inbox["id"],
                assistant_message_id=assistant_id,
                response_kind=kind,
                chunk_number=number,
                content=chunk,
            )
            await connection.execute(
                statement.on_conflict_do_nothing(
                    index_elements=[
                        OUTBOX.c.tenant_id,
                        OUTBOX.c.channel_id,
                        OUTBOX.c.inbox_id,
                        OUTBOX.c.response_kind,
                        OUTBOX.c.chunk_number,
                    ]
                )
            )

    async def recover_sending(self, connection: AsyncConnection) -> None:
        await connection.execute(
            update(OUTBOX).where(
                self._scope(OUTBOX), OUTBOX.c.status == "SENDING"
            ).values(
                status="UNKNOWN",
                error_code="CRASH_DURING_SEND",
                updated_at=func.now(),
            )
        )

    async def suppress_tails_after_unknown(
        self, connection: AsyncConnection
    ) -> None:
        previous = OUTBOX.alias("previous")
        earlier_failure = exists(
            select(previous.c.id).where(
                self._same_owner(previous, OUTBOX),
                previous.c.inbox_id == OUTBOX.c.inbox_id,
                previous.c.response_kind == OUTBOX.c.response_kind,
                previous.c.chunk_number < OUTBOX.c.chunk_number,
                previous.c.status.in_(("UNKNOWN", "FAILED")),
            )
        ).correlate(OUTBOX)
        await connection.execute(
            update(OUTBOX).where(
                self._scope(OUTBOX),
                OUTBOX.c.status == "PENDING",
                earlier_failure,
            ).values(
                status="FAILED",
                error_code="PREVIOUS_CHUNK_UNKNOWN",
                updated_at=func.now(),
            )
        )

    async def processing_inboxes(
        self, connection: AsyncConnection
    ) -> list[Row]:
        result = await connection.execute(
            select(INBOX).where(
                self._scope(INBOX), INBOX.c.status == "PROCESSING"
            ).order_by(INBOX.c.received_order)
        )
        return [dict(mapping) for mapping in result.mappings()]

    async def next_inbox(
        self, connection: AsyncConnection
    ) -> Row | None:
        return await self._first(
            connection,
            select(INBOX).where(
                self._scope(INBOX),
                INBOX.c.status.in_(("PENDING", "RETRY", "READY")),
            ).order_by(INBOX.c.received_order).limit(1),
        )

    async def identity(
        self, connection: AsyncConnection, inbox: Row
    ) -> Row | None:
        return await self._first(
            connection,
            select(USERS).select_from(
                USERS.join(
                    INBOX,
                    and_(
                        USERS.c.platform == INBOX.c.platform,
                        USERS.c.id == INBOX.c.user_id,
                    ),
                )
            ).where(
                self._owner(INBOX, inbox),
                INBOX.c.id == inbox["id"],
                USERS.c.id == inbox["user_id"],
            ),
        )

    async def existing_user_message(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID
    ) -> Row | None:
        return await self._first(
            connection,
            select(MESSAGES.c.id).where(
                self._owner(MESSAGES, inbox),
                MESSAGES.c.conversation_id == session_id,
                MESSAGES.c.inbox_id == inbox["id"],
                MESSAGES.c.role == "USER",
            ),
        )

    async def completed_history(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID
    ) -> list[tuple[str, str]]:
        user = MESSAGES.alias("user_message")
        assistant = MESSAGES.alias("assistant_message")
        completed = INBOX.alias("completed_inbox")
        statement = (
            select(
                user.c.content.label("user_content"),
                assistant.c.content.label("answer"),
            )
            .select_from(
                user.join(
                    assistant,
                    and_(
                        self._same_owner(assistant, user),
                        assistant.c.conversation_id == user.c.conversation_id,
                        assistant.c.reply_to_id == user.c.id,
                        assistant.c.role == "ASSISTANT",
                    ),
                ).join(
                    completed,
                    and_(
                        self._same_owner(completed, user),
                        completed.c.id == user.c.inbox_id,
                        completed.c.conversation_id == user.c.conversation_id,
                    ),
                )
            )
            .where(
                self._owner(user, inbox),
                user.c.conversation_id == session_id,
                user.c.role == "USER",
                completed.c.status == "DONE",
                user.c.inbox_id != inbox["id"],
            )
            .order_by(user.c.sequence.desc())
            .limit(32)
        )
        result = await connection.execute(statement)
        return [
            (mapping["user_content"], mapping["answer"])
            for mapping in reversed(result.mappings().all())
        ]

    async def mark_processing(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(
                status="PROCESSING",
                attempts=INBOX.c.attempts + 1,
                conversation_id=session_id,
                next_attempt_at=None,
                updated_at=func.now(),
            )
        )

    async def mark_retry(
        self, connection: AsyncConnection, inbox: Row, code: str,
        next_attempt: datetime,
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(
                status="RETRY",
                error_code=code,
                next_attempt_at=next_attempt,
                updated_at=func.now(),
            )
        )

    async def mark_ready(
        self, connection: AsyncConnection, inbox: Row,
        error_code: str | None,
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(
                status="READY",
                error_code=error_code,
                updated_at=func.now(),
            )
        )

    async def mark_command_ready(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(
                status="READY",
                conversation_id=session_id,
                updated_at=func.now(),
            )
        )

    async def pending_outbox(
        self, connection: AsyncConnection, inbox: Row
    ) -> Row | None:
        return await self._first(
            connection,
            select(OUTBOX).where(
                self._owner(OUTBOX, inbox),
                OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.status == "PENDING",
            ).order_by(OUTBOX.c.chunk_number).limit(1),
        )

    async def has_failed_outbox(
        self, connection: AsyncConnection, inbox: Row
    ) -> bool:
        failed = await self._first(
            connection,
            select(OUTBOX.c.id).where(
                self._owner(OUTBOX, inbox),
                OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.status.in_(("FAILED", "UNKNOWN")),
            ).limit(1),
        )
        return failed is not None

    async def mark_done(
        self, connection: AsyncConnection, inbox: Row
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(status="DONE", updated_at=func.now())
        )

    async def mark_delivery_failed(
        self, connection: AsyncConnection, inbox: Row
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(status="DELIVERY_FAILED", updated_at=func.now())
        )

    async def mark_sending(
        self, connection: AsyncConnection, inbox: Row, chunk: Row
    ) -> None:
        await connection.execute(
            update(OUTBOX).where(
                self._owner(OUTBOX, inbox),
                OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.id == chunk["id"],
            ).values(
                status="SENDING",
                attempts=OUTBOX.c.attempts + 1,
                updated_at=func.now(),
            )
        )

    async def mark_outbox_result(
        self,
        connection: AsyncConnection,
        inbox: Row,
        chunk: Row,
        status: str,
        code: str | None,
        message_id: str | None,
        next_attempt: datetime | None,
        retry_after: int,
    ) -> None:
        await connection.execute(
            update(OUTBOX).where(
                self._owner(OUTBOX, inbox),
                OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.id == chunk["id"],
            ).values(
                status=status,
                error_code=code,
                external_message_id=message_id,
                next_attempt_at=next_attempt,
                retry_after_seconds=retry_after,
                updated_at=func.now(),
            )
        )

    async def fail_later_chunks(
        self, connection: AsyncConnection, inbox: Row, chunk: Row
    ) -> None:
        await connection.execute(
            update(OUTBOX).where(
                self._owner(OUTBOX, inbox),
                OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.response_kind == chunk["response_kind"],
                OUTBOX.c.chunk_number > chunk["chunk_number"],
                OUTBOX.c.status == "PENDING",
            ).values(
                status="FAILED",
                error_code="PREVIOUS_CHUNK_FAILED",
                updated_at=func.now(),
            )
        )
