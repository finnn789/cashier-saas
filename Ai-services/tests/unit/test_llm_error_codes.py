"""Unit tests for LLM error classification and the agent node failure path."""

import asyncio
import unittest
from unittest.mock import AsyncMock, patch

import httpx
import openai
from langchain_core.messages import HumanMessage

from app.ai.graph.error_codes import LLM_ERROR_CODES, classify_llm_error
from app.ai.graph.nodes import create_agent_node
from app.ai.openrouter import AIError


def _response(status: int) -> httpx.Response:
    return httpx.Response(status, request=httpx.Request("POST", "https://x"))


def _status_error(cls: type[openai.APIStatusError], status: int) -> openai.APIStatusError:
    return cls("x", response=_response(status), body=None)


class ClassifyLlmErrorTests(unittest.TestCase):
    """Table tests for classify_llm_error."""

    def test_table(self) -> None:
        request = httpx.Request("POST", "https://x")
        cases: list[tuple[str, BaseException, str]] = [
            ("auth 401", _status_error(openai.AuthenticationError, 401), "AI_HTTP_401"),
            ("payment 402", _status_error(openai.APIStatusError, 402), "AI_HTTP_402"),
            ("permission 403", _status_error(openai.PermissionDeniedError, 403), "AI_HTTP_403"),
            ("rate limit 429", _status_error(openai.RateLimitError, 429), "AI_UNKNOWN"),
            ("server 500", _status_error(openai.InternalServerError, 500), "AI_UNKNOWN"),
            ("ai error code", AIError("AI_NOT_CONFIGURED"), "AI_NOT_CONFIGURED"),
            ("ai error http", AIError("AI_HTTP_402"), "AI_HTTP_402"),
            ("builtin timeout", TimeoutError(), "AI_TRANSPORT_UNKNOWN"),
            ("asyncio timeout", asyncio.TimeoutError(), "AI_TRANSPORT_UNKNOWN"),
            ("connection error", ConnectionResetError(), "AI_TRANSPORT_UNKNOWN"),
            ("httpx connect", httpx.ConnectError("boom", request=request), "AI_TRANSPORT_UNKNOWN"),
            ("httpx timeout", httpx.ReadTimeout("boom", request=request), "AI_TRANSPORT_UNKNOWN"),
            ("openai connection", openai.APIConnectionError(request=request), "AI_TRANSPORT_UNKNOWN"),
            ("openai timeout", openai.APITimeoutError(request=request), "AI_TRANSPORT_UNKNOWN"),
            (
                "httpx status error",
                httpx.HTTPStatusError("x", request=request, response=_response(403)),
                "AI_HTTP_403",
            ),
            ("generic", ValueError("boom"), "AI_UNKNOWN"),
        ]
        for name, exc, expected in cases:
            with self.subTest(name):
                code = classify_llm_error(exc)
                self.assertEqual(code, expected)
                self.assertIn(code, LLM_ERROR_CODES)
        self.assertIn("AI_UNKNOWN", LLM_ERROR_CODES)

    def test_wrapped_cause_and_context_chains(self) -> None:
        with self.subTest("explicit cause"):
            inner = _status_error(openai.AuthenticationError, 401)
            try:
                try:
                    raise inner
                except openai.AuthenticationError as caught:
                    raise RuntimeError("wrapper") from caught
            except RuntimeError as outer:
                self.assertEqual(classify_llm_error(outer), "AI_HTTP_401")
        with self.subTest("implicit context"):
            try:
                try:
                    raise TimeoutError()
                except TimeoutError:
                    raise RuntimeError("wrapper")
            except RuntimeError as outer:
                self.assertEqual(classify_llm_error(outer), "AI_TRANSPORT_UNKNOWN")

    def test_chain_depth_is_bounded(self) -> None:
        exc: BaseException = _status_error(openai.AuthenticationError, 401)
        for _ in range(6):
            wrapper = RuntimeError("wrapper")
            wrapper.__cause__ = exc
            exc = wrapper
        self.assertEqual(classify_llm_error(exc), "AI_UNKNOWN")

    def test_message_text_is_never_inspected(self) -> None:
        for text in ("HTTP 401 unauthorized", "port 4020 status 402", "error 403 at 10.0.0.403"):
            with self.subTest(text):
                self.assertEqual(classify_llm_error(ValueError(text)), "AI_UNKNOWN")
                self.assertEqual(classify_llm_error(RuntimeError(text)), "AI_UNKNOWN")


class AgentNodeFailureTests(unittest.IsolatedAsyncioTestCase):
    """The agent node returns only an error code when the LLM call fails."""

    async def test_llm_authentication_error_returns_only_error_code(self) -> None:
        node = create_agent_node("sk-test", "model", tools=None)
        error = _status_error(openai.AuthenticationError, 401)
        with patch("langchain_openai.ChatOpenAI.ainvoke", new_callable=AsyncMock) as mock_invoke:
            mock_invoke.side_effect = error
            with self.assertLogs("app.ai.graph.nodes", level="ERROR") as logs:
                result = await node({"messages": [HumanMessage(content="halo")]})

        self.assertEqual(result, {"error_code": "AI_HTTP_401"})
        self.assertEqual(len(logs.records), 1)
        self.assertEqual(logs.records[0].__dict__["event"], "llm_invocation_failed")
        self.assertEqual(logs.records[0].__dict__["error_code"], "AI_HTTP_401")


if __name__ == "__main__":
    unittest.main()
