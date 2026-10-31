"""One serial durable worker. No transactions span provider/send I/O."""

import asyncio
from datetime import datetime, timedelta, timezone
from uuid import UUID

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncConnection

from app.ai.openrouter import AIError, OpenRouterClient
from app.ai.prompts import build_prompt
from app.channels.telegram.sender import Sender, split_chunks
from app.chat.repositories import Repository, Row
from app.core.database import Database


class Worker:
    def __init__(
        self,
        database: Database,
        repository: Repository,
        ai: OpenRouterClient,
        sender: Sender,
    ) -> None:
        self.database = database
        self.repository = repository
        self.ai = ai
        self.sender = sender
        self.stopping = asyncio.Event()
        self.healthy = False

    async def recover(self) -> None:
        async with self.database.transaction() as connection:
            await self.repository.execute(
                connection,
                """
                UPDATE telegram_outbox
                SET status='UNKNOWN', error_code='CRASH_DURING_SEND', updated_at=now()
                WHERE scope=:scope AND bot_id=:bot AND status='SENDING'
                """,
            )
            await self.repository.execute(
                connection,
                """
                UPDATE telegram_outbox o
                SET status='FAILED', error_code='PREVIOUS_CHUNK_UNKNOWN', updated_at=now()
                WHERE o.scope=:scope AND o.bot_id=:bot AND o.status='PENDING'
                  AND EXISTS (SELECT 1 FROM telegram_outbox p WHERE p.scope=o.scope
                    AND p.bot_id=o.bot_id AND p.inbox_id=o.inbox_id
                    AND p.response_kind=o.response_kind AND p.chunk_number<o.chunk_number
                    AND p.status IN ('UNKNOWN','FAILED'))
                """,
            )
            # PROCESSING may represent an accepted provider request. Preserve USER
            # and create a safe error response without automatic regeneration.
            result = await connection.execute(
                text("""
                    SELECT * FROM telegram_inbox WHERE scope=:scope AND bot_id=:bot
                      AND status='PROCESSING' ORDER BY received_order
                """),
                self.repository.parameters(),
            )
            for mapping in result.mappings():
                inbox = dict(mapping)
                session_id = inbox["session_id"]
                if session_id is None:
                    raise RuntimeError("PROCESSING_SESSION_MISSING")
                await self.repository.outbox(
                    connection,
                    inbox,
                    session_id,
                    "ERROR",
                    [
                        "Permintaan AI sebelumnya terputus. "
                        "Kirim pertanyaan baru untuk mencoba lagi."
                    ],
                )
                await self.repository.execute(
                    connection,
                    """
                    UPDATE telegram_inbox
                    SET status='READY', error_code='AI_CRASH_UNKNOWN', updated_at=now()
                    WHERE scope=:scope AND bot_id=:bot AND id=:id
                    """,
                    id=inbox["id"],
                )
        self.healthy = True

    async def run(self) -> None:
        try:
            while not self.stopping.is_set():
                inbox = await self.next_inbox()
                if inbox is None:
                    await asyncio.sleep(0.5)
                    continue
                next_attempt = inbox["next_attempt_at"]
                now = datetime.now(timezone.utc)
                if next_attempt is not None and next_attempt > now:
                    await asyncio.sleep(min(1.0, (next_attempt - now).total_seconds()))
                    continue
                if inbox["status"] == "READY":
                    await self.deliver(inbox)
                else:
                    await self.process(inbox)
        finally:
            self.healthy = False

    async def next_inbox(self) -> Row | None:
        async with self.database.transaction() as connection:
            return await self.repository.one(
                connection,
                """
                SELECT * FROM telegram_inbox WHERE scope=:scope AND bot_id=:bot
                  AND status IN ('PENDING','RETRY','READY') ORDER BY received_order LIMIT 1
                """,
            )

    async def process(self, inbox: Row) -> None:
        prompt: list[dict[str, str]] = []
        async with self.database.transaction() as connection:
            identity = await self.repository.one(
                connection,
                """
                SELECT * FROM telegram_identities
                WHERE scope=:scope AND bot_id=:bot AND id=:identity
                """,
                identity=inbox["identity_id"],
            )
            if identity is None:
                raise RuntimeError("IDENTITY_MISSING")
            if inbox["session_id"] is None:
                session = await self.repository.session(
                    connection, inbox, reset=inbox["kind"] == "NEW"
                )
                session_id = session["id"]
            else:
                session_id = inbox["session_id"]
            if inbox["kind"] != "TEXT":
                await self.command(connection, inbox, session_id, identity["display_name"])
                return
            user = await self.repository.one(
                connection,
                """
                SELECT id FROM conversation_messages WHERE scope=:scope AND bot_id=:bot
                  AND identity_id=:identity AND chat_id=:chat AND session_id=:session
                  AND inbox_id=:inbox AND role='USER'
                """,
                identity=inbox["identity_id"],
                chat=inbox["chat_id"],
                session=session_id,
                inbox=inbox["id"],
            )
            if user is None:
                user_id = await self.repository.message(
                    connection, inbox, session_id, "USER", inbox["content"]
                )
            else:
                user_id = user["id"]
            # Successful completed pairs only; exclude failed and current turns.
            result = await connection.execute(
                text("""
                    SELECT u.content AS user_content, a.content AS answer
                    FROM conversation_messages u
                    JOIN conversation_messages a ON a.scope=u.scope AND a.bot_id=u.bot_id
                      AND a.identity_id=u.identity_id AND a.chat_id=u.chat_id
                      AND a.session_id=u.session_id AND a.reply_to_id=u.id
                      AND a.role='ASSISTANT'
                    JOIN telegram_inbox i ON i.scope=u.scope AND i.bot_id=u.bot_id
                      AND i.identity_id=u.identity_id AND i.chat_id=u.chat_id AND i.id=u.inbox_id
                    WHERE u.scope=:scope AND u.bot_id=:bot AND u.identity_id=:identity
                      AND u.chat_id=:chat AND u.session_id=:session AND u.role='USER'
                      AND i.status='DONE' AND u.inbox_id<>:inbox
                    ORDER BY u.sequence DESC LIMIT 32
                """),
                self.repository.parameters(
                    identity=inbox["identity_id"],
                    chat=inbox["chat_id"],
                    session=session_id,
                    inbox=inbox["id"],
                ),
            )
            pairs: list[tuple[str, str]] = []
            for row in reversed(result.mappings().all()):
                pairs.append((row["user_content"], row["answer"]))
            prompt = build_prompt(
                identity["display_name"], identity["username"], pairs, inbox["content"]
            )
            await self.repository.execute(
                connection,
                """
                UPDATE telegram_inbox SET status='PROCESSING', attempts=attempts+1,
                  session_id=:session, next_attempt_at=NULL, updated_at=now()
                WHERE scope=:scope AND bot_id=:bot AND id=:id
                  AND identity_id=:identity AND chat_id=:chat
                """,
                session=session_id,
                id=inbox["id"],
                identity=inbox["identity_id"],
                chat=inbox["chat_id"],
            )
        attempts = inbox["attempts"] + 1
        try:
            completion = await self.ai.reply(prompt)
        except AIError as error:
            async with self.database.transaction() as connection:
                if error.retryable and attempts < 3:
                    next_attempt = datetime.now(timezone.utc) + timedelta(
                        seconds=max(error.delay, attempts * 5)
                    )
                    await self.repository.execute(
                        connection,
                        """
                        UPDATE telegram_inbox SET status='RETRY', error_code=:code,
                          next_attempt_at=:next, updated_at=now()
                        WHERE scope=:scope AND bot_id=:bot AND id=:id
                        """,
                        id=inbox["id"],
                        code=error.code,
                        next=next_attempt,
                    )
                    return
                await self.repository.outbox(
                    connection, inbox, session_id, "ERROR", [self.error_message(error.code)]
                )
                await self.repository.execute(
                    connection,
                    """
                    UPDATE telegram_inbox SET status='READY', error_code=:code, updated_at=now()
                    WHERE scope=:scope AND bot_id=:bot AND id=:id
                    """,
                    id=inbox["id"],
                    code=error.code,
                )
            return
        async with self.database.transaction() as connection:
            assistant_id = await self.repository.message(
                connection,
                inbox,
                session_id,
                "ASSISTANT",
                completion.content,
                reply_to=user_id,
                model=completion.model,
                response_id=completion.response_id,
            )
            await self.repository.outbox(
                connection,
                inbox,
                session_id,
                "AI",
                split_chunks(completion.content),
                assistant_id,
            )
            await self.repository.execute(
                connection,
                """
                UPDATE telegram_inbox SET status='READY', error_code=NULL, updated_at=now()
                WHERE scope=:scope AND bot_id=:bot AND id=:id
                """,
                id=inbox["id"],
            )

    async def command(
        self,
        connection: AsyncConnection,
        inbox: Row,
        session_id: UUID,
        name: str,
    ) -> None:
        if inbox["kind"] == "START":
            answer = f"Halo, {name}! Silakan kirim pertanyaan. Sesi: {session_id}"
        elif inbox["kind"] == "NEW":
            answer = f"Sesi baru dibuat, {name}. Sesi: {session_id}"
        elif inbox["kind"] == "SESSION":
            answer = f"{name}, sesi aktif Anda: {session_id}"
        elif inbox["kind"] == "INVALID":
            answer = "Kirim pesan teks berisi 1 sampai 4000 karakter."
        else:
            answer = "Perintah tersedia: /start, /new, /session."
        await self.repository.outbox(
            connection, inbox, session_id, "COMMAND", split_chunks(answer)
        )
        await self.repository.execute(
            connection,
            """
            UPDATE telegram_inbox SET status='READY', session_id=:session, updated_at=now()
            WHERE scope=:scope AND bot_id=:bot AND id=:id
            """,
            session=session_id,
            id=inbox["id"],
        )

    def error_message(self, code: str) -> str:
        if code == "AI_NOT_CONFIGURED":
            return "AI belum dikonfigurasi. Operator perlu mengisi OPENROUTER_API_KEY dan AI_MODEL."
        if code in ("AI_HTTP_401", "AI_HTTP_403"):
            return "Akses AI ditolak. Hubungi operator untuk memeriksa konfigurasi."
        if code == "AI_HTTP_402":
            return "Saldo layanan AI tidak mencukupi. Hubungi operator."
        if code == "AI_TRANSPORT_UNKNOWN":
            return (
                "Koneksi AI terputus; hasil permintaan tidak diketahui. "
                "Kirim pertanyaan baru bila perlu."
            )
        return "Layanan AI belum dapat menjawab. Silakan kirim pertanyaan baru nanti."

    async def deliver(self, inbox: Row) -> None:
        retry_due: datetime | None = None
        async with self.database.transaction() as connection:
            chunk = await self.repository.one(
                connection,
                """
                SELECT * FROM telegram_outbox WHERE scope=:scope AND bot_id=:bot
                  AND identity_id=:identity AND chat_id=:chat AND inbox_id=:inbox
                  AND status='PENDING' ORDER BY chunk_number LIMIT 1
                """,
                identity=inbox["identity_id"],
                chat=inbox["chat_id"],
                inbox=inbox["id"],
            )
            if chunk is None:
                failed = await self.repository.one(
                    connection,
                    """
                    SELECT id FROM telegram_outbox WHERE scope=:scope AND bot_id=:bot
                      AND inbox_id=:inbox AND status IN ('FAILED','UNKNOWN') LIMIT 1
                    """,
                    inbox=inbox["id"],
                )
                status = "DONE"
                if failed is not None:
                    status = "DELIVERY_FAILED"
                await self.repository.execute(
                    connection,
                    """
                    UPDATE telegram_inbox SET status=:status, updated_at=now()
                    WHERE scope=:scope AND bot_id=:bot AND id=:id
                    """,
                    status=status,
                    id=inbox["id"],
                )
                return
            now = datetime.now(timezone.utc)
            if chunk["next_attempt_at"] is not None and chunk["next_attempt_at"] > now:
                retry_due = chunk["next_attempt_at"]
            else:
                await self.repository.execute(
                    connection,
                    """
                    UPDATE telegram_outbox SET status='SENDING', attempts=attempts+1, updated_at=now()
                    WHERE scope=:scope AND bot_id=:bot AND id=:id
                    """,
                    id=chunk["id"],
                )
        if retry_due is not None:
            # Wait outside the transaction, then reread queue state in run().
            while not self.stopping.is_set():
                remaining = (retry_due - datetime.now(timezone.utc)).total_seconds()
                if remaining <= 0:
                    break
                await asyncio.sleep(min(1.0, remaining))
            return
        delivery = await self.sender.send(
            inbox["chat_id"], chunk["content"], chunk["attempts"] + 1
        )
        next_attempt = None
        if delivery.status == "PENDING":
            next_attempt = datetime.now(timezone.utc) + timedelta(seconds=delivery.retry_after)
        async with self.database.transaction() as connection:
            await self.repository.execute(
                connection,
                """
                UPDATE telegram_outbox SET status=:status, error_code=:code,
                  telegram_message_id=:message, next_attempt_at=:next,
                  retry_after_seconds=:retry, updated_at=now()
                WHERE scope=:scope AND bot_id=:bot AND id=:id
                """,
                status=delivery.status,
                code=delivery.code,
                message=delivery.message_id,
                next=next_attempt,
                retry=delivery.retry_after,
                id=chunk["id"],
            )
            if delivery.status in ("UNKNOWN", "FAILED"):
                await self.repository.execute(
                    connection,
                    """
                    UPDATE telegram_outbox
                    SET status='FAILED', error_code='PREVIOUS_CHUNK_FAILED', updated_at=now()
                    WHERE scope=:scope AND bot_id=:bot AND inbox_id=:inbox
                      AND response_kind=:kind AND chunk_number>:number AND status='PENDING'
                    """,
                    inbox=inbox["id"],
                    kind=chunk["response_kind"],
                    number=chunk["chunk_number"],
                )
        if delivery.status == "PENDING":
            await asyncio.sleep(min(delivery.retry_after, 1))
