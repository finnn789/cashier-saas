# TASK-083: Multi-outlet — outlet management, per-outlet lock, consolidated report, outlet add-on

Status: VERIFIED (2026-11-15; verify 601 unit + 189 IT green run 1; Python unit 268 + integration 20 OK). Memory version: 87. Branch `fix/flow-controls-a`. Baseline commit: `f9ed23d` (dirty: only `.ai/MEMORY.md`, `.ai/TASKS.md`, orchestrator-owned).

## User instruction (2026-11-14)
The user explicitly brings multi-outlet into scope now (was post-pilot in PRD v2.2 §16) and asks for all five gaps to be finished and tested:
1. Owner can create and manage additional outlets (today the outlet is created once at onboarding).
2. The transaction lock is per tenant; change it to per outlet first.
3. Reports/dashboard cannot combine several outlets.
4. Per-outlet add-on billing does not exist.
5. TASK-082 AI quota computes month/day in the tenant time zone assuming one outlet per tenant.

## Global rules (every worker)
Identical to `tasks/TASK-076.md` "Global rules" 1-7 plus the TASK-082 "Test-writing rule" (also in `AGENTS.md`). Reports: `reports/TASK-083-<id>.md`.
- Read `AGENTS.md` first (DTO = named public class with Lombok `@Getter/@Setter`, `ApiResponse<T>`, no `ResponseEntity`, no records, no wildcard generics, no `@Deprecated`, existing packages only, Indonesian user messages).
- Write ONLY your reserved paths plus your report. Need another file? Stop and write the need in the report.
- Reuse existing code (`MerchantAccessGuard`, `PlanEntitlementService`, `PlanUpgradeRequiredException` -> 402, `AuditLogEntity.recordAction`, `FnbPaymentLabelService.createDefaults`, `MembershipOutletAccessEntity.grant`, `OutletEntity.open`, existing time-zone validation). No duplicate helpers, no new utility classes.
- Never run Maven, pytest/unittest, npm or the app. Never edit V1..V11. Java paths below are relative to `src/main/java/org/lite/project/cashiersaas/`.
- Never log tokens, passwords, phone numbers or keys.

