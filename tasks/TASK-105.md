# TASK-105: Self-service subscription checkout with a MOCK payment gateway

Status: DELEGATED (wave 1: J1 + F1 in parallel). Memory version: 102. Branch `feature/leakage-report`. Baseline commit: `ded6050` (dirty: TASK-107 Python test fix only).

## User instruction (2026-11-25)
"buatkan TASK nya untuk PAYMENT GATEWAY, TAPI yang MockUP jangan Terintegrasi dengan Payment Gateway, terus untuk endpoint itu buatkan juga" — the endpoints = checkout, upgrade, cancel. No real provider (no Midtrans/Xendit, no outbound HTTP).

## Global rules (every worker)
- Reports: `reports/TASK-105-<id>.md`.
- Next migration: `V18__platform_billing_checkout.sql` (V17 is the latest).
- Reuse: `MembershipPlanCatalogService` (prices, `OUTLET_ADDON_MONTHLY`), `PlanEntitlementService` (`effectivePlan`, `allowedOutletCount`), `SubscriptionRepository.findActiveAt`, `MerchantAccessGuard.requireOwnerIgnoringAllowance`, `FnbOrderRepository.lockTenant(UUID)` (tenant pessimistic lock), `DigestService.digest(String)`, `AiUsageRepository` (add one increment method for bonus questions), `AuditLogRepository`, `ApiResponse`, existing exceptions (`OperationConflictException` 409, `ResourceNotFoundException` 404, validation 400). Idempotency header convention: `@RequestHeader(value = "Idempotency-Key", required = false)` as in `FnbRefundController`.

## Verified facts (source read 2026-11-25)
- Plans `enums/PlanCode { FREETIER, PRO, PREMIUM }`. Prices (IDR) in `MembershipPlanCatalogService`: PRO 59.000/month, 590.000/year; PREMIUM 119.000/month, 1.190.000/year; outlet add-on 49.000/outlet/month. PRD: AI top-up +200 questions for Rp29.000, Premium only, valid until the end of the tenant-zone month.
- `subscriptions` (V1+V11+V12): id, tenant_id, plan_code (CHECK FREETIER/PRO/PREMIUM), status varchar(20) (no CHECK; values used: `ACTIVE`), starts_at, ends_at, extra_outlets int (CHECK >=0, 0 on FREETIER), version, created_at, updated_at. Every tenant has one open-ended FREETIER ACTIVE row forever. Effective plan = highest row active now (`status ACTIVE`, `starts_at <= now`, `ends_at null or > now`) or PREMIUM while the 14-day trial runs (`tenants.premium_trial_started_at`). A row without `starts_at` is never active. Expiry is lazy (no scheduler). Allowed outlets = 1 + sum of `extra_outlets` of active paid rows. `SubscriptionEntity` has only `activateFreeTier`, `getPlan`, `isActiveAt` (no setters).
- Unused billing tables from V1 with entities in `entity/shared/billing`:
  - `platform_invoices` (`PlatformInvoiceEntity`): id, tenant_id, subscription_id NOT NULL FK subscriptions, invoice_number UNIQUE varchar(50), currency_code, amount_due numeric(19,2), status varchar(16), due_at, issued_at NOT NULL, paid_at, created_at.
  - `platform_payment_attempts` (`PlatformPaymentAttemptEntity`): id, tenant_id, platform_invoice_id FK, provider_code varchar(50), provider_account_ref varchar(120), provider_payment_ref varchar(160), amount, currency_code, status varchar(20), idempotency_key varchar(128), request_fingerprint varchar(64), version; UNIQUE (tenant_id, idempotency_key).
  - `gateway_events` (`GatewayEventEntity`): provider_code, provider_account_ref, provider_event_id (UNIQUE triple), provider_payment_ref, normalized_amount, currency_code, normalized_status, matched_attempt_id, event_type, payload_digest varchar(64), received_at, processed_at, processing_status.
- `audit_logs`: tenant_id NOT NULL, actor_staff_user_id, entity_id NOT NULL, entity_type, action, reason, details, outlet_id nullable.
- Existing owner endpoint `GET /api/v1/merchant/subscription` (`MerchantSubscriptionController` -> `MerchantSubscriptionService.get`, response `MerchantSubscriptionResponse`: planCode, activeUntil, trial{active,startedAt,endsAt,eligible}, outlets{allowed,active,extra}, ai (PREMIUM only), outletAddOnMonthlyPrice). `/api/v1/merchant/**` is OWNERMERCHANT only (cashier gets 404/403 by security). Public `GET /api/v1/public/plans` (`MembershipPlanCatalogController`).
- Ops today: operator activates plans with SQL (`docs/ops/plan-activation.md`); that runbook stays valid.

