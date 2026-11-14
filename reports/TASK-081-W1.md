# TASK-081 W1 report (Java F&B catalog / inventory tests)

Tests were NOT run (policy). Only test files below and this report were touched. Method counts = `@Test` + `@ParameterizedTest`.

| File | before -> after |
|---|---|
| FnbCatalogRecipeServiceTest (R) | 50 -> 37 |
| FnbOptionGroupServiceTest (OG) | 32 -> 23 |
| FnbInventoryServiceTest (I) | 38 -> 30 |
| FnbInventoryCostAndStockCountTest (C) | 22 -> 20 |
| FnbStockServiceTest (S) | 21 -> 21 (assertion-only edit) |

## FnbCatalogRecipeServiceTest
- R:339 replaceOptionRecipeRejectsMissingGroup: DELETED (identical to R:312; read both).
- R:251 replaceProductRecipeUsesProductVariantOfDeleteOnly: DELETED after folding `verify(inventory, never()).deleteOptionRecipe(...)` into R:178 (replaceProductRecipeDeletesOldLinesThenPersistsNewLinesThenAudits). The `verify(...).deleteProductRecipe` half was already covered by R:178's InOrder.
- R:430 replaceOptionRecipeReturnsMappedLines: DELETED. Claimed keeper R:231 is the product variant, but both go through the shared `FnbCatalogMapper.recipe(...)` (confirmed in FnbCatalogService), and R:572 also checks option-line mapping (name/unit), so nothing distinct is lost.
- R:482 productRecipeRejectsProductWhoseCategoryIsArchived: DELETED (same private `product()` archived-category branch as R:135; R:135 asserts the same verifyNoInteractions).
- R:522 productRecipeReturnsEmptyLines..., R:592 optionRecipeReturnsEmptyLines...: DELETED (empty mock -> empty list).
- Guard-denied R:113, 283, 462, 533, 605, 723: MERGED into one `@ParameterizedTest @MethodSource("ownerOnlyCalls")` (`ownerOnlyUseCasesRequireOwnerGuardAndTouchNothingWhenDenied`), 6 named cases (replaceProductRecipe, replaceOptionRecipe, productRecipe, optionRecipe, updateOption, updateProduct) with the original request inputs. All cases now `verifyNoInteractions(catalog, inventory, audit, references)` (superset of the original per-test sets) plus `verify(guard).requireOwner(...)`.
- R:615+628 (option price reason null/blank): MERGED into `@NullSource @ValueSource("   ")` test; R:733+747 (product) MERGED into `@NullSource @ValueSource("  ")` test. Original message, verifyNoInteractions(audit) and unchanged-price assertions kept (the price assertion now also runs for the product blank case, which previously lacked it).
- R:213+412 (audit shape): KEPT both. Bodies differ by more than entityId (different service method, different arrange helper, different expected entity id), so the "only if" condition is not met.
- New imports: Named, ParameterizedTest, Arguments, MethodSource, NullSource, ValueSource, Stream. No imports became unused.

## FnbOptionGroupServiceTest
- OG:366 replaceProductGroupsRejectsProductWhoseCategoryIsArchived: DELETED (= R:135, same `product()` branch; verified).
- OG:328 replaceProductGroupsRejectsArchivedGroup...: DELETED. Note: not literally mock-identical to OG:315 (stubs differ slightly: unknown group first vs. second), but both hit the same `catalog.group(...).orElseThrow` branch and assert NotFound plus verifyNothingWrittenForProductGroups, so it is redundant as instructed.
- OG:544 updateOptionThroughAnotherGroup...: DELETED (= R:702, which also asserts no audit and unchanged price).
- OG:529 updateOptionResolvesTheGroupByIdOnly...: DELETED (R:689 covers rename without audit; R:673 asserts response optionGroupId). Its `never().product` line went with it.
- `verify(catalog, never()).product(...)` removed at OG:133 (plus its comment), 216, 485, 515 (541 removed with the deleted test).
- Guard-denied OG:384, 395, 407, 418: MERGED into `@ParameterizedTest @MethodSource("cashierDeniedCalls")` (createGroup, updateGroup, archiveGroup, replaceProductGroups). Each keeps `verify(guard).requireOwner` and `verifyNoInteractions(catalog, inventory, audit, references)`; the group-unchanged assertions (name "Topping", not archived) now run for all cases.
- OG:147+157+167 (validateGroup rejections): MERGED into one `@CsvSource` test (min>max, required with min 0, optional with min>0), same inputs, `verify(catalog, never()).persist(...)` kept.
- New imports: Named, ParameterizedTest, Arguments, CsvSource, MethodSource, Stream. None unused.

## FnbInventoryServiceTest
- I:427, I:438, I:450 (guard selection): DELETED. Covered by strict stubs in `stubNewMovementPath` used by the delta tests (wrong guard -> null context NPE / unnecessary stub), including owner-for-ADJUSTMENT and outlet-for-RESTOCK/WASTE. The section-5 header (now empty) was removed.
- I:464+477+490+503 (delta sign): MERGED into `@CsvSource` test (RESTOCK 5 -> +5/15, WASTE 3 -> -3/7, ADJUSTMENT -4 -> -4/6, ADJUSTMENT 4 -> 4/14); principal is owner for ADJUSTMENT, else cashier; reasons and key "k1" preserved.
- I:547+558 (restock reason blank/null): MERGED into `@NullSource @ValueSource("   ")`.
- I:223+240 (initial quantity null / "0.000"): MERGED into `@CsvSource({"Susu,SUSU,ml,500,", "Gula,,g,50,0.000"})`; assertions are the union (no movement, no tokens, zero balance persisted, zero response, OUT).
- New imports: ParameterizedTest, CsvSource, NullSource, ValueSource.

## FnbInventoryCostAndStockCountTest
- C:88+109 (priced restock cashier/owner): MERGED into `@CsvSource({"false,7.5000,6.2500,7.5", "true,7,6.0000,7"})`. Cashier row keeps all original assertions (average 6.2500, qty 20, unit cost 7.5, type, delta, fingerprint, response cost null); owner row keeps response cost 7 and now also asserts the average (6.0000 = (10*5+10*7)/20, scale 4 per `InventoryBalanceEntity.receive`), unit cost and fingerprint. Not executed: the new owner-row expectations are derived by hand.
- C:288+321 (count deficit/surplus): MERGED into `@CsvSource({"8.000,-2,8", "15,5,15"})`; the full C:288 assertion set now applies to both rows (includes surplus: delta 5, qty 15, average unchanged 3.0000, difference 5).
- New imports: ParameterizedTest, CsvSource.

## FnbStockServiceTest
- Removed the three `assertTrue(warnings.get(0).contains(...))` lines (Susu, -26, (minus)) that duplicate the `assertEquals("Susu: sisa -26 ml (minus)", ...)` that follows. `assertTrue` is still used elsewhere in the file.

## Risks to check on `mvnw verify`
- Named lambda + `private interface Call` pattern (R, OG) is new to this test suite; should compile (same-class private access, static MethodSource).
- Owner-row expectations in the C:88+109 merge (above).
