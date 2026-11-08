'''Minimal Telegram intake tests without network or database access.'''

import asyncio
import unittest
from contextlib import suppress
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch
from uuid import uuid4

from aiogram.types import Message, Update

import app.channels.telegram.handlers as handlers
from app.channels.telegram.config import TelegramSettings
from app.channels.telegram.handlers import Intake
from app.channels.telegram.sender import Sender, split_chunks
from app.chat.service import VERIFIED, ChatService, Identity, TenantOption


def message_fixture(
    text: str | None = 'hello',
    *,
    user_id: int = 7,
    chat_type: str = 'private',
    is_bot: bool = False,
    has_user: bool = True,
    first_name: str = 'First',
    last_name: str | None = 'Last',
    username: str | None = 'handle',
) -> Message:
    user = None
    if has_user:
        user = {
            'id': user_id,
            'is_bot': is_bot,
            'first_name': first_name,
            'last_name': last_name,
            'username': username,
        }
    return Message.model_validate({
        'message_id': 21,
        'date': datetime(2026, 10, 13, tzinfo=timezone.utc),
        'chat': {'id': 71, 'type': chat_type},
        'from': user,
        'text': text,
    })


def delivery(status: str, retry_after: int = 0) -> SimpleNamespace:
    return SimpleNamespace(status=status, retry_after=retry_after)


TENANT_ID = uuid4()


