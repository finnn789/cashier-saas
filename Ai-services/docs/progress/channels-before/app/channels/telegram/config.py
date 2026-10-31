"""Server-owned Telegram access configuration."""

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class TelegramSettings:
    token: str = field(repr=False)
    allowed_user_ids: frozenset[int]
    service_scope: str


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
    scope = os.getenv("TELEGRAM_SERVICE_SCOPE", "").strip()
    if not scope or len(scope) > 128:
        raise RuntimeError("Isi TELEGRAM_SERVICE_SCOPE dengan scope server sepanjang 1–128 karakter.")
    return TelegramSettings(
        token=os.getenv("TELEGRAM_BOT_TOKEN", "").strip(),
        allowed_user_ids=frozenset(allowed),
        service_scope=scope,
    )