## Orchestrator design decisions (reversible, user may override)
- D1 Mock only. Provider code `MOCK`, account ref `mock`, payment ref `mock_<uuid>`. No outbound calls. Property `cashier.billing.mock-gateway.enabled` (env `CASHIER_BILLING_MOCK_ENABLED`, default `true`; production sets false). When false, the mock-pay endpoint answers 404, the checkout attempt is created with provider code `MANUAL_TRANSFER` (account ref `manual`) instead of `MOCK`, and invoices are settled only by a SUPERADMIN (TASK-106, manual transfer, passing the transfer reference as `providerPaymentRef`). One settlement method is the single entry point for both, so a real gateway webhook can call it later. No Strategy/interface yet (one implementation): `shortcut: single MockPaymentGateway class, extract an interface when the first real provider is added`.
- D2 Subscription rows get status `PENDING` (created with the invoice, starts_at/ends_at null, ignored by `findActiveAt`), `ACTIVE` (on payment, with dates) and `CANCELLED`. V18 adds CHECK status IN ('PENDING','ACTIVE','CANCELLED').
- D3 One start-date rule covers new, renew, upgrade and downgrade: at SETTLEMENT time `startsAt = max(now, latest ends_at of ACTIVE paid rows whose plan >= requested plan)`; `endsAt = startsAt + periodMonths`. So renew stacks after the current row, upgrade (PRO->PREMIUM) starts now and the remaining PRO resumes when PREMIUM ends (no proration, nothing lost), downgrade is queued after the higher plan. An ACTIVE paid row with `ends_at` null -> checkout 409 "Paket aktif tanpa batas waktu, hubungi admin." The trial is ignored by the rule (paying during the trial starts now). `changeType` for the quote: NEW (no active paid row), RENEW (same plan), UPGRADE, DOWNGRADE (queued).
- D4 Price is server-side only: `amount = planPrice(cycle) + extraOutlets * 49.000 * periodMonths`, MONTHLY = 1 month, YEARLY = 12 months (add-on not discounted). `extraOutlets` 0..20 per row; FE prefills the current extra. AI_TOPUP = Rp29.000, +200 `bonus_questions` on the tenant-zone current month, only when an ACTIVE paid PREMIUM row exists now (trial -> 409 "Top-up tersedia untuk paket Premium berbayar."); its invoice links that PREMIUM row as `subscription_id`.
- D5 Invoice: number `INV-<yyyyMMdd>-<8 upper hex>`, status ISSUED -> PAID | CANCELLED | EXPIRED, `due_at = issued_at + 24h`, expiry is lazy (a read or action after due_at turns ISSUED into EXPIRED and the PENDING row into CANCELLED). Max one ISSUED, not-due invoice per tenant: another checkout -> 409 "Masih ada tagihan yang belum dibayar." (check under the tenant lock). Same Idempotency-Key + same payload -> the first response; same key + other payload -> 409. Key is required for checkout (400 when missing).
- D6 V18 adds to `platform_invoices`: `kind` varchar(20) NOT NULL (SUBSCRIPTION|AI_TOPUP), `plan_code` varchar(40), `billing_cycle` varchar(10) (MONTHLY|YEARLY, null for AI_TOPUP), `period_months` int, `extra_outlets` int NOT NULL DEFAULT 0, `ai_questions` int NOT NULL DEFAULT 0, `cancelled_at`, `cancel_reason` varchar(500); CHECK status IN ('ISSUED','PAID','CANCELLED','EXPIRED'); index (tenant_id, issued_at desc). Existing tables are empty in every environment (never written), so NOT NULL additions are safe; still use DEFAULTs where listed.
- D7 No auto-renewal, no refund. "Cancel" = cancel an ISSUED invoice (its PENDING row -> CANCELLED, attempt -> CANCELLED). A paid period always runs to `ends_at`; stopping = not renewing. Audit actions: `PLATFORM_INVOICE_ISSUED`, `PLATFORM_INVOICE_PAID`, `PLATFORM_INVOICE_CANCELLED`, `PLATFORM_PAYMENT_FAILED` (entity_type `PLATFORM_INVOICE`, outlet_id null).
- D8 Cancel is one service method `cancel(tenantId, invoiceId, reason, actorUserId)` so TASK-106 (SUPERADMIN) can reuse it; the owner endpoint passes the principal's tenant. Settlement (`settle(invoiceId, providerPaymentRef, actorUserId)`, public method for TASK-106): tenant lock; invoice must be ISSUED and not past due (else 409, EXPIRED applied); already PAID with the same ref -> return current state, no second activation; mark invoice PAID + paid_at, attempt SUCCEEDED, then SUBSCRIPTION -> row ACTIVE with D3 dates and the invoice's extra_outlets; AI_TOPUP -> bonus +200; audit.

