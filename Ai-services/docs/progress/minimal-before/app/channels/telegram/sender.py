"""UTF-16 chunking and explicit Telegram delivery classifications."""

import asyncio
from dataclasses import dataclass

from aiogram import Bot
from aiogram.exceptions import (
    TelegramAPIError, TelegramNetworkError, TelegramRetryAfter,
    TelegramServerError,
)


@dataclass(frozen=True)
class Delivery:
    status: str
    code: str | None = None
    message_id: int | None = None
    retry_after: int = 0


def split_chunks(content: str) -> list[str]:
    chunks: list[str] = []
    characters: list[str] = []
    units = 0
    for character in content:
        width = 2 if ord(character) > 0xFFFF else 1
        if units + width > 4000:
            chunks.append("".join(characters))
            characters = []
            units = 0
        characters.append(character)
        units += width
    if characters:
        chunks.append("".join(characters))
    return chunks


class Sender:
    def __init__(self, bot: Bot) -> None:
        self.bot = bot

    async def send(self, chat_id: int, content: str, attempts: int) -> Delivery:
        try:
            async with asyncio.timeout(45):
                message = await self.bot.send_message(chat_id, content, parse_mode=None)
            return Delivery("SENT", message_id=message.message_id)
        except TelegramRetryAfter as error:
            if attempts >= 5:
                return Delivery("FAILED", "TELEGRAM_RATE_LIMIT_EXHAUSTED")
            return Delivery("PENDING", "TELEGRAM_RATE_LIMIT", retry_after=max(1, error.retry_after))
        except TelegramServerError:
            if attempts >= 3:
                return Delivery("FAILED", "TELEGRAM_SERVER_EXHAUSTED")
            return Delivery("PENDING", "TELEGRAM_SERVER", retry_after=5 * attempts)
        except (TelegramNetworkError, asyncio.TimeoutError):
            return Delivery("UNKNOWN", "TELEGRAM_TRANSPORT_UNKNOWN")
        except TelegramAPIError:
            return Delivery("FAILED", "TELEGRAM_REJECTED")
