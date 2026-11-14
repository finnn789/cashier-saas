# TASK-077 W1 report (schema V7, entities, repositories)

Nothing compiled or run (no Maven by rule). Base package `org.lite.project.cashiersaas`.

## Files
- NEW `src/main/resources/db/migration/V7__cash_shifts_and_refunds.sql`
- NEW `entity/fnb/CashShiftEntity.java`, `entity/fnb/PaymentRefundEntity.java`
- NEW `repository/FnbShiftRepository.java`, `repository/FnbRefundRepository.java`
- EDIT `entity/fnb/PaymentEntity.java`, `entity/fnb/SalesOrderEntity.java`, `entity/shared/inventory/InventoryMovementEntity.java`, `entity/shared/audit/AuditLogEntity.java`, `repository/FnbInventoryRepository.java`
- EDIT call lines only: `service/FnbPaymentService.java:72`, `service/MockFnbGatewayService.java:107` (trailing `, null`; W2 replaces with the real shift)

## Contract points
- V7: `cash_shifts` (cols/checks/FKs, partial unique `uk_cash_shifts_open_per_member`, index `idx_cash_shifts_outlet_opened`), `payments.cash_shift_id` + FK `fk_payments_cash_shift` + partial index, `payment_refunds` (UNIQUE payment_id, UNIQUE tenant+idempotency, amount > 0), `ck_inventory_movements_type` dropped/re-added with SALE_REVERSAL (V7 lines 62-65 region, end of file). Mutable columns copied from V1 (`created_at`, `updated_at`, `version bigint NOT NULL`, `timestamp(6) with time zone`). payment_refunds is BaseEntity style (created_at only, no version/updated_at).
- `CashShiftEntity` (extends MutableBaseEntity), `PaymentRefundEntity` (extends BaseEntity).
- `PaymentEntity.cashShift` (`@ManyToOne LAZY`, nullable) line ~51; `settle(...)` trailing `CashShiftEntity cashShift` line 57-59.
- `InventoryMovementEntity.Type.SALE_REVERSAL` line 16 (column length 16, fits).
- `SalesOrderEntity.markRefunded()` line 123.
- `AuditLogEntity.Action` SHIFT_OPENED / SHIFT_CLOSED ("CASH_SHIFT"), FNB_ORDER_REFUNDED ("SALES_ORDER") lines 61-63.
- `FnbInventoryRepository.saleMovements` line 69.

## Public signatures
```java
// CashShiftEntity
public enum Status { OPEN, CLOSED }
public static CashShiftEntity open(TenantEntity tenant, OutletEntity outlet, TenantMembershipEntity membership, BigDecimal openingCash)
public void close(StaffUserEntity closedBy, BigDecimal expectedCash, BigDecimal countedCash, String note)
public boolean isOpen()
// getters (Lombok): getTenant, getOutlet, getMembership, getOpenedBy, getStatus, getOpeningCash, getOpenedAt,
//   getClosedBy, getClosedAt, getExpectedCash, getCountedCash, getCashDifference, getClosingNote, getId, getCreatedAt, getUpdatedAt, getVersion

// PaymentRefundEntity
public static PaymentRefundEntity record(TenantEntity tenant, OutletEntity outlet, PaymentEntity payment,
        SalesOrderEntity order, BigDecimal amount, String reason, StaffUserEntity refundedBy,
        CashShiftEntity cashShift /* nullable */, String idempotencyKey, String fingerprint)
// method copied from payment.getMethod(); refundedAt = now.
// getters: getTenant, getOutlet, getPayment, getSalesOrder, getAmount, getMethod (PaymentMethod), getReason,
//   getRefundedBy, getCashShift, getRefundedAt, getIdempotencyKey, getRequestFingerprint, getId, getCreatedAt

// PaymentEntity
public static PaymentEntity settle(TenantEntity, OutletEntity, SalesOrderEntity, PaymentMethod method, BigDecimal amountPaid,
        BigDecimal changeAmount, String receiptNumber, String idempotencyKey, String fingerprint, String reference,
        StaffUserEntity receivedBy, CashShiftEntity cashShift)
// + getCashShift()

// SalesOrderEntity
public void markRefunded()   // OperationConflictException("Hanya pesanan lunas yang dapat direfund.") unless COMPLETED && PAID

// FnbShiftRepository (@Repository)
public void persist(CashShiftEntity shift)
public void lock(CashShiftEntity shift)                                   // refresh PESSIMISTIC_WRITE
public Optional<CashShiftEntity> openFor(UUID tenantId, UUID outletId, UUID membershipId)
public Optional<CashShiftEntity> byId(UUID tenantId, UUID outletId, UUID id)
public List<CashShiftEntity> page(UUID tenantId, UUID outletId, Instant from, Instant to, int page, int size)
        // openedAt >= from AND < to; opened_at desc, id desc; returns up to size+1 rows (probe)
public CashSummary cashSummary(UUID shiftId)
public static class MethodTotal { PaymentMethod getMethod(); long getCount(); BigDecimal getAmount(); }   // amount = amount_paid - change_amount
public static class CashSummary { BigDecimal getCashIn(); BigDecimal getCashRefunds(); long getPaymentCount(); List<MethodTotal> getTotalsByMethod(); }
        // both classes: @Getter @RequiredArgsConstructor (all-args public ctor); totals ordered by method; paymentCount = sum of method counts

// FnbRefundRepository (@Repository)
public Optional<PaymentRefundEntity> byKey(UUID tenantId, String key)
public Optional<PaymentRefundEntity> byPayment(UUID tenantId, UUID paymentId)
public void persist(PaymentRefundEntity refund)

// FnbInventoryRepository
public List<InventoryMovementEntity> saleMovements(UUID tenant, UUID outlet, UUID orderId)   // type SALE, ordered by inventory item id asc
```

