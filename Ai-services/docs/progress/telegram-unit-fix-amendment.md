# Reviewed regression fix amendment

Same approved tool-free read-only executor. Actual orchestrator run:73 tests,72 pass,
1 failure: ProviderTests.test_very_long_decimal_retry_after_remains_rate_limit reports
AI_INVALID_RESPONSE rather than AI_RATE_LIMIT. This proves the suspected Python int()
conversion limit bug. Do NOT weaken/remove that test. Minimal production correction is
now reserved ONLY app/ai/openrouter.py in addition to tests/unit/test_ai.py and
tests/unit/test_conversation.py. No other app, SQL, config/dependency/Java writes.
Return exact old/new edits and progress in existing edits schema. No tools/test execution.

1. Preserve existing 429/default-delay semantics; parse decimal Retry-After without
   converting arbitrarily many digits into a Python integer. An explicit digit loop
   with small accumulator and early cap3600 is readable and preserves decimal leading
   zero and Unicode decimal behavior. Clamp minimum1. Nondecimal still defaults10.
   Do not use clever one-liners, giant-int limits/global interpreter changes, or disable
   validation. Existing HTTP status classification/no retry on ambiguous timeout unchanged.
2. Extend existing rate-limit delay cases with5000 leading zeros (expected1),5000
   leading zeros plus7 (expected7), Unicode decimal values, and oversized numeric cap.
   Preserve existing long-nine regression. Test helper code supplied earlier unchanged.
3. Strengthen ConversationTests atomic-answer test: current mocked repository.message
   side_effect list returns IDs but does not record transaction active/number. Add a
   small typed capture_message callback checking active connection, recording role/tx,
   returning fixture USER/ASSISTANT UUIDs. Keep tests that intentionally override it
   working. Assert USER prepare transaction1 and ASSISTANT same transaction2 as outbox
   and READY. Add focused rollback regression when outbox raises: second transaction
   rolls back, no READY commit is marked, provider call not repeated. Do not claim real
   PostgreSQL rollback durability from marker fake. Avoid new production changes.

Full existing source/test text is already in your context. Helpers/types readable.
Progress acknowledges73-run failure actual evidence, proposed bug fix, strengthened
transaction marker test and no executor tests/runtime. Prior Java UNIT remains paused.
