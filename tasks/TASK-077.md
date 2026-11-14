# TASK-077: M4 — cash shifts and owner void/refund (PRD v2 §8.8, §8.10, AC-03, AC-06, AC-07)

Status: PLANNED (2026-11-13), delegation starts after TASK-076 W5 is reviewed. Memory version at planning: 71. Branch `fix/flow-controls-a` (stacked on TASK-076, same branch; baseline = the reviewed TASK-076 tree, saved as a patch by the orchestrator before W1 starts).

Global rules: identical to `tasks/TASK-076.md` "Global rules" 1-7 (report files `reports/TASK-077-W<n>.md`). Workers never run Maven. Never edit migrations V1..V6.

## Contract

### Schema — V7 `src/main/resources/db/migration/V7__cash_shifts_and_refunds.sql`
- `cash_shifts`: `id uuid PK`, `tenant_id uuid NOT NULL FK tenants`, `outlet_id uuid NOT NULL FK outlets`, `membership_id uuid NOT NULL FK tenant_memberships`, `opened_by_staff_user_id uuid NOT NULL FK staff_users`, `status varchar(16) NOT NULL CHECK (status IN ('OPEN','CLOSED'))`, `opening_cash numeric(19,2) NOT NULL CHECK (opening_cash >= 0)`, `opened_at timestamptz NOT NULL`, `closed_by_staff_user_id uuid NULL FK staff_users`, `closed_at timestamptz NULL`, `expected_cash numeric(19,2) NULL`, `counted_cash numeric(19,2) NULL CHECK (counted_cash >= 0)`, `cash_difference numeric(19,2) NULL`, `closing_note varchar(500) NULL`, `version bigint`/`created_at`/`updated_at` exactly like other `MutableBaseEntity` tables (copy column names/types from an existing mutable table in V1). Unique partial index `uk_cash_shifts_open_per_member` on `(outlet_id, membership_id) WHERE status = 'OPEN'`. Index `(tenant_id, outlet_id, opened_at)`.
- `payments`: `ADD COLUMN cash_shift_id uuid NULL REFERENCES cash_shifts(id)` (NULL for historical rows) + index.
- `payment_refunds`: `id uuid PK`, `tenant_id`, `outlet_id`, `payment_id uuid NOT NULL UNIQUE FK payments`, `sales_order_id uuid NOT NULL FK sales_orders`, `amount numeric(19,2) NOT NULL CHECK (amount > 0)`, `method varchar(32) NOT NULL` (copy of the payment method), `reason varchar(500) NOT NULL`, `refunded_by_staff_user_id uuid NOT NULL FK staff_users`, `cash_shift_id uuid NULL FK cash_shifts`, `refunded_at timestamptz NOT NULL`, `idempotency_key varchar(128) NOT NULL`, `request_fingerprint varchar(64) NOT NULL`, `created_at timestamptz NOT NULL`; `UNIQUE (tenant_id, idempotency_key)`. Immutable (`BaseEntity` style, check an existing immutable table for columns).
- `inventory_movements`: drop `ck_inventory_movements_type` and re-add with `'SALE_REVERSAL'` added.
- `audit_logs.action` has no CHECK (verified for TASK-075); nothing to do.

