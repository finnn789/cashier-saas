# TASK-080 T1 report (Java unit tests)

Status: written, NOT compiled, NOT run (policy: no Maven). Baseline 08ff7af plus J1/J2/J3 (J3-A1 `retryable(int, Instant, LocalDate, int)` signature used).

## New files (src/test/java/org/lite/project/cashiersaas/unit/)
- service/FnbOwnerInsightServiceTest: restock formula (7d cover + reorder - on hand, avg = usage/14), clamps (negative usage, negative suggestion), scale 3 HALF_UP, missing balance row = OUT/0, ordering NEGATIVE/OUT/LOW/OK then name, single grouped usage query (types SALE/SALE_REVERSAL/WASTE, ~14d window); anomaly rule D3 (minimums 3/3/20000, strictly > 2x baseline, baseline = 30d total / 30 scale 2, kind order, owners excluded, outlet-local windows, invalid zone); salesInsight totals and range-validation-first.
- grpc/GrpcInsightMapperTest: all sales-summary fields, plain-string money (1E+5 -> 100000), ISO dates, null -> ""/"0", restock, anomalies.
- grpc/CashierInsightGrpcHandlerTest: bad/blank/reversed dates and range >= 366 => INVALID_ARGUMENT (365 ok, single day ok), PERMISSION_DENIED wins over argument errors, null request, outlet access failure skipped, INTERNAL fixed message.
- service/DailySummaryServiceTest: last shift => insert + one event; other open shift => nothing; second insert => no event; outlet-local business date; invalid zone => UTC; fallback 22:59 vs 23:00, per-zone dates, disabled no-op, retry args `(3, now-10min, utcToday-2, 50)`; dispatch SENT/FAILED, attempts, SENT never resent, never throws.
- service/DailySummaryClientTest: disabled (blank URL, blank key, flag off, Spring ctor builds no client), 2xx sent (request/headers/body contract), 4xx/5xx/IO => false, single attempt, trailing slash, key never logged.

## Edited existing tests
- grpc/CashierGrpcHandlerRejectionTest, grpc/GrpcTenantAccessPolicyTest: new 3rd constructor arg `TenantMembershipRepository`; policy test gained requireOwnerOutlets cases (owner ok, cashier/inactive/no-outlet => identical PERMISSION_DENIED, bad user id, member path never reads membership role).
- service/FnbShiftServiceTest: `@Mock DailySummaryService`; closeCurrent and forceClose tests verify `onShiftClosed(context, shift)`.
- No test constructs `CashierGrpcService`; FnbPaymentServiceTest/FnbRefundServiceTest/FnbSalesReportServiceTest need no change (mock FnbShiftService; no new FnbSalesReportService deps).

## Notes / suspected issues
- `DailySummaryService.runFallback(Instant)` is package-private; the unit tests live in `unit.service`, so they call it via `ReflectionTestUtils.invokeMethod`. Making it public would allow direct calls.
- `retryable` min date is computed in UTC (`now` UTC date - 2), not outlet-local; the test pins that behaviour.
- Rule D3 uses the scale-2 rounded baseline: a baseline that rounds down (e.g. 1.004) is compared against 2.00 rather than 2.008 (negligible).
- `FnbOwnerInsightService.restock` uses `Instant.now()` directly, so the window test asserts a tolerance range.
- Not verified: compile (mapper/handler tests need generated gRPC classes), mock behaviour of `TransactionTemplate` with a mocked `PlatformTransactionManager`.
