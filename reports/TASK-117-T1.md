# TASK-117 T1 report (test-worker)

Test status: NOT RUN (orchestrator runs RED). Expected RED reason for every test: compile failure (PaymentGateway, MidtransClient, MidtransProperties, MidtransStatus, MidtransWebhookService, PaymentGatewayUnavailableException, PlatformBillingService.pay, billing repo methods do not exist yet).

## Behaviours covered -> test
MidtransClientTest
- signature = sha512(orderId+statusCode+grossAmount+serverKey), tampered amount/garbage rejected -> signatureIsSha512OfOrderIdStatusCodeGrossAmountAndServerKeyAndRejectsATamperedAmount
- sandbox vs production host, Basic auth, order_id, integer gross_amount, customer, callbacks.finish, expiry minutes -> snapRedirectIsRequestedFromTheSandboxOrProductionHostWithBasicAuthAndAnIntegerAmount (parameterized)
- fraction -> IllegalStateException, no HTTP call -> anAmountWithAFractionFailsLoudlyWithoutCallingMidtrans
- 5xx / 4xx / IO / missing redirect_url -> PaymentGatewayUnavailableException -> aFailedSnapCallIsReportedAsGatewayUnavailable (parameterized)
- status() parsing + API host + Basic auth -> statusReadsTheProviderTruthFromTheApiHostWithBasicAuth
- status() failure -> aFailedStatusCallIsReportedAsGatewayUnavailable

MidtransWebhookServiceTest
- missing required field -> 400 (InvalidOperationException), nothing called -> aBodyMissingARequiredFieldIsABadRequestAndNothingIsCalled (parameterized x5)
- bad signature -> 401 (AuthenticationRequiredException), no status call/event -> aBadSignatureIsUnauthorizedAndNothingIsStoredOrSettled
- status call failure propagates (503) -> aFailedStatusCallPropagatesSoMidtransRetries
- unknown order -> ack, no event/settle -> anUnknownOrderIsAcknowledgedWithoutStoringOrSettling
- settlement -> settle once; event provider/id/digest -> settlementSettlesTheInvoiceOnceAndStoresTheEventWithTheRawBodyDigest
- duplicate event -> no-op -> aRepeatedEventIsAcknowledgedAsANoOp
- capture+accept settles; capture+challenge, pending nothing -> captureIsSettledOnlyWhenFraudAcceptedAndPendingChangesNothing
- deny/cancel/expire/failure -> fail(invoiceId,null) -> aTerminalFailureFailsThePendingAttempt
- failure for non-pending attempt ignored -> aFailureForAnAttemptThatIsNoLongerPendingIsIgnored
- amount mismatch -> REJECTED + audit, no settle -> anAmountThatDiffersFromTheInvoiceIsRejectedAuditedAndNeverSettled
- OperationConflictException from settle -> REJECTED + PLATFORM_PAYMENT_FAILED audit, no throw -> aSettlementConflictIsAcknowledgedRecordedAsRejectedAndAudited

PlatformBillingServiceTest (new tests)
- midtransCheckoutUsesTheAttemptIdAsOrderIdAndReturnsTheSnapRedirectUrl
- aFailedSnapCallFailsTheCheckoutSoNothingIsKept
- payOnAFailedMidtransAttemptOpensANewAttemptWithANewSnapUrl
- payOnAnInvoiceThatIsNotIssuedIsAConflict
- payIsNotFoundWhenTheProviderIsNotMidtrans

## Existing tests modified
PlatformBillingServiceTest, PlatformBillingSettlementServiceTest, PlatformDashboardServiceTest, PlatformTenantAdminServiceTest: MockPaymentGateway -> PaymentGateway; gateway construction in the first two uses the new constructor (see assumptions). No `isEnabled` usage existed in these tests, so no `isMockEnabled` call was needed.

## Skipped (already covered)
Mock/manual checkout, settle/fail behaviour: existing tests unchanged.

