# TASK-117: Midtrans (Snap, sandbox-first) for subscription and AI top-up invoices

Status: DONE 2026-11-27 (uncommitted, not run against Midtrans sandbox). GREEN: 176 Java unit tests (Midtrans*, Platform*, MerchantSubscription*, SecurityConfiguration*, *Insight*), UI `tsc -b` 0, oxlint clean on touched files. Orchestrator fixes: PlatformBillingServiceTest strict stub for invoice persist; UI `Date.now()` in render -> `useState` initializer. J1 deviations (accepted): edited `PlatformBillingRepository`, `GatewayEventEntity` outside reserved paths; `newAttempt` takes customer email/name; `paymentUrl` only while attempt PENDING. V20 not applied to a DB. Branch `feature/leakage-report`. Baseline: `545ac38` (dirty: TASK-114/116, uncommitted).

## User instruction (2026-11-27)
"Buatkan setup untuk Midtrans untuk Subscription." Existing flow (TASK-105/106): owner checkout creates PENDING subscription row + ISSUED invoice (24h) + payment attempt; `PlatformBillingSettlementService.settle(invoiceId, providerPaymentRef, actor, reason)` is the single, idempotent settlement entry; `gateway_events` has a UNIQUE (provider_code, provider_account_ref, provider_event_id). This task adds Midtrans as the real provider behind that flow.

## Global rules (every worker)
- Reports: `reports/TASK-117-<id>.md`.
- Next Flyway: `V20__payment_attempt_url.sql`.
- Secrets: Midtrans server key only from env `MIDTRANS_SERVER_KEY`; never logged, never returned by any API, never in tests (use `"SB-Mid-server-test"` style fakes). No client key (D1 needs none).
- Reuse: `DailySummaryClient` (`RestClient` + `java.net.http.HttpClient`, timeouts) as the HTTP pattern; `@ConfigurationProperties` like `RateLimitProperties`/`MediaProperties`; `settle`/`fail` in `PlatformBillingSettlementService`; `GatewayEventEntity`; `DigestService`; `AuditLogEntity.recordAction`; `GlobalExceptionHandler`.
- No new dependency (SHA-512 via `java.security.MessageDigest`, constant-time compare via `MessageDigest.isEqual`).
- Out of scope: automatic recurring charge (Midtrans Subscription API / card tokenisation), refunds, Snap.js popup, QRIS for merchant POS sales (PRD non-goal), multi-currency.