class IntakeTests(unittest.IsolatedAsyncioTestCase):
    def make_intake(
        self, allowed: frozenset[int] = frozenset({7})
    ) -> tuple[Intake, Mock, Mock]:
        chat = Mock(spec=ChatService)
        # A verified user: the tenant is resolved server-side before chat.handle.
        chat.identify = AsyncMock(return_value=Identity(
            VERIFIED, TENANT_ID, 'CASHIER', (TenantOption(TENANT_ID, 'Kopi Senja'),)
        ))
        chat.handle = AsyncMock(return_value='ok')
        sender = Mock(spec=Sender)
        sender.send = AsyncMock(return_value=delivery('SENT'))
        settings = TelegramSettings('dummy-token', allowed)
        return Intake(settings, chat, sender), chat, sender

    async def test_access_checks_precede_chat_and_sender_for_all_kinds(self) -> None:
        for text in ('hello', '/start payload', '/new', '/session', '/unknown', None):
            for fields in (
                {'user_id': 8},
                {'chat_type': 'group'},
                {'is_bot': True},
                {'has_user': False},
            ):
                with self.subTest(text=text, fields=fields):
                    intake, chat, sender = self.make_intake()
                    await intake.handle(message_fixture(text, **fields), Update(update_id=301))
                    chat.identify.assert_not_awaited()
                    chat.handle.assert_not_awaited()
                    sender.send.assert_not_awaited()
                    self.assertTrue(intake.healthy)
                    self.assertFalse(intake.failed.is_set())

    async def test_empty_allowlist_accepts_private_human_and_normalizes_ids(self) -> None:
        intake, chat, sender = self.make_intake(frozenset())
        await intake.handle(message_fixture(user_id=88), Update(update_id=301))
        chat.handle.assert_awaited_once_with(
            tenant_id=TENANT_ID,
            external_user_id='88',
            external_chat_id='71',
            external_message_id='21',
            username='handle',
            first_name='First Last',
            kind='TEXT',
            content='hello',
        )
        sender.send.assert_awaited_once_with(71, 'ok', 1)

    async def test_commands_profile_and_text_normalization(self) -> None:
        cases = (
            (' /START@UnitBot deep-link ', 'START', '/start'),
            ('/NEW', 'NEW', '/new'),
            ('/session@UnitBot', 'SESSION', '/session'),
            ('/unknown argument', 'UNSUPPORTED', '/unknown'),
            (' hello ', 'TEXT', 'hello'),
        )
        for text, kind, content in cases:
            with self.subTest(text=text):
                intake, chat, sender = self.make_intake()
                await intake.process(message_fixture(text))
                self.assertEqual(chat.handle.await_args.kwargs['kind'], kind)
                self.assertEqual(chat.handle.await_args.kwargs['content'], content)
                self.assertEqual(chat.handle.await_args.kwargs['first_name'], 'First Last')
                sender.send.assert_awaited_once()

    async def test_missing_empty_and_oversized_text_is_invalid(self) -> None:
        cases = (None, '  ', 'x' * 4001, '/' + 'x' * 4000)
        for text in cases:
            with self.subTest(length=0 if text is None else len(text)):
                intake, chat, sender = self.make_intake()
                await intake.process(message_fixture(text))
                self.assertEqual(chat.handle.await_args.kwargs['kind'], 'INVALID')
                self.assertEqual(chat.handle.await_args.kwargs['content'], '')
                sender.send.assert_awaited_once()
        intake, chat, sender = self.make_intake()
        await intake.process(message_fixture('x' * 4000))
        self.assertEqual(chat.handle.await_args.kwargs['kind'], 'TEXT')
        self.assertEqual(chat.handle.await_args.kwargs['content'], 'x' * 4000)

    async def test_duplicate_response_skips_all_delivery(self) -> None:
        intake, chat, sender = self.make_intake()
        chat.handle.return_value = None
        await intake.handle(message_fixture(), Update(update_id=301))
        chat.handle.assert_awaited_once()
        sender.send.assert_not_awaited()

    async def test_utf16_chunks_send_serially_with_integer_chat_id(self) -> None:
        intake, chat, sender = self.make_intake()
        answer = '😀' * 3000
        chunks = split_chunks(answer)
        self.assertGreater(len(chunks), 1)
        chat.handle.return_value = answer
        observed: list[tuple[int, str, int]] = []

        async def send(chat_id: int, chunk: str, attempt: int) -> SimpleNamespace:
            observed.append((chat_id, chunk, attempt))
            return delivery('SENT')

        sender.send.side_effect = send
        await intake.process(message_fixture())
        self.assertEqual(observed, [(71, chunk, 1) for chunk in chunks])
        self.assertEqual(''.join(chunk for _, chunk, _ in observed), answer)

    async def test_pending_retries_are_bounded_and_stop_tail(self) -> None:
        intake, chat, sender = self.make_intake()
        answer = '😀' * 3000
        first_chunk = split_chunks(answer)[0]
        chat.handle.return_value = answer
        sender.send.side_effect = [delivery('PENDING', 2)] * 3
        waits: list[int] = []

        async def fake_sleep(seconds: int) -> None:
            waits.append(seconds)

        proxy = SimpleNamespace(
            Event=asyncio.Event,
            CancelledError=asyncio.CancelledError,
            Future=asyncio.Future,
            sleep=fake_sleep,
        )
        with patch.object(handlers, 'asyncio', proxy):
            await intake.process(message_fixture())
        self.assertEqual(waits, [2, 2])
        self.assertEqual(
            [call.args for call in sender.send.await_args_list],
            [(71, first_chunk, 1), (71, first_chunk, 2), (71, first_chunk, 3)],
        )

    async def test_unknown_and_failed_delivery_stop_tail_without_retry(self) -> None:
        for status in ('UNKNOWN', 'FAILED'):
            with self.subTest(status=status):
                intake, chat, sender = self.make_intake()
                chat.handle.return_value = '😀' * 3000
                sender.send.return_value = delivery(status)
                await intake.process(message_fixture())
                sender.send.assert_awaited_once()
                self.assertEqual(sender.send.await_args.args[2], 1)

    async def test_fatal_handler_failure_signals_and_suspends_until_cancelled(self) -> None:
        intake, chat, sender = self.make_intake()
        chat.handle.side_effect = RuntimeError('dummy-secret')
        task: asyncio.Task[None] | None = None
        with self.assertLogs(handlers.logger, level='ERROR') as logs:
            try:
                task = asyncio.create_task(intake.handle(message_fixture(), Update(update_id=301)))
                await asyncio.wait_for(intake.failed.wait(), 1)
                self.assertFalse(task.done())
                self.assertFalse(intake.healthy)
                sender.send.assert_not_awaited()
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await asyncio.wait_for(task, 1)
            finally:
                if task is not None:
                    if not task.done():
                        task.cancel()
                    with suppress(asyncio.CancelledError):
                        await task
        self.assertEqual(len(logs.output), 1)
        self.assertNotIn('dummy-secret', ' '.join(logs.output))

    async def test_cancellation_propagates_without_fatal_signal(self) -> None:
        intake, chat, sender = self.make_intake()
        entered = asyncio.Event()

        async def blocked_chat(**kwargs: object) -> str:
            entered.set()
            await asyncio.Event().wait()
            return 'unreachable'

        chat.handle.side_effect = blocked_chat
        task = asyncio.create_task(intake.handle(message_fixture(), Update(update_id=301)))
        try:
            await asyncio.wait_for(entered.wait(), 1)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 1)
            self.assertFalse(intake.failed.is_set())
            sender.send.assert_not_awaited()
        finally:
            if not task.done():
                task.cancel()
            with suppress(asyncio.CancelledError):
                await task
