# Contract Audit Result (2026-11-23)

> TEMPORARY working memory. Delete with part-a/b/c after decisions are applied. Paths relative to `src/main/java/org/lite/project/cashiersaas`.

## 1. FE contract wrong — backend is the truth (fix FE)
- Prefix `/api/v1` missing everywhere; sales report = `/api/v1/merchant/outlets/{outletId}/fnb/reports/sales` (OWNERMERCHANT).
- Order option fields: `name`, `priceAdjustment` (not `optionName`, `extraPrice`) — dto/response/FnbStaffOrderOptionResponse.java:14-16.
- `source` enum: `QR` | `POS` (not `QR_ORDER`) — enums/OrderSource.java.
- receiptNumber `INV-yyyyMMdd-NNNN`; orderNumber `POS-` + 20 hex.
- FnbPaymentRequest: add `labelId` (required for non-cash); idempotencyKey max 128, no min 16. Response has `methodLabel`, no `amountRequested`.
- FnbPosOrderRequest: `customerLabel` optional max 80; `orderNote`; quantity 1..100; items 1..50.
- Checkout + refund need `Idempotency-Key` header; stock count uses body `idempotencyKey`.
- Product: name max 160, categoryId always required, price >= 0, description optional max 1000.
- Option group has no nested `options`; option recipe only via separate GET/PUT recipe endpoints.
- InventoryItem: `sku` optional, `unit` free text max 30, no `costPerUnit`/`updatedAt`. StockMovement: `actorMembershipId`, no name/unit. StockCountResponse has `stockCountId`.
- JWT claim `roles` (array), not `role`. SelectMerchantRequest only tenantId + outletId.
- `User` type (storeName, avatarUrl, storeId) has no backend DTO.
- Plan code `FREETIER` / display "Freetier".
- CSV export = JSON `{filename, contentType, content}`, not a stream.
- ApiResponse: `data` omitted when null (NON_NULL); timestamp LocalDateTime without `Z`; 400 message "Request tidak valid."
- Contract omits many existing endpoints/fields (auth register/password/refresh/logout/csrf, onboarding, outlet CRUD, order list/detail/items/cancel, refund, catalog/inventory CRUD, report COGS/profit/refund/hourly/cashiers/shifts, 402 OUTLET_OVER_ALLOWANCE).

## 2. Possible backend gap — needs user decision
- `MOCK_GATEWAY` / `QRIS` payment method: not in backend enum.
- Order response `cashierName`, `paymentMethodName`, `referenceId`: absent.
- Product `categoryName`, recipe `costEstimate`, movement `actorName`: absent (FE needs lookup).
- Timestamp without offset (LocalDateTime) vs ISO UTC.
- Idempotency key: header on checkout/refund but body on payment/stock count — inconsistent.

## 3. False claims (§6 checklist)
- Idempotency TTL 24h: FALSE — permanent DB unique constraints, no Redis, no expiry.
- 409 optimistic conflict on stock movement: FALSE — PESSIMISTIC_WRITE lock (blocks).
- 409 on recipe update: FALSE — replaceRecipe has no version/lock check.
- 0-based pagination: TRUE. Payment -> SALE stock deduction incl. option recipes: TRUE. Role enum incl. OWNERMERCHANT: TRUE.
