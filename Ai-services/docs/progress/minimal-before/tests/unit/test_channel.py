"""Owned lifecycle tests, including native aiogram polling child teardown."""
import asyncio
import logging
import unittest
from contextlib import suppress
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from aiogram import Bot, Dispatcher
from aiogram.exceptions import TelegramNetworkError
from aiogram.methods import GetMe
import app.channels.telegram.bot as channel_module
from app.channels.telegram.bot import TelegramChannel, await_owned, start_telegram_channel
from app.channels.telegram.handlers import Intake
from app.chat.worker import Worker
from app.core.database import Database


class ChannelTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        aiogram_logger = logging.getLogger("aiogram")
        engine_logger = logging.getLogger("sqlalchemy.engine")
        self.addCleanup(aiogram_logger.setLevel, aiogram_logger.level)
        self.addCleanup(setattr, engine_logger, "disabled", engine_logger.disabled)
        self.bot = Mock(spec=Bot)
        self.bot.id = 71
        self.bot.session = SimpleNamespace(close=AsyncMock())
        self.bot.get_me = AsyncMock()
        self.bot.get_webhook_info = AsyncMock(return_value=SimpleNamespace(url=""))
        self.database = Mock(spec=Database)
        self.database.owned = True
        self.database.ping = AsyncMock(return_value=True)
        self.database.close = AsyncMock()
        self.database.acquire = AsyncMock()
        self.intake = Mock(spec=Intake)
        self.intake.failed = asyncio.Event()
        self.intake.healthy = True
        self.worker = Mock(spec=Worker)
        self.worker.healthy = True
        self.worker.stopping = asyncio.Event()
        self.worker.run = AsyncMock()
        self.dispatcher = Dispatcher(disable_fsm=True)
        self.channel = TelegramChannel(self.bot, self.dispatcher, self.database, self.intake, self.worker)

    async def finish_task(self, task: asyncio.Task[None]) -> None:
        if not task.done():
            task.cancel()
        with suppress(asyncio.CancelledError):
            await task

    async def test_await_owned_cancellation_waits_for_cleanup(self) -> None:
        entered, release, finished = asyncio.Event(), asyncio.Event(), asyncio.Event()

        async def cleanup() -> None:
            entered.set()
            await release.wait()
            finished.set()

        owned = asyncio.create_task(cleanup())
        caller = asyncio.create_task(await_owned(owned))
        try:
            await asyncio.wait_for(entered.wait(), 1)
            caller.cancel()
            await asyncio.sleep(0)
            self.assertFalse(caller.done())
            self.assertFalse(owned.cancelled())
            release.set()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(caller, 1)
            self.assertTrue(finished.is_set())
        finally:
            release.set()
            await self.finish_task(caller)
            await self.finish_task(owned)

    async def test_native_dispatcher_stop_awaits_polling_child_finally(self) -> None:
        entered, child_closed = asyncio.Event(), asyncio.Event()

        async def polling_child(**kwargs: object) -> None:
            self.assertIs(kwargs["bot"], self.bot)
            self.assertFalse(kwargs["handle_as_tasks"])
            entered.set()
            try:
                await asyncio.Event().wait()
            finally:
                child_closed.set()

        # Only the SDK network polling child is replaced. Real start_polling,
        # stop_polling, startup observers, running lock and task teardown run.
        with patch.object(self.dispatcher, "_polling", side_effect=polling_child):
            runner = asyncio.create_task(self.channel.run_poller())
            self.channel.polling_task = runner
            try:
                await asyncio.wait_for(entered.wait(), 1)
                self.assertTrue(self.channel.polling_started.is_set())
                await asyncio.wait_for(self.channel.stop_poller(), 1)
                self.assertTrue(child_closed.is_set())
                self.assertTrue(runner.done())
                self.assertTrue(self.channel.polling_finished.is_set())
                self.bot.session.close.assert_not_awaited()
            finally:
                await self.finish_task(runner)
                if self.channel.poller_stop_task is not None:
                    await self.finish_task(self.channel.poller_stop_task)

    async def test_stop_before_startup_waits_for_observation(self) -> None:
        release, entered = asyncio.Event(), asyncio.Event()
        dispatcher = Mock(spec=Dispatcher)
        dispatcher.startup = SimpleNamespace(register=Mock())
        dispatcher.stop_polling = AsyncMock(side_effect=release.set)
        channel = TelegramChannel(self.bot, dispatcher, self.database, self.intake, self.worker)

        async def runner_body() -> None:
            entered.set()
            await release.wait()
            channel.polling_finished.set()

        runner = asyncio.create_task(runner_body())
        channel.polling_task = runner
        stop = asyncio.create_task(channel.stop_poller())
        try:
            await asyncio.wait_for(entered.wait(), 1)
            await asyncio.sleep(0)
            dispatcher.stop_polling.assert_not_awaited()
            self.assertFalse(runner.cancelled())
            await channel.observe_startup(self.bot)
            await asyncio.wait_for(stop, 1)
            dispatcher.stop_polling.assert_awaited_once()
        finally:
            release.set()
            await self.finish_task(stop)
            await self.finish_task(runner)
            if channel.poller_stop_task is not None:
                await self.finish_task(channel.poller_stop_task)

    async def test_finished_and_failed_runner_stop_without_sdk_stop(self) -> None:
        for failed in (False, True):
            with self.subTest(failed=failed):
                async def finished_runner() -> None:
                    if failed:
                        raise RuntimeError("unit-failure")

                runner = asyncio.create_task(finished_runner())
                await asyncio.wait({runner}, timeout=1)
                channel = TelegramChannel(self.bot, self.dispatcher, self.database, self.intake, self.worker)
                channel.polling_task = runner
                with patch.object(self.dispatcher, "stop_polling", new=AsyncMock()) as sdk_stop:
                    await asyncio.wait_for(channel.stop_poller(), 1)
                    sdk_stop.assert_not_awaited()
                self.assertTrue(runner.done())
                if channel.poller_stop_task is not None:
                    await channel.poller_stop_task

    async def test_repeated_stop_shares_cleanup_and_cancelled_worker_still_closes(self) -> None:
        async def waiting_worker() -> None:
            await asyncio.Event().wait()

        task = asyncio.create_task(waiting_worker())
        self.channel.worker_task = task
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
        try:
            await asyncio.wait_for(asyncio.gather(self.channel.stop(), self.channel.stop()), 1)
            cleanup = self.channel.cleanup_task
            await self.channel.stop()
            self.assertIs(self.channel.cleanup_task, cleanup)
            self.assertTrue(self.worker.stopping.is_set())
            self.bot.session.close.assert_awaited_once()
            self.database.close.assert_awaited_once()
        finally:
            await self.finish_task(task)
            if self.channel.cleanup_task is not None:
                await self.finish_task(self.channel.cleanup_task)
            if self.channel.poller_stop_task is not None:
                await self.finish_task(self.channel.poller_stop_task)

    async def test_readiness_requires_live_healthy_owned_tasks(self) -> None:
        async def waiting() -> None:
            await asyncio.Event().wait()

        tasks = [asyncio.create_task(waiting()) for _ in range(3)]
        self.channel.polling_task, self.channel.worker_task, self.channel.supervisor_task = tasks
        self.channel.polling_started.set()
        try:
            self.assertTrue(await self.channel.ready())
            for target, attribute in ((self.channel, "closing"), (self.channel, "failed"),
                                      (self.database, "owned"), (self.intake, "healthy"), (self.worker, "healthy")):
                original = getattr(target, attribute)
                setattr(target, attribute, not original)
                self.assertFalse(await self.channel.ready())
                setattr(target, attribute, original)
            self.database.ping.side_effect = asyncio.TimeoutError()
            self.assertFalse(await self.channel.ready())
            self.database.ping.side_effect = None
            await self.finish_task(tasks[0])
            self.assertFalse(await self.channel.ready())
        finally:
            for task in tasks:
                await self.finish_task(task)

    async def test_supervised_worker_failure_stops_poller(self) -> None:
        polling_release = asyncio.Event()

        async def polling() -> None:
            await polling_release.wait()

        async def failed_worker() -> None:
            raise RuntimeError("dummy-secret")

        async def stop_poller() -> None:
            polling_release.set()

        polling_task = asyncio.create_task(polling())
        worker_task = asyncio.create_task(failed_worker())
        self.channel.polling_task = polling_task
        self.channel.worker_task = worker_task
        try:
            with patch.object(self.channel, "stop_poller", new=AsyncMock(side_effect=stop_poller)) as stop:
                with self.assertLogs(channel_module.logger, level="ERROR") as logs:
                    await asyncio.wait_for(self.channel.supervise(), 1)
                self.assertTrue(self.channel.failed)
                stop.assert_awaited_once()
                self.assertNotIn("dummy-secret", " ".join(logs.output))
        finally:
            polling_release.set()
            await self.finish_task(polling_task)
            with suppress(RuntimeError):
                await self.finish_task(worker_task)

    async def test_startup_webhook_rejection_and_sdk_failure_close_fake_resources(self) -> None:
        settings = SimpleNamespace(telegram=SimpleNamespace(token="dummy-token", service_scope="unit"),
                                   database=SimpleNamespace(url="dummy-url"))
        for mode in ("webhook", "network"):
            with self.subTest(mode=mode):
                self.bot.session.close.reset_mock()
                self.database.close.reset_mock()
                self.bot.get_me.side_effect = None
                self.bot.get_webhook_info.return_value = SimpleNamespace(url="https://invalid.example/hook")
                if mode == "network":
                    self.bot.get_me.side_effect = TelegramNetworkError(GetMe(), "dummy-secret")
                with patch.object(channel_module, "Bot", return_value=self.bot), patch.object(
                    channel_module, "Database", return_value=self.database):
                    with self.assertRaises(RuntimeError) as caught:
                        await asyncio.wait_for(start_telegram_channel(settings, Mock()), 1)
                self.assertNotIn("dummy-secret", str(caught.exception))
                self.database.acquire.assert_not_awaited()
                self.bot.session.close.assert_awaited_once()
                self.database.close.assert_awaited_once()

    async def test_startup_cancellation_closes_fake_resources_before_propagation(self) -> None:
        entered = asyncio.Event()
        settings = SimpleNamespace(telegram=SimpleNamespace(token="dummy-token", service_scope="unit"),
                                   database=SimpleNamespace(url="dummy-url"))

        async def blocked_get_me() -> None:
            entered.set()
            await asyncio.Event().wait()

        self.bot.get_me.side_effect = blocked_get_me
        with patch.object(channel_module, "Bot", return_value=self.bot), patch.object(
            channel_module, "Database", return_value=self.database):
            task = asyncio.create_task(start_telegram_channel(settings, Mock()))
            try:
                await asyncio.wait_for(entered.wait(), 1)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await asyncio.wait_for(task, 1)
                self.bot.session.close.assert_awaited_once()
                self.database.close.assert_awaited_once()
            finally:
                await self.finish_task(task)
