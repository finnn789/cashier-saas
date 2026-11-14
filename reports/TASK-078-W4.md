# TASK-078 W4 — petty cash, opening suggestion, order note, optional customer, order filters

Not compiled/tested (policy: no Maven). Paths under `src/main/java/org/lite/project/cashiersaas/`.

## Files
- EDIT `service/FnbShiftService.java`, `controller/FnbShiftController.java`, `repository/FnbShiftRepository.java`, `dto/FnbShiftMapper.java`
- EDIT `dto/response/FnbShiftResponse.java` (+`expensesTotal`, `expenses`), `dto/response/FnbShiftMethodTotalResponse.java` (+`methodLabel`)
- NEW `dto/request/FnbCashExpenseRequest.java`, `dto/response/FnbCashExpenseResponse.java`, `dto/response/FnbShiftOpeningSuggestionResponse.java`
- EDIT `service/FnbPosOrderService.java`, `dto/request/FnbPosOrderRequest.java`, `entity/fnb/SalesOrderEntity.java`
- EDIT `controller/FnbStaffOrderController.java`, `service/FnbStaffOrderService.java`, `repository/FnbOrderRepository.java`, `dto/FnbStaffOrderMapper.java`, `dto/response/FnbStaffOrderResponse.java`

## Routes
| Method + path | Role | Notes |
|---|---|---|
| POST `/api/v1/cashier/outlets/{outletId}/fnb/shifts/current/expenses` | OWNER+CASHIER | header `Idempotency-Key`, body `{amount (0.01.., 2 dec), description (1..200)}`; 201 `FnbCashExpenseResponse {id, amount, description, recordedAt, recordedBy}` |
| GET `.../fnb/shifts/current/expenses` | OWNER+CASHIER | caller's open shift expenses (recorded_at asc); 404 when no open shift |
| GET `.../fnb/shifts/opening-suggestion` | OWNER+CASHIER | `{openingCash, sourceShiftId}`; counted cash of the latest CLOSED shift of the outlet (any member), else `0.00` / `null` (nulls are serialised) |
| GET `.../fnb/orders?from=&to=&orderNumber=` | OWNER+CASHIER | new optional params (see below) |

## Contract points (file:line)
- `FnbShiftService.recordExpense` :178 — validatedKey -> `orders.lockTenant` -> guard -> replay by key (fingerprint = SHA-256 via `DigestService` of `amount(2dp)|trimmed description`; mismatch, other outlet or other recorder -> 409 "Idempotency-Key telah digunakan untuk pengeluaran berbeda.") -> `requireOpenShift(context, "Buka shift terlebih dahulu sebelum mencatat pengeluaran.")` (:193) -> persist -> audit `CASH_EXPENSE_RECORDED` entityId = expense id, details `amount=<a>`. Replay is answered before the shift requirement (works after the shift closed).
- `FnbShiftService.requireOpenShift(context, message)` :60 (new overload; the 1-arg one :56 delegates, used by W3 payments/refund unchanged).
- Expected cash = opening + cashIn − cashRefunds − expensesTotal: `FnbShiftService.expected` :163, used live in `response` :155 and in `close` :145 (stored value once closed).
- Shift response: `expensesTotal` always; `expenses` list on open/current/detail/close, omitted (null, NON_NULL) on owner list rows. `totalsByMethod[].methodLabel` (group by method + label; `FnbShiftRepository.cashSummary` :63; `MethodTotal` has new 2nd component `methodLabel`; `cashIn` now sums all CASH rows).
- `FnbShiftService.openingSuggestion` :168, `FnbShiftRepository.latestClosed` :38 (closedAt desc, id desc).
- `FnbOrderRepository.countOpenUnpaid` :54 (exact); `shortcut:` cap and `OPEN_ORDER_PROBE` removed; all openOrderCount call sites use it.
- Orders: `SalesOrderEntity.orderNote` :51, `PosDetails` gained trailing `orderNote` :75, `pos` :89, `replaceLines(serviceType, customerLabel, orderNote, charges)` :107. `FnbPosOrderRequest`: `customerLabel` now optional (@Size 80, no @NotBlank), `orderNote` @Size 500. `FnbPosOrderService.blankToNull` :145 (customerLabel and orderNote, blank -> null, trimmed); fingerprint now also appends `orderNote` (:159). `FnbStaffOrderResponse.orderNote` + mapper.
- Order list filters: `FnbStaffOrderService.list(..., LocalDate from, LocalDate to, String orderNumber)`: exactly one of from/to -> 400 "Filter tanggal harus menyertakan from dan to."; to < from or >= 366 days -> 400 "Rentang tanggal pesanan tidak valid."; range = createdAt in [from 00:00, to+1 00:00) in the outlet zone; `orderNumber` trimmed, blank ignored, exact match. `FnbOrderRepository.page(..., Instant from, Instant to, String orderNumber)` (:64, old overload removed).

## Deviations / notes
- Expense routes live in `FnbShiftService`/`FnbShiftController` (no new service), `DigestService` and `FnbCashExpenseRepository` injected there.
- Opening-suggestion returns the stored `countedCash` of the latest closed shift; no balance carry-over beyond that.
- The outlet-zone range logic is now in `FnbShiftService.list`, `FnbSalesReportService` and `FnbStaffOrderService` (three similar blocks; not extracted because the reserved scope did not cover a shared helper).
- The idempotency fingerprint of a POS order now has an extra field, so replaying a key created before this change would answer 409 (acceptable; keys are single-use per checkout).
- Expense amount is not capped against the expected cash (expected may go negative).
- `FnbShiftPageResponse` unchanged; DB column `sales_orders.order_note` comes from W1's V8.

## Tests that will break (for W6)
- `FnbShiftServiceTest` (unit): constructor now needs mocks `FnbCashExpenseRepository` and `DigestService`; `FnbShiftRepository.MethodTotal` ctor is now `(method, methodLabel, count, amount)`; open-order count is `orders.countOpenUnpaid(...)` instead of `orders.page(...)`; stub `expenses.totalForShift(...)` (a Mockito mock returns null -> NPE in `subtract`) and `expenses.forShift(...)`; expected-cash assertions must subtract expenses.
- `SalesOrderEntityTest`, `FnbOrderCancellationServiceTest`, `FnbPosOrderServiceTest`, `FnbRefundServiceTest`, `MerchantBusinessSettingsServiceTest`: `new SalesOrderEntity.PosDetails(key, fp, number, serviceType, label)` needs a 6th arg `orderNote`; `SalesOrderEntityTest` calls `replaceLines(type, label, charges)` -> now `(type, label, note, charges)`.
- `FnbPosOrderServiceTest`: any assertion that a blank/missing customerLabel is rejected (the request no longer requires it); verify fingerprint-dependent stubs.
- Any test calling `FnbStaffOrderService.list(...)` or `FnbOrderRepository.page(...)` (none found by grep in unit tests; integration tests go through HTTP and are unaffected).
- Integration: `FnbShiftRefundIntegrationTest` and the pay-first suites that assert `totalsByMethod` / `expectedCash` JSON are unaffected unless they assert the exact field set; MANUAL_NON_CASH payments (W3) need a labelId.
- New coverage needed: expense create/replay/conflict/no-shift/other-role, expected-cash math in current and close, `totalsByMethod` per label, opening suggestion (none / latest closed any member), exact `openOrderCount` > 101, orderNote create/replace/clear, optional customer, list filters (from/to pair, 366-day cap, zone boundary, orderNumber exact).
