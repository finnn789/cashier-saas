"""UTF-16 chunking and explicit Telegram delivery classifications."""

import asyncio
from dataclasses import dataclass
import logging
import time

from aiogram import Bot
from aiogram.exceptions import (
    TelegramAPIError,
    TelegramBadRequest,
    TelegramNetworkError,
    TelegramRetryAfter,
    TelegramServerError,
)
from aiogram.types import ReplyKeyboardMarkup, ReplyKeyboardRemove

logger = logging.getLogger(__name__)


def _elapsed_ms(started: float) -> int:
    return int((time.perf_counter() - started) * 1000)


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
    def __init__(self, bot: Bot, parse_mode: str | None = "Markdown") -> None:
        self.bot = bot
        self.parse_mode = parse_mode

    async def send(
        self,
        chat_id: int,
        content: str,
        attempts: int,
        reply_markup: ReplyKeyboardMarkup | ReplyKeyboardRemove | None = None,
    ) -> Delivery:
        # The keyboard is passed only when given, so plain replies keep the old call shape.
        extra: dict[str, ReplyKeyboardMarkup | ReplyKeyboardRemove] = {}
        if reply_markup is not None:
            extra["reply_markup"] = reply_markup
        started = time.perf_counter()
        logger.info(
            "Sending Telegram message",
            extra={
                "event": "sender_sending",
                "chars": len(content),
                "attempt": attempts,
                "parse_mode": self.parse_mode,
                "keyboard": type(reply_markup).__name__ if reply_markup is not None else "none",
            },
        )
        try:
            async with asyncio.timeout(45):
                if self.parse_mode is not None:
                    try:
                        message = await self.bot.send_message(
                            chat_id, content, parse_mode=self.parse_mode, **extra
                        )
                    except TelegramBadRequest as parse_err:
                        logger.warning(
                            "Telegram gagal mem-parsing format %s: %s. Melakukan fallback kirim plain text.",
                            self.parse_mode,
                            parse_err,
                            extra={"event": "sender_parse_mode_fallback"},
                        )
                        message = await self.bot.send_message(
                            chat_id, content, parse_mode=None, **extra
                        )
                        logger.info(
                            "Plain-text fallback accepted",
                            extra={"event": "sender_plain_text_fallback_accepted"},
                        )
                else:
                    message = await self.bot.send_message(
                        chat_id, content, parse_mode=None, **extra
                    )
            logger.info(
                "Telegram message sent",
                extra={
                    "event": "sender_message_sent",
                    "telegram_message_id": message.message_id,
                    "elapsed_ms": _elapsed_ms(started),
                },
            )
            return Delivery("SENT", message_id=message.message_id)
        except TelegramRetryAfter as error:
            if attempts >= 5:
                logger.warning(
                    "Rate limited, attempts exhausted",
                    extra={"event": "sender_rate_limit_exhausted", "attempt": attempts},
                )
                return Delivery("FAILED", "TELEGRAM_RATE_LIMIT_EXHAUSTED")
            logger.warning(
                "Rate limited",
                extra={
                    "event": "sender_rate_limited",
                    "attempt": attempts,
                    "retry_after": error.retry_after,
                },
            )
            return Delivery("PENDING", "TELEGRAM_RATE_LIMIT", retry_after=max(1, error.retry_after))
        except TelegramServerError:
            if attempts >= 3:
                logger.warning(
                    "Telegram server error, attempts exhausted",
                    extra={"event": "sender_server_error_exhausted", "attempt": attempts},
                )
                return Delivery("FAILED", "TELEGRAM_SERVER_EXHAUSTED")
            logger.warning(
                "Telegram server error",
                extra={
                    "event": "sender_server_error",
                    "attempt": attempts,
                    "retry_after": 5 * attempts,
                },
            )
            return Delivery("PENDING", "TELEGRAM_SERVER", retry_after=5 * attempts)
        except (TelegramNetworkError, asyncio.TimeoutError) as error:
            # Outcome unknown: the message may or may not have reached the user.
            logger.warning(
                "Transport failure, delivery outcome unknown",
                extra={
                    "event": "sender_transport_failure",
                    "error_type": type(error).__name__,
                    "elapsed_ms": _elapsed_ms(started),
                },
            )
            return Delivery("UNKNOWN", "TELEGRAM_TRANSPORT_UNKNOWN")
        except TelegramAPIError as api_err:
            logger.error(
                "Telegram send API error: %s",
                api_err,
                exc_info=True,
                extra={"event": "sender_api_error"},
            )
            return Delivery("FAILED", "TELEGRAM_REJECTED")
