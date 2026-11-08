# TASK-058-C: Fix the 5 remaining failing Python unit tests in Ai-services

Status: DELEGATED (2026-11-07)
Memory version at delegation: 46
Parent: TASK-058 items #1, #2, #6, #7, #8. Investigation: TASK-058-B (reports/TASK-058-B1.md, TASK-058-B2.md). All five were classified STALE_TEST; none is a security-relevant production bug.
Authorization: user chose "Naikkan batas ke ~21000" and asked to delegate together with the 4 other test fixes (2026-11-07).

## Reserved writes (worker may write ONLY these)

- `Ai-services/app/ai/prompts.py` (PRODUCTION: one constant only)
- `Ai-services/tests/unit/test_channel.py`
- `Ai-services/tests/unit/test_database.py`
- `Ai-services/tests/unit/test_telegram_sender.py`
- `Ai-services/reports/TASK-058-C.md` (worker report, create it)

Read-only: everything else. In particular do NOT edit `tests/unit/test_ai.py` (it should pass unchanged once the constant is raised; if it does not, report - do not edit), `tests/unit/test_conversation.py`, any other source, `.ai/*`, `.env`, pyproject, uv.lock.

## Required changes

1. `app/ai/prompts.py:22` — raise `MAX_PROMPT_BYTES` from `20000` to `21000`. Change nothing else in the file (not the 512 reserve, not the 128 per-pair constant, not SYSTEM). Grep the repo (excluding `docs/progress/`, `__pycache__`, `.venv`) for other references to `20000` / `MAX_PROMPT_BYTES` in README/docs/code and LIST them in the report; do not edit them.
2. `tests/unit/test_channel.py` ~L292-297 (`test_startup_binds_registered_channel_to_chat_service`) — the fake `settings` SimpleNamespace lacks `ai`, but `app/channels/telegram/bot.py:229` reads `settings.ai`. Add `ai=SimpleNamespace(openrouter_key='', ai_model='')` to that settings object. Keep all assertions.
3. `tests/unit/test_channel.py` ~L233 (`test_supervised_db_failure_stops_poller_without_logging_details`) — `supervise()` waits 1 s (`bot.py:91-100`) before its first DB ping, but the test uses `asyncio.wait_for(self.channel.supervise(), 1)`. Widen the timeout to 3. Keep all assertions.
4. `tests/unit/test_database.py` ~L66-72 (`test_old_telegram_revision_is_rejected`) — `acquire()` sets `owned=True` (`app/core/database.py:78`) before the version check and `owned` is only cleared by `close()`. Move `self.assertFalse(self.database.owned)` to AFTER `await self.database.close()`, mirroring the passing sibling `test_migration_version_missing_rolls_back_and_closes` (L56-64). Keep the rollback/events assertion. Do not change `app/core/database.py`.
5. `tests/unit/test_telegram_sender.py` ~L86-97 (`test_fatal_api_errors_are_safe_and_terminal`) — the sender (`app/channels/telegram/sender.py:50-66`) deliberately retries ONCE as plain text on `TelegramBadRequest` (Markdown parse fallback; covered by `test_parse_error_falls_back_to_plain_text`). For `TelegramBadRequest` expect `bot.send_message.await_count == 2`; for `TelegramForbiddenError` and `TelegramUnauthorizedError` keep `assert_awaited_once()`. Keep the status/code and no-secret assertions. Do not change sender.py.

## Acceptance

- The 5 target tests pass; no assertion is weakened or deleted beyond the explicit changes above.
- Run, from `Ai-services`: `uv run --locked python -m unittest discover -s tests/unit -p "test_*.py"` and report the exact `Ran N tests` and OK/FAILED line. (User authorized the unit run for this task; no integration test, no app startup, no live DB/Telegram/OpenRouter, no network, no `.env`.)
- If any target test still fails after its change, or any other test newly fails, do NOT widen the change: stop, record exact traceback and diagnosis in the report.
- Report: files changed with diff summary, the list of other references to the prompt limit, command + raw counts, remaining failures, remaining work. Be honest; do not claim a pass you did not observe.

## Orchestrator review after submission

git diff vs baseline, confirm only reserved files changed and the prompts.py diff is exactly one line, rerun full tests/unit, update `.ai/MEMORY.md` and `.ai/TASKS.md`.
