'''Serial chat use cases with short database transactions and LangGraph workflow.

The tenant of a request is never taken from message text. It is resolved from the
user's verified membership (`identify`, re-checked on every message) and passed to
`handle`, which scopes storage with `ChannelRepository.for_tenant` and hands the same
tenant to the graph state that `tool.execute(tenant_id=...)` reads.
'''

import asyncio
import json
import logging
import time
from dataclasses import dataclass
from typing import Any
from uuid import UUID

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage

from app.ai.graph.error_codes import classify_llm_error
from app.ai.graph.workflow import build_chat_graph
from app.ai.openrouter import AIError, OpenRouterClient
from app.ai.prompts import MAX_INPUT_CHARS, build_prompt
from app.chat import members
from app.chat.members import MemberMatch, VerifiedMembership
from app.chat.repositories import ChannelRepository
from app.core.database import Database
from app.tools.registry import ToolRegistry

logger = logging.getLogger(__name__)

# Identity outcomes. VERIFIED and FALLBACK carry a tenant usable by `handle`.
VERIFIED = 'VERIFIED'
FALLBACK = 'FALLBACK'
UNVERIFIED = 'UNVERIFIED'
CHOOSE = 'CHOOSE'
REVOKED = 'REVOKED'
NOT_REGISTERED = 'NOT_REGISTERED'
LISTED = 'LISTED'
INVALID_CHOICE = 'INVALID_CHOICE'
# Least privilege first when one phone matches two staff rows of the same tenant.
_ROLE_RANK = {'CASHIER': 0, 'OWNERMERCHANT': 1}


@dataclass(frozen=True)
class TenantOption:
    '''One verified tenant as offered to the user; only `name` is ever displayed.'''

    tenant_id: UUID
    name: str | None


@dataclass(frozen=True)
class Identity:
    '''Server-side identity of one Telegram user for one message.'''

    status: str
    tenant_id: UUID | None = None
    role: str | None = None
    options: tuple[TenantOption, ...] = ()
    java_user_id: UUID | None = None  # the verified Java staff user; sent to the backend as user_id

    @property
    def can_chat(self) -> bool:
        return self.status in (VERIFIED, FALLBACK) and self.tenant_id is not None


def _one_member_per_tenant(matches: list[MemberMatch]) -> list[MemberMatch]:
    '''Keep the given order of tenants; within a tenant keep the least privileged row.'''
    chosen: dict[UUID, MemberMatch] = {}
    for match in matches:
        current = chosen.get(match.tenant_id)
        if current is None or _ROLE_RANK.get(match.role, 9) < _ROLE_RANK.get(current.role, 9):
            chosen[match.tenant_id] = match
    return list(chosen.values())


def _options(items: list[VerifiedMembership] | list[MemberMatch]) -> tuple[TenantOption, ...]:
    return tuple(TenantOption(item.tenant_id, item.tenant_name) for item in items)


def _log_identity(identity: Identity, event: str, **fields: Any) -> Identity:
    '''Log one identity outcome (status, role, counts only) and return it unchanged.'''
    logger.info(
        'Identity step finished',
        extra={
            'event': event,
            'status': identity.status,
            'role': identity.role,
            'options_count': len(identity.options),
            **fields,
        },
    )
    return identity


