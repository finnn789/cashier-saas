"""Scoped SQLAlchemy Core operations on the mapped Telegram tables."""

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
    ConversationMessage, ConversationSession, TelegramIdentity,
    TelegramInbox, TelegramOutbox,
)

Row = dict[str, Any]
IDENTITIES = TelegramIdentity.__table__
SESSIONS = ConversationSession.__table__
INBOX = TelegramInbox.__table__
MESSAGES = ConversationMessage.__table__
OUTBOX = TelegramOutbox.__table__


class Repository:
    def __init__(self, scope: str, bot_id: int) -> None:
        self.scope = scope
        self.bot_id = bot_id

    def _scope(self, table: FromClause) -> ColumnElement[bool]:
        return and_(table.c.scope == self.scope, table.c.bot_id == self.bot_id)

    def _owner(self, table: FromClause, inbox: Row) -> ColumnElement[bool]:
        return and_(
            self._scope(table),
            table.c.identity_id == inbox["identity_id"],
            table.c.chat_id == inbox["chat_id"],
        )

    async def _first(self, connection: AsyncConnection, statement: Executable) -> Row | None:
        result = await connection.execute(statement)
        mapping = result.mappings().first()
        return dict(mapping) if mapping is not None else None

    async def intake(
        self, connection: AsyncConnection, user_id: int, username: str | None,
        first_name: str, last_name: str | None, display_name: str,
        update_id: int, chat_id: int, message_id: int, kind: str, content: str,
    ) -> None:
        identity_insert = pg_insert(IDENTITIES).values(
            id=uuid4(), scope=self.scope, bot_id=self.bot_id,
            telegram_user_id=user_id, username=username, first_name=first_name,
            last_name=last_name, display_name=display_name,
        )
        identity = await self._first(
            connection,
            identity_insert.on_conflict_do_update(
                index_elements=[IDENTITIES.c.scope, IDENTITIES.c.bot_id, IDENTITIES.c.telegram_user_id],
                set_={
                    "username": identity_insert.excluded.username,
                    "first_name": identity_insert.excluded.first_name,
                    "last_name": identity_insert.excluded.last_name,
                    "display_name": identity_insert.excluded.display_name,
                    "updated_at": func.now(),
                    "last_seen_at": func.now(),
                },
            ).returning(IDENTITIES.c.id),
        )
        if identity is None:
            raise RuntimeError("IDENTITY_NOT_RETURNED")
        inbox_insert = pg_insert(INBOX).values(
            id=uuid4(), scope=self.scope, bot_id=self.bot_id,
            update_id=update_id, identity_id=identity["id"], chat_id=chat_id,
            message_id=message_id, kind=kind, content=content,
        )
        await connection.execute(
            inbox_insert.on_conflict_do_nothing(
                index_elements=[INBOX.c.scope, INBOX.c.bot_id, INBOX.c.update_id]
            )
        )

    async def session(self, connection: AsyncConnection, inbox: Row, reset: bool = False) -> Row:
        if reset:
            await connection.execute(
                update(SESSIONS).where(
                    self._owner(SESSIONS, inbox), SESSIONS.c.status == "ACTIVE"
                ).values(status="CLOSED", closed_at=func.now())
            )
        session = await self._first(
            connection,
            select(SESSIONS).where(
                self._owner(SESSIONS, inbox), SESSIONS.c.status == "ACTIVE"
            ),
        )
        if session is None:
            session = await self._first(
                connection,
                insert(SESSIONS).values(
                    id=uuid4(), scope=self.scope, bot_id=self.bot_id,
                    identity_id=inbox["identity_id"], chat_id=inbox["chat_id"],
                ).returning(SESSIONS),
            )
        if session is None:
            raise RuntimeError("SESSION_NOT_RETURNED")
        return session

    async def message(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID,
        role: str, content: str, reply_to: UUID | None = None,
        model: str | None = None, response_id: str | None = None,
    ) -> UUID:
        sequence = await connection.scalar(
            update(SESSIONS).where(
                self._owner(SESSIONS, inbox), SESSIONS.c.id == session_id
            ).values(
                last_sequence=SESSIONS.c.last_sequence + 1,
                last_activity_at=func.now(),
            ).returning(SESSIONS.c.last_sequence)
        )
        if sequence is None:
            raise RuntimeError("SESSION_SCOPE_MISMATCH")
        message_id = uuid4()
        await connection.execute(
            insert(MESSAGES).values(
                id=message_id, scope=self.scope, bot_id=self.bot_id,
                identity_id=inbox["identity_id"], chat_id=inbox["chat_id"],
                session_id=session_id, sequence=sequence, role=role,
                content=content, inbox_id=inbox["id"],
                inbound_update_id=inbox["update_id"], reply_to_id=reply_to,
                model=model, provider_response_id=response_id,
            )
        )
        return message_id

    async def outbox(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID,
        kind: str, chunks: list[str], assistant_id: UUID | None = None,
    ) -> None:
        for number, chunk in enumerate(chunks):
            statement = pg_insert(OUTBOX).values(
                id=uuid4(), scope=self.scope, bot_id=self.bot_id,
                identity_id=inbox["identity_id"], chat_id=inbox["chat_id"],
                session_id=session_id, inbox_id=inbox["id"],
                assistant_message_id=assistant_id, response_kind=kind,
                chunk_number=number, content=chunk,
            )
            await connection.execute(
                statement.on_conflict_do_nothing(
                    index_elements=[
                        OUTBOX.c.scope, OUTBOX.c.bot_id, OUTBOX.c.inbox_id,
                        OUTBOX.c.response_kind, OUTBOX.c.chunk_number,
                    ]
                )
            )

    async def recover_sending(self, connection: AsyncConnection) -> None:
        await connection.execute(
            update(OUTBOX).where(
                self._scope(OUTBOX), OUTBOX.c.status == "SENDING"
            ).values(
                status="UNKNOWN", error_code="CRASH_DURING_SEND", updated_at=func.now()
            )
        )

    async def suppress_tails_after_unknown(self, connection: AsyncConnection) -> None:
        previous = OUTBOX.alias("previous")
        earlier_failure = exists(
            select(previous.c.id).where(
                previous.c.scope == OUTBOX.c.scope,
                previous.c.bot_id == OUTBOX.c.bot_id,
                previous.c.inbox_id == OUTBOX.c.inbox_id,
                previous.c.response_kind == OUTBOX.c.response_kind,
                previous.c.chunk_number < OUTBOX.c.chunk_number,
                previous.c.status.in_(("UNKNOWN", "FAILED")),
            )
        ).correlate(OUTBOX)
        await connection.execute(
            update(OUTBOX).where(
                self._scope(OUTBOX), OUTBOX.c.status == "PENDING", earlier_failure
            ).values(
                status="FAILED", error_code="PREVIOUS_CHUNK_UNKNOWN", updated_at=func.now()
            )
        )

    async def processing_inboxes(self, connection: AsyncConnection) -> list[Row]:
        result = await connection.execute(
            select(INBOX).where(
                self._scope(INBOX), INBOX.c.status == "PROCESSING"
            ).order_by(INBOX.c.received_order)
        )
        return [dict(mapping) for mapping in result.mappings()]

    async def next_inbox(self, connection: AsyncConnection) -> Row | None:
        return await self._first(
            connection,
            select(INBOX).where(
                self._scope(INBOX), INBOX.c.status.in_(("PENDING", "RETRY", "READY"))
            ).order_by(INBOX.c.received_order).limit(1),
        )

    async def identity(self, connection: AsyncConnection, inbox: Row) -> Row | None:
        return await self._first(
            connection,
            select(IDENTITIES).where(
                self._scope(IDENTITIES), IDENTITIES.c.id == inbox["identity_id"]
            ),
        )

    async def existing_user_message(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID,
    ) -> Row | None:
        return await self._first(
            connection,
            select(MESSAGES.c.id).where(
                self._owner(MESSAGES, inbox), MESSAGES.c.session_id == session_id,
                MESSAGES.c.inbox_id == inbox["id"], MESSAGES.c.role == "USER",
            ),
        )

    async def completed_history(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID,
    ) -> list[tuple[str, str]]:
        user = MESSAGES.alias("user_message")
        assistant = MESSAGES.alias("assistant_message")
        completed = INBOX.alias("completed_inbox")
        statement = (
            select(user.c.content.label("user_content"), assistant.c.content.label("answer"))
            .select_from(
                user.join(
                    assistant,
                    and_(
                        assistant.c.scope == user.c.scope,
                        assistant.c.bot_id == user.c.bot_id,
                        assistant.c.identity_id == user.c.identity_id,
                        assistant.c.chat_id == user.c.chat_id,
                        assistant.c.session_id == user.c.session_id,
                        assistant.c.reply_to_id == user.c.id,
                        assistant.c.role == "ASSISTANT",
                    ),
                ).join(
                    completed,
                    and_(
                        completed.c.scope == user.c.scope,
                        completed.c.bot_id == user.c.bot_id,
                        completed.c.identity_id == user.c.identity_id,
                        completed.c.chat_id == user.c.chat_id,
                        completed.c.id == user.c.inbox_id,
                    ),
                )
            )
            .where(
                self._owner(user, inbox), user.c.session_id == session_id,
                user.c.role == "USER", completed.c.status == "DONE",
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
        self, connection: AsyncConnection, inbox: Row, session_id: UUID,
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(
                status="PROCESSING", attempts=INBOX.c.attempts + 1,
                session_id=session_id, next_attempt_at=None, updated_at=func.now(),
            )
        )

    async def mark_retry(
        self, connection: AsyncConnection, inbox: Row, code: str, next_attempt: datetime,
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(
                status="RETRY", error_code=code,
                next_attempt_at=next_attempt, updated_at=func.now(),
            )
        )

    async def mark_ready(
        self, connection: AsyncConnection, inbox: Row, error_code: str | None,
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(status="READY", error_code=error_code, updated_at=func.now())
        )

    async def mark_command_ready(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID,
    ) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(status="READY", session_id=session_id, updated_at=func.now())
        )

    async def pending_outbox(self, connection: AsyncConnection, inbox: Row) -> Row | None:
        return await self._first(
            connection,
            select(OUTBOX).where(
                self._owner(OUTBOX, inbox), OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.status == "PENDING",
            ).order_by(OUTBOX.c.chunk_number).limit(1),
        )

    async def has_failed_outbox(self, connection: AsyncConnection, inbox: Row) -> bool:
        failed = await self._first(
            connection,
            select(OUTBOX.c.id).where(
                self._owner(OUTBOX, inbox), OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.status.in_(("FAILED", "UNKNOWN")),
            ).limit(1),
        )
        return failed is not None

    async def mark_done(self, connection: AsyncConnection, inbox: Row) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(status="DONE", updated_at=func.now())
        )

    async def mark_delivery_failed(self, connection: AsyncConnection, inbox: Row) -> None:
        await connection.execute(
            update(INBOX).where(
                self._owner(INBOX, inbox), INBOX.c.id == inbox["id"]
            ).values(status="DELIVERY_FAILED", updated_at=func.now())
        )

    async def mark_sending(self, connection: AsyncConnection, inbox: Row, chunk: Row) -> None:
        await connection.execute(
            update(OUTBOX).where(
                self._owner(OUTBOX, inbox), OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.id == chunk["id"],
            ).values(
                status="SENDING", attempts=OUTBOX.c.attempts + 1,
                updated_at=func.now(),
            )
        )

    async def mark_outbox_result(
        self, connection: AsyncConnection, inbox: Row, chunk: Row,
        status: str, code: str | None, message_id: int | None,
        next_attempt: datetime | None, retry_after: int,
    ) -> None:
        await connection.execute(
            update(OUTBOX).where(
                self._owner(OUTBOX, inbox), OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.id == chunk["id"],
            ).values(
                status=status, error_code=code, telegram_message_id=message_id,
                next_attempt_at=next_attempt, retry_after_seconds=retry_after,
                updated_at=func.now(),
            )
        )

    async def fail_later_chunks(
        self, connection: AsyncConnection, inbox: Row, chunk: Row,
    ) -> None:
        await connection.execute(
            update(OUTBOX).where(
                self._owner(OUTBOX, inbox), OUTBOX.c.inbox_id == inbox["id"],
                OUTBOX.c.response_kind == chunk["response_kind"],
                OUTBOX.c.chunk_number > chunk["chunk_number"],
                OUTBOX.c.status == "PENDING",
            ).values(
                status="FAILED", error_code="PREVIOUS_CHUNK_FAILED",
                updated_at=func.now(),
            )
