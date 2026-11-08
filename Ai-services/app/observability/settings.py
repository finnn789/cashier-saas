"""Logging configuration read from environment variables."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

DEFAULT_LEVEL = "INFO"
DEFAULT_FORMAT = "json"
DEFAULT_LOG_DIR = "logs"
DEFAULT_MAX_BYTES = 10 * 1024 * 1024
DEFAULT_BACKUP_COUNT = 5

_VALID_LEVELS = {
    "DEBUG": "DEBUG",
    "INFO": "INFO",
    "WARNING": "WARNING",
    "WARN": "WARNING",
    "ERROR": "ERROR",
    "CRITICAL": "CRITICAL",
    "FATAL": "CRITICAL",
}
_TRUE_VALUES = {"1", "true", "yes", "on"}
_FALSE_VALUES = {"0", "false", "no", "off", ""}


@dataclass(frozen=True)
class LoggingSettings:
    """Immutable logging configuration."""

    level: str = DEFAULT_LEVEL
    log_format: str = DEFAULT_FORMAT
    file_enabled: bool = False
    log_dir: Path = Path(DEFAULT_LOG_DIR)
    max_bytes: int = DEFAULT_MAX_BYTES
    backup_count: int = DEFAULT_BACKUP_COUNT


def _read_level(raw: str | None) -> str:
    return _VALID_LEVELS.get((raw or "").strip().upper(), DEFAULT_LEVEL)


def _read_format(raw: str | None) -> str:
    value = (raw or "").strip().lower()
    return value if value in {"json", "text"} else DEFAULT_FORMAT


def _read_bool(raw: str | None, default: bool) -> bool:
    value = (raw or "").strip().lower()
    if value in _TRUE_VALUES:
        return True
    if value in _FALSE_VALUES:
        return False
    return default


def _read_int(raw: str | None, default: int, minimum: int) -> int:
    try:
        value = int((raw or "").strip())
    except ValueError:
        return default
    return value if value >= minimum else default


def load_logging_settings(env: Mapping[str, str] | None = None) -> LoggingSettings:
    """Build settings from `env` (defaults to `os.environ`); bad values fall back to defaults."""
    source = os.environ if env is None else env
    log_dir = (source.get("LOG_DIR") or "").strip() or DEFAULT_LOG_DIR
    return LoggingSettings(
        level=_read_level(source.get("LOG_LEVEL")),
        log_format=_read_format(source.get("LOG_FORMAT")),
        file_enabled=_read_bool(source.get("LOG_FILE_ENABLED"), False),
        log_dir=Path(log_dir),
        max_bytes=_read_int(source.get("LOG_MAX_BYTES"), DEFAULT_MAX_BYTES, 1),
        backup_count=_read_int(source.get("LOG_BACKUP_COUNT"), DEFAULT_BACKUP_COUNT, 0),
    )
