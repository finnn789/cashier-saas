# TASK-106 J1 report

Status: IMPLEMENTED, awaiting orchestrator compile and review. Baseline: `ded6050` + uncommitted TASK-105/107.
Test status: NOT RUN (policy).

## Changed files

New:
- `src/main/resources/db/migration/V19__platform_admin.sql` (`staff_users.platform_admin boolean NOT NULL DEFAULT false`)
- `component/PlatformPrincipal.java` (userId, email, sessionId; `toString()` = userId so the rate limiter key is stable)
- `service/PlatformAccessGuard.java`, `service/PlatformDashboardService.java`, `service/PlatformTenantAdminService.java`
- `repository/PlatformAdminRepository.java`
- `controller/PlatformController.java` (single controller, base `/api/v1/platform`)
- `dto/PlatformAdminMapper.java`
- `dto/request/platform/`: `PlatformReasonRequest`, `PlatformGrantPlanRequest`, `PlatformAiTopupRequest`, `PlatformMarkPaidRequest`
- `dto/response/platform/`: `PlatformPageResponse<T>`, `PlatformOverviewResponse`, `PlatformTenantItemResponse`, `PlatformTenantDetailResponse`, `PlatformSubscriptionResponse`, `PlatformAiUsageResponse`, `PlatformInvoiceRowResponse` (extends TASK-105 `PlatformInvoiceResponse` + tenantId, tenantName), `PlatformAuditItemResponse`

Modified:
- `entity/shared/identity/StaffUserEntity.java` (`platformAdmin`), `entity/shared/merchant/TenantEntity.java` (`SUSPENDED`, `isSuspended`, `suspend()`, `reactivate()`), `entity/shared/audit/AuditLogEntity.java` (5 new `Action`s)
- `component/JwtTokenService.java` (`generatePlatformAccessToken`), `component/TenantJwtAuthenticationConverter.java` (scope PLATFORM -> `PlatformPrincipal`, authority `ROLE_SUPERADMIN`)
- `service/AuthSessionService.java` (`issuePlatform`), `service/LoginService.java` (`startPlatformSession`, `platformAdmin` on login), `controller/AuthController.java` (`POST /auth/platform`, password change accepts a PlatformPrincipal), `dto/response/identity/LoginResponse.java` (`platformAdmin`)
- `configuration/SecurityConfiguration.java` (one line: `/api/v1/auth/platform` added to the SCOPE_ACCOUNT matcher)
- `service/PlatformBillingSettlementService.java`: `settle(invoiceId, ref, actorUserId, reason)` gained a `reason` (written to the PLATFORM_INVOICE_PAID audit row); `service/PlatformBillingService.java`: its only caller (`mockPay`) passes `null`. Behaviour unchanged for owners.
- `docs/ops/plan-activation.md` (new section 8 "Superadmin"; the intro line 4 still says there is no SUPERADMIN login, outside my reservation, orchestrator may refresh it)

## Contract points done
- P1 flag + SQL runbook. P2 `/auth/platform` (404 unless active + flag; token expiry = min(access TTL, session expiry); `nextStep: "PLATFORM"`, role SUPERADMIN, tenant/outlet null, `platformAdmin: true`). P3 `PlatformAccessGuard.require` on every service method (404 when the principal is null, user missing, inactive or flag revoked).
- P4 audit rows (target tenant, admin actor, trimmed reason): `PLATFORM_PLAN_GRANTED` (entity SUBSCRIPTION), `PLATFORM_PLAN_ENDED` (SUBSCRIPTION), `PLATFORM_AI_TOPUP_GRANTED`, `PLATFORM_TENANT_SUSPENDED`, `PLATFORM_TENANT_REACTIVATED` (TENANT); invoice paid/cancelled reuse TASK-105 audit with the admin as actor and now the reason.
- P5 suspend only from ACTIVE, reactivate only from SUSPENDED, both 409 otherwise.
- P6 revenue month in `Asia/Jakarta`.
- All endpoints of the contract exist with the shapes given. Writes take `FnbOrderRepository.lockTenant` first (same as owner billing). Grant uses `settlement.subscriptionStart/periodEnd` (start-date rule), `SubscriptionEntity.pending(...).activate(...)`, no invoice.
- Tenant list: effective plans, outlet counts via 2 batched queries per page; q matches tenant name or owner email (owner = `tenants.created_by_user_id`), escaped LIKE.

## Deviations / decisions to review
- Hardening (not in packet): converter now rejects a MERCHANT token carrying role SUPERADMIN (a `tenant_memberships.role = SUPERADMIN` row would otherwise pass the `/platform/**` matcher with a TenantPrincipal). `/auth/password` got a third principal branch, otherwise a PLATFORM token would NPE there.
- Plan buckets are mutually exclusive: `TRIAL` = running trial without an active paid PREMIUM row; `PREMIUM` = paid PREMIUM row; `PRO` = PRO row, no trial, no PREMIUM; `FREETIER` = none of those. So a trial-only tenant shows `effectivePlan: PREMIUM, trialActive: true` in a row but is filtered/counted under `TRIAL`, not `PREMIUM`.
- `issuedInvoices` counts ISSUED invoices not yet past due; the invoice list `status=ISSUED` can also show past-due ones (lazy expiry happens on the owner flows and on mark-paid/cancel, which answer 409 and persist the expiry).
- `expiringIn7Days` counts tenants whose paid row ends within 7 days and have no later paid row queued.
- `ai-topup` does not require a paid Premium (complimentary/correction); the owner flow does.
- End plan: running row -> `ends_at = now`; scheduled (not started) row -> CANCELLED; FREETIER, pending, cancelled or already ended -> 409. Uses `SubscriptionEntity.activate(startsAt, now)` to set the end (no new entity method; SubscriptionEntity not reserved).
- Plan effective/allowed-outlet computation is repeated in `PlatformDashboardService.planInfo` (batched); `PlanEntitlementService` would query per tenant. Rules are the same.
- Invoice list `size` and all page sizes are clamped to 1..50; invalid `plan` filter -> 400.
- Refresh stays unchanged: a platform session has no tenant, so refresh returns an ACCOUNT token and the UI calls `/auth/platform` again.
- `/auth/platform` is not in the rate limiter's AUTH_PATHS (RateLimitFilter not reserved); it is limited under the authenticated group.

## Existing tests that may need updating
- None found referencing SUPERADMIN (`TenantJwtAuthenticationConverterTest` has no SUPERADMIN case). Any test or mock calling `PlatformBillingSettlementService.settle` with 3 args (none found in `src/test`) must add the `reason` argument. Tests building `LoginResponse` JSON may now see `platformAdmin`.

## Open questions
- None blocking.
