# TASK-076: Flow controls group A — staff deactivation + password, mock gateway flag, cancel rule, checkout audit, gRPC money + user/outlet scoping, drop CPU tool

Status: DELEGATED (2026-11-13). Memory version at delegation: 70. Branch `fix/flow-controls-a` from `refactor/pivot-cashier-ai` @ `038a2f7` (clean tree = baseline). Source: business-flow audit of 2026-11-13 (group A) and the user's decisions of 2026-11-13 (recorded in `.ai/DECISIONS.md` "Flow controls A").

## Global rules (every worker)
1. Read `AGENTS.md` (DTO/code design, no records for DTOs, `ApiResponse`, no wildcard generics, no `@Deprecated`, existing packages only) and the touched classes before writing.
2. Write ONLY your reserved paths plus your report `reports/TASK-076-W<n>.md`. Need another file? Stop and write the need in the report.
3. Reuse existing code (repositories, guard, `AuditLogEntity.recordAction`, `DigestService`, `PasswordEncoder` bean, `PhoneNumberNormalizer`, `MemberSyncPublisher`). No duplicate helpers, no new utility classes.
4. Never run Maven, pytest, npm or the app. Running `uv run python -m grpc_tools.protoc` for stub generation (W4) is allowed.
5. Never log passwords, temporary passwords, tokens, phone numbers or keys.
6. Messages to users are Indonesian, like the existing ones.
7. Report: changed files, contract points done, deviations, open questions.

## Contract

### C1 Staff management (owner) — W1
Routes in a NEW `controller/MerchantStaffController.java` (`/api/v1/merchant/**` = OWNERMERCHANT by security config; the service still calls `guard.requireOwner(principal, outletId)`):
- `GET /api/v1/merchant/outlets/{outletId}/staff` -> `ApiResponse<List<MerchantStaffResponse>>`: every CASHIER membership of the principal's tenant that has an outlet-access row for `outletId`, active and inactive. Fields: `userId`, `fullName`, `email`, `phoneNumber`, `active` (membership.active), `joinedAt` (membership.createdAt, ISO instant). Sorted by fullName.
- `POST .../staff/{userId}/deactivate` and `POST .../staff/{userId}/reactivate` -> `ApiResponse<MerchantStaffResponse>`.
- `POST .../staff/{userId}/password-reset` -> `ApiResponse<MerchantStaffPasswordResetResponse>` with `userId` and `temporaryPassword` (shown once, never stored in plain text).
Rules (service `service/MerchantStaffService.java`, `@Transactional`):
- Target = membership of (userId, principal tenant) with role CASHIER and outlet access for `outletId`; otherwise `ResourceNotFoundException` (also for OWNERMERCHANT targets and other tenants; no existence evidence).
- `TenantMembershipEntity.deactivate()` / `reactivate()` (named methods; deactivate on inactive or reactivate on active = no-op, idempotent).
- Deactivate revokes every non-revoked `AuthSessionEntity` whose `membership` is that membership (new `AuthSessionRepository.revokeForMembership(UUID membershipId, Instant now)`). Existing access already ends immediately because `MerchantAccessRepository.findMember/findOutlet` require `membership.active` — do not change those.
- Deactivate/reactivate publish member sync with status `DISABLED` / `ACTIVE` (after commit, existing listener). Extend `MemberSyncRequestedEvent` with a `String status` field (`ACTIVE`|`DISABLED`) and update every existing publisher call to pass `ACTIVE`; `MemberSyncClient` sends `event.getStatus()` instead of the literal.
- Password reset: allowed only when the target user has NO active membership in any other tenant; otherwise `OperationConflictException("Akun kasir ini terhubung ke bisnis lain; minta kasir mengganti password sendiri.")`. Generate a 12-character random password from `SecureRandom` (letters without ambiguous `0O1lI` + digits, at least one digit and one letter), store via `PasswordEncoder` through a named `StaffUserEntity.changePasswordHash(String)` method, revoke ALL non-revoked sessions of that user (new `AuthSessionRepository.revokeForUser(UUID userId, Instant now)`).
- Audit (W1 adds the constants to `AuditLogEntity.Action`): `STAFF_DEACTIVATED("TENANT_MEMBERSHIP")`, `STAFF_REACTIVATED("TENANT_MEMBERSHIP")`, `STAFF_PASSWORD_RESET("STAFF_USER")`, entityId = target userId, outlet = context outlet. ALSO add `FNB_ORDER_CREATED("SALES_ORDER")` (used by W2).
- Deactivate/reactivate an already matching state still returns 200 but writes no audit and publishes nothing.

