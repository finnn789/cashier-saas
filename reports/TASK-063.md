# TASK-063 Report: Postman requests for F&B inventory / recipe API

Status: SUBMITTED by worker. Nothing run against a live server; tests/Maven/git not touched.

## Files created (all under `postman/collections/F&B Catalog Controller/`)
- `05 - Inventory Items/.resources/definition.yaml` (order 5000)
  - Create Ingredient (1000), Update Ingredient (2000), Deactivate Ingredient (3000), List Ingredients (4000), List Ingredients By Status (5000)
- `06 - Stock Movements/.resources/definition.yaml` (order 6000)
  - Record Restock (1000), Record Waste (2000), Record Adjustment (Owner Only) (3000), List Movements (4000)
- `07 - Recipes/.resources/definition.yaml` (order 7000)
  - Set Product Recipe (1000), Get Product Recipe (2000), Set Option Recipe (Add-on) (3000), Get Option Recipe (Add-on) (4000)

Each request is named `<Name>.request.yaml`. Format copied from `02 - Products/Update Product`, `04 - Options/Create Option`, `List Products`, `Archive Product` (headers, single-line JSON `body.content`, one `afterResponse` script, `order`). No Authorization header. Variables: `baseUrl`, `outletId`, `productId`, `groupId`, `optionId` (existing), `inventoryItemId` (set by Create Ingredient), `movementId` (set by Record Restock). Idempotency keys use `{{$guid}}`.

## Verification against code
Checked `FnbInventoryController`, `FnbCatalogController` (recipe routes), `FnbInventoryItemRequest`, `FnbStockMovementRequest`, `FnbRecipeRequest`, and the three response DTOs, `InventoryBalanceEntity.Status` (OK, LOW, OUT, NEGATIVE), `InventoryMovementEntity.Type` (RESTOCK, WASTE, ADJUSTMENT, SALE), and the page/size check in `FnbInventoryService.movements` (size 1-50). All URLs, methods, JSON field names (`name, sku, unit, reorderLevel, initialQuantity`; `type, quantity, reason, idempotencyKey`; `lines[].inventoryItemId, quantityPerUnit`) and response fields (`id, stockStatus, type, quantityDelta, lines[].inventoryItemName, unit, quantityPerUnit`) match the packet. The option recipe path variable is `{id}` in code; the request uses `{{optionId}}` as specified.

## Validation
Command (files were generated and checked with scratch scripts outside the repo; the check script is):

```
uv run --project E:/Java/cashier-saas/Ai-services python -I <scratchpad>/val63.py "E:/Java/cashier-saas/postman/collections/F&B Catalog Controller"
```

The script runs `yaml.safe_load` on every YAML in the three folders (including `.resources/definition.yaml`), asserts `$kind`, `method`, `url`, script code and `order` exist, and `json.loads` the body `content` (with `{{...}}` placeholders substituted). Output ended with: `16 files parsed` (all lines `OK`).

## Deviations
None. Note: `List Ingredients By Status` test asserts `data` is an array and every item `stockStatus` equals `LOW` (passes vacuously on an empty list). Number fields in tests use `Number(res.data.quantityDelta)` in case BigDecimal serializes as a string.
