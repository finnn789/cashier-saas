'''Serial chat use cases with short database transactions.'''

import asyncio
from uuid import UUID

from app.ai.openrouter import AIError, OpenRouterClient
from app.ai.prompts import MAX_INPUT_CHARS, build_prompt
from app.chat.repositories import Repository
from app.core.database import Database


class ChatService:
    def __init__(
        self,
        database: Database,
        repository: Repository,
        ai: OpenRouterClient,
    ) -> None:
        self.database = database
        self.repository = repository
        self.ai = ai
        self._serial = asyncio.Lock()

    async def handle(
        self,
        *,
        external_user_id: str,
        external_chat_id: str,
        external_message_id: str,
        username: str | None,
        first_name: str | None,
        kind: str,
        content: str,
    ) -> str | None:
        '''Handle one permitted SDK message; None means a duplicate TEXT was stored.'''
        if kind not in ('TEXT', 'START', 'NEW', 'SESSION', 'INVALID', 'UNSUPPORTED'):
            raise ValueError('MESSAGE_KIND_INVALID')
        if not external_chat_id or len(external_chat_id) > 128:
            raise ValueError('EXTERNAL_CHAT_ID_INVALID')
        if not external_message_id or len(external_message_id) > 512:
            raise ValueError('EXTERNAL_MESSAGE_ID_INVALID')
        if kind == 'TEXT' and (not content.strip() or len(content) > MAX_INPUT_CHARS):
            kind = 'INVALID'
        display_name = (first_name or username or external_user_id).strip()
        if not display_name:
            display_name = external_user_id
        async with self._serial:
            async with self.database.transaction() as connection:
                user_id = await self.repository.upsert_user(
                    connection, external_user_id, username, first_name
                )
                if kind == 'TEXT':
                    if await self.repository.find_duplicate_incoming(
                        connection, user_id, external_chat_id, external_message_id
                    ):
                        return None
                if kind in ('INVALID', 'UNSUPPORTED'):
                    return self._command_answer(kind, display_name, None)
                conversation_id = await self.repository.resolve_conversation(
                    connection, user_id, external_chat_id, reset=kind == 'NEW'
                )
                if kind != 'TEXT':
                    return self._command_answer(kind, display_name, conversation_id)
                current_id = await self.repository.insert_message(
                    connection,
                    conversation_id,
                    user_id,
                    external_chat_id,
                    'USER',
                    content,
                    external_message_id,
                )
                pairs = await self.repository.completed_history(
                    connection, conversation_id, user_id, external_chat_id, current_id
                )
                prompt = build_prompt(display_name, username, pairs, content)
            completion = None
            for attempt in range(1, 4):
                try:
                    completion = await self.ai.reply(prompt)
                    break
                except AIError as error:
                    if not error.retryable or attempt == 3:
                        return self._error_message(error.code)
                    await asyncio.sleep(max(error.delay, attempt * 5))
            if completion is None:
                raise RuntimeError('AI_COMPLETION_MISSING')
            async with self.database.transaction() as connection:
                await self.repository.insert_message(
                    connection,
                    conversation_id,
                    user_id,
                    external_chat_id,
                    'ASSISTANT',
                    completion.content,
                )
            return completion.content

    def _command_answer(self, kind: str, name: str, session_id: UUID | None) -> str:
        if kind == 'START':
            return f'Halo, {name}! Silakan kirim pertanyaan. Sesi: {session_id}'
        if kind == 'NEW':
            return f'Sesi baru dibuat, {name}. Sesi: {session_id}'
        if kind == 'SESSION':
            return f'{name}, sesi aktif Anda: {session_id}'
        if kind == 'INVALID':
            return 'Kirim pesan teks berisi 1 sampai 4000 karakter.'
        return 'Perintah tersedia: /start, /new, /session.'

    def _error_message(self, code: str) -> str:
        if code == 'AI_NOT_CONFIGURED':
            return 'AI belum dikonfigurasi. Operator perlu mengisi OPENROUTER_API_KEY dan AI_MODEL.'
        if code in ('AI_HTTP_401', 'AI_HTTP_403'):
            return 'Akses AI ditolak. Hubungi operator untuk memeriksa konfigurasi.'
        if code == 'AI_HTTP_402':
            return 'Saldo layanan AI tidak mencukupi. Hubungi operator.'
        if code == 'AI_TRANSPORT_UNKNOWN':
            return (
                'Koneksi AI terputus; hasil permintaan tidak diketahui. '
                'Kirim pertanyaan baru bila perlu.'
            )
        return 'Layanan AI belum dapat menjawab. Silakan kirim pertanyaan baru nanti.'
