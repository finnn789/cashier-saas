# TASK-066: Postman requests for reusable option groups (TASK-064)

Status: DELEGATED (2026-11-10), parallel with TASK-064/065. Memory version at delegation: 59 (-> 60). Branch `feature/fnb-reusable-option-groups`.

## Worker rules
- You inherit no conversation. Read this file, `tasks/TASK-064.md` (routes table and request bodies are the contract; the controller code is being rewritten in parallel so code the contract) and, for format, existing files under `postman/collections/F&B Catalog Controller/` (`03 - Option Groups/*`, `04 - Options/*`, `07 - Recipes/Set Option Recipe (Add-on).request.yaml`, the folder `.resources/definition.yaml` files). Copy their structure EXACTLY (`$kind: http-request`, url, method, headers, `body.type: json` single-line JSON, test scripts, collection variables only: `baseUrl`, `outletId`, `productId`, `groupId`, `optionId`, `inventoryItemId`).
- Reserved writes (ONLY): `postman/collections/F&B Catalog Controller/03 - Option Groups/**`, `.../04 - Options/**`, `.../07 - Recipes/Set Option Recipe (Add-on).request.yaml`, `.../07 - Recipes/Get Option Recipe (Add-on).request.yaml`, and `reports/TASK-066.md`. Do NOT touch folders 01, 02, 05, 06, `postman/collections/KedaiMbo API/**`, `postman/flows/**`, `postman/specs/**`, `.postman/resources.yaml`, `.ai/*`, `tasks/*`, `src/**`.
- No Authorization header (the collection handles it). No git commands, no Maven. Validate every YAML you wrote parses (e.g. `uv run --project E:\Java\cashier-saas\Ai-services python -I -c "import yaml,sys; ..."`) and report the exact command and result. No secrets.

## Changes
Folder `03 - Option Groups` (rename none; keep the folder):
1. `Create Option Group` -> `POST {{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/fnb/option-groups` (body: name, required, minimumSelections, maximumSelections, displayOrder; no productId); test script saves `data.id` to `groupId` (keep whatever the current script does, adapted).
2. `Update Option Group` -> `PUT .../fnb/option-groups/{{groupId}}`.
3. `List Option Groups` -> `GET {{baseUrl}}/api/v1/cashier/outlets/{{outletId}}/fnb/option-groups`.
4. NEW `Archive Option Group` -> `DELETE .../merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}` (409 while attached to a product).
5. NEW `Set Product Option Groups` -> `PUT .../merchant/outlets/{{outletId}}/fnb/products/{{productId}}/option-groups` body `{"groupIds": ["{{groupId}}"]}` (replace-all, ordered; empty array detaches all); test: 200, `data` is an array.
6. NEW `Get Product Option Groups` -> `GET .../cashier/outlets/{{outletId}}/fnb/products/{{productId}}/option-groups`; test: 200.
Folder `04 - Options`: all three requests move to `.../fnb/option-groups/{{groupId}}/options[/{{optionId}}]` (create POST, update PUT with `/{{optionId}}`, list GET under `/cashier/`); drop the product segment.
Folder `07 - Recipes`: only the two option-recipe requests change to `.../merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}/options/{{optionId}}/recipe` (PUT body unchanged). Product recipe requests untouched.
If you add files, give the new `.request.yaml` files unique, ordered names consistent with the folder, and update the folder's `.resources/definition.yaml` only if the existing convention requires listing them (check how 05/06/07 did it).
Report in `reports/TASK-066.md`: files changed/created, validation command + output, deviations.