## Contracts shared between workers (J1 provides, F1 consumes; base `/api/v1/merchant/subscription`, OWNER)
- `POST /quote` body `{kind: "SUBSCRIPTION"|"AI_TOPUP", planCode?: "PRO"|"PREMIUM", billingCycle?: "MONTHLY"|"YEARLY", extraOutlets?: number}` -> `SubscriptionQuoteResponse {kind, planCode, billingCycle, periodMonths, extraOutlets, changeType: "NEW"|"RENEW"|"UPGRADE"|"DOWNGRADE"|"AI_TOPUP", lines: [{label, amount}], totalAmount, currencyCode: "IDR", estimatedStartsAt, estimatedEndsAt, aiQuestions}`. Message "Rincian tagihan dihitung."
- `POST /checkout` header `Idempotency-Key` (required), same body -> `PlatformInvoiceResponse {id, invoiceNumber, kind, status: "ISSUED"|"PAID"|"CANCELLED"|"EXPIRED", planCode, billingCycle, periodMonths, extraOutlets, aiQuestions, amountDue, currencyCode, issuedAt, dueAt, paidAt, cancelledAt, providerCode: "MOCK"|"MANUAL_TRANSFER", providerPaymentRef, mockPaymentEnabled: boolean, subscriptionStartsAt, subscriptionEndsAt}` (the last two null until paid). Message "Tagihan dibuat."
- `GET /invoices` -> `PlatformInvoiceResponse[]` newest first, max 50. `GET /invoices/{invoiceId}` -> one (foreign/unknown -> 404).
- `POST /invoices/{invoiceId}/mock-pay` body `{outcome: "SUCCEEDED"|"FAILED"}` -> `PlatformInvoiceResponse`. 404 when mock disabled. FAILED: attempt FAILED, invoice stays ISSUED (a later mock-pay creates a new attempt), audit `PLATFORM_PAYMENT_FAILED`. Each call stores one `gateway_events` row (event id `mock-evt-<uuid>`, normalized status, matched attempt, payload digest, processed).
- `POST /invoices/{invoiceId}/cancel` body `{reason?: string <=500}` -> `PlatformInvoiceResponse`; not ISSUED -> 409 "Tagihan tidak dapat dibatalkan."
- `GET /api/v1/merchant/subscription` gains additive `pendingInvoiceId` (UUID or null) and `mockPaymentEnabled`.
- Error texts are Indonesian. Validation: planCode required for SUBSCRIPTION and must be PRO/PREMIUM; billingCycle required for SUBSCRIPTION; extraOutlets 0..20.

## Wave 1 (parallel)

### J1 — backend (`java-worker`)
Reserved:
- `src/main/resources/db/migration/V18__platform_billing_checkout.sql`
- `src/main/java/org/lite/project/cashiersaas/entity/shared/billing/{SubscriptionEntity,PlatformInvoiceEntity,PlatformPaymentAttemptEntity,GatewayEventEntity}.java`
- `src/main/java/org/lite/project/cashiersaas/controller/MerchantSubscriptionController.java`
- `src/main/java/org/lite/project/cashiersaas/service/{MerchantSubscriptionService,MembershipPlanCatalogService}.java`
- new `service/PlatformBillingService.java` (quote, checkout, list, get, cancel), new `service/PlatformBillingSettlementService.java` (settle, used by mock-pay and TASK-106), new `service/MockPaymentGateway.java` (creates attempts/refs, records gateway events)
- new `repository/PlatformBillingRepository.java` (invoices, attempts, gateway events, PENDING/paid rows queries); `repository/SubscriptionRepository.java`, `repository/AiUsageRepository.java` (one `addBonusQuestions` method)
- new DTOs: `dto/request/SubscriptionCheckoutRequest.java`, `dto/request/MockPaymentRequest.java`, `dto/request/InvoiceCancelRequest.java`, `dto/response/SubscriptionQuoteResponse.java`, `dto/response/PlatformInvoiceResponse.java`; `dto/response/MerchantSubscriptionResponse.java`
- new mapper `dto/PlatformInvoiceMapper.java` (mappers live in `dto/`, e.g. `dto/CashierMapper.java`)
- enums only if needed: new `enums/BillingCycle.java`, `enums/InvoiceKind.java`
- `src/main/resources/application*.yml|properties` (only the `cashier.billing.mock-gateway.enabled` property)
- `reports/TASK-105-J1.md`
Do: implement the contracts and D1-D8. Entity state changes as named methods (`SubscriptionEntity.pending(...)`, `activate(startsAt, endsAt)`, `cancel()`; invoice `issue(...)`, `markPaid(...)`, `cancel(reason)`, `expire()`), no public setters. Check the plan rules again inside the settlement transaction (an open-ended row created meanwhile -> 409). Never trust amounts from the client.
Acceptance: all endpoints compile and follow AGENTS.md (named DTO classes, `ApiResponse`, no `ResponseEntity`, no records/wildcards); cashier cannot reach any; another tenant's invoice id -> 404; report lists files, decisions, tests that will need updating, "NOT RUN (policy)".

