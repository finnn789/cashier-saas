'''Serial chat use cases with short database transactions and LangGraph workflow.'''

import asyncio
import json
import logging
from typing import Any
from uuid import UUID

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage

from app.ai.graph.workflow import build_chat_graph
from app.ai.openrouter import AIError, OpenRouterClient
from app.ai.prompts import MAX_INPUT_CHARS, build_prompt
from app.chat.repositories import Repository
from app.core.database import Database
from app.tools.registry import ToolRegistry

logger = logging.getLogger(__name__)


class ChatService:
    def __init__(
        self,
        database: Database,
        repository: Repository,
        ai: Any | None = None,
        tools: ToolRegistry | None = None,
        graph: Any | None = None,
        ai_settings: Any | None = None,
    ) -> None:
        self.database = database
        self.repository = repository
        self.ai = ai
        self.tools = tools
        self._serial = asyncio.Lock()
        if graph is not None:
            self.graph = graph
        else:
            settings_obj = ai_settings
            if settings_obj is None and ai is not None:
                settings_obj = getattr(ai, 'settings', None)

            openrouter_key = getattr(settings_obj, 'openrouter_key', None) if settings_obj else None
            ai_model = getattr(settings_obj, 'ai_model', None) if settings_obj else None

            if isinstance(openrouter_key, str) and isinstance(ai_model, str) and openrouter_key.strip():
                self.graph = build_chat_graph(
                    openrouter_key=openrouter_key,
                    ai_model=ai_model,
                    tools=tools,
                )
            else:
                self.graph = None

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

            if self.graph is not None:
                if (
                    self.ai is not None
                    and getattr(self.ai, 'settings', None) is not None
                    and (
                        not getattr(self.ai.settings, 'openrouter_key', None)
                        or not getattr(self.ai.settings, 'ai_model', None)
                    )
                ):
                    return self._error_message('AI_NOT_CONFIGURED')

                formatted_messages: list[BaseMessage] = []
                for user_text, assistant_text in pairs:
                    formatted_messages.append(HumanMessage(content=user_text))
                    formatted_messages.append(AIMessage(content=assistant_text))
                formatted_messages.append(HumanMessage(content=content))

                try:
                    graph_state = await self.graph.ainvoke({
                        'messages': formatted_messages,
                        'tenant_id': str(self.repository.tenant_id),
                        'user_id': user_id,
                        'conversation_id': str(conversation_id),
                        'display_name': display_name,
                    })
                except AIError as error:
                    return self._error_message(error.code)
                except Exception as exc:
                    logger.error('Chat graph execution failed: %s', exc, exc_info=True)
                    code = getattr(exc, 'code', None)
                    if isinstance(code, str) and code in (
                        'AI_NOT_CONFIGURED',
                        'AI_HTTP_401',
                        'AI_HTTP_403',
                        'AI_HTTP_402',
                        'AI_TRANSPORT_UNKNOWN',
                    ):
                        return self._error_message(code)
                    exc_str = str(exc)
                    if '401' in exc_str:
                        return self._error_message('AI_HTTP_401')
                    if '402' in exc_str:
                        return self._error_message('AI_HTTP_402')
                    if '403' in exc_str:
                        return self._error_message('AI_HTTP_403')
                    return self._error_message('AI_UNKNOWN')

                if isinstance(graph_state, dict):
                    error_code = graph_state.get('error_code')
                    if error_code in (
                        'AI_NOT_CONFIGURED',
                        'AI_HTTP_401',
                        'AI_HTTP_403',
                        'AI_HTTP_402',
                        'AI_TRANSPORT_UNKNOWN',
                    ):
                        return self._error_message(error_code)

                final_answer: str | None = None
                if isinstance(graph_state, dict):
                    if graph_state.get('final_response'):
                        final_answer = str(graph_state['final_response'])
                    elif 'messages' in graph_state and graph_state['messages']:
                        for msg in reversed(graph_state['messages']):
                            if isinstance(msg, AIMessage) and msg.content:
                                final_answer = (
                                    msg.content
                                    if isinstance(msg.content, str)
                                    else str(msg.content)
                                )
                                break
                            elif (
                                hasattr(msg, 'content')
                                and msg.content
                                and not getattr(msg, 'tool_calls', None)
                            ):
                                final_answer = str(msg.content)
                                break

                if not final_answer:
                    if isinstance(graph_state, dict) and graph_state.get('error_code'):
                        return self._error_message(graph_state['error_code'])
                    raise RuntimeError('AI_COMPLETION_MISSING')

                async with self.database.transaction() as connection:
                    await self.repository.insert_message(
                        connection,
                        conversation_id,
                        user_id,
                        external_chat_id,
                        'ASSISTANT',
                        final_answer,
                    )
                return final_answer

            elif self.ai is not None:
                prompt = build_prompt(display_name, username, pairs, content)
                completion = None
                tools_schemas = self.tools.get_openrouter_schemas() if self.tools else None
                for attempt in range(1, 4):
                    try:
                        completion = await self.ai.reply(prompt, tools=tools_schemas)
                        break
                    except AIError as error:
                        if not error.retryable or attempt == 3:
                            return self._error_message(error.code)
                        await asyncio.sleep(max(error.delay, attempt * 5))
                if completion is None:
                    raise RuntimeError('AI_COMPLETION_MISSING')

                if completion.tool_calls and self.tools:
                    tool_messages = list(prompt)
                    tool_messages.append(
                        completion.raw_message
                        or {
                            'role': 'assistant',
                            'content': completion.content or None,
                            'tool_calls': completion.tool_calls,
                        }
                    )
                    for call in completion.tool_calls:
                        call_id = call.get('id', '')
                        fn_name = call.get('function', {}).get('name', '')
                        fn_args = call.get('function', {}).get('arguments', '{}')
                        tool_result = await self.tools.dispatch(
                            tool_name=fn_name,
                            arguments_json=fn_args,
                            tenant_id=str(self.repository.tenant_id),
                        )
                        tool_messages.append(
                            {
                                'role': 'tool',
                                'tool_call_id': call_id,
                                'name': fn_name,
                                'content': json.dumps(tool_result),
                            }
                        )
                    for attempt in range(1, 4):
                        try:
                            second_completion = await self.ai.reply(tool_messages)
                            completion = second_completion
                            break
                        except AIError as error:
                            if not error.retryable or attempt == 3:
                                return self._error_message(error.code)
                            await asyncio.sleep(max(error.delay, attempt * 5))

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
            else:
                return self._error_message('AI_NOT_CONFIGURED')

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
