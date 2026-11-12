# TASK-062-T4: FnbInventoryFlowIntegrationTest

Status: written, NOT compiled or run (Maven/Docker are orchestrator-owned).

## Files written / changed
- NEW `src/test/java/org/lite/project/cashiersaas/integration/fnb/FnbInventoryFlowIntegrationTest.java` (13 `@Test`, `@TestInstance(PER_CLASS)`, extends `AbstractPostgresIntegrationTest`, `truncateTenantData()` in `@AfterAll`).
- ADDITIVE helper in `src/test/java/.../integration/support/ApiTestSupport.java`: `MerchantFixture onboardCashier(MerchantFixture owner, String label)`. No existing helper was changed. `MerchantFixture` untouched.

## Helper added and why
No cashier creation existed anywhere in `src/test/**` (`SecurityAuthorizationIntegrationTest` only mints JWTs with random ids, which would not resolve a real membership). `onboardCashier` uses the real API: register (account token) -> `PUT /onboarding/intent {"intent":"CASHIER_ONLY"}` -> owner `POST /merchant/outlets/{o}/cashier-invitations {"email"}` (reads `$.data.code`) -> cashier `POST /onboarding/cashier-invitations/claim {"code"}` -> `POST /auth/select-merchant {"tenantId","outletId"}` (reads `$.data.accessToken`). Returns a `MerchantFixture` with the owner's tenant/outlet and a CASHIER bearer.

