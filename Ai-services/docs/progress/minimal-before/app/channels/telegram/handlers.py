"""Intake commits before returning to aiogram; it never calls AI or sends."""

import asyncio
import logging

from aiogram.types import Message, Update
from sqlalchemy.exc import DBAPIError, OperationalError

from app.channels.telegram.config import TelegramSettings
from app.chat.repositories import Repository
from app.core.database import Database, OwnershipLost

logger = logging.getLogger(__name__)


class Intake:
    def __init__(self, settings: TelegramSettings, database: Database, repository: Repository) -> None:
        self.settings = settings
        self.database = database
        self.repository = repository
        self.failed = asyncio.Event()
        self.healthy = True

    async def handle(self, message: Message, event_update: Update) -> None:
        # event_update is injected by the installed Dispatcher's _listen_update.
        try:
            await self.persist(message, event_update)
        except asyncio.CancelledError:
            raise
        except Exception:
            # Deliberate last-resort boundary: aiogram catches ordinary exceptions
            # and then advances its generator offset. Notify the supervisor, but
            # NEVER return or raise an ordinary exception to the dispatcher here.
            self.healthy = False
            self.failed.set()
            logger.error("Telegram intake stopped: persistence invariant failed.")
            await asyncio.Future()

    async def persist(self, message: Message, update: Update) -> None:
        user = message.from_user
        if message.chat.type != "private" or user is None or user.is_bot or message.text is None:
            return
        if self.settings.allowed_user_ids and user.id not in self.settings.allowed_user_ids:
            return
        content = message.text.strip()
        if not content:
            return
        kind = "TEXT"
        if content.startswith("/"):
            command = content.split(maxsplit=1)[0].split("@", maxsplit=1)[0].lower()
            commands = {"/start": "START", "/new": "NEW", "/session": "SESSION"}
            kind = commands.get(command, "UNSUPPORTED")
            content = command
        elif len(content) > 4000:
            kind = "INVALID"
            content = ""
        names = [user.first_name]
        if user.last_name:
            names.append(user.last_name)
        display_name = " ".join(names).strip()
        if not display_name:
            display_name = user.username or f"User {user.id}"
        delay = 1
        while True:
            try:
                async with self.database.transaction() as connection:
                    await self.repository.intake(
                        connection, str(user.id), user.username, user.first_name,
                        user.last_name, display_name, str(update.update_id),
                        str(message.chat.id), str(message.message_id), kind, content,
                    )
                self.healthy = True
                return
            except OwnershipLost:
                raise
            except (OperationalError, DBAPIError) as error:
                # Retry only known availability/transaction-abort classes. Invalidated
                # owner connections are fatal; reconnecting would forfeit the lock.
                if not self.database.owned or error.connection_invalidated:
                    raise OwnershipLost("DB_OWNER_LOST") from None
                sqlstate = getattr(error.orig, "sqlstate", None)
                if sqlstate not in ("40001", "40P01", "53300", "55P03", "57014"):
                    raise
                self.healthy = False
                logger.warning("Telegram intake waiting for database availability.")
                await asyncio.sleep(delay)
                delay = min(delay * 2, 15)
