# TASK-058-B1: Classification of 3 failing tests (read-only investigation)

Worker: B1. Date: 2026-11-07. No source/test file was modified; only this report was written.
Commands run: targeted `uv run --locked python -m unittest <test id>` for the 3 tests, plus two throwaway
scratch scripts in the session scratchpad (outside the repo) that monkeypatch the test module in memory to
validate the proposed fixes. Nothing else (no .env, DB, network, app startup).

Git history note: `app/core/database.py`, `app/channels/telegram/bot.py`, `tests/unit/test_database.py`,
`tests/unit/test_channel.py` were all introduced in the single commit `e5c64a5` (2026-10-31) and not touched
since (working tree clean for these files; only `tests/unit/test_conversation.py` is modified). So there is no
drift commit to point at: the tests were inconsistent with the source from the day they were committed.

Summary

| Test | Verdict | Fix scope |
|---|---|---|
| test_channel...test_startup_binds_registered_channel_to_chat_service | STALE_TEST | test-only |
| test_channel...test_supervised_db_failure_stops_poller_without_logging_details | STALE_TEST (timing) | test-only |
| test_database...test_old_telegram_revision_is_rejected | STALE_TEST (medium confidence) | test-only (optional production hardening) |

---

## 1. test_startup_binds_registered_channel_to_chat_service

1. Verdict: STALE_TEST.

2. Root cause
   - Source: `app/channels/telegram/bot.py:224-230` builds `ChatService(..., ai_settings=settings.ai)`. `Settings`
     (`app/config.py:17-21`) is a dataclass with `telegram`, `ai`, `database`, `grpc`, so `settings.ai` is a
     legitimate, required attribute.
   - Test: `tests/unit/test_channel.py:294-297` builds `settings = SimpleNamespace(telegram=..., database=...)`
     with no `ai` attribute.
   - Result: `AttributeError: 'types.SimpleNamespace' object has no attribute 'ai'` at bot.py:229. The
     blanket `except BaseException` at bot.py:238 converts any non-RuntimeError into
     `RuntimeError('Startup Telegram/database gagal; ...') from None`, which hides the real cause. That is
     exactly the symptom in the packet.
   - The test predates/ignores the `ai_settings=settings.ai` wiring (ChatService LangGraph graph construction
     in `app/chat/service.py:36-52`). Note that the sibling startup tests (early-failure and cancellation, lines
     241-289) fail before reaching that line, which is why they still pass.

3. Evidence
   - Raw run: `RuntimeError: Startup Telegram/database gagal; periksa konfigurasi dan konektivitas.` at
     test_channel.py:313 (bot.py:250).
   - I wrapped `start_telegram_channel` in a scratch script and printed `__context__` of the raised error:
     `AttributeError 'types.SimpleNamespace' object has no attribute 'ai'` (bot.py:229).
   - Validation of fix: in a scratch script, adding `ai=SimpleNamespace(openrouter_key='', ai_model='')` to the
     settings namespace makes this test pass ("ok"). All other assertions in the test (acquire(71),
     register_telegram_channel args, Repository tenant/channel/platform, chat.database, sender.bot,
     start called once, cleanup closes) hold against current source.

4. Intentional design change: `app/config.py` `Settings.ai` and README/docs describe the LangGraph/OpenRouter
   design (docs/telegram-conversation-design.md, README tech table). No document says tests may omit `ai`; the
   production contract is that `Settings` always has `ai`. No contradicting decision found.

5. Proposed minimal fix (test-only): in `tests/unit/test_channel.py` ~line 294, add
   `ai=SimpleNamespace(openrouter_key='', ai_model='')` (empty key => `ChatService.graph = None`, no network,
   no LangGraph build) to the `settings` SimpleNamespace. Same would be needed in any other test that gets past
   `get_me`/`acquire` with that settings object.
   Optional non-blocking observation (not required): `from None` in bot.py:250 masks programming errors as a
   generic connectivity message; consider logging the exception class name (not message) for diagnosability.

6. Confidence: high. Verified by reproduction and by a passing in-memory variant.

---

## 2. test_supervised_db_failure_stops_poller_without_logging_details

1. Verdict: STALE_TEST (timing/contract mismatch in the test).

2. Root cause
   - Test: `tests/unit/test_channel.py:233` runs `await asyncio.wait_for(self.channel.supervise(), 1)`.
   - Source: `app/channels/telegram/bot.py:83-106`. `supervise()` loops `asyncio.wait([polling_task,
     failure_waiter], timeout=1, ...)` (bot.py:91-95) and only calls `self.database.ping()` (bot.py:100) after
     that 1 s wait elapses with nothing done. So the first ping, and therefore the supervised-DB-failure path,
     cannot occur before ~1.0 s, while the test deadline is exactly 1 s. The test can never pass: it hits
     `TimeoutError` (the cancelled `asyncio.wait` is visible in the traceback).
   - Correction to packet: the symptom for this test is `TimeoutError` from `wait_for`, not the
     `RuntimeError: Startup ...` (this test never calls `start_telegram_channel`). The packet line grouped both.
   - The rest of the test's expectations match current source: failed flag set (bot.py:102), error log
     `'Telegram channel stopped after a supervised failure.'` with no secret (bot.py:105), `stop_poller` awaited
     once (bot.py:106), `OwnershipLost` is in the caught tuple (bot.py:101).