class ChatService:
    def __init__(
        self,
        database: Database,
        channel_repository: ChannelRepository,
        ai: Any | None = None,
        tools: ToolRegistry | None = None,
        graph: Any | None = None,
        ai_settings: Any | None = None,
        fallback_tenant_id: UUID | None = None,
    ) -> None:
        if fallback_tenant_id is not None and not isinstance(fallback_tenant_id, UUID):
            raise ValueError('FALLBACK_TENANT_INVALID')
        self.database = database
        self.channel_repository = channel_repository
        # Development only: used for a user with NO verified membership, never otherwise.
        self.fallback_tenant_id = fallback_tenant_id
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

    async def identify(
        self,
        *,
        external_user_id: str,
        username: str | None,
        first_name: str | None,
    ) -> Identity:
        '''Resolve tenant and role for this message from the verified membership.'''
        logger.info('Identity lookup started', extra={'event': 'identity_lookup_started'})
        async with self._serial:
            async with self.database.transaction() as connection:
                user_id = await self.channel_repository.upsert_user(
                    connection, external_user_id, username, first_name
                )
                selected = await members.get_selected_tenant(connection, user_id)
                active, revoked = await self._recheck_memberships(connection, user_id)
                options = _options(active)
                chosen = next((item for item in active if item.tenant_id == selected), None)
                found = 'identity_lookup_finished'
                if chosen is not None:
                    return _log_identity(
                        Identity(VERIFIED, chosen.tenant_id, chosen.role, options, chosen.java_user_id),
                        found,
                        revoked_count=len(revoked),
                    )
                if selected is not None and selected in revoked:
                    return _log_identity(
                        Identity(REVOKED, options=options), found, revoked_count=len(revoked)
                    )
                if len(active) == 1:
                    await members.select_tenant(connection, user_id, active[0].tenant_id)
                    return _log_identity(
                        Identity(
                            VERIFIED, active[0].tenant_id, active[0].role, options,
                            active[0].java_user_id,
                        ),
                        found,
                        revoked_count=len(revoked),
                    )
                if active:
                    return _log_identity(
                        Identity(CHOOSE, options=options), found, revoked_count=len(revoked)
                    )
                if revoked:
                    return _log_identity(Identity(REVOKED), found, revoked_count=len(revoked))
                if self.fallback_tenant_id is not None:
                    return _log_identity(
                        Identity(FALLBACK, self.fallback_tenant_id),
                        found,
                        revoked_count=len(revoked),
                    )
                return _log_identity(Identity(UNVERIFIED), found, revoked_count=len(revoked))

    async def verify_contact(
        self,
        *,
        external_user_id: str,
        username: str | None,
        first_name: str | None,
        phone_normalized: str,
    ) -> Identity:
        '''Bind the user to every tenant whose ACTIVE member has this phone.

        The caller must have checked that the phone comes from the user's OWN
        Telegram contact; this method never sees message text.
        '''
        if not phone_normalized or not phone_normalized.isdigit():
            raise ValueError('PHONE_INVALID')
        async with self._serial:
            async with self.database.transaction() as connection:
                user_id = await self.channel_repository.upsert_user(
                    connection, external_user_id, username, first_name
                )
                matches = _one_member_per_tenant(
                    await members.find_active_members_by_phone(connection, phone_normalized)
                )
                await members.replace_memberships(connection, user_id, matches)
                options = _options(matches)
                verified = 'contact_verification_finished'
                if len(matches) == 1:
                    await members.select_tenant(connection, user_id, matches[0].tenant_id)
                    return _log_identity(
                        Identity(
                            VERIFIED, matches[0].tenant_id, matches[0].role, options,
                            matches[0].java_user_id,
                        ),
                        verified,
                        matches_count=len(matches),
                    )
                if matches:
                    await members.clear_selected_tenant(connection, user_id)
                    return _log_identity(
                        Identity(CHOOSE, options=options), verified, matches_count=len(matches)
                    )
                return _log_identity(
                    Identity(NOT_REGISTERED), verified, matches_count=len(matches)
                )

    async def choose_tenant(
        self,
        *,
        external_user_id: str,
        username: str | None,
        first_name: str | None,
        choice: int | None,
    ) -> Identity:
        '''List the verified tenants (choice None) or switch to the 1-based `choice`.'''
        async with self._serial:
            async with self.database.transaction() as connection:
                user_id = await self.channel_repository.upsert_user(
                    connection, external_user_id, username, first_name
                )
                selected = await members.get_selected_tenant(connection, user_id)
                active, revoked = await self._recheck_memberships(connection, user_id)
                options = _options(active)
                chosen_event = 'tenant_choice_finished'
                if not active:
                    return _log_identity(
                        Identity(REVOKED if revoked else UNVERIFIED), chosen_event, choice=choice
                    )
                if choice is None:
                    current = next(
                        (item for item in active if item.tenant_id == selected), None
                    )
                    if current is None:
                        return _log_identity(
                            Identity(LISTED, options=options), chosen_event, choice=choice
                        )
                    return _log_identity(
                        Identity(
                            LISTED, current.tenant_id, current.role, options, current.java_user_id
                        ),
                        chosen_event,
                        choice=choice,
                    )
                if not 1 <= choice <= len(active):
                    return _log_identity(
                        Identity(INVALID_CHOICE, options=options), chosen_event, choice=choice
                    )
                target = active[choice - 1]
                await members.select_tenant(connection, user_id, target.tenant_id)
                return _log_identity(
                    Identity(
                        VERIFIED, target.tenant_id, target.role, options, target.java_user_id
                    ),
                    chosen_event,
                    choice=choice,
                )

    async def _recheck_memberships(
        self, connection: Any, user_id: UUID
    ) -> tuple[list[VerifiedMembership], list[UUID]]:
        '''Keep memberships whose member is still ACTIVE; revoke (delete) the rest.'''
        memberships = await members.list_memberships(connection, user_id)
        revoked = [item.tenant_id for item in memberships if not item.active]
        if revoked:
            await members.revoke_memberships(connection, user_id, revoked)
            logger.warning(
                'Memberships revoked',
                extra={'event': 'memberships_revoked', 'revoked_count': len(revoked)},
            )
        return [item for item in memberships if item.active], revoked

    async def handle(
        self,
        *,
        tenant_id: UUID,
        external_user_id: str,
        external_chat_id: str,
        external_message_id: str,
        username: str | None,
        first_name: str | None,
        kind: str,
        content: str,
        java_user_id: UUID | None = None,
    ) -> str | None:
        '''Handle one permitted SDK message; None means a duplicate TEXT was stored.'''
        if not isinstance(tenant_id, UUID):
            raise ValueError('TENANT_SCOPE_INVALID')
        if kind not in ('TEXT', 'START', 'NEW', 'SESSION', 'INVALID', 'UNSUPPORTED'):
            raise ValueError('MESSAGE_KIND_INVALID')
        if not external_chat_id or len(external_chat_id) > 128:
            raise ValueError('EXTERNAL_CHAT_ID_INVALID')
        if not external_message_id or len(external_message_id) > 512:
            raise ValueError('EXTERNAL_MESSAGE_ID_INVALID')
        started = time.perf_counter()
        logger.info(
            'Chat handling started',
            extra={'event': 'chat_handle_started', 'kind': kind, 'content_chars': len(content)},
        )
        if kind == 'TEXT' and (not content.strip() or len(content) > MAX_INPUT_CHARS):
            kind = 'INVALID'
        display_name = (first_name or username or external_user_id).strip()
        if not display_name:
            display_name = external_user_id
        repository = self.channel_repository.for_tenant(tenant_id)
        async with self._serial:
            async with self.database.transaction() as connection:
                user_id = await repository.upsert_user(
                    connection, external_user_id, username, first_name
                )
                if kind == 'TEXT':
                    if await repository.find_duplicate_incoming(
                        connection, user_id, external_chat_id, external_message_id
                    ):
                        logger.info(
                            'Duplicate message ignored',
                            extra={'event': 'chat_duplicate_message'},
                        )
                        return self._finish(started, 'duplicate', None)
                if kind in ('INVALID', 'UNSUPPORTED'):
                    logger.info(
                        'Chat input not processable',
                        extra={'event': 'chat_input_invalid', 'kind': kind},
                    )
                    return self._finish(
                        started, 'command', self._command_answer(kind, display_name, None)
                    )
                conversation_id = await repository.resolve_conversation(
                    connection, user_id, external_chat_id, reset=kind == 'NEW'
                )
                logger.info(
                    'Conversation resolved',
                    extra={
                        'event': 'conversation_resolved',
                        'conversation_id': str(conversation_id),
                        'reset': kind == 'NEW',
                    },
                )
                if kind != 'TEXT':
                    return self._finish(
                        started,
                        'command',
                        self._command_answer(kind, display_name, conversation_id),
                    )
                current_id = await repository.insert_message(
                    connection,
                    conversation_id,
                    user_id,
                    external_chat_id,
                    'USER',
                    content,
                    external_message_id,
                )
                logger.info(
                    'User message stored',
                    extra={'event': 'user_message_stored', 'conversation_id': str(conversation_id)},
                )
                pairs = await repository.completed_history(
                    connection, conversation_id, user_id, external_chat_id, current_id
                )
                logger.info(
                    'History loaded',
                    extra={'event': 'history_loaded', 'pairs_count': len(pairs)},
                )

            ai_path = (
                'graph'
                if self.graph is not None
                else 'legacy'
                if self.ai is not None
                else 'unconfigured'
            )
            logger.info('AI path selected', extra={'event': 'ai_path_selected', 'path': ai_path})
            if self.graph is not None:
                if (
                    self.ai is not None
                    and getattr(self.ai, 'settings', None) is not None
                    and (
                        not getattr(self.ai.settings, 'openrouter_key', None)
                        or not getattr(self.ai.settings, 'ai_model', None)
                    )
                ):
                    return self._finish_error(started, 'AI_NOT_CONFIGURED', 'ai_not_configured')

                formatted_messages: list[BaseMessage] = []
                for user_text, assistant_text in pairs:
                    formatted_messages.append(HumanMessage(content=user_text))
                    formatted_messages.append(AIMessage(content=assistant_text))
                formatted_messages.append(HumanMessage(content=content))

                logger.info(
                    'AI graph invoke started',
                    extra={
                        'event': 'ai_graph_invoke_started',
                        'messages_count': len(formatted_messages),
                    },
                )
                graph_started = time.perf_counter()
                try:
                    graph_state = await self.graph.ainvoke({
                        'messages': formatted_messages,
                        'tenant_id': str(tenant_id),
                        'java_user_id': str(java_user_id) if java_user_id else None,
                        'user_id': user_id,
                        'conversation_id': str(conversation_id),
                        'display_name': display_name,
                    })
                except AIError as error:
                    return self._finish_error(started, error.code, 'ai_graph_error_code')
                except Exception as exc:
                    logger.error(
                        'Chat graph execution failed: %s',
                        exc,
                        exc_info=True,
                        extra={'event': 'ai_graph_failed'},
                    )
                    code = classify_llm_error(exc)
                    return self._finish_error(started, code, 'ai_graph_error_code')

                logger.info(
                    'AI graph invoke finished',
                    extra={
                        'event': 'ai_graph_invoke_finished',
                        'elapsed_ms': int((time.perf_counter() - graph_started) * 1000),
                        'has_final_response': isinstance(graph_state, dict)
                        and bool(graph_state.get('final_response')),
                    },
                )
                if isinstance(graph_state, dict):
                    # Any non-empty error code means the graph failed: never persist a reply.
                    error_code = graph_state.get('error_code')
                    if isinstance(error_code, str) and error_code:
                        return self._finish_error(started, error_code, 'ai_graph_error_code')

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
                    raise RuntimeError('AI_COMPLETION_MISSING')

                async with self.database.transaction() as connection:
                    await repository.insert_message(
                        connection,
                        conversation_id,
                        user_id,
                        external_chat_id,
                        'ASSISTANT',
                        final_answer,
                    )
                logger.info(
                    'Assistant message stored',
                    extra={
                        'event': 'assistant_message_stored',
                        'conversation_id': str(conversation_id),
                        'reply_chars': len(final_answer),
                    },
                )
                return self._finish(started, 'reply', final_answer)

            elif self.ai is not None:
                prompt = build_prompt(display_name, username, pairs, content)
                completion = None
                tools_schemas = self.tools.get_openrouter_schemas() if self.tools else None
                for attempt in range(1, 4):
                    logger.info(
                        'AI reply attempt',
                        extra={'event': 'ai_reply_attempt', 'attempt': attempt, 'stage': 'first'},
                    )
                    try:
                        completion = await self.ai.reply(prompt, tools=tools_schemas)
                        break
                    except AIError as error:
                        if not error.retryable or attempt == 3:
                            return self._finish_error(started, error.code, 'ai_reply_failed')
                        delay = max(error.delay, attempt * 5)
                        logger.warning(
                            'AI reply retry',
                            extra={
                                'event': 'ai_reply_retry',
                                'attempt': attempt,
                                'delay_seconds': delay,
                                'code': error.code,
                            },
                        )
                        await asyncio.sleep(delay)
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
                        tool_started = time.perf_counter()
                        tool_result = await self.tools.dispatch(
                            tool_name=fn_name,
                            arguments_json=fn_args,
                            tenant_id=str(tenant_id),
                            user_id=str(java_user_id) if java_user_id else None,
                        )
                        logger.info(
                            'AI tool call finished',
                            extra={
                                'event': 'ai_tool_call',
                                'tool_name': fn_name,
                                'elapsed_ms': int((time.perf_counter() - tool_started) * 1000),
                            },
                        )
                        tool_messages.append(
                            {
                                'role': 'tool',
                                'tool_call_id': call_id,
                                'name': fn_name,
                                'content': json.dumps(tool_result),
                            }
                        )
                    logger.info(
                        'AI second completion started',
                        extra={'event': 'ai_second_completion_started'},
                    )
                    for attempt in range(1, 4):
                        logger.info(
                            'AI reply attempt',
                            extra={
                                'event': 'ai_reply_attempt',
                                'attempt': attempt,
                                'stage': 'second',
                            },
                        )
                        try:
                            second_completion = await self.ai.reply(tool_messages)
                            completion = second_completion
                            break
                        except AIError as error:
                            if not error.retryable or attempt == 3:
                                return self._finish_error(
                                    started, error.code, 'ai_reply_failed'
                                )
                            delay = max(error.delay, attempt * 5)
                            logger.warning(
                                'AI reply retry',
                                extra={
                                    'event': 'ai_reply_retry',
                                    'attempt': attempt,
                                    'delay_seconds': delay,
                                    'code': error.code,
                                },
                            )
                            await asyncio.sleep(delay)

                async with self.database.transaction() as connection:
                    await repository.insert_message(
                        connection,
                        conversation_id,
                        user_id,
                        external_chat_id,
                        'ASSISTANT',
                        completion.content,
                    )
                logger.info(
                    'Assistant message stored',
                    extra={
                        'event': 'assistant_message_stored',
                        'conversation_id': str(conversation_id),
                        'reply_chars': len(completion.content or ''),
                    },
                )
                return self._finish(started, 'reply', completion.content)
            else:
                return self._finish_error(started, 'AI_NOT_CONFIGURED', 'ai_not_configured')

    def _finish(self, started: float, outcome: str, reply: str | None) -> str | None:
        '''Log the end of `handle` (outcome, length, duration only) and return `reply`.'''
        logger.info(
            'Chat handling finished',
            extra={
                'event': 'chat_handle_finished',
                'elapsed_ms': int((time.perf_counter() - started) * 1000),
                'reply_chars': len(reply) if reply else 0,
                'outcome': outcome,
            },
        )
        return reply

    def _finish_error(self, started: float, code: str, event: str) -> str:
        '''Log an AI error code as a WARNING, finish `handle` and return the user message.'''
        logger.warning('AI step ended with an error code', extra={'event': event, 'code': code})
        reply = self._error_message(code)
        self._finish(started, 'error', reply)
        return reply

    def _command_answer(self, kind: str, name: str, session_id: UUID | None) -> str:
        if kind == 'START':
            return f'Halo, {name}! Silakan kirim pertanyaan. Sesi: {session_id}'
        if kind == 'NEW':
            return f'Sesi baru dibuat, {name}. Sesi: {session_id}'
        if kind == 'SESSION':
            return f'{name}, sesi aktif Anda: {session_id}'
        if kind == 'INVALID':
            return 'Kirim pesan teks berisi 1 sampai 4000 karakter.'
        return 'Perintah tersedia: /start, /new, /session, /tenant.'

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
