# TASK-058-B: Classify the 5 remaining failing Python unit tests (READ-ONLY investigation)

Status: DELEGATED (2026-11-07)
Memory version at delegation: 46
Parent: TASK-058 items #1, #2, #6, #7, #8. Items #3-#5 are done (TASK-058-A).
Authorization: user said "Ya, lanjut investigasi 5 kegagalan sisanya" on 2026-11-07.

## Goal

For each failing test, decide with evidence whether the TEST is stale (source behaviour was intentionally changed and the test was not updated) or the PRODUCTION code regressed (the test correctly describes required behaviour). Do NOT fix anything.

## Split (two parallel workers, disjoint reports)

- B1: `test_channel.ChannelTests.test_startup_binds_registered_channel_to_chat_service`, `test_channel.ChannelTests.test_supervised_db_failure_stops_poller_without_logging_details`, `test_database.DatabaseTests.test_old_telegram_revision_is_rejected`. Report: `Ai-services/reports/TASK-058-B1.md`.
- B2: `test_ai.PromptTests.test_maximum_emoji_profile_and_current_keep_complete_newest_pairs`, `test_telegram_sender.SenderTests.test_fatal_api_errors_are_safe_and_terminal` (subTest error_type='TelegramBadRequest'). Report: `Ai-services/reports/TASK-058-B2.md`.

## Known symptoms (from orchestrator runs)

- test_channel x2: `RuntimeError: Startup Telegram/database gagal; periksa konfigurasi dan konektivitas.` from `start_telegram_channel(settings, Mock())`; the 2nd one times out in `asyncio.wait_for(self.channel.supervise(), 1)`.
- test_database: `self.assertFalse(self.database.owned)` -> `True is not false`.
- test_ai: `messages[2:-1]` is `[]`, expected the newest complete pair `recent` / `recent-answer`.
- test_telegram_sender: `bot.send_message.assert_awaited_once()` but it was awaited 2 times for TelegramBadRequest.

## Reserved writes

Each worker may write ONLY its own report file listed above. Everything else (all source, all tests, `.ai/*`, `.env`, pyproject, uv.lock) is strictly read-only.

## Allowed commands

Read files freely. Run ONLY targeted unittest invocations for your own tests, from `Ai-services`, e.g.
`uv run --locked python -m unittest tests.unit.test_database.DatabaseTests.test_old_telegram_revision_is_rejected -v`
No full suite, no app startup, no live DB/Telegram/OpenRouter, no `.env` access, no network. You may use `git log`/`git blame`/`git diff` (read-only) to see when the source or test last changed and why.

## Report requirements (per test)

1. Verdict: STALE_TEST or PRODUCTION_BUG or UNCLEAR.
2. Root cause with exact file:line references in source and test, and the commit/hunk that introduced the drift if you can find it.
3. Evidence: what the test asserts, what the source actually does, and a short reasoning chain. If you ran the test, include the raw result.
4. Whether the user-approved design intentionally replaced the tested behaviour (see `.ai/DECISIONS.md` and `Ai-services/README.md` / `docs/` for the channel schema and LangGraph design) - cite it, or say none found.
5. Proposed minimal fix (describe, do not apply): which file(s), what change, and whether it is test-only or touches production code. For PRODUCTION_BUG state the user-visible/security consequence.
6. Confidence level and anything you could not verify.

Be honest: do not label a verdict without evidence. UNCLEAR is acceptable if justified.
