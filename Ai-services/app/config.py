"""Load .env once without overriding the process environment."""

import os
from dataclasses import dataclass, field
from pathlib import Path

from dotenv import load_dotenv

from app.ai.config import OpenRouterSettings, load_ai_settings
from app.channels.telegram.config import TelegramSettings, load_telegram_settings
from app.core.database import DatabaseSettings, load_database_settings
from app.integrations.grpc.config import GrpcSettings, load_grpc_settings

_dotenv_loaded = False

MIN_INTERNAL_API_KEY_LENGTH = 16


@dataclass(frozen=True)
class InternalApiSettings:
    # Shared secret for `X-Internal-Service-Key` on /api/v1/internal/*.
    # Excluded from repr so it never leaks through logs or tracebacks.
    api_key: str = field(default="", repr=False)

    @property
    def configured(self) -> bool:
        return len(self.api_key) >= MIN_INTERNAL_API_KEY_LENGTH


def load_internal_api_settings() -> InternalApiSettings:
    return InternalApiSettings((os.getenv("AI_INTERNAL_API_KEY") or "").strip())


@dataclass(frozen=True)
class Settings:
    telegram: TelegramSettings
    ai: OpenRouterSettings
    database: DatabaseSettings
    grpc: GrpcSettings = GrpcSettings()
    internal_api: InternalApiSettings = InternalApiSettings()


def load_settings() -> Settings:
    global _dotenv_loaded
    if not _dotenv_loaded:
        load_dotenv(Path(__file__).resolve().parents[1] / ".env", override=False)
        _dotenv_loaded = True
    return Settings(
        load_telegram_settings(),
        load_ai_settings(),
        load_database_settings(),
        load_grpc_settings(),
        load_internal_api_settings(),
    )
