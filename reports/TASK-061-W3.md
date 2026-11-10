# TASK-061 W3 report: recipe endpoints in the catalog slice

Status: SUBMITTED (not compiled, not tested: policy, orchestrator compiles).

## Changed files
- `src/main/java/org/lite/project/cashiersaas/entity/shared/audit/AuditLogEntity.java`: added `FNB_RECIPE_CHANGED("RECIPE")` after `FNB_OPTION_PRICE_CHANGED`.
- `src/main/java/org/lite/project/cashiersaas/dto/request/FnbRecipeRequest.java` (new): `lines` (`@Valid @NotNull @Size(max=50)`) + nested `Line`.
- `src/main/java/org/lite/project/cashiersaas/dto/response/FnbRecipeResponse.java` (new): `lines` + nested `Line {inventoryItemId, inventoryItemName, unit, quantityPerUnit}`.
- `src/main/java/org/lite/project/cashiersaas/dto/FnbCatalogMapper.java`: `recipe(List<RecipeLineEntity>)` + private `recipeLine`.
- `src/main/java/org/lite/project/cashiersaas/service/FnbCatalogService.java`: inject `FnbInventoryRepository inventory`; `replaceProductRecipe`, `productRecipe`, `replaceOptionRecipe`, `optionRecipe`; ONE private `replaceRecipe(...)` shared by product/option PUT (takes `Runnable clear` + `BiFunction` line factory).
- `src/main/java/org/lite/project/cashiersaas/controller/FnbCatalogController.java`: 4 endpoints under `/merchant/outlets/{outletId}/fnb/products/{productId}/recipe` and `.../groups/{groupId}/options/{id}/recipe` (PUT + GET each).

## Reuse / generalization
- Extracted the option lookup + `getOptionGroup().getId().equals(groupId)` check from `updateOption` into private `option(tenant, outlet, productId, groupId, id)`; `updateOption` now calls it (same behaviour: group() check, option lookup, ownership check, same ordering) and recipe endpoints reuse it.
- `recordPrice` now delegates to new private `recordAudit(principal, outletId, id, action, reason, details)` (same persisted values); recipe change uses `recordAudit` with reason null and details `lines=<n>`.

## Behaviour
- All 4 endpoints: `guard.requireOwner`, then `product()` / `option()` (tenant+outlet scoped, 404 otherwise).
- PUT: validate every line first (duplicate inventoryItemId -> `InvalidOperationException("Bahan resep tidak boleh duplikat.")`; `inventory.item(tenant, outlet, id)` else `ResourceNotFoundException`), only then `deleteProductRecipe/deleteOptionRecipe` (flushes), persist new `RecipeLineEntity` lines, audit. Empty list clears the recipe. Response lines are in request order.
- GET: `productRecipes/optionRecipes(tenant, outlet, List.of(id))` ordered by createdAt, mapped in a readOnly transaction (lazy inventoryItem read is safe).
- Messages: "Resep produk diperbarui." / "Resep produk diambil." / "Resep pilihan diperbarui." / "Resep pilihan diambil."

## Verified by reading
- RecipeLineEntity factories, FnbInventoryRepository signatures, InventoryItemEntity getters (`getId` via BaseEntity, `getName`, `getUnit`), AuditLogEntity.recordAction, imports/generics (no wildcards, no records, no @Deprecated).
- Not run: Maven, tests (policy).

## Remaining / risks
- Orchestrator compile needed. If `BaseEntity` lacks `getId()` on `InventoryItemEntity` the mapper would fail (other mappers call `getId()` on entities, so expected fine).
- Replace-then-insert of an identical (product, item) pair relies on the repository's flush in delete (W1 provides it).
- No deviations from the contract.
