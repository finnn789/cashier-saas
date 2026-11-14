# TASK-080 J3 report: daily summary dispatch (Java)

Status: implemented, not compiled, no tests run (policy). Baseline `08ff7af`.

## Files (all under `src/main/java/org/lite/project/cashiersaas/` unless noted)
New:
- `src/main/resources/db/migration/V10__daily_summary_dispatches.sql` (table, UNIQUE (outlet_id, business_date), CHECKs, status index)
- `entity/fnb/DailySummaryDispatchEntity` (MutableBaseEntity; `markSent()`, `markFailed()`; both increment attempts and set last_attempt_at; java field `triggerType` mapped to column `trigger`)
- `repository/DailySummaryDispatchRepository` (`insertIfAbsent`, `byId`, `retryable`, plus `countOpenShifts` and `activeFnbOutlets` so `FnbShiftRepository` stays untouched)
- `service/DailySummaryService` (`onShiftClosed`, `runFallback()`, package-private `runFallback(Instant now)` as the test seam, `dispatch`)
- `service/DailySummaryClient` (single attempt, `send(tenantId, outletId, date) -> boolean`, `isEnabled()`)
- `component/DailySummaryRequestedEvent`, `component/DailySummaryListener` (AFTER_COMMIT, single-thread executor, test-seam constructor), `component/DailySummaryScheduler` (`@ConditionalOnProperty app.daily-summary.enabled`, fixedDelay `${app.daily-summary.fallback-interval:PT10M}`)
- `configuration/SchedulingConfiguration` (`@EnableScheduling`)

Edited:
- `service/FnbShiftService`: field `DailySummaryService dailySummary` + ONE call `dailySummary.onShiftClosed(context, shift)` inside the private `close(...)` (after the audit persist), which both `closeCurrent` and `forceClose` use, so one line covers both.
- `configuration/AiServiceProperties`: added `dailySummaryReadTimeout` (default 30s) for `app.ai-service.daily-summary-read-timeout` (this file was not in the reserved list but the spec requires the property; minimal addition).
- `application.properties` (read timeout, `app.daily-summary.enabled`, `fallback-interval`), `application-test.properties` (`app.daily-summary.enabled=false`), `.env.example` (3 vars).

## Behaviour notes
- Enabled = `app.daily-summary.enabled` AND AI base URL set AND API key set. `DailySummaryClient.isEnabled()` guards the listener, `dispatch` and `runFallback`; with it off, `onShiftClosed` still inserts the PENDING row (so IT can assert one row per outlet per day) but nothing is sent.
- `onShiftClosed` counts OPEN shifts of the outlet after the close (flushed in `close`), inserts if none open, publishes the event only when a row was inserted. Business date = outlet-local date of `shift.openedAt`; an invalid stored zone falls back to UTC so shift close never fails.
- `runFallback`: outlets with local time >= 23:00 get `FALLBACK` for the local date; inserted rows are dispatched immediately; then rows PENDING/FAILED with attempts < 3 are retried (limit 50).
- `dispatch` uses `TransactionTemplate` in short transactions (read target, HTTP outside any transaction, then mark); never throws; a SENT row is never re-sent.
- Request: `POST {base}/api/v1/internal/daily-summaries`, JSON `tenant_id`, `outlet_id`, `business_date`, header `X-Internal-Service-Key` (reuses `MemberSyncClient.KEY_HEADER`); the key is never logged.

## Deviations / risks
- `retryable` compares `coalesce(last_attempt_at, created_at) < olderThan` (spec: last_attempt_at null OR older). Without this a fresh PENDING row could be retried by the scheduler before the after-commit dispatch ran. A never-attempted row is therefore retried after 10 minutes.
- Client builds its own RestClient (about 8 lines duplicated from `MemberSyncClient.buildRestClient`) because the read timeout differs; the retry loop is not copied.
- With the feature disabled for a while, rows stay PENDING and get retried (attempts < 3) on re-enable, including stale days. Accepted; no age cap.
- `FnbShiftService` has a new constructor dependency: any unit test that constructs it manually or with `@InjectMocks` (for example `unit/service/FnbShiftServiceTest`) needs a `DailySummaryService` mock (T1 scope).
- Fallback sweep loads all active F&B outlets and filters by local time in memory (marked `shortcut:`).
- Not verified: compile, Hibernate validate against V10 (column `trigger` is non-reserved in PostgreSQL), native insert binding of `LocalDate`.

## Amendment A1 (stale rows are not resent)
- `DailySummaryDispatchRepository.retryable(int maxAttempts, Instant olderThan, LocalDate minBusinessDate, int limit)` now also requires `d.businessDate >= :minBusinessDate`.
- `DailySummaryService.runFallback(Instant now)` passes `minBusinessDate = UTC date of now minus 2 days`.
- This resolves the "stale days resent on re-enable" risk above. Nothing else changed; tests not edited; not compiled.
