"""Real intake logic over transaction-boundary mocks, with no database engine."""
import asyncio
import unittest
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager, suppress
from datetime import datetime, timezone
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from aiogram.types import Message, Update
from sqlalchemy.exc import OperationalError
import app.channels.telegram.handlers as handlers
from app.channels.telegram.config import TelegramSettings
from app.chat.repositories import Repository
from app.core.database import Database, OwnershipLost


class TransactionBoundary:
    def __init__(self) -> None:
        self.connection = object()
        self.events: list[str] = []
        self.active = False

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[object]:
        self.events.append("begin")
        self.active = True
        try:
            yield self.connection
        except BaseException:
            self.events.append("rollback")
            raise
        else:
            self.events.append("commit")
        finally:
            self.active = False


class CommitExitBoundary(TransactionBoundary):
    def __init__(self, errors: list[Exception]) -> None:
        super().__init__()
        self.errors = list(errors)

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[object]:
        self.events.append("begin")
        self.active = True
        try:
            yield self.connection
            if self.errors:
                raise self.errors.pop(0)
        except BaseException:
            self.events.append("rollback")
            raise
        else:
            self.events.append("commit")
        finally:
            self.active = False


class DatabaseAbort(Exception):
    def __init__(self, sqlstate: str) -> None:
        super().__init__("dummy-secret")
        self.sqlstate = sqlstate


def message_fixture(text: str | None = "hello", user_id: int = 7,
                    chat_type: str = "private", is_bot: bool = False,
                    first_name: str = "First", last_name: str | None = "Last",
                    username: str | None = "handle", has_user: bool = True) -> Message:
    user = None
    if has_user:
        user = {"id": user_id, "is_bot": is_bot, "first_name": first_name,
                "last_name": last_name, "username": username}
    return Message.model_validate({"message_id": 21, "date": datetime(2026, 10, 13, tzinfo=timezone.utc),
        "chat": {"id": 7, "type": chat_type}, "from": user, "text": text})


