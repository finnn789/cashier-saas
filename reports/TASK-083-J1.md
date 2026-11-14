# TASK-083 J1 report: schema, entitlement, outlet management, access enforcement

Status: implemented, compile-checked by reading only (no Maven, no tests run, by policy). Baseline `f9ed23d`.

## Changed files (all under `src/main/` unless noted)
New:
- `resources/db/migration/V12__multi_outlet.sql`: `subscriptions.extra_outlets int NOT NULL DEFAULT 0`, CHECK `ck_subscriptions_extra_outlets` (`>= 0` and `= 0` when `plan_code = 'FREETIER'`), index `idx_outlets_tenant_active_created (tenant_id, active, created_at)`.
- `java/.../controller/MerchantOutletController.java`
- `java/.../dto/request/MerchantOutletRequest.java`, `dto/response/MerchantOutletResponse.java`, `dto/response/MerchantOutletListResponse.java`

Modified:
- `entity/shared/billing/SubscriptionEntity`: `extraOutlets` (getter only).
- `entity/shared/merchant/OutletEntity`: `deactivate()` / `reactivate()` (return true when state changed). No `updateProfile`: the existing `updateBusinessSettings(name, timeZone, address, phone)` already is that named method, so it is reused (no duplicate).
- `service/PlanEntitlementService`: `allowedOutletCount`, `usableOutletIds`, `requireOutletUsable` (contract) plus `requireOutletCapacity(tenantId, activeOutlets, now)` (shared by create/reactivate) and the two message constants. Outlet ranking uses `EntityManager` JPQL like `startTrialIfEligible`, so the constructor stays `(SubscriptionRepository, EntityManager)` and `PlanEntitlementServiceTest` still compiles.
- `repository/OutletRepository`: `findInTenant`, `listByTenant` (created_at, id), `nameTaken`, `hasOpenShift`, `activeOwnerMemberships`. `SubscriptionRepository` unchanged (not needed).
- `service/OutletService`: `listAll`, `create`, `update`, `deactivate`, `reactivate` (existing `listAccessible` unchanged).
- `service/MerchantAccessGuard`: new dependency `PlanEntitlementService`; `requireOutlet` calls `requireOutletUsable` after the access row is found (non-members still get 404, never 402).
- `service/LoginService`: new dependency `PlanEntitlementService`; `listChoices` omits over-allowance outlets; `selectMerchant` calls `requireOutletUsable` (402) after the outlet/tenant active checks.
- `grpc/policy/GrpcTenantAccessPolicy`: `requireOutlets` (member and owner) filters the outlet ids through `usableOutletIds`; empty result -> the same PERMISSION_DENIED. Constructor unchanged. `CashierInsightGrpcHandler` goes through `requireOwnerOutlets`, so the AI scope is covered without touching it.
- `service/MembershipPlanCatalogService` (+ `dto/response/MembershipPlanResponse`): `OUTLET_ADDON_MONTHLY = 49000` (public constant, BigDecimal IDR like the other prices), new field `outletAddOnMonthlyPrice` returned on PRO and PREMIUM only (omitted on FREETIER via NON_NULL).
- `dto/CashierMapper`: `toMerchantOutletResponse(outlet, usable)`.

## Deviations (files outside the J1 reserved list, minimal and necessary)
1. `exception/runtime/PlanUpgradeRequiredException`: added constructor `(String message)`. The add-on messages cannot be expressed with the existing `PlanCode` constructor. Handler mapping (402) unchanged.
2. `entity/shared/audit/AuditLogEntity.Action`: added `OUTLET_CREATED`, `OUTLET_UPDATED`, `OUTLET_DEACTIVATED`, `OUTLET_REACTIVATED` (entity type `OUTLET`). `audit_logs.action` has no DB constraint, no migration needed.
3. `OutletService` injects `FnbOrderRepository` to reuse `lockTenant` / `lockOutlet` (J2 has both in the working tree). Depends on J2 keeping `lockTenant(UUID)` and `lockOutlet(UUID tenantId, UUID outletId)`.
4. `activeOwnerMemberships` lives in `OutletRepository` (reserved) instead of `TenantMembershipRepository` (not reserved); can be moved later.
5. `normalize()` in `OutletService` has its own small `ZoneId.of` validation (the existing ones are private in `FreeTierOnboardingService` and `MerchantBusinessSettingsService`); a third copy, flagged as duplicate candidate.

## Routes (all `/api/v1/merchant/outlets`, OWNERMERCHANT by security config + `guard.requireOwner(principal, principal.getOutletId())`)
- `GET` -> `ApiResponse<MerchantOutletListResponse>`: `outlets[]` (`id, name, timeZone, address, phoneNumber, active, usable`; all tenant outlets, oldest first), `allowedOutlets`, `activeOutlets`, `extraOutletPrice` (49000). Message "Daftar outlet diambil."
- `POST` body `MerchantOutletRequest {name @NotBlank<=120, timeZone @NotBlank<=64, address<=255 optional, phoneNumber<=32 optional}` -> 201 `ApiResponse<MerchantOutletResponse>` "Outlet dibuat."
- `PUT /{outletId}` same body -> 200 "Outlet diperbarui."
- `POST /{outletId}/deactivate` -> 200 "Outlet dinonaktifkan."
- `POST /{outletId}/reactivate` -> 200 "Outlet diaktifkan kembali."
- Existing `GET /api/v1/cashier/outlets` unchanged. Existing `/{outletId}/business-settings`, `/{outletId}/staff`, `/{outletId}/fnb/**` unaffected (different paths).

