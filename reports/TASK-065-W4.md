# TASK-065 W4 report: adapt existing tests to reusable option groups

Status: DONE (not compiled or run, by rule; the orchestrator compiles and runs).

## Changed files
- src/test/java/org/lite/project/cashiersaas/unit/dto/FnbMenuMapperTest.java
- src/test/java/org/lite/project/cashiersaas/unit/service/FnbCatalogRecipeServiceTest.java
- src/test/java/org/lite/project/cashiersaas/integration/fnb/FnbInventoryFlowIntegrationTest.java

## Adaptations
FnbMenuMapperTest
- `OptionGroupEntity.create(tenant, outlet, details)` (no product); `FnbMenuMapper.menu` 4th arg now `Map<UUID, List<OptionGroupEntity>>` (`Map.of()` / `Map.of(product.getId(), List.of(group))`); import `java.util.Map`. Existing assertions untouched.
- NEW test `sharedGroupIsExposedUnderEachProductOnceWithOptionsNotDuplicated`: one group + one option mapped under two products; each product has exactly 1 group and that group exactly 1 option.

FnbCatalogRecipeServiceTest
- Group creation without product; `replaceOptionRecipe/optionRecipe/updateOption` calls drop `productId` (new signatures `(owner, outletId, groupId, [id,] ...)`).
- `stubProduct()` removed from `stubOption()` and from the option-only tests (new service no longer loads the product; strict stubs would fail otherwise). Product-recipe and updateProduct tests unchanged.
- Assertions on option recipes/audits/price audits unchanged.

FnbInventoryFlowIntegrationTest
- Helpers: `optionGroups(merchant)`, `optionRecipeUrl(merchant, groupId, optionId)`, `createGroup(owner)` (POST /merchant/.../fnb/option-groups), `createOption(owner, groupId, name, price)`, NEW `attachGroups(owner, productId, groupIds...)` (PUT .../products/{pid}/option-groups, reuses `setRecipe` which asserts 200).
- Happy path: group is created then attached to the product before the order (required for the order flow).
- Recipe scenario: attaches both groups; "option recipe through another group" 404 stays.

## Replaced scenarios (meaningless in the new model)
1. Unit `replaceOptionRecipeRejectsGroupThatBelongsToAnotherProduct` -> `replaceOptionRecipeRejectsGroupThatIsNotFoundForThisTenantAndOutlet` (catalog.group returns empty -> ResourceNotFoundException, catalog.option never called, inventory/audit untouched). Reason: groups have no product owner now; foreign tenant/outlet/archived group = not found.
2. Unit `replaceOptionRecipeRejectsMissingProduct` -> `replaceOptionRecipeRejectsMissingGroup` (404 when the group is missing; no product lookup exists any more).
3. Integration: PUT option recipe via `optionRecipeUrl(owner, otherProductId, groupId, optionId)` (404) -> same with `foreignGroupId` (a group created by the other tenant) -> 404. The now-unused `otherProductId` setup line was removed.

## Remaining gaps / notes
- No unit tests were added for optionRecipe/updateOption with a missing group (W5's FnbOptionGroupServiceTest covers group resolution).
- Not compiled. Assumes group creation has no unique-name rule (the old test already created two groups named "Tambahan").
- No deviations from the contract.
