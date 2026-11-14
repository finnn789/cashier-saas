'''Validate Telegram messages, resolve the verified tenant, run chat use cases,
and send replies serially.

Identity rules (TASK-059): the tenant is resolved server-side on EVERY message
from the user's verified membership. A phone number is accepted ONLY from a
Telegram contact the user shares about THEMSELVES (`contact.user_id ==
from_user.id`, not forwarded); typed or forwarded numbers never bind anything.
Phone numbers and names are never logged.
'''

import asyncio
import logging
import os
import time
import traceback

from aiogram.types import (
    KeyboardButton,
    Message,
    ReplyKeyboardMarkup,
    ReplyKeyboardRemove,
    Update,
    User,
)

from app.channels.telegram.config import TelegramSettings
from app.channels.telegram.sender import Sender, split_chunks
from app.chat.service import (
    CHOOSE,
    INVALID_CHOICE,
    LISTED,
    REVOKED,
    VERIFIED,
    ChatService,
    Identity,
    TenantOption,
)
from app.core.phone import normalize_phone
from app.observability import log_context

logger = logging.getLogger(__name__)
_COMMANDS = {'/start': 'START', '/new': 'NEW', '/session': 'SESSION', '/tenant': 'TENANT'}
_MAX_INPUT_CHARS = 4000
_MAX_SEND_ATTEMPTS = 3
_MAX_CHOICE_DIGITS = 3

SHARE_CONTACT_BUTTON = 'Bagikan nomor saya'
ASK_CONTACT = (
    'Untuk memakai asisten ini, verifikasi dulu bahwa Anda staf terdaftar. '
    f'Tekan tombol "{SHARE_CONTACT_BUTTON}" di bawah untuk membagikan nomor Telegram Anda sendiri.'
)
CONTACT_REJECTED = (
    'Kontak ini tidak dapat dipakai. Hanya nomor Telegram Anda sendiri yang dibagikan '
    f'lewat tombol "{SHARE_CONTACT_BUTTON}" yang diterima.'
)
PHONE_INVALID = 'Nomor telepon pada kontak Anda tidak valid.'
NOT_REGISTERED_TEXT = (
    'Nomor Anda tidak terdaftar sebagai staf. Minta pemilik usaha mendaftarkan nomor ini, '
    'lalu bagikan kontak Anda lagi.'
)
REVOKED_TEXT = (
    'Akses Anda ke usaha ini sudah tidak berlaku. Bagikan kontak Anda lagi untuk verifikasi ulang.'
)
CHOOSE_HEADER = (
    'Nomor Anda terdaftar di beberapa usaha. '
    'Pilih salah satu dengan mengirim /tenant <nomor>:'
)
LIST_HEADER = 'Usaha yang terhubung dengan akun Anda:'
LIST_FOOTER = 'Ganti usaha dengan mengirim /tenant <nomor>.'
INVALID_CHOICE_TEXT = 'Pilihan tidak valid.'
UNNAMED_TENANT = 'Usaha tanpa nama'


def contact_keyboard() -> ReplyKeyboardMarkup:
    return ReplyKeyboardMarkup(
        keyboard=[[KeyboardButton(text=SHARE_CONTACT_BUTTON, request_contact=True)]],
        resize_keyboard=True,
        one_time_keyboard=True,
    )


def own_contact_phone(message: Message, user: User) -> str | None:
    '''The raw phone of a contact the sender shared about themselves, else None.'''
    contact = message.contact
    if contact is None or contact.user_id is None or contact.user_id != user.id:
        return None
    if (
        message.forward_origin is not None
        or message.forward_date is not None
        or message.via_bot is not None
    ):
        return None
    return contact.phone_number or None


def _tenant_name(option: TenantOption) -> str:
    return option.name or UNNAMED_TENANT


def _numbered(options: tuple[TenantOption, ...], current: object = None) -> str:
    lines = []
    for number, option in enumerate(options, start=1):
        marker = ' (aktif)' if current is not None and option.tenant_id == current else ''
        lines.append(f'{number}. {_tenant_name(option)}{marker}')
    return '\n'.join(lines)


