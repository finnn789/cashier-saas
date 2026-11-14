# F&B backend: Pay First contract

Rewritten on 2026-11-12 for the cashier-first pivot. The earlier version of this document described the
stage-1 customer QR slice (outlet/table QR tokens, public menu and public order routes). That slice was
removed together with the kitchen queue, dining tables and WebSocket real-time; see `.ai/DECISIONS.md`
(entry "Pivot"). The pre-pivot text is in Git history.

## Contract

- Orders are created by the cashier at the POS (`POST /api/v1/cashier/outlets/{outletId}/fnb/orders`
  with `Idempotency-Key`). There is no anonymous or customer-facing order route.
- Flow: menu -> POS order (held = `OPEN`/`UNPAID`) -> record-only payment (cash or non-cash with an owner-defined label; atomic) -> `COMPLETED`/`PAID` -> receipt `INV-YYYYMMDD-NNNN` -> sales report.
- Order status is `OPEN`, `COMPLETED` or `CANCELLED`. Payment status is `UNPAID`, `PAID` or `REFUNDED`.
- A held bill can be changed with `PUT .../fnb/orders/{orderId}/items` (full replacement, no idempotency key)
  while it is `OPEN`/`UNPAID`, a POS order, without a discount.
- Owner controls discounts before tax/service charge. Owner/Cashier may cancel only `OPEN`/`UNPAID`
  orders, with a reason and an audit record. Paid orders are never cancelled or deleted.
- Totals are computed on the server; product names, prices and tax/service rates are stored as snapshots.
- Tenant and outlet come from the validated JWT principal; foreign records answer `404`.

Details of each route: [fnb-operations.md](fnb-operations.md). Architecture and security: [architecture.md](architecture.md).

## Database

Migration V6 (`V6__order_status_open_completed_cancelled.sql`) relaxes `sales_orders_status_check`, maps old rows
(paid -> `COMPLETED`, unpaid -> `OPEN`), fails on any row it cannot map, then tightens the constraint to
`OPEN`/`COMPLETED`/`CANCELLED`. Dining-table and QR tables and columns remain in the schema but are not mapped by
entities. Applied migrations are never edited.
