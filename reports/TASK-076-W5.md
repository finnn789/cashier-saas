# TASK-076 W5 report (Java tests)

Status: written, NOT compiled, NOT run (policy: orchestrator runs `mvnw -B -DskipTests test-compile`, tests only on user request).
Writes limited to `src/test/**` and this report.

## Existing tests fixed
- `src/test/resources/application-test.properties`: `app.payment.mock-gateway.enabled=true` (FnbInventoryFlowIntegrationTest MOCK_GATEWAY).
- `unit/service/MemberSyncPublisherTest`, `unit/component/MemberSyncListenerTest`, `unit/service/MemberSyncClientTest`: new `status` argument. Added: publisher carries `DISABLED`; client test `deactivatedMemberIsSentAsDisabled` asserts the PUT body `status` is `DISABLED`. Fixtures (`member-sync.example.json`) still say `ACTIVE`, event built from fixture now passes `body.status`.
- `unit/grpc/GrpcTenantAccessPolicyTest`: constructor with a mocked `MembershipOutletAccessRepository`; new tests for `requireMemberOutlets` (blank/null/malformed -> INVALID_ARGUMENT without DB; empty list -> PERMISSION_DENIED with the same tenant message; non-empty list returned, whitespace/uppercase accepted).
- `unit/grpc/CashierGrpcHandlerRejectionTest`: constructor + `user_id` on the blank-order-number case; new cases for missing user_id, invalid user_id, outsider (no outlet access) for catalog and order, no repository/query touched.
- `integration/grpc/GrpcSecurityIntegrationTest`: requests carry `user_id` (helper `memberOf(tenant)` picks a seeded member; unknown/denied tenants use USER_A1 because the tenant check fires first); `getPriceAmount()` = `"18000.00"`, `getTotal()` = `"75000.00"` / `"25000.00"`; seeded via JDBC: staff users, `tenant_memberships`, `membership_outlet_access` (`seedStaffAccess/seedMember`), a second outlet of tenant A1 with a product and an order.
- Grepped `src/test` for `memberChanged`, `MemberSyncRequestedEvent(`, `new GrpcTenantAccessPolicy`, `getTotalAmount`, `getPrice()` on proto types, `AuditLogEntity.Action`, `audit_logs`: no other breakage. No existing test counts audit rows after checkout (only `FNB_RECIPE_CHANGED`).

## New tests
Unit (`@Tag("unit")`, Mockito, `unit/service/`):
- `MerchantStaffServiceTest`: list (incl. inactive); deactivate (revokes by membership, audit `STAFF_DEACTIVATED`/`TENANT_MEMBERSHIP`/target userId, publishes `DISABLED`); deactivate idempotent (no revoke/audit/publish); reactivate (audit + `ACTIVE`); reactivate idempotent; owner/other-tenant/unknown target -> `ResourceNotFoundException` and no side effects; reset rejected with exact conflict message when another active membership exists (no encode/revoke/audit); reset stores encoded hash, `revokeForUserExcept(user, null, any)`, audit without reason/details; 200 generated passwords are 12 chars, `[A-Za-z2-9]`, letter+digit, no `0O1lI`, random.
- `AccountPasswordServiceTest`: wrong current -> `InvalidCredentialsException`; same -> `InvalidOperationException("Password baru harus berbeda.")`; success -> new hash and `revokeForUserExcept(userId, callerSessionId, any)`.
- `MockFnbGatewayServiceTest`: disabled `initiate` -> `InvalidOperationException("Metode pembayaran tidak tersedia.")`, `resolve` -> `ResourceNotFoundException`; no interaction with guard/repositories (so before `lockTenant`). Field set with `ReflectionTestUtils` ("enabled").
- `FnbOrderCancellationServiceTest`: cashier + discount -> `AccessForbiddenException` with the contract message, order stays OPEN, no audit; owner not blocked (discount repo is not even consulted); cashier without discount can cancel.

