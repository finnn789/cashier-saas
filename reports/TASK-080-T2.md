# TASK-080 T2 report (Java integration tests)

Status: written, NOT compiled, NOT run (policy: no Maven/Docker). Only `src/test/java/**/integration/**` and this report were touched.

## Files
- `src/test/java/org/lite/project/cashiersaas/integration/grpc/OwnerInsightIntegrationTest.java` (in the `grpc` package because `GrpcServerPortHolder` is package-private; same port-holder pattern as `GrpcSecurityIntegrationTest`).
- `src/test/java/org/lite/project/cashiersaas/integration/fnb/DailySummaryFallbackIntegrationTest.java` (extra small class, needed for the fallback with a fake clock; see below).

## OwnerInsightIntegrationTest (test profile unchanged: `app.daily-summary.enabled=false`, AI base URL blank, no HTTP)
Seeded once through the public API: owner, cashiers main/quiet/vetA/vetB, second tenant; ingredients Beras/Susu/Habis/Gula, products Nasi/Latte (recipes) and Teh; owner sales + refund + waste + cancellations; main cashier cash/non-cash/3 discounted sales, 3 cancellations, 7500.50 petty cash, shift closed 25000 short; vet history cloned into `audit_logs` 5 days back (44 and 45 rows).
- V10: flyway row, table, `uk_daily_summary_dispatches_outlet_date`, status index.
- Service key still required (UNAUTHENTICATED) on the 3 RPCs.
- Owner-only: cashiers (main, quiet, vetA) and an unknown user get PERMISSION_DENIED with the same description as an unknown tenant ("Tenant is not permitted for this service"), for all 3 RPCs; invalid dates from a cashier still give PERMISSION_DENIED; cashier does have outlet access (asserted), so the denial is about the role.
- Tenant isolation both directions (owner B on tenant A and owner A on tenant B denied); owner B sees only its own empty outlet; owner A gets exactly its one outlet.
- Validation: blank/garbage/reversed/366-day dates, blank/malformed user_id => INVALID_ARGUMENT.
- GetSalesSummary vs REST owner report (same range): transaction count, gross/discount/net/total/tax/service charge, COGS, gross profit, items without cost, refund count/amount, cancellation count, payment labels (order and values), top products (first 5); plus hard-coded Nasi qty 3 / COGS 150 and owner extras (discounted orders 3, expenses 7500.50, cash difference -25000, closed shifts 1). Empty day = zeros.
- GetRestockSuggestions: order Susu (NEGATIVE, exact 4.498) / Habis (OUT, 1) / Beras (OK with suggestion; numbers recomputed from the ledger and balance); Gula not listed.
- GetAnomalies: exact set {main: CANCELLATIONS 3, DISCOUNTS 3, CASH_DIFFERENCE 25000 (baseline 0); vetA CANCELLATIONS 3 baseline 1.47}; not flagged: quiet (2 < min), vetB (baseline 1.50, 3 not above 2x), owner (4 cancellations, owners excluded). Days without activity => none.
- Read-only: row counts of 7 tables unchanged across the 3 RPCs.
- Dispatch: another shift open => no row; closing the last shift => exactly 1 row (SHIFT_CLOSED, PENDING, attempts 0, business_date = outlet-local date of the shift's `opened_at`); second open/close the same day => still 1; two outlets => one row each; public `runFallback()` with blank URL is a no-op (no rows, no attempts).
- Midnight safety: `@BeforeAll` sleeps past Asia/Jakarta midnight if started at 23:50 or later; all data is created "today"; REST/gRPC comparison uses today-1..today+1.

## DailySummaryFallbackIntegrationTest (deviation from "keep base URL blank")
The fallback and retry do nothing while the client is disabled, so they cannot be exercised with a blank URL. This class enables the feature with a JDK HttpServer stub (pattern of `MemberSyncIntegrationTest`), scheduler interval PT24H, and calls the package-private `runFallback(Instant)` by reflection with fixed 2031 instants. Cases: 22:59 Jakarta => no row; 23:00 Jakarta => one FALLBACK row, SENT, attempts 1, body/key correct, New York outlet (12:00 local) skipped, second run adds nothing; row from SHIFT_CLOSED blocks fallback; 500 => FAILED, retry after 10 min => SENT attempts 2, and a row older than today-2 days (A1) is not retried; a SENT row is never re-sent. Stub calls are filtered by outlet and business date, so a real-time scheduler tick cannot disturb the counts.

## Assumptions to verify at first compile/run
- Generated Java stubs expose the proto getters (`getDiscountedOrderCount`, `getStockStatus`, ...) as in the contract.
- Restock numbers assume refunds write a SALE_REVERSAL (Beras usage 16, on hand 4) and that selling beyond stock is allowed (Susu -2).
- REST `cancellationCount` counts the 15 seeded cancellations in the range; REST topProducts list order matches the gRPC top 5.
- `GET shifts/current` returns `expectedCash` (as used by existing tests); free tier allows 4 cashiers on one outlet.
- Parameterless reflection access to `DailySummaryService.runFallback(Instant)` (package-private) relies on J3 keeping that signature.
