"""Request-scoped internal values for the AI graph."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal


def _require_nonblank(value: str, field_name: str, maximum: int = 128) -> str:
    normalized = value.strip()
    if not normalized or len(normalized) > maximum:
        raise ValueError(f"{field_name} must be nonblank and at most {maximum} characters.")
    return normalized


@dataclass(frozen=True, slots=True)
class RequestContext:
    """Trusted adapter-supplied routing context, never authorization proof or prompt data."""

    tenant_id: str
    channel: Literal["web", "whatsapp"]
    subject_id: str
    conversation_id: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "tenant_id", _require_nonblank(self.tenant_id, "tenant_id"))
        if self.channel not in ("web", "whatsapp"):
            raise ValueError("channel must be web or whatsapp.")
        object.__setattr__(self, "subject_id", _require_nonblank(self.subject_id, "subject_id"))
        object.__setattr__(
            self,
            "conversation_id",
            _require_nonblank(self.conversation_id, "conversation_id"),
        )


@dataclass(frozen=True, slots=True)
class ConversationMessage:
    """A bounded plain-text history item accepted from a trusted adapter."""

    role: Literal["user", "assistant"]
    content: str

    def __post_init__(self) -> None:
        if self.role not in ("user", "assistant"):
            raise ValueError("history role must be user or assistant.")
        object.__setattr__(self, "content", _require_nonblank(self.content, "history content", 2000))


@dataclass(frozen=True, slots=True)
class AgentReply:
    """A non-durable response; persistence and delivery belong to future adapters."""

    message: str
    status: Literal["completed", "unavailable"]
    escalation_offered: bool = False

