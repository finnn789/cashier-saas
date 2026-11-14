# F&B POS, held bill, payment, receipt and reports

Backend API implementation, 6 October 2026. This document describes the current source
contracts; production compile passed (237 sources, release 17), runtime verification remains
pending. Frontend rendering is separate work. Payments are record-only: the application never
moves money and there is no payment provider or gateway integration (the mock gateway was removed in M5, migration V8). Since the cashier-first pivot (2026-11-12) orders come only from the cashier POS: customer QR ordering, the kitchen queue and dining tables were removed.

## Readiness and database prerequisite

The database applied `V11__fnb_pay_first_operations.sql` with checksum `835635316`.
The workspace at that time contained a conflicting QR-only V11. The authentic source has
not been recovered. Restore its original contents/provenance before assigning an additive
migration for the final entity mappings. Migration history must remain unchanged.

Source code requires rate snapshots and payment idempotency/fingerprint/label columns. Their schema
integration is pending TASK-024 and the authentic V11 source gate.
The current feature must not be treated as deployable until that gate is resolved.
No feature tests or application startup have been executed during implementation.
Parent verification: `mvnw.cmd -o -DskipTests compile`, BUILD SUCCESS at 18:47:39 WIB,
6 October 2026. Java runtime 21, target release 17. Compile does not verify SQL/query execution
or the end-to-end acceptance path. See [review](../reports/FNB-20261006-orchestrator.md).

## Authentication and access

Supply a validated Bearer JWT on every staff request. Tenant identity comes from the
principal; outlet access is checked against current membership and assigned outlet.
Owner and Cashier operate POS, payments and receipts. Only Owner grants discounts
and reads sales reports/CSV. These APIs use the existing `ApiResponse<T>` JSON envelope.
Cross-tenant/outlet records are hidden through scoped access and not-found responses.

## POS and common staff orders

Base: `/api/v1/cashier/outlets/{outletId}/fnb/orders`.

`POST` with header `Idempotency-Key` (16-128 letters, digits, `_` or `-`) creates a POS order:

```json
{
  "serviceType": "DINE_IN",
  "customerLabel": "Pelanggan 01",
  "orderNote": "Untuk meja dekat jendela",
  "items": [
    {"productId": "<product-uuid>", "quantity": 2, "note": "Tidak pedas", "optionIds": []}
  ]
}
```

`serviceType` is `DINE_IN` or `TAKEAWAY`. `customerLabel` (max 80) and `orderNote` (order-level note, max 500)
are both optional; blank values are stored as null. Use actual UUIDs
in requests. The server checks product/option availability, computes money with decimal
arithmetic and stores product/option names and prices plus tax/service-rate snapshots.
The order starts as `OPEN/UNPAID` (a held bill). Statuses are `OPEN`, `COMPLETED` and `CANCELLED`.

Exact checkout retry returns its existing order. Reusing a key for another source, outlet
or payload produces a conflict.

`GET` lists orders with `page`, `size`, optional `status`, `paymentStatus`
and `source`, plus optional `from` and `to` (ISO dates in the outlet time zone, both or neither, at most 366 days;
otherwise `400`) and `orderNumber` (exact match after trimming). For held bills use `status=OPEN&paymentStatus=UNPAID`. Defaults are page 0/size 20; size is at most 50. `GET /{orderId}` returns
stored item/option snapshots and financial totals. List results include `hasNext`.

### Replace items of a held bill

`PUT /{orderId}/items` with the same body as checkout and no `Idempotency-Key` (a full
replacement is idempotent). Allowed only for an `OPEN/UNPAID` POS order with no discount;
otherwise `409`. A missing or foreign order is `404`. The server
re-prices the new lines with the order's stored tax/service-rate snapshots and writes an audit
entry. Cancel a held bill with `POST /{orderId}/cancel`; pay it with the payments route.

## Payments (record-only)

The application records how a customer paid; it never processes money and has no provider or
gateway. Only two methods exist: `CASH` and `MANUAL_NON_CASH` (non-cash). The mock gateway and its
attempt/resolve flow were deleted in M5; historical `MOCK_GATEWAY` rows were converted by V8 to
`MANUAL_NON_CASH` with the label "Gateway simulasi (lama)".

`POST /{orderId}/payments` on the staff orders base (Owner, Cashier):

```json
{"method":"CASH","amountTendered":100000,"idempotencyKey":"payment-request-0001"}
```

