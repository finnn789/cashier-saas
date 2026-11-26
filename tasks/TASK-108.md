# TASK-108: Unit tests for TASK-105 (mock billing checkout) and TASK-106 (SUPERADMIN platform)

Status: DELEGATED (T1 + T2 in parallel). Memory version: 103. Branch `feature/leakage-report`. Baseline: `ded6050` + uncommitted TASK-105/106/107 (compiled).

## User instruction (2026-11-25)
"Silahkan lakukan Unit testnya dulu, delegate ke Agent test, setelah itu report kalau Testnya aman."

Note: TASK-105/106 production code already exists (written before the TDD rule applied), so these are test-after unit tests. Tests must assert the contract below; if a test fails because the code disagrees with the contract, that is a finding for the orchestrator, not something to "fix" by weakening the assertion.

## Global rules (every worker)
- Reports: `reports/TASK-108-<id>.md`.
- Unit tests only (JUnit 5 + Mockito, the style of `src/test/java/org/lite/project/cashiersaas/unit/service/PlanEntitlementServiceTest.java` and `FnbRefundServiceTest.java`). No Spring context, no DB, no Testcontainers.
- AGENTS.md unit test rule: no new helpers/base classes/builders/fakes; extend existing test classes when one exists for the class under test; one test per behaviour, parameterize variants; no trivial tests (getters, constants, mocks asserting mocks). Production code is never changed.
- Entities with private constructors/no setters: build them the way existing tests do (grep `ReflectionTestUtils` / factory methods in `unit/service/*Test.java`) — do not add production factories.
- Baseline: `mvnw -Punit-tests test` = 674 tests, 0 failures (orchestrator ran 2026-11-25). Existing unit test classes: `unit/component/{JwtTokenServiceTest,TenantJwtAuthenticationConverterTest}`, `unit/service/PlanEntitlementServiceTest`; there is NO existing unit test for `LoginService`, `AuthSessionService`, `MerchantSubscriptionService`, `MembershipPlanCatalogService` or any Platform* class.

## Contract facts (from the reviewed code)
TASK-105 (package `org.lite.project.cashiersaas`):
- `service/PlatformBillingService`: `quote(TenantPrincipal, SubscriptionCheckoutRequest)`, `checkout(TenantPrincipal, String key, SubscriptionCheckoutRequest)`, `list`, `get(principal, invoiceId)`, `mockPay(principal, invoiceId, MockPaymentRequest)`, `cancelForOwner(...)`, `cancel(UUID tenantId, UUID invoiceId, String reason, UUID actorUserId)`. Owner resolved via `MerchantAccessGuard.requireOwnerIgnoringAllowance`; tenant lock `FnbOrderRepository.lockTenant`.
- Prices: PRO 59.000/month, 590.000/year; PREMIUM 119.000/month, 1.190.000/year; add-on 49.000 per extra outlet per month (`amount = planPrice(cycle) + extraOutlets * 49.000 * periodMonths`, YEARLY = 12 months); AI_TOPUP 29.000 for 200 questions.
- Checkout: missing/invalid Idempotency-Key -> 400 (via `FnbPosOrderService.validatedKey`); same key + other payload -> 409 "Idempotency-Key telah digunakan untuk tagihan berbeda."; same key + same payload -> existing invoice, no new rows; an open ISSUED not-due invoice -> 409 "Masih ada tagihan yang belum dibayar."; SUBSCRIPTION creates a PENDING subscription row + ISSUED invoice (due = issued + 24h) + PENDING attempt (provider `MOCK` when mock enabled, `MANUAL_TRANSFER` when disabled) + audit `PLATFORM_INVOICE_ISSUED`; AI_TOPUP without an active paid PREMIUM row -> 409 "Top-up tersedia untuk paket Premium berbayar.".
- `service/PlatformBillingSettlementService`: `settle(invoiceId, providerPaymentRef, actorUserId, reason)` (4th arg added by TASK-106), `fail(...)`, `subscriptionStart(tenantId, plan, now)` = max(now, latest ends_at of live paid rows with plan >= requested); an open-ended (ends_at null) such row -> 409 "Paket aktif tanpa batas waktu, hubungi admin."; `periodEnd` = calendar months in tenant zone. Settle: not ISSUED -> 409; past due -> invoice EXPIRED, PENDING row CANCELLED, 409 "Tagihan sudah kedaluwarsa." (and not rolled back); already PAID with the same ref -> returned unchanged, no second activation; PAID with other ref -> 409; SUBSCRIPTION -> row ACTIVE with start rule dates; AI_TOPUP -> `AiUsageRepository.addBonusQuestions(..., 200)`; audit `PLATFORM_INVOICE_PAID`. `fail` -> attempt FAILED, invoice stays ISSUED, audit `PLATFORM_PAYMENT_FAILED`.
- Cancel: ISSUED -> CANCELLED + PENDING row CANCELLED + pending attempts CANCELLED + audit `PLATFORM_INVOICE_CANCELLED`; not ISSUED -> 409 "Tagihan tidak dapat dibatalkan."; foreign tenant invoice id -> 404 (`ResourceNotFoundException`).
- mockPay with mock disabled (`cashier.billing.mock-gateway.enabled=false`) -> 404.
- Entity state methods: `SubscriptionEntity.pending/activate/cancel/isPending`, `PlatformInvoiceEntity.issue/markPaid/cancel/expire/isIssued/isPastDue`.

