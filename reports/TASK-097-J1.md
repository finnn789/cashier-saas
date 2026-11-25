# TASK-097 J1 report

Status: DONE (code written, not compiled)
Baseline: working tree on `feature/leakage-report` (memory v97)

Changed (modified): `entity/shared/audit/AuditLogEntity.java` (two enum values), `service/FnbInventoryService.java` (inject `AuditLogRepository audit`, two audit writes, `changed` counter).

Contract: D1 actions added. D2 audit after persist/flush on the new path of `recordMovement` only (replay returns earlier). D3 one audit row on the new path of `stockCount` (`header != null`), details `lines=<n> changed=<n>`, reason = note or null. Same transaction (`@Transactional` methods, `audit.persist`).

Deviations: none. Open questions: none.

Tests needing an `AuditLogRepository` mock (`@Mock`, otherwise NPE on new-path calls): `FnbInventoryServiceTest`, `FnbInventoryCostAndStockCountTest`.

Test status: NOT RUN (policy)