## Orchestrator design decisions (reversible, user may override)
- M1 **Who may have more outlets.** FREETIER = exactly 1 active outlet (no change). PRO/PREMIUM: allowed active outlets = `1 + extra_outlets` summed over the paid subscription rows that are active now. Creating an outlet when active outlets >= allowance -> `PlanUpgradeRequiredException` (402) with an Indonesian message about the outlet add-on ("Outlet tambahan memerlukan add-on outlet (Rp49.000/outlet/bulan)."). On FREETIER the message names paket Pro.
- M2 **Add-on billing = pilot manual activation** like TASK-082 E4/D5: new column `subscriptions.extra_outlets int NOT NULL DEFAULT 0 CHECK (extra_outlets >= 0)` set by an operator on the paid row (runbook section). No payment integration, no new platform endpoint. The add-on lives and expires with its paid row. Price Rp49.000/outlet/month shown in the existing plan catalog response.
- M3 **Downgrade without deleting data.** When the allowance shrinks, active outlets are ranked by `created_at, id`; outlets with rank > allowance are "over allowance": no operational access (guard, select-merchant, gRPC AI scope) -> 402 for HTTP, excluded for gRPC/AI/reports. The first outlet (rank 1) is always usable. Owner can deactivate outlets to choose which ones remain (deactivate is always allowed, also when over allowance). Re-upgrade restores access automatically.
- M4 **Outlet lifecycle.** Create: name (required, trimmed, <= 120), time zone (required, valid `ZoneId`), address, phone optional. New outlet gets default payment labels and an access row for every active OWNERMERCHANT membership of the tenant. Catalog, stock and staff start empty (no copy). Update profile = name/address/phone/time zone (same validation). Deactivate refused (409 `InvalidOperationException`-style, existing mapping) when it is the last active outlet or when it has an open shift. Reactivate subject to M1 allowance. Every create/update/deactivate/reactivate writes an audit log (actor, timestamp, old/new values) with `AuditLogEntity.recordAction`.
- M5 **Per-outlet lock.** `FnbOrderRepository.lockTenant` is replaced by `lockOutlet(UUID tenantId, UUID outletId)` (PESSIMISTIC_WRITE on the outlet row, tenant-scoped lookup) in every order/payment/shift/refund/discount/cancellation/payment-label/POS path. `MerchantBusinessSettingsService` keeps a TENANT lock (tenant-level settings and currency lock), so `lockTenant` stays only for that caller. Idempotency keys stay unique per tenant; a concurrent cross-outlet duplicate key that hits the DB unique constraint must surface as the existing 409 conflict, never 500.
- M6 **Tenant vs outlet time zone.** AI quota month/day keep using `tenants.time_zone` (the billing zone, set at onboarding from the first outlet). Business-settings update writes `tenants.time_zone` only while the tenant has exactly one active outlet; otherwise only the outlet's zone changes. Daily summary is already per outlet (no change).
- M7 **Consolidated report.** Owner-only `GET /api/v1/merchant/reports/sales?startDate&endDate` (same filter validation/limits as the per-outlet report, same Free 30-day rule via the existing plan check). Covers every active, within-allowance outlet the owner can access. Each outlet's dates are interpreted in that outlet's zone. Response: `startDate`, `endDate`, `currency` if available, `totals` (same summary fields summed with BigDecimal), `outlets` (list of `outletId`, `outletName`, `timeZone`, `summary`). Built by reusing `FnbSalesReportService.summarize` per outlet; no new SQL unless needed. Paid, non-cancelled only (inherited).

## Contracts shared between workers (J1 provides, others consume)
- `PlanEntitlementService.allowedOutletCount(UUID tenantId, Instant now): int` — 1 on FREETIER/trial without paid row; `1 + sum(extra_outlets)` of active PRO/PREMIUM rows otherwise.
- `PlanEntitlementService.usableOutletIds(UUID tenantId, Instant now): List<UUID>` — active outlets ordered by `created_at, id`, first `allowedOutletCount`.
- `PlanEntitlementService.requireOutletUsable(UUID tenantId, UUID outletId, Instant now)` — throws `PlanUpgradeRequiredException` (402, add-on message) when the outlet is active but over allowance. Cheap path: when the tenant has one active outlet, no ranking query.

## Wave 1 (parallel)

### J1 — schema, entitlement, outlet management, access enforcement
Reserved:
- `src/main/resources/db/migration/V12__multi_outlet.sql` (new): `subscriptions.extra_outlets`; index `outlets (tenant_id, active, created_at)` if useful.
- `entity/shared/billing/SubscriptionEntity.java`, `entity/shared/merchant/OutletEntity.java` (named methods `updateProfile(...)`, `deactivate()`, `reactivate()`; no public setters)
- `service/PlanEntitlementService.java` (contracts above)
- `repository/OutletRepository.java`, `repository/SubscriptionRepository.java`
- `service/OutletService.java`, `controller/OutletController.java` (keep `GET /api/v1/cashier/outlets`), new `controller/MerchantOutletController.java` under `/api/v1/merchant/outlets`: `GET` (all tenant outlets: id, name, timeZone, address, phone, active, usable + `allowedOutlets`, `activeOutlets`, `extraOutletPrice`), `POST` create (returns outlet), `PUT /{outletId}`, `POST /{outletId}/deactivate`, `POST /{outletId}/reactivate`. Owner check via `MerchantAccessGuard.requireOwner(principal, principal.getOutletId())` (token outlet), target outlet must belong to the tenant (else 404).
- new DTOs in `dto/request/` and `dto/response/` (e.g. `MerchantOutletRequest`, `MerchantOutletResponse`, `MerchantOutletListResponse`); mapping in existing `dto/CashierMapper.java` or a focused method there.
- `service/MerchantAccessGuard.java` (call `requireOutletUsable` in `requireOutlet`)
- `service/LoginService.java` (`listChoices` marks/omits over-allowance outlets — omit them; `selectMerchant` -> 402 for over-allowance outlet)
- `grpc/policy/GrpcTenantAccessPolicy.java` (owner/member outlet lists filtered to usable outlets)
- `service/MembershipPlanCatalogService.java` + its response DTO (add outlet add-on price Rp49.000/month, BigDecimal or minor units consistent with existing)
- `service/FreeTierOnboardingService.java` only if needed to keep it compiling.
Done when: M1-M4 and M3 enforcement implemented, compile-clean by reading; report lists routes and messages.

