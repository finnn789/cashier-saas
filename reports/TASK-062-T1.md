# TASK-062-T1 report: FnbStockService unit tests

Status: written, NOT compiled/run (Maven forbidden for workers).

## File written
- `src/test/java/org/lite/project/cashiersaas/unit/service/FnbStockServiceTest.java` (only reserved file + this report)

## Tests (all `@Tag("unit")`, `MockitoExtension`, mocks: FnbOrderRepository, FnbInventoryRepository, PublicOrderTokenService)
1. `orderWithoutAnyRecipeReturnsEmptyAndTouchesNoStock` - no recipe: empty list; `balances`, `movementByKey`, `lock`, both `persist` overloads never called.
2. `productRecipeDeductsQuantityTimesRecipeAndPersistsOneSaleMovement` - qty 2 x 18 = 36 deducted (100 -> 64); one movement: orderId, SALE, -36, key `sale:{orderId}:{ingredientId}`, actor membership id, fingerprint = `tokens.digest("sale|orderId|ingredientId|36.000")`, tenant/outlet/item/reason "Penjualan ORD-1"; balance locked.
3. `productAndOptionRecipesOnSameIngredientAreSummedIntoOneMovementUsingParentLineQuantity` - line qty 3: 3x20 + 3x5 = 75 in ONE movement (balance 1000 -> 925), one lock.
4. `twoIngredientsCreateTwoMovementsAndLockBalancesInAscendingIdOrder` - recipe/balances returned in reverse order; `InOrder`: lock(low), persist, lock(high), persist; two movements with right items/deltas.
5. `linesWithNullProductOrNullOptionAreSkippedWithoutError` - null-product line and null-option selection skipped; only the normal line deducted; `optionRecipes` called with empty list.
6. `existingMovementWithSameKeySkipsThatIngredientWithoutSecondDeductionOrWarning` - existing key -> no lock, balance unchanged, no warning (balance deliberately low), other ingredient still processed.
7. `missingBalanceRowIsCreatedAndPersistedNotLockedThenApplied` - balance created via `InventoryBalanceEntity.create` (tenant/outlet/item) and persisted, `lock` never called, final quantity -18, warning "(minus)".
8. `stockMayGoNegativeWithoutBlockingPaymentAndWarnsWithNameAmountAndMinus` - balance 10, need 36 -> -26, no exception, warning exactly `Susu: sisa -26 ml (minus)`.
9a. `exactlyZeroRemainingWarnsHabisWithUnit` - `Gula Aren: sisa 0 ml (habis)`.
9b. `remainingAtOrBelowReorderLevelWarnsMenipisAndAboveWarnsNothing` - 90 (<100) and exactly 100 -> "(menipis)"; 101 -> no warning; units in text.
10. `recipesAndOrderRowsAreFetchedWithSingleBatchedCalls` - two lines, three option selections (one product-option on two lines); `items`, `itemOptions`, `productRecipes`, `optionRecipes`, `balances` each `times(1)` with distinct id lists; totals 25 g / 14 ml summed correctly.

## Not covered / notes
- Nothing from the T1 list left out. Behaviour 9 is split into two tests.
- Suspected production issues: none found. The packet's key `sale:{orderId}:{itemId}` was interpreted as the INVENTORY item (ingredient) id, matching TASK-061 contract and the code (`sale:{orderId}:{inventoryItemId}`), not the order line id.
- Fingerprint uses `delta.toPlainString()` of the NON-stripped delta (e.g. `36.000` when recipe quantity has scale 3); test 2 asserts that exact string, so it depends on recipe quantities being built as `new BigDecimal("18.000")`. If production switches to `stripTrailingZeros`, test 2's digest stub must change (the stub mismatch would make `digest` return null and the fingerprint assertion fail).
- Test 8/9 depend on the warning format `name + ": sisa " + stripTrailingZeros().toPlainString() + " " + unit + " (label)"`.

## Assumptions about signatures
- `FnbStockService(FnbOrderRepository, FnbInventoryRepository, PublicOrderTokenService)` via constructor injection (`@InjectMocks`).
- `FnbOrderRepository.items(UUID, UUID, List<UUID>)`, `.itemOptions(UUID, UUID, List<UUID>)`; `FnbInventoryRepository.productRecipes/optionRecipes/balances(UUID, UUID, List<UUID>)`, `movementByKey(UUID, String)`, `lock(InventoryBalanceEntity)`, overloaded `persist(...)`.
- Entities built with public no-arg constructors + `ReflectionTestUtils.setField` for private ids/fields (`id`, `orderNumber`, `product`, `quantity`, `orderItem`, `productOption`); factories `InventoryItemEntity.create`, `InventoryBalanceEntity.create/apply`, `RecipeLineEntity.forProduct/forOption`, `InventoryMovementEntity.record`, `OutletEntity.open` exactly as in production source.
- Mockito default returns (empty Optional/List) are relied on for unstubbed `movementByKey`, `productRecipes`, `optionRecipes`; stubs elsewhere use exact args (strict stubs).
