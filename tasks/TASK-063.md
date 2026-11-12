# TASK-063: Postman requests for the F&B inventory / recipe API (TASK-061)

Status: DELEGATED (2026-11-10). Memory version at delegation: 57. User decisions: add them to the existing collection `postman/collections/F&B Catalog Controller` as folders 05-07, then commit and push so the user's Postman picks them up on pull.

## Worker rules
- You inherit no conversation. Read this file, `tasks/TASK-061.md` (endpoint contract, sections W2/W3) and the REAL source of truth for JSON: `dto/request/FnbInventoryItemRequest.java`, `FnbStockMovementRequest.java`, `FnbRecipeRequest.java`, `dto/response/*Inventory*`, `FnbStockMovementResponse.java`, `FnbRecipeResponse.java`, `controller/FnbInventoryController.java`, `controller/FnbCatalogController.java` (recipe routes).
- Reserved writes (ONLY these): `postman/collections/F&B Catalog Controller/05 - Inventory Items/**`, `.../06 - Stock Movements/**`, `.../07 - Recipes/**`, and `reports/TASK-063.md`. Do NOT touch the existing folders 01-04, `postman/collections/KedaiMbo API/**`, `postman/flows/**`, `postman/specs/**`, `.postman/**`, `.ai/*`, `tasks/*`, or any `src/**`.
- Copy the format of the existing files EXACTLY: read `.../02 - Products/Update Product.request.yaml`, `.../04 - Options/Create Option.request.yaml` and `.../04 - Options/.resources/definition.yaml` first (keys `$kind: http-request`, `url`, `method`, `headers`, `body.type: json` + single-line JSON `content`, `scripts` with one `afterResponse` JS test, `order`). Each new folder needs `.resources/definition.yaml` with `$kind: collection` and `order: 5000` / `6000` / `7000`. Request `order` values 1000, 2000, 3000, ... within a folder. You may use the skill `postman:collection-schema-v3` for the v3 shape. Use ASCII only in file names and values except Indonesian example strings.
- No Authorization header (existing requests have none; the collection handles it). Use only collection variables via `{{...}}`: existing `baseUrl`, `outletId`, `productId`, `groupId`, `optionId`; new ones `inventoryItemId` (set by the Create Ingredient test script), `movementId`. Idempotency keys: `{{$guid}}` in the body.
- Do not run Maven, do not touch git (no add/commit/push). Validate every YAML you wrote parses (e.g. `uv run --project E:\Java\cashier-saas\Ai-services python -I -c "import yaml,sys; ..."` or any available parser) and report the exact command/output. Never put real secrets in files.

## Requests to create (13). Names = file names `<Name>.request.yaml`

Folder `05 - Inventory Items` (owner, `/merchant/`):
1. `Create Ingredient` POST `{{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/fnb/inventory/items` body `{"name": "Susu Segar", "sku": "BAHAN-SUSU-01", "unit": "ml", "reorderLevel": 500.000, "initialQuantity": 5000.000}`; test: status 201, `success` true, `data.stockStatus` exists, saves `data.id` to collection variable `inventoryItemId`.
2. `Update Ingredient` PUT `.../fnb/inventory/items/{{inventoryItemId}}` body same shape (name/sku/unit/reorderLevel; initialQuantity is ignored on update); test: 200.
3. `Deactivate Ingredient` DELETE `.../fnb/inventory/items/{{inventoryItemId}}`; test: 200.
4. `List Ingredients` GET `{{baseUrl}}/api/v1/cashier/outlets/{{outletId}}/fnb/inventory/items`; test: 200 and `data` is an array.
5. `List Ingredients By Status` GET same URL with `?status=LOW` (valid values OK, LOW, OUT, NEGATIVE); test: 200 and every item has `stockStatus` equal to LOW.

Folder `06 - Stock Movements` (staff, `/cashier/`, path `.../fnb/inventory/items/{{inventoryItemId}}/movements`):
6. `Record Restock` POST body `{"type": "RESTOCK", "quantity": 1000.000, "reason": "Belanja pasar pagi", "idempotencyKey": "{{$guid}}"}`; test: 201, `data.type` RESTOCK, `data.quantityDelta` > 0, saves `data.id` to `movementId`.
7. `Record Waste` POST `{"type": "WASTE", "quantity": 120.000, "reason": "Tumpah saat produksi", "idempotencyKey": "{{$guid}}"}`; test: 201, `quantityDelta` < 0.
8. `Record Adjustment (Owner Only)` POST `{"type": "ADJUSTMENT", "quantity": -50.000, "reason": "Koreksi stock opname", "idempotencyKey": "{{$guid}}"}` (signed quantity, non-zero; cashier gets 404); test: 201.
9. `List Movements` GET same path with `?page=0&size=20` (size 1-50); test: 200, array.

Folder `07 - Recipes` (owner, `/merchant/`; PUT replaces the whole recipe, an empty `lines` array clears it):
10. `Set Product Recipe` PUT `{{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/fnb/products/{{productId}}/recipe` body `{"lines": [{"inventoryItemId": "{{inventoryItemId}}", "quantityPerUnit": 18.000}]}`; test: 200, `data.lines` length 1.
11. `Get Product Recipe` GET same URL; test: 200, lines have `inventoryItemName`, `unit`, `quantityPerUnit`.
12. `Set Option Recipe (Add-on)` PUT `{{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/fnb/products/{{productId}}/groups/{{groupId}}/options/{{optionId}}/recipe` body `{"lines": [{"inventoryItemId": "{{inventoryItemId}}", "quantityPerUnit": 50.000}]}`; test: 200.
13. `Get Option Recipe (Add-on)` GET same URL; test: 200.

Confirm each URL/method/JSON field name against the controllers/DTOs listed above; the code wins over this packet if they differ (report the difference). Report in `reports/TASK-063.md`: files created, the validation command + result, and any deviation.
