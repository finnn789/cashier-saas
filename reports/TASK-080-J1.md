# TASK-080 J1 report (Java read models)

Status: implemented, not compiled, no tests run (policy).

## Changed files (all under src/main/java/org/lite/project/cashiersaas/)
- service/FnbSalesReportService.java: `report()` now = guard + `assemble(tenantId, outletId, query, status, method)`; new public `summarize(MerchantAccessContext, LocalDate, LocalDate, int topProducts)` builds a filter (page 0, size 1, no status/method) and calls the same `query(...)` validation + `assemble(...)`. No duplicated query code; report() output unchanged.
- service/FnbOwnerInsightService.java (new): `salesInsight(ctx, start, end)`, `restock(ctx)`, `anomalies(ctx, date)`, all `@Transactional(readOnly = true)`, no guard.
- dto/response/FnbOwnerSalesInsight, FnbRestockSuggestion, FnbCashierAnomaly (new, `@Getter @Setter`).
- repository additions: FnbShiftRepository (`closedTotals`, `absCashDifferenceByUser`, nested `ClosedTotals`), FnbCashExpenseRepository (`totalForOutlet`), AuditLogRepository (`countByActor`), FnbOrderRepository (`discountedPaidOrdersByReceiver`), FnbInventoryRepository (`netDeltaByItem`). All tenant + outlet scoped, grouped JPQL.

## Result class fields (for J2 mapper)
- FnbOwnerSalesInsight: `FnbSalesReportResponse report`, `long discountedOrderCount`, `BigDecimal expensesTotal`, `BigDecimal cashDifferenceTotal` (signed sum), `long closedShiftCount`.
- FnbRestockSuggestion: `UUID inventoryItemId`, `String name`, `String unit`, `BigDecimal quantityOnHand, reorderLevel, averageDailyUsage, suggestedQuantity`, `InventoryBalanceEntity.Status stockStatus` (enum NEGATIVE/OUT/LOW/OK; use `.name()`).
- FnbCashierAnomaly: `UUID staffUserId`, `String fullName`, `String kind` (CANCELLATIONS|DISCOUNTS|CASH_DIFFERENCE), `BigDecimal value` (count or amount), `BigDecimal baselineDailyAverage` (scale 2).

## Contract notes
- Report mapping to proto: top 5 products come from `report.topProducts`; labels from `report.paymentBreakdown`; refund/cancellation/COGS from `report.summary`.
- Range validation (end >= start, < 366 days, zone) throws `InvalidOperationException` via the reused `query(...)`; `salesInsight` calls `summarize` first, so it validates before extra queries. `anomalies` only parses the outlet zone (InvalidOperationException if invalid); it does not range-check `date`.
- Discounted orders: payments paid in range, order PAID and not CANCELLED, discount > 0 (same basis as the sales report), counted per `payment.receivedBy`; salesInsight sums that map.
- Cancellations: audit `FNB_ORDER_CANCELLED`, same outlet, `createdAt` in range, grouped by audit actor (staff user).
- Cash difference: CLOSED shifts with `openedAt` in the outlet-local day range (business date), |difference| summed per membership user.
- Restock: active items only; missing balance row = on hand 0, status OUT; items with suggested > 0 or status != OK returned, sorted NEGATIVE, OUT, LOW, OK then name; one grouped usage query (SALE, SALE_REVERSAL, WASTE over last 14 x 24 h).
- Anomalies: cashiers from existing `TenantMembershipRepository.listCashiersForOutlet` (CASHIER role only, includes inactive cashiers; owners excluded). Six grouped queries total (3 metrics x day/baseline). Rule uses the scale-2 average: value >= minimum AND value > 2 x average. Baseline window = 30 outlet-local days before `date`.

## Deviations / open questions
- Reused `TenantMembershipRepository` (not in my reserved list) read-only, no edit.
- `stockStatus` is an enum, `kind` a String; J2 must adapt if it assumed otherwise.
- `Instant.now()` used directly for the 14-day usage window (no Clock bean), so tests need seeded movements relative to now.
- Not compiled; JPQL (`select ... s.membership.user.id`, `type in :types`) unverified against Hibernate until the orchestrator compile.
