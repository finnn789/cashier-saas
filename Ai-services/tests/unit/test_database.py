"""Database ownership and cleanup with construction of the engine mocked."""
import asyncio
import unittest
from contextlib import suppress
from unittest.mock import AsyncMock, Mock, patch

from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine
import app.core.database as database_module
from app.core.database import Database, DatabaseSettings, OwnershipLost


class BeginBoundary:
    def __init__(self) -> None:
        self.events: list[str] = []

    async def __aenter__(self) -> None:
        self.events.append("begin")

    async def __aexit__(self, error_type: object, error: object, traceback: object) -> None:
        self.events.append("rollback" if error_type is not None else "commit")


class DatabaseTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.engine = Mock(spec=AsyncEngine)
        self.connection = Mock(spec=AsyncConnection)
        self.connection.closed = False
        self.connection.invalidated = False
        self.connection.scalar = AsyncMock(side_effect=[True, "0001_channels"])
        self.connection.commit = AsyncMock()
        self.connection.execute = AsyncMock()
        self.connection.close = AsyncMock()
        self.connection.invalidate = AsyncMock()
        self.begin = BeginBoundary()
        self.connection.begin = Mock(return_value=self.begin)
        self.engine.connect = AsyncMock(return_value=self.connection)
        self.engine.dispose = AsyncMock()
        with patch.object(database_module, "create_async_engine", return_value=self.engine) as factory:
            self.database = Database(DatabaseSettings("postgresql+asyncpg://unit:dummy@localhost/unit"))
        factory.assert_called_once()
        self.assertFalse(factory.call_args.kwargs["echo"])

    async def test_advisory_lock_denial_never_becomes_owned(self) -> None:
        self.connection.scalar.side_effect = [False]
        with self.assertRaisesRegex(RuntimeError, "Bot sudah dimiliki"):
            await self.database.acquire(71)
        self.assertFalse(self.database.owned)
        self.connection.commit.assert_awaited_once()
        self.connection.begin.assert_not_called()
        statement, parameters = self.connection.scalar.await_args.args
        self.assertIn("pg_try_advisory_lock(:key)", str(statement))
        self.assertIsInstance(parameters["key"], int)
        await self.database.close()
        self.engine.dispose.assert_awaited_once()

    async def test_migration_version_missing_rolls_back_and_closes(self) -> None:
        self.connection.scalar.side_effect = [True, None]
        with self.assertRaisesRegex(RuntimeError, "Migrasi AI belum sesuai"):
            await self.database.acquire(71)
        self.assertEqual(self.begin.events, ["begin", "rollback"])
        await self.database.close()
        self.assertFalse(self.database.owned)
        self.connection.close.assert_awaited_once()
        self.engine.dispose.assert_awaited_once()

    async def test_old_telegram_revision_is_rejected(self) -> None:
        self.connection.scalar.side_effect = [True, "0001_telegram"]
        with self.assertRaisesRegex(RuntimeError, "Migrasi AI belum sesuai"):
            await self.database.acquire(71)
        self.assertEqual(self.begin.events, ["begin", "rollback"])
        self.assertFalse(self.database.owned)
        await self.database.close()

    async def test_transaction_commit_rollback_and_ping(self) -> None:
        await self.database.acquire(71)
        async with self.database.transaction() as connection:
            self.assertIs(connection, self.connection)
            self.assertTrue(self.database.lock.locked())
        with self.assertRaisesRegex(RuntimeError, "unit-abort"):
            async with self.database.transaction():
                raise RuntimeError("unit-abort")
        self.assertTrue(await self.database.ping())
        self.assertEqual(self.begin.events, ["begin", "commit", "begin", "commit",
                                            "begin", "rollback", "begin", "commit"])
        self.assertFalse(self.database.lock.locked())
        self.assertEqual(str(self.connection.execute.await_args.args[0]), "SELECT 1")
        await self.database.close()

    async def test_invalidated_owner_never_reconnects(self) -> None:
        await self.database.acquire(71)
        self.connection.invalidated = True
        for attempt in range(2):
            with self.subTest(attempt=attempt), self.assertRaisesRegex(OwnershipLost, "^DB_OWNER_LOST$"):
                await self.database.ping()
        self.assertFalse(self.database.owned)
        self.engine.connect.assert_awaited_once()
        self.connection.execute.assert_not_awaited()
        await self.database.close()

    async def test_invalidation_during_transaction_removes_ownership(self) -> None:
        await self.database.acquire(71)
        async with self.database.transaction():
            self.connection.invalidated = True
        self.assertFalse(self.database.owned)
        with self.assertRaises(OwnershipLost):
            async with self.database.transaction():
                self.fail("Invalid owner yielded a connection")
        self.engine.connect.assert_awaited_once()
        await self.database.close()

    async def test_successful_close_disposes_and_releases_ownership(self) -> None:
        await self.database.acquire(71)
        await self.database.close()
        self.assertFalse(self.database.owned)
        self.connection.close.assert_awaited_once()
        self.connection.invalidate.assert_not_awaited()
        self.engine.dispose.assert_awaited_once()

    async def test_close_failure_timeout_and_invalidate_failure_are_safe(self) -> None:
        for mode, expected in (("failure", "DB_OWNER_CLOSE_FAILED"),
                               ("timeout", "DB_OWNER_CLOSE_FAILED"),
                               ("invalidate", "DB_OWNER_CLEANUP_FAILED")):
            with self.subTest(mode=mode):
                self.database.connection = self.connection
                self.database.owned = True
                self.connection.close.reset_mock()
                self.connection.invalidate.reset_mock()
                self.engine.dispose.reset_mock()
                self.connection.invalidate.side_effect = None
                if mode == "timeout":
                    started = asyncio.Event()

                    async def blocked_close() -> None:
                        started.set()
                        await asyncio.Event().wait()

                    self.connection.close.side_effect = blocked_close
                else:
                    self.connection.close.side_effect = RuntimeError("dummy-secret")
                if mode == "invalidate":
                    self.connection.invalidate.side_effect = RuntimeError("dummy-secret")
                with patch.object(database_module, "COMMAND_TIMEOUT_SECONDS", 0.02):
                    with self.assertRaises(RuntimeError) as caught:
                        await asyncio.wait_for(self.database.close(), 1)
                self.assertEqual(str(caught.exception), expected)
                self.assertFalse(self.database.owned)
                self.connection.invalidate.assert_awaited_once()
                self.engine.dispose.assert_awaited_once()

    async def test_cancelled_close_invalidates_then_disposes_before_propagating(self) -> None:
        self.database.connection = self.connection
        entered = asyncio.Event()

        async def blocked_close() -> None:
            entered.set()
            await asyncio.Event().wait()

        self.connection.close.side_effect = blocked_close
        task = asyncio.create_task(self.database.close())
        try:
            await asyncio.wait_for(entered.wait(), 1)
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await asyncio.wait_for(task, 1)
            self.connection.invalidate.assert_awaited_once()
            self.engine.dispose.assert_awaited_once()
        finally:
            if not task.done():
                task.cancel()
            with suppress(asyncio.CancelledError):
                await task

    async def test_dispose_error_is_safe_even_after_close_failure(self) -> None:
        self.database.connection = self.connection
        self.connection.close.side_effect = RuntimeError("dummy-close-secret")
        self.engine.dispose.side_effect = RuntimeError("dummy-dispose-secret")
        with self.assertRaises(RuntimeError) as caught:
            await self.database.close()
        self.assertEqual(str(caught.exception), "DB_ENGINE_DISPOSE_FAILED")
        self.connection.invalidate.assert_awaited_once()
        self.engine.dispose.assert_awaited_once()
