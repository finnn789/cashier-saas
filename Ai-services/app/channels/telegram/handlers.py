'''Validate Telegram messages, run chat use cases, and send replies serially.'''

import asyncio
import logging

from aiogram.types import Message, Update

from app.channels.telegram.config import TelegramSettings
from app.channels.telegram.sender import Sender, split_chunks
from app.chat.service import ChatService

logger = logging.getLogger(__name__)
_COMMANDS = {'/start': 'START', '/new': 'NEW', '/session': 'SESSION'}
_MAX_INPUT_CHARS = 4000
_MAX_SEND_ATTEMPTS = 3


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
        try:
            await self.process(message)
        except asyncio.CancelledError:
            raise
        except Exception:
            # An ordinary exception returned to aiogram could advance its update
            # offset after uncertain persistence. Suspend until the poller is cancelled.
            self.healthy = False
            self.failed.set()
            logger.error('Telegram intake stopped after a fatal handler failure.')
            await asyncio.Future()

    async def process(self, message: Message) -> None:
        user = message.from_user
        if message.chat.type != 'private' or user is None or user.is_bot:
            return
        if self.settings.allowed_user_ids and user.id not in self.settings.allowed_user_ids:
            return

        content = (message.text or '').strip()
        if not content or len(content) > _MAX_INPUT_CHARS:
            kind = 'INVALID'
            content = ''
        elif content.startswith('/'):
            command = content.split(maxsplit=1)[0].split('@', maxsplit=1)[0].lower()
            kind = _COMMANDS.get(command, 'UNSUPPORTED')
            content = command
        else:
            kind = 'TEXT'

        first_name = ' '.join(
            part for part in (user.first_name, user.last_name) if part
        ).strip() or None
        response = await self.chat.handle(
            external_user_id=str(user.id),
            external_chat_id=str(message.chat.id),
            external_message_id=str(message.message_id),
            username=user.username,
            first_name=first_name,
            kind=kind,
            content=content,
        )
        if response is None:
            return

        for chunk in split_chunks(response):
            for attempt in range(1, _MAX_SEND_ATTEMPTS + 1):
                delivery = await self.sender.send(message.chat.id, chunk, attempt)
                if delivery.status == 'SENT':
                    break
                if delivery.status != 'PENDING':
                    return
                if attempt == _MAX_SEND_ATTEMPTS:
                    return
                await asyncio.sleep(max(1, delivery.retry_after))
