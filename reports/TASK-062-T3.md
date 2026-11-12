# TASK-062-T3: FnbCatalogService recipe + refactor-regression unit tests

Status: WRITTEN, NOT RUN (per packet: the orchestrator runs `mvnw verify`).

## File written
- `src/test/java/org/lite/project/cashiersaas/unit/service/FnbCatalogRecipeServiceTest.java` (only reserved test file; no production, pom, .ai or tasks files touched)

Setup: `@Tag("unit")`, `MockitoExtension`, `@InjectMocks FnbCatalogService` with mocks `MerchantAccessGuard`, `FnbCatalogRepository`, `MerchantReferenceRepository`, `AuditLogRepository`, `FnbInventoryRepository`. Entities built with the real factories (`CategoryEntity.create`, `ProductEntity.create(Details)`, `OptionGroupEntity.create`, `ProductOptionEntity.create`, `InventoryItemEntity.create`, `OutletEntity.open`, `new TenantEntity()`, `new StaffUserEntity()`), ids via `ReflectionTestUtils.setField(x, "id", ...)`. Audit assertions use `ArgumentCaptor<AuditLogEntity>` and getters; recipe lines via `ArgumentCaptor<RecipeLineEntity>`; ordering via `InOrder`.

## Group 1: replaceProductRecipe
- replaceProductRecipeRequiresOwnerGuardAndTouchesNothingWhenDenied: guard.requireOwner throws -> NotFound, no catalog/inventory/audit/references interaction.
- replaceProductRecipeRejectsMissingProductWithoutDeleting: product absent -> NotFound, inventory/audit untouched.
- replaceProductRecipeRejectsProductWhoseCategoryIsArchived: archived category -> NotFound.
- replaceProductRecipeRejectsDuplicateIngredientBeforeAnyDelete: duplicate item id -> InvalidOperationException ("Bahan resep tidak boleh duplikat."), neither delete variant, no persist, no audit.
- replaceProductRecipeRejectsUnknownOrInactiveIngredientBeforeAnyDelete: `inventory.item` empty (repo returns active only) -> NotFound, delete never, no persist, no audit (validation precedes delete).
- replaceProductRecipeDeletesOldLinesThenPersistsNewLinesThenAudits: InOrder deleteProductRecipe -> 2x persist(RecipeLineEntity) -> audit.persist.
- replaceProductRecipePersistsProductLinesWithItemAndQuantityInRequestOrder: lines have product set, productOption null, correct item/quantity/tenant/outlet, request order.
- replaceProductRecipeRecordsRecipeAuditWithProductIdNoReasonAndLineCount: action FNB_RECIPE_CHANGED, entityType RECIPE, entityId = product id, reason null, details `lines=2`, tenant/outlet/actor refs.
- replaceProductRecipeReturnsMappedLinesWithNameUnitAndQuantity: response lines (id, name, unit, quantityPerUnit).
- replaceProductRecipeUsesProductVariantOfDeleteOnly: deleteProductRecipe called, deleteOptionRecipe never.
- replaceProductRecipeWithEmptyListClearsRecipeAndAuditsZeroLines: delete called, no persist, no item lookup, details `lines=0`, empty response.

## Group 2: replaceOptionRecipe
- replaceOptionRecipeRequiresOwnerGuardAndTouchesNothingWhenDenied
- replaceOptionRecipeRejectsGroupIdThatDoesNotMatchTheOptionGroup: valid group for the product but option belongs to another group -> NotFound, nothing written.
- replaceOptionRecipeRejectsGroupThatBelongsToAnotherProduct: wrong product/group pair -> NotFound.
- replaceOptionRecipeRejectsMissingOption
- replaceOptionRecipeRejectsMissingProduct
- replaceOptionRecipeRejectsDuplicateIngredientBeforeAnyDelete (shared validation)
- replaceOptionRecipeRejectsUnknownIngredientBeforeAnyDelete (shared validation)
- replaceOptionRecipeDeletesOptionLinesThenPersistsOptionLinesThenAudits: InOrder deleteOptionRecipe(tenant, outlet, optionId) -> 2x persist -> audit; deleteProductRecipe never.
- replaceOptionRecipePersistsOptionLinesNotProductLines: lines have productOption set, product null.
- replaceOptionRecipeAuditsWithOptionIdAndSharedRecipeFormat: entityId = option id, FNB_RECIPE_CHANGED/RECIPE, reason null, `lines=2`.
- replaceOptionRecipeReturnsMappedLines
- replaceOptionRecipeWithEmptyListClearsRecipeAndAuditsZeroLines