### J2 — per-outlet lock and tenant time zone rule
Reserved:
- `repository/FnbOrderRepository.java` (add `lockOutlet`, keep `lockTenant` for settings only)
- `service/FnbDiscountService.java`, `service/FnbOrderCancellationService.java`, `service/FnbPaymentLabelService.java`, `service/FnbPaymentService.java`, `service/FnbPosOrderService.java`, `service/FnbRefundService.java`, `service/FnbShiftService.java` (lock call sites only, plus the 409 mapping for the cross-outlet duplicate key if the conflict surfaces in these services)
- `service/MerchantBusinessSettingsService.java` (M6 tenant zone rule; keep tenant lock)
- `exception/GlobalExceptionHandler.java` ONLY if a DataIntegrityViolation for `uk_sales_orders_tenant_idempotency` / `uk_payments_tenant_idempotency` / `uk_payment_refunds_tenant_idempotency` currently maps to 500; map it to the existing 409 conflict response.
Every lock call must use the outlet the request is already scoped to (context/principal outlet). Do not change any other behaviour.

## Wave 2

### J3 — consolidated sales report (after J1)
Reserved: new `controller/FnbConsolidatedSalesReportController.java`, `service/FnbSalesReportService.java` (new method `consolidated(TenantPrincipal, FnbSalesReportFilter)`), new response DTOs `dto/response/FnbConsolidatedSalesReportResponse.java`, `dto/response/FnbOutletSalesSummaryResponse.java`, `dto/FnbSalesReportMapper.java`. Uses `PlanEntitlementService.usableOutletIds` and builds a `MerchantAccessContext` per outlet through the guard/repository (owner must have access to each).

## Wave 3 (parallel)

### T1 — Java tests
Extend existing classes (search first). Cover: create outlet limit (FREETIER 402, PRO 1+extra, over limit 402), deactivate rules (last outlet, open shift), reactivate over allowance 402, over-allowance outlet 402 on guard and select-merchant + omitted from choices after paid row expiry, owner granted access to new outlet, per-outlet lock lets two outlets transact independently (behavioural, no timing), cross-outlet duplicate idempotency -> 409 not 500 if testable without timing, consolidated report sums two outlets in different zones and excludes cancelled/unpaid and over-allowance outlets, cashier/other-tenant cannot call consolidated report or outlet management (404/403 like existing), business settings zone rule (single vs multi outlet), plan catalog shows add-on price, gRPC owner outlet list excludes over-allowance outlet. Reserved: `src/test/**` only.

### D1 — docs
Reserved: `docs/ops/plan-activation.md` (new section: activate/extend/stop outlet add-on via `extra_outlets` on the active paid row, verify SELECT, rule that downgrade does not delete outlets), Postman collection/examples under `postman/` if present for merchant outlets and consolidated report, gRPC contract JSON untouched.

## Verification (orchestrator)
Review each diff against baseline `f9ed23d`, `mvnw -B -DskipTests compile test-compile`, then (user-requested) `mvnw -B verify` and Python `.venv` unit + integration.