class IntakeTests(unittest.IsolatedAsyncioTestCase):
    def intake(self, allowed: frozenset[int] = frozenset({7})) -> tuple[handlers.Intake, Mock, Mock, TransactionBoundary]:
        boundary = TransactionBoundary()
        database = Mock(spec=Database)
        database.owned = True
        database.transaction = Mock(side_effect=boundary.transaction)
        repository = Mock(spec=Repository)
        repository.intake = AsyncMock()
        intake = handlers.Intake(TelegramSettings("dummy-token", allowed, "unit"), database, repository)
        return intake, database, repository, boundary

    async def test_access_checks_cover_every_command(self) -> None:
        for text in ("hello", "/start payload", "/new", "/session", "/unknown"):
            cases = [{"user_id": 8}, {"chat_type": "group"}, {"is_bot": True}, {"has_user": False}]
            for fields in cases:
                with self.subTest(text=text, fields=fields):
                    intake, database, repository, boundary = self.intake()
                    await intake.handle(message_fixture(text, **fields), Update(update_id=301))
                    repository.intake.assert_not_awaited()
                    database.transaction.assert_not_called()
                    self.assertTrue(intake.healthy)

    async def test_empty_allowlist_accepts_private_human(self) -> None:
        intake, database, repository, boundary = self.intake(frozenset())
        await intake.handle(message_fixture(user_id=88), Update(update_id=301))
        self.assertEqual(repository.intake.await_args.args[1], 88)
        self.assertEqual(boundary.events, ["begin", "commit"])

    async def test_command_normalization_and_deep_link(self) -> None:
        for text, kind, content in ((" /START@UnitBot deep-link ", "START", "/start"),
            ("/NEW", "NEW", "/new"), ("/session@UnitBot", "SESSION", "/session"),
            ("/unknown argument", "UNSUPPORTED", "/unknown")):
            with self.subTest(text=text):
                intake, database, repository, boundary = self.intake()
                await intake.handle(message_fixture(text), Update(update_id=301))
                self.assertEqual(repository.intake.await_args.args[-2:], (kind, content))
                self.assertEqual(boundary.events, ["begin", "commit"])

    async def test_mutable_profile_and_fallback_names(self) -> None:
        intake, database, repository, boundary = self.intake()
        for first, last, username, display in (("First", "Last", "old", "First Last"),
            ("Changed", None, "new", "Changed"), ("", None, "fallback", "fallback"),
            ("", None, None, "User 7")):
            with self.subTest(display=display):
                await intake.handle(message_fixture(first_name=first, last_name=last, username=username),
                                    Update(update_id=301))
                self.assertEqual(repository.intake.await_args.args[1:6], (7, username, first, last, display))
        self.assertEqual(repository.intake.await_count, 4)

    async def test_input_cap_and_empty_inputs(self) -> None:
        for text, expected in ((None, None), ("  ", None), ("x" * 4000, ("TEXT", "x" * 4000)),
                               ("x" * 4001, ("INVALID", ""))):
            with self.subTest(length=0 if text is None else len(text)):
                intake, database, repository, boundary = self.intake()
                await intake.handle(message_fixture(text), Update(update_id=301))
                if expected is None:
                    repository.intake.assert_not_awaited()
                    database.transaction.assert_not_called()
                else:
                    self.assertEqual(repository.intake.await_args.args[-2:], expected)

    async def test_injected_update_id_and_commit_before_handler_return(self) -> None:
        intake, database, repository, boundary = self.intake()
        observed: list[bool] = []

        async def capture(*args: object) -> None:
            observed.append(boundary.active)
            self.assertIs(args[0], boundary.connection)
            self.assertEqual(args[6:9], (98765, 7, 21))

        repository.intake.side_effect = capture
        await intake.handle(message_fixture(" hello "), Update(update_id=98765))
        self.assertEqual(observed, [True])
        self.assertFalse(boundary.active)
        self.assertEqual(boundary.events, ["begin", "commit"])
        self.assertEqual(repository.intake.await_args.args[-2:], ("TEXT", "hello"))

    async def test_transient_abort_retries_inside_handler(self) -> None:
        for sqlstate in ("40001", "40P01", "53300", "55P03", "57014"):
            with self.subTest(sqlstate=sqlstate):
                intake, database, repository, boundary = self.intake()
                repository.intake.side_effect = [OperationalError("unit", {}, DatabaseAbort(sqlstate)), None]
                sleeps: list[int] = []

                async def retry_sleep(delay: int) -> None:
                    sleeps.append(delay)
                    self.assertFalse(boundary.active)
                    self.assertFalse(intake.healthy)

                proxy = SimpleNamespace(Event=asyncio.Event, CancelledError=asyncio.CancelledError,
                                        Future=asyncio.Future, sleep=retry_sleep)
                with patch.object(handlers, "asyncio", proxy), self.assertLogs(handlers.logger, level="WARNING") as logs:
                    await asyncio.wait_for(intake.handle(message_fixture(), Update(update_id=301)), 1)
                self.assertEqual(sleeps, [1])
                self.assertEqual(repository.intake.await_count, 2)
                self.assertEqual(boundary.events, ["begin", "rollback", "begin", "commit"])
                self.assertTrue(intake.healthy)
                self.assertFalse(intake.failed.is_set())
                self.assertNotIn("dummy-secret", " ".join(logs.output))

    async def test_transient_commit_exit_retries_same_update_and_message(self) -> None:
        intake, database, repository, original_boundary = self.intake()
        boundary = CommitExitBoundary([OperationalError("unit", {}, DatabaseAbort("40001"))])
        database.transaction.side_effect = boundary.transaction
        sleeps: list[int] = []

        async def retry_sleep(delay: int) -> None:
            self.assertFalse(boundary.active)
            self.assertEqual(repository.intake.await_count, 1)
            sleeps.append(delay)

        proxy = SimpleNamespace(Event=asyncio.Event, CancelledError=asyncio.CancelledError,
                                Future=asyncio.Future, sleep=retry_sleep)
        with patch.object(handlers, "asyncio", proxy):
            await asyncio.wait_for(intake.handle(message_fixture(), Update(update_id=98765)), 1)
        self.assertEqual(sleeps, [1])
        self.assertEqual(repository.intake.await_count, 2)
        for call in repository.intake.await_args_list:
            self.assertIs(call.args[0], boundary.connection)
            self.assertEqual(call.args[6:9], (98765, 7, 21))
        self.assertEqual(boundary.events, ["begin", "rollback", "begin", "commit"])
        self.assertTrue(intake.healthy)
        self.assertFalse(intake.failed.is_set())

    async def test_unknown_commit_failure_and_owner_loss_suspend_safely(self) -> None:
        for error in (RuntimeError("dummy-secret"), OwnershipLost("dummy-secret")):
            with self.subTest(error_type=type(error).__name__):
                intake, database, repository, original_boundary = self.intake()
                boundary = CommitExitBoundary([error])
                database.transaction.side_effect = boundary.transaction
                task: asyncio.Task[None] | None = None
                with self.assertLogs(handlers.logger, level="ERROR") as logs:
                    try:
                        task = asyncio.create_task(intake.handle(message_fixture(), Update(update_id=98765)))
                        await asyncio.wait_for(intake.failed.wait(), 1)
                        self.assertFalse(task.done())
                        self.assertFalse(intake.healthy)
                        repository.intake.assert_awaited_once()
                        self.assertEqual(repository.intake.await_args.args[6:9], (98765, 7, 21))
                        self.assertEqual(boundary.events, ["begin", "rollback"])
                        task.cancel()
                        with self.assertRaises(asyncio.CancelledError):
                            await asyncio.wait_for(task, 1)
                    finally:
                        if task is not None:
                            if not task.done():
                                task.cancel()
                            with suppress(asyncio.CancelledError):
                                await task
                self.assertEqual(logs.output, [
                    "ERROR:app.channels.telegram.handlers:Telegram intake stopped: persistence invariant failed."])
                self.assertNotIn("dummy-secret", " ".join(logs.output))

    async def test_six_supported_aborts_have_bounded_backoff_outside_transactions(self) -> None:
        intake, database, repository, boundary = self.intake()
        errors: list[object] = []
        for sqlstate in ("40001", "40P01", "53300", "55P03", "57014", "40001"):
            errors.append(OperationalError("unit", {}, DatabaseAbort(sqlstate)))
        errors.append(None)
        repository.intake.side_effect = errors
        sleeps: list[int] = []

        async def retry_sleep(delay: int) -> None:
            self.assertFalse(boundary.active)
            self.assertFalse(intake.healthy)
            self.assertFalse(intake.failed.is_set())
            sleeps.append(delay)

        proxy = SimpleNamespace(Event=asyncio.Event, CancelledError=asyncio.CancelledError,
                                Future=asyncio.Future, sleep=retry_sleep)
        with patch.object(handlers, "asyncio", proxy):
            await asyncio.wait_for(intake.handle(message_fixture(), Update(update_id=98765)), 1)
        self.assertEqual(sleeps, [1, 2, 4, 8, 15, 15])
        self.assertEqual(repository.intake.await_count, 7)
        self.assertEqual(boundary.events.count("rollback"), 6)
        self.assertEqual(boundary.events[-1], "commit")
        for call in repository.intake.await_args_list:
            self.assertEqual(call.args[6:9], (98765, 7, 21))
        self.assertTrue(intake.healthy)
        self.assertFalse(intake.failed.is_set())

    async def test_cancellation_during_retry_sleep_has_no_fatal_signal_or_next_attempt(self) -> None:
        intake, database, repository, boundary = self.intake()
        repository.intake.side_effect = OperationalError("unit", {}, DatabaseAbort("40001"))
        sleeping = asyncio.Event()
        sleep_finished = asyncio.Event()

        async def blocked_sleep(delay: int) -> None:
            self.assertEqual(delay, 1)
            self.assertFalse(boundary.active)
            sleeping.set()
            try:
                await asyncio.Event().wait()
            finally:
                sleep_finished.set()

        proxy = SimpleNamespace(Event=asyncio.Event, CancelledError=asyncio.CancelledError,
                                Future=asyncio.Future, sleep=blocked_sleep)
        task: asyncio.Task[None] | None = None
        with patch.object(handlers, "asyncio", proxy), self.assertLogs(handlers.logger, level="WARNING") as logs:
            try:
                task = asyncio.create_task(intake.handle(message_fixture(), Update(update_id=98765)))
                await asyncio.wait_for(sleeping.wait(), 1)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await asyncio.wait_for(task, 1)
                self.assertTrue(sleep_finished.is_set())
                self.assertFalse(intake.failed.is_set())
                repository.intake.assert_awaited_once()
                database.transaction.assert_called_once()
                self.assertEqual(boundary.events, ["begin", "rollback"])
            finally:
                if task is not None:
                    if not task.done():
                        task.cancel()
                    with suppress(asyncio.CancelledError):
                        await task
        self.assertEqual(logs.output, [
            "WARNING:app.channels.telegram.handlers:Telegram intake waiting for database availability."])
        self.assertNotIn("dummy-secret", " ".join(logs.output))

    async def test_ownership_loss_is_fatal_without_retry(self) -> None:
        for mode in ("explicit", "unowned", "invalidated"):
            with self.subTest(mode=mode):
                intake, database, repository, boundary = self.intake()
                if mode == "explicit":
                    error = OwnershipLost("dummy-secret")
                else:
                    error = OperationalError("unit", {}, DatabaseAbort("40001"),
                                             connection_invalidated=mode == "invalidated")
                    database.owned = mode != "unowned"
                repository.intake.side_effect = error
                with self.assertRaises(OwnershipLost):
                    await intake.persist(message_fixture(), Update(update_id=301))
                repository.intake.assert_awaited_once()
                self.assertEqual(boundary.events, ["begin", "rollback"])

    async def test_fatal_handler_suspends_until_cancelled_and_logs_safe_message(self) -> None:
        errors = (RuntimeError("dummy-secret"), OwnershipLost("dummy-secret"),
                  OperationalError("unit", {}, DatabaseAbort("23505")))
        for error in errors:
            with self.subTest(error_type=type(error).__name__):
                intake, database, repository, boundary = self.intake()
                repository.intake.side_effect = error
                task: asyncio.Task[None] | None = None
                with self.assertLogs(handlers.logger, level="ERROR") as logs:
                    try:
                        task = asyncio.create_task(intake.handle(message_fixture(), Update(update_id=301)))
                        await asyncio.wait_for(intake.failed.wait(), 1)
                        self.assertFalse(task.done())
                        self.assertFalse(intake.healthy)
                        repository.intake.assert_awaited_once()
                        self.assertEqual(boundary.events, ["begin", "rollback"])
                        task.cancel()
                        with self.assertRaises(asyncio.CancelledError):
                            await asyncio.wait_for(task, 1)
                    finally:
                        if task is not None:
                            if not task.done():
                                task.cancel()
                            with suppress(asyncio.CancelledError):
                                await task
                self.assertEqual(logs.output, [
                    "ERROR:app.channels.telegram.handlers:Telegram intake stopped: persistence invariant failed."])
                self.assertNotIn("dummy-secret", " ".join(logs.output))
