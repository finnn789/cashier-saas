# Existing approved executor continuation — tests only

Continue in the SAME already-approved read-only tool-free worker thread. NO new
application source is provided or sent to another destination. You already received
the complete app source in your initial approved bundle, including Worker B APIs.
The separate worker launch was denied; do not spawn/call workers or access tools.

Scope amendment: now author ONLY Worker B reserved paths from your original contract:
tests/unit/test_conversation.py, test_database.py, test_repository.py, test_channel.py,
docs/progress/telegram-unit-b-progress.md. Do not modify Worker A files or app source.
Return complete contents in existing output schema, remove_paths empty. Source is
unchanged from your supplied initial bundle. Use standard unittest async/mock.

All original offline, cancellation, finite timeout and Python readability requirements
remain. Preserve realistic SDK exception/method contracts. Do not duplicate source
SQL engine behavior in a fake database. Test real worker orchestration with scoped
SQL text+parameter capture, explicit transaction-active markers, mocked repository
boundary responses and no live DB. Include current USER once/history order, NEW reset,
provider retry/error result, atomic answer/outbox, no AI on delivery retry, processing
crash/unknown send recovery, future retry waits outside transaction, no tail resend.

Test real Database APIs with mocked engine/connection, advisory ownership rejection,
invalidation, close failure/timeout/cancellation/dispose safe outcomes. At least one
channel test uses real Dispatcher.start_polling/stop_polling with a fake or mocked
polling child; actual SDK native start/stop task management should be exercised.
Do not patch global asyncio.sleep; module-local proxy only if needed. Bound all waits
and clean up tasks. Mock settings dotenv and Bot/DB before start_channel functions;
do not instantiate real external clients.

Checkpoint reality: Worker A has been applied and orchestrator executed31 tests,
all passed; your previous reported32 was an off-by-one claim. Do not claim any Worker B
test executed. Explain any production issue through regression evidence, no source edit.
Prior Java UNIT remains paused. Unit mocks do not certify actual PostgreSQL constraints.