### F1 — UI (`fe-worker`, repo `E:\Java\cashier-saas-ui`)
Reserved:
- `src/pages/subscription/**` (existing `SubscriptionPage.tsx` + new components in this folder)
- `src/api/plan.api.ts`, new `src/api/billing.api.ts`, `src/api/mock/plan.mock.ts`, new `src/api/mock/billing.mock.ts`, `src/api/index.ts` (export line only)
- `src/language/{id,en}.ts` (only keys under the subscription section)
- `reports/TASK-105-F1.md` (in `E:\Java\cashier-saas\reports`)
Do (follow the existing page/hook/query patterns, KedaiMbo tokens, Indonesian copy):
- Plan cards: "Pilih paket" (PRO/PREMIUM) opens a checkout panel: billing cycle toggle (Bulanan/Tahunan), extra outlet stepper (prefill current `outlets.extra`, 0..20), live quote from `POST /quote` (lines, total, estimated dates, change type label: Baru/Perpanjang/Upgrade/Turun paket dijadwalkan), "Buat tagihan" -> `POST /checkout` with a fresh `crypto.randomUUID()` Idempotency-Key kept per open panel (retries reuse it).
- Invoice view (inside the subscription page, e.g. a panel or `?invoice=<id>` param, no new route): number, amount, due time (countdown), status badge; when `mockPaymentEnabled`: a clearly labelled "Simulasi pembayaran (mock)" box with "Bayar berhasil" / "Gagal" buttons -> `mock-pay`; "Batalkan tagihan" with optional reason. When mock is disabled: show "Menunggu konfirmasi pembayaran oleh admin" and keep the existing WhatsApp CTA.
- `pendingInvoiceId` on the subscription summary shows a banner "Ada tagihan yang belum dibayar" linking to it; "Riwayat tagihan" list from `GET /invoices`.
- Premium (paid) shows "Top-up 200 pertanyaan AI (Rp29.000)" -> quote/checkout kind AI_TOPUP.
- After PAID invalidate the subscription, invoices and plan queries. 409/400 messages from the server are shown as-is.
- Mock layer: `billing.mock.ts` simulates quote/checkout/mock-pay/cancel consistent with the contract (respect `?mockPlan=`).
Acceptance: `tsc -b` passes (orchestrator runs it), no new oxlint warnings, no files outside reserved paths, report written.

## Recommended tests (for when the user requests testing)
- Free owner: quote NEW PRO monthly 59.000; checkout -> ISSUED; mock-pay SUCCEEDED -> plan PRO, activeUntil +1 month; replay checkout same key -> same invoice; other payload same key -> 409; second checkout while ISSUED -> 409.
- PRO active: renew stacks after ends_at; upgrade to PREMIUM starts now and PRO resumes after; downgrade queued.
- Extra outlets 2 yearly = 590.000 + 2*49.000*12; allowed outlets 3 after payment.
- FAILED then SUCCEEDED; pay after due -> 409 EXPIRED; cancel ISSUED -> CANCELLED, cancel PAID -> 409; double settle -> one ACTIVE row.
- AI_TOPUP on paid PREMIUM -> monthlyLimit 500; on trial -> 409.
- Cashier -> 404/403; foreign tenant invoice -> 404; mock disabled -> mock-pay 404.