## Test methods and what they prove
1. `paidOrderDeductsProductAndOptionRecipeWithOneSaleMovementPerIngredient`: kopi 18/unit on product + 18 on option "Extra Shot", susu 100/unit; qty 2 with option, CASH. `stockWarnings` empty; kopi 1000 -> 928 (summed into ONE SALE movement, delta -72, key `sale:{order}:{item}`), susu 500 -> 300; exactly 2 SALE rows with `order_id`; `SUM(quantity_delta)==quantity_on_hand`; list shows OK; history endpoint shows the SALE with orderId.
2. `stockMayGoNegativeWithoutBlockingPaymentAndIsReportedAsNegative`: stock 10, need 36: payment PAID, warning exactly `Susu: sisa -26 ml (minus)`, balance -26, list `NEGATIVE`, `?status=NEGATIVE` returns only it, `OK`/`LOW` filters empty.
3. `stockAtOrBelowReorderLevelWarnsLowAndExactlyZeroWarnsOut`: 100 - 40 = 60 <= 70 -> `Telur: sisa 60 butir (menipis)`, LOW + filter; second order drains to exactly 0 -> `Telur: sisa 0 butir (habis)`, OUT.
4. `mockGatewayDeductsStockOnlyWhenResolvedSucceededAndExactlyOnce`: initiate (PENDING) deducts nothing; resolve FAILED deducts nothing, order stays UNPAID; second attempt resolved SUCCEEDED deducts once (100 -> 64), order PAID; repeating resolve SUCCEEDED (terminal replay) deducts nothing more; ledger invariant.
5. `replayingTheSamePaymentKeyDoesNotDeductTwice`: same key replayed -> same receipt, balance/movement count unchanged; a new key on the paid order -> 409 and no deduction.
6. `productWithoutRecipePaysNormallyWithoutMovementsOrWarnings`: PAID, empty warnings, zero movements for the order, zero SALE rows for the tenant, unrelated ingredient untouched.
7. `cancellingAnUnpaidOrderCreatesNoInventoryMovements`: no movement on order creation nor after cancel (status CANCELLED), balance unchanged.
8. `cashierMayRestockAndWasteButOnlyOwnerAdjustsAndManagesIngredientsAndRecipes`: real cashier; RESTOCK/WASTE 201 with the cashier membership as actor (differs from owner's); cashier ADJUSTMENT -> 404 and no change; cashier list + history 200; cashier POST/PUT/DELETE item and PUT product recipe -> 403 with DB unchanged; owner ADJUSTMENT -30 and +10 work; balance 110, 5 movements, ledger invariant.
9. `movementIdempotencyReplayConflictAndValidationRules`: same key+body -> same movement id (201), balance unchanged, one row; same key different quantity -> 409; same key other ingredient -> 409; SALE, WASTE without/blank reason, ADJUSTMENT without reason, zero/negative RESTOCK/WASTE, zero ADJUSTMENT, missing key -> 400 with nothing applied; blank RESTOCK reason defaults to "Restock".
10. `recipeReplaceIsRepeatableClearableValidatedAndReadable`: PUT twice with identical lines (no duplicates), GET returns name/unit/quantityPerUnit, replace swaps lines, duplicate ingredient -> 400, unknown and other-tenant ingredient -> 404, zero/negative quantity and missing `lines` -> 400, unknown product -> 404; all failed replaces keep the previous recipe (validation before delete); `[]` clears and writes audit `FNB_RECIPE_CHANGED` with `lines=0`; option recipe same rules plus wrong group / wrong product -> 404.
11. `anotherTenantCannotReadChangeDeactivateMoveOrRecipeAnIngredient`: B (own outlet, A's item id) and B (A's outlet path) get 404 for history, update, deactivate, RESTOCK/WASTE/ADJUSTMENT, create, recipe use; A's balance/name/active/movement count unchanged; lists contain only own data; same ingredient name allowed in two tenants.
12. `ingredientNameIsUniquePerOutletAndUnitIsLockedOnceMovementsExist`: duplicate name on create -> 409 (GlobalExceptionHandler maps `DataIntegrityViolationException` to 409), exactly one row and one balance remain; rename onto existing name -> 409; unit change with movements -> 400 (DB unchanged); name/reorder editable with same unit and `initialQuantity` ignored; unit change allowed without movements (stock status OUT).
13. `deactivatedIngredientLeavesListAndNewRecipesButExistingRecipeLineStillDeducts`: DELETE -> `active=false`; gone from list; update/movement/history -> 404; new recipe with it -> 404; existing recipe line still readable and still deducts on payment (100 -> 82, SALE movement), ledger invariant.

## Not covered / limits
- Concurrency is not exercised (packet said none unless existing test does; stock lock behaviour under race is untested).
- `GET .../movements` paging bounds (page/size validation) are not covered here (T2 unit test covers them).
- Public QR orders paying through stock are not covered separately (same `settle` path as POS).
- Not compiled or run, so everything below the "Assumptions" is unverified until the orchestrator runs `mvnw verify`.

## Suspected production issues / risks to watch when running
- #12 relies on `DataIntegrityViolationException` being raised (and translated) from `inventory.flush()` inside `@Transactional` services; if Spring exception translation did not wrap the Hibernate exception, the response would be 500 and the 409 assertion (the intended mapping) would fail; that would be a production gap, not a test bug.
- `FnbStockService.consumeForPaidOrder` skips lines on missing recipes only; a recipe line pointing at a deactivated ingredient still deducts (asserted in #13 as documented behaviour).
- Cashier onboarding (`onboardCashier`) depends on the free-tier onboarding producing an active subscription and FNB-enabled tenant (needed by invitation claim and `select-merchant`); the owner flow already relies on the same data, so it is expected to hold.

## Assumptions (JSON field names / statuses, verified from source)
- Create item 201 `{name, sku?, unit, reorderLevel, initialQuantity?}`, response `{id, sku, name, unit, reorderLevel, active, quantityOnHand, stockStatus}`; movement request `{type, quantity, reason?, idempotencyKey}` -> 201, response `{id, inventoryItemId, type, quantityDelta, reason, orderId, occurredAt, actorMembershipId}`; recipe `{lines:[{inventoryItemId, quantityPerUnit}]}` -> 200 with `lines[{inventoryItemId, inventoryItemName, unit, quantityPerUnit}]`; pay response carries `stockWarnings` (list, empty by default), mock gateway responses carry `attemptId` and `gatewayOutcome`.
- Statuses: validation and `InvalidOperationException` 400; `OperationConflictException` and `DataIntegrityViolationException` 409; `ResourceNotFoundException` 404; cashier on `/merchant/**` 403 (security chain); `api.send(.., 200|201)` treats 200 and 201 as interchangeable like the existing helper.
- Orders are POS `TAKEAWAY` without a table (selectedTable allows it) paid with CASH 1,000,000 tendered (always above total).
- Warning text format `"{name}: sisa {qty} {unit} ({minus|habis|menipis})"` as implemented in `FnbStockService`; asserted exactly.
- DB names: `inventory_items`, `inventory_balances`, `inventory_movements`, `recipe_lines`, `audit_logs(action, details)`.
