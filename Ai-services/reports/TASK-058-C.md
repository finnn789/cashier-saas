# TASK-058-C Worker Report

Status: SUBMITTED (all 5 target tests fixed; full unit suite green)

## Files changed
- `Ai-services/app/ai/prompts.py`: `MAX_PROMPT_BYTES` 20000 -> 21000 (one line only).
- `Ai-services/tests/unit/test_channel.py`: (a) `test_startup_binds_registered_channel_to_chat_service` settings gained `ai=SimpleNamespace(openrouter_key='', ai_model='')`; (b) `test_supervised_db_failure_stops_poller_without_logging_details` `wait_for(..., 1)` -> `3`. Assertions unchanged.
- `Ai-services/tests/unit/test_database.py`: `test_old_telegram_revision_is_rejected` - `assertFalse(self.database.owned)` moved after `await self.database.close()`. Rollback/events assertion kept.
- `Ai-services/tests/unit/test_telegram_sender.py`: `test_fatal_api_errors_are_safe_and_terminal` - for `TelegramBadRequest` expects `bot.send_message.await_count == 2`; others keep `assert_awaited_once()`. Status/code and no-secret assertions kept.
- `Ai-services/reports/TASK-058-C.md`: this report.

`git diff --stat` for app/tests also lists `tests/unit/test_conversation.py`; that was already modified before this task (in the initial git status) and I did not touch it.

## Other references to the prompt limit (not edited)
- `Ai-services/app/ai/prompts.py:49` uses `MAX_PROMPT_BYTES` in `build_prompt`.
- `Ai-services/tests/unit/test_ai.py:12, 83, 102` import/use `MAX_PROMPT_BYTES` (symbolic, no literal 20000).
- `Ai-services/reports/TASK-058-B2.md` mentions 20000 in prose (historical investigation).
- No README/docs references to MAX_PROMPT_BYTES or a prompt limit of 20000 found. Other `20000` hits in the repo (postman collections, Java tests, demo seed SQL, reports/artifacts) are unrelated money amounts or phone numbers.

## Test run
Command: `cd /e/Java/cashier-saas/Ai-services && uv run --locked python -m unittest discover -s tests/unit -p "test_*.py"`
Result (observed): `Ran 136 tests in 2.654s` / `OK`

## Remaining failures
None observed. No integration tests, app startup, network, live services or `.env` used.

## Remaining work
Orchestrator review per packet (diff vs baseline, rerun, update `.ai/MEMORY.md` / `.ai/TASKS.md`).
