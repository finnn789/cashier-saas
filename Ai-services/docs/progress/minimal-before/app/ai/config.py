"""OpenRouter settings; credentials are never included in repr."""

import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class OpenRouterSettings:
    openrouter_key: str = field(repr=False)
    ai_model: str


def load_ai_settings() -> OpenRouterSettings:
    return OpenRouterSettings(
        openrouter_key=os.getenv("OPENROUTER_API_KEY", "").strip(),
        ai_model=os.getenv("AI_MODEL", "").strip(),
    )