## Orchestrator design decisions (reversible, user may override)
- D1 Snap **redirect**: checkout calls `POST {snapBaseUrl}/snap/v1/transactions` (Basic auth `serverKey:`), body `transaction_details{order_id, gross_amount}`, `item_details` (one line: invoice number, amount), `customer_details{email, first_name}` of the owner, `expiry{start_time, unit:"minute", duration}` = minutes until `invoice.dueAt` (min 1), `callbacks.finish` = `cashier.billing.midtrans.finish-url`. Response `redirect_url` is stored on the attempt and returned as `paymentUrl`. Renewal is a new checkout each period (no auto-debit).
- D2 `order_id` = attempt id (UUID string, 36 chars, unique per attempt) = attempt `provider_payment_ref`. Provider code `MIDTRANS`, account ref = `cashier.billing.midtrans.merchant-id`.
- D3 `gross_amount` = `invoice.amountDue` as integer IDR. If `amountDue` has a non-zero fraction -> `IllegalStateException` (prices are whole rupiah; fail loudly).
- D4 Provider switch: property `cashier.billing.provider` = `MOCK | MANUAL_TRANSFER | MIDTRANS` (env `CASHIER_BILLING_PROVIDER`, default `MOCK`). It replaces `cashier.billing.mock-gateway.enabled` / `CASHIER_BILLING_MOCK_ENABLED` (remove them). Startup fails (`IllegalStateException` in the properties/gateway constructor) when provider is `MIDTRANS` and server key, merchant id or finish URL is blank.
- D5 Rename `MockPaymentGateway` -> `PaymentGateway` (same package), `isEnabled()` -> `isMockEnabled()`. Keep the existing MOCK/MANUAL behaviour byte-for-byte. Outbound HTTP lives in a new `MidtransClient` (`service/`), the only class that talks to Midtrans. No interface (one real provider): `shortcut: provider switch inside PaymentGateway, extract an interface when a second real provider is added`.
- D6 Snap call failure (timeout, non-2xx, missing redirect_url) during checkout: throw `PaymentGatewayUnavailableException` (new, `shared/exception/runtime`) -> 503 "Pembayaran sedang tidak tersedia, coba lagi." in `GlobalExceptionHandler`; the whole checkout transaction rolls back (no orphan invoice). Timeouts: connect 5 s, read 10 s.
- D7 Existing ISSUED invoice whose latest attempt is MIDTRANS: `GET invoice`/`list` return its stored `paymentUrl`; no new Snap call. A failed/expired Midtrans attempt + invoice still ISSUED: `POST /api/v1/merchant/subscription/invoices/{invoiceId}/pay` (new, owner) creates a new attempt + Snap transaction and returns the invoice with the new `paymentUrl`. 409 when the invoice is not ISSUED, 404 when the provider is not MIDTRANS.
- D8 Webhook `POST /api/v1/webhooks/midtrans` (permitAll, no CSRF needed — CSRF is only enforced on refresh/logout; no JWT). Steps, in order:
  1. Parse JSON; missing `order_id`/`status_code`/`gross_amount`/`signature_key`/`transaction_status` -> 400.
  2. `signature_key == sha512(order_id + status_code + gross_amount + serverKey)` (hex, lowercase), constant-time; mismatch -> 401, nothing stored.
  3. Re-read the truth: `GET {apiBaseUrl}/v2/{order_id}/status` (Basic auth) and use ITS `transaction_status`, `fraud_status`, `gross_amount`, `transaction_id`. Status call failure -> 503 (Midtrans retries).
  4. Unknown `order_id` (no attempt with provider MIDTRANS and that ref) -> 200 and log warn (stop retries for foreign orders).
  5. Store one `gateway_events` row: provider `MIDTRANS`, event id `transaction_id + ":" + transaction_status`, payload digest = `DigestService.digest(raw body)`; duplicate event id -> 200, no further action (idempotent).
  6. Amount check: status `gross_amount` (e.g. `"150000.00"`) must equal `invoice.amountDue`; mismatch -> event `processing_status=REJECTED`, audit, 200, no settlement.
  7. Map: `settlement`, or `capture` with `fraud_status` `accept` -> `settle(invoiceId, orderId, null, "midtrans")`; `deny|cancel|expire|failure` -> `fail(invoiceId, null)` (only if that attempt is still PENDING); `pending`, `capture`+`challenge` -> nothing. `settle` on an already PAID invoice with the same ref returns unchanged (already idempotent). `OperationConflictException` from settle/fail (invoice expired/cancelled meanwhile) -> 200, event processing_status `REJECTED`, audit `PLATFORM_PAYMENT_FAILED` with reason, log error (operator must refund manually).
  8. Always 200 for handled cases so Midtrans stops retrying.
- D9 Config (`MidtransProperties`, prefix `cashier.billing.midtrans`): `server-key` (`MIDTRANS_SERVER_KEY`), `merchant-id` (`MIDTRANS_MERCHANT_ID`), `production` (`MIDTRANS_PRODUCTION`, default false), `finish-url` (`MIDTRANS_FINISH_URL`, e.g. `http://localhost:5173/subscription`). Base URLs derived: sandbox `https://app.sandbox.midtrans.com` / `https://api.sandbox.midtrans.com`, production `https://app.midtrans.com` / `https://api.midtrans.com`.
- D10 UI: when `invoice.paymentUrl` is set and status ISSUED, show a "Bayar sekarang" button that opens it (same tab). On return to `/subscription` (finish URL), the page refetches invoices; while an ISSUED Midtrans invoice is open, poll the invoice every 5 s for up to 2 min (React Query `refetchInterval`), then stop. Mock buttons stay for `mockPaymentEnabled`; the manual-transfer text stays for `providerCode === 'MANUAL_TRANSFER'`.

