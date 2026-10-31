"""FastAPI wiring for the disabled-by-default local Telegram polling adapter."""

from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncIterator

import httpx
from fastapi import FastAPI, HTTPException

from app.ai.agent import build_agent
from app.config import Settings, load_settings
from app.telegram.client import TelegramClient
from app.telegram.faq import load_telegram_faq
from app.telegram.polling import TelegramPoller
from app.telegram.service import TelegramService


def create_app(settings: Settings | None = None) -> FastAPI:
    """Create HTTP health endpoints; Telegram resources exist only when enabled."""
    configured_settings = settings or load_settings(Path(".env"))

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        application.state.telegram_poller = None
        application.state.telegram_failed = False
        application.state.telegram_task = None
        http_client: httpx.AsyncClient | None = None
        poller: TelegramPoller | None = None
        task: asyncio.Task[None] | None = None
        if configured_settings.telegram_enabled:
            try:
                configured_settings.validate_telegram_runtime()
                configured_settings.validate_ai_runtime()
                logging.getLogger("httpx").setLevel(logging.WARNING)
                logging.getLogger("httpcore").setLevel(logging.WARNING)
                http_client = httpx.AsyncClient(
                    timeout=httpx.Timeout(configured_settings.telegram_send_timeout_seconds),
                    follow_redirects=False,
                    headers={"User-Agent": "cashier-ai-local-telegram/0.1"},
                )
                faq_service = load_telegram_faq(configured_settings.telegram_faq_file)
                agent = build_agent(configured_settings, faq_service)
                token = configured_settings.telegram_bot_token
                if token is None:
                    raise RuntimeError("Telegram configuration unavailable.")
                client = TelegramClient(
                    token.get_secret_value(),
                    http_client,
                    configured_settings.telegram_send_timeout_seconds,
                )
                service = TelegramService(
                    agent,
                    configured_settings.telegram_tenant_id,
                    configured_settings.telegram_allowed_user_ids,
                    configured_settings.chat_max_message_chars,
                )
                poller = TelegramPoller(
                    client, service, configured_settings.telegram_poll_timeout_seconds
                )
                await poller.ensure_no_webhook()
                task = asyncio.create_task(poller.run(), name="telegram-local-poller")
                application.state.telegram_poller = poller
                application.state.telegram_task = task
            except Exception:
                application.state.telegram_failed = True
                logging.exception("Failed to initialize Telegram polling")
                if task is not None and not task.done():
                    if poller is not None:
                        poller.stop()
                    task.cancel()
                    try:
                        await task
                    except asyncio.CancelledError:
                        pass
                if http_client is not None:
                    await http_client.aclose()
                    http_client = None
        try:
            yield
        finally:
            if task is not None and not task.done():
                if poller is not None:
                    poller.stop()
                task.cancel()
                try:
                    await task
                except asyncio.CancelledError:
                    pass
            if http_client is not None:
                await http_client.aclose()

    application = FastAPI(title="Cashier AI Customer Service", lifespan=lifespan)

    @application.get("/health/live")
    async def health_live() -> dict[str, str]:
        return {"status": "live"}

    @application.get("/health/ready")
    async def health_ready() -> dict[str, str]:
        
        if not configured_settings.telegram_enabled:
            return {"status": "ready", "mode": "http-only"}
        poller = application.state.telegram_poller
        task = application.state.telegram_task
        
       
        if (
            application.state.telegram_failed
            or poller is None
            or task is None
            or task.done()
            or not poller.ready
        ):
            raise HTTPException(status_code=503, detail="Service is not ready.")
        return {"status": "ready", "mode": "telegram-polling"}

    return application


app = create_app()
