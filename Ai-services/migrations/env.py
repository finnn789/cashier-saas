"""Operator-run async PostgreSQL migrations; no startup schema creation."""

import asyncio
from pathlib import Path

from alembic import context
from dotenv import load_dotenv
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.pool import NullPool

from app.chat.entities import Base
from app.core.database import (
    COMMAND_TIMEOUT_SECONDS,
    CONNECT_TIMEOUT_SECONDS,
    load_database_settings,
)

load_dotenv(Path(__file__).resolve().parents[1] / ".env", override=False)
target_metadata = Base.metadata


def migrate(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=target_metadata)
    with context.begin_transaction():
        context.run_migrations()


async def run_online() -> None:
    settings = load_database_settings()
    engine = create_async_engine(
        settings.url,
        poolclass=NullPool,
        connect_args={
            "timeout": CONNECT_TIMEOUT_SECONDS,
            "command_timeout": COMMAND_TIMEOUT_SECONDS,
        },
    )
    try:
        async with engine.connect() as connection:
            await connection.run_sync(migrate)
    finally:
        await engine.dispose()


if context.is_offline_mode():
    settings = load_database_settings()
    context.configure(
        url=settings.url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )
    with context.begin_transaction():
        context.run_migrations()
else:
    asyncio.run(run_online())
