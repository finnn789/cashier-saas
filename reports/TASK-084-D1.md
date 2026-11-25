# TASK-084 D1: Postman requests for F&B leakage

Status: DONE (Postman files only). Tests: NOT RUN (policy). Baseline commit: `20faa7a`.

## Changed files (all new)
- `postman/collections/KedaiMbo API/21 - F&B Leakage/.resources/definition.yaml` (folder, `order: 21000`, same shape as folder 20)
- `postman/collections/KedaiMbo API/21 - F&B Leakage/Stock Count History.request.yaml` (GET `/api/v1/merchant/outlets/{{outletId}}/fnb/inventory/stock-counts?page=0&size=20`)
- `postman/collections/KedaiMbo API/21 - F&B Leakage/Leakage Report Per Outlet.request.yaml` (GET `/api/v1/merchant/outlets/{{outletId}}/fnb/reports/leakage`, no stockCountId, so latest count)
- `postman/collections/KedaiMbo API/21 - F&B Leakage/Consolidated Leakage Report.request.yaml` (GET `/api/v1/merchant/reports/leakage?from=2026-11-01&to=2026-11-14`)

## Contract points done
- Three GET requests from the packet's REST (J1) section, with routes copied from `FnbLeakageReportController`.
- Afterresponse tests check status 200, `success`, and the response fields named in the contract (`stockCountId`, `flaggedCount`, `totalLoss`, `lines`, `outlets`).
- Descriptions in Indonesian, covering OWNER-only access, the D1-D9 leakage rules, and the 404 cases.

## Deviations
- The stock-count POST example was not written. The packet says "updated stock-count example if present", and no inventory request file exists under `postman/collections/KedaiMbo API/`, so there was nothing to update.
- The per-outlet request omits `stockCountId` instead of sending an empty value. An empty value would fail UUID binding and return 400.
- No `examples/` subfolders were added. Existing folders have them only for some requests, and the packet did not ask for them.

## Open questions
- None for D1. The collection-level `baseUrl` and `outletId` variables are assumed, as in the existing Owner Sales Report request.
