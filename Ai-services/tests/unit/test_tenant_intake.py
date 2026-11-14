"""Telegram intake identity flows (TASK-059) without network or database.

A phone is accepted ONLY from a contact the sender shared about themselves
(`contact.user_id == from_user.id`, not forwarded). Typed or forwarded numbers
never reach `verify_contact`; the tenant used by `chat.handle` always comes from
`chat.identify`, resolved again for every message."""

import logging
import unittest
from datetime import datetime, timezone
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock, Mock
from uuid import uuid4

from aiogram.types import Message, ReplyKeyboardMarkup, ReplyKeyboardRemove

import app.channels.telegram.handlers as handlers
from app.channels.telegram.config import TelegramSettings
from app.channels.telegram.handlers import Intake
from app.channels.telegram.sender import Sender
from app.chat.service import (
    CHOOSE,
    FALLBACK,
    INVALID_CHOICE,
    LISTED,
    NOT_REGISTERED,
    REVOKED,
    UNVERIFIED,
    VERIFIED,
    ChatService,
    Identity,
    TenantOption,
)

USER_ID = 7
RAW_PHONE = "+62 812-3456-7890"
PHONE = "6281234567890"
TENANT_A = uuid4()
TENANT_B = uuid4()
OPTIONS = (TenantOption(TENANT_A, "Kopi Senja"), TenantOption(TENANT_B, None))


def message(
    text: str | None = None,
    *,
    contact: dict[str, Any] | None = None,
    forwarded: bool = False,
    message_id: int = 21,
) -> Message:
    payload: dict[str, Any] = {
        "message_id": message_id,
        "date": datetime(2026, 11, 7, tzinfo=timezone.utc),
        "chat": {"id": 71, "type": "private"},
        "from": {"id": USER_ID, "is_bot": False, "first_name": "First", "username": "handle"},
        "text": text,
    }
    if contact is not None:
        payload["contact"] = contact
    if forwarded:
        payload["forward_origin"] = {
            "type": "user",
            "date": datetime(2026, 11, 6, tzinfo=timezone.utc),
            "sender_user": {"id": USER_ID, "is_bot": False, "first_name": "First"},
        }
    return Message.model_validate(payload)


def own_contact(phone: str = RAW_PHONE, user_id: int | None = USER_ID) -> dict[str, Any]:
    contact: dict[str, Any] = {"phone_number": phone, "first_name": "First"}
    if user_id is not None:
        contact["user_id"] = user_id
    return contact


class TenantIntakeTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.chat = Mock(spec=ChatService)
        self.chat.identify = AsyncMock(return_value=Identity(UNVERIFIED))
        self.chat.verify_contact = AsyncMock(return_value=Identity(NOT_REGISTERED))
        self.chat.choose_tenant = AsyncMock(return_value=Identity(UNVERIFIED))
        self.chat.handle = AsyncMock(return_value="jawaban")
        self.sender = Mock(spec=Sender)
        self.sender.send = AsyncMock(return_value=SimpleNamespace(status="SENT", retry_after=0))
        self.intake = Intake(TelegramSettings("dummy-token", frozenset({USER_ID})), self.chat, self.sender)

    def sent(self) -> list[tuple[str, object]]:
        return [
            (call.args[1], call.kwargs.get("reply_markup"))
            for call in self.sender.send.await_args_list
        ]

    def assert_contact_keyboard(self, markup: object) -> None:
        self.assertIsInstance(markup, ReplyKeyboardMarkup)
        button = markup.keyboard[0][0]  # type: ignore[union-attr]
        self.assertTrue(button.request_contact)
        self.assertEqual(button.text, handlers.SHARE_CONTACT_BUTTON)

    # --- contact acceptance --------------------------------------------------------------

    async def test_own_contact_is_normalized_and_verified(self) -> None:
        self.chat.verify_contact.return_value = Identity(VERIFIED, TENANT_A, "CASHIER", OPTIONS[:1])
        with self.assertLogs(handlers.logger, level=logging.DEBUG) as logs:
            await self.intake.process(message(contact=own_contact()))
        # The trace is visible, but never carries the phone number or the sender's name.
        # Structured data lives in `extra`, so scan every record attribute as well.
        scanned = [" ".join(logs.output)]
        for record in logs.records:
            scanned.append(record.getMessage())
            scanned.extend(str(value) for value in record.__dict__.values())
        joined_logs = " ".join(scanned)
        self.assertNotIn(PHONE, joined_logs)
        self.assertNotIn(RAW_PHONE, joined_logs)
        self.assertNotIn("First", joined_logs)
        self.chat.verify_contact.assert_awaited_once_with(
            external_user_id=str(USER_ID), username="handle", first_name="First",
            phone_normalized=PHONE,
        )
        self.chat.identify.assert_not_awaited()
        self.chat.handle.assert_not_awaited()
        [(text, markup)] = self.sent()
        self.assertIn("Verifikasi berhasil", text)
        self.assertIn("Kopi Senja", text)
        self.assertNotIn(PHONE, text)
        self.assertIsInstance(markup, ReplyKeyboardRemove)

    async def test_foreign_contact_is_rejected_without_any_lookup(self) -> None:
        cases = (
            {"contact": own_contact(user_id=USER_ID + 1)},
            {"contact": own_contact(user_id=None)},
            {"contact": own_contact(), "forwarded": True},
        )
        for kwargs in cases:
            with self.subTest(contact_user=kwargs["contact"].get("user_id"), forwarded=kwargs.get("forwarded", False)):
                self.sender.send.reset_mock()
                await self.intake.process(message(**kwargs))
                [(text, markup)] = self.sent()
                self.assertEqual(text, handlers.CONTACT_REJECTED)
                self.assert_contact_keyboard(markup)
        self.chat.verify_contact.assert_not_awaited()
        self.chat.identify.assert_not_awaited()
        self.chat.handle.assert_not_awaited()

    async def test_typed_phone_number_is_ignored_for_verification(self) -> None:
        for typed in ("0812-3456-7890", "+6281234567890", "nomor saya 081234567890"):
            with self.subTest(typed=typed):
                self.sender.send.reset_mock()
                await self.intake.process(message(typed))
                [(text, markup)] = self.sent()
                self.assertEqual(text, handlers.ASK_CONTACT)
                self.assert_contact_keyboard(markup)
        self.chat.verify_contact.assert_not_awaited()
        self.chat.handle.assert_not_awaited()
        self.assertEqual(self.chat.identify.await_count, 3)

    async def test_own_contact_with_invalid_phone(self) -> None:
        await self.intake.process(message(contact=own_contact("12ab")))
        self.chat.verify_contact.assert_not_awaited()
        [(text, markup)] = self.sent()
        self.assertEqual(text, handlers.PHONE_INVALID)
        self.assert_contact_keyboard(markup)

    async def test_unregistered_number_gets_no_hint(self) -> None:
        await self.intake.process(message(contact=own_contact()))
        [(text, markup)] = self.sent()
        self.assertEqual(text, handlers.NOT_REGISTERED_TEXT)
        self.assertNotIn(PHONE, text)
        self.assert_contact_keyboard(markup)

    async def test_many_matches_list_tenant_names_only(self) -> None:
        self.chat.verify_contact.return_value = Identity(CHOOSE, options=OPTIONS)
        await self.intake.process(message(contact=own_contact()))
        [(text, markup)] = self.sent()
        self.assertIn("1. Kopi Senja", text)
        self.assertIn(f"2. {handlers.UNNAMED_TENANT}", text)
        self.assertIn("/tenant", text)
        for hidden in (str(TENANT_A), str(TENANT_B), PHONE):
            self.assertNotIn(hidden, text)
        self.assertIsInstance(markup, ReplyKeyboardRemove)

    # --- unverified, choice pending, revoked ---------------------------------------------

    async def test_unverified_commands_and_text_ask_for_contact(self) -> None:
        for text in ("/start", "/new", "/session", "halo", None):
            with self.subTest(text=text):
                self.sender.send.reset_mock()
                await self.intake.process(message(text))
                [(reply, markup)] = self.sent()
                self.assertEqual(reply, handlers.ASK_CONTACT)
                self.assert_contact_keyboard(markup)
        self.chat.handle.assert_not_awaited()

    async def test_pending_choice_and_revocation_block_chat(self) -> None:
        self.chat.identify.return_value = Identity(CHOOSE, options=OPTIONS)
        await self.intake.process(message("halo"))
        self.assertIn("1. Kopi Senja", self.sent()[0][0])
        self.chat.identify.return_value = Identity(REVOKED)
        await self.intake.process(message("halo"))
        text, markup = self.sent()[1]
        self.assertEqual(text, handlers.REVOKED_TEXT)
        self.assert_contact_keyboard(markup)
        self.chat.handle.assert_not_awaited()

    # --- /tenant ---------------------------------------------------------------------------

    async def test_tenant_command_parses_choice_server_side(self) -> None:
        cases = (("/tenant", None), ("/tenant 2", 2), ("/TENANT@UnitBot 1", 1),
                 ("/tenant abc", 0), ("/tenant 99999", 0), (f"/tenant {TENANT_B}", 0))
        for text, choice in cases:
            with self.subTest(text=text):
                self.chat.choose_tenant.reset_mock()
                await self.intake.process(message(text))
                self.chat.choose_tenant.assert_awaited_once_with(
                    external_user_id=str(USER_ID), username="handle", first_name="First",
                    choice=choice,
                )
        self.chat.identify.assert_not_awaited()
        self.chat.handle.assert_not_awaited()

    async def test_tenant_replies(self) -> None:
        self.chat.choose_tenant.return_value = Identity(VERIFIED, TENANT_B, "CASHIER", OPTIONS)
        await self.intake.process(message("/tenant 2"))
        self.chat.choose_tenant.return_value = Identity(LISTED, TENANT_A, "CASHIER", OPTIONS)
        await self.intake.process(message("/tenant"))
        self.chat.choose_tenant.return_value = Identity(INVALID_CHOICE, options=OPTIONS)
        await self.intake.process(message("/tenant 5"))
        switched, listed, invalid = (text for text, _ in self.sent())
        self.assertIn(f"Usaha aktif sekarang: {handlers.UNNAMED_TENANT}", switched)
        self.assertIn("1. Kopi Senja (aktif)", listed)
        self.assertNotIn(f"{handlers.UNNAMED_TENANT} (aktif)", listed)
        self.assertTrue(invalid.startswith(handlers.INVALID_CHOICE_TEXT))

    # --- per-message tenant resolution ---------------------------------------------------

    async def test_every_message_uses_the_tenant_resolved_for_it_including_the_fallback(self) -> None:
        self.chat.identify.side_effect = [
            Identity(VERIFIED, TENANT_A, "CASHIER", OPTIONS),
            Identity(VERIFIED, TENANT_B, "OWNERMERCHANT", OPTIONS),
        ]
        await self.intake.process(message(f"tenant_id={TENANT_B} tolong", message_id=1))
        await self.intake.process(message(f"tenant_id={TENANT_A} tolong", message_id=2))
        tenants = [call.kwargs["tenant_id"] for call in self.chat.handle.await_args_list]
        self.assertEqual(tenants, [TENANT_A, TENANT_B])
        self.assertEqual(self.chat.identify.await_count, 2)
        self.assertEqual([text for text, _ in self.sent()], ["jawaban", "jawaban"])

        # development fallback tenant reaches chat
        fallback = uuid4()
        self.chat.identify.side_effect = None
        self.chat.identify.return_value = Identity(FALLBACK, fallback)
        await self.intake.process(message("halo", message_id=3))
        self.assertEqual(self.chat.handle.await_args.kwargs["tenant_id"], fallback)


class SenderMarkupTests(unittest.IsolatedAsyncioTestCase):
    async def test_keyboard_is_forwarded_also_on_plain_text_fallback(self) -> None:
        from aiogram import Bot
        from aiogram.exceptions import TelegramBadRequest
        from aiogram.methods import SendMessage

        bot = Mock(spec=Bot)
        bot.send_message = AsyncMock(side_effect=[
            TelegramBadRequest(SendMessage(chat_id=7, text="x"), "can't parse entities"),
            SimpleNamespace(message_id=93),
        ])
        keyboard = handlers.contact_keyboard()
        delivery = await Sender(bot).send(7, "x", 1, reply_markup=keyboard)
        self.assertEqual(delivery.status, "SENT")
        self.assertEqual(
            [call.kwargs for call in bot.send_message.await_args_list],
            [{"parse_mode": "Markdown", "reply_markup": keyboard},
             {"parse_mode": None, "reply_markup": keyboard}],
        )


if __name__ == "__main__":
    unittest.main()
