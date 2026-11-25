# TASK-087: Read-model enrichment for FE contract (additive response fields)

Status: DELEGATED (J1). Branch `feature/leakage-report`. Baseline: commit `20faa7a` + uncommitted TASK-084/085/086 working tree (do not revert or reformat those changes). Dirty user file `.postman/resources.yaml`: do not touch.

## User instruction (2026-11-23)
FE contract audit (`reports/TASK-087-FE-CONTRACT.md` section C). User approved adding: order `cashierName` + payment fields (no `referenceId`), product `categoryName`, recipe line `costEstimate`, stock movement `actorName`.

## Rules
- Additive only: new response fields, no request change, no endpoint change, no migration.
- New fields nullable; null when unknown. Keep existing `@JsonInclude` behaviour of each DTO.
- No N+1: list endpoints resolve names/payments with one batch query per page (by id set), not per row.
- Reuse existing repositories/queries and the existing `showCost(...)` rule in `FnbInventoryService`; reuse `FnbStockService.itemCost(...)`/balances logic from TASK-085 for cost — do not copy it.
- No tests (user has not asked). Report: `reports/TASK-087-J1.md`.

## Design decisions
- D1 `FnbStaffOrderResponse` gains `cashierName` (String), `methodLabel` (String), `receiptNumber` (String).
  - `cashierName` = display name of the staff whose membership created the order (`createdByMembershipId`); null for QR orders / unknown.
  - `methodLabel` / `receiptNumber` = from the order's successful payment; null while UNPAID. If `methodLabel` is null on the payment, fall back to the `PaymentMethod` name.
  - Applies to create (FnbPosOrderService:142), detail and list (FnbStaffOrderService).
- D2 `FnbProductResponse` gains `categoryName` = `entity.getCategory().getName()`. Ensure product list query fetches category (fetch join or existing batch) so no per-row load.
- D3 `FnbRecipeResponse.Line` gains `costEstimate` (BigDecimal, scale 2 HALF_UP) = `quantityPerUnit × outlet averageUnitCost` of that inventory item; null when average cost unknown or when `showCost` rule hides cost. Applies to product and option recipe GET/PUT.
- D4 `FnbStockMovementResponse` gains `actorName` (String) resolved from `actorMembershipId`; null for system movements (e.g. SALE without actor) or unknown member. Keep `actorMembershipId`.

## Reserved paths (J1)
- `src/main/java/org/lite/project/cashiersaas/dto/response/{FnbStaffOrderResponse,FnbProductResponse,FnbRecipeResponse,FnbStockMovementResponse}.java`
- `src/main/java/org/lite/project/cashiersaas/dto/{FnbStaffOrderMapper,FnbCatalogMapper,FnbInventoryMapper}.java`
- `src/main/java/org/lite/project/cashiersaas/service/{FnbStaffOrderService,FnbPosOrderService,FnbCatalogService,FnbInventoryService}.java`
- `src/main/java/org/lite/project/cashiersaas/repository/{FnbOrderRepository,FnbCatalogRepository,FnbInventoryRepository}.java`
- Membership/staff repository read method only if no existing batch name lookup exists (prefer existing).
- If a mapper signature change breaks another caller (incl. test sources), fix that call site minimally and list it in the report.

## Tests (T1, user requested 2026-11-23)
Worker T1 (`test-worker`): write only, do not run. Report `reports/TASK-087-T1.md`. Extend existing test classes; no new test classes, no helper/base layers, no test for behaviour already covered elsewhere (see `.claude/agents/test-worker.md`).
Goal: prove the new JPQL/Blaze queries and V15 on Postgres, plus the one computed branch.
- `src/test/java/.../integration/fnb/FnbPayFirstFlowIntegrationTest.java`: after create order, list/detail show `cashierName` (creator) and no `methodLabel`/`receiptNumber`; after payment, list shows `methodLabel` and `receiptNumber` equal to the payment response.
- `src/test/java/.../integration/fnb/FnbInventoryFlowIntegrationTest.java`: product list has `categoryName`; product recipe GET has `costEstimate` = quantityPerUnit × averageUnitCost (2 dp) after a RESTOCK with unitCost, null before any cost; movement list has `actorName` of the owner.
- `src/test/java/.../unit/service/FnbCatalogRecipeServiceTest.java`: only if needed to keep existing tests green with the new `inventory.balances(...)` call (stub it); add at most one case for HALF_UP rounding of `costEstimate` if the IT above does not already assert it.
Reserved paths: exactly these three files.

## API tests (A1, user requested 2026-11-23, backend already running)
Worker A1 (`api-test-worker`). baseUrl `http://localhost:8080`. Report `reports/TASK-087-A1.md`.
Credentials: none provided. Use a fresh throwaway owner each run: in the Bash command generate `EMAIL="t087-$(date +%s)@example.test"` and a random `PASSWORD` (e.g. `Pw!$(date +%s)aA`) and pass them only via `--env-var`; never write them to files or the report. Flow: Register Account -> Choose Onboarding Intent (OWNER) -> Start Free Tier -> Complete Business Profile (gives outlet-scoped token, tenantId, outletId) — follow `definition.yaml`. Free tier is enough: POS, inventory and recipe endpoints are not plan-gated.
Scenarios (assert exactly these new fields; existing behaviour is not retested):
1. Create Category -> Create Product -> `07/List Products`: the product has `categoryName` = created category name.
2. New folder `23 - F&B Inventory` (reserved, new requests allowed only here): Create Inventory Item (`POST /api/v1/merchant/outlets/{{outletId}}/fnb/inventory/items`, capture `inventoryItemId` in a request-level script), Put Product Recipe (`PUT .../fnb/products/{{productId}}/recipe`, quantityPerUnit 2) -> assert `lines[0].costEstimate` is null; Restock (`POST .../fnb/inventory/movements`, type RESTOCK, unitCost 12.3425) ; Get Product Recipe -> `costEstimate` = 24.69; List Movements -> restock row has `actorName` = owner fullName. Read controllers/DTOs (`FnbInventoryController`, `FnbCatalogController`, `FnbInventoryMovementRequest`/`FnbRecipeRequest`) for exact paths and bodies.
3. `15/Checkout POS Order` -> `cashierName` = owner fullName, no `methodLabel`/`receiptNumber`; `16/Open Shift` -> `17/Pay Order Cash` -> `15/List Orders`: the order has `methodLabel` "CASH" and `receiptNumber` equal to the payment response.
Reserved edit paths: request YAML in folders `07 - F&B Products`, `15 - F&B POS Orders`, new folder `23 - F&B Inventory`. Other folders (01, 03, 06, 16, 17) run only, do not edit. No data-destroying requests (archive/cancel/refund/deactivate).
Collection-level scripts / `definition.yaml`: do not edit; capture new variables with request-level scripts.

## Done when
- All four fields populated per D1–D4; compile-safe call sites; report lists files, queries added, and any assumption.
