# TASK-085 D1 report (Postman)

Status: DONE (files written, not executed by policy)
Baseline: commit 20faa7a + uncommitted TASK-084 tree, memory version 91
Test status: NOT RUN (policy)

## Changed files
- NEW `postman/collections/KedaiMbo API/22 - F&B Menu Margins/.resources/definition.yaml` (collection, order 22000)
- NEW `postman/collections/KedaiMbo API/22 - F&B Menu Margins/Menu Margin Report.request.yaml` (GET `/api/v1/merchant/outlets/{{outletId}}/fnb/reports/menu-margins`, order 1000, status 200 + shape test)
- NEW `postman/collections/KedaiMbo API/22 - F&B Menu Margins/Menu Margin Report Below Target.request.yaml` (same path with `?belowTargetOnly=true`, order 2000, asserts every menu is BELOW_TARGET)
- MODIFIED `postman/collections/KedaiMbo API/19 - Business Settings/Update Business Settings.request.yaml` (body gains `"targetMarginPct": 60.00`; description notes it is optional, 0 to <100, omitted = unchanged)

## Not touched
- `postman/.../19 - Business Settings/Get Business Settings.request.yaml` left as is (its description does not yet mention targetMarginPct; the response field is part of the contract, so a one-line description update can follow if wanted).
- `.postman/resources.yaml` (dirty user file) not touched.

## Contract points
- Routes and DTO field names taken from the packet (menu-margins, belowTargetOnly, targetMarginPct, status values, suggestedPrice, missingCostItems).
- The route `menu-margins` does not exist in Java yet (J1 controller is being written in parallel); the requests are written against the packet contract.

## Open questions
- None blocking. The packet says "existing business settings PUT request file if present"; it was present, so the only change is the body field and description.
