# TASK-100 J1 report

Status: DONE (implementation), not compiled by worker. Baseline: working tree `feature/leakage-report` at cee0f5f plus uncommitted TASK-094/096/097/098 work.
Test status: NOT RUN (policy).

## Changed files (base `src/main/java/org/lite/project/cashiersaas/`)
New: `resources/db/migration/V16__purchases.sql`; `entity/shared/inventory/PurchaseEntity.java`, `PurchaseLineEntity.java`; `dto/request/FnbPurchaseRequest.java`, `FnbPurchaseLineRequest.java`; `dto/response/FnbPurchaseResponse.java`, `FnbPurchaseLineResponse.java`, `FnbPurchaseSummaryResponse.java`.
Modified (additions only): `repository/FnbInventoryRepository.java`, `service/FnbInventoryService.java` (+ `actorName(UUID, UUID)` overload used by the old movement variant), `controller/FnbInventoryController.java`, `dto/FnbInventoryMapper.java`, `entity/shared/audit/AuditLogEntity.java` (`FNB_PURCHASE_RECORDED("PURCHASE")`).

## Contract points done
- POST/GET list/GET detail under `/merchant/outlets/{outletId}/fnb/inventory/purchases`, `guard.requireOwner`, tenant from principal; foreign item/purchase -> 404 (`ResourceNotFoundException`).
- Validation per contract; date check uses `context.getTenant().getTimeZone()`; duplicate item -> 400 "Bahan duplikat dalam pembelian."
- One transaction: balances locked in item-id order, `receive` + RESTOCK movement with unitCost, header + lines, audit row (reason = note, details `supplier=.. lines=.. total=..`).
- Replay (same key + fingerprint, same outlet) returns first response, no new rows, no `marginWarnings` (null, omitted); mismatch -> 409 "Kunci idempotensi telah digunakan."
- Line total = qty*cost HALF_UP 2dp, total = sum. Lines ordered by name (then id). POST `marginWarnings` is distinct, only for items whose average changed (always an array on POST, maybe empty).
- List: size 1..50, page >= 0 (else 400 "Parameter halaman tidak valid."), 1 header query + 1 grouped count query + 1 names query.

## Deviations
- Movement idempotency key is `purchase:<sha digest of key>:<itemId>` (not the raw key): a 128-char purchase key plus item id would overflow `inventory_movements.idempotency_key` varchar(128). Digest is 64 chars (same helper as stock counts).
- Movement fingerprint = header fingerprint (as the contract says).

## Exact SQL
See `src/main/resources/db/migration/V16__purchases.sql` (tables `purchases`, `purchase_lines`, indexes `idx_purchases_outlet_date`, `idx_purchase_lines_item`, unique `uk_purchases_tenant_key`, `uk_purchase_lines_purchase_item`).

## Existing tests
No constructor or dependency change in `FnbInventoryService`, so `@InjectMocks` setups need no change. `FnbInventoryMapper` and `FnbInventoryRepository` only gained methods.

## Open questions
None. Concurrent identical first POSTs hit the unique key (500), same as the existing stock-count behaviour.
