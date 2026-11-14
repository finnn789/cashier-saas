# TASK-078 W6 - tests for M5 (record-only payments, receipts, petty cash)

Not compiled or run (policy: no Maven). Everything below was written against the real main code by reading it; the first `test-compile` / `verify` by the orchestrator is the real check. Only `src/test/**` and this report were touched.

## Files

Deleted
- `unit/service/MockFnbGatewayServiceTest.java`
- line `app.payment.mock-gateway.enabled=true` in `src/test/resources/application-test.properties`

Edited (adapt to new behaviour)
- `integration/support/ApiTestSupport.java`: new `firstActiveLabelId(actor)` (GET cashier payment-labels, first row), plus shared helpers `createPosOrder`, `orderTotal`, `pay(actor, orderId, method, amount, labelId, key)` (no status assert), `payCashExact`, `payNonCash(actor, orderId, labelId)`.
- `integration/fnb/FnbInventoryFlowIntegrationTest.java`: mock-gateway test replaced by `manualNonCashPaymentDeductsStockExactlyOnce` (MANUAL_NON_CASH + label, deducts once, replay deducts nothing).
- `integration/fnb/FnbShiftRefundIntegrationTest.java`: mock-gateway shift test replaced by `nonCashPaymentNeedsAnOpenShiftAndAttachesToItOnceOpened`; `paymentJson`/`payCall` now add `labelId` for MANUAL_NON_CASH (via `api.firstActiveLabelId`), so every existing non-cash payment in that class carries a label.
- `unit/entity/SalesOrderEntityTest.java`: `PosDetails` 6 args, `replaceLines` 4 args, new `orderNoteIsStoredByPosAndReplaceLinesAndNullClearsIt`.
- `unit/service/FnbOrderCancellationServiceTest.java`, `FnbPosOrderServiceTest.java`: attempts mock/import/test removed; PosDetails 6 args. `FnbPosOrderServiceTest` + new `orderNoteIsTrimmedStoredAndBlankClearsNoteAndCustomerLabel`.
- `unit/service/FnbRefundServiceTest.java`, `MerchantBusinessSettingsServiceTest.java`: PosDetails 6 args.
- `unit/service/FnbShiftServiceTest.java`: mocks `FnbCashExpenseRepository` + `DigestService` (lenient defaults `totalForShift=0.00`, `forShift=[]`, digest stub), `MethodTotal(method, methodLabel, count, amount)`, cashier `id` set (replay checks recorder id). New: expected cash subtracts expenses (live and at close, stored value), exact `countOpenUnpaid`, recordExpense (persist under lock + audit + trimmed description, no-shift message, replay without shift or new row, same key different body 409, malformed key before any work), currentExpenses (list / 404), openingSuggestion (0.00 + null source / latest closed counted cash).
- `unit/service/MerchantBusinessSettingsServiceTest.java`: footer set + audit before/after JSON, blank clears, unchanged footer = no audit, 201 chars rejected without audit.
- `unit/dto/MerchantBusinessSettingsMapperTest.java`: footer mapped / null.
- `unit/service/FreeTierOnboardingServiceTest.java`: `@Mock FnbPaymentLabelService` added.

New
- `unit/service/FnbPaymentLabelServiceTest.java` (create/limit/duplicate/default order/audit, update partial + self-exclusion + deactivate + 404, list guards, `createDefaults`).
- `unit/service/FnbPaymentServiceTest.java` (cash+label rejected, non-cash without/unknown/inactive label rejected, amount rules, label name snapshot, `INV-yyyyMMdd-NNNN` with 4-digit pad and 5 digits uncapped, outlet-zone date, replay same key+label (incl. `100` vs `100.00`) draws no number, replay with other label/method conflicts, already-paid conflict draws no number).
- `integration/fnb/FnbPaymentLabelIntegrationTest.java` (4 tests + schema test below)
- `integration/fnb/FnbReceiptIntegrationTest.java`
- `integration/fnb/FnbPettyCashOrderIntegrationTest.java`

## Scenarios (integration)

