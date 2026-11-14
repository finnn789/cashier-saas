# TASK-078 W2 — delete the mock gateway (report saved by the orchestrator from the worker's hand-back)

Worker could not create report files in its session; content below is its hand-back, condensed. Orchestrator check: `mvnw -B -DskipTests compile` OK after wave 1 (2026-11-14).

## Deleted (`src/main/java/org/lite/project/cashiersaas/`)
- service/MockFnbGatewayService.java, entity/fnb/FnbGatewayAttemptEntity.java, repository/FnbGatewayAttemptRepository.java, enums/FnbGatewayOutcome.java, dto/request/FnbMockPaymentResolutionRequest.java

## Edited
- enums/PaymentMethod: `CASH, MANUAL_NON_CASH`.
- controller/FnbPaymentController: resolve route and mockGateway field removed.
- service/FnbPaymentService: attempts/mockGateway dependencies, MOCK delegation, keyed-attempt lookup and pending-attempt check removed; `pay()` has `String methodLabel = null` for W3; `settle(...)` takes `methodLabel` instead of `reference` and uses W1's `PaymentEntity.settle` signature. Replay, already-paid check, cash/non-cash validation and shift requirement unchanged.
- service/FnbPosOrderService, FnbDiscountService, FnbOrderCancellationService: attempts field and pending-attempt checks removed.
- dto/response/FnbPaymentResponse: dropped provider, attemptId, gatewayOutcome, providerReference, amountRequested.
- dto/FnbPaymentMapper: attempt/mockPayment/mockMetadata removed. dto/FnbReceiptMapper: mock block and "SIMULASI PEMBAYARAN (MOCK)" removed.
- application.properties, application-demo.properties, .env.example, docker-compose.yml: mock flag removed.

## Leftovers
- Allowed: AuditLogEntity FNB_GATEWAY_ATTEMPT_CREATED/RESOLVED (historical rows), V1, V8 data update.
- For W5: dto/response/FnbReceiptResponse still has dead fields simulatedPayment, provider, providerReference.

## Tests to fix (W6)
- Delete unit/service/MockFnbGatewayServiceTest.java.
- unit/service/FnbOrderCancellationServiceTest.java (attempts mock/stub), unit/service/FnbPosOrderServiceTest.java (attempt imports, mock, pending-attempt test).
- integration/fnb/FnbInventoryFlowIntegrationTest.java (~397-419) and FnbShiftRefundIntegrationTest.java (~368-383): mock-gateway flows.
- src/test/resources/application-test.properties: remove `app.payment.mock-gateway.enabled=true`.
- Non-cash payments need a labelId after W3.

## For W7
- README.md lines ~25, 74, 142, 148, 200, 207 mention the mock gateway / CASHIER_MOCK_GATEWAY_ENABLED; docs and Postman not checked.
