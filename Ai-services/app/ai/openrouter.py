"""Bounded OpenRouter I/O, with explicit and safe failure categories."""

import asyncio
import json
from dataclasses import dataclass
from typing import Any

import aiohttp

from app.ai.config import OpenRouterSettings

ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
MAX_RESPONSE_BYTES = 262144


class AIError(Exception):
    def __init__(self, code: str, retryable: bool = False, delay: int = 5) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable
        self.delay = delay


@dataclass(frozen=True)
class Completion:
    content: str
    model: str
    response_id: str | None
    tool_calls: list[dict[str, Any]] | None = None
    raw_message: dict[str, Any] | None = None


class OpenRouterClient:
    def __init__(self, settings: OpenRouterSettings, session: aiohttp.ClientSession) -> None:
        self.settings = settings
        self.session = session

    async def reply(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
    ) -> Completion:
        if not self.settings.openrouter_key or not self.settings.ai_model:
            raise AIError("AI_NOT_CONFIGURED")
        payload: dict[str, Any] = {
            "model": self.settings.ai_model,
            "messages": messages,
            "max_tokens": 1024,
            "stream": False,
        }
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"
        try:
            async with self.session.post(
                ENDPOINT,
                headers={"Authorization": f"Bearer {self.settings.openrouter_key}"},
                json=payload,
                allow_redirects=False,
                timeout=aiohttp.ClientTimeout(total=45, connect=10),
            ) as response:
                if response.status in (401, 402, 403):
                    raise AIError(f"AI_HTTP_{response.status}")
                if response.status == 429:
                    delay = 10
                    raw_delay = response.headers.get("Retry-After", "")
                    if raw_delay.isdecimal():
                        parsed_delay = 0
                        for digit in raw_delay:
                            parsed_delay = parsed_delay * 10 + int(digit)
                            if parsed_delay >= 3600:
                                parsed_delay = 3600
                                break
                        delay = max(1, parsed_delay)
                    raise AIError("AI_RATE_LIMIT", retryable=True, delay=delay)
                if response.status in (500, 502, 503, 504):
                    raise AIError("AI_TEMPORARY", retryable=True)
                if response.status != 200:
                    raise AIError("AI_REJECTED")
                body = bytearray()
                async for chunk in response.content.iter_chunked(8192):
                    body.extend(chunk)
                    if len(body) > MAX_RESPONSE_BYTES:
                        raise AIError("AI_RESPONSE_TOO_LARGE")
                data = json.loads(body)
                choice = data["choices"][0]
                message = choice["message"]
                tool_calls = message.get("tool_calls")
                answer = message.get("content")
                model = data.get("model", self.settings.ai_model)
                response_id = data.get("id")
                if not isinstance(model, str) or len(model) > 256:
                    raise AIError("AI_INVALID_RESPONSE")
                if response_id is not None and (not isinstance(response_id, str) or len(response_id) > 256):
                    raise AIError("AI_INVALID_RESPONSE")

                if tool_calls:
                    return Completion(
                        content=answer or "",
                        model=model,
                        response_id=response_id,
                        tool_calls=tool_calls,
                        raw_message=message,
                    )

                if not isinstance(answer, str) or not answer.strip():
                    raise AIError("AI_INVALID_RESPONSE")
                answer = answer.strip()
                if len(answer) > 12000:
                    answer = answer[:12000] + "\n[Jawaban dipotong.]"
                return Completion(answer, model, response_id)
        except (asyncio.TimeoutError, aiohttp.ClientError):
            # Transport failure may occur after the provider accepted the request.
            raise AIError("AI_TRANSPORT_UNKNOWN") from None
        except (ValueError, KeyError, IndexError, TypeError):
            raise AIError("AI_INVALID_RESPONSE") from None