FnbPaymentLabelIntegrationTest
- V8: flyway version 8 success, unique index, 3 new tables, `payments_method_check` has no MOCK_GATEWAY, and `UPDATE payments SET method='MOCK_GATEWAY'` on a real payment is refused with `payments_method_check`; onboarding creates exactly QRIS/Transfer/Debit (orders 1..3, active), no audit rows for the defaults.
- CRUD: owner create (trim, default order count+1, `active` ignored on create), rename, omitted displayOrder/active kept, deactivate; owner list shows all ordered by displayOrder then name, cashier GET shows active only; cashier on merchant routes 403; other tenant 404 on foreign outlet path and foreign label id; blank / 41 chars / negative displayOrder 400; unauthenticated 401; audit rows `PAYMENT_LABEL_CHANGED` with `name=X active=Y` (3 writes, failures not audited).
- Duplicates case-insensitive on POST and PUT (incl. whitespace-padded name), rename to own name in other casing OK, same name free in another tenant; 20 label limit (409, message) incl. deactivated labels counting; messages asserted by body text.
- Payment rules: non-cash without label 400, cash with label 400, unknown label 400, other tenant's label 400, deactivated label 400, `MOCK_GATEWAY` method 400, order stays UNPAID with 0 payments; valid label -> `methodLabel` in response and `payments.method_label`; replay same key/label returns same receipt; different label or method with same key 409; label rename/deactivate afterwards leaves payment, replay and receipt `methodLabel` unchanged (snapshot).
- Report: paymentBreakdown has 3 rows (CASH without label, QRIS count 2, Transfer count 1), transactions carry `methodLabel`, CSV header `...Payment Method,Payment Label,Paid At...`, label cells per row, CASH row has empty label cell.

FnbReceiptIntegrationTest
- Receipt numbers: `INV-yyyyMMdd-0001`, `-0002`, `-0003` per outlet per day; second outlet starts at 0001; replay returns the same number and `receipt_sequences.last_value` does not move; refused payments (already paid 409, short cash 400) consume none; receipt endpoint prints the same number; new business day simulated by moving the sequence row and the existing receipt numbers back one day -> restarts at 0001, previous day's row kept (2 rows, 3 and 1).
- 4 parallel payments (one latch-released burst, same outlet, same shift) -> 4 distinct numbers exactly 0001..0004, `last_value` 4.
- Footer setting: set/trim/read back, shown in receipt JSON and HTML, cashier prints the same, audit JSON before null / after text, same value again = no extra audit row, 200 chars OK, 201 chars 400 and unchanged, blank clears (response and receipt omit the field), omitting the field clears, cashier PUT 403, other tenant 404 and its footer never on the owner's receipt. Receipt JSON carries businessName, customerLabel, orderNote, methodLabel, `refunded=false`, HTML without REFUND.
- HTML escaping of footer, orderNote, customerLabel (`<script>`, `<img>`, `<b>`, `&`, quotes) while JSON fields stay raw; refund reason escaped.
- Refunded order: receipt 200, `refunded=true`, `refundReason`, `refundedAt` (outlet zone), HTML contains REFUND; paymentStatus REFUNDED. Unpaid and cancelled orders 404, foreign tenant 404 (both path combinations), unknown id 404, no token 401.

FnbPettyCashOrderIntegrationTest
- Petty cash: no open shift 409 with the petty-cash message and GET expenses 404; record (trimmed description, 2dp amount, recordedBy full name, linked to the shift); replay same key returns same id, single row and single audit (`amount=15000.00`); same key with other amount / other description / other member 409; validation (0, negative, 3 decimals, missing/blank/201-char description, missing key, short key) 400; live shift `expensesTotal`, `expenses` list, `expectedCash = opening + cashIn - expenses`; owner without own shift gets 404 on current expenses but sees them in shift detail, list rows carry `expensesTotal` and no `expenses`; other tenant's cashier 404, no token 401; close counted = expected gives difference 0 and stored `cash_shifts.expected_cash`; after close replay still returns the stored expense and a new expense is 409. Second test: opening 50000, expense 10000, close counted 40000 -> expected 40000, difference 0.
- Opening suggestion: 0.00 and null source without history, open shift ignored, counted cash of latest closed shift of any member with `sourceShiftId`, latest closed wins, other tenant 0.00 / foreign outlet 404 / 401.
- Order note / customer: both optional, blank -> null (JSON and DB columns), values trimmed, shown on create/detail/list, idempotency replay same body OK and different note 409, size limits (note 500 / customer 80 boundary and +1 -> 400), replace-items sets / trims / clears on blank / omitted customer is cleared, note 501 on replace 400.
- List filters: from/to pair required (400 for one side), `to < from` 400, 365-day span 200, 366-day span 400, malformed date 400; half-open day window in the outlet zone (order at exactly 00:00 today is in today and not yesterday, order at 23:30 yesterday only in yesterday, set through `created_at` UPDATE); exact `orderNumber` (trimmed; partial/unknown matches nothing; blank ignored; combined with dates); other tenant sees none, foreign outlet path 404.

