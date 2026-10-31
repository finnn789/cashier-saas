"""Offline delivery classifications; persistence is the worker's responsibility."""
import asyncio
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

from aiogram import Bot
from aiogram.exceptions import (TelegramBadRequest, TelegramForbiddenError,
    TelegramNetworkError, TelegramRetryAfter, TelegramServerError, TelegramUnauthorizedError)
from aiogram.methods import SendMessage
from app.channels.telegram.sender import Sender, split_chunks


class SenderTests(unittest.IsolatedAsyncioTestCase):
    def sender(self, error: Exception | None = None) -> tuple[Sender, Mock]:
        bot = Mock(spec=Bot)
        bot.send_message = AsyncMock(return_value=SimpleNamespace(message_id=91), side_effect=error)
        return Sender(bot), bot

    def test_utf16_chunks_preserve_content_and_boundaries(self) -> None:
        for content in ("", "x" * 4000, "x" * 4001, "😀" * 2001,
                        "x" * 3999 + "😀" + "tail"):
            with self.subTest(characters=len(content)):
                chunks = split_chunks(content)
                self.assertEqual("".join(chunks), content)
                for chunk in chunks:
                    self.assertTrue(chunk)
                    self.assertLessEqual(len(chunk.encode("utf-16-le")) // 2, 4000)
        self.assertEqual(split_chunks("x" * 3999 + "😀"), ["x" * 3999, "😀"])
        self.assertEqual(split_chunks(""), [])

    async def test_success_returns_persistable_message_id_without_parse_mode(self) -> None:
        sender, bot = self.sender()
        delivery = await sender.send(7, "<literal>", 1)
        self.assertEqual((delivery.status, delivery.message_id, delivery.code, delivery.retry_after),
                         ("SENT", 91, None, 0))
        bot.send_message.assert_awaited_once_with(7, "<literal>", parse_mode=None)

    async def test_rate_limit_pending_and_retry_exhaustion(self) -> None:
        method = SendMessage(chat_id=7, text="hello")
        for attempts, retry, status, code, delay in (
            (1, 0, "PENDING", "TELEGRAM_RATE_LIMIT", 1),
            (4, 17, "PENDING", "TELEGRAM_RATE_LIMIT", 17),
            (5, 17, "FAILED", "TELEGRAM_RATE_LIMIT_EXHAUSTED", 0)):
            with self.subTest(attempts=attempts, retry=retry):
                sender, bot = self.sender(TelegramRetryAfter(method, "dummy-secret", retry))
                delivery = await sender.send(7, "hello", attempts)
                self.assertEqual((delivery.status, delivery.code, delivery.retry_after), (status, code, delay))
                self.assertIsNone(delivery.message_id)
                bot.send_message.assert_awaited_once()

    async def test_server_failure_has_bounded_attempts(self) -> None:
        method = SendMessage(chat_id=7, text="hello")
        for attempts, status, code, delay in ((1, "PENDING", "TELEGRAM_SERVER", 5),
            (2, "PENDING", "TELEGRAM_SERVER", 10),
            (3, "FAILED", "TELEGRAM_SERVER_EXHAUSTED", 0)):
            with self.subTest(attempts=attempts):
                sender, bot = self.sender(TelegramServerError(method, "dummy-secret"))
                delivery = await sender.send(7, "hello", attempts)
                self.assertEqual((delivery.status, delivery.code, delivery.retry_after), (status, code, delay))
                bot.send_message.assert_awaited_once()

    async def test_network_and_timeout_are_unknown_without_retry(self) -> None:
        method = SendMessage(chat_id=7, text="hello")
        for error in (TelegramNetworkError(method, "dummy-secret"), asyncio.TimeoutError("dummy-secret")):
            with self.subTest(error_type=type(error).__name__):
                sender, bot = self.sender(error)
                delivery = await sender.send(7, "hello", 1)
                self.assertEqual((delivery.status, delivery.code), ("UNKNOWN", "TELEGRAM_TRANSPORT_UNKNOWN"))
                self.assertEqual(delivery.retry_after, 0)
                self.assertIsNone(delivery.message_id)
                bot.send_message.assert_awaited_once()

    async def test_fatal_api_errors_are_safe_and_terminal(self) -> None:
        method = SendMessage(chat_id=7, text="hello")
        for error_type in (TelegramBadRequest, TelegramForbiddenError, TelegramUnauthorizedError):
            with self.subTest(error_type=error_type.__name__):
                sender, bot = self.sender(error_type(method, "dummy-secret"))
                delivery = await sender.send(7, "hello", 1)
                self.assertEqual((delivery.status, delivery.code), ("FAILED", "TELEGRAM_REJECTED"))
                self.assertNotIn("dummy-secret", repr(delivery))
                bot.send_message.assert_awaited_once()