## Rules and messages
- Create: `lockTenant`, owner check, validation, name unique per tenant (pre-check -> 409 "Nama outlet sudah dipakai."), capacity check, persist outlet, `createDefaults` payment labels, access row for every active OWNERMERCHANT membership, audit `OUTLET_CREATED`.
- Capacity (create and reactivate): active outlets >= `allowedOutletCount` -> 402. Effective plan FREETIER -> "Fitur ini tersedia di paket Pro."; otherwise (also a Premium trial without paid row) -> "Outlet tambahan memerlukan add-on outlet (Rp49.000/outlet/bulan)."
- Over allowance at guard / select-merchant -> 402 "Outlet ini melebihi kuota outlet paket Anda. Tambahkan add-on outlet (Rp49.000/outlet/bulan) atau nonaktifkan outlet lain."
- Invalid zone -> 400 "Zona waktu outlet tidak valid."; blank/too long name -> 400 "Nama outlet wajib diisi dan tidak boleh melebihi 120 karakter." (bean validation 400 first for blank).
- Deactivate: `lockTenant` + `lockOutlet`; last active outlet -> 409 "Outlet aktif terakhir tidak dapat dinonaktifkan."; open shift (any member) -> 409 "Outlet masih memiliki shift terbuka. Tutup shift terlebih dahulu."; always allowed when over allowance. Already inactive / already active -> 200, no audit.
- Update only audits when something changed (before/after in `details`, `reason` null).
- Target outlet of another tenant or unknown -> 404 (`ResourceNotFoundException`, tenant-scoped lookup). Cashier cannot reach the routes (403 by security config).
- Audit `details` is `key=value` text (name, timeZone, address, phoneNumber, active) because the JSON escape helper is private elsewhere; actor = owner, outlet = target outlet, entityId = outlet id.

## Test impact (not edited)
- `new MerchantAccessGuard(` and `new LoginService(`: no manual construction in tests (guards are `@Mock`).
- `GrpcTenantAccessPolicyTest` / `CashierGrpcHandlerRejectionTest` build the policy manually with a mocked `PlanEntitlementService`: an unstubbed `usableOutletIds` returns an empty list now, so member/owner outlet tests need `when(planEntitlementService.usableOutletIds(...)).thenReturn(...)`.
- `PlanEntitlementServiceTest` constructor unchanged; its `EntityManager` mock now also needs `createQuery("select o.id from OutletEntity ...")` stubs for the new methods.
- `MembershipPlanCatalog` tests/Postman: PRO and PREMIUM now include `outletAddOnMonthlyPrice`.

## Open questions / risks
- `requireOutletUsable` adds one small query (ordered active outlet ids) to every `guard.requireOutlet`; the subscription query only runs when the tenant has 2+ active outlets.
- An owner whose JWT outlet is over allowance gets 402 on every owner route (including outlet management) because the guard checks the token outlet; recovery = log in again (`listChoices` omits that outlet, `selectMerchant` into a usable one). Deactivating the outlet the token points to is allowed and ends that session's access (404 afterwards).
- Deactivate only checks open shifts (per spec); unpaid OPEN orders do not block it.
- Name uniqueness pre-check is exact-match (mirrors `uk_outlets_tenant_name`); a race is covered by the tenant lock plus the DB constraint (generic 409).
- Reactivating an outlet that was created before others may rank ahead of them; this cannot exceed the allowance because capacity is checked first.

## Recommended test scenarios (T1)
Create: FREETIER 402 (Pro message); PRO with `extra_outlets=0` 402 (add-on message); PRO `extra_outlets=1` allows second, third 402; Premium trial without paid row 402; duplicate name 409; invalid zone 400; new outlet has 4 default labels (existing defaults) and owner access row; cashier and other-tenant outlet id (404/403); audit rows for create/update/deactivate/reactivate; update with no change writes no audit.
Deactivate: last active 409; open shift 409; success then `GET` shows `active=false`; deactivate while over allowance succeeds.
Downgrade: expire/stop the paid row with 2 active outlets -> second outlet `usable=false`, guard 402 on it, `selectMerchant` 402, omitted from `listChoices`, first outlet works; re-extend row -> access restored; reactivate over allowance 402.
gRPC: `requireOwnerOutlets` / `requireMemberOutlets` exclude the over-allowance outlet; only-over-allowance outlet -> PERMISSION_DENIED.
Catalog: PRO and PREMIUM carry `outletAddOnMonthlyPrice=49000`, FREETIER does not.
Migration: `extra_outlets` negative or `> 0` on a FREETIER row rejected by the CHECK.