TASK-106:
- `component/TenantJwtAuthenticationConverter`: scope `PLATFORM` + roles contains SUPERADMIN -> `PlatformPrincipal` with only `ROLE_SUPERADMIN`; scope PLATFORM without SUPERADMIN -> `BadCredentialsException`; MERCHANT token whose roles contain SUPERADMIN -> `BadCredentialsException`.
- `component/JwtTokenService.generatePlatformAccessToken(userId, email, sessionId, expiresAt)`: claims scope PLATFORM, roles ["SUPERADMIN"], no tenant_id/outlet_id/membership_id.
- `service/LoginService.startPlatformSession(AccountPrincipal)`: user not active or `platformAdmin` false -> 404 `ResourceNotFoundException`; else response `platformAdmin=true`, nextStep "PLATFORM". `login` sets `platformAdmin` from the user.
- `service/PlatformAccessGuard.require(PlatformPrincipal)`: null, unknown, inactive or flag false -> 404; else the user.
- `service/PlatformTenantAdminService`: grant plan (ACTIVE row with start rule, audit `PLATFORM_PLAN_GRANTED`), end plan (FREETIER row -> 409; paid row ends now; not yet started -> cancelled; audit `PLATFORM_PLAN_ENDED`), AI top-up (bonus, audit), suspend (only from ACTIVE, else 409) / reactivate (only from SUSPENDED, else 409) with audits, mark-paid calls `settle(invoiceId, transferReference, adminId, reason)`, cancel calls `PlatformBillingService.cancel`. Every write calls the guard first (revoked admin -> 404 and no write).
- `service/PlatformDashboardService`: every read calls the guard first; page size clamped to 1..50; plan buckets FREETIER/PRO/PREMIUM/TRIAL mutually exclusive.
Read the actual classes before writing: if a signature differs from this list, follow the code and note it in the report.

## Wave 1 (parallel)

### T1 — TASK-105 billing unit tests (`test-worker`)
Reserved:
- new `src/test/java/org/lite/project/cashiersaas/unit/service/PlatformBillingServiceTest.java`
- new `src/test/java/org/lite/project/cashiersaas/unit/service/PlatformBillingSettlementServiceTest.java`
- `reports/TASK-108-T1.md`
Cover (one test per behaviour, parameterize the price table): quote prices incl. yearly + add-on + top-up; change type NEW/RENEW/UPGRADE/DOWNGRADE; checkout idempotency replay / other payload 409 / missing key 400 / open invoice 409 / provider MOCK vs MANUAL_TRANSFER; top-up without paid Premium 409; start rule (now, stacked after same plan, upgrade now, downgrade queued, open-ended 409); settle activation, AI top-up bonus, double settle same ref idempotent, other ref 409, expired 409 + EXPIRED state; fail keeps ISSUED; cancel ISSUED vs PAID 409; foreign invoice 404; mock disabled 404.

### T2 — TASK-106 platform unit tests (`test-worker`)
Reserved:
- `src/test/java/org/lite/project/cashiersaas/unit/component/TenantJwtAuthenticationConverterTest.java` (extend)
- `src/test/java/org/lite/project/cashiersaas/unit/component/JwtTokenServiceTest.java` (extend)
- new `src/test/java/org/lite/project/cashiersaas/unit/service/PlatformAccessGuardTest.java`
- new `src/test/java/org/lite/project/cashiersaas/unit/service/PlatformTenantAdminServiceTest.java`
- new `src/test/java/org/lite/project/cashiersaas/unit/service/PlatformDashboardServiceTest.java` (only guard-first + page clamp + plan bucket logic that is unit-testable without HQL; skip pure repository pass-through)
- new `src/test/java/org/lite/project/cashiersaas/unit/service/LoginServicePlatformTest.java` ONLY for `startPlatformSession` + `login` flag (no LoginService test exists)
- `reports/TASK-108-T2.md`
Cover: converter PLATFORM ok / PLATFORM without SUPERADMIN / MERCHANT with SUPERADMIN; platform token claims; guard 404 cases; startPlatformSession 404 vs success; admin writes (grant, end FREETIER 409, end paid, end future -> cancelled, suspend/reactivate state 409s, mark-paid delegates with the reason, revoked admin -> no write, every write audits with the admin actor); dashboard guard-first + page clamp.

## Acceptance
- Only reserved paths changed; report per worker with behaviour -> test method and anything where code and contract disagree.
- Orchestrator runs the targeted classes, then `mvnw -B -Punit-tests test` (user asked for the unit test run); everything green before reporting "aman".
