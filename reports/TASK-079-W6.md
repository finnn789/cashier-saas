# TASK-079 W6 report (docs, Postman, UI prompt)

Nothing was run; no Java/SQL touched.

## Files
- `postman/collections/F&B Catalog Controller/06 - Stock Movements/Record Restock.request.yaml`: body now has `"unitCost": 120.5000`; description added (price semantics, owner-only cost fields).
- `postman/collections/F&B Catalog Controller/06 - Stock Movements/Stock Count (Owner Only).request.yaml` (new, order 5000): `POST {{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/fnb/inventory/stock-counts`, same header/script/variable style as siblings (`{{inventoryItemId}}`, `{{$guid}}`).
- `postman/collections/KedaiMbo API/20 - F&B Owner Reports/` (new folder + `.resources/definition.yaml` order 20000) with `Owner Sales Report.request.yaml`.
- `docs/fnb-operations.md`: new "M6" section before "Recommended verification".
- `README.md`: M6 feature bullet; the stock-deduction table row no longer says deactivated ingredients keep deducting.
- `prompts/m6-ui-owner.md` (new).

## Deviations
- The packet said to find existing inventory and sales-report requests under `KedaiMbo API/`. Inventory requests live in the separate `F&B Catalog Controller` collection (so restock/stock count went there), and NO sales report request existed anywhere, so I created `Owner Sales Report` in a new folder `20 - F&B Owner Reports` instead of updating a description.
- The stock count endpoint is under `/merchant/...` (owner only), as in the controller.

## Risks
- Stock count request depends on `inventoryItemId` collection variable (same as sibling requests).
- Docs state the contract only; behaviour is not yet compiled or verified.
