"""Load .env once without overriding the process environment."""

from dataclasses import dataclass
from pathlib import Path

from dotenv import load_dotenv

from app.ai.config import OpenRouterSettings, load_ai_settings
from app.channels.telegram.config import TelegramSettings, load_telegram_settings
from app.core.database import DatabaseSettings, load_database_settings
from app.integrations.grpc.config import GrpcSettings, load_grpc_settings

_dotenv_loaded = False


@dataclass(frozen=True)
class Settings:
    telegram: TelegramSettings
    ai: OpenRouterSettings
    database: DatabaseSettings
    grpc: GrpcSettings = GrpcSettings()


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
    )