### Entities / repositories — W1
- `entity/fnb/CashShiftEntity` (extends `MutableBaseEntity`): `open(TenantEntity, OutletEntity, TenantMembershipEntity, BigDecimal openingCash)` (openingCash non-null, >= 0, scale 2 else `InvalidOperationException("Modal awal tidak valid.")`), `close(StaffUserEntity closedBy, BigDecimal expectedCash, BigDecimal countedCash, String note)` (only from OPEN else `OperationConflictException("Shift sudah ditutup.")`; difference = counted - expected), `isOpen()`.
- `entity/fnb/PaymentRefundEntity` (`BaseEntity`): static `record(...)` with all fields.
- `PaymentEntity`: field `cashShift` (`@ManyToOne LAZY`, nullable); `settle(...)` gains a trailing `CashShiftEntity cashShift` parameter (update both callers: `FnbPaymentService.settle`, `MockFnbGatewayService.settle` — W1 changes only the call lines, passing `null`; W2 replaces `null` with the real shift).
- `InventoryMovementEntity.Type` adds `SALE_REVERSAL`.
- `SalesOrderEntity.markRefunded()`: requires `status == COMPLETED && paymentStatus == PAID` else `OperationConflictException("Hanya pesanan lunas yang dapat direfund.")`; sets `paymentStatus = REFUNDED` (status stays COMPLETED).
- `AuditLogEntity.Action`: `SHIFT_OPENED("CASH_SHIFT")`, `SHIFT_CLOSED("CASH_SHIFT")`, `FNB_ORDER_REFUNDED("SALES_ORDER")`.
- New `repository/FnbShiftRepository`: `persist`, `lock(CashShiftEntity)` (PESSIMISTIC_WRITE refresh, same style as existing locks), `openFor(UUID tenantId, UUID outletId, UUID membershipId) -> Optional`, `byId(tenantId, outletId, id) -> Optional`, `page(tenantId, outletId, Instant from, Instant to, int page, int size) -> List` (opened_at desc, probe size+1 like `FnbOrderRepository`), `cashSummary(UUID shiftId) -> CashSummary` (public static class with getters: `cashIn` = sum(amount_paid - change_amount) of CASH payments of the shift, `cashRefunds` = sum(amount) of CASH refunds of the shift, `paymentCount`, `totalsByMethod` as `List<MethodTotal>` (method, count, amount = amount_paid - change_amount)).
- New `repository/FnbRefundRepository`: `persist`, `byKey(tenantId, key)`, `byPayment(tenantId, paymentId)`.
- `FnbInventoryRepository.saleMovements(UUID tenantId, UUID outletId, UUID orderId) -> List<InventoryMovementEntity>` (type SALE, ordered by inventory item id).

### Shift use cases — W2
Routes (`/cashier/**` = OWNER + CASHIER, `/merchant/**` = OWNER), new `controller/FnbShiftController`:
- `POST /api/v1/cashier/outlets/{outletId}/fnb/shifts` body `{openingCash}` -> open; 409 "Anda masih memiliki shift terbuka." if one exists (check + unique index). Audit `SHIFT_OPENED` details `openingCash=<x>`.
- `GET /api/v1/cashier/outlets/{outletId}/fnb/shifts/current` -> caller's open shift with live summary, or `ResourceNotFoundException` when none.
- `POST /api/v1/cashier/outlets/{outletId}/fnb/shifts/current/close` body `{countedCash, note?}` -> closes caller's open shift.
- `GET /api/v1/merchant/outlets/{outletId}/fnb/shifts?from=&to=&page=&size=` (dates in outlet zone like the sales report, max 366 days, size <= 50) and `GET /api/v1/merchant/outlets/{outletId}/fnb/shifts/{shiftId}`.
- `POST /api/v1/merchant/outlets/{outletId}/fnb/shifts/{shiftId}/close` body `{countedCash, note?}` -> owner force-closes any open shift of the outlet (closedBy = owner).
- Close: `orders.lockTenant`, lock shift, expected = opening + cashIn - cashRefunds, persist, audit `SHIFT_CLOSED` details `expected=<e> counted=<c> difference=<d>`.
- `FnbShiftResponse`: id, status, member userId + fullName, openingCash, openedAt, closedAt, expectedCash (live value while OPEN), countedCash, cashDifference, closingNote, paymentCount, totalsByMethod (method, count, amount), cashIn, cashRefunds, openOrderCount (OPEN+UNPAID orders of the outlet, at the time of the response). Times rendered as ISO instants like other responses.
- Payment requires a shift: `FnbPaymentService.pay` (CASH / MANUAL_NON_CASH) and `MockFnbGatewayService.resolve` with SUCCEEDED both call ONE new method `FnbShiftService.requireOpenShift(MerchantAccessContext context)` (inside the existing tenant lock) -> `OperationConflictException("Buka shift terlebih dahulu sebelum menerima pembayaran.")` when the caller has no open shift; the shift is passed to `PaymentEntity.settle`. Idempotent replay of an existing payment must still return the stored payment even if the shift is now closed (check replay BEFORE requiring the shift).

