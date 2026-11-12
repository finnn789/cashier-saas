# TASK-062: Tests for the F&B inventory / recipe / stock-warning feature (TASK-061)

Status: DELEGATED (2026-11-10). Memory version at delegation: 55 -> 56. Baseline: git HEAD `95bd9af` + uncommitted TASK-061 changes (compiles; `mvnw verify` green at 321 unit + 48 integration BEFORE these tests).
User authorization: on 2026-11-10 the user asked to delegate writing these tests. The orchestrator will run them (`mvnw verify`); workers must NOT.

Read first: `tasks/TASK-061.md` (contract, facts, decisions) and the real code listed per worker. The production code is the source of truth, not this packet.

## Rules for EVERY worker

- You inherit no conversation. Read this file, `tasks/TASK-061.md` (decisions + shared contract), `AGENTS.md` (Java conventions) and the production sources you test.
- Write ONLY your reserved paths + your report `reports/TASK-062-<T>.md`. NEVER edit production code (`src/main/**`), `pom.xml`, `.ai/*`, `tasks/*`, `postman/**`. If a test exposes a production bug or a contract/code mismatch, do NOT weaken the assertion and do NOT fix production code: write the test for the CORRECT behaviour, keep it, and describe the failure risk in your report so the orchestrator can decide.
- DO NOT RUN MAVEN or any build/test (the orchestrator runs `mvnw verify`; concurrent builds corrupt `target/`). Re-read your test code for compile errors (imports, generics, checked exceptions, constructor signatures, Mockito strict stubs: `MockitoExtension` fails on unused stubbings, so stub only what the code path really calls).
- Reuse existing test conventions and helpers; do not duplicate a helper that already exists (search `src/test/**` first). No new dependencies. No `@Disabled`, no weakened assertions, no sleeping.
- Unit tests: package `org.lite.project.cashiersaas.unit.*`, `@Tag("unit")`, JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`), AssertJ/JUnit assertions as the existing tests do. Follow `unit/service/FnbOrderQrServiceTest.java` for style and how `TenantPrincipal`, `MerchantAccessContext`, entities are constructed (`ReflectionTestUtils` for ids/private fields when entities have no setters).
- Integration tests: extend `AbstractPostgresIntegrationTest`, use `ApiTestSupport` / `MerchantFixture` exactly as `integration/fnb/FnbPayFirstFlowIntegrationTest.java` does; class name must end with `IntegrationTest`.
- Java 17, no wildcard generics, no `@Deprecated`. Tests are real: every test asserts observable behaviour, including failure paths.
- Update `reports/TASK-062-<T>.md` at each checkpoint and at the end: files written, list of test method names with the behaviour each proves, anything you could not cover and why, suspected production issues, exact assumptions you made about production signatures.

## Waves

| Worker | Model | Reserved writes (new files) |
|---|---|---|
| T1 | sonnet | `src/test/java/org/lite/project/cashiersaas/unit/service/FnbStockServiceTest.java` |
| T2 | sonnet | `.../unit/service/FnbInventoryServiceTest.java`, `.../unit/entity/InventoryBalanceEntityTest.java` |
| T3 | sonnet | `.../unit/service/FnbCatalogRecipeServiceTest.java` |
| T4 | sonnet | `.../integration/fnb/FnbInventoryFlowIntegrationTest.java`; ADDITIVE helper methods only in `integration/support/ApiTestSupport.java` / `MerchantFixture.java` (T4 is the only editor; search first, never duplicate or change existing helpers) |

All four run in parallel (disjoint files). Orchestrator then: `mvnw.cmd -q test-compile`, `mvnw.cmd verify -B` (Docker Desktop at `C:\Users\arips\AppData\Local\Programs\DockerDesktop\`), review failures, send fixes back to the owning worker.

---

## T1: `FnbStockService` unit tests (`consumeForPaidOrder`)

Collaborators to mock: `FnbOrderRepository`, `FnbInventoryRepository`, `PublicOrderTokenService`. Cover (each its own test):
1. No recipe anywhere -> returns empty list, no balance lookup beyond need, `persist`/`lock` never called.
2. Product recipe only: need = line quantity x quantityPerUnit; balance reduced by exactly that; one SALE movement persisted with `orderId`, type SALE, negative delta, key `sale:{orderId}:{itemId}`, actor membership id, fingerprint from `tokens.digest(...)`.
3. Product + selected option recipes on the same ingredient are SUMMED into ONE movement; option need uses the PARENT order line's quantity (qty 3 x option recipe).
4. Two ingredients -> two movements; balances locked in ASCENDING ingredient-id order (use `InOrder`).
5. Lines with null product or null option are skipped without error.
6. Existing movement with the same key -> that ingredient is skipped (no second deduction, no duplicate warning).
7. Missing balance row -> created via `InventoryBalanceEntity.create` and persisted (not locked), then applied.
8. Stock may go negative: balance 10, need 36 -> payment NOT blocked (no exception), warning contains the name, "-26" and "(minus)".
9. Warning texts: exactly 0 -> "(habis)"; <= reorder level -> "(menipis)"; above -> no warning; unit appears in the text.
10. Recipes fetched with ONE `productRecipes` and ONE `optionRecipes` call each (verify times(1)), items/options fetched once.

## T2: `FnbInventoryService` + `InventoryBalanceEntity` unit tests

`FnbInventoryService` collaborators: `MerchantAccessGuard`, `FnbInventoryRepository`, `PublicOrderTokenService`. Cover:
1. `createItem`: owner guard used; item+balance persisted; `initialQuantity > 0` -> RESTOCK movement reason "Stok awal", key `init:{itemId}`, balance equals initial; `initialQuantity` null/0 -> no movement; blank sku stored as null; `flush()` called after persisting the item.
2. `updateItem`: unit change rejected (`InvalidOperationException`) when `movements(...,0,1)` is non-empty; allowed when empty; unchanged unit never queries movements; unknown id -> `ResourceNotFoundException`.
3. `deactivateItem`: calls `deactivate()`; missing -> NotFound.
4. `items`: ONE `balances(...)` call for the whole list; status filter returns only matching `stockStatus`; item without balance reported as 0 / OUT.
5. `recordMovement` guard selection: `ADJUSTMENT` -> `guard.requireOwner` (and NOT `requireOutlet`); `RESTOCK` and `WASTE` -> `guard.requireOutlet`.
6. Delta rules: RESTOCK +q, WASTE -q, ADJUSTMENT signed as given; SALE rejected; zero rejected; RESTOCK/WASTE negative rejected.
7. Reason rules: blank RESTOCK -> "Restock"; blank WASTE or ADJUSTMENT -> `InvalidOperationException`; reason trimmed.
8. Idempotency: existing movement with same fingerprint -> returns it, nothing persisted/locked; same key different fingerprint -> `OperationConflictException`; whitespace-only reason difference replays.
9. New movement: balance locked (`lock`) then `apply(delta)`, movement persisted with the membership id as actor, `flush()` called; missing balance -> created and persisted instead of locked.
10. `movements`: page/size bounds (`page<0`, `size<1`, `size>50`, `page>1_000_000` -> InvalidOperationException), item must exist (NotFound), mapping preserved.
`InventoryBalanceEntityTest` (`unit/entity`): `status()` boundaries — negative -> NEGATIVE, zero -> OUT, equal to reorder level -> LOW, just above -> OK; `apply` accumulates with scale; `create` starts at zero. Follow `unit/entity/SalesOrderEntityTest` style; use `InventoryItemEntity.create(...)` for the item.

## T3: recipe + refactor-regression unit tests in `FnbCatalogService`

Collaborators of `FnbCatalogService`: `MerchantAccessGuard`, `FnbCatalogRepository`, `MerchantReferenceRepository`, `AuditLogRepository`, `FnbInventoryRepository`. Cover:
1. `replaceProductRecipe`: owner guard; product must exist and category not archived; duplicate `inventoryItemId` -> `InvalidOperationException` and `deleteProductRecipe` NEVER called; unknown/inactive item -> `ResourceNotFoundException` and delete never called (validation happens before the delete); happy path -> delete then persist N lines (InOrder), audit persisted with action `FNB_RECIPE_CHANGED`, `entityId` = product id, reason null, details `lines=N`; empty list -> delete called, nothing persisted, details `lines=0`.
2. `replaceOptionRecipe`: option must belong to the given group and product (`ResourceNotFoundException` when the group id does not match the option's group, and when the product/group pair is wrong); `deleteOptionRecipe` used; audit `entityId` = option id; shared logic equals the product variant.
3. `productRecipe` / `optionRecipe` GET: owner guard, ownership checks, returns mapped lines (name, unit, quantityPerUnit) from `productRecipes` / `optionRecipes` with `List.of(id)`.
4. REGRESSION for the refactor of `updateOption` and `recordPrice`: price change without reason -> `InvalidOperationException` ("Alasan perubahan harga wajib diisi."); with a reason -> audit persisted with action `FNB_OPTION_PRICE_CHANGED`, reason TRIMMED, details `old=<plain>;new=<plain>`; unchanged price -> no audit; same for `updateProduct` with `FNB_PRODUCT_PRICE_CHANGED`; `updateOption` with a wrong group id -> `ResourceNotFoundException`.
Build entities the way existing unit tests do (`ProductEntity.create`, `CategoryEntity.create`, `OptionGroupEntity.create`, `ProductOptionEntity.create`, `ReflectionTestUtils.setField` for ids). `AuditLogEntity` fields can be inspected through its getters via an `ArgumentCaptor`.

## T4: end-to-end integration test `FnbInventoryFlowIntegrationTest`

Real PostgreSQL 17 (Testcontainers), real Flyway V1..V4, real security chain. Study `FnbPayFirstFlowIntegrationTest` and `ApiTestSupport`/`MerchantFixture` first (onboarding, category/product/table helpers, bearer tokens, `api.jdbc()`, cashier creation/invitation helper if one exists — if none exists, add the smallest additive helper to the support class after verifying none is there; if a cashier cannot be created reasonably, cover the cashier cases as far as possible and explain the gap). Use the real endpoints from TASK-061 §W2/§W3. Read `GlobalExceptionHandler` to assert the REAL HTTP status for each error. Cover (each its own test, independent data via `api.onboardOwner(...)`):
1. Happy path: owner creates ingredient (initialQuantity 1000, reorderLevel 100), sets product recipe (e.g. 18 per unit) and an option recipe, creates a POS order qty 2 with that option, pays CASH -> `stockWarnings` empty, balance reduced by qty x (product recipe + option recipe), exactly one SALE movement per ingredient with `order_id` set, and `SUM(quantity_delta) == quantity_on_hand` (invariant) via SQL.
2. Negative allowed: ingredient with 10 in stock, recipe needs more -> payment still succeeds, `stockWarnings` contains "(minus)", balance negative; list endpoint shows `stockStatus` NEGATIVE and `?status=NEGATIVE` filter returns it.
3. LOW warning: stock ends above 0 but <= reorder level -> "(menipis)" warning and `stockStatus` LOW.
4. MOCK_GATEWAY path: initiate -> resolve SUCCEEDED -> stock deducted exactly once; a FAILED outcome deducts nothing.
5. Idempotent payment replay with the same payment idempotency key does NOT deduct twice (movement count and balance unchanged).
6. Product without recipe: pay succeeds, no inventory movements for that order, `stockWarnings` empty.
7. Unpaid order cancelled -> no inventory movements.
8. Roles: cashier can RESTOCK and WASTE; cashier ADJUSTMENT -> 404; cashier creating/updating an ingredient or setting a recipe on `/merchant/**` -> 403; owner ADJUSTMENT (negative and positive) works; cashier can list stock.
9. Movement idempotency: same key + same body -> same movement id returned, balance unchanged; same key + different quantity -> 409; SALE type rejected (400); WASTE without reason -> 400.
10. Recipe replace: PUT the same recipe twice (re-inserting the same product/item pair works), PUT empty list clears it, duplicate ingredient in one request -> 400, unknown or other-tenant ingredient -> 404, product recipe GET returns name/unit/quantity.
11. Tenant isolation: owner B cannot read, update, deactivate, move stock for, or use in a recipe an ingredient of owner A (all 404, no evidence the record exists), and A's list does not contain B's data.
12. Ingredient name is unique per outlet: creating a duplicate name returns the status mapped by `GlobalExceptionHandler` for a unique violation (assert the actual mapping) and leaves one row; changing `unit` after movements exist -> 400.
13. Deactivated ingredient disappears from the list and cannot be added to a new recipe (404), but an EXISTING recipe line referencing it still deducts on payment (documented behaviour of TASK-061).
Clean up with `api.truncateTenantData()` like the existing test. Keep each scenario deterministic (no sleeps, no real concurrency except where the existing test already does it).

---

## Orchestrator checklist after the workers finish
1. Diff: only reserved test paths (+ additive support helpers) changed; no production change.
2. `mvnw.cmd -q test-compile` -> fix compile errors by sending them back to the owning worker (SendMessage).
3. `mvnw.cmd verify -B`; every failure is classified as test bug (worker fixes) or production bug (report to the user, then delegate a production fix as its own scoped task).
4. Update `.ai/TASKS.md` / `.ai/MEMORY.md` with exact counts and any production findings.
