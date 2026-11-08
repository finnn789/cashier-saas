# TASK-058-A: Fix stale test doubles in Ai-services test_conversation

Status: DELEGATED (2026-11-07)
Memory version at delegation: 45
Parent: TASK-058 (items #3, #4, #5 only). Items #1, #2, #6, #7, #8 are OUT OF SCOPE.
Authorization: user said "Ya, delegasikan ke worker sekarang" on 2026-11-07 after the orchestrator report.

## Problem (source-verified by orchestrator)

`Ai-services/app/chat/service.py:209` calls `await self.ai.reply(prompt, tools=tools_schemas)`.
The real `OpenRouterClient.reply(self, messages, tools=None)` (`app/ai/openrouter.py:38`) accepts `tools`.
Two fakes in `Ai-services/tests/unit/test_conversation.py` do not:
- line ~122: `async def reply(self, prompt: list[dict[str, str]]) -> Completion:`
- line ~219: `async def retry_reply(prompt: list[dict[str, str]]) -> Completion:` (inside `test_transient_retry_is_bounded_and_waits_outside_transaction`)

Affected tests (all ERROR with `TypeError: ... unexpected keyword argument 'tools'`):
1. test_user_commits_before_ai_and_assistant_commits_before_return
2. test_transient_retry_is_bounded_and_waits_outside_transaction
3. test_assistant_persistence_failure_propagates_after_user_commit

## Reserved writes (worker may write ONLY these)

- `Ai-services/tests/unit/test_conversation.py`
- `Ai-services/reports/TASK-058-A.md` (worker report, create it)

Everything else is read-only. No production code, no other test module, no `.env`, no `.ai/*`, no pyproject/uv.lock.

## Required change

Add an optional `tools` parameter to both fakes so their signatures accept the keyword the service passes, e.g.
`async def reply(self, prompt: list[dict[str, str]], tools: list[dict[str, Any]] | None = None) -> Completion:`
(`Any` is already imported). Keep every existing assertion in the fakes and tests unchanged.

## Acceptance

- The 3 tests no longer fail with the `tools` TypeError.
- No assertion is weakened, removed or loosened to go green.
- If, after the TypeError is gone, a test fails on a real assertion: DO NOT change production code and DO NOT rewrite the assertion. Stop, record the exact failure, traceback and your diagnosis (stale expectation vs production regression) in the report, and leave that test as is. Orchestrator decides.
- Run only: `cd Ai-services && uv run --locked python -m unittest tests/unit/test_conversation.py -v` (user-authorized test run for this module). Do not run the full suite, do not start the app, no live DB/Telegram/OpenRouter calls.
- Report must list: files changed, exact diff summary, command run, raw result counts (Ran/OK/FAILED), any remaining failure, remaining work.

## Orchestrator review after submission

Inspect `git diff` against baseline (clean for this file), confirm only reserved files changed, rerun the module, then update `.ai/MEMORY.md` and `.ai/TASKS.md`.