```json
{"method":"MANUAL_NON_CASH","amountTendered":47500,"labelId":"<payment-label-uuid>","idempotencyKey":"payment-request-0002"}
```

- `CASH`: `amountTendered` at least the order total (else `400` "Uang tunai kurang dari total pesanan."); the
  server records the change. A `labelId` is rejected (`400` "Pembayaran tunai tidak memakai label.").
- `MANUAL_NON_CASH`: `amountTendered` must equal the total ("Pembayaran non-tunai harus sesuai total pesanan.")
  and `labelId` must be an active label of the same outlet, otherwise `400` "Pilih label pembayaran non-tunai.".
  The label NAME is stored on the payment as a text snapshot (`methodLabel`), so later renames do not change history.
- A payment requires the paying staff member to have an open cash shift (see "Cash shifts"); without one
  the request is `409` ("Buka shift terlebih dahulu sebelum menerima pembayaran.") and nothing is written.
  An idempotent replay of an existing payment is answered before the shift check, so it still returns the stored
  payment after the shift was closed.
- The payment key is supplied in the body and scoped to the tenant. Exact retries return the stored payment and
  receipt; reuse with a different method, amount or label conflicts (`409` "Kunci pembayaran telah digunakan.").
  Paying an already paid order is `409`.
- Response: `orderId`, `method`, `amountPaid`, `changeAmount`, `methodLabel` (non-cash only), `receiptNumber`,
  `paidAt`, `stockWarnings`.
- Receipt number `INV-YYYYMMDD-NNNN`: the date is the business date in the outlet time zone, `NNNN` is a
  per-outlet, per-day counter starting at 0001 (zero-padded to four digits; five or more digits are allowed past
  9999). The counter lives in `receipt_sequences` (atomic upsert) and is drawn only when a payment is settled,
  so replays and rolled-back payments consume no number.

Successful settlement atomically records a single payment/receipt, deducts stock and audits, and sets
`COMPLETED/PAID`. Paid orders cannot be cancelled or discounted.

### Payment labels (non-cash)

Owner manages the labels of an outlet; Cashier reads the active ones to fill the picker. No delete: deactivate instead.

| Route | Role | Notes |
|---|---|---|
| `GET /api/v1/merchant/outlets/{outletId}/fnb/payment-labels` | Owner | all labels including inactive, ordered by `displayOrder` then name |
| `POST /api/v1/merchant/outlets/{outletId}/fnb/payment-labels` body `{"name":"Transfer BCA","displayOrder":4}` | Owner | `201`; a new label is always active; `displayOrder` optional (default = count + 1) |
| `PUT /api/v1/merchant/outlets/{outletId}/fnb/payment-labels/{labelId}` body `{"name":"...","displayOrder":2,"active":false}` | Owner | omitted `displayOrder`/`active` keep the current value |
| `GET /api/v1/cashier/outlets/{outletId}/fnb/payment-labels` | Owner, Cashier | active labels only |

Name is required, max 40 (trimmed); `displayOrder` 0..9999. A duplicate name (case-insensitive) is `409`
"Label sudah ada."; more than 20 labels per outlet is `409` "Jumlah label maksimal 20.". Create and update are audited
as `PAYMENT_LABEL_CHANGED` (`name=<n> active=<b>`). Response item: `id`, `name`, `active`, `displayOrder`.
Every outlet gets the default labels `QRIS`, `Transfer` and `Debit` (order 1-3): V8 seeds existing outlets and the
onboarding flow creates them with the first outlet.

## Cash shifts

One open shift per staff member and outlet (unique partial index; a second open is `409`
"Anda masih memiliki shift terbuka."). Owner and Cashier use the cashier routes; the owner
has extra merchant routes. Bodies use decimal amounts with at most 2 fractions.

| Route | Role | Notes |
|---|---|---|
| `POST /api/v1/cashier/outlets/{outletId}/fnb/shifts` body `{"openingCash":100000.00}` | Owner, Cashier | `201`; audit `SHIFT_OPENED` |
| `GET /api/v1/cashier/outlets/{outletId}/fnb/shifts/current` | Owner, Cashier | caller's open shift with live summary; `404` when none |
| `POST /api/v1/cashier/outlets/{outletId}/fnb/shifts/current/close` body `{"countedCash":..., "note":"..."}` | Owner, Cashier | closes the caller's open shift; note max 500 |
| `GET /api/v1/merchant/outlets/{outletId}/fnb/shifts?from=&to=&page=&size=` | Owner | `from`/`to` ISO dates in the outlet time zone (default today, max 366 days), size at most 50, newest first; response `{shifts, page, size, hasNext}` |
| `GET /api/v1/merchant/outlets/{outletId}/fnb/shifts/{shiftId}` | Owner | `404` outside the tenant/outlet |
| `POST /api/v1/merchant/outlets/{outletId}/fnb/shifts/{shiftId}/close` same body | Owner | force-closes any open shift of the outlet; the owner is recorded as closer |

