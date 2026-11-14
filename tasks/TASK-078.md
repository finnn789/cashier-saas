# TASK-078: M5 — record-only payments and receipts (PRD v2.1 §8.3, §8.7, §8.8, §8.9, §8.11, §8.12, AC-06, AC-10)

Status: DELEGATED (2026-11-13). Memory version: 74. Branch `fix/flow-controls-a`; baseline = the verified, uncommitted TASK-076 + TASK-077 tree (patch saved by the orchestrator before wave 1).

Global rules: identical to `tasks/TASK-076.md` "Global rules" 1-7 (reports `reports/TASK-078-W<n>.md`). Workers never run Maven. Never edit V1..V7. Paths below are under `src/main/java/org/lite/project/cashiersaas/` unless absolute.

## Wave 1 (parallel)

### W1 — schema, entities, repositories
`src/main/resources/db/migration/V8__record_only_payments_and_receipts.sql`:
- `payment_labels`: `id uuid PK`, `created_at`, `updated_at`, `version` (MutableBaseEntity columns as in V7 `cash_shifts`), `tenant_id` FK, `outlet_id` FK, `name varchar(40) NOT NULL`, `active boolean NOT NULL DEFAULT true`, `display_order integer NOT NULL DEFAULT 0`; unique index `uk_payment_labels_outlet_name` on `(outlet_id, lower(name))`. Seed for EVERY existing outlet: `QRIS` (1), `Transfer` (2), `Debit` (3) with `gen_random_uuid()`, `now()`, version 0.
- `payments`: `ADD COLUMN method_label varchar(40)`; `UPDATE payments SET method='MANUAL_NON_CASH', method_label='Gateway simulasi (lama)' WHERE method='MOCK_GATEWAY'`; `UPDATE payment_refunds SET method='MANUAL_NON_CASH' WHERE method='MOCK_GATEWAY'`; `UPDATE payments SET method_label='Non-tunai' WHERE method='MANUAL_NON_CASH' AND method_label IS NULL`; drop and re-add `payments_method_check` with `('CASH','MANUAL_NON_CASH')`.
- `cash_expenses` (immutable, BaseEntity columns): `id`, `created_at`, `tenant_id`, `outlet_id`, `cash_shift_id uuid NOT NULL FK cash_shifts`, `amount numeric(19,2) NOT NULL CHECK (amount > 0)`, `description varchar(200) NOT NULL`, `recorded_by_staff_user_id uuid NOT NULL FK staff_users`, `recorded_at timestamptz NOT NULL`, `idempotency_key varchar(128) NOT NULL`, `request_fingerprint varchar(64) NOT NULL`; `UNIQUE (tenant_id, idempotency_key)`; index on `cash_shift_id`.
- `receipt_sequences`: `outlet_id uuid NOT NULL FK outlets`, `business_date date NOT NULL`, `last_value integer NOT NULL`, `PRIMARY KEY (outlet_id, business_date)`. Not a JPA entity.
- `tenants`: `ADD COLUMN receipt_footer varchar(200)`. `sales_orders`: `ADD COLUMN order_note varchar(500)`.
Java:
- `entity/fnb/PaymentLabelEntity` (MutableBaseEntity): `create(TenantEntity, OutletEntity, String name, int displayOrder)`, `update(String name, int displayOrder, boolean active)`, getters. Name trimmed, 1..40 else `InvalidOperationException("Nama label tidak valid.")`.
- `entity/fnb/CashExpenseEntity` (BaseEntity): `record(TenantEntity, OutletEntity, CashShiftEntity, BigDecimal amount, String description, StaffUserEntity recordedBy, String idempotencyKey, String fingerprint)`; amount > 0 scale <= 2, description trimmed 1..200, `recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)`.
- `PaymentEntity`: field `methodLabel` (column `method_label`); REMOVE the `reference` field mapping; new exact signature `settle(TenantEntity, OutletEntity, SalesOrderEntity, PaymentMethod, BigDecimal amountPaid, BigDecimal changeAmount, String receiptNumber, String idempotencyKey, String fingerprint, String methodLabel, StaffUserEntity receivedBy, CashShiftEntity cashShift)`. Do NOT edit its callers (W2 does).
- `TenantEntity`: field `receiptFooter` + `changeReceiptFooter(String footer)` (blank -> null, max 200).
- `AuditLogEntity.Action`: `PAYMENT_LABEL_CHANGED("PAYMENT_LABEL")`, `CASH_EXPENSE_RECORDED("CASH_EXPENSE")`. Keep the old gateway constants (historical audit rows).
- New `repository/FnbPaymentLabelRepository`: `persist`, `byId(tenantId, outletId, id)`, `list(tenantId, outletId, boolean activeOnly)` ordered by display_order then name, `count(tenantId, outletId)`, `existsName(tenantId, outletId, String name, UUID excludeId)` (case-insensitive).
- New `repository/FnbCashExpenseRepository`: `persist`, `byKey(tenantId, key)`, `forShift(UUID shiftId)` (recorded_at asc), `totalForShift(UUID shiftId) -> BigDecimal` (0.00 when none).
- `FnbPaymentRepository.nextReceiptSequence(UUID outletId, LocalDate businessDate) -> int` via native `INSERT ... ON CONFLICT (outlet_id, business_date) DO UPDATE SET last_value = receipt_sequences.last_value + 1 RETURNING last_value`.
Reserved: the V8 file, the entities/repositories named above.