## Assumed production signatures (java-worker must match)
- `MidtransProperties`: no-arg constructor + setters `setServerKey`, `setMerchantId`, `setFinishUrl`, `setProduction(boolean)` (package `configuration`). Base URLs not accessed directly by tests.
- `MidtransClient(MidtransProperties, RestClient)` public constructor (test seam like DailySummaryClient); fixed absolute URLs `https://app[.sandbox].midtrans.com/snap/v1/transactions`, `https://api[.sandbox].midtrans.com/v2/{orderId}/status`; header `Authorization: Basic base64(serverKey + ":")`; snap body JSON keys per D1 (`item_details[0].price`, `expiry.unit="minute"`, `expiry.duration`); `status()` and `createSnapRedirect()` throw `PaymentGatewayUnavailableException` on 4xx/5xx/IO/missing redirect_url; `validSignature` is an instance method.
- `MidtransStatus` (`dto.response`): no-arg constructor with setters `setTransactionStatus/FraudStatus/GrossAmount(BigDecimal)/TransactionId/OrderId` and matching getters.
- `PaymentGatewayUnavailableException(String message)` constructor.
- `PaymentGateway(PlatformBillingRepository, DigestService, MidtransClient, MidtransProperties, String provider)` constructor, provider values `"MOCK" | "MANUAL_TRANSFER" | "MIDTRANS"`; with `"MIDTRANS"` account ref = `properties.getMerchantId()`, attempt `providerPaymentRef` = attempt id string (id is assigned on `billing.persist(attempt)`, so the ref is set after persist), `attachPaymentUrl` then called with the redirect URL.
- `PlatformPaymentAttemptEntity.getPaymentUrl()`; `PlatformInvoiceResponse.getPaymentUrl()`.
- `PlatformBillingService.pay(TenantPrincipal principal, UUID invoiceId)` returns `PlatformInvoiceResponse`; order of checks: provider not MIDTRANS -> `ResourceNotFoundException` (before any repository read), then `billing.invoice(tenantId, invoiceId)`, not ISSUED -> `OperationConflictException`; the returned response is built from the NEW attempt (not from `billing.latestAttempts`, which is unstubbed/empty in the test). Customer email/name for Snap are not asserted (test uses `any()`).
- `MidtransWebhookService(MidtransClient, PlatformBillingRepository, PlatformBillingSettlementService, DigestService, AuditLogRepository, MidtransProperties, ObjectMapper)`; `void handle(String rawBody)`. Errors: missing field -> `InvalidOperationException`; bad signature -> `AuthenticationRequiredException`; status failure -> `PaymentGatewayUnavailableException` propagated. Step order as D8 (status call happens before unknown-order lookup).
- `PlatformBillingRepository.attemptByProviderRef(String providerCode, String providerPaymentRef)` returns `Optional<PlatformPaymentAttemptEntity>`.
- `PlatformBillingRepository.gatewayEventExists(String providerCode, String providerAccountRef, String providerEventId)` returns boolean; account ref = `MidtransProperties.getMerchantId()`.
- Gateway event stored with exactly one `billing.persist(GatewayEventEntity)` per handled event (also for REJECTED, whose `getProcessingStatus()` is `"REJECTED"` at the end of `handle`); `getProviderCode()=="MIDTRANS"`, `getProviderEventId()==transactionId + ":" + transactionStatus`, `getPayloadDigest()==digests.digest(rawBody)`. Needs a new factory on `GatewayEventEntity` for REJECTED (name free; test does not call it).
- Amount mismatch and settle conflict each persist exactly one `AuditLogEntity` via `audit.persist`; conflict audit action `PLATFORM_PAYMENT_FAILED`.
- `PlatformBillingSettlementService` constructor unchanged except the gateway type; `settle(invoiceId, orderId, null, "midtrans")` and `fail(invoiceId, null)` called by the webhook with exactly these arguments.

## Open questions
- If `pay` must re-read the latest attempt from `billing.latestAttempts`, the pay test needs a stub for it.