3. Evidence
   - Raw run: `TimeoutError` raised from `asyncio.wait_for(self.channel.supervise(), 1)` at test_channel.py:233,
     "Ran 1 test in 1.011s".
   - Scratch variant with the deadline widened to 3 s: test passes (total ~1.03 s, i.e. the 1 s supervisor
     poll interval is exactly what consumed the budget).

4. Intentional design change: the 1 s poll cadence is an implementation detail; no README/DECISIONS text
   specifies a supervision interval or says the first ping must be immediate. Nothing found either way, so
   there is no evidence that "ping first, wait second" is required behaviour. A ~1 s detection delay for DB
   ownership loss is not a defect.

5. Proposed minimal fix (test-only): widen the deadline in test_channel.py:233, e.g.
   `asyncio.wait_for(self.channel.supervise(), 3)`. Alternative (production, only if the owner wants
   sub-second detection): reorder supervise() to ping before the first wait. Not recommended: larger behaviour
   change for no demonstrated need.

6. Confidence: high on cause; medium-high on STALE_TEST classification (cannot rule out that the author
   intended ping-first, but nothing in docs supports that, and current behaviour is safe).

---

## 3. test_old_telegram_revision_is_rejected

1. Verdict: STALE_TEST (the assertion position/contract is wrong), medium confidence. A small optional
   production hardening is possible but not required.

2. Root cause
   - Test: `tests/unit/test_database.py:66-72`. After `acquire(71)` raises "Migrasi AI belum sesuai" (correctly
     rejects `0001_telegram`; message and rollback events assert fine), line 71 asserts
     `self.assertFalse(self.database.owned)` BEFORE `await self.database.close()` (line 72).
   - Source: `app/core/database.py:64-84`. `acquire()` sets `self.owned = True` (line 78) before the version
     check, because `self.transaction()` (lines 86-103) refuses to run unless `owned` is True. When the version
     check raises RuntimeError (line 84) nothing resets `owned`; `transaction()`'s finally (lines 101-103) only
     clears it if the connection is closed/invalidated, which it is not. `owned` is cleared only by `close()`
     (line 111). So after a rejected acquire `owned` is still True until `close()`.
   - Same-file sibling `test_migration_version_missing_rolls_back_and_closes` (lines 56-64) exercises the
     identical code path (version mismatch) and asserts `owned` is False only AFTER `close()` (lines 61-62).
     That test passes. So the two tests from the same commit disagree about the contract, and the one
     matching the actual code design (close clears ownership) is the sibling.
   - Production caller always closes on failure: `start_telegram_channel` (bot.py:238-245) calls
     `close_startup_resources` -> `database.close()` for any exception before a channel exists, and the
     `TelegramChannel` is never constructed (so `ready()` never consults `owned`) when acquire fails. The
     advisory lock is genuinely still held by the connection until close, so `owned=True` is not misleading
     while the connection is open.

3. Evidence
   - Raw run: `AssertionError: True is not false` at test_database.py:71.
   - `test_advisory_lock_denial_never_becomes_owned` (line 43) passes only because there `owned` is never set.
   - Mocked scalar side_effect `[True, "0001_telegram"]` yields version != "0001_channels" -> RuntimeError as
     expected; begin events `["begin","rollback"]` assert passes (that line runs before the failing one).

4. Intentional design change: README.md lines 20/135 and docs/channel-schema.md:56 state the active revision is
   `0001_channels` and that databases on `0001_telegram` need a separate migration plan, which confirms the
   rejection of `0001_telegram` is intended (the code does that). No document specifies that `owned` must be
   False immediately after a failed `acquire`. None found for that point.

5. Proposed minimal fix
   - Test-only (preferred): in test_database.py move line 71 below `await self.database.close()` (line 72),
     matching the sibling test and the real lifecycle.
   - Optional production hardening (only if the owner wants "failed acquire never leaves owned=True"): in
     `Database.acquire`, wrap the version check so that on failure `self.owned = False` is set before
     re-raising (lock/connection still released by `close()`). Low value because every caller already closes;
     it would also not change user-visible behaviour or security.

6. Confidence: medium. The two sibling tests conflict, so the "right" contract is a judgment call; I weighted
   the actual implementation (forced by `transaction()` requiring `owned`) and the sibling test. Could not
   verify author intent (single commit, no history).
