"""JSON and text log formatters sharing the same field conventions."""

from __future__ import annotations

import json
import logging
from datetime import datetime, timezone
from typing import Any

from pythonjsonlogger.json import JsonFormatter

from app.observability.redaction import STANDARD_ATTRS, redact_text


def _format_timestamp(created: float) -> str:
    moment = datetime.fromtimestamp(created, tz=timezone.utc)
    return moment.isoformat(timespec="milliseconds").replace("+00:00", "Z")


class StructuredJsonFormatter(JsonFormatter):
    """One JSON object per line: ts, level, logger, message, then context and extra fields."""

    def __init__(self) -> None:
        super().__init__(
            "%(levelname)s %(name)s %(message)s",
            rename_fields={"levelname": "level", "name": "logger"},
        )

    def add_fields(
        self,
        log_data: dict[str, Any],
        record: logging.LogRecord,
        message_dict: dict[str, Any],
    ) -> None:
        log_data["ts"] = _format_timestamp(record.created)
        super().add_fields(log_data, record, message_dict)

    def formatException(self, ei: Any) -> str:
        return redact_text(super().formatException(ei))


class TextFormatter(logging.Formatter):
    """Human-readable line followed by ` key=value` pairs of extra and context fields."""

    def __init__(self) -> None:
        super().__init__("%(asctime)s [%(levelname)s] %(name)s: %(message)s")

    def format(self, record: logging.LogRecord) -> str:
        record.message = record.getMessage()
        if self.usesTime():
            record.asctime = self.formatTime(record, self.datefmt)
        line = self.formatMessage(record)
        for key, value in record.__dict__.items():
            if key in STANDARD_ATTRS or key.startswith("_"):
                continue
            line += f" {key}={_render(value)}"
        if record.exc_info:
            line += "\n" + self.formatException(record.exc_info)
        if record.stack_info:
            line += "\n" + self.formatStack(record.stack_info)
        return line

    def formatException(self, ei: Any) -> str:
        return redact_text(super().formatException(ei))


def _render(value: Any) -> str:
    text = value if isinstance(value, str) else str(value)
    if not text or any(ch.isspace() or ch in '"=' for ch in text):
        return json.dumps(text, ensure_ascii=False)
    return text
