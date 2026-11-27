# TASK-117 J1 report (java-worker)

Status: DONE (code written), not compiled. Baseline `545ac38` (dirty). Test status: NOT RUN (orchestrator runs GREEN).

## Changed files
New: `db/migration/V20__payment_attempt_url.sql`, `service/{PaymentGateway (renamed from MockPaymentGateway),MidtransClient,MidtransWebhookService}.java`, `configuration/MidtransProperties.java`, `controller/MidtransWebhookController.java`, `dto/response/MidtransStatus.java`, `exception/runtime/PaymentGatewayUnavailableException.java`.
Modified: `PlatformBillingService` (pay, newAttempt helper), `PlatformBillingSettlementService`, `MerchantSubscriptionService`, `PlatformDashboardService`, `PlatformTenantAdminService` (rename usage), `MerchantSubscriptionController` (/pay), `PlatformInvoiceMapper`, `PlatformInvoiceResponse`, `PlatformPaymentAttemptEntity`, `GlobalExceptionHandler` (503), `SecurityConfiguration` (permitAll POST webhook), `application.properties`, `.env.example`, `docker-compose.yml`, `docs/ops/plan-activation.md` (section 9). Deleted: `MockPaymentGateway.java`.

## Deviations (please review)
1. Files outside the reserved list, required by T1's binding signatures and by compile: `repository/PlatformBillingRepository.java` (`attemptByProviderRef`, `gatewayEventExists`) and `entity/shared/billing/GatewayEventEntity.java` (`reject()` mutator). Minimal additions only.
2. `PlatformPaymentAttemptEntity` also got `assignProviderPaymentRef(String)` (id exists only after persist; ref = id).
3. `PaymentGateway.newAttempt` has 5 args (adds customerEmail, customerName, needed for Snap customer_details); `PlatformBillingService.newAttempt` reads the owner via `billing.actor(userId)`, null-safe.
4. Two constructors on `MidtransClient` and `MidtransWebhookService` (Spring one + the test seam), like `DailySummaryClient`. The test passes a Jackson 2 `com.fasterxml.jackson.databind.ObjectMapper`, but the app's bean is Jackson 3, so the Spring constructor builds its own Jackson 2 reader.
5. Snap `expiry` has `unit` and `duration` only; `start_time` omitted (optional at Midtrans, defaults to now) to avoid a timestamp-format rejection.
6. `paymentUrl` is exposed only while the invoice is ISSUED and the latest attempt is PENDING (a dead URL of a failed attempt is hidden), slightly stricter than the contract text.
7. `pay` reuses an open MIDTRANS attempt (no second Snap call) and creates a new one only when none is pending; an expired/zero-minute invoice gets 409 via lazy expiry.
8. `pendingOrNewAttempt` for MIDTRANS (late payment on a closed attempt) creates an attempt without a Snap call.
9. Webhook persists the gateway event once at the end of `handle` (with final PROCESSED/REJECTED status); the duplicate check is `gatewayEventExists` before processing. Concurrent identical webhooks are saved by the unique constraint (second gets 500, Midtrans retries, then no-op).

## Contract points done
D1-D9 as above: signature constant-time (`MessageDigest.isEqual`), status re-read before settle, amount check before settle, server key only from env, never logged/returned; startup `IllegalStateException` for MIDTRANS with blank key/merchant/finish URL or an unknown provider value.

## Existing tests that may need updating
None known beyond the T1 edits. Integration tests booting the context use default provider MOCK.

## Open questions
None. Risk: Jackson 3 `JsonNode.asText()` used in `MidtransClient` (compiles in existing tests; may be deprecated).
