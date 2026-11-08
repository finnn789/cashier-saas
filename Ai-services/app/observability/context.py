"""Per-task correlation fields propagated onto every log record."""

from __future__ import annotations

import logging
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from typing import Any

_CONTEXT: ContextVar[dict[str, str]] = ContextVar("log_context", default={})


@contextmanager
def log_context(**fields: Any) -> Iterator[None]:
    """Bind correlation fields for the duration of the block.

    Nested blocks merge with the enclosing one and the previous state is restored on exit.
    `None` values are ignored and every other value is converted with `str()`.
    """
    merged = dict(_CONTEXT.get())
    for key, value in fields.items():
        if value is not None:
            merged[key] = str(value)
    token = _CONTEXT.set(merged)
    try:
        yield
    finally:
        _CONTEXT.reset(token)


def get_log_context() -> dict[str, str]:
    """Return a copy of the fields currently bound in this task."""
    return dict(_CONTEXT.get())


class ContextFilter(logging.Filter):
    """Copy the bound context onto each record without overwriting explicit `extra` keys."""

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            for key, value in _CONTEXT.get().items():
                if key not in record.__dict__:
                    setattr(record, key, value)
        except Exception:  # a logging filter must never raise
            pass
        return True