### W2 — delete the mock gateway
Delete: `service/MockFnbGatewayService.java`, `entity/fnb/FnbGatewayAttemptEntity.java`, `repository/FnbGatewayAttemptRepository.java`, `enums/FnbGatewayOutcome.java`, `dto/request/FnbMockPaymentResolutionRequest.java`. Remove `PaymentMethod.MOCK_GATEWAY`. Remove the resolve route from `controller/FnbPaymentController.java`. In `service/FnbPaymentService.java` remove the mock delegation and adapt the `PaymentEntity.settle(...)` call to W1's new signature, passing a local `String methodLabel = null` (W3 fills it). Remove every pending-attempt check and the attempts dependency from `FnbPosOrderService`, `FnbOrderCancellationService`, `FnbDiscountService`. `dto/response/FnbPaymentResponse.java`: drop `provider`, `attemptId`, `gatewayOutcome`, `providerReference`. `dto/FnbPaymentMapper.java` and `dto/FnbReceiptMapper.java`: drop mock mapping/labels. Remove `app.payment.mock-gateway.enabled` from `application.properties`, `application-demo.properties`, `.env.example`, `docker-compose.yml`. Grep `src/main` for any leftover reference and list it.
Reserved: exactly the files above.

## Wave 2 (parallel, after wave 1 review + compile)

### W3 — non-cash labels, payment label, sequential receipt number, report per label
- Owner routes (new `controller/FnbPaymentLabelController`, `service/FnbPaymentLabelService`, DTOs in `dto/request`/`dto/response`, `dto/FnbPaymentLabelMapper`): `GET/POST /api/v1/merchant/outlets/{outletId}/fnb/payment-labels`, `PUT .../payment-labels/{labelId}` body `{name, displayOrder, active}`. Cashier: `GET /api/v1/cashier/outlets/{outletId}/fnb/payment-labels` (active only). Max 20 labels per outlet (409), duplicate name case-insensitive (409 "Label sudah ada."). Audit `PAYMENT_LABEL_CHANGED` details `name=<n> active=<b>`. No delete (deactivate instead).
- `FnbPaymentLabelService.createDefaults(TenantEntity, OutletEntity)` (QRIS/Transfer/Debit) called from `FreeTierOnboardingService` right after the first outlet is created (one call line only).
- Payment: `FnbPaymentRequest.labelId` (UUID, optional). MANUAL_NON_CASH requires an active label of the outlet (`InvalidOperationException("Pilih label pembayaran non-tunai.")`); CASH with a labelId -> `InvalidOperationException("Pembayaran tunai tidak memakai label.")`. Label NAME is passed as `methodLabel` to `PaymentEntity.settle`. Fingerprint = SHA-256 of `method|amount|labelId-or-empty`. `FnbPaymentResponse.methodLabel`.
- Receipt number: `INV-` + business date `yyyyMMdd` in the outlet zone (now) + `-` + 4-digit zero-padded `FnbPaymentRepository.nextReceiptSequence(outletId, date)` (5+ digits allowed past 9999). Replace the random `receiptNumber()`.
- Sales report: payment-method breakdown grouped by method AND method_label (`methodLabel` field on the row DTO); CSV gains a `label` column; nothing else changes.
Reserved: the new label files, `service/FnbPaymentService.java`, `dto/request/FnbPaymentRequest.java`, `dto/response/FnbPaymentResponse.java`, `dto/FnbPaymentMapper.java`, `service/FreeTierOnboardingService.java` (one line), `repository/FnbSalesReportRepository.java`, `service/FnbSalesReportService.java`, `dto/FnbSalesReportMapper.java`, the sales report response DTO(s).

