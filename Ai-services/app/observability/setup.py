"""Root logger configuration: stdout (+ optional rotating file) behind a non-blocking queue."""

from __future__ import annotations

import atexit
import copy
import logging
import queue
import sys
from logging.handlers import QueueHandler, QueueListener, RotatingFileHandler
from pathlib import Path

from dotenv import load_dotenv

from app.observability.context import ContextFilter
from app.observability.formatter import StructuredJsonFormatter, TextFormatter
from app.observability.redaction import RedactionFilter
from app.observability.settings import LoggingSettings, load_logging_settings

_UVICORN_LOGGERS = ("uvicorn", "uvicorn.error", "uvicorn.access")

_active_settings: LoggingSettings | None = None
_listener: QueueListener | None = None
_root_handler: logging.Handler | None = None
_sinks: list[logging.Handler] = []
_dotenv_loaded = False


class _KeepRecordQueueHandler(QueueHandler):
    """Queue handler that keeps `exc_info`/args so the listener's formatter sees the raw record."""

    def prepare(self, record: logging.LogRecord) -> logging.LogRecord:
        return copy.copy(record)


def _load_dotenv_once() -> None:
    global _dotenv_loaded
    if not _dotenv_loaded:
        # Same file app.config loads; override=False keeps real environment variables on top.
        load_dotenv(Path(__file__).resolve().parents[2] / ".env", override=False)
        _dotenv_loaded = True


def _teardown() -> None:
    global _listener, _active_settings, _root_handler
    if _root_handler is not None:
        logging.getLogger().removeHandler(_root_handler)
    if _listener is not None:
        _listener.stop()
    for handler in _sinks:
        handler.close()
    _sinks.clear()
    _listener = None
    _root_handler = None
    _active_settings = None


def _stop_at_exit() -> None:
    global _listener
    if _listener is not None:
        _listener.stop()
        _listener = None


def shutdown_logging() -> None:
    """Remove the handlers installed by `setup_logging` and stop the listener thread."""
    _teardown()


def setup_logging(settings: LoggingSettings | None = None) -> None:
    """Configure the root logger once; repeated calls with unchanged settings are no-ops."""
    global _listener, _active_settings, _root_handler
    if settings is None:
        _load_dotenv_once()
        settings = load_logging_settings()
    if _active_settings == settings:
        return
    _teardown()

    json_formatter = StructuredJsonFormatter()
    stdout_handler = logging.StreamHandler(sys.stdout)
    stdout_handler.setFormatter(
        json_formatter if settings.log_format == "json" else TextFormatter()
    )
    _sinks.append(stdout_handler)

    entry: logging.Handler
    if settings.file_enabled:
        settings.log_dir.mkdir(parents=True, exist_ok=True)
        file_handler = RotatingFileHandler(
            settings.log_dir / "ai-services.log",
            maxBytes=settings.max_bytes,
            backupCount=settings.backup_count,
            encoding="utf-8",
        )
        file_handler.setFormatter(json_formatter)
        _sinks.append(file_handler)
        log_queue: queue.SimpleQueue[logging.LogRecord] = queue.SimpleQueue()
        entry = _KeepRecordQueueHandler(log_queue)
        _listener = QueueListener(log_queue, *_sinks, respect_handler_level=True)
        _listener.start()
        atexit.register(_stop_at_exit)
    else:
        entry = stdout_handler
    # Filters run on the entry handler, i.e. in the logging task where the context is bound.
    entry.addFilter(ContextFilter())
    entry.addFilter(RedactionFilter())

    root = logging.getLogger()
    root.setLevel(settings.level)
    root.addHandler(entry)
    _root_handler = entry

    for name in _UVICORN_LOGGERS:
        uvicorn_logger = logging.getLogger(name)
        uvicorn_logger.handlers.clear()
        uvicorn_logger.propagate = True
    _active_settings = settings
