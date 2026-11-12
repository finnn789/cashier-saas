# TASK-069-W1: documentation for inventory, recipes and reusable option groups

Status: SUBMITTED for review. Docs only; no Maven, no git, no edits outside the reserved paths. No command from `docs/demo.md` was executed by the worker (the orchestrator runs them).

## Files changed

- `README.md`
- `docs/architecture.md`
- `docs/demo.md`
- `reports/TASK-069-W1.md` (this file)

## What changed

README: Key features (catalog line rewritten for reusable groups, new Inventory and recipes line); two new rows in the decisions table (negative stock + warnings, deduct-on-paid in both settle paths); one sentence about the seeded ingredients under Demo data; Running tests (460 unit / 75 integration, three new class bullets, new not-covered line, stale 200/10 and TASK-054 reference dropped); Roadmap (stale "inventory entities outside MVP" line replaced, stale test-gap line corrected).

architecture.md: section 1 stale sentence fixed; payment paragraph mentions stock deduction; new subsections "Inventory and recipes" and "Reusable option groups" appended to section 4 (before section 5; no renumbering, the existing 8-before-7 order is untouched); Persistence lists V3, V4, V5 and the demo seed.

demo.md: ingredient/recipe seed paragraph under Demo data; new section "Inventory and recipes walkthrough" (13 numbered steps 0-12) between the curl walkthrough and Notes, with the single placeholder `Verification: pending`.

## Claims and sources