def _selected_name(identity: Identity) -> str:
    for option in identity.options:
        if option.tenant_id == identity.tenant_id:
            return _tenant_name(option)
    return UNNAMED_TENANT


def _parse_choice(argument: str | None) -> int | None:
    if argument is None:
        return None
    if not argument.isdigit() or len(argument) > _MAX_CHOICE_DIGITS:
        return 0  # never a valid 1-based index
    return int(argument)


def _identity_fields(identity: Identity) -> dict[str, object]:
    '''Log-safe identity description as log fields: no names, no phone numbers.'''
    return {
        'status': identity.status,
        'role': identity.role,
        'tenant_id': str(identity.tenant_id) if identity.tenant_id is not None else None,
        'options': len(identity.options),
    }


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


def _origin(error: BaseException) -> str:
    '''Where an error was raised, without its message (messages may carry secrets).'''
    frames = traceback.extract_tb(error.__traceback__)
    if not frames:
        return 'unknown'
    last = frames[-1]
    return f'{os.path.basename(last.filename)}:{last.lineno} in {last.name}'


class Intake:
    def __init__(
        self,
        settings: TelegramSettings,
        chat: ChatService,
        sender: Sender,
    ) -> None:
        self.settings = settings
        self.chat = chat
        self.sender = sender
        self.failed = asyncio.Event()
        self.healthy = True

    async def handle(self, message: Message, event_update: Update) -> None:
        # event_update is supplied by the installed Dispatcher; no queue ID is stored.
        started = time.perf_counter()
        with log_context(
            update_id=event_update.update_id,
            message_id=message.message_id,
            chat_id=message.chat.id,
        ):
            logger.info('Update received', extra={'event': 'update_received'})
            try:
                await self.process(message)
            except asyncio.CancelledError:
                logger.info(
                    'Update cancelled',
                    extra={'event': 'update_cancelled', 'elapsed_ms': _elapsed_ms(started)},
                )
                raise
            except Exception as error:
                # An ordinary exception returned to aiogram could advance its update
                # offset after uncertain persistence. Suspend until the poller is cancelled.
                self.healthy = False
                self.failed.set()
                # Only the exception type and raise site are logged: messages can carry secrets.
                logger.error(
                    'Telegram intake stopped after a fatal handler failure',
                    extra={
                        'event': 'intake_fatal_failure',
                        'error_type': type(error).__name__,
                        'origin': _origin(error),
                        'elapsed_ms': _elapsed_ms(started),
                    },
                )
                await asyncio.Future()
            logger.info(
                'Update handled',
                extra={'event': 'update_handled', 'elapsed_ms': _elapsed_ms(started)},
            )

    async def process(self, message: Message) -> None:
        user = message.from_user
        if message.chat.type != 'private' or user is None or user.is_bot:
            logger.info(
                'Update ignored',
                extra={
                    'event': 'update_ignored_not_private',
                    'reason': 'not a private chat with a human user',
                },
            )
            return
        if self.settings.allowed_user_ids and user.id not in self.settings.allowed_user_ids:
            logger.info(
                'Update ignored',
                extra={
                    'event': 'update_ignored_not_allowed',
                    'user_id': user.id,
                    'reason': 'user not in TELEGRAM allowlist',
                },
            )
            return
        with log_context(user_id=user.id):
            await self.process_user(message, user)

    async def process_user(self, message: Message, user: User) -> None:
        first_name = ' '.join(
            part for part in (user.first_name, user.last_name) if part
        ).strip() or None
        profile = {
            'external_user_id': str(user.id),
            'username': user.username,
            'first_name': first_name,
        }

        if message.contact is not None:
            logger.info('Contact message received', extra={'event': 'contact_message_received'})
            await self.process_contact(message, user, profile)
            return

        text_chars = len(message.text or '')
        content = (message.text or '').strip()
        argument: str | None = None
        if not content or len(content) > _MAX_INPUT_CHARS:
            kind = 'INVALID'
            content = ''
        elif content.startswith('/'):
            parts = content.split(maxsplit=2)
            command = parts[0].split('@', maxsplit=1)[0].lower()
            kind = _COMMANDS.get(command, 'UNSUPPORTED')
            content = command
            argument = parts[1] if len(parts) > 1 else None
        else:
            kind = 'TEXT'
        logger.info(
            'Message classified',
            extra={'event': 'message_classified', 'kind': kind, 'text_chars': text_chars},
        )

        if kind == 'TENANT':
            choice = _parse_choice(argument)
            logger.info(
                'Tenant command requested',
                extra={'event': 'tenant_command_requested', 'choice': choice},
            )
            identity = await self.chat.choose_tenant(**profile, choice=choice)
            logger.info(
                'Tenant command result',
                extra={'event': 'tenant_command_result', **_identity_fields(identity)},
            )
            await self.deliver(message.chat.id, *self.tenant_reply(identity))
            return

        logger.info('Resolving membership', extra={'event': 'identity_resolving'})
        identity = await self.chat.identify(**profile)
        logger.info(
            'Identity resolved', extra={'event': 'identity_resolved', **_identity_fields(identity)}
        )
        if not identity.can_chat or identity.tenant_id is None:
            logger.info(
                'Chat blocked, sending verification prompt',
                extra={'event': 'identity_chat_blocked', 'status': identity.status},
            )
            await self.deliver(message.chat.id, *self.identity_prompt(identity))
            return

        with log_context(tenant_id=identity.tenant_id):
            await self.chat_and_deliver(message, user, identity, first_name, kind, content)

    async def chat_and_deliver(
        self,
        message: Message,
        user: User,
        identity: Identity,
        first_name: str | None,
        kind: str,
        content: str,
    ) -> None:
        logger.info('Handling chat message', extra={'event': 'chat_handling', 'kind': kind})
        chat_started = time.perf_counter()
        response = await self.chat.handle(
            tenant_id=identity.tenant_id,
            java_user_id=identity.java_user_id,
            external_user_id=str(user.id),
            external_chat_id=str(message.chat.id),
            external_message_id=str(message.message_id),
            username=user.username,
            first_name=first_name,
            kind=kind,
            content=content,
        )
        if response is None:
            logger.info(
                'Duplicate message, no reply',
                extra={'event': 'chat_duplicate_message', 'elapsed_ms': _elapsed_ms(chat_started)},
            )
            return
        logger.info(
            'Chat reply ready',
            extra={
                'event': 'chat_reply_ready',
                'reply_chars': len(response),
                'elapsed_ms': _elapsed_ms(chat_started),
            },
        )
        await self.deliver(message.chat.id, response)

    async def process_contact(
        self, message: Message, user: User, profile: dict[str, str | None]
    ) -> None:
        raw_phone = own_contact_phone(message, user)
        if raw_phone is None:
            logger.info(
                'Contact rejected',
                extra={
                    'event': 'contact_rejected_not_own',
                    'reason': 'not own contact, forwarded, or empty',
                },
            )
            await self.deliver(message.chat.id, CONTACT_REJECTED, contact_keyboard())
            return
        phone = normalize_phone(raw_phone)
        if phone is None:
            logger.info(
                'Contact rejected',
                extra={
                    'event': 'contact_rejected_invalid_phone',
                    'reason': 'phone failed normalization',
                },
            )
            await self.deliver(message.chat.id, PHONE_INVALID, contact_keyboard())
            return
        logger.info(
            'Own contact accepted, verifying membership',
            extra={'event': 'contact_accepted'},
        )
        identity = await self.chat.verify_contact(
            external_user_id=str(user.id),
            username=profile['username'],
            first_name=profile['first_name'],
            phone_normalized=phone,
        )
        logger.info(
            'Contact verification result',
            extra={'event': 'contact_verification_result', **_identity_fields(identity)},
        )
        if identity.status == VERIFIED:
            text = (
                f'Verifikasi berhasil. Anda terhubung ke {_selected_name(identity)}. '
                'Silakan kirim pertanyaan.'
            )
            await self.deliver(message.chat.id, text, ReplyKeyboardRemove())
        elif identity.status == CHOOSE:
            text = f'{CHOOSE_HEADER}\n{_numbered(identity.options)}'
            await self.deliver(message.chat.id, text, ReplyKeyboardRemove())
        else:
            await self.deliver(message.chat.id, NOT_REGISTERED_TEXT, contact_keyboard())

    def identity_prompt(
        self, identity: Identity
    ) -> tuple[str, ReplyKeyboardMarkup | None]:
        if identity.status == CHOOSE:
            return f'{CHOOSE_HEADER}\n{_numbered(identity.options)}', None
        if identity.status == REVOKED:
            if identity.options:
                listing = _numbered(identity.options)
                text = f'{REVOKED_TEXT}\n{LIST_HEADER}\n{listing}\n{LIST_FOOTER}'
                return text, contact_keyboard()
            return REVOKED_TEXT, contact_keyboard()
        return ASK_CONTACT, contact_keyboard()

    def tenant_reply(
        self, identity: Identity
    ) -> tuple[str, ReplyKeyboardMarkup | None]:
        if identity.status == VERIFIED:
            text = (
                f'Usaha aktif sekarang: {_selected_name(identity)}. '
                'Percakapan dengan usaha ini terpisah dari usaha lain.'
            )
            return text, None
        if identity.status == LISTED:
            listing = _numbered(identity.options, identity.tenant_id)
            return f'{LIST_HEADER}\n{listing}\n{LIST_FOOTER}', None
        if identity.status == INVALID_CHOICE:
            listing = _numbered(identity.options)
            return f'{INVALID_CHOICE_TEXT}\n{LIST_HEADER}\n{listing}\n{LIST_FOOTER}', None
        if identity.status == REVOKED:
            return REVOKED_TEXT, contact_keyboard()
        return ASK_CONTACT, contact_keyboard()

    async def deliver(
        self,
        chat_id: int,
        text: str,
        reply_markup: ReplyKeyboardMarkup | ReplyKeyboardRemove | None = None,
    ) -> None:
        chunks = split_chunks(text)
        logger.info(
            'Delivery started',
            extra={
                'event': 'deliver_start',
                'chunks': len(chunks),
                'chars': len(text),
                'keyboard': type(reply_markup).__name__ if reply_markup is not None else 'none',
            },
        )
        for index, chunk in enumerate(chunks, start=1):
            for attempt in range(1, _MAX_SEND_ATTEMPTS + 1):
                if reply_markup is None:
                    delivery = await self.sender.send(chat_id, chunk, attempt)
                else:
                    delivery = await self.sender.send(
                        chat_id, chunk, attempt, reply_markup=reply_markup
                    )
                code = getattr(delivery, 'code', None)
                logger.info(
                    'Delivery chunk result',
                    extra={
                        'event': 'deliver_chunk_result',
                        'chunk': index,
                        'chunks': len(chunks),
                        'attempt': attempt,
                        'max_attempts': _MAX_SEND_ATTEMPTS,
                        'status': delivery.status,
                        'code': code,
                    },
                )
                if delivery.status == 'SENT':
                    break
                if delivery.status != 'PENDING':
                    logger.warning(
                        'Delivery abandoned, remaining chunks are not sent',
                        extra={
                            'event': 'deliver_abandoned',
                            'chunk': index,
                            'chunks': len(chunks),
                            'status': delivery.status,
                            'code': code,
                        },
                    )
                    return
                if attempt == _MAX_SEND_ATTEMPTS:
                    logger.warning(
                        'Delivery abandoned, retries exhausted',
                        extra={
                            'event': 'deliver_retries_exhausted',
                            'chunk': index,
                            'chunks': len(chunks),
                            'code': code,
                        },
                    )
                    return
                wait = max(1, delivery.retry_after)
                logger.info(
                    'Delivery retrying',
                    extra={'event': 'deliver_retrying', 'wait_seconds': wait},
                )
                await asyncio.sleep(wait)
        logger.info(
            'Delivery completed', extra={'event': 'deliver_completed', 'chunks': len(chunks)}
        )
