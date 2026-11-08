"""Classification of LLM invocation failures into stable, user-safe error codes."""

import asyncio

import httpx
import openai

from app.ai.openrouter import AIError

LLM_ERROR_CODES: tuple[str, ...] = (
    "AI_NOT_CONFIGURED",
    "AI_HTTP_401",
    "AI_HTTP_402",
    "AI_HTTP_403",
    "AI_TRANSPORT_UNKNOWN",
    "AI_UNKNOWN",
)

_MAX_CHAIN_DEPTH = 5
_HTTP_STATUS_CODES: dict[int, str] = {
    401: "AI_HTTP_401",
    402: "AI_HTTP_402",
    403: "AI_HTTP_403",
}
_TRANSPORT_ERRORS: tuple[type[BaseException], ...] = (
    openai.APIConnectionError,
    httpx.TransportError,
    httpx.TimeoutException,
    asyncio.TimeoutError,
    TimeoutError,
    ConnectionError,
)


def _status_code_of(exc: BaseException) -> int | None:
    """Return an integer HTTP status carried by the exception, if any."""
    direct = getattr(exc, "status_code", None)
    if isinstance(direct, int) and not isinstance(direct, bool):
        return direct
    response = getattr(exc, "response", None)
    nested = getattr(response, "status_code", None)
    if isinstance(nested, int) and not isinstance(nested, bool):
        return nested
    return None


def _classify_single(exc: BaseException) -> str | None:
    """Classify one exception object, or return None when it is not recognised."""
    if isinstance(exc, AIError):
        code = exc.code
        if isinstance(code, str) and code:
            return code
        return None
    status = _status_code_of(exc)
    if status is not None and status in _HTTP_STATUS_CODES:
        return _HTTP_STATUS_CODES[status]
    if isinstance(exc, _TRANSPORT_ERRORS):
        return "AI_TRANSPORT_UNKNOWN"
    return None


def classify_llm_error(exc: BaseException) -> str:
    """Map an LLM failure to one of ``LLM_ERROR_CODES``.

    The exception message is never inspected; only types and structured attributes
    (``AIError.code``, ``status_code``, ``response.status_code``) are used. The
    ``__cause__`` / ``__context__`` chain is walked up to five levels and the first
    recognised exception wins.

    Args:
        exc: The exception raised while invoking the LLM.

    Returns:
        A stable error code; ``AI_UNKNOWN`` when nothing recognisable is found.
    """
    current: BaseException | None = exc
    seen: set[int] = set()
    for _ in range(_MAX_CHAIN_DEPTH):
        if current is None or id(current) in seen:
            break
        seen.add(id(current))
        code = _classify_single(current)
        if code is not None:
            return code
        current = current.__cause__ or current.__context__
    return "AI_UNKNOWN"