## Assumptions about status codes / behaviour (verify on first run)
- Cashier on `/api/v1/merchant/**` -> 403 from the security chain (not the 404 of `requireOwner`); other-tenant owner on foreign outlet or label id -> 404.
- Invalid enum value `MOCK_GATEWAY` in the payment body -> 400 via `HttpMessageNotReadableException` handler; malformed/missing Idempotency-Key on expenses -> 400.
- Expense POST and label POST return 201; replays also 201 (tests accept 200 or 201); everything else asserted exactly.
- `refunded` is always serialised (primitive boolean); `sourceShiftId` null is serialised in opening-suggestion; empty optional strings are absent (NON_NULL) in receipt, settings and order JSON (tests accept missing or null for order fields, require absence for footer in settings/receipt).
- Replay of a stored payment does not re-validate the label (W3 note), so replay after the label was deactivated still returns the payment.
- Unit `FnbShiftServiceTest` sets the cashier's `id` by reflection because replay compares the recorder's id.

## Not covered / risks
- Real V8 data migration of an existing MOCK_GATEWAY row (UPDATE to MANUAL_NON_CASH + 'Gateway simulasi (lama)') is not tested: it would need running Flyway to V7, seeding, then V8 on a separate schema/container. Replaced as instructed by the CHECK-constraint and seeded-labels assertions; the V8 seed for pre-existing outlets is only indirectly covered (onboarded outlets get labels through `createDefaults`, not through the migration SQL).
- Midnight-straddling runs: receipt date assertions accept the previous and next day prefix for the number but the report/CSV and order filter tests use `LocalDate.now(Asia/Jakarta)` once; a run across 00:00 Jakarta can flake.
- `FreeTierOnboardingService.complete` is not unit-tested for the `createDefaults` call (covered by integration: every onboarded outlet has 3 labels).
- Concurrency of label creation (limit/duplicate race under `lockTenant`) not stress-tested.
- Nothing from W7 (Postman/docs) is checked here; gRPC/Telegram suites were not touched (grep found no M5 impact).
- Existing tests were adapted by reading, not by running; PayFirst / StockConcurrency / ReusableOptionGroup integration suites needed no change (CASH only, customerLabel still sent).

## Fix 1 (after orchestrator `verify`)
`FnbShiftServiceTest.openPersistsTheShiftUnderTheTenantLockAndAuditsIt` threw an NPE: `open` persists a NEW shift whose id is null, so the `@BeforeEach` lenient stubs keyed on `shift.getId()` did not match and `expenses.totalForShift` returned null. The lenient stubs now match any argument (`totalForShift(any())` -> `0.00`, `forShift(any())` -> empty list), which also covers any other test that builds a response for a different or null shift id. Tests that need a specific value still override it (`expectedCashSubtractsPettyCashExpensesLiveAndAtClose`, `currentExpensesListsTheCallersOpenShiftOrIsNotFound`: later stubs win). Test code only; not re-run.

## Fix 2
`salesReportAndCsvBreakDownNonCashPerLabel`: the CSV service quotes every data field (header line is unquoted), so the row assertions now match the quoted form (`"MANUAL_NON_CASH","QRIS",`, `"MANUAL_NON_CASH","Transfer",`, `"CASH","",`). Header assertion unchanged (literal unquoted header). Test code only; not re-run.
