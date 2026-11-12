# F&B POS, held bill, payment, receipt and reports

Backend API implementation, 6 October 2026. This document describes the current source
contracts; production compile passed (237 sources, release 17), runtime verification remains
pending. Frontend rendering and real gateway
integration are separate work. Since the cashier-first pivot (2026-11-12) orders come only from the cashier POS: customer QR ordering, the kitchen queue and dining tables were removed.

## Readiness and database prerequisite

The database applied `V11__fnb_pay_first_operations.sql` with checksum `835635316`.
The workspace at that time contained a conflicting QR-only V11. The authentic source has
not been recovered. Restore its original contents/provenance before assigning an additive
migration for the final entity mappings. Migration history must remain unchanged.

Source code requires rate snapshots, payment idempotency/fingerprint/reference and persisted
mock attempts. Their schema integration is pending TASK-024 and the authentic V11 source gate.
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
  "items": [
    {"productId": "<product-uuid>", "quantity": 2, "note": "Tidak pedas", "optionIds": []}
  ]
}
```

`serviceType` is `DINE_IN` or `TAKEAWAY`. Use actual UUIDs
in requests. The server checks product/option availability, computes money with decimal
arithmetic and stores product/option names and prices plus tax/service-rate snapshots.
The order starts as `OPEN/UNPAID` (a held bill). Statuses are `OPEN`, `COMPLETED` and `CANCELLED`.

Exact checkout retry returns its existing order. Reusing a key for another source, outlet
or payload produces a conflict.

`GET` lists orders with `page`, `size`, optional `status`, `paymentStatus`
and `source`. For held bills use `status=OPEN&paymentStatus=UNPAID`. Defaults are page 0/size 20; size is at most 50. `GET /{orderId}` returns
stored item/option snapshots and financial totals. List results include `hasNext`.

### Replace items of a held bill

`PUT /{orderId}/items` with the same body as checkout and no `Idempotency-Key` (a full
replacement is idempotent). Allowed only for an `OPEN/UNPAID` POS order with no discount and
no pending gateway attempt; otherwise `409`. A missing or foreign order is `404`. The server
re-prices the new lines with the order's stored tax/service-rate snapshots and writes an audit
entry. Cancel a held bill with `POST /{orderId}/cancel`; pay it with the payments route.

## Payment and mock simulation

`POST /{orderId}/payments` on the staff orders base:

```json
{"method":"CASH","amountTendered":100000,"idempotencyKey":"payment-request-0001"}
```

Methods: `CASH`, `MANUAL_NON_CASH`, `MOCK_GATEWAY`. Cash accepts tender at least equal to
the order total and records change; manual non-cash and mock require the exact total.
The payment key is supplied in the body and is scoped across the tenant's payment/attempt
requests. Exact retries preserve payment and receipt; changed key reuse conflicts.

Successful settlement atomically records a single payment/receipt and audit, sets
`COMPLETED/PAID`. Paid orders cannot be cancelled or discounted.

To simulate the gateway, submit method MOCK_GATEWAY with the exact server total and a
new payment key. The response identifies provider MOCK and a persisted PENDING attempt.
It includes `amountRequested`; an unsettled attempt has no `amountPaid` or receipt.
Pending attempts block manual payment, discounts and cancellation.

Resolve through authenticated
`POST /{orderId}/mock-payments/{attemptId}/resolve`:

```json
{"outcome":"SUCCEEDED"}
```

FAILED leaves the order unpaid; a fresh key can retry. SUCCEEDED settles and returns the
receipt/payment with mock metadata. Identical terminal retries return the stored result;
attempted terminal reversal conflicts. No external provider, credentials or callback is used.

## Discounts and cancellation

Owner: `POST /api/v1/merchant/outlets/{outletId}/fnb/orders/{orderId}/discount`:

```json
{"type":"PERCENTAGE","value":10,"reason":"Promo makan siang"}
```

NOMINAL and PERCENTAGE are supported. One discount is allowed on an OPEN/UNPAID order;
it reduces subtotal before tax/service are recomputed from stored rates.

Owner/Cashier: `POST /{orderId}/cancel` on the staff orders base, with
`{"reason":"Pelanggan membatalkan"}`. Only OPEN/UNPAID is cancellable. Actor, reason and
time are audited.

## Receipts and sales reports

Receipt: `GET /api/v1/cashier/outlets/{outletId}/fnb/orders/{orderId}/receipt`.
Requires a scoped paid order. JSON includes stored financial/item/payment snapshots,
receipt/order numbers, merchant/outlet contact, currency, outlet-local payment time,
tender/change and cashier. Escaped `html` includes print CSS for the separate frontend.
Mock receipts visibly identify simulated payment.

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
Mock payments remain included for development and are identified by method.

`GET /sales/csv` uses the same sales filters and returns a named JSON payload with
`filename`, `contentType` and `content`. The frontend can save `content` as CSV. Export
over 10,000 rows is rejected; no partial export is silently returned. Fields are quoted,
quotes escaped and leading spreadsheet formula prefixes neutralized.

## Recommended verification when explicitly requested

Verify POS availability, checkout retries and replace-items rules (OPEN/UNPAID only); cash tender/change; pending/failed/succeeded
mock states, terminal retries and key conflicts; tenant/outlet hiding and owner-only actions;
discount snapshot rates; OPEN to COMPLETED on payment;
receipt HTML escaping and simulation labeling; local-date/DST report boundaries, complete
aggregates, product identities, report filters and CSV escaping/export limits. Verify schema
constraints and concurrent requests after the authentic V11 and additive schema are restored.
