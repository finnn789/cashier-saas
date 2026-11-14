# TASK-079 W5 report: integration tests

Status: written, NOT compiled or run (Maven/Docker forbidden by rule).

## Files
- New `src/test/java/org/lite/project/cashiersaas/integration/fnb/FnbCostAndStockCountIntegrationTest.java` (6 tests, existing harness `AbstractPostgresIntegrationTest` + `ApiTestSupport`; other helpers are small private methods).
- Edited `integration/fnb/FnbInventoryFlowIntegrationTest.java`: test `deactivatedIngredient...` encoded the OLD TASK-061 behaviour (inactive ingredient still deducted); updated to the M6 rule (renamed to `...IsNoLongerDeducted`, 0 SALE movements, balance stays 100).

## Coverage
1. V9 columns + flyway version 9; restock 1000@10 then 3000@14 -> average 13; owner list shows average; order (qty 2, product recipe + option recipe) -> `cost_amount` 2536.00, SALE `unit_cost` 13 / 8; unpriced RESTOCK records average 13 and keeps it; WASTE records average; `unitCost` on WASTE -> 400.
2. Deactivated ingredient: not deducted, cost = active only (150.00); item with only inactive recipe -> `cost_amount` null.
3. Owner refund: SALE_REVERSAL `unit_cost` = SALE `unit_cost` (12.5) even after the average moved to a later restock at 20; stock back.
4. Cost visibility: cashier may restock with price (average becomes 15), cashier list/movements/restock response show no cost; owner sees average and movement unit cost.
5. Stock count: owner 201, ADJUSTMENT reason "Stok opname", unit cost = average, zero-diff line has no movement; replay 201 with identical `data` and no second effect; same key different payload 409; cashier 403; other tenant item 404 (no line applied); duplicate/empty/negative 400; note -> "Stok opname: Akhir bulan".
6. Report (Asia/Jakarta): summary transactionCount/netSales/COGS 150.00/grossProfit/itemsWithoutCost 1/refundCount 1/refundAmount; topProducts cost + profit (Nasi 29850, Teh 20000 with itemsWithoutCost 1); `hourly` expected buckets computed from the payments' own `paid_at` in Jakarta (asserts hour != UTC hour); `cashiers` order and numbers; `shifts` (cashier CLOSED with closedAt, owner OPEN) numbers.

## Risks (unverified)
- JSON paths with filters (`$.data[?(@.id=='..')]`, `@.unitCost==20`) and `JsonPath.<Map<String,Object>>read` are untested; numeric JSON values are parsed via `BigDecimal(String.valueOf(..))`.
- Assumes closing the cashier shift with `countedCash` = sum of the cashier's order totals returns 200, and that `payments.paid_at` is timestamptz readable as `Timestamp`.
- Assumes 400 for validation/`InvalidOperationException` (same as existing inventory tests) and `/merchant/**` returns 403 for a cashier token.
- Test relies on default tax/service charge not affecting `subtotal - discount_amount` (net sales is read from the DB, not hard-coded); product gross sales 30000/20000 assume no option price.

## Fix round 1
`stockCountAdjustsInOneBatch...` replay assertion compared raw JSON maps; the zero-difference line differs only in BigDecimal scale (0.0 vs 0, 50.0 vs 50). Now compared per line: inventoryItemId, name, unit, movementId exactly; previousQuantity, countedQuantity, difference numerically (`compareTo`). "No second effect" checks (balance, ADJUSTMENT count) kept. Test file only; not compiled or run.