| Claim | Source |
|---|---|
| Routes: inventory CRUD under `/merchant/outlets/{o}/fnb/inventory/items[/{id}]`; list, movements POST/GET under `/cashier/...`; `?status=` takes `InventoryBalanceEntity.Status`; movements `page` (default 0), `size` (default 20, max 50) | `controller/FnbInventoryController.java`, `FnbInventoryService.movements` |
| Catalog routes: option-groups CRUD, attach `PUT .../products/{p}/option-groups`, options and option recipes under `/option-groups/{groupId}/options/...`, product recipe `PUT/GET .../products/{p}/recipe` (owner/merchant only) | `controller/FnbCatalogController.java` |
| Request fields: ingredient `name, sku?, unit, reorderLevel, initialQuantity?`; movement `type, quantity, reason?, idempotencyKey`; recipe `lines[{inventoryItemId, quantityPerUnit}]`; group `name, required, minimumSelections, maximumSelections, displayOrder`; option `name, priceAdjustment, available, displayOrder`; attach `groupIds`; payment `method, amountTendered, idempotencyKey` | `dto/request/*` (all read) |
| Response fields used by demo: `data.id`, `quantityOnHand`, `stockStatus`, `name`, `unit`, movement `type, quantityDelta, reason, orderId, inventoryItemId`, payment `stockWarnings`, order `orderId` | `dto/response/*` |
| Initial stock = RESTOCK movement, reason "Stok awal", key `init:{itemId}`, only when initialQuantity > 0 | `FnbInventoryService.createItem` |
| RESTOCK > 0 (reason defaults "Restock"); WASTE > 0 stored negative, reason required; ADJUSTMENT signed non-zero, reason required; SALE rejected; replay on same key+fingerprint, 409 on different fingerprint | `FnbInventoryService.delta/reason/recordMovement` |
| ADJUSTMENT and ingredient/recipe management owner only (`requireOwner` throws ResourceNotFoundException, i.e. 404 for a cashier on the cashier route); RESTOCK/WASTE/reads need `requireOutlet` | `FnbInventoryService`, `MerchantAccessGuard.requireOwner`, test `cashierMayRestockAndWasteButOnly...` (404 comment) |
| Unit locked once movements exist; ingredient name unique per outlet | `FnbInventoryService.updateItem`; V4 `uk_inventory_items_outlet_name` |
| Deduct on paid in both settle paths, after payment flush, before audit; one SALE per (order, ingredient), key `sale:{orderId}:{itemId}`, reason "Penjualan {orderNumber}"; need = recipe qty x line qty (option: x its line's qty); needs in `TreeMap` (ascending id); balance lock `PESSIMISTIC_WRITE` refresh; warnings "minus"/"habis"/"menipis" format `{name}: sisa {qty} {unit} ({label})` | `FnbStockService`, `FnbPaymentService.settle`, `MockFnbGatewayService.settle`, `FnbInventoryRepository.lock` |
| Tenant lock precedes stock locks in both payment paths; manual movements take only the balance lock | `FnbPaymentService.pay`, `MockFnbGatewayService.initiate/resolve` (`lockTenant`), `FnbInventoryService.recordMovement` |
| Status: <0 NEGATIVE, ==0 OUT, <= reorder LOW, else OK; no check constraint on balances | `InventoryBalanceEntity.status`, V4 header comment, V1 table |
| Paid order cannot be cancelled | `SalesOrderEntity.cancel` -> `requireNewUnpaid` |
| `reversal_of_movement_id` column exists, unused in code | V1, `InventoryMovementEntity` (grep for "reversal" finds only the field) |
| Tables: inventory_* in V1; recipe_lines, movement-type check, two indexes, unique name in V4 | V1, `V4__fnb_inventory_recipe.sql` |
| V5: `archived` column, join table (unique product+group, display_order), backfill, drops `option_groups.product_id` | `V5__reusable_option_groups.sql` |
| Attach is replace-all, ordered, rejects duplicates, no audit; archive is 409 while linked to a non-archived product; archived groups filtered out of product groups; recipe changes are audited (`FNB_RECIPE_CHANGED`) | `FnbCatalogService`, `FnbCatalogRepository.groups/groupInUse`, `AuditLogEntity.Action` |
| Pricing validates selected options against the product's attached non-archived groups; min/max enforced; unattached option -> "Pilihan bukan milik produk" | `FnbOrderPricingService.selectedOptions` and the lines above it |
| Option arithmetic of the demo: subtotal, 11% tax and 5% service on the subtotal (83.520 check from existing step 7: 72.000 x 1.16) | `demo.md` step 7, `R__demo_seed.sql` (tenant 11.00 / 5.00) |
| Seed: 7 ingredients, balances, "Stok awal" movements, 11 recipe lines, Gula Aren 400 vs reorder 500 | `db/demo/R__demo_seed.sql` |
| Integration classes and counts 13 / 6 / 8 | `grep` of `@Test` method lines in the three test classes (matches the packet); method names used for the bullet descriptions |
| Concurrency tests run in-process via MockMvc against a PostgreSQL container, not over a network | imports and `webAppContextSetup` in `FnbStockConcurrencyIntegrationTest` |
| 460 unit / 75 integration passed, PostgreSQL 17.11, 2026-11-10 | **taken from the packet** (orchestrator run); not independently verifiable by the worker |
| Known limits (same-key parallel 409 for loser; `archiveGroup` no lock; group response lacks products; no audit on attach/detach; deactivation does not stop recipes; one unit) | packet; confirmed in code for: no lock and no audit (`FnbCatalogService`), no products in `FnbOptionGroupResponse`, one unit (single `unit` column); deactivation behaviour confirmed by test name `deactivatedIngredientLeavesListAndNewRecipesButExistingRecipeLineStillDeducts` and `items()` filtering `active=true`; same-key parallel 409 NOT confirmed from code (unique constraint on (tenant, idempotency_key) makes it plausible) |
| PRD section 5 still lists inventory as post-MVP; scope changed by explicit user decision | `PRD-MVP.md` line 53, `.ai/DECISIONS.md` (read only) |

## Deviations from the packet (code or repo wins)

1. **README "Not yet covered: CASHIER-role token paths and MOCK_GATEWAY payments" and the roadmap "Test gaps" line were stale**: `FnbInventoryFlowIntegrationTest` covers a CASHIER token (RESTOCK/WASTE allowed, ADJUSTMENT 404) and MOCK_GATEWAY deduction (`mockGatewayDeductsStockOnlyWhenResolvedSucceededAndExactlyOnce`). I reworded both to "covered for the inventory flows only; MOCK_GATEWAY concurrency not covered". The packet only asked to drop 200/10.
2. **PRD conflict stated**: the README roadmap says inventory is implemented by explicit user decision although PRD section 5 still lists it post-MVP (the packet said to replace the line with the "real status"; this nuance comes from `.ai/DECISIONS.md`).
3. The seed ALREADY contains ingredients, balances and recipes (the packet said to check). The demo section therefore does not need to create seed data, but it creates its own ingredient/product/group so numbers do not depend on prior stock consumption. Side effect to know: it attaches the new group to the seeded Espresso (the only mutation of seed data).
4. Inventory/recipe subsections were placed inside section 4 (Request flows) rather than as new numbered sections, to avoid renumbering.
5. Mutation-sensitivity is listed as "not established" in README without any result, per the packet.

## Unconfirmed / for the orchestrator to check when executing demo.md

- All numbers marked "expected" are hand-computed: step 4 total 139200.00; step 5 change 10800.00 and warning `Sirup Vanila <SUF>: sisa 70 ml (menipis)`; step 6 deltas -72 (Biji Kopi 3x18 + 1x18), -450 (Susu), -80 (new ingredient); step 11 balance 120 with 5 movements; step 12 total 306240.00, change 3760.00, warning `... sisa -40 ml (minus)`. The README/demo step 7 note that the first walkthrough's payment returns `["Gula Aren: sisa 380 ml (menipis)"]` is also hand-computed (fresh stack only).
- Python prints `quantityDelta` as a float (`-72.0`), the docs say so; if Jackson serializes differently the printed form may differ.
- HTTP codes written in comments (201 on create/movement incl. replay, 200 on PUT/pay, 404 for cashier ADJUSTMENT) come from controller annotations and the test comment; not executed.
- Step 8 relies on a replayed movement returning the same `data.id` (code path `FnbInventoryMapper.movement(existing)`).
- `Verification: pending` is the single placeholder in `docs/demo.md` to replace after the run.
- If the demo seed was not loaded (profile not `demo`), `ESP`, `KOPI`, `SUSU` and category `...1001` do not exist and steps 2-6 will fail with 404.
