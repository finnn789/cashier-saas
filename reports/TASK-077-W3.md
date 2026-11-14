# TASK-077 W3 report (owner refund use case)

Nothing compiled or run (no Maven by rule). Base package `org.lite.project.cashiersaas`.

## Files
- NEW `service/FnbRefundService.java`, `controller/FnbRefundController.java`, `dto/request/FnbRefundRequest.java`, `dto/response/FnbRefundResponse.java`
- EDIT `dto/FnbPaymentMapper.java` (new `refund(PaymentRefundEntity)` + 2 imports), `service/FnbStockService.java` (new `reverseForRefundedOrder`, private `lockedBalance` helper)

## Contract points
- `POST /api/v1/merchant/outlets/{outletId}/fnb/orders/{orderId}/refund`, header `Idempotency-Key` (optional at binding, validated in service, same regex/message as checkout), body `{reason}` NotBlank + max 500: `FnbRefundController.refund`.
- `FnbRefundService.refund` order of steps: `orders.lockTenant` -> `guard.requireOwner` -> key validation -> order by id (404) + `refreshOrder` -> replay by key (outlet, order and fingerprint `SHA-256(orderId|trimmedReason)` must match, else 409 "Idempotency-Key telah digunakan untuk refund berbeda."; replay returns the stored refund with empty `stockWarnings`) -> `payments.byOrder` (absent -> 409 "Hanya pesanan lunas yang dapat direfund.") -> `refunds.byPayment` present -> 409 "Pesanan sudah direfund." (this is the clean 409 for a second refund under a new key; also `markRefunded` would reject REFUNDED) -> `order.markRefunded()` -> shift: CASH -> `shifts.requireOpenShift(context)`, otherwise `shifts.findOpenShift(context).orElse(null)` -> `PaymentRefundEntity.record(... amount = amountPaid - changeAmount ...)` -> `orders.flush()` -> `stock.reverseForRefundedOrder` -> audit `FNB_ORDER_REFUNDED` (reason, details `amount=<a> method=<m>`) -> response.
- `FnbStockService.reverseForRefundedOrder(MerchantAccessContext, SalesOrderEntity) -> List<String>`: iterates `inventory.saleMovements` (item id order), skips when `sale-reversal:{orderId}:{itemId}` exists, locks/creates the balance via new private `lockedBalance` (also now used by `consumeForPaidOrder`, behaviour unchanged), `balance.apply(-original)`, persists `SALE_REVERSAL` movement (`quantityDelta = -original`, reason "Refund pesanan <orderNumber>", fingerprint via `DigestService`), same `warning(...)` strings.
- `FnbRefundResponse`: orderId, orderNumber, paymentStatus (REFUNDED), amount, method, reason, refundedAt, refundedBy (fullName), stockWarnings. Mapper `FnbPaymentMapper.refund`.
- Response message "Pesanan direfund." via `ApiResponse.success` (HTTP 200). Security: `/api/v1/merchant/**` = OWNER (cashier -> 403).

## Deviations / needs (orchestrator action)
1. `reversalOfMovementId` is NOT set: `InventoryMovementEntity` (W1 scope) has no setter/overload and W3 may not edit it. Needed: either a `record(...)` overload with `UUID reversalOfMovementId` or `public void markReversalOf(UUID id)`; then in `FnbStockService.reverseForRefundedOrder` (marked with a TODO comment) pass `sale.getId()`. Reversals are still identifiable by type SALE_REVERSAL + orderId + item.
2. Key validation regex and SHA-256 are private in `FnbPosOrderService`; I could not edit it, so the one-line regex check is duplicated in `FnbRefundService.validatedKey` (same text). Fingerprint reuses `DigestService.digest` (no copied digest code). Suggest later moving the key validator to a shared place.
3. Request `reason` is trimmed before storing/fingerprinting; the extra `isEmpty()` check in the service is defensive only (`@NotBlank` already rejects blank).
4. Replay of a key returns the refund's current order paymentStatus, and empty stockWarnings.
5. Depends on W2's `FnbShiftService.requireOpenShift(MerchantAccessContext)` and `findOpenShift(MerchantAccessContext)` (file did not exist when I finished; code will not compile until it does).
6. Receipt for a refunded order stays 404: `FnbReceiptService:35` requires `PaymentStatus.PAID` (M5 item).
7. Sales report counts only PAID, so refunded orders drop out with no change (not touched).

## Test scenarios for W4
- Happy path CASH: owner has open shift, pay cash with change, refund -> 200, `paymentStatus=REFUNDED`, amount = paid - change, method CASH, refundedBy = owner name; order status stays COMPLETED; sales report excludes the order; audit row FNB_ORDER_REFUNDED with reason and `amount=.. method=CASH`; shift close math includes cashRefunds.
- Stock: product with recipe sold then refunded -> balance back to the pre-sale value, one SALE_REVERSAL movement with positive delta, key `sale-reversal:{orderId}:{itemId}`; items with low stock return warnings; order without recipes -> empty warnings.
- Non-cash (MANUAL_NON_CASH / MOCK_GATEWAY) refund without owner shift -> 200 with null cashShift; CASH refund without owner open shift -> 409 "Buka shift terlebih dahulu sebelum menerima pembayaran." and nothing persisted (order still PAID).
- Replay: same key + same reason -> 200 same body (empty stockWarnings), no second movement/audit; same key different reason -> 409; same key other order -> 409.
- Second refund with a new key -> 409 "Pesanan sudah direfund." (no DB error).
- Refund of OPEN/UNPAID order -> 409; cancelled order -> 409; unknown order id -> 404; order of another tenant -> 404.
- Cashier token -> 403; missing/invalid Idempotency-Key -> 400-style InvalidOperation; blank or >500 char reason -> validation error.
- Concurrent duplicate refund requests (different keys) -> one 200, one 409.