### Refund use case — W3
- `POST /api/v1/merchant/outlets/{outletId}/fnb/orders/{orderId}/refund`, header `Idempotency-Key` (same validation as checkout), body `{reason}` (NotBlank, max 500). New `controller/FnbRefundController`, `service/FnbRefundService`.
- Order: `guard.requireOwner`; `orders.lockTenant`; order by id (404); replay by key (same fingerprint SHA-256 of `orderId|reason` -> return stored result; different -> 409); `payments.byOrder` must exist; `order.markRefunded()`; if payment method is CASH -> `FnbShiftService.requireOpenShift(context)` of the owner (message as above), else shift optional (use the owner's open shift if any); record `PaymentRefundEntity` (amount = payment amount_paid - change_amount, method copied); stock: new `FnbStockService.reverseForRefundedOrder(MerchantAccessContext, SalesOrderEntity) -> List<String>` that, for every SALE movement of the order, records one `SALE_REVERSAL` movement with `quantityDelta = -original`, `reversalOfMovementId = original.id`, idempotency key `sale-reversal:{orderId}:{inventoryItemId}`, reason "Refund pesanan <orderNumber>", locking balances in item-id order like `consumeForPaidOrder`, and returns the same warning strings; audit `FNB_ORDER_REFUNDED` with reason and details `amount=<a> method=<m>`.
- Response `FnbRefundResponse`: orderId, orderNumber, paymentStatus, amount, method, reason, refundedAt, refundedBy (fullName), stockWarnings.
- Effects already handled elsewhere: sales report counts only PAID (refunded orders drop out); receipt for a refunded order is M5 (stays 404 for now; report it).

### Tests — W4 (after W1-W3 review)
Existing payment tests must open a shift first (helper in `ApiTestSupport`, e.g. `openShift(token, outletId, "0.00")`). New: shift open/duplicate/close math (cash, non-cash, change, refunds), owner force close, cashier cannot see merchant shift list, payment without shift -> 409, replay after shift closed still returns the payment; refund happy path (stock back, report excludes, audit), refund twice -> replay/409, refund of OPEN order -> 409, cashier -> 403, CASH refund without owner shift -> 409, other tenant -> 404; V7 applies on the Testcontainers DB.

### Docs/Postman — W5 (after review)
Postman folder "16 - F&B Shifts" + refund request; `docs/fnb-operations.md`, `docs/demo.md` (open a shift before paying), README.

## Reserved writes (under `src/main/java/org/lite/project/cashiersaas/` unless absolute)
- W1: V7 migration; `entity/fnb/CashShiftEntity.java` (new), `entity/fnb/PaymentRefundEntity.java` (new), `entity/fnb/PaymentEntity.java`, `entity/fnb/SalesOrderEntity.java`, `entity/shared/inventory/InventoryMovementEntity.java`, `entity/shared/audit/AuditLogEntity.java`, `repository/FnbShiftRepository.java` (new), `repository/FnbRefundRepository.java` (new), `repository/FnbInventoryRepository.java`, and only the `PaymentEntity.settle(...)` call lines in `service/FnbPaymentService.java` and `service/MockFnbGatewayService.java`.
- W2 (after W1): `service/FnbShiftService.java` (new), `controller/FnbShiftController.java` (new), `dto/request/FnbShiftOpenRequest.java`, `dto/request/FnbShiftCloseRequest.java`, `dto/response/FnbShiftResponse.java` (+ nested method-total DTO class file if needed), `dto/FnbShiftMapper.java` (new), `service/FnbPaymentService.java`, `service/MockFnbGatewayService.java`.
- W3 (after W1, parallel with W2): `service/FnbRefundService.java` (new), `controller/FnbRefundController.java` (new), `dto/request/FnbRefundRequest.java`, `dto/response/FnbRefundResponse.java`, `dto/FnbPaymentMapper.java` (refund mapping), `service/FnbStockService.java`. Calls `FnbShiftService.requireOpenShift(MerchantAccessContext) -> CashShiftEntity` (W2 creates it) and may also need `FnbShiftService.findOpenShift(MerchantAccessContext) -> Optional<CashShiftEntity>`; W2 provides both with exactly these signatures.

## Orchestrator gate
Diff limited to reserved paths; `mvnw -B -DskipTests test-compile`; full `mvnw -B verify` only when the user asks.