Integration:
- `integration/identity/MerchantStaffIntegrationTest` (new, real PostgreSQL + security chain, `ApiTestSupport.onboardOwner/onboardCashier`):
  - list (fields, email, active, joinedAt) + tenant B owner gets 404 on A's outlet and empty list on its own.
  - deactivate: old merchant token -> **404** on `/api/v1/cashier/.../fnb/orders` (JWT is stateless; `MerchantAccessGuard` rejects inactive membership); refresh with the revoked session cookie -> **401**; no non-revoked sessions left; login still 200 but select-merchant -> **404**; list shows `active=false`; audit row once; second deactivate 200 without a new audit row; reactivate -> 200, audit once, repeat no new row, cashier can log in, select merchant and call the API again.
  - password reset: 12-char temporary password, old password login 401, temporary login 200, old session refresh 401, audit row, temporary password not present in audit `details`/`reason`.
  - password reset conflict: second active membership inserted via SQL in tenant B -> 409 with "terhubung ke bisnis lain", old password still works, no audit.
  - authorization: cashier token on all four routes -> 403, anonymous -> 401, owner of tenant B -> 404 on A's outlet path and 404 on its own path with A's user id, owner/unknown user as target -> 404; membership still active and no `STAFF_%` audit rows.
  - self password change (merchant bearer): anonymous 401, wrong current 401, same password 400, short password 400, then success; old password login 401, new login 200, caller's own session refresh 200, another session refresh 401. Also with an account-scoped bearer (owner) -> 200, old password 401.
  - cancel rule: owner creates discounted + plain order; cashier cancel of discounted -> 403 (body mentions "diskon", order OPEN, no cancel audit); cashier cancel of plain -> 200; owner cancel of discounted -> 200 + `FNB_ORDER_CANCELLED` audit.
  - checkout audit: cashier POS order writes exactly one `FNB_ORDER_CREATED` row (`SALES_ORDER`, details starts with `orderNumber=<n> total=`); idempotent replay writes none.
- `GrpcSecurityIntegrationTest` additions: missing/blank/malformed `user_id` -> INVALID_ARGUMENT for both RPCs; outsider, user of another tenant, deactivated membership, DISABLED staff user, unknown user -> PERMISSION_DENIED with the unchanged tenant message and no id leak; toggling `tenant_memberships.active` flips access immediately; catalog limited to the caller's outlets (outlet-2 user sees only "Kopi Cabang Dua", price `"21000.00"`); order of another outlet -> NOT_FOUND with the same description as a missing order, own-outlet user finds it with total `"12500.50"`. A second outlet could be seeded through plain JDBC, so this is fully covered.

## Assumptions about status codes
- Existing access token of a deactivated cashier: 404 (not 401) because the JWT is not checked against the session; refresh: 401 (`SessionInvalidException` extends `AuthenticationRequiredException`); select-merchant with inactive membership: 404 (`findAccessible` requires `membership.active`).
- Weak/same password: 400 (`MethodArgumentNotValidException` / `InvalidOperationException`); wrong current password: 401 (`InvalidCredentialsException`).
- Refresh calls use `SecurityMockMvcRequestPostProcessors.csrf()` plus the `cashier_refresh` cookie parsed from the `Set-Cookie` header (CSRF is required on refresh).
- Cashier list endpoint `GET /api/v1/cashier/outlets/{id}/fnb/orders` without params returns 200 for a member (used as the "is access alive" probe).

## Not covered / notes
- No integration test for the mock gateway disabled flag (needs a second Spring context with `app.payment.mock-gateway.enabled=false`); covered by the unit test. `FnbInventoryFlowIntegrationTest` covers enabled.
- Observation, not asserted: after reactivation the pre-deactivation merchant access token (max 15 min TTL) works again because tokens are stateless; only sessions/refresh are revoked.
- Rate limit filter test for `/api/v1/auth/password` not added (`RateLimitFilterTest` untouched).
- Nothing compiled; possible compile risks: Mockito `isNull()` generic inference for the `UUID` argument in `MerchantStaffServiceTest`, and JsonPath `$.data.length()` used with `ApiTestSupport.read` (returns "1").
- `GrpcSecurityIntegrationTest` seed: `seedOrder` total parameter changed from `long` to `double` to allow `12500.5`.