## Contracts shared between workers
- `PlatformInvoiceResponse` gains `String paymentUrl` (null unless latest attempt is MIDTRANS and has a URL). `mockPaymentEnabled` unchanged in meaning. `providerCode` may now be `MIDTRANS`.
- `MerchantSubscriptionResponse.mockPaymentEnabled` unchanged.
- `POST /api/v1/merchant/subscription/invoices/{invoiceId}/pay` -> `ApiResponse<PlatformInvoiceResponse>` (D7).
- `POST /api/v1/webhooks/midtrans` -> 200 `ApiResponse` (any body) / 400 / 401 / 503 (D8).
- Flyway `V20__payment_attempt_url.sql`: `ALTER TABLE public.platform_payment_attempts ADD COLUMN payment_url varchar(500);`
- `PlatformPaymentAttemptEntity`: field `paymentUrl` + setter-like method `attachPaymentUrl(String)`.
- `MidtransClient`: `String createSnapRedirect(PlatformInvoiceEntity invoice, PlatformPaymentAttemptEntity attempt, String customerEmail, String customerName)` (returns redirect_url, throws `PaymentGatewayUnavailableException`); `MidtransStatus status(String orderId)` (DTO class `MidtransStatus` in `dto/response/`: `transactionStatus, fraudStatus, grossAmount (BigDecimal), transactionId, orderId`); `boolean validSignature(String orderId, String statusCode, String grossAmount, String signatureKey)`.
- `PaymentGateway`: `isMockEnabled()`, `isMidtrans()`, `newAttempt(...)` (MIDTRANS branch: persist attempt, call `createSnapRedirect`, `attachPaymentUrl`), `pendingOrNewAttempt(...)` unchanged, `recordEvent(...)` unchanged for MOCK.
- New `MidtransWebhookService.handle(String rawBody)` (D8) called by new `MidtransWebhookController`.

- **Binding extra signatures fixed by T1's tests: see `reports/TASK-117-T1.md` section "Production signatures assumed" (constructors of `MidtransClient(MidtransProperties, RestClient)`, `PaymentGateway(PlatformBillingRepository, DigestService, MidtransClient, MidtransProperties, String provider)`, `MidtransWebhookService(...)`, repository methods `attemptByProviderRef`, `gatewayEventExists`, exceptions `InvalidOperationException` 400 / `AuthenticationRequiredException` 401). J1 must match them exactly.**

## Wave 0 — RED (parallel)

### T1 — Java tests (`test-worker`)
Reserved:
- `src/test/java/org/lite/project/cashiersaas/unit/service/MidtransClientTest.java` (new; signature check with a known vector computed in the test from the D8 formula; base-URL selection; integer gross_amount; fraction -> IllegalStateException. Use `MockRestServiceServer` or a stubbed `RestClient` like `DailySummaryClient` tests, if any; otherwise construct with a fake base URL and test only pure methods)
- `src/test/java/org/lite/project/cashiersaas/unit/service/MidtransWebhookServiceTest.java` (new; mocks for client/billing/settlement)
- `src/test/java/org/lite/project/cashiersaas/unit/service/PlatformBillingServiceTest.java`, `PlatformBillingSettlementServiceTest.java`, `PlatformDashboardServiceTest.java`, `PlatformTenantAdminServiceTest.java` (rename `MockPaymentGateway` -> `PaymentGateway`, `isEnabled` -> `isMockEnabled` only; plus in `PlatformBillingServiceTest`: MIDTRANS checkout returns `paymentUrl`; Snap failure -> `PaymentGatewayUnavailableException`; `pay` endpoint rules D7)

## Wave 1 — GREEN (parallel, after RED; never edit Wave 0 tests)

