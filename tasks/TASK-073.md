# TASK-073: Repoint Postman folders "08 - F&B Option Groups" and "09 - F&B Options" to the V5 routes

Status: DELEGATED (2026-11-11). Memory version at delegation: 67. Branch `feature/fnb-stock-concurrency-tests` @ `0f39c5f` (pushed). Postman/docs scope only: NO application code, NO tests, NO Maven. Origin: since V5 (TASK-064/065/066) option groups are reusable at outlet level and the per-product routes `/products/{productId}/groups/**` no longer exist; the collection `postman/collections/KedaiMbo API` still uses them (the other collection `F&B Catalog Controller`, commit 3c75b15, is already current and can serve as a style/body reference if it holds equivalent requests).

## Reserved writes (ONLY these)
- `postman/collections/KedaiMbo API/08 - F&B Option Groups/*.request.yaml` (edit the 3 existing, add new ones)
- `postman/collections/KedaiMbo API/09 - F&B Options/*.request.yaml` (edit the 3 existing)
- `reports/TASK-073-W1.md`
DO NOT touch anything else under `postman/**` or `.postman/**` (the user has unrelated uncommitted edits there: `.resources/definition.yaml`, folder 14, deleted folder 15, `postman/flows/`, `postman/specs/`, `.postman/resources.yaml`), nor `src/**`, `.ai/*`, `tasks/*`, `README.md`.

## Contract (verified routes, controller `FnbCatalogController`, prefix `/api/v1`)
Folder 08 (reusable groups; `{{outletId}}` as today):
- Create Option Group: `POST {{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/fnb/option-groups` (keep order 1000)
- List Option Groups: `GET {{baseUrl}}/api/v1/cashier/outlets/{{outletId}}/fnb/option-groups` (order 2000)
- Update Option Group: `PUT {{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}` (order 3000)
- NEW Delete Option Group: `DELETE .../merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}` (order 4000) (archive semantics: read `FnbCatalogController`/service for the success message/status; do not invent)
- NEW Attach Option Groups To Product: `PUT .../merchant/outlets/{{outletId}}/fnb/products/{{productId}}/option-groups`, body per `FnbProductOptionGroupsRequest` (order 5000)
- NEW List Product Option Groups: `GET .../cashier/outlets/{{outletId}}/fnb/products/{{productId}}/option-groups` (order 6000)
Folder 09 (options belong to a group, not to a product):
- Create Option: `POST .../merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}/options`
- List Options: `GET .../cashier/outlets/{{outletId}}/fnb/option-groups/{{groupId}}/options`
- Update Option: `PUT .../merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}/options/{{optionId}}`
No `{{productId}}` may remain in folder 09 URLs.

## Rules
- You inherit no conversation. Read this file, `AGENTS.md`, `FnbCatalogController.java`, the DTOs `FnbOptionGroupRequest`, `FnbOptionRequest`, `FnbProductOptionGroupsRequest` (field names, `@NotNull/@Size/@Min` constraints) and the nearby response DTOs, then the existing request files for the exact YAML shape. Request bodies MUST be valid JSON and MUST match the DTO fields and constraints exactly (the old bodies may carry obsolete fields; remove what the DTO no longer has, keep valid examples). Use only variables the collection already defines (`baseUrl`, `outletId`, `productId`, `groupId`, `optionId`); if the collection has no `groupId`/`optionId` variable defined, still use those names (do not edit the dirty definition file) and say so in the report.
- Keep the YAML shape (`$kind: http-request`, `url`, `method`, `headers`, `body`, `order`) and file naming `<Request Name>.request.yaml`. Do not rename existing request files. No auth block changes (inherits from the collection as today).
- DO NOT RUN anything (no Maven, no Postman CLI, no git). Re-read each file for YAML/JSON validity.
- Report `reports/TASK-073-W1.md`: per file the old -> new URL/body, DTO evidence (class + constraint you matched), anything you could not verify.

## Orchestrator acceptance
Read-only review of the diff against the controller/DTOs; confirm no files outside the reserved paths changed. The collection is NOT executed against a live server in this task.
