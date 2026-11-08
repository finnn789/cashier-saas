"""Defense-in-depth masking of secrets and phone-like numbers in log output."""

from __future__ import annotations

import logging
import re

# Numeric identifiers that are legitimately long digit strings; never scanned.
ID_FIELDS = frozenset(
    {"update_id", "chat_id", "message_id", "user_id", "tenant_id", "conversation_id"}
)

STANDARD_ATTRS = frozenset(logging.makeLogRecord({}).__dict__) | {
    "message",
    "asctime",
    "taskName",
}

_PATTERNS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\d{6,}:[A-Za-z0-9_-]{30,}"), "[REDACTED_BOT_TOKEN]"),
    (re.compile(r"\b[Bb]earer\s+[A-Za-z0-9._~+/=-]+"), "Bearer [REDACTED]"),
    (re.compile(r"\bsk-[A-Za-z0-9_-]{16,}"), "[REDACTED_API_KEY]"),
    (re.compile(r"(?<![\w.-])\+?\d{9,}(?!\w)"), "[REDACTED_NUMBER]"),
)


def redact_text(text: str) -> str:
    """Mask bot tokens, bearer tokens, API keys and phone-like digit runs in `text`."""
    for pattern, replacement in _PATTERNS:
        text = pattern.sub(replacement, text)
    return text


class RedactionFilter(logging.Filter):
    """Scrub the message and string-valued extras; never raises and never drops a record."""

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            self._scrub(record)
        except Exception:  # fail open: the record is kept, the logger must not crash
            pass
        return True

    @staticmethod
    def _scrub(record: logging.LogRecord) -> None:
        if isinstance(record.msg, str):
            original = record.getMessage()
            redacted = redact_text(original)
            if redacted != original:
                record.msg = redacted
                record.args = ()
        for key, value in list(record.__dict__.items()):
            if key in STANDARD_ATTRS or key in ID_FIELDS or key.startswith("_"):
                continue
            if isinstance(value, str):
                redacted = redact_text(value)
                if redacted != value:
                    record.__dict__[key] = redacted
