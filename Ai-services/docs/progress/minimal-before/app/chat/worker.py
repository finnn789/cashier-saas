"""One serial durable worker. No transactions span provider/send I/O."""

import asyncio
from datetime import datetime, timedelta, timezone
from uuid import UUID

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
        if repository.platform != "TELEGRAM":
            raise RuntimeError("TELEGRAM_REPOSITORY_REQUIRED")
        self.database = database
        self.repository = repository
        self.ai = ai
        self.sender = sender
        self.stopping = asyncio.Event()
        self.healthy = False

    async def recover(self) -> None:
        async with self.database.transaction() as connection:
            await self.repository.recover_sending(connection)
            await self.repository.suppress_tails_after_unknown(connection)
            # PROCESSING may represent an accepted provider request. Preserve USER
            # and create a safe error response without automatic regeneration.
            for inbox in await self.repository.processing_inboxes(connection):
                session_id = inbox["conversation_id"]
                if session_id is None:
                    raise RuntimeError("PROCESSING_CONVERSATION_MISSING")
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
                await self.repository.mark_ready(connection, inbox, "AI_CRASH_UNKNOWN")
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
            return await self.repository.next_inbox(connection)

    async def process(self, inbox: Row) -> None:
        prompt: list[dict[str, str]] = []
        async with self.database.transaction() as connection:
            identity = await self.repository.identity(connection, inbox)
            if identity is None:
                raise RuntimeError("IDENTITY_MISSING")
            if inbox["conversation_id"] is None:
                session = await self.repository.session(
                    connection, inbox, reset=inbox["kind"] == "NEW"
                )
                session_id = session["id"]
            else:
                session_id = inbox["conversation_id"]
            if inbox["kind"] != "TEXT":
                await self.command(connection, inbox, session_id, identity["display_name"])
                return
            user = await self.repository.existing_user_message(connection, inbox, session_id)
            if user is None:
                user_id = await self.repository.message(
                    connection, inbox, session_id, "USER", inbox["content"]
                )
            else:
                user_id = user["id"]
            pairs = await self.repository.completed_history(connection, inbox, session_id)
            prompt = build_prompt(
                identity["display_name"], identity["username"], pairs, inbox["content"]
            )
            await self.repository.mark_processing(connection, inbox, session_id)
        attempts = inbox["attempts"] + 1
        try:
            completion = await self.ai.reply(prompt)
        except AIError as error:
            async with self.database.transaction() as connection:
                if error.retryable and attempts < 3:
                    next_attempt = datetime.now(timezone.utc) + timedelta(
                        seconds=max(error.delay, attempts * 5)
                    )
                    await self.repository.mark_retry(
                        connection, inbox, error.code, next_attempt
                    )
                    return
                await self.repository.outbox(
                    connection, inbox, session_id, "ERROR", [self.error_message(error.code)]
                )
                await self.repository.mark_ready(connection, inbox, error.code)
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
                connection, inbox, session_id, "AI",
                split_chunks(completion.content), assistant_id,
            )
            await self.repository.mark_ready(connection, inbox, None)

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
        await self.repository.mark_command_ready(connection, inbox, session_id)

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
            chunk = await self.repository.pending_outbox(connection, inbox)
            if chunk is None:
                if await self.repository.has_failed_outbox(connection, inbox):
                    await self.repository.mark_delivery_failed(connection, inbox)
                else:
                    await self.repository.mark_done(connection, inbox)
                return
            now = datetime.now(timezone.utc)
            if chunk["next_attempt_at"] is not None and chunk["next_attempt_at"] > now:
                retry_due = chunk["next_attempt_at"]
            else:
                await self.repository.mark_sending(connection, inbox, chunk)
        if retry_due is not None:
            # Wait outside the transaction, then reread queue state in run().
            while not self.stopping.is_set():
                remaining = (retry_due - datetime.now(timezone.utc)).total_seconds()
                if remaining <= 0:
                    break
                await asyncio.sleep(min(1.0, remaining))
            return
        delivery = await self.sender.send(
            int(inbox["external_chat_id"]), chunk["content"], chunk["attempts"] + 1
        )
        next_attempt = None
        if delivery.status == "PENDING":
            next_attempt = datetime.now(timezone.utc) + timedelta(seconds=delivery.retry_after)
        external_message_id = (
            str(delivery.message_id) if delivery.message_id is not None else None
        )
        async with self.database.transaction() as connection:
            await self.repository.mark_outbox_result(
                connection, inbox, chunk, delivery.status, delivery.code,
                external_message_id, next_attempt, delivery.retry_after,
            )
            if delivery.status in ("UNKNOWN", "FAILED"):
                await self.repository.fail_later_chunks(connection, inbox, chunk)
        if delivery.status == "PENDING":
            await asyncio.sleep(min(delivery.retry_after, 1))
