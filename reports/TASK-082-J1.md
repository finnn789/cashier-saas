# TASK-082 J1 report: entitlement core

Not compiled or tested (policy: no Maven). Written against Blaze-Persistence 1.6.20 API (checked in the jar sources).

## Changed files (java root `src/main/java/org/lite/project/cashiersaas/`)
New: `enums/PlanCode.java`, `exception/runtime/PlanUpgradeRequiredException.java`, `service/PlanEntitlementService.java`, `src/main/resources/db/migration/V11__plan_entitlements.sql`.
Edited: `entity/shared/billing/SubscriptionEntity.java`, `entity/shared/merchant/TenantEntity.java`, `repository/SubscriptionRepository.java`, `repository/MerchantAccessRepository.java`, `exception/GlobalExceptionHandler.java`, `service/MembershipPlanCatalogService.java`, `dto/response/MembershipPlanResponse.java`, `service/FnbPaymentService.java`, `service/CashierInvitationService.java`, `service/AuthSessionService.java`, `service/FreeTierOnboardingService.java`.

## Contract points
- PlanCode FREETIER < PRO < PREMIUM with `getDisplayName()` (Freetier/Pro/Premium), used by the exception and the catalog.
- PlanEntitlementService: `effectivePlan(tenantId, now)` (running trial => PREMIUM, else max plan of active rows, no rows => FREETIER; one tenant `find` + one subscription query), `require(tenantId, minimum)` (uses `Instant.now()`), `startTrialIfEligible(tenantId, now)` (skips cheaply when trial already started or effective plan is not FREETIER; then one count query: orders with paymentStatus PAID and status <> CANCELLED of the tenant; >= 50 => `tenant.startPremiumTrial(now)`; idempotent).
- PlanUpgradeRequiredException message "Fitur ini tersedia di paket <Pro|Premium>."; GlobalExceptionHandler maps to 402 via ApiResponse.error + @ResponseStatus.
- TenantEntity: column `premium_trial_started_at`, `startPremiumTrial(Instant)` (no-op when already set), `isPremiumTrialActiveAt(Instant)`, constant `PREMIUM_TRIAL_DURATION` (14 days; also used by MerchantAccessRepository and AuthSessionService).
- SubscriptionEntity: `getPlan()` (PlanCode.valueOf of the String column); `activateFreeTier` uses `PlanCode.FREETIER.name()`.
- SubscriptionRepository: `findActiveForTenant` removed; `findActiveAt(tenantId, now)` returns all rows active at now.
- MerchantAccessRepository: both queries now go through one private generic helper `requirePlanAccess` (`B extends WhereBuilder<B>`, no wildcard): any active subscription qualifies (FREETIER filter dropped); and OR(role <> CASHIER, EXISTS active PRO/PREMIUM row, tenant.premiumTrialStartedAt > now - 14d). Same single query, no extra round trip.
- Catalog: all three plans `available=true`, with `monthlyPrice`/`yearlyPrice` BigDecimal (0/0, 59000/590000, 119000/1190000); order is now FREETIER, PRO, PREMIUM (was FREETIER, PREMIUM, PRO). `MembershipPlanResponse.of(PlanCode, monthly, yearly)` replaces `of(String, String)`; only caller updated.
- FnbPaymentService: `startTrialIfEligible` is called in `settle(...)` after the payment is persisted and flushed (so the count includes the current order) and after stock consumption, in the same transaction. `settle` is only reached from `pay`, never from the idempotent replay path (`replayPayment` returns earlier). There is only this one settle path in the class.
- Amendment A1 callers: CashierInvitationService claim = "some row active at now" (`findActiveAt(...).isEmpty()`), no plan gating added. FreeTierOnboardingService.complete picks the active FREETIER row. AuthSessionService.refresh: owner session valid when any row is active; CASHIER session needs an active PRO/PREMIUM row or a running trial, else revoke with SessionInvalidException. Expiry cap only when no entitling row is open-ended: max endsAt of the entitling rows, extended to the trial end when a trial is running (so a cashier token ends at the paid row / trial end).
- V11: `ck_subscriptions_plan_code` CHECK, `idx_subscriptions_tenant_status` (V1 has no index on subscriptions beyond the pkey and the tenant FK), `tenants.premium_trial_started_at timestamptz`, `ai_usage_periods` (PK tenant_id+period_month, FK tenants, all counters CHECK >= 0). Existing data: only 'FREETIER' is written by the code and the demo seed (`db/demo/R__demo_seed.sql`), so the CHECK holds.

## Deviations
- Plan prices are private constants in `MembershipPlanCatalogService` (no new constants class, no config) rather than in `shared/`; display names live in `PlanCode`.
- Trial count query lives in PlanEntitlementService via the injected EntityManager (no existing paid-order count method). Refunded orders (paymentStatus REFUNDED) are not counted as paid.
- `PlanEntitlementService.require` takes no clock (contract signature); it uses `Instant.now()`.

## Open points / risks for the orchestrator (outside J1 reserved paths)
1. `repository/MerchantSelectionRepository.findAccessible` (cashier/owner login `selectMerchant`) LEFT JOINs ALL ACTIVE-status subscription rows and takes row #1 via `setMaxResults(1)`. With FREETIER + PRO rows the chosen subscription is arbitrary (token expiry cap in `AuthSessionService.merchantAccessExpiry` may use the wrong row; `LoginService` also does not check the cashier entitlement, so a downgraded cashier gets a token that then fails at the guard with 404). Needs a follow-up (e.g. `LoginService` + this repository), not reserved for J1.
2. `repository/MembershipOutletAccessRepository.listActiveChoices` uses an EXISTS on any active subscription: fine for owners, but lists a CASHIER's outlet choices even without PRO. Same follow-up.
3. `unit/service/FnbPaymentServiceTest` (`@InjectMocks`) will need a `@Mock PlanEntitlementService` (wave 4, T1). `FreeTierOnboardingServiceTest` and `CashierInvitationService` tests that stub `findActiveForTenant` must switch to `findActiveAt`.
4. Not compiled: the nested `whereExists()` inside `whereOr()` of the helper and the `B extends WhereBuilder<B>` inference are verified only against the Blaze API sources.
5. Every `pay` for a FREETIER tenant without a trial runs one extra count query until the tenant reaches 50 paid orders (tenant lock is already held by `pay`, so the trial start is race-free).
