"""Server-owned Telegram access configuration."""

import os
from dataclasses import dataclass, field
from uuid import UUID


@dataclass(frozen=True)
class TelegramSettings:
    token: str = field(repr=False)
    allowed_user_ids: frozenset[int]
    # Development fallback only (AI_TENANT_ID). Used for a Telegram user who has NO
    # verified membership; it never overrides a tenant resolved from a verified
    # membership. Leave it unset in production so every user must verify.
    fallback_tenant_id: UUID | None = None


def load_telegram_settings() -> TelegramSettings:
    allowed: set[int] = set()
    try:
        for item in os.getenv("TELEGRAM_ALLOWED_USER_IDS", "").split(","):
            if not item.strip():
                continue
            user_id = int(item.strip())
            if user_id <= 0:
                raise ValueError
            allowed.add(user_id)
    except ValueError:
        raise RuntimeError("TELEGRAM_ALLOWED_USER_IDS harus berisi ID positif dipisahkan koma.") from None
    raw_tenant_id = os.getenv("AI_TENANT_ID", "").strip()
    fallback_tenant_id: UUID | None = None
    if raw_tenant_id:
        try:
            fallback_tenant_id = UUID(raw_tenant_id)
        except ValueError:
            raise RuntimeError("AI_TENANT_ID harus berupa UUID yang valid.") from None
    return TelegramSettings(
        token=os.getenv("TELEGRAM_BOT_TOKEN", "").strip(),
        allowed_user_ids=frozenset(allowed),
        fallback_tenant_id=fallback_tenant_id,
    )
