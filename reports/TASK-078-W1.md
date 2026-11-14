# TASK-078 W1 — schema, entities, repositories

Not compiled/tested (policy: never run Maven). Paths under `src/main/java/org/lite/project/cashiersaas/` unless absolute.

## Files
- NEW `src/main/resources/db/migration/V8__record_only_payments_and_receipts.sql` (payment_labels + seed QRIS/Transfer/Debit per outlet, payments.method_label + MOCK_GATEWAY mapping + new check, cash_expenses, receipt_sequences, tenants.receipt_footer, sales_orders.order_note)
- NEW `entity/fnb/PaymentLabelEntity.java`, `entity/fnb/CashExpenseEntity.java`
- NEW `repository/FnbPaymentLabelRepository.java`, `repository/FnbCashExpenseRepository.java`
- EDIT `entity/fnb/PaymentEntity.java` (methodLabel replaces reference; settle signature)
- EDIT `entity/shared/merchant/TenantEntity.java` (receiptFooter, changeReceiptFooter)
- EDIT `entity/shared/audit/AuditLogEntity.java` (two Action constants; gateway constants kept)
- EDIT `repository/FnbPaymentRepository.java` (nextReceiptSequence)

## Contract points (file:line)
- V8 labels/index/seed: V8 lines 3-24; payments changes lines 26-32; cash_expenses 34-53; receipt_sequences 55-62; tenants/sales_orders columns 64-65.
- PaymentLabelEntity.create/update: entity/fnb/PaymentLabelEntity.java:34 / :43 (name trimmed 1..40, else `InvalidOperationException("Nama label tidak valid.")`).
- CashExpenseEntity.record: entity/fnb/CashExpenseEntity.java:53 (amount > 0, scale <= 2, description trimmed 1..200, recordedAt now micros).
- PaymentEntity.methodLabel field :47-48, `reference` mapping removed, settle :58 (callers must be updated by W2).
- TenantEntity.receiptFooter :56-57, changeReceiptFooter :109.
- AuditLogEntity.Action PAYMENT_LABEL_CHANGED / CASH_EXPENSE_RECORDED :64-65.
- Repositories: see signatures below; FnbPaymentRepository.nextReceiptSequence :36.

## Public signatures
```java
// PaymentLabelEntity extends MutableBaseEntity  (@Getter: getTenant, getOutlet, getName, isActive, getDisplayOrder, getId, ...)
static PaymentLabelEntity create(TenantEntity tenant, OutletEntity outlet, String name, int displayOrder)
void update(String name, int displayOrder, boolean active)

// CashExpenseEntity extends BaseEntity (@Getter: getCashShift, getAmount, getDescription, getRecordedBy, getRecordedAt, getIdempotencyKey, getRequestFingerprint, getId, getCreatedAt, ...)
static CashExpenseEntity record(TenantEntity tenant, OutletEntity outlet, CashShiftEntity cashShift, BigDecimal amount,
        String description, StaffUserEntity recordedBy, String idempotencyKey, String fingerprint)

// PaymentEntity (new getter getMethodLabel(); getReference() REMOVED)
static PaymentEntity settle(TenantEntity tenant, OutletEntity outlet, SalesOrderEntity order, PaymentMethod method,
        BigDecimal amountPaid, BigDecimal changeAmount, String receiptNumber, String idempotencyKey, String fingerprint,
        String methodLabel, StaffUserEntity receivedBy, CashShiftEntity cashShift)

// TenantEntity (getReceiptFooter())
boolean changeReceiptFooter(String footer)   // blank -> null, trimmed, >200 -> InvalidOperationException("Footer struk maksimal 200 karakter."); returns true when changed

// FnbPaymentLabelRepository
void persist(PaymentLabelEntity label)
Optional<PaymentLabelEntity> byId(UUID tenantId, UUID outletId, UUID id)
List<PaymentLabelEntity> list(UUID tenantId, UUID outletId, boolean activeOnly)   // display_order, then name
long count(UUID tenantId, UUID outletId)
boolean existsName(UUID tenantId, UUID outletId, String name, UUID excludeId)     // case-insensitive, excludeId nullable

// FnbCashExpenseRepository
void persist(CashExpenseEntity expense)
Optional<CashExpenseEntity> byKey(UUID tenantId, String key)
List<CashExpenseEntity> forShift(UUID shiftId)       // recorded_at asc
BigDecimal totalForShift(UUID shiftId)               // 0.00 when none

// FnbPaymentRepository
int nextReceiptSequence(UUID outletId, LocalDate businessDate)   // native INSERT ... ON CONFLICT ... RETURNING last_value
```

## Notes / deviations
- `changeReceiptFooter` returns `boolean` (spec said unspecified return) so W5 can build the audit diff.
- `payments.reference` DB column (V1) is left in place, nullable and now unmapped; validate is unaffected.
- `FnbReceiptMapper.java:46` still calls `payment.getReference()` and the old `settle(...)` caller exists in `FnbPaymentService`; both are W2/W5 scope and will not compile until they are done.
- Gateway audit constants and the `gateway attempt` table are untouched.
- V8 seeds labels for every existing outlet only; new outlets get defaults via W3 `createDefaults`.