### J1 — backend (`java-worker`)
Reserved:
- `src/main/resources/db/migration/V20__payment_attempt_url.sql` (new)
- `src/main/java/org/lite/project/cashiersaas/service/MockPaymentGateway.java` -> rename to `PaymentGateway.java`
- `src/main/java/org/lite/project/cashiersaas/service/{MidtransClient,MidtransWebhookService}.java` (new)
- `src/main/java/org/lite/project/cashiersaas/configuration/MidtransProperties.java` (new)
- `src/main/java/org/lite/project/cashiersaas/controller/MidtransWebhookController.java` (new), `MerchantSubscriptionController.java` (`/pay` only)
- `src/main/java/org/lite/project/cashiersaas/dto/response/{MidtransStatus,PlatformInvoiceResponse}.java`, `dto/PlatformInvoiceMapper.java`
- `src/main/java/org/lite/project/cashiersaas/entity/shared/billing/PlatformPaymentAttemptEntity.java`
- `src/main/java/org/lite/project/cashiersaas/service/{PlatformBillingService,PlatformBillingSettlementService,MerchantSubscriptionService,PlatformDashboardService,PlatformTenantAdminService}.java` (rename usage; `PlatformBillingService.pay` for D7; settlement unchanged otherwise)
- `src/main/java/org/lite/project/cashiersaas/shared/exception/runtime/PaymentGatewayUnavailableException.java` (new), `exception/GlobalExceptionHandler.java` (503 only)
- `src/main/java/org/lite/project/cashiersaas/configuration/SecurityConfiguration.java` (permitAll `POST /api/v1/webhooks/midtrans` only)
- `src/main/resources/application.properties` (D4/D9 properties), `.env.example` and `docker-compose.yml` (backend env: `CASHIER_BILLING_PROVIDER`, `MIDTRANS_*` with empty defaults; remove `CASHIER_BILLING_MOCK_ENABLED` if present)
- `docs/ops/plan-activation.md` (one short section: Midtrans sandbox setup — dashboard notification URL `https://<public-host>/api/v1/webhooks/midtrans`, finish URL, env vars, how to test locally with a tunnel; mark "belum ada integrasi" line as outdated)
Acceptance: T1 green; `./mvnw -q -B compile` clean.

### F1 — UI (`fe-worker`, repo `E:\Java\cashier-saas-ui`)
Reserved:
- `src/api/billing.api.ts` (`paymentUrl`, `providerCode` union adds `'MIDTRANS'`, `usePayInvoiceMutation` for `/pay`, `refetchInterval` per D10)
- `src/api/mock/billing.mock.ts` (type follow-up only)
- `src/pages/subscription/InvoicePanel.tsx`, `src/pages/subscription/CheckoutModal.tsx` (D10; after checkout with a `paymentUrl`, offer "Bayar sekarang")
- `src/language/id.ts`, `src/language/en.ts` (new strings only)
Acceptance: `npx tsc -b` clean, oxlint clean.

## Test cases (TDD)
- Signature valid/invalid (case: tampered gross_amount) -> true/false.
- Base URLs: production=false -> sandbox hosts; true -> production hosts.
- Checkout (MIDTRANS): attempt provider `MIDTRANS`, ref = attempt id, `paymentUrl` = stubbed redirect_url; Snap error -> `PaymentGatewayUnavailableException`, nothing persisted beyond the rolled-back tx (assert client called and exception propagates).
- Webhook: bad signature -> 401 and no event stored; unknown order -> 200 no settle; status `settlement` -> `settle(invoiceId, orderId, null, "midtrans")` once; same event twice -> second is a no-op; `capture`+`accept` -> settle; `capture`+`challenge` -> nothing; `expire` -> `fail`; amount mismatch -> no settle, event REJECTED; settle throws `OperationConflictException` -> handled, 200, REJECTED.
- `pay`: invoice PAID -> 409; provider MOCK -> 404; ISSUED + failed Midtrans attempt -> new attempt with new URL.
- RED evidence: `./mvnw -q test -Dtest=MidtransClientTest,MidtransWebhookServiceTest,PlatformBilling*Test` fails to compile.
- GREEN evidence: same passes; `./mvnw -q test -Dtest=Platform*Test,Merchant*Subscription*Test` passes; UI `npx tsc -b` clean.
