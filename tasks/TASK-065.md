# TASK-065: Tests for reusable option groups (TASK-064)

Status: DELEGATED (2026-11-10), runs in PARALLEL with TASK-064 and TASK-066. Memory version at delegation: 59 (-> 60). Branch `feature/fnb-reusable-option-groups` @ `d41d731`. The user authorised test writing by workers and `mvnw verify` by the orchestrator for this feature.
Binding contract: `tasks/TASK-064.md` (sections "User decisions" and "Shared contract"). Code against the CONTRACT, not against the current source of `OptionGroupEntity`/`FnbCatalogService`/`FnbCatalogController` — TASK-064 workers are rewriting those files right now. Do not edit any main source. You cannot compile; the orchestrator compiles and runs the suite.

## Rules (same as `tasks/TASK-061.md` "Reuse rules for EVERY worker")
- You inherit no conversation. Write ONLY your reserved paths; never edit `.ai/*`, `tasks/*`, `postman/**`, `src/main/**`.
- Reuse existing test helpers/fixtures (`ApiTestSupport`, `ApiTestSupport.onboardCashier(owner, label)`, `AbstractPostgresIntegrationTest`, the existing mock/fixture style in the test you copy). No new helper that duplicates one that exists. No new file unless listed.
- NEVER weaken, delete-to-pass or loosen an assertion. A test that becomes meaningless under the new model (product-ownership mismatch) is REPLACED by the equivalent new-model scenario (group not found / other tenant or outlet -> 404) and listed in your report with the reason.
- DO NOT RUN MAVEN or any build/test. Re-read for compile errors (imports, generics, overloaded `ReflectionTestUtils.setField` — assign `invocation.getArgument(0)` to a typed local first).
- Java 17, no wildcard generics, no `@Deprecated`.
- Report in `reports/TASK-065-<W>.md`: changed files, test list with the behaviour each proves, remaining gaps, deviations.

## Worker split

| Worker | Model | Reserved writes |
|---|---|---|
| W4 | sonnet | `src/test/.../unit/dto/FnbMenuMapperTest.java`, `src/test/.../unit/service/FnbCatalogRecipeServiceTest.java`, `src/test/.../integration/fnb/FnbInventoryFlowIntegrationTest.java`, `reports/TASK-065-W4.md` — ADAPT existing tests to the new contract |
| W5 | sonnet | NEW `src/test/.../unit/service/FnbOptionGroupServiceTest.java`, NEW `src/test/.../integration/fnb/FnbReusableOptionGroupIntegrationTest.java`, `reports/TASK-065-W5.md` — NEW coverage |

(`src/test/...` = `src/test/java/org/lite/project/cashiersaas`.)

## W4: adapt existing tests
- `FnbMenuMapperTest`: `OptionGroupEntity.create(tenant, outlet, details)` has no product argument; `FnbMenuMapper.menu(...)`'s 4th parameter is `Map<UUID, List<OptionGroupEntity>>` keyed by product id. Keep every existing assertion; add one case where the SAME group is mapped under two products and each product exposes the group exactly once with its options not duplicated.
- `FnbCatalogRecipeServiceTest`: the option-recipe tests call the new service signatures (`replaceOptionRecipe(principal, outletId, groupId, id, request)`, `optionRecipe(principal, outletId, groupId, id)`); the group/option ownership mocks use `catalog.group(tenantId, outletId, groupId)`. Tests that proved "group belongs to another product" become "group not found / belongs to another outlet-tenant -> `ResourceNotFoundException`" and tests that proved "option belongs to another group" stay. Product-recipe tests are unchanged. Keep the suite's other assertions intact (verify with the diff: only signature/mock adaptations plus the replaced scenarios).
- `FnbInventoryFlowIntegrationTest`: helpers build routes with the new API: create group `POST /merchant/outlets/{o}/fnb/option-groups`, then attach with `PUT /merchant/outlets/{o}/fnb/products/{pid}/option-groups` body `{"groupIds":["<id>"]}` (the group must be attached for the order flow to accept its options); options `POST /merchant/outlets/{o}/fnb/option-groups/{gid}/options`; option recipe `PUT|GET /merchant/outlets/{o}/fnb/option-groups/{gid}/options/{oid}/recipe`. Keep every scenario and assertion. The existing "other group" scenario (~line 655: recipe through a group that is not the option's group) stays valid with the new URLs.

## W5: new coverage
Unit `FnbOptionGroupServiceTest` (Mockito, style of `FnbCatalogRecipeServiceTest`; service = `FnbCatalogService`):
1. createGroup: owner guard used (`guard.requireOwner`), persisted with trimmed name, invalid limits (`min > max`, required with min 0, optional with min > 0) -> `InvalidOperationException`.
2. updateGroup / archiveGroup unknown id -> `ResourceNotFoundException`.
3. archiveGroup when `catalog.groupInUse(...)` true -> `OperationConflictException`, group NOT archived; when false -> `isArchived()` true.
4. replaceProductGroups: persists one `ProductOptionGroupEntity` per id with `displayOrder` = index (use an ArgumentCaptor), returns request order, clears first via `deleteProductGroups`; duplicate ids -> `InvalidOperationException` and nothing deleted/persisted; unknown group id -> `ResourceNotFoundException`; empty list only clears; unknown/archived product -> 404.
5. cashier cannot mutate: owner guard throws `ResourceNotFoundException` for the CASHIER membership (mock `guard.requireOwner` to throw) for createGroup/updateGroup/archiveGroup/replaceProductGroups; `groups` and `productGroups` use `guard.requireOutlet`.
6. options(principal, outletId, groupId) / createOption / updateOption resolve the group by id only (no product).

Integration `FnbReusableOptionGroupIntegrationTest extends AbstractPostgresIntegrationTest` (copy the style of `FnbInventoryFlowIntegrationTest`, reuse its fixtures/`ApiTestSupport`; read it first):
1. Reuse + stock: one group "Topping" with option "Boba" (recipe: 50 g of ingredient "Boba", stock 1000 g) attached to TWO products (each with its own recipe or none). Order one of each product with Boba through the POS checkout, settle both -> stock = 1000 - 100 (two SALE movements), `stockWarnings` consistent. Proves reuse + deduction through one shared option.
2. Public menu: `GET /public/fnb/menu/{qrToken}` lists the shared group under BOTH products and each group's options appear exactly once (no duplicates).
3. Group rules enforced at order time through an attached group: a required group (min 1) attached to a product rejects an order without a selection (400) and accepts it with one; selecting an option of a group NOT attached to that product is rejected (400).
4. Archive: archiving an attached group -> 409; after `PUT .../products/{pid}/option-groups` with `{"groupIds":[]}` for every product that used it -> archive 200, and it disappears from `GET /cashier/outlets/{o}/fnb/option-groups`. An archived group's options can no longer be created/read (404).
5. Replace-all ordering: attach `[B, A]` -> `GET /cashier/.../products/{pid}/option-groups` returns B then A; re-attach `[A]` removes B from that product but B stays in the library.
6. Roles/tenant isolation: CASHIER (via `ApiTestSupport.onboardCashier`) can `GET` the library and product groups but gets 403/404 (mirror what the existing inventory test asserts for cashier on `/merchant/**`) on create/update/archive/attach; a second tenant cannot attach or see the first tenant's group (404, no existence leak) and cannot attach a group of another outlet.
Keep each scenario self-contained (own onboarded tenant) like the existing integration tests.

Remember: the backfill in V5 is NOT testable here (Flyway runs on an empty DB). The orchestrator verifies it separately.
