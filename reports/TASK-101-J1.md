# TASK-101 J1 report

Status: implemented, not compiled by worker. Baseline: working tree `feature/leakage-report` (packet memory v100).

## Changed files (under src/main/java/org/lite/project/cashiersaas/)
New: `controller/FnbCatalogCopyController.java`, `service/FnbCatalogCopyService.java`, `dto/request/FnbCatalogCopyRequest.java`, `dto/response/FnbCatalogCopyResponse.java`.
Modified: `repository/FnbCatalogRepository.java` (+allCategories, optionsOfGroups, productGroupLinks, hasProductsOrGroups), `repository/FnbInventoryRepository.java` (+hasItems), `entity/shared/audit/AuditLogEntity.java` (+CATALOG_COPIED("OUTLET")).

## Contract points
- POST /api/v1/merchant/outlets/{targetOutletId}/catalog/copy, owner via requireOwnerIgnoringAllowance on the token outlet; both outlets via findInTenant (404); same -> 400; inactive target -> 409; target outlet write lock (orders.lockOutlet) before the emptiness check (products, groups, items incl. archived/inactive) -> 409.
- Copies categories (reuse by case-insensitive trimmed name), products, groups, options, links, active items (zero balance, no cost), recipe lines (skipped + counted when the item is inactive). One audit row; response counts; message "Menu berhasil disalin.".
- Bulk reads only (one query per table); no N+1.

## Deviations / notes
- Target archived category with a matching name cannot be reused (unique name) and cannot be created: returns 409 "Outlet tujuan memiliki kategori terarsip dengan nama yang sama." (not in the contract).
- Source products whose category is archived are skipped (hidden in the source too).
- Lock is on the target only; source is read without lock.
- Controller returns 200 (not 201).

## Existing tests to update
None identified. AuditLogEntity enum gained one value.

## Open questions
None.

Tests: NOT RUN (policy).
