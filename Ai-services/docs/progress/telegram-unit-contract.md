# Offline unit tests — orchestrator contract

Canonical baseline26, user authorizes unit tests on13 October2026 after source
implementation completed. Do NOT resume paused Java UNIT tasks. No TASK.md/report-task.
Workers author exact test contents in read-only/tool-free source-bundle mode;
orchestrator reviews, applies and RUNS offline tests. Never claim tests passed without
actual output. Existing worker child shell policy disallows direct implementation;
do not try shell tools or permission changes. Return files through supplied JSON schema.

## Common boundaries

Standard library unittest.TestCase/IsolatedAsyncioTestCase and unittest.mock only;
no pytest/new dependencies, no actual Telegram/OpenRouter/PostgreSQL/HTTP servers,
no real .env read/print/write, no application lifespan/startup with live dependencies,
no migrations run. All external dependencies replaced with fakes/mocks. Use dummy
credentials and patch load_dotenv before calling load_settings. Construction of a
SQLAlchemy engine must be mocked for database owner tests. Application code imports
allowed only in returned tests after review, not worker execution.

No production/migration/pyproject/lock/README/canonical edits. No nested workers/tools.
Named readable typed helpers, explicit assertions, no clever one-liners/dynamic test
generation/lambdas. Use finite event/timeouts for cancellation tests and always await
owned tasks in finally. Do not patch global asyncio.sleep or asyncio.timeout in ways
that break event loop/test scheduling; replace a MODULE'S asyncio reference with a
small proxy namespace when time control is necessary, preserving real other methods.
Use real SDK exception constructors (source declarations supplied). Assertions should
prove observable invariants, not simply that a MagicMock returns the mocked value.
No huge fake persistence backend which duplicates production SQL semantics. Mocked
SQLAlchemy results may record predicates/parameters but cannot prove actual FK/index
enforcement or real durable restart; state this limitation explicitly.

Checkpoint progress strings: baseline/reservation, authored files, meaningful behavior
covered, test status NOT EXECUTED BY WORKER, remaining risks/likely failures. Do not
weaken behavior assertions to pass. If source has a bug, return failing regression
and explain evidence; production changes require a separate reviewed amendment.

## Worker A reservations and acceptance

Only tests/unit/test_config.py, test_ai.py, test_telegram_intake.py,
test_telegram_sender.py and docs/progress/telegram-unit-a-progress.md.

Cover config environment precedence/.env loaded once with mocked loader, invalid
scope/allowlist and secret-safe repr/errors; prompt history order/newest-pair trimming,
bounded untrusted profile and current user once; provider payload bound/fixed endpoint,
success/error status/invalid oversized response and ambiguous transport no retry;
UTF16 splitting and sender success/429 persistence classification/retry exhaustion/
network UNKNOWN/fatal errors; intake private-human/allowlist for all commands, mutable
profile and fallback name, deep link /start, input cap, injected update ID, transaction
commit before return, transient abort retry within handler, ownership loss and fatal
handler suspension until cancellation without logging original exception details.

Aim ~25-35 focused test methods with subTest where appropriate, not vanity test count.
Fake aiohttp session.post is regular method returning async context manager, not
AsyncMock coroutine; response.content.iter_chunked must be async iterator. No shared
helper file; helpers local to your reserved test modules. Tests independent of workerB.

## Worker B reservations and acceptance

Only tests/unit/test_conversation.py, test_database.py, test_repository.py,
test_channel.py and docs/progress/telegram-unit-b-progress.md.

Cover Repository public APIs with captured real SQLAlchemy text+params: stable identity
upsert/update and duplicate inbox conflict contract, session reuse and NEW scoped reset,
message sequence guard/no insert on scope mismatch, correct reply/outbox scoping.
Worker.process uses real production worker with transaction-aware boundary mocks:
commands /start,/session no reset, /new reset; existing USER reused on retry; history
query parameters/ordering/excluding current and failed turns; provider called AFTER
transaction closes, ASSISTANT+outbox+READY atomic; transient AI scheduled and hard errors
no ASSISTANT; bounded retries; persisted READY delivery never calls model; delivery
SENDING before external send, retry_due outside DB transaction/no busy-query loop,
UNKNOWN/fatal suppresses tail, all successful vs failed delivery terminal status;
recover PROCESSING creates ERROR not regeneration, SENDING UNKNOWN update/tail suppression.
DB public APIs: exclusive lock denied, missing migration version, owner invalidation
prevents reconnect; transaction lifecycle; close/dispose on success, close timeout/
failure/invalidate/cancel, safe errors, always dispose. Use short patched timeouts, no15s waits.
Channel: await_owned caller cancellation waits cleanup before propagation; stop before
startup observation and already failed/finished runner; shared repeated stop; readiness;
worker already cancelled cleanup still closes bot/DB; supervised worker failure stops
poller; startup webhook rejection and cancellation with fake Bot/DB only. At least
one test exercises REAL Dispatcher.start_polling/stop_polling child teardown using
fake bot getUpdates/me or a narrow mocked SDK polling child and real start/stop logic;
clearly describe the mock boundary. Never call Telegram.

Aim ~25-35 meaningful tests; prioritize transaction boundaries/history/session/recovery
and native lifecycle over trivial factories. No full fake SQL database. Tests independent
of workerA. Use unittest.mock spec types to catch misuse and real UUID/UTC data fixtures.
