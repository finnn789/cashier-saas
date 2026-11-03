# TASK-058: Fix pre-existing failing Python unit tests in Ai-services

Status: PENDING — NOT STARTED (packet created 2026-11-02; implementation not yet authorized/delegated)
Memory version: 44
Origin: found by TASK-057 W6 test run; confirmed PRE-EXISTING by running the same modules against a clean export of `HEAD` (e5c64a5): 8 failures/errors there, unrelated to TASK-057.

## Evidence

Command (from `Ai-services`, no pytest in venv, so unittest):
`uv run python -m unittest tests.unit.test_channel tests.unit.test_conversation tests.unit.test_ai tests.unit.test_database tests.unit.test_telegram_sender`
On clean HEAD: `Ran 60 tests` (with test_grpc_client) -> `FAILED (failures=3, errors=5)`.

| # | Test | Type | Symptom (from W6 report; root cause NOT yet investigated) |
|---|---|---|---|
| 1 | test_channel.ChannelTests.test_startup_binds_registered_channel_to_chat_service | ERROR | RuntimeError "Startup Telegram/database gagal; periksa konfigurasi dan konektivitas." |
| 2 | test_channel.ChannelTests.test_supervised_db_failure_stops_poller_without_logging_details | ERROR | same family as #1 |
| 3 | test_conversation.ConversationTests.test_assistant_persistence_failure_propagates_after_user_commit | ERROR | TypeError: `reply()` got unexpected kwarg `tools` |
| 4 | test_conversation.ConversationTests.test_transient_retry_is_bounded_and_waits_outside_transaction | ERROR | TypeError in test helper `retry_reply()` signature |
| 5 | test_conversation.ConversationTests.test_user_commits_before_ai_and_assistant_commits_before_return | ERROR | TypeError: `reply()` unexpected kwarg `tools` |
| 6 | test_ai.PromptTests.test_maximum_emoji_profile_and_current_keep_complete_newest_pairs | FAIL | `[] != [{'role':'user','content':'recent'}, ...]` |
| 7 | test_database.DatabaseTests.test_old_telegram_revision_is_rejected | FAIL | `True is not false` |
| 8 | test_telegram_sender.SenderTests.test_fatal_api_errors_are_safe_and_terminal | FAIL | send_message awaited 2 times, expected 1 (error_type='TelegramBadRequest') |

Hypothesis to verify (not a conclusion): tests #3–#5 look stale against the TASK-052 LangGraph refactor (`ChatService` no longer takes `tools` in `reply()`), #1–#2 against the same refactor/startup wiring, #7 against the TASK-050+ Alembic revision chain, #6/#8 against prompt/sender behaviour changes. For each: decide whether the TEST is stale (update it) or the PRODUCTION code regressed (fix it and say so explicitly).

## Acceptance

- Every listed test passes, or is deliberately removed/rewritten with a written reason in the report when it asserts behaviour that the user-approved design (TASK-052 LangGraph, channel schema) intentionally replaced.
- No assertion is weakened just to go green; production changes are called out separately from test-only changes.
- Full `tests/unit` run reported with exact counts (unittest).
- No change to gRPC client/config (TASK-057 scope) and no `.env` access, no live DB/Telegram/OpenRouter calls.

## Suggested delegation

1. Investigation worker (sonnet, read-only + report): classify each of the 8 as stale-test vs production-regression with evidence.
2. Fix worker(s) (sonnet), reserved per test module: `tests/unit/test_conversation.py`, `tests/unit/test_channel.py`, `tests/unit/test_ai.py`, `tests/unit/test_database.py`, `tests/unit/test_telegram_sender.py`; production paths only after the investigation report is reviewed and reserved explicitly.
3. Runner (haiku): full `tests/unit` via unittest, raw results only.

## Reservations

Not reserved yet. To be defined when the user authorizes implementation.
