# TASK-079 W3 report: owner sales report additions

Status: implemented, NOT compiled (no Maven by rule).

## Changed files (under `src/main/java/org/lite/project/cashiersaas/`)
- `repository/FnbSalesReportRepository.java`: new `cost(...)` (COGS + itemsWithoutCost via `paidSalesQuery` + item join), `hourly(...)` (native SQL), `cashiers(...)`, `shifts(...)`; `products(...)` now also selects cost sum and null-cost count; `ProductRow` gets `cost`, `itemsWithoutCost`; new row classes `CostRow`, `HourlyRow`, `CashierRow`, `ShiftRow`.
- `repository/FnbRefundRepository.java`: one added query `totals(tenantId, outletId, start, end)` + `RefundTotals {count, amount}`.
- `dto/FnbSalesReportMapper.java`: `summary(row, cancellations, cost, refunds)` (signature changed, one caller), `products` fills COGS/gross profit (grossSales - cost), new `hourly`, `cashiers`, `shifts`.
- `service/FnbSalesReportService.java`: injects `FnbRefundRepository`; `report` wires summary extras and the three lists. `csv` untouched.
- `dto/response/FnbSalesSummaryResponse.java`, `FnbSalesProductResponse.java`, `FnbSalesReportResponse.java`: new fields.
- New: `dto/response/FnbSalesHourlyResponse.java`, `FnbSalesCashierResponse.java`, `FnbSalesShiftResponse.java`.

## Contract points
- Summary: `costOfGoodsSold` = sum of non-null `cost_amount` (COALESCE 0), `grossProfit` = netSales - COGS, `itemsWithoutCost` = count of included item rows with null cost, `refundCount`/`refundAmount` from `payment_refunds` (tenant+outlet+`refunded_at` in range, no status/method filter).
- topProducts: `costOfGoodsSold`, `grossProfit` = grossSales - COGS (D2, before order discount), `itemsWithoutCost`.
- `hourly`: native SQL, `extract(hour from paid_at at time zone :zone)`, grouped in DB, only hours with data, ascending; same tenant/outlet (payments and orders), date range, PAID, not CANCELLED, optional orderStatus and paymentMethod filters.
- `cashiers`: Blaze, grouped by `payment.receivedBy` (id, fullName), ordered by netSales desc (then name).
- `shifts`: Blaze, LEFT JOIN `payment.cashShift` with tenant/outlet ON conditions and `openedBy`; grouped by shift, ordered by openedAt desc nulls last; payments without shift become one row with null shift fields. `status` is `Status.name()` string.
- netSales everywhere = `subtotal - discountAmount` (same as existing `methods`).

## Deviations / notes
- `FnbSalesReportMapper.summary` signature gained two params; no tests in `src/test` reference it (grepped). W4 should use the new signature. `FnbSalesReportService` now has a second constructor dependency (Lombok `@RequiredArgsConstructor`); any test building it manually needs the extra arg (none found).
- `ProductRow` constructor changed to 6 args (only the repository uses it).
- itemsWithoutCost uses `COUNT(CASE WHEN item.costAmount IS NULL THEN 1 END)`.

## Open questions / risks (unverified, no compile/run)
- Blaze `leftJoinOn("payment.cashShift", ...)`, `orderBy(expr, false, false)` and the CASE inside COUNT in `selectNew` have not been compiled; check at the compile gate.
- Native query binds `Instant` and UUID parameters; Hibernate 7 should handle both, verify in integration tests (hour grouping in a non-UTC zone is worth a case).
- Refund totals ignore outlet-zone vs range subtlety: they use the same `[start, end)` instants as the rest.
- Orders paid before V9 have null cost, so they inflate `itemsWithoutCost` and grossProfit equals netSales for them (accepted limit in packet).
