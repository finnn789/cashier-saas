# TASK-104: Tech debt after TASK-101/103 (source lock, test alignment)

Status: PLANNED. Memory version: 101. Branch `feature/leakage-report`. Baseline commit: `cee0f5f` (dirty: TASK-094/096/097/098/100/101/103 uncommitted, see `.ai/TASKS.md`).

## User instruction (2026-11-25)
"Perbaiki utang teknis itu, sesuai dengan saran anda" — fix the debts reported after TASK-101/103, FE too, skip Ai-services; the user does QA afterwards. The user explicitly asked for the test work (test-worker allowed). Workers do not run tests.

## Global rules (every worker)
- Reports: `reports/TASK-104-<id>.md`.
- No migration.
- Debt 1 (cost visible to cashier) needs NO code: verified by the orchestrator, `FnbInventoryService.showCost(context)` already nulls `averageUnitCost` (items) and `unitCost` (movements) and skips `marginWarnings` for non-owners; purchase line cost stays visible by design (the cashier types it). UI types are already optional. Do not change these.

## Orchestrator design decisions
- D1 Copy locks both outlets with `FnbOrderRepository.lockOutlet`, always in ascending UUID order (`UUID.compareTo`), so two opposite copies (A->B and B->A) cannot deadlock. Both locks are taken after the same-outlet/inactive checks and before the emptiness check, exactly where the single lock is now.
- D2 Tests follow the unit-test writing rule (no new test helpers/layers, no test for behaviour already covered). Update existing tests to the current code; add only focused tests for the new behaviour listed in T1.

## Wave 1 (parallel, disjoint)

### J1 — source outlet lock (`java-worker`)
Reserved: `src/main/java/org/lite/project/cashiersaas/service/FnbCatalogCopyService.java` only.
Do: implement D1 (replace the single `orders.lockOutlet(tenantId, target.getId())`). Nothing else.
Acceptance: two `lockOutlet` calls, lower UUID first; behaviour otherwise unchanged; compiles.

### T1 — align and extend tests (`test-worker`)
Reserved: under `src/test/java/org/lite/project/cashiersaas/`:
`unit/service/FnbInventoryServiceTest.java`, `unit/service/FnbInventoryCostAndStockCountTest.java`, `unit/service/FnbCatalogRecipeServiceTest.java`, `unit/service/FnbOptionGroupServiceTest.java`, `unit/service/MerchantBusinessSettingsServiceTest.java`, `unit/service/MerchantAccessGuardTest.java`, `integration/fnb/FnbInventoryFlowIntegrationTest.java`, new `unit/service/FnbDiscountServiceTest.java` (only if none exists), new `unit/service/FnbCatalogCopyServiceTest.java`.
Read first: `reports/TASK-103-J1.md` ("Tests likely needing updates"), `reports/TASK-101-J1.md`, `reports/TASK-097-J1.md` (these same classes need `@Mock AuditLogRepository`), `tasks/TASK-103.md` (contract table).
Do:
- Existing tests: replace `guard.requireOwner(...)` stubs with `guard.require(..., Permission.X)` for the methods listed in TASK-103-J1 "Guard calls changed"; add missing mocks (`AuditLogRepository`); update `MerchantBusinessSettingsServiceTest` for `cashierMaxDiscountPct` (response + audit JSON); any assertion that a CASHIER gets 404 on an action now allowed for cashiers must assert the new behaviour.
- New focused tests:
  - `MerchantAccessGuardTest`: `require` returns the context for an allowed role, 404 (`ResourceNotFoundException`) for CASHIER on `STOCK_ADJUST`.
  - Catalog (in the existing catalog/option-group test class that covers `updateProduct`/`updateOption`): cashier price change -> `InvalidOperationException` "Kasir tidak dapat mengubah harga."; cashier same price passes; owner price change still works.
  - `FnbDiscountServiceTest`: cashier PERCENTAGE at cap OK, over cap 400 "Diskon kasir maksimal {cap}%.", NOMINAL over cap, NOMINAL on subtotal 0 rejected, cap 0 rejects any cashier discount, owner over cap OK.
  - Inventory: cashier `recordMovement` ADJUSTMENT -> 404, RESTOCK allowed and its response has null `unitCost` and empty `marginWarnings`.
  - `FnbCatalogCopyServiceTest`: same outlet 400, foreign outlet 404, inactive target 409, non-empty target 409, both outlets locked in ascending UUID order (`InOrder`, D1 contract from J1), success counts + one `CATALOG_COPIED` audit.
  - IT `FnbInventoryFlowIntegrationTest`: cashier restock via `/cashier/...` 200 without cost fields; cashier adjustment 404.
- Do not run tests. Report which tests changed and why.
Acceptance: the listed classes match the current production code; no new helper classes; report lists every changed/added test.

## Recommended tests (for the user's QA)
- Run `mvnw -Punit-tests test`, then `mvnw -B verify` (Docker Desktop up; V16/V17 run on a real DB for the first time).
- Manual QA: cashier session for each TASK-103 matrix row; owner copies menu A->B; cashier never sees cost in stock/movement responses.
