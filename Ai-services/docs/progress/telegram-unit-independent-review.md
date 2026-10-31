The supplied tests contain useful assertions for payloads, error classification, intake filtering, and transaction ordering. The regular `session.post` mock returning an async context manager correctly matches aiohttp usage. The reported 31 passing tests are user-provided evidence; I have not executed them.

Prioritize these additional offline regressions:

- **`app/ai/openrouter.py` — malformed `Retry-After` can change failure classification.** A 429 header containing thousands of decimal digits passes `isdecimal()`, but `int()` can raise `ValueError` under Python’s integer conversion limit. The outer handler then reports non-retryable `AI_INVALID_RESPONSE` instead of retryable `AI_RATE_LIMIT`. Add an excessively long numeric header case requiring a bounded rate-limit delay.

- **`tests/unit/test_ai.py` — transport coverage stops at context entry.** `test_ambiguous_transport_is_never_retried` injects failures only through `__aenter__`. Add a body iterator that yields partial JSON and then raises `aiohttp.ClientError` or `TimeoutError`. Assert `AI_TRANSPORT_UNKNOWN`, no retry, one POST, and context exit. Also inject `CancelledError` during reading and require cancellation to propagate unchanged. This covers interruption after the provider may have accepted the request.

- **`tests/unit/test_ai.py` — byte boundaries and Unicode budgeting remain weakly exercised.** Add a valid response of exactly `MAX_RESPONSE_BYTES`, plus one byte over the limit; the existing oversized test alone would pass if the implementation incorrectly rejected the exact limit. For prompts, use maximum-length emoji current input and escape-heavy profile values, with competing history pairs. Assert the content-byte budget and retention of complete newest pairs. Distinguish that budget from the serialized HTTP JSON size, which the current assertions do not measure.

- **`app/channels/telegram/handlers.py` — oversized slash-prefixed input bypasses the text limit.** Command detection precedes the 4000-character check. A slash followed by 4001 characters becomes `UNSUPPORTED`, with the entire command token persisted. Add this case alongside long command arguments and surrounding whitespace. Decide explicitly whether the cap applies to all input or only normalized ordinary text; the current test title does not establish that contract.

- **`tests/unit/test_telegram_intake.py` — persistence failures at transaction exit are uncovered.** Current transient and fatal failures originate in `repository.intake`. Add a transaction fake that raises during commit after intake succeeds. Assert a supported transient abort retries with the same update/message identifiers, while an unknown or ownership-loss failure signals failure and suspends `handle()` until cancellation. This verifies that successful repository execution alone cannot let the handler return before durable intake.

- **`tests/unit/test_telegram_intake.py` — retry growth and cancellation need coverage.** Multiple consecutive supported aborts should produce delays `1, 2, 4, 8, 15, 15`, outside transactions. Cancel during retry sleep and assert no further intake attempt, no fatal-error log, and propagated cancellation. The existing single-abort test cannot detect broken exponential backoff or its cap.

Two claims need narrower wording:

- **`tests/unit/test_config.py`:** mocking `load_dotenv` proves its path, call count, and `override=False` argument. It does not demonstrate actual dotenv/process-environment precedence. The configuration implementations were not supplied, so their broader correctness cannot be independently assessed.
- **`tests/unit/test_telegram_sender.py`:** sender implementation was not supplied. These tests express useful contracts, but this bundle does not permit implementation review.

For Worker B, transaction-aware mocks should prove call order, parameters, and failure behavior. They cannot establish PostgreSQL advisory-lock exclusivity, rollback durability, conflict handling, foreign-key enforcement, or crash recovery against persisted rows. Those remain integration guarantees requiring separate evidence.