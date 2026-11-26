# TASK-106: SUPERADMIN platform login, permission guard and dashboard

Status: DELEGATED (wave 2; TASK-105 J1 reviewed + compiled 2026-11-25) (J1 reuses `PlatformBillingSettlementService.settle` and `PlatformBillingService.cancel` from TASK-105; F1 shares language files with TASK-105 F1). Memory version: 102. Branch `feature/leakage-report`. Baseline: `ded6050` + uncommitted TASK-105/107.

## User instruction (2026-11-25)
"Buatkan TASK untuk Dashboard SuperAdmin, Permission dan Lain lain." Context: there is no SUPERADMIN login or panel; billing is manual transfer activated by SQL; TASK-105 adds owner checkout with a mock gateway.

## Global rules (every worker)
- Reports: `reports/TASK-106-<id>.md`.
- Next migration: `V19__platform_admin.sql` (V18 = TASK-105).
- Reuse: `JwtTokenService`, `AuthSessionService`, `TenantJwtAuthenticationConverter`, `PlanEntitlementService`, `SubscriptionRepository`, `AiUsageRepository.readUsage` + `addBonusQuestions` (TASK-105), `PlatformBillingSettlementService.settle(invoiceId, providerPaymentRef, actorUserId)` and `PlatformBillingService.cancel(tenantId, invoiceId, reason, actorUserId)` (TASK-105), `PlatformBillingRepository` (TASK-105), `PlatformInvoiceMapper` (TASK-105), `AuditLogRepository`, `DigestService`, `ApiResponse`, existing exceptions. Mappers live in `dto/`.

## Verified facts (source read 2026-11-25)
- Roles `enums/Role { SUPERADMIN, OWNERMERCHANT, CASHIER }` (do not add roles). `SecurityConfiguration` (`configuration/`) already has `.requestMatchers("/api/v1/platform/**").hasRole(Role.SUPERADMIN.name())` (:66); nothing issues a SUPERADMIN token and no controller exists under `/api/v1/platform`.
- Auth flow: `POST /api/v1/auth/login` (`LoginService.login` -> `AuthSessionService.start`) returns an ACCOUNT token (scope `ACCOUNT`, authority `SCOPE_ACCOUNT`, `AccountPrincipal`) + refresh token; `POST /api/v1/auth/select-merchant` turns the session into a MERCHANT token (claims tenant_id, outlet_id, membership_id, session_id, scope `MERCHANT`, roles from the membership). `POST /api/v1/auth/refresh`: a session without a tenant gets a new ACCOUNT token (`AuthSessionService.issueForExistingSession`, :136), a tenant session re-checks user active, tenant active and fnb enabled, so a suspended tenant loses access at the next refresh.
- `TenantJwtAuthenticationConverter` (`component/`): ACCOUNT scope -> AccountPrincipal; otherwise requires scope `MERCHANT` (else `BadCredentialsException`) and tenant/outlet/membership claims -> `TenantPrincipal` with `ROLE_<role>` authorities.
- `staff_users`: id, email (lower case), full_name, password_hash, status, onboarding_intent, version, timestamps; no platform flag. `tenant_memberships.role` CHECK allows SUPERADMIN but memberships are per tenant.
- `tenants.status` varchar(20), no CHECK, values ACTIVE / PENDING_PROFILE; `TenantEntity.isActive()`. `LoginService.selectMerchant` answers 409 MERCHANT_INACTIVE when the tenant is not active.
- `audit_logs`: tenant_id NOT NULL, actor_staff_user_id, entity_id NOT NULL, entity_type, action, reason, details, outlet_id nullable.
- Billing after TASK-105: subscriptions PENDING/ACTIVE/CANCELLED with a start-date rule `startsAt = max(now, latest ends_at of ACTIVE paid rows with plan >= requested)`; invoices ISSUED/PAID/CANCELLED/EXPIRED with kind SUBSCRIPTION/AI_TOPUP; when the mock gateway is disabled, attempts use provider `MANUAL_TRANSFER` and the SUPERADMIN settles them.
- Login response DTO: `dto/response/identity/LoginResponse.java`.

## Orchestrator design decisions (reversible, user may override)
- P1 SUPERADMIN identity = V19 `staff_users.platform_admin boolean NOT NULL DEFAULT false`. Set only by an operator with SQL (no endpoint creates admins); J1 adds that SQL to `docs/ops/plan-activation.md` as a new section.
- P2 Platform token: `POST /api/v1/auth/platform` (SCOPE_ACCOUNT): user active and `platform_admin` -> access token with scope `PLATFORM`, roles `["SUPERADMIN"]`, sub, session_id, email, no tenant claims; expiry = min(access TTL, session expiry); else 404. Refresh semantics unchanged (the session has no tenant, so refresh returns an ACCOUNT token and the UI calls `/auth/platform` again). The converter maps scope PLATFORM to a new `component/PlatformPrincipal` (userId, sessionId, email) with authority `ROLE_SUPERADMIN`. A PLATFORM token never passes `/merchant/**` or `/cashier/**` (no role there) and MERCHANT tokens never carry SUPERADMIN.
- P3 Permission: one fixed role, all platform permissions; no per-admin matrix. New `service/PlatformAccessGuard.require(PlatformPrincipal)` re-reads the user on every platform request (active + platform_admin still true; else 404) so revoking the flag is immediate. Login response gets additive `platformAdmin: boolean`.
- P4 Every platform write needs a `reason` (3..500 chars) and writes `audit_logs` with the target tenant_id and the admin as actor: `PLATFORM_PLAN_GRANTED`, `PLATFORM_PLAN_ENDED`, `PLATFORM_AI_TOPUP_GRANTED`, `PLATFORM_TENANT_SUSPENDED`, `PLATFORM_TENANT_REACTIVATED`, plus TASK-105's `PLATFORM_INVOICE_PAID` / `PLATFORM_INVOICE_CANCELLED` with the admin as actor.
- P5 Suspend sets `tenants.status = 'SUSPENDED'` (only from ACTIVE); reactivate back to ACTIVE (only from SUSPENDED). No data is deleted; sessions die at the next refresh (accepted, access TTL).
- P6 Revenue month = calendar month in `Asia/Jakarta` (platform zone). Money as BigDecimal.

## Contracts (J1 provides, F1 consumes; base `/api/v1/platform`, SUPERADMIN)
- `POST /api/v1/auth/platform` -> existing `LoginResponse` shape with the PLATFORM token (`nextStep: "PLATFORM"`, role `SUPERADMIN`, tenantId/outletId null).
- `GET /overview` -> `{tenantsTotal, tenantsByPlan: {FREETIER, PRO, PREMIUM, TRIAL}, suspendedTenants, issuedInvoices, paidThisMonthCount, paidThisMonthAmount, expiringIn7Days}`.
- `GET /tenants?q=&plan=&status=&page=0&size=20` (size <= 50; q matches tenant name or owner email) -> `{items: [{id, name, ownerName, ownerEmail, phoneNumber, status, effectivePlan, activeUntil, trialActive, outletsActive, outletsAllowed, createdAt}], page, size, total}`; effective plans for a page computed with batched queries (no per-row query loop).
- `GET /tenants/{tenantId}` -> item fields + `subscriptions: [{id, planCode, status, startsAt, endsAt, extraOutlets}]` (newest first), `invoices` (last 20, TASK-105 `PlatformInvoiceResponse`), `ai: {monthlyLimit, monthlyUsed, dailyUsed}` (tenant zone month).
- `POST /tenants/{tenantId}/subscriptions` `{planCode: PRO|PREMIUM, months: 1..24, extraOutlets: 0..20, reason}` -> creates an ACTIVE paid row with the TASK-105 start-date rule, no invoice (complimentary or correction) -> tenant detail.
- `POST /tenants/{tenantId}/subscriptions/{subscriptionId}/end` `{reason}` -> paid ACTIVE row gets `ends_at = now` (FREETIER row -> 409) -> tenant detail.
- `POST /tenants/{tenantId}/ai-topup` `{questions: 1..1000, reason}` -> bonus on the tenant-zone current month -> tenant detail.
- `POST /tenants/{tenantId}/suspend` and `/reactivate` `{reason}` -> tenant detail; wrong state -> 409.
- `GET /invoices?status=&q=&page=0&size=20` -> page of `PlatformInvoiceResponse` + `tenantId`, `tenantName`.
- `POST /invoices/{invoiceId}/mark-paid` `{transferReference: 1..160, reason}` -> `settle(invoiceId, transferReference, adminId)` -> invoice.
- `POST /invoices/{invoiceId}/cancel` `{reason}` -> `PlatformBillingService.cancel(invoice's tenant, ...)` -> invoice.
- `GET /audit?tenantId=&page=0&size=50` -> `{items: [{id, tenantId, tenantName, actorName, action, entityType, entityId, reason, details, createdAt}], page, size, total}` for actions starting with `PLATFORM_`.
- Unknown ids -> 404; validation -> 400 Indonesian messages.

## Wave 2 (parallel, after TASK-105 J1 review)

### J1 — backend (`java-worker`)
Reserved:
- `src/main/resources/db/migration/V19__platform_admin.sql`
- `entity/shared/identity/StaffUserEntity.java` (only `platformAdmin`), `entity/shared/merchant/TenantEntity.java` (only `suspend()`/`reactivate()`), `entity/shared/audit/AuditLogEntity.java` (only new `Action` constants for P4, entity type `TENANT` or `SUBSCRIPTION`; the four PLATFORM_INVOICE/PAYMENT ones already exist)
- `component/TenantJwtAuthenticationConverter.java`, `component/JwtTokenService.java`, new `component/PlatformPrincipal.java`
- `service/LoginService.java`, `service/AuthSessionService.java` (only what `/auth/platform` needs), `controller/AuthController.java`, `dto/response/identity/LoginResponse.java`
- new `controller/PlatformController.java` (or split by area: `PlatformTenantController`, `PlatformInvoiceController`), new `service/PlatformAccessGuard.java`, `service/PlatformDashboardService.java`, `service/PlatformTenantAdminService.java`, new `repository/PlatformAdminRepository.java`, new DTOs under `dto/request/platform/` and `dto/response/platform/`, new `dto/PlatformAdminMapper.java`
- `service/PlatformBillingSettlementService.java` and `service/PlatformBillingService.java` only if a signature must accept an admin actor (keep TASK-105 behaviour)
- `docs/ops/plan-activation.md` (new "Superadmin" section: grant/revoke `platform_admin`)
- `reports/TASK-106-J1.md`
Do: P1-P6 and the contracts. SecurityConfiguration already guards `/api/v1/platform/**`; add `/api/v1/auth/platform` to the SCOPE_ACCOUNT matcher (SecurityConfiguration is reserved for that one line).
Acceptance: compiles; OWNER/CASHIER/ACCOUNT tokens -> 403/404 on `/platform/**`; PLATFORM token -> 403 on `/merchant/**`; flag revoked -> next platform call 404; report "NOT RUN (policy)".

### F1 — UI (`fe-worker`, repo `E:\Java\cashier-saas-ui`)
Reserved:
- new `src/pages/platform/**` (own layout: sidebar Ringkasan, Tenant, Tagihan, Audit)
- new `src/api/platform.api.ts`, new `src/api/mock/platform.mock.ts`, `src/api/auth.api.ts`, `src/api/session.ts`, `src/api/client.ts` (refresh path re-calls `/auth/platform` for platform sessions), `src/api/index.ts` (export line)
- `src/routes/AppRoutes.tsx`, `src/routes/ProtectedRoute.tsx`, `src/context/AuthContext.tsx`, `src/constants/permissions.ts`
- `src/pages/auth/LoginPage.tsx`, `src/pages/auth/SelectOutletPage.tsx` (entry "Masuk sebagai Superadmin" when `platformAdmin`)
- `src/language/{id,en}.ts` (new `platform` section only)
- `reports/TASK-106-F1.md` (in `E:\Java\cashier-saas\reports`)
Do: SUPERADMIN can open only `/platform/*`; other roles never see or reach it. Pages: overview cards; tenant table with search/filters/paging; tenant detail with subscriptions, invoices, AI usage and action dialogs (grant plan, end plan, AI top-up, suspend/reactivate) each requiring a reason; invoices list with mark-paid (transfer reference) and cancel; audit list. Server messages shown as-is; mock layer follows the contract.
Acceptance: `tsc -b` passes (orchestrator runs it), no new oxlint warnings, only reserved paths.

## Recommended tests (for when the user requests testing)
- Flag false -> `/auth/platform` 404; flag true -> PLATFORM token; that token on `/merchant/**` 403; OWNER token on `/platform/**` 403; flag revoked mid-session -> 404.
- Overview counts with Free/Pro/Premium/trial/suspended tenants; tenant search by email; paging total.
- Grant PRO 1 month on a Free tenant -> plan PRO; grant while PRO active stacks; end -> back to Free; FREETIER end -> 409.
- Mark-paid ISSUED invoice -> PAID and plan active; already PAID -> no second row; expired -> 409.
- Suspend -> owner select-merchant 409, refresh fails; reactivate restores. Every write has an audit row with the admin as actor.
