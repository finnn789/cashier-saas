# TASK-078 W5 — full receipt and receipt footer setting

Not compiled/tested (policy). Depends on W4 `SalesOrderEntity.getOrderNote()` (not yet present at write time) and W1/W3 `PaymentEntity.getMethodLabel()`.
Paths under `src/main/java/org/lite/project/cashiersaas/`.

## Files (all edited)
- service/FnbReceiptService.java, dto/FnbReceiptMapper.java, dto/response/FnbReceiptResponse.java
- service/MerchantBusinessSettingsService.java, dto/MerchantBusinessSettingsMapper.java
- dto/request/MerchantBusinessSettingsRequest.java, dto/response/MerchantBusinessSettingsResponse.java

## Contract points
- Receipt allowed for PAID and REFUNDED, 404 for CANCELLED/UNPAID: FnbReceiptService.receipt (the `if` after `orders.byId`). Refund loaded via `FnbRefundRepository.byPayment(tenantId, paymentId)` only when REFUNDED, passed to `FnbReceiptMapper.receipt(payment, refund, items, options)` (signature changed: new 2nd arg, refund nullable).
- FnbReceiptResponse: removed simulatedPayment/provider/providerReference; added businessName, logoUrl, receiptFooter, methodLabel, customerLabel, orderNote, refunded (boolean primitive; JSON `refunded`, always serialized), refundedAt (ZonedDateTime, outlet zone), refundReason. `merchantName` kept (same value as businessName).
- HTML: logo img (HTTPS, escaped), red "REFUND" box when refunded, customer/note lines when present, method "METHOD - label", refund time+reason, footer centered at bottom. All strings go through the existing `escape`. Labels now Indonesian (Struk, Pesanan, Dibayar, Zona waktu, Pelanggan, Catatan, Diskon, Pajak, Layanan, Metode, Diterima, Kembalian, Kasir, Direfund, Alasan refund).
- Settings: request `@Size(max=200) receiptFooter` optional; service applies `tenant.changeReceiptFooter(...)` (null/blank clears it, so omitting the field clears the footer — full-replace PUT semantics like the other optional fields); change triggers the existing BUSINESS_SETTINGS_CHANGED audit; `details()` JSON now includes `"receiptFooter"` in before/after. Response/mapper expose `receiptFooter`.

## Deviations
- Existing English receipt HTML labels translated to Indonesian (no test asserts HTML).
- Footer length >200 after trim -> InvalidOperationException("Footer struk maksimal 200 karakter.") (from W1 entity).

## Tests that break / to add (W6)
- Anything calling `FnbReceiptMapper.receipt(payment, items, options)` (3-arg) — grep tests; none found in unit tests at write time.
- Settings tests building request/audit JSON string equality: audit details gained `,"receiptFooter":null`.
- New: receipt for REFUNDED order (refunded=true, marker, reason), CANCELLED/UNPAID 404, footer set/clear/too long (201 chars -> 400/422), HTML escaping of footer/orderNote/customerLabel.
