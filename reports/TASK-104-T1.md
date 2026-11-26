# TASK-104 T1 report

Status: DONE, test status NOT RUN (orchestrator runs). Production code untouched. J1 had already landed the ascending-UUID double lock in `FnbCatalogCopyService` (read before writing the lock-order test).

## Existing tests modified (under src/test/java/org/lite/project/cashiersaas/)
- `unit/service/FnbInventoryServiceTest`: added `@Mock AuditLogRepository` (recordMovement now audits); `guard.requireOwner` stubs for createItem -> `guard.require(.., INVENTORY_ITEM_CREATE)`; movement stubs/replay/unknown-item -> `guard.require(.., STOCK_MOVE)` (ADJUSTMENT -> `STOCK_ADJUST`) via a private `movementPermission` helper in the class. updateItem/deactivateItem/items/movements keep `requireOwner`/`requireOutlet` (unchanged in production).
- `unit/service/FnbInventoryCostAndStockCountTest`: added `@Mock AuditLogRepository`; movement paths (`stubMovementPath`, waste/adjustment, unit-cost rejection, both replay tests) -> `guard.require(.., STOCK_MOVE | STOCK_ADJUST)`. stockCount, deactivateItem, items, movements unchanged.
- `unit/service/FnbCatalogRecipeServiceTest`: `context` now carries an owner membership (price check reads the role; it was null before) and a cashier principal/context was added; `updateProduct`/`updateOption` tests use `guard.require(.., CATALOG_EDIT)` via new `stubCatalogEdit()`; the owner-only parameterized denial test became `useCasesAskTheirGuardAndTouchNothingWhenDenied(Call, Permission)` (recipes -> requireOwner, updateProduct/updateOption -> CATALOG_EDIT).
- `unit/service/FnbOptionGroupServiceTest`: createGroup/updateGroup/replaceProductGroups tests use `guard.require(.., CATALOG_EDIT)` (`stubCatalogEdit()`); archiveGroup/createOption stay `requireOwner`; the cashier-denied parameterized test now takes the permission each call must ask for (archiveGroup = owner-only).
- `unit/service/MerchantBusinessSettingsServiceTest`: three new invalid-cap scenarios (`cap-negative`, `cap-over100`, `cap-scale`) in the existing `rejectsInvalidValuesBeforeMutation`; `omittedTargetMarginLeavesTheCurrentTargetAndWritesNoAudit` extended and renamed to cover the omitted cap too.
- `integration/fnb/FnbInventoryFlowIntegrationTest`: in the existing roles test, added a cashier priced restock via `/cashier/...` (201, `unitCost` null, `marginWarnings` empty, stock list `averageUnitCost` null); comment updated (adjustment is denied by STOCK_ADJUST).

## Behaviours covered -> test
- Guard `require` allowed role returns context / CASHIER on STOCK_ADJUST -> 404: `MerchantAccessGuardTest.cashierGetsTheContextOnlyForPermissionsGrantedToCashiersAndOtherwiseNotFound` (parameterized).
- Cashier price change rejected "Kasir tidak dapat mengubah harga." (product and option): `FnbCatalogRecipeServiceTest.cashierCannotChangeAProductOrOptionPriceAndNothingIsAudited`.
- Cashier same-price edit passes: `FnbCatalogRecipeServiceTest.cashierCanEditEverythingButThePriceOfAProductOrOption`.
- Owner price change still works: already covered by `updateProductPriceChange...` / `updateOptionPriceChange...` tests (now with an owner membership).
- Discount: `FnbDiscountServiceTest.discountAtTheCashierCapOrByTheOwnerIsGrantedAndAudited` (cashier PERCENTAGE at cap, NOMINAL at cap, owner above cap), `cashierDiscountOverTheCapIsRejectedWithTheCapInTheMessageAndChangesNothing` (PERCENTAGE/NOMINAL over cap, cap printed without trailing zeros, cap 0 rejects any), `cashierNominalDiscountOnAZeroSubtotalIsOverEveryCap`.
- Copy: `FnbCatalogCopyServiceTest` -> `sourceAndTargetMustDiffer` (400), `anOutletOutsideTheTenantIsNotFound` (404, target or source), `anInactiveTargetIsAConflict` (409), `aTargetThatAlreadyHasMenuOrIngredientsIsAConflictAndCopiesNothing` (409, products/groups or items), `bothOutletsAreLockedLowestIdFirstBeforeTheEmptinessCheck` (InOrder, both id orderings), `copiesMenuGroupsItemsAndRecipesIntoTheTargetReusingMatchingCategoriesAndAuditsOnce` (counts, category reuse, skipped recipe line, one CATALOG_COPIED).
- Cashier restock allowed, response without cost: unit `FnbInventoryCostAndStockCountTest.pricedRestockBlendsTheAverage...` (cashier row, existing) + new IT assertions above.

## Skipped (already covered)
- Unit "cashier ADJUSTMENT -> 404" and "cashier RESTOCK null unitCost / empty marginWarnings": the former is covered by the guard test above plus the existing IT assertion (`status 404` on cashier ADJUSTMENT) and the permission selection in `movementDeltaSignFollowsTypeAndIsAppliedToTheBalance` (strict stub on STOCK_ADJUST vs STOCK_MOVE); the latter by `pricedRestockBlendsTheAverage...` (cashier row).
- Cashier restock status: the endpoint answers 201, not 200 (existing behaviour).

## Open questions / risks
- The new `FnbDiscountServiceTest` and `FnbCatalogCopyServiceTest` were written without compiling; entity factory signatures were checked by reading. If a signature differs the compiler will point at it.
- `GrpcTenantAccessPolicyTest`, `FnbMenuMarginServiceTest`, `FnbPaymentLabelServiceTest` etc. were not in my reserved list and were not inspected.

Test status: NOT RUN (orchestrator runs).
