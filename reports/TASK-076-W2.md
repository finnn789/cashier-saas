# TASK-076 W2 report (C3 mock gateway flag, C4 cancel rule, C5 checkout audit)

Not compiled, no Maven/tests run (policy).

## Changed files
- `src/main/java/org/lite/project/cashiersaas/service/MockFnbGatewayService.java`
- `src/main/java/org/lite/project/cashiersaas/service/FnbOrderCancellationService.java`
- `src/main/java/org/lite/project/cashiersaas/service/FnbPosOrderService.java`
- `src/main/resources/application.properties`
- `src/main/resources/application-demo.properties`
- `.env.example`
- `docker-compose.yml`

## Contract points
- C3: `@Value("${app.payment.mock-gateway.enabled:false}") private boolean enabled;` in `MockFnbGatewayService` (field). `initiate` first line: `if (!enabled) throw new InvalidOperationException("Metode pembayaran tidak tersedia.")`, before `lockTenant`. `resolve` first line: `if (!enabled) throw new ResourceNotFoundException()`. `FnbPaymentService.pay` already delegates MOCK_GATEWAY to `initiate`, so it is covered with no change. Property `app.payment.mock-gateway.enabled=${CASHIER_MOCK_GATEWAY_ENABLED:false}` in application.properties (after refresh-cookie-secure); `=true` in application-demo.properties; `CASHIER_MOCK_GATEWAY_ENABLED=false` documented in `.env.example`; `CASHIER_MOCK_GATEWAY_ENABLED: ${CASHIER_MOCK_GATEWAY_ENABLED:-false}` in docker-compose.yml backend env. (Compose runs with `SPRING_PROFILES_ACTIVE=demo`, and the demo profile's literal `true` overrides the application.properties placeholder, so the compose env var has no effect while the demo profile is active; it only matters if the profile is changed.)
- C4: `FnbOrderCancellationService.cancel`, after the pending-attempt check and before `order.cancel`: if `context.getMembership().getRole() == Role.CASHIER` and `discounts.byOrder(tenantId, outletId, orderId).isPresent()` then `AccessForbiddenException("Pesanan dengan diskon hanya dapat dibatalkan oleh owner.")`. Injected `FnbDiscountRepository discounts` (new final field).
- C5: `FnbPosOrderService.checkout`, after `persistLines` + `orders.flush()` and before `return response(order)` (the idempotent replay returns earlier, so no audit there): `AuditLogEntity.Action.FNB_ORDER_CREATED`, entityId = order id, reason null, details `"orderNumber=" + number + " total=" + order.getTotal().toPlainString()`. Depends on W1 adding `FNB_ORDER_CREATED` to `AuditLogEntity.Action` (not present at the time of writing; compile fails until W1 lands).

## Deviations
- `AccessForbiddenException` already has a `(String message)` constructor: used as is, no deviation.
- Order of C4 checks: the pending gateway attempt conflict (409) is evaluated before the cashier/discount 403 (the 403 is placed right before `order.cancel` as per contract). A cashier cancelling a discounted order with a pending attempt gets 409, not 403.
- C4 is read from the membership role of the guard context (not the JWT roles), as the packet says.

## Existing tests likely to break (for W5)
- `FnbInventoryFlowIntegrationTest` lines ~395 and ~412 use `MOCK_GATEWAY`: need `app.payment.mock-gateway.enabled=true` in `src/test/resources/application-test.properties` (W5). No other test references MOCK_GATEWAY / the two classes.
- Add tests: mock gateway disabled -> 400 on pay with MOCK_GATEWAY, 404 on resolve; cashier cancel of discounted order -> 403, owner cancel OK; checkout writes one FNB_ORDER_CREATED audit row and idempotent replay writes none.
- `FnbPosOrderServiceTest` has no `checkout` call (only `replaceItems`); no break expected. `FnbStockConcurrencyIntegrationTest` cancels with cashier on non-discounted orders: unaffected.
- Any test that counts audit rows after POS checkout will see one extra FNB_ORDER_CREATED row.
- No unit test constructs `FnbOrderCancellationService` / `MockFnbGatewayService` directly (grep), so the new constructor field and `@Value` field do not break wiring; a pure Mockito unit test of the gateway would need `ReflectionTestUtils.setField(service, "enabled", true)`.
