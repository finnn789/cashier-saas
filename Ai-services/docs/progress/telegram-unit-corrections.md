# Unit review corrections and focused missing regressions

Same approved tool-free read-only worker thread; source/code already in context.
Return exact minimal old/new edits in supplied edits schema. No source tools/commands.
Allowed paths only your existing8 unit modules. No production edits yet. User has now
explicitly approved second-worker source review; independent feedback below is reviewed
by orchestrator. Keep all current assertion strength and no external dependencies.

Worker B candidate not yet applied:
- test_conversation.py result = Mock() then mappings.__iter__.return_value is invalid:
  ordinary Mock lacks magic methods. Import/use MagicMock for these two iterable results.
- sender.send.side_effect = Delivery(...) in UNKNOWN/FAILED test is invalid: AsyncMock
  treats noncallable side_effect as an iterator. Set side_effect=None and return_value,
  or a small typed async callback preserving external-I/O-outside-transaction assertion.
- test_channel.py startup calls mutate aiogram logger level and sqlalchemy.engine
  disabled globally; record/restore through addCleanup in setUp so tests stay isolated.

Add focused regressions to existing Worker A files (already applied, unchanged from
your proposal). Exact insertion at stable anchor; don't replace whole files.

test_ai.py: interrupted body iterator (partial chunk then ClientError/TimeoutError)
gives safe nonretryable AI_TRANSPORT_UNKNOWN and exits context with one POST;
CancelledError during body read propagates and exits context; valid response EXACTLY
MAX_RESPONSE_BYTES succeeds while one byte over fails (proper8192-byte chunk fake);
maximum emoji current/profile plus history fits content byte budget and retains complete
newest pairs. Add malformed very long numeric Retry-After regression: 429 with5000
decimal digits must remain retryable AI_RATE_LIMIT with a bounded delay, not classified
as invalid successful response. This may currently FAIL due Python int conversion
limit; DO NOT weaken assertion or edit production to pass. Report likely bug accurately.

test_telegram_intake.py: commit-exit OperationalError after repository succeeds must
retry same update/message IDs; unknown commit exception/ownership loss must signal
fatal and suspend until task cancelled, with generic logs. Consecutive6 supported
aborts delays1,2,4,8,15,15 outside transactions. Cancellation during retry sleep
propagates without another attempt or fatal flag/log. Preserve real asyncio event
loop; use module-local proxy for controlled sleeps, finite waits, awaited task cleanup.

Use reasonable local helpers, no SQL emulator/lambdas/dynamic tests. Keep type hints.
Progress must distinguish source-authored tests from actual execution. Tests have NOT
been run by you; original31 runner pass evidence remains valid. Do not create report-task.
