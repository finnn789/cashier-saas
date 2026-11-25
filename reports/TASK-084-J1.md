# TASK-084 J1 report

Status: DONE (code written, not compiled, not run). Baseline commit: `20faa7a`.

## Changed files
New:
- `src/main/resources/db/migration/V13__stock_counts_and_leakage.sql`
- `entity/shared/inventory/StockCountEntity.java`, `StockCountLineEntity.java` (factory `record(...)`, no setters)
- `service/FnbLeakageService.java`, `controller/FnbLeakageReportController.java`
- `dto/response/FnbLeakageLineResponse`, `FnbLeakageReportResponse`, `FnbStockCountSummaryResponse`, `FnbOutletLeakageResponse`, `FnbLeakageConsolidatedResponse`

Modified:
- `repository/FnbInventoryRepository.java` (stock count queries, `movementTotals`, `previousCountTimes`, persist overloads, nested `MovementTotal`)
- `service/FnbInventoryService.java` (`stockCount` persists header + one line per item incl. zero difference; header-based replay)
- `service/FnbSalesReportService.java` (only: extracted the existing date-range rule into public `requireValidRange(LocalDate, LocalDate)`; same message and behaviour)
- `dto/FnbInventoryMapper.java` (`stockCountLine`), `dto/response/FnbStockCountResponse.java` (`stockCountId`)
- `FnbInventoryController` was NOT changed: POST is unchanged; the new GET list lives in `FnbLeakageReportController`.

## Contract points done
- Routes: POST stock-counts (existing, now returns `stockCountId`), GET stock-counts?page&size (newest first, size 1..50), GET `/outlets/{id}/fnb/reports/leakage?stockCountId=` (latest when absent, 404 when none or foreign), GET `/reports/leakage?from&to`. All under `/api/v1/merchant` (owner-only by security config), owner via guard, PRO via `PlanEntitlementService.require` (list, report, consolidated; POST not gated, as before).
- `FnbLeakageService.outletsLeakage(UUID tenantId, List<UUID> outletIds, Instant from, Instant to, int topLines)` with the exact contract signature.
- D1-D9: shortage = previous - counted; per-item period from the previous count line of that item (exclusive) to counted_at (inclusive); theoretical = -(SALE + SALE_REVERSAL); waste/restock/manualAdjustment informational; stock-count adjustments excluded from manual by idempotency key prefix `count:`; variance_pct scale 2 HALF_UP; loss = shortage x unit_cost scale 2; threshold 5.00 (strictly greater, on the rounded pct); total_loss sums positive losses only; consolidated and per-outlet sorted by total loss desc.
- Replay: header looked up by (tenant, idempotency key); same fingerprint returns the stored lines and the same `stockCountId`; different fingerprint or outlet is 409. Counts recorded before V13 (movements, no header) still replay through the old movement path and return `stockCountId` null.
- counted_at is taken after every balance is locked, so a concurrent sale is either in the previous quantity (occurred before counted_at) or occurs after it.

## IMPORTANT for J2 / orchestrator
`outletsLeakage` treats the range as half-open: `from <= counted_at < to`. `to` must be the start of the day AFTER the last day (outlet/tenant zone), like `end.plusDays(1).atStartOfDay(zone)`. The packet wrote "[from, to]"; I chose half-open to match the sales report convention. REST consolidated does the per-outlet-zone conversion itself.

## Deviations
- `stock_counts.note` is varchar(500), not 255, because the existing request allows 500 characters. `note` is null when blank.
- `stock_count_lines` has a `created_at` column (not in the packet contract) because the entities extend `BaseEntity` which maps it. Added CHECK `unit_cost >= 0`.
- variance_pct is null and the line is flagged when theoretical usage is <= 0 (packet said = 0); a negative net usage (refunds from an earlier period) would otherwise hide a shortage.
- Consolidated default range when from/to are omitted: last 30 days ending today in the caller's outlet zone (packet did not specify the REST default). Range validation reuses the sales rule (< 366 days).
- `GET stock-counts` is in `FnbLeakageReportController` (same path) instead of `FnbInventoryController`.

## Existing tests that will need updating
- `FnbCostAndStockCountIntegrationTest` (stock count response now has `stockCountId`; replay test with only zero-difference lines now yields a header). Any test asserting the exact table list / Flyway version 12 (check `FlywayMigrationTest`-like tests for V13).
- Hibernate `ddl-auto=validate`: new entities must match V13 (checked by reading, not run).

## Open questions
- Per-ingredient threshold and per-shift breakdown deferred (D6, D10).
- A manual ADJUSTMENT whose user-chosen idempotency key starts with `count:` would be hidden from `manualAdjustment` (informational only).

Test status: NOT RUN (policy).
