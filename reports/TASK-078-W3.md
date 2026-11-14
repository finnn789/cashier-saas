# TASK-078 W3 — non-cash labels, payment label, sequential receipt number, report per label

Not compiled/tested (policy: never run Maven). Paths under `src/main/java/org/lite/project/cashiersaas/`.

## Files
- NEW `controller/FnbPaymentLabelController`, `service/FnbPaymentLabelService`, `dto/FnbPaymentLabelMapper`, `dto/request/FnbPaymentLabelRequest`, `dto/response/FnbPaymentLabelResponse`
- EDIT `service/FnbPaymentService` (labelId, label resolution, fingerprint, INV- receipt number), `dto/request/FnbPaymentRequest` (`UUID labelId`), `dto/response/FnbPaymentResponse` (`methodLabel`), `dto/FnbPaymentMapper` (methodLabel)
- EDIT `service/FreeTierOnboardingService` (field `paymentLabelService` + one call `createDefaults(tenant, outlet)` right after `outletRepository.persist(outlet)` in `complete`)
- EDIT `repository/FnbSalesReportRepository`, `service/FnbSalesReportService`, `dto/FnbSalesReportMapper`, `dto/response/FnbSalesTransactionResponse`, `dto/response/FnbSalesPaymentBreakdownResponse`

## Routes (all `/api/v1`)
- `GET  /merchant/outlets/{outletId}/fnb/payment-labels` (owner; all labels incl. inactive)
- `POST /merchant/outlets/{outletId}/fnb/payment-labels` body `{name, displayOrder?, active?}` -> 201 (new label is always active; `active` ignored; displayOrder default = count+1)
- `PUT  /merchant/outlets/{outletId}/fnb/payment-labels/{labelId}` body `{name, displayOrder?, active?}` (omitted displayOrder/active keep current)
- `GET  /cashier/outlets/{outletId}/fnb/payment-labels` (OWNER+CASHIER via `requireOutlet`; active only)
- Max 20 labels per outlet (`OperationConflictException` "Jumlah label maksimal 20."), duplicate case-insensitive (409 "Label sudah ada."; also checked on PUT excluding itself). Audit `PAYMENT_LABEL_CHANGED` details `name=<n> active=<b>` on create and update. `orders.lockTenant` serialises concurrent label writes (limit/duplicate race).
- Cashier/other-outlet access to merchant routes -> 404 via `requireOwner` (existing pattern).

## Contract points (file:line)
- `FnbPaymentService.pay`: fingerprint with labelId :49; `methodLabel(...)` resolved after replay + already-paid + amount checks and before `requireOpenShift` :61-62; helper `methodLabel` ~:92 (CASH + labelId -> "Pembayaran tunai tidak memakai label."; non-cash without/with unknown/inactive/other-outlet label -> "Pilih label pembayaran non-tunai."); `fingerprint(method, tendered, labelId)` = SHA-256 of `method|amount|labelId-or-empty` (signature changed, package-private static).
- Replay by idempotency key unchanged (fingerprint now includes labelId; replay does not re-validate the label, so a later-deactivated label still replays). Receipt number is only drawn in `settle`, so replays and rolled-back payments never consume a sequence value.
- `receiptNumber(OutletEntity)`: `INV-yyyyMMdd-%04d`, date = `LocalDate.now(outlet zone)`, number = `payments.nextReceiptSequence(outletId, date)`.
- Report: `FnbSalesReportRepository.TransactionRow` constructor gained `String methodLabel` after `method`; `MethodRow(PaymentMethod method, String methodLabel, Long count, ...)`; methods() groups/orders by method then methodLabel. `FnbSalesPaymentBreakdownResponse.methodLabel`, `FnbSalesTransactionResponse.methodLabel` (JSON omitted when null). CSV header now `...,Payment Method,Payment Label,Paid At,...` (CASH rows have an empty label cell).

## Deviations
- CSV column name is `Payment Label` (spec said "a `label` column"), placed right after `Payment Method`.
- Transaction rows also expose `methodLabel` (needed for the CSV anyway).
- `createDefaults` is not `@Transactional` itself (runs inside onboarding's transaction); it writes no audit rows.
- Not done by design: labels for outlets created after the first one (no code creates additional outlets today; V8 seeds only existing outlets).

## Tests that break / to write (W6)
- `FnbPaymentService.fingerprint(...)` 3-arg; any test building `FnbPaymentRequest` with MANUAL_NON_CASH now needs `labelId` (seed labels exist for outlets created via onboarding `complete`; tests creating outlets directly must create a label).
- Unit tests constructing `FnbPaymentService` / `FreeTierOnboardingService` need the new `FnbPaymentLabelRepository` / `FnbPaymentLabelService` mocks (@RequiredArgsConstructor order: FnbPaymentService adds `labels` after `payments`; onboarding adds `paymentLabelService` last).
- Receipt number assertions `R-...` -> `INV-yyyyMMdd-0001` pattern, sequential per outlet/day, concurrent-safe.
- Report/CSV tests: CSV header changed (extra column), breakdown rows now split per label.
- New: label CRUD/limit(20)/duplicate/role (cashier 404 on merchant routes, cashier list active only), CASH+label rejected, non-cash without/inactive/foreign label rejected, replay with same/different labelId, audit rows.
