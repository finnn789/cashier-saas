# TASK-087 — FE Contract Alignment Report (2026-11-23)

Source: audit of FE contract "TASK-CONTRACT-DATA v2.5.0" against backend DTO/controllers (`.ai/context/contract-data/audit-result.md`, temporary).
Decision owner: user. Backend stays as-is for every item below; the FE contract changes.

## A. Kept by decision (backend unchanged)

| # | Topic | Backend fact | Location |
|---|---|---|---|
| A1 | Payment method | Only `CASH` and `MANUAL_NON_CASH`. No `MOCK_GATEWAY`, no `QRIS`. Non-cash variants (QRIS, transfer, e-wallet) are payment **labels**: send `labelId`, display `methodLabel`. | enums/PaymentMethod.java; dto/request/FnbPaymentRequest.java:12 |
| A2 | Idempotency key placement | Header `Idempotency-Key` on **checkout** (POST orders) and **refund**. Body field `idempotencyKey` on **payment** and **stock count**. | FnbStaffOrderController.java:45; FnbRefundController.java:24; FnbPaymentRequest.java:13; FnbStockCountRequest.java:15-17 |
| A3 | Idempotency lifetime | Permanent DB unique key per tenant, no 24h TTL, no Redis. Reusing a key returns the original result; a new attempt needs a new key. | SalesOrderEntity.java:21; PaymentEntity.java:20; StockCountEntity.java:14 |
| A4 | Timestamps | `ApiResponse.timestamp` is `LocalDateTime` without offset (`2026-11-23T10:15:30.123`). Domain fields (`createdAt`, `occurredAt`, `paidAt` on payment) are `Instant` (UTC, with `Z`). Report/receipt `paidAt` is `ZonedDateTime` with outlet offset. | shared/response/ApiResponse.java:25,28 |

## B. FE contract errors (backend is correct)

1. All paths are prefixed `/api/v1`. `/cashier/**` = OWNERMERCHANT+CASHIER, `/merchant/**` = OWNERMERCHANT, `/public/**` = open.
2. Sales report: `GET /api/v1/merchant/outlets/{outletId}/fnb/reports/sales`; CSV: `GET .../sales/csv` returns JSON `ApiResponse<{filename, contentType, content}>` (not a stream).
3. `ApiResponse`: `data` is **omitted** when null (no `"data": null`). Validation message is `"Request tidak valid."`.
4. JWT claim is `roles` (array), plus `scope`, `membership_id`, `session_id`, `email`. Role values `SUPERADMIN | OWNERMERCHANT | CASHIER` are correct.
5. Select outlet request: only `tenantId`, `outletId` (both required). `outletName`, `token` do not exist.
6. `User` type (storeName, storeId, avatarUrl) has no backend DTO — derive from JWT + `GET /api/v1/auth/outlets`.
7. Plan code `FREETIER` (display "Freetier"), plus `PRO`, `PREMIUM`. Response `{code, name, available, monthlyPrice, yearlyPrice, outletAddOnMonthlyPrice}` from `GET /api/v1/public/plans`.
8. Order `source`: `POS | QR` (not `QR_ORDER`).
9. Order option fields: `optionId`, `name`, `priceAdjustment` (not `optionName`, `extraPrice`). Order has `orderNote`.
10. `FnbPosOrderRequest`: `customerLabel` optional max 80; `orderNote` optional; items 1..50; quantity 1..100; `optionIds` required (may be empty) max 100.
11. Formats: `orderNumber` = `POS-` + 20 hex chars; `receiptNumber` = `INV-yyyyMMdd-NNNN`. Do not parse them.
12. `FnbPaymentRequest`: `idempotencyKey` 1..128 chars (no min 16); `amountTendered` >= 0, 2 decimals; `labelId` required for non-cash. Response has `methodLabel`, **no** `amountRequested`.
13. Product: name max 160, `categoryId` always required, price >= 0 (0 allowed), description optional max 1000. Archived category → 404.
14. `OptionGroup` has no nested `options`; load options via the options endpoint. `ProductOption` has no inline `recipe`; use `GET/PUT .../options/{optionId}/recipe`.
15. Inventory item: `sku` optional (max 80), `unit` free text max 30 (not an enum), no `costPerUnit`/`updatedAt`; cost is `averageUnitCost` (owner only).
16. Stock movement: `actorMembershipId` (UUID). Stock count response has `stockCountId`.
17. Concurrency: stock movements use a pessimistic lock (requests wait, no 409). Recipe update has no conflict check (last write wins). A generic 409 can still appear from DB unique violations or `@Version` mismatch.
18. Pagination is 0-based (`page`, `size`, response `hasNext`) on order list and sales report — correct.
19. Endpoints the contract omits but FE may use: auth register/password/refresh/logout/csrf, onboarding (intent/status/freetier/business-profile), merchant outlet CRUD + deactivate/reactivate, cashier outlet list, order list/detail/replace items/cancel, refund, full catalog/inventory CRUD, report extras (`costOfGoodsSold`, `grossProfit`, `itemsWithoutCost`, `refundCount`, `refundAmount`, `hourly`, `cashiers`, `shifts`), error 402 `OUTLET_OVER_ALLOWANCE` with `{reason, usableOutletIds}`.

## C. Pending backend additions (separate packet, not in this report's scope)
`categoryName` (product), `costEstimate` (recipe line), `actorName` (stock movement), and order-level cashier/payment fields — see orchestrator decision.

---

## Prompt for FE agent

```
Repo: E:\Java\cashier-saas-ui. Align the FE data contract with the backend. Backend is the source of truth; do not invent fields.
Read E:\Java\cashier-saas\reports\TASK-087-FE-CONTRACT.md sections A and B — every item there is binding.

Do:
1. Update src/types/*.ts, src/schema/*.ts and src/api/*.ts to match sections A and B exactly
   (paths with /api/v1, field names, optionality, enums, validation limits).
2. Payment: method enum = 'CASH' | 'MANUAL_NON_CASH'. Remove MOCK_GATEWAY/QRIS as methods; QRIS etc. are payment labels
   (send labelId for non-cash, display methodLabel). Update mocks accordingly.
3. Idempotency: header `Idempotency-Key` for checkout and refund; body `idempotencyKey` for payment and stock count.
   Generate one key per user attempt (crypto.randomUUID()), reuse it only on retry of the same attempt. No TTL assumptions.
4. Timestamps: parse domain timestamps (Instant, with Z) as UTC; ApiResponse.timestamp has no offset — do not use it for logic.
   Display times in the outlet timeZone.
5. ApiResponse: treat missing `data` as null.
6. Remove claims of 409 optimistic conflict for stock/recipe; keep a generic 409 handler only.
7. Do not parse orderNumber/receiptNumber formats.
8. Fields listed in section C do not exist yet: do not depend on them; keep current lookups until the backend ships them.

Do not touch unrelated UI. Report: list of files changed and any FE screen that loses data because of these corrections.
```