### W4 — petty cash, opening suggestion, order note, optional customer, order filters
- `POST /api/v1/cashier/outlets/{outletId}/fnb/shifts/current/expenses`, header `Idempotency-Key` (validate with `FnbPosOrderService.validatedKey`), body `{amount, description}`: `orders.lockTenant`, caller's open shift required (`FnbShiftService.requireOpenShift`, message "Buka shift terlebih dahulu sebelum mencatat pengeluaran." — add a message parameter or a second method, no copy of the lookup), replay by key (fingerprint `amount|description`), audit `CASH_EXPENSE_RECORDED` details `amount=<a>`. `GET .../shifts/current/expenses`.
- Shift response: `expensesTotal`, and for detail/current also `expenses` (amount, description, recordedAt, recordedBy fullName); `totalsByMethod` grouped by method + label (`methodLabel`); expected = opening + cashIn − cashRefunds − expensesTotal (everywhere it is computed, incl. close).
- `GET /api/v1/cashier/outlets/{outletId}/fnb/shifts/opening-suggestion` -> `{openingCash, sourceShiftId}`: counted cash of the latest CLOSED shift of the outlet (any member), else `0.00` and null.
- Exact `openOrderCount`: new `FnbOrderRepository.countOpenUnpaid(tenantId, outletId)`; remove the `shortcut:` cap.
- Orders: `FnbPosOrderRequest.customerLabel` optional (max 80, blank -> null), new optional `orderNote` (max 500, blank -> null); `SalesOrderEntity` field `orderNote` (column `order_note`), set in `pos(...)`/`PosDetails` and `replaceLines(...)`; order responses include `orderNote`.
- `GET .../fnb/orders` gains optional `from`, `to` (LocalDate, outlet zone, both or none, max 366 days) and `orderNumber` (exact, trimmed).
Reserved: `service/FnbShiftService.java`, `controller/FnbShiftController.java`, `repository/FnbShiftRepository.java`, `dto/FnbShiftMapper.java`, shift + expense DTOs (new expense request/response classes allowed), `service/FnbPosOrderService.java`, `dto/request/FnbPosOrderRequest.java`, `entity/fnb/SalesOrderEntity.java`, `controller/FnbStaffOrderController.java`, `service/FnbStaffOrderService.java`, `repository/FnbOrderRepository.java`, `dto/FnbStaffOrderMapper.java`, order response DTO(s).

### W5 — full receipt and receipt footer setting
- Receipt allowed for `PAID` and `REFUNDED` (never CANCELLED). Response adds `businessName`, `logoUrl`, `receiptFooter`, `methodLabel`, `customerLabel`, `orderNote`, `refunded` (boolean), `refundedAt`, `refundReason`; HTML shows a clear "REFUND" marker and the footer. Uses `FnbRefundRepository.byPayment`.
- Business settings request/response gain optional `receiptFooter` (max 200), applied with `TenantEntity.changeReceiptFooter`, included in the existing `BUSINESS_SETTINGS_CHANGED` audit diff style.
Reserved: `service/FnbReceiptService.java`, `dto/FnbReceiptMapper.java`, receipt response DTO(s), `service/MerchantBusinessSettingsService.java`, `dto/MerchantBusinessSettingsMapper.java`, settings request/response DTOs.

## Wave 3 (parallel, after wave 2 review + compile)
- **W6 tests** (`src/test/**`): remove mock-gateway tests and properties; adapt all payments (MANUAL_NON_CASH needs a labelId; seed labels exist for onboarded outlets via `createDefaults`); new unit + integration coverage for every W3-W5 rule incl. V8 data migration (MOCK_GATEWAY rows mapped), receipt numbers sequential per outlet per day and concurrent-safe, label CRUD/limits/duplicates/roles, petty cash math in close + replay, opening suggestion, order note/optional customer, list filters, receipt for refunded order with marker, footer setting.
- **W7 docs/Postman/UI prompt**: Postman (labels, expenses, opening suggestion, payment body with labelId, remove mock requests if any), `docs/**` (not contracts), README; new `prompts/m5-ui-cashier.md` for the external frontend agent: label picker on non-cash payment, quick cash buttons, favourites/search, optional print, shift open with suggestion, petty cash form, receipt with footer/REFUND, order filters, shift summary as daily report (no code).

## Orchestrator gate
Review each wave's diff against reserved paths; `mvnw -B -DskipTests compile` after waves 1 and 2, `test-compile` after wave 3; `mvnw -B verify` (the user asked for it after implementation: "Ya, setelah 077" applied to M4; for M5 the orchestrator asks again unless the user already said so).