### C2 Self password change — W1
- `POST /api/v1/auth/password`, any authenticated principal (`AccountPrincipal` or `TenantPrincipal`); add an explicit `.requestMatchers(HttpMethod.POST, "/api/v1/auth/password").authenticated()` in `SecurityConfiguration`. Body `ChangePasswordRequest` (`currentPassword` NotBlank, `newPassword` with the SAME validation annotations as `RegisterUserRequest.password`). Wrong current password -> `InvalidCredentialsException`. new == current -> `InvalidOperationException("Password baru harus berbeda.")`.
- On success: new hash, revoke every other non-revoked session of the user (keep the caller's `sessionId`): `AuthSessionRepository.revokeForUserExcept(UUID userId, UUID keepSessionId, Instant now)` (one method may serve both C1 and C2 with a nullable keep id — pick one, no duplicate). Returns `ApiResponse.success("Password diperbarui.", null)`.
- Rate limit: add `/api/v1/auth/password` to `RateLimitFilter.AUTH_PATHS`.

### C3 Mock gateway flag — W2
- Property `app.payment.mock-gateway.enabled=${CASHIER_MOCK_GATEWAY_ENABLED:false}` in `application.properties`; `true` in `application-demo.properties`; documented in `.env.example` and `docker-compose.yml` (existing `${CASHIER_...}` style). `src/test/resources/application-test.properties` sets `true` (W5 owns that file).
- Read with `@Value` in `MockFnbGatewayService` (no new properties class). Disabled: `initiate` -> `InvalidOperationException("Metode pembayaran tidak tersedia.")` BEFORE any lock or write; `resolve` -> `ResourceNotFoundException`.

### C4 Cancel rule — W2
- `FnbOrderCancellationService.cancel`: after loading the order and before `order.cancel`, if the caller's membership role is CASHIER and `FnbDiscountRepository.byOrder(tenantId, outletId, orderId)` is present -> `AccessForbiddenException` (403) with message "Pesanan dengan diskon hanya dapat dibatalkan oleh owner." (use the existing exception; if its constructor takes no message, report and use it as is). OWNERMERCHANT unchanged.

### C5 Checkout audit — W2
- `FnbPosOrderService.checkout`: after the new order and lines are persisted (NOT on idempotent replay), audit `FNB_ORDER_CREATED`, entityId = order id, reason null, details `"orderNumber=<n> total=<plain total>"`.

### C6 gRPC proto (exact text) — W4 writes the file, W3 implements Java
`src/main/proto/cashier_service.proto`, messages become:
```
message GetCatalogItemRequest {
  string tenant_id = 1;
  string query = 2;
  int32 limit = 3;
  string user_id = 4;
}
message CatalogItem {
  reserved 3;
  reserved "price";
  string id = 1;
  string name = 2;
  bool is_available = 4;
  string category = 5;
  string price_amount = 6;
}
message GetOrderStatusRequest {
  string tenant_id = 1;
  string order_number = 2;
  string user_id = 3;
}
message GetOrderStatusResponse {
  reserved 5;
  reserved "total_amount";
  string tenant_id = 1;
  string order_number = 2;
  string status = 3;
  string payment_status = 4;
  string created_at = 6;
  string total = 7;
}
```
Money strings = `BigDecimal.toPlainString()` with scale 2 (e.g. `"12500.50"`). Service and other messages unchanged.

### C7 gRPC member/outlet scoping — W3 (Java)
- `GrpcTenantAccessPolicy.requireMemberOutlets(UUID tenantId, String rawUserId)` -> `List<UUID>` outlet ids: user_id blank/invalid UUID -> INVALID_ARGUMENT; no ACTIVE staff user with an ACTIVE membership in that tenant, or no active outlet access -> PERMISSION_DENIED with the same `TENANT_NOT_PERMITTED_MESSAGE` (no distinction). Use `MembershipOutletAccessRepository` (add one query there if needed; W3 owns that file).
- `CashierOrderGrpcHandler`: also filter `salesOrder.outlet.id IN outletIds`. `CashierCatalogGrpcHandler`: also filter `p.outlet.id IN outletIds`. Not found stays NOT_FOUND.
- Mappers: set `price_amount` / `total` per C6; remove `longValue()`.
- Update `docs/contracts/cashier-service.grpc.*` (W3 owns `docs/contracts/cashier-service*`).

### C8 Python AI — W4
- Proto per C6 + regenerate `Ai-services/app/integrations/grpc/generated/*` with the same command/flags the files were generated with (check headers / README / pyproject).
- `Identity` (`app/chat/service.py`) gets `java_user_id: UUID | None`; set it from the verified member row; FALLBACK identity has `None`. Graph state gets `java_user_id` (`app/ai/graph/state.py`); nodes set a new ContextVar `current_java_user_id` next to `current_tenant_id`; `tools_adapter` hides `user_id` from the LLM schema exactly like `tenant_id` and injects it. No java_user_id -> the order/catalog tools return their existing "not available" result without calling gRPC.
- `CashierGrpcClient.get_catalog_items/get_order_status` take `user_id` and send it; parse `price_amount` / `total` as `Decimal` strings; tool output keeps keys `price` / `total_amount` but with the string value (no float).
- Delete `app/tools/cpu.py`, its registration/export in `app/tools/__init__.py`, its tests, and mentions in `app/tools/README.md`. Do NOT edit `pyproject.toml`/`uv.lock`; report whether `psutil` is still used elsewhere.
- Update Python unit tests under `Ai-services/tests/**` that cover these files (do not run them).

## Workers and reserved writes (paths relative to `src/main/java/org/lite/project/cashiersaas/` unless absolute)
- **W1 (sonnet)** C1+C2: `entity/shared/identity/TenantMembershipEntity.java`, `entity/shared/identity/StaffUserEntity.java`, `entity/shared/audit/AuditLogEntity.java`, `repository/AuthSessionRepository.java`, `repository/TenantMembershipRepository.java`, `service/MerchantStaffService.java` (new), `service/AccountPasswordService.java` (new), `controller/MerchantStaffController.java` (new), `controller/AuthController.java`, `dto/request/identity/ChangePasswordRequest.java` (new), `dto/response/identity/MerchantStaffResponse.java` (new), `dto/response/identity/MerchantStaffPasswordResetResponse.java` (new), `dto/IdentityMapper.java`, `configuration/SecurityConfiguration.java`, `component/RateLimitFilter.java`, `component/MemberSyncRequestedEvent.java`, `service/MemberSyncPublisher.java`, `service/MemberSyncClient.java`, plus the existing callers of `MemberSyncPublisher` only on the call line. `docs/contracts/member-sync.md` (status now sent as ACTIVE or DISABLED).
- **W2 (sonnet)** C3+C4+C5: `service/MockFnbGatewayService.java`, `service/FnbOrderCancellationService.java`, `service/FnbPosOrderService.java`, `src/main/resources/application.properties`, `src/main/resources/application-demo.properties`, `.env.example`, `docker-compose.yml`.
- **W3 (sonnet)** C6 Java side + C7: `grpc/**`, `repository/MembershipOutletAccessRepository.java`, `docs/contracts/cashier-service*`.
- **W4 (sonnet)** C6 proto + C8: `src/main/proto/cashier_service.proto`, `Ai-services/app/**` files named in C8, `Ai-services/tests/**`.
- **W5 (sonnet, after W1-W4 are reviewed)** Java tests `src/test/**` incl. `application-test.properties`.
- **W6 (sonnet, after review)** Postman + docs (`postman/collections/**`, `docs/**` except contracts, `README.md`).

## Orchestrator gate
Diff vs `038a2f7` limited to reserved paths; greps: no `longValue()` in `grpc/`, no `"ACTIVE"` literal left in `MemberSyncClient`, no `CpuUsageTool` in `Ai-services/app`; `mvnw -B -DskipTests test-compile` after W5; full tests only when the user asks.
