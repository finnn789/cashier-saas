# TASK-097: Audit log for manual stock movements and stock counts (backend)

Status: DONE, REVIEWED, COMPILED, NOT COMMITTED (2026-11-25, memory v98).

## User instruction (2026-11-25)
"Jika ada task lagi silahkan buatkan dan delegasikan." Picked from the audit backlog in `.ai/TASKS.md`: "audit log lacks stock events (count/adjust/waste/restock)". AGENTS.md domain invariant: record actor, timestamp and reason for price, discount, cancellation and payment actions; the owner relies on the audit trail to explain stock leakage.

## Verified facts (orchestrator, source read)
- `entity/shared/audit/AuditLogEntity.java`: `enum Action` with an entity type per value (e.g. `SHIFT_OPENED("CASH_SHIFT")`); factory `AuditLogEntity.recordAction(TenantEntity, OutletEntity, StaffUserEntity actor, Action, UUID entityId, String reason, String details)`.
- Table `audit_logs` (V1): `action varchar(80)`, `entity_type varchar(80)`, `reason varchar(1000)`, `details text`. Action is a plain string column with no CHECK constraint, so **no migration is needed**. Index V15 `(tenant_id, entity_id, action)`.
- Writer pattern to copy: `service/FnbShiftService.java:74-76` (`audit.persist(AuditLogEntity.recordAction(context.getTenant(), context.getOutlet(), context.getMembership().getUser(), Action.X, id, reason, "k=v k=v"))`, `AuditLogRepository audit`).
- `service/FnbInventoryService.java`:
  - `recordMovement(...)` (:104): manual RESTOCK / WASTE / ADJUSTMENT (ADJUSTMENT owner-only), idempotent: if `existing != null` it returns the earlier movement (a replay); otherwise it persists `movement` and flushes.
  - `stockCount(...)` (:139): owner-only; replay paths (`recorded != null`, or legacy `replay` when `existing` is non-empty) write nothing new; the new path persists `header` (`StockCountEntity`), ADJUSTMENT movements for lines with a non-zero delta, and the lines.
  - Fields now: `guard`, `inventory`, `tokens`, `margins` (`@RequiredArgsConstructor`).
- Unit tests `FnbInventoryServiceTest` and `FnbInventoryCostAndStockCountTest` use `@InjectMocks`; a new constructor field arrives as null there (runtime NPE in tests that hit the new audit line). Tests are NOT in scope now (policy); record this in the report as a test-phase item.

## Design decisions
- D1 New actions: `FNB_STOCK_MOVEMENT_RECORDED("INVENTORY_MOVEMENT")` and `FNB_STOCK_COUNT_RECORDED("STOCK_COUNT")`.
- D2 `recordMovement`: on the NEW path only (never on an idempotent replay), one audit row: entityId = movement id, reason = the movement reason, details `type=<TYPE> item=<itemId> quantity=<delta plain> unitCost=<plain or ->`. Actor = `context.getMembership().getUser()`.
- D3 `stockCount`: on the NEW path only (`header != null`), one audit row for the whole count: entityId = header id, reason = the note or null, details `lines=<n> changed=<lines with non-zero delta>`. The per-line ADJUSTMENT movements created by a count are NOT audited separately (the count row covers them).
- D4 Sales, sale reversals and refunds keep their existing audit (order/refund actions); no audit for SALE movements.
- D5 Nothing else: no read endpoint, no DTO change, no migration, no proto/Postman change, no tests.

## Wave 1

### J1 (`java-worker`)
Reserved: `src/main/java/org/lite/project/cashiersaas/entity/shared/audit/AuditLogEntity.java` (two enum values only), `src/main/java/org/lite/project/cashiersaas/service/FnbInventoryService.java` (inject `AuditLogRepository`, two audit writes).
Report: `reports/TASK-097-J1.md` (list the unit tests that will need an `AuditLogRepository` mock).
Acceptance:
- A new manual movement writes exactly one audit row with actor, reason and details; a replay with the same idempotency key writes none.
- A new stock count writes exactly one audit row; a replay writes none.
- The audit row is written in the same transaction as the movement/count (no separate transaction).
- Follows AGENTS.md (no wildcard generics, no `@Deprecated`, no new abstractions).
