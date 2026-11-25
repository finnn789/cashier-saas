# TASK-087 T1 — Tests (written, NOT RUN)

Assertions added to existing tests only; no new classes or helpers.

- `integration/fnb/FnbPayFirstFlowIntegrationTest.posOrderWithIdempotencyKeyThenCashPayment`: create/detail/list `cashierName` = creator ("Owner idemorder"); unpaid → no `methodLabel`/`receiptNumber` (NON_NULL, checked via JSON `contains`); after cash payment list `methodLabel` = "CASH" (fallback), `receiptNumber` = payment response. Exercises `creatorNames` + `paymentsByOrder` + V15 on Postgres.
- `integration/fnb/FnbInventoryFlowIntegrationTest`:
  - `paidOrderDeductsProductAndOptionRecipeWithOneSaleMovementPerIngredient`: product list `categoryName` = "Minuman" (Blaze fetch).
  - new `recipeLineCostEstimateIsQuantityTimesAverageCostRoundedHalfUpAndNullWithoutCost`: null before cost; 2 × 12.3425 → 24.69 (distinguishes HALF_UP from HALF_EVEN) on GET and PUT.
  - `cashierMayRestockAndWasteButOnlyOwnerAdjustsAndManagesIngredientsAndRecipes`: movement list `actorName` for cashier WASTE and owner opening stock (batch `memberNames`).
- `unit/service/FnbCatalogRecipeServiceTest`: unchanged (plain mock returns empty balances; no strict-stub break).

Not covered (accepted): option recipe cost (same code path), `actorName` null for SALE.