Two more cashier routes (Owner, Cashier):

| Route | Notes |
|---|---|
| `GET /api/v1/cashier/outlets/{outletId}/fnb/shifts/opening-suggestion` | `{openingCash, sourceShiftId}`: the counted cash of the latest CLOSED shift of the outlet (any member); `0.00` and `null` when there is none. The UI prefills the opening cash with it; the user can change it |
| `POST /api/v1/cashier/outlets/{outletId}/fnb/shifts/current/expenses` header `Idempotency-Key`, body `{"amount":15000.00,"description":"Beli es batu"}` | Petty cash taken from the drawer during the caller's open shift; `201` with `{id, amount, description, recordedAt, recordedBy}`. `amount` > 0 (2 decimals), `description` 1..200. No open shift: `409` "Buka shift terlebih dahulu sebelum mencatat pengeluaran." (a replay is answered first). Same key with a different amount/description is `409`. Audited as `CASH_EXPENSE_RECORDED` |
| `GET /api/v1/cashier/outlets/{outletId}/fnb/shifts/current/expenses` | Expenses of the caller's open shift, oldest first; `404` when no open shift |

Close math: `expectedCash = openingCash + cashIn - cashRefunds - expensesTotal`, where `cashIn` is the sum of
`amountPaid - changeAmount` over the shift's CASH payments, `cashRefunds` the sum of the
shift's CASH refunds and `expensesTotal` the shift's petty-cash expenses. `cashDifference = countedCash - expectedCash`
(expected may be negative; expenses are not capped). Non-cash payments are
listed in `totalsByMethod` (method, `methodLabel`, count, amount), one row per method and label, but do not enter the expected cash.
The shift summary doubles as the daily report: it also returns `expensesTotal` and the `expenses` list (amount,
description, `recordedAt`, `recordedBy`; omitted on the owner list rows).
Closing an already closed shift is `409` ("Shift sudah ditutup."); audit `SHIFT_CLOSED` with
`expected`, `counted` and `difference`. While a shift is OPEN, `expectedCash` in the response is the live
value. The response also carries `paymentCount`, `cashIn`, `cashRefunds`, `expensesTotal` and `openOrderCount`
(exact count of OPEN/UNPAID orders of the outlet; they may stay open at close and are shown as a warning). Each payment stores the
shift of the member who settled it; payments from before V7 have no shift.

## Refund (owner)

`POST /api/v1/merchant/outlets/{outletId}/fnb/orders/{orderId}/refund` with header
`Idempotency-Key` (same format as checkout) and body `{"reason":"Pelanggan komplain"}`
(required, max 500). Rules:

- Owner only (a Cashier gets `403`). Only a paid `COMPLETED` order can be refunded, otherwise `409`
  ("Hanya pesanan lunas yang dapat direfund."); an unknown or foreign order is `404`.
- Always a full refund of `amountPaid - changeAmount` of the order's single payment.
- A second refund of the same order is `409` ("Pesanan sudah direfund."). Retrying the same
  key with the same order and reason returns the stored refund (with empty `stockWarnings`);
  the same key for another order or reason is `409`.