## Group 3: productRecipe / optionRecipe (GET)
- productRecipeRequiresOwnerGuardAndQueriesNothingWhenDenied, productRecipeRejectsMissingProductWithoutQueryingRecipes, productRecipeRejectsProductWhoseCategoryIsArchived
- productRecipeReturnsMappedLinesFromProductRecipesQuery: stubbed with exact `List.of(productId)`; mapped id/name/unit/quantity; read-only (no delete/persist/audit).
- productRecipeReturnsEmptyLinesWhenProductHasNoRecipe
- optionRecipeRequiresOwnerGuardAndQueriesNothingWhenDenied, optionRecipeRejectsGroupIdThatDoesNotMatchTheOptionGroup, optionRecipeRejectsMissingOption
- optionRecipeReturnsMappedLinesFromOptionRecipesQuery (exact `List.of(optionId)`), optionRecipeReturnsEmptyLinesWhenOptionHasNoRecipe

## Group 4: regression for updateOption / updateProduct / recordPrice refactor
updateOption:
- updateOptionRequiresOwnerGuard
- updateOptionPriceChangeWithoutReasonIsRejectedAndNothingIsAudited (message "Alasan perubahan harga wajib diisi.", price unchanged on entity)
- updateOptionPriceChangeWithBlankReasonIsRejected
- updateOptionPriceChangeWithReasonRecordsOptionPriceAuditWithTrimmedReason (FNB_OPTION_PRICE_CHANGED, PRODUCT_OPTION, entityId = option id, trimmed reason, tenant/outlet/actor)
- updateOptionAuditDetailsUsePlainOldAndNewPrices (`old=3000.00;new=4500` from `4.5E+3`, proves toPlainString)
- updateOptionPriceChangeAppliesNewValuesAfterAuditing
- updateOptionWithUnchangedPriceWritesNoAuditEvenWhenScaleDiffers (3000.00 vs 3000, no audit, no references usage, name still updated)
- updateOptionRejectsGroupIdThatDoesNotMatchTheOptionGroup (NotFound, entity untouched, no audit)

updateProduct:
- updateProductRequiresOwnerGuard
- updateProductPriceChangeWithoutReasonIsRejectedAndNothingIsAudited, updateProductPriceChangeWithBlankReasonIsRejected
- updateProductPriceChangeWithReasonRecordsProductPriceAuditWithTrimmedReason (FNB_PRODUCT_PRICE_CHANGED, PRODUCT, entityId = product id, trimmed reason, details `old=10000.00;new=12000.00`)
- updateProductAuditDetailsUsePlainOldAndNewPrices (`1.2E+4` -> `new=12000`)
- updateProductPriceChangeAppliesNewValuesAfterAuditing
- updateProductWithUnchangedPriceWritesNoAuditEvenWhenScaleDiffers
- updateProductRejectsUnknownProductId, updateProductRejectsUnknownCategoryWithoutAuditing

## Not covered / notes
- "Inactive item" is covered via `inventory.item(...)` returning empty: the service has no separate inactive check; the active filter lives in the repository (`FnbInventoryRepository.item` filters `active = true`) and is out of scope for a service unit test (T4 integration covers it).
- Same-tenant/outlet scoping of lookups is proven by stubbing with exact (tenantId, outletId, id) arguments (strict stubs fail on mismatch), not with separate cross-tenant tests.
- Bean validation (`@Size(max=50)`, `@DecimalMin`) is controller-layer and not exercised here.

## Suspected production issues
- None found. All assertions follow the packet and the code as read. Observations only:
  - `updateOption`/`updateProduct` look up category/option before checking the price reason; so a wrong id fails with NotFound even when the reason is missing (matches pre-refactor order; tests rely on it).
  - `replaceRecipe` with a duplicate id where the FIRST occurrence's item is unknown throws NotFound (lookup runs before the next iteration's duplicate check); tests use a known first item, so they are not sensitive to this.

## Assumptions about signatures (all verified by reading the source)
- `FnbCatalogService` methods: `replaceProductRecipe(principal, outletId, productId, request)`, `replaceOptionRecipe(principal, outletId, productId, groupId, id, request)`, `productRecipe(principal, outletId, productId)`, `optionRecipe(principal, outletId, productId, groupId, id)`, `updateOption(principal, outletId, productId, groupId, id, request)`, `updateProduct(principal, outletId, id, request)`.
- `TenantPrincipal(userId, tenantId, outletId, membershipId, sessionId, roles)`; `MerchantAccessContext(tenant, outlet, membership)` with null membership (never used by the catalog service).
- `AuditLogEntity` exposes `getAction()` (String), `getEntityType()`, `getEntityId()`, `getReason()`, `getDetails()`, `getTenant()`, `getOutlet()`, `getActor()` via Lombok `@Getter`.
- `recordAudit` calls `references.tenant(tenantId)`, `references.outlet(outletId)`, `references.user(userId)` (all stubbed only in tests that reach it).
- Strict stubs: every `stub*` helper is used only on paths that actually call it; happy-path recipe tests always send two lines because both items are stubbed.
- Tests were NOT compiled or run (Maven forbidden); re-read twice for imports, overload resolution (`persist(any(RecipeLineEntity.class))`) and unused stubs.