## Deviations / notes for W2-W4
- `CashShiftEntity.open`: contract says "scale 2"; I accept any scale <= 2 (non-negative) and normalize to scale 2 (so "100000" works). Scale > 2 or negative or null -> `InvalidOperationException("Modal awal tidak valid.")`.
- `CashShiftEntity.close`: also validates `countedCash` (non-null, >= 0, scale <= 2) -> `InvalidOperationException("Uang tunai hitung tidak valid.")`, after the OPEN check (`OperationConflictException("Shift sudah ditutup.")`). `expectedCash` is rounded HALF_UP to scale 2 and must be non-null (caller computes opening + cashIn - cashRefunds). Blank note stored as null, otherwise trimmed (length 500 enforced by DTO).
- `openedBy` is derived as `membership.getUser()` (no extra parameter).
- Aggregates in `cashSummary` use plain JPQL via `EntityManager` (two queries); no wildcard generics. `cashRefunds` counts only CASH refunds whose `cash_shift_id` is that shift (refunds recorded with a null shift are not counted anywhere).
- Refund unique index on `payment_id` means one refund per payment; W3 must check `byPayment` / catch the replay case itself.
- No `MenuEntity`/Q-class hand edits; Q-classes are generated at build (QCashShiftEntity, QPaymentRefundEntity appear after annotation processing; the new repos use string paths and do not need them).
- Working tree already contains unrelated uncommitted TASK-076 changes (e.g. StaffUserEntity, TenantMembershipEntity); untouched by W1.

## Follow-up (orchestrator request)
1. `InventoryMovementEntity.record(...)` overload with trailing `UUID reversalOfMovementId` (`entity/shared/inventory/InventoryMovementEntity.java:52-54`, assigns the field at line 60); the original 10-arg `record` (line 45) delegates with `null` (line 48-49). No setter.
2. `FnbStockService.reverseForRefundedOrder`: TODO removed; the SALE_REVERSAL movement now passes `sale.getId()` (`service/FnbStockService.java:97-99`). The SALE path in `consumeForPaidOrder` (line 70-72) is unchanged.
3. Idempotency-Key validation: `FnbPosOrderService.validatedKey` is now `static String validatedKey(String key)` (package-private, same name/message; `service/FnbPosOrderService.java:147`). The copy in `FnbRefundService` is deleted and the call at `service/FnbRefundService.java:45` is `FnbPosOrderService.validatedKey(key)`. `InvalidOperationException` import in FnbRefundService is still used (line 47). `FreeTierOnboardingService.validatedKey` is an unrelated private method, left alone.
Not compiled (no Maven).

## Amendment A1
Reason: PostgreSQL `timestamptz` keeps microseconds, so a response built from a fresh entity (`Instant.now()`, sub-microsecond on Windows) differed from an idempotent replay read back from the DB (FnbShiftRefundIntegrationTest line 548). Fix: truncate to micros at assignment so the in-memory value equals the stored value. Added `import java.time.temporal.ChronoUnit;` (after `import java.time.Instant;`) in each file. No comments added. Not compiled (no Maven).

Lines changed (all in `src/main/java/org/lite/project/cashiersaas/entity/fnb/`), each now `Instant.now().truncatedTo(ChronoUnit.MICROS)`:
- `PaymentRefundEntity.java:79` `refund.refundedAt = ...`
- `PaymentEntity.java:75` `payment.paidAt = ...`
- `CashShiftEntity.java:76` `shift.openedAt = ...`
- `CashShiftEntity.java:89` `this.closedAt = ...`
- `FnbGatewayAttemptEntity.java:85` `resolvedAt = ...`