- A CASH refund requires the owner's own open shift (`409` otherwise) and is counted in that shift's
  `cashRefunds`. For non-cash methods the shift is optional (the owner's open shift is recorded if there is one).
- Payment status becomes `REFUNDED`; the order stays `COMPLETED`. The row is stored in `payment_refunds`
  (amount, method, reason, actor, time) and audited as `FNB_ORDER_REFUNDED` (`amount=<a> method=<m>`).
- Stock is returned: for each `SALE` movement of the order one `SALE_REVERSAL` movement with the opposite
  delta is written (key `sale-reversal:{orderId}:{inventoryItemId}`, reason "Refund pesanan <orderNumber>").
  The response `stockWarnings` list the same low/out/negative warnings as a payment.
- The sales report counts only `PAID` orders, so a refunded order drops out of it. The receipt route
  still returns the receipt of a refunded order, marked `REFUND`.

Response: `orderId`, `orderNumber`, `paymentStatus`, `amount`, `method`, `reason`, `refundedAt`,
`refundedBy` (full name), `stockWarnings`.

## Discounts and cancellation

Owner: `POST /api/v1/merchant/outlets/{outletId}/fnb/orders/{orderId}/discount`:

```json
{"type":"PERCENTAGE","value":10,"reason":"Promo makan siang"}
```

NOMINAL and PERCENTAGE are supported. One discount is allowed on an OPEN/UNPAID order;
it reduces subtotal before tax/service are recomputed from stored rates.

Owner/Cashier: `POST /{orderId}/cancel` on the staff orders base, with
`{"reason":"Pelanggan membatalkan"}`. Only OPEN/UNPAID is cancellable. Actor, reason and
time are audited. A paid order cannot be cancelled; use the owner refund. A Cashier cannot cancel an order that has a discount (403, only the
owner can); the owner can cancel it.

Creating an order (checkout, not an idempotent replay) is audited as `FNB_ORDER_CREATED`
with the order number and total.

## Receipts and sales reports

Receipt: `GET /api/v1/cashier/outlets/{outletId}/fnb/orders/{orderId}/receipt` (Owner, Cashier).
Available for `PAID` and `REFUNDED` orders; cancelled or unpaid orders are `404`. JSON includes the stored
financial/item/payment snapshots, receipt/order numbers, `businessName`, `logoUrl`, outlet name/address/phone,
currency, outlet-local `paidAt`, `method` + `methodLabel`, tender/change, cashier (`receivedBy`),
`customerLabel`, `orderNote` and the owner-defined `receiptFooter`. A refunded order has `refunded=true`,
`refundedAt` and `refundReason`, and the escaped `html` shows a clear "REFUND" marker. The `html` (print CSS
included) also shows the footer; printing is optional and done by the frontend from the browser.

Receipt footer: set by the owner in business settings (`receiptFooter`, optional, max 200, blank clears it; the
`PUT .../business-settings` replaces the whole document, so send the current footer back to keep it).

Owner report: `GET /api/v1/merchant/outlets/{outletId}/fnb/reports/sales`.
Optional query fields: `startDate`, `endDate` (YYYY-MM-DD), `orderStatus`, `paymentMethod`,
`page`, `size`, `topProducts`. Dates default to today in the outlet time zone. Start and
end dates are inclusive local dates, converted to an exclusive next-day timestamp boundary.
Range is at most 366 days; page size at most 100, top products at most 50.

Only paid, non-cancelled sales are included, selected by payment `paidAt`. Summary,
transaction history, product ranking and method breakdown share filters; aggregates cover
the whole range, regardless of the history page. Cancellation count is a separate metric
for cancellations during the selected period, independent of paid-sales filters.

`netSales` is subtotal minus discount before tax/service. `amountCollected` is tender minus
change. Product `grossSales` represents item/option totals before order discount and charges.
Summary `total` includes tax/service; `averageTransaction` is that total divided by count.
The payment-method breakdown is grouped by method and label (`methodLabel`, e.g. QRIS vs Transfer) so the owner can match
non-cash totals with bank statements; the CSV has a `Payment Label` column after `Payment Method`.

`GET /sales/csv` uses the same sales filters and returns a named JSON payload with
`filename`, `contentType` and `content`. The frontend can save `content` as CSV. Export
over 10,000 rows is rejected; no partial export is silently returned. Fields are quoted,
quotes escaped and leading spreadsheet formula prefixes neutralized.

## M6: unit cost, COGS, stock count and owner reports (migration V9)

Not compiled or run end to end at the time of writing (TASK-079); recorded here as the contract.

Unit cost. `inventory_balances.average_unit_cost` (scale 4), `inventory_movements.unit_cost` (scale 4) and
`order_items.cost_amount` (scale 2) are nullable; old rows are not backfilled.

- Moving average on `RESTOCK` with price `c` and quantity `q`, before-balance on hand `s` and average `a`:
  `a' = c` when `s <= 0` or `a` is null, otherwise `a' = (s*a + q*c) / (s + q)` (scale 4, HALF_UP). Only a priced
  RESTOCK changes the average. A RESTOCK without a price leaves the average unchanged and records the current
  average as the movement `unitCost`. WASTE and a positive ADJUSTMENT (including stock count) never change it.
- Movement `unitCost`: RESTOCK = the entered price (or the current average when none was sent); SALE, WASTE,
  ADJUSTMENT = the balance average at that moment (may be null); SALE_REVERSAL = the `unitCost` of the SALE it reverses.
- `unitCost` on the movement request is only valid for RESTOCK (`400 "Harga beli hanya untuk restock."`).
  Both OWNER and CASHIER may send a purchase price. It is part of the request fingerprint only when present,
  so old idempotency keys keep replaying.
- Cost visibility: `averageUnitCost` (ingredient responses) and `unitCost` (movement responses) are returned only
  to OWNERMERCHANT; they are null for CASHIER on every inventory response.
- Inactive ingredients are never deducted on payment and add no cost, even when a recipe line still references them.

COGS per order item. On payment, `cost_amount` of each item = sum over the product recipe lines and that item's
option recipe lines (active ingredients only) of `quantityPerUnit x item quantity x average cost`. It is null when
the item has no active recipe line, or any used ingredient has no balance or no average ("item without cost").
A replayed payment does not overwrite an existing cost.

Stock count (owner only): `POST /api/v1/merchant/outlets/{outletId}/fnb/inventory/stock-counts`, one transaction
for many ingredients. Body `{idempotencyKey (max 128), note? (max 500), lines: [{inventoryItemId, countedQuantity >= 0}]}`
(1-200 lines; duplicate ingredient: `400`; unknown, inactive or foreign ingredient: `404`). Each line with a
difference becomes an ADJUSTMENT of (counted - on hand) with reason `Stok opname` (plus `: note` when given); a zero
difference creates no movement (`movementId` null). Response `{lines: [{inventoryItemId, name, unit, previousQuantity,
countedQuantity, difference, movementId}]}`, HTTP 201. Idempotency: a per-line key is derived from the request key and
the ingredient id; the same key with the same content (lines and note) replays the previous result (also 201), different
content is `409 "Kunci idempotensi telah digunakan."`. Known limits: an all-zero count leaves no movement, so its
replay is re-evaluated against current stock; a replay after an ingredient was deactivated returns `404`; lines without
a movement are returned in a replay with previous = counted.

Owner sales report additions (`GET /merchant/outlets/{outletId}/fnb/reports/sales`, same filters; the CSV is unchanged):

- Summary: `costOfGoodsSold` (sum of non-null item costs), `grossProfit` = `netSales` (after order discount) - `costOfGoodsSold`,
  `itemsWithoutCost` (included item rows with null cost), `refundCount` and `refundAmount` (refunds with `refundedAt`
  in the range, outlet-wide, no status or method filter).
- Per menu (`topProducts`): `costOfGoodsSold`, `grossProfit` = `grossSales` - `costOfGoodsSold` (line totals before order
  discount, so the per-menu sum can exceed the total gross profit by the discounts), `itemsWithoutCost`. The UI should hint
  "N item tanpa HPP" so a profit that ignores uncosted items is not misread; orders paid before V9 count as without cost.
- `hourly`: `{hour 0-23 in the outlet zone, transactionCount, netSales}`, only hours with data, ascending.
- `cashiers`: `{staffUserId, fullName, transactionCount, netSales, amountCollected}` by receiving member, ordered by
  `netSales` descending.
- `shifts`: `{shiftId, cashierName, openedAt, closedAt, status, transactionCount, netSales}` ordered by `openedAt`
  descending; payments without a shift form one row with null shift fields.

## Recommended verification when explicitly requested

Verify shift open/close math (cash, non-cash, change, refunds, petty cash), opening suggestion, label CRUD/limits/duplicates and the
non-cash label rules, sequential receipt numbers per outlet per day, receipt of a refunded order, payment without a shift (`409`) and replay
after the shift closed, owner force close, refund rules above (stock restored, report excludes the order, CASH refund
needs the owner's shift); then POS availability, checkout retries and replace-items rules (OPEN/UNPAID only); cash tender/change; key conflicts; order list
filters; V8 mapping of old `MOCK_GATEWAY` rows; tenant/outlet hiding and owner-only actions;
discount snapshot rates; OPEN to COMPLETED on payment;
receipt HTML escaping (footer, note, customer label); local-date/DST report boundaries, complete
aggregates, product identities, report filters and CSV escaping/export limits. Verify schema
constraints and concurrent requests after the authentic V11 and additive schema are restored.
