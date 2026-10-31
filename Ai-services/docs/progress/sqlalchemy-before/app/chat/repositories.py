"""Scoped SQLAlchemy Core queries over the migrated PostgreSQL schema."""

from typing import Any
from uuid import UUID, uuid4

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncConnection

Row = dict[str, Any]


class Repository:
    def __init__(self, scope: str, bot_id: int) -> None:
        self.scope = scope
        self.bot_id = bot_id

    def parameters(self, **values: Any) -> dict[str, Any]:
        return {"scope": self.scope, "bot": self.bot_id, **values}

    async def one(self, connection: AsyncConnection, sql: str, **values: Any) -> Row | None:
        result = await connection.execute(text(sql), self.parameters(**values))
        row = result.mappings().first()
        if row is None:
            return None
        return dict(row)

    async def execute(self, connection: AsyncConnection, sql: str, **values: Any) -> None:
        await connection.execute(text(sql), self.parameters(**values))

    async def intake(
        self, connection: AsyncConnection, user_id: int, username: str | None,
        first_name: str, last_name: str | None, display_name: str,
        update_id: int, chat_id: int, message_id: int, kind: str, content: str,
    ) -> None:
        identity = await self.one(connection, """
            INSERT INTO telegram_identities
              (id, scope, bot_id, telegram_user_id, username, first_name, last_name, display_name)
            VALUES (:id, :scope, :bot, :user, :username, :first, :last, :display)
            ON CONFLICT (scope, bot_id, telegram_user_id) DO UPDATE SET
              username=EXCLUDED.username, first_name=EXCLUDED.first_name,
              last_name=EXCLUDED.last_name, display_name=EXCLUDED.display_name,
              updated_at=now(), last_seen_at=now()
            RETURNING id
        """, id=uuid4(), user=user_id, username=username, first=first_name,
            last=last_name, display=display_name)
        if identity is None:
            raise RuntimeError("IDENTITY_NOT_RETURNED")
        await self.execute(connection, """
            INSERT INTO telegram_inbox
              (id, scope, bot_id, update_id, identity_id, chat_id, message_id, kind, content)
            VALUES (:id, :scope, :bot, :update, :identity, :chat, :message, :kind, :content)
            ON CONFLICT (scope, bot_id, update_id) DO NOTHING
        """, id=uuid4(), update=update_id, identity=identity["id"], chat=chat_id,
            message=message_id, kind=kind, content=content)

    async def session(self, connection: AsyncConnection, inbox: Row, reset: bool = False) -> Row:
        if reset:
            await self.execute(connection, """
                UPDATE conversation_sessions SET status='CLOSED', closed_at=now()
                WHERE scope=:scope AND bot_id=:bot AND identity_id=:identity
                  AND chat_id=:chat AND status='ACTIVE'
            """, identity=inbox["identity_id"], chat=inbox["chat_id"])
        session = await self.one(connection, """
            SELECT * FROM conversation_sessions WHERE scope=:scope AND bot_id=:bot
              AND identity_id=:identity AND chat_id=:chat AND status='ACTIVE'
        """, identity=inbox["identity_id"], chat=inbox["chat_id"])
        if session is None:
            session = await self.one(connection, """
                INSERT INTO conversation_sessions (id, scope, bot_id, identity_id, chat_id)
                VALUES (:id, :scope, :bot, :identity, :chat) RETURNING *
            """, id=uuid4(), identity=inbox["identity_id"], chat=inbox["chat_id"])
        if session is None:
            raise RuntimeError("SESSION_NOT_RETURNED")
        return session

    async def message(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID,
        role: str, content: str, reply_to: UUID | None = None,
        model: str | None = None, response_id: str | None = None,
    ) -> UUID:
        sequence = await connection.scalar(text("""
            UPDATE conversation_sessions SET last_sequence=last_sequence+1, last_activity_at=now()
            WHERE scope=:scope AND bot_id=:bot AND id=:session AND identity_id=:identity
              AND chat_id=:chat RETURNING last_sequence
        """), self.parameters(session=session_id, identity=inbox["identity_id"], chat=inbox["chat_id"]))
        if sequence is None:
            raise RuntimeError("SESSION_SCOPE_MISMATCH")
        message_id = uuid4()
        await self.execute(connection, """
            INSERT INTO conversation_messages
              (id, scope, bot_id, identity_id, chat_id, session_id, sequence, role,
               content, inbox_id, inbound_update_id, reply_to_id, model, provider_response_id)
            VALUES (:id, :scope, :bot, :identity, :chat, :session, :sequence, :role,
                    :content, :inbox, :update, :reply, :model, :response)
        """, id=message_id, identity=inbox["identity_id"], chat=inbox["chat_id"],
            session=session_id, sequence=sequence, role=role, content=content,
            inbox=inbox["id"], update=inbox["update_id"], reply=reply_to, model=model, response=response_id)
        return message_id

    async def outbox(
        self, connection: AsyncConnection, inbox: Row, session_id: UUID,
        kind: str, chunks: list[str], assistant_id: UUID | None = None,
    ) -> None:
        for number, chunk in enumerate(chunks):
            await self.execute(connection, """
                INSERT INTO telegram_outbox
                  (id, scope, bot_id, identity_id, chat_id, session_id, inbox_id,
                   assistant_message_id, response_kind, chunk_number, content)
                VALUES (:id, :scope, :bot, :identity, :chat, :session, :inbox,
                        :assistant, :kind, :number, :content)
                ON CONFLICT (scope, bot_id, inbox_id, response_kind, chunk_number) DO NOTHING
            """, id=uuid4(), identity=inbox["identity_id"], chat=inbox["chat_id"],
                session=session_id, inbox=inbox["id"], assistant=assistant_id,
                kind=kind, number=number, content=chunk)
