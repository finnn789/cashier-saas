# TASK-109 J2: Java integration test dedup

Test status: NOT RUN (orchestrator runs). Not compiled either; checked by grep only.

Expected result: no RED. This is deletion/refactor of tests, so `test-compile` and the changed classes should pass.

## Behaviours
- Removed `FnbPettyCashOrderIntegrationTest.expensesAreCountedAgainstTheDrawerWhenClosingTheShift`.
  Covered by `pettyCashNeedsAnOpenShiftIsIdempotentAndIsSubtractedFromExpectedCashAtClose` in the same class.
- `FnbInventoryFlowIntegrationTest`: payment part dropped, renamed to `deactivatedIngredientLeavesListAndNewRecipes`.
  Non-deduction covered by `FnbCostAndStockCountIntegrationTest.deactivatedIngredientIsNotDeductedAndAddsNoCost`.
- No tests added.

## Helpers moved to `support/ApiTestSupport`
Static: `readList`, `money`, `assertMoney` (both), `assertStatus`, `orders`, `runInParallel` (static imports at call sites).
Instance (`api.x(...)`): `product`, `count`, `balance`, `saleMovements`, `assertLedgerMatchesBalance`.
`productFor` was merged into `product`. Orphaned imports, `PRICE` constants and `FnbInventoryFlow`'s `status` helper removed.

## Changed files (under `src/test/java/org/lite/project/cashiersaas/integration/`)
- `support/ApiTestSupport`
- `fnb/`: CostAndStockCount, InventoryFlow, LeakageAndMargin, PayFirstFlow, PaymentLabel, PettyCashOrder, Receipt,
  ReusableOptionGroup, ShiftRefund, StockConcurrency (`Fnb*IntegrationTest`)
- `grpc/OwnerInsightIntegrationTest`
- `identity/MerchantOutletIntegrationTest`, `identity/MerchantStaffIntegrationTest`

## Kept local (behaviour differs or name not listed)
- `FnbStockConcurrencyIntegrationTest.runInParallel`: fixed 8-thread pool, other timeout and message.
- `FnbLeakageAndMarginIntegrationTest.product(owner, name, price, itemId, perUnit)` and `count(owner, ...)`: different behaviour.

## Open questions
- `FnbShiftRefundIntegrationTest.simpleProduct` has the same body as `product`, but is not in the packet's name list.
