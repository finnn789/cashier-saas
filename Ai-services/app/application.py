"""FastAPI owns HTTP, Telegram polling, and PostgreSQL lifetimes."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.channels.telegram.bot import TelegramChannel, start_telegram_channel
from app.config import load_settings


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    settings = load_settings()
    channel: TelegramChannel = await start_telegram_channel(settings)
    application.state.telegram_channel = channel
    try:
        yield
    finally:
        await channel.stop()


app = FastAPI(title="AI Service", lifespan=lifespan)


@app.get("/")
async def read_root() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/ready")
async def readiness() -> JSONResponse:
    channel: TelegramChannel | None = getattr(app.state, "telegram_channel", None)
    if channel is None or not await channel.ready():
        return JSONResponse({"status": "not_ready"}, status_code=503)
    return JSONResponse({"status": "ready"})
