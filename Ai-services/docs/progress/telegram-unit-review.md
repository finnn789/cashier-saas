# Telegram unit test review — 13 October 2026

Baseline canonical26. User requested unit tests after implementation; offline unit
execution authorized. Java UNIT work remains independently paused. No TASK/report-task.

Ruflo tester registrations are coordination only. Actual authorship: approved tool-free
read-only Codex worker thread01a13368-872f-7bb1-9658-36499251cdd9, supplied non-secret
source bundle. Separate second-worker launch was rejected by automatic approval review
for lack of explicit source disclosure authorization. No source was sent by that
rejected action. Safe continuation uses the same approved context and only task
instructions, without resending source or a new destination. This continuation was approved.

## Reviewed first checkpoint

Four worker-authored files: test_config.py, test_ai.py, test_telegram_intake.py,
test_telegram_sender.py. Spec-constrained dependency mocks, real SDK exception
constructors and real Message validation, mock dotenv, isolated process env, finite
response streams, and explicit transaction boundary fake. Intake failure tests assert
handler remains suspended and owned test tasks are cancelled/awaited in finally.
No live dependencies in the applied test modules.

Protected baseline sources rechecked before application, no differences. Worker
claimed32 test methods; actual unittest runner executed31. Actual evidence wins.
Default sandbox run completed three sync prompt tests but stalled before first async
test setup, then was stopped with Ctrl+C. Approved offline execution completed:
31 tests,0 failures/errors, elapsed0.920s, exit0. Evidence: telegram-unit-a-run.txt.
This environment result does not establish a production bug or live readiness.

Worker is now authoring remaining persistence/worker/database/channel tests from
the original source bundle. Those tests are not yet applied or executed at this checkpoint.
Application/migrations/dependency files unchanged; actual .env not accessed.
Unit mocks do not prove PostgreSQL FK/index behavior, durable restart, or API connectivity.

## Full-suite regression checkpoint

Human subsequently explicitly approved non-secret source sharing with the second
Codex worker. Because test authorship was already continuing in the first context,
the second worker performed an independent source/test quality review. Its findings
are in telegram-unit-independent-review.md; no worker test execution claim accepted.

Reviewed second bundle:33 methods across repository/conversation/database/channel.
Corrected ordinary Mock magic-iteration misuse, invalid AsyncMock side_effect value,
and global logger restoration. Added9 focused AI/intake regressions for interrupted
response bodies/cancellation, exact byte limits, maximum Unicode prompt, malformed
numeric Retry-After, commit-exit failures, capped backoff and retry cancellation.
All corrections authored by worker and applied as exact reviewed replacements.

Actual full offline unittest run:73 tests in2.340s,1 failure,0 errors. Evidence:
telegram-unit-full-first-run.txt. Failure is a production parser edge:429 with a
5000-digit decimal Retry-After triggers Python integer conversion limit, which the
provider adapter misclassifies as nonretryable AI_INVALID_RESPONSE. Assertion retained.

Narrow reviewed fix reservation: app/ai/openrouter.py parser only, plus focused AI
cases and stronger message transaction marker/rollback test. Fix worker running;
no production fix applied or passing full-suite claim at this checkpoint.

## Final reviewed result

Accepted exact worker edits: app/ai/openrouter.py parses Retry-After decimal digits
with a small accumulator capped3600, preserving minimum1/nondecimal default10,
leading zeros and Unicode decimal behavior without converting an unbounded integer.
The failing regression retained. Added long-zero/Unicode cases and a stronger
ASSISTANT/outbox/READY shared-transaction marker plus outbox-failure rollback regression.

Actual final offline execution: **74 tests in2.280s,0 failures,0 errors,0 skips, exit0**.
Runner: project .venv/Scripts/python.exe -m unittest discover -s tests/unit -p test_*.py -v.
Evidence: telegram-unit-final-run.txt. Test outputs are actual orchestrator evidence;
worker checkpoint docs record authorship without claiming worker execution.

New tests reside in8 modules in tests/unit. Standard-library unittest only; no new
dependency. Only reserved production parser path changed; all other application,
migrations, main.py, pyproject.toml and uv.lock match pre-test baseline. README updated
by orchestrator for the reproducible command and actual unit result. Actual .env not
read or written. No live Telegram/OpenRouter/PostgreSQL operations or migrations.

Limits: captured SQL/parameter contracts and transaction markers do not prove actual
PostgreSQL constraints, concurrency, commit durability, advisory lock exclusivity or
crash recovery from persisted rows. Native Dispatcher start/stop task management is
exercised with only its network polling child replaced; Telegram transport is mocked.
No broader live acceptance claim. Paused Java UNIT remains unchanged.

Final reviewed progress persisted in parent canonical MEMORY/TASKS/INDEX, version29.
Earlier checkpoints26/27/28 preserved as history. Initial second-worker rejection was
resolved by explicit human authorization; second reviewer completed, remaining test
authorship continued in the previously approved context. No outstanding approval block.
