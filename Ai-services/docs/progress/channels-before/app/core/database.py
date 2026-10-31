"""Async PostgreSQL connections and exclusive bot ownership.

Queue transactions use the dedicated advisory-lock connection. Ownership loss
is fatal; that connection is never transparently reconnected.
"""

import asyncio
import hashlib
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field

from sqlalchemy import text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import ArgumentError
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine, create_async_engine
from sqlalchemy.pool import NullPool

CONNECT_TIMEOUT_SECONDS = 10
COMMAND_TIMEOUT_SECONDS = 15


@dataclass(frozen=True)
class DatabaseSettings:
    url: str = field(repr=False)


def load_database_settings() -> DatabaseSettings:
    raw = os.getenv("DATABASE_URL", "").strip()
    try:
        url = make_url(raw)
        if url.drivername not in ("postgresql", "postgresql+asyncpg") or not url.database:
            raise ValueError
        # Force validation even when URL parsing defers port conversion.
        _ = url.port
        url = url.set(drivername="postgresql+asyncpg")
        rendered = url.render_as_string(hide_password=False)
    except (ArgumentError, ValueError):
        raise RuntimeError("DATABASE_URL harus berupa URL PostgreSQL AI yang valid.") from None
    return DatabaseSettings(rendered)


class OwnershipLost(RuntimeError):
    """The process must stop rather than reconnect its owner connection."""


class Database:
    def __init__(self, settings: DatabaseSettings, scope: str) -> None:
        self.engine: AsyncEngine = create_async_engine(
            settings.url,
            poolclass=NullPool,
            echo=False,
            connect_args={
                "timeout": CONNECT_TIMEOUT_SECONDS,
                "command_timeout": COMMAND_TIMEOUT_SECONDS,
            },
        )
        self.scope = scope
        self.bot_id = 0
        self.connection: AsyncConnection | None = None
        self.lock = asyncio.Lock()
        self.owned = False

    async def acquire(self, bot_id: int) -> None:
        self.bot_id = bot_id
        # Lock the bot globally within this database, across service scopes.
        digest = hashlib.sha256(f"telegram-poller:{bot_id}".encode()).digest()
        key = int.from_bytes(digest[:8], "big", signed=True)
        connection = await self.engine.connect()
        self.connection = connection
        acquired = await connection.scalar(
            text("SELECT pg_try_advisory_lock(:key)"),
            {"key": key},
        )
        await connection.commit()
        if not acquired:
            raise RuntimeError("Bot sudah dimiliki proses lain pada database ini.")
        self.owned = True
        async with self.transaction() as transaction:
            version = await transaction.scalar(
                text("SELECT version_num FROM alembic_version")
            )
            if version != "0001_telegram":
                raise RuntimeError("Migrasi AI belum sesuai. Operator harus menjalankan Alembic.")

    @asynccontextmanager
    async def transaction(self) -> AsyncIterator[AsyncConnection]:
        async with self.lock:
            connection = self.connection
            if (
                not self.owned
                or connection is None
                or connection.closed
                or connection.invalidated
            ):
                self.owned = False
                raise OwnershipLost("DB_OWNER_LOST")
            try:
                async with connection.begin():
                    yield connection
            finally:
                if connection.closed or connection.invalidated:
                    self.owned = False

    async def ping(self) -> bool:
        async with self.transaction() as connection:
            await connection.execute(text("SELECT 1"))
        return True

    async def close(self) -> None:
        self.owned = False
        try:
            if self.connection is not None:
                try:
                    # Bound graceful close; command_timeout does not bound it.
                    async with asyncio.timeout(COMMAND_TIMEOUT_SECONDS):
                        await self.connection.close()
                except (Exception, asyncio.CancelledError) as error:
                    try:
                        # Public invalidation terminates the NullPool session.
                        await self.connection.invalidate()
                    except Exception:
                        raise RuntimeError("DB_OWNER_CLEANUP_FAILED") from None
                    if isinstance(error, asyncio.CancelledError):
                        raise
                    raise RuntimeError("DB_OWNER_CLOSE_FAILED") from None
        finally:
            try:
                await self.engine.dispose()
            except Exception:
                raise RuntimeError("DB_ENGINE_DISPOSE_FAILED") from None
