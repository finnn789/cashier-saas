# TASK-059-B: gRPC tenant authorization via database

Status: code and tests written; NOT compiled, NOT run (policy: Java workers must not run Maven; the orchestrator compiles and tests).

## Behavior

A gRPC request tenant is allowed only when `TenantRepository.existsActiveFnbTenant(tenantId)` is true: the `tenants` row exists, `tenants.status = 'ACTIVE'`, and it has a business type with `code = 'FNB'` and `is_active = true` (same predicates as `TenantEntity.isActive()` / `isFnbEnabled()` and `MerchantAccessRepository`).

Disallowed tenant (missing, disabled, pending profile, non-F&B, no business type): status `PERMISSION_DENIED`, message exactly `Tenant is not permitted for this service` (unchanged from the previous policy). Identical for all cases; no tenant id echoed. Blank/null tenant: `INVALID_ARGUMENT` "tenant_id must not be blank"; non-UUID: `INVALID_ARGUMENT` "tenant_id must be a valid UUID" (both unchanged, checked before any DB access). A repository exception is not swallowed; the handlers' existing catch maps it to `INTERNAL`.

Unchanged: service-key interceptor, rate limiter, limit clamp, LIKE escape, catalog/order handlers (they still call `requireAllowedTenant`), tenant-scoped queries.

## Changed files

Main:
- `src/main/java/org/lite/project/cashiersaas/grpc/policy/GrpcTenantAccessPolicy.java` (constructor now `(TenantRepository, GrpcSecurityProperties)`; allowlist parsing removed)
- `src/main/java/org/lite/project/cashiersaas/configuration/GrpcSecurityProperties.java` (`allowedTenantIds` field and its imports/comment removed)
- `src/main/java/org/lite/project/cashiersaas/repository/TenantRepository.java` (added `existsActiveFnbTenant(UUID)` + `StatusConstants` import)
- `src/main/resources/application.properties` (removed `app.grpc.allowed-tenant-ids=${CASHIER_GRPC_ALLOWED_TENANT_IDS:}` and the "empty allowlist" comment line; added one comment line)

Tests:
- `src/test/java/.../unit/grpc/GrpcTenantAccessPolicyTest.java` (rewritten: mocked `TenantRepository`; active, upper-case, whitespace, inactive, unknown, indistinguishable, repository failure, null/blank/malformed with `verifyNoInteractions`, clamp, escape, shortRef kept)
- `src/test/java/.../unit/grpc/GrpcSecurityPropertiesValidationTest.java` (removed the `getAllowedTenantIds()` assertion only)
- `src/test/java/.../unit/grpc/CashierGrpcHandlerRejectionTest.java` (policy built with mocked repository; two test names renamed; assertions unchanged)
- `src/test/java/.../integration/grpc/GrpcSecurityIntegrationTest.java` (tenants seeded with FNB business type; tests for disabled, pending, non-F&B, no-business-type, unknown denied with identical result for both RPCs; toggle test proves no stale decision; all other cases preserved)
- `src/test/java/.../integration/support/AbstractPostgresIntegrationTest.java` (removed `GRPC_ALLOWED_TENANT_A1/A2` constants, `app.grpc.allowed-tenant-ids` registration, unused `UUID` import)

## Removed config (for docs, Worker D)

- Environment variable `CASHIER_GRPC_ALLOWED_TENANT_IDS`
- Property `app.grpc.allowed-tenant-ids` (`GrpcSecurityProperties.allowedTenantIds`)
- Remaining gRPC config: `CASHIER_GRPC_SERVICE_KEY` (required, >= 16 chars), `app.grpc.max-catalog-limit`, `app.grpc.default-catalog-limit`, `app.grpc.requests-per-second`
- Docs should say: a tenant is allowed when it exists, is active and has F&B enabled in the database.
- Note: tests no longer depend on fixed tenant ids in the base class; `GrpcSecurityIntegrationTest` defines its own ids. Historical files mentioning the allowlist (`tasks/TASK-057.md`, `reports/TASK-057-*.md`, `.ai/MEMORY.md`, `.env.example`, `docker-compose.yml`, `README.md`) are outside my reserved scope.

## Test status

Written, not run. Suggested: `GrpcTenantAccessPolicyTest`, `GrpcSecurityPropertiesValidationTest`, `CashierGrpcHandlerRejectionTest`, `GrpcSecurityIntegrationTest`.

## Points to double-check when compiling

- Blaze query in `TenantRepository.existsActiveFnbTenant` (`create(em, UUID.class).from(...).innerJoin(...).select("tenant.id")...setMaxResults(1).getResultList()`), modeled on `MerchantAccessRepository`.
- `GrpcSecurityIntegrationTest.seedTenant` passes a null UUID for `business_type_id` through `JdbcTemplate.update` (PostgreSQL driver with untyped null) for `TENANT_NO_BUSINESS_TYPE`; if it fails, use `Types.OTHER`/cast.
- The test inserts a business type `OTHER` and deletes it in `@AfterAll`; `FNB` is created with `ON CONFLICT DO NOTHING` and left in place (same as `ApiTestSupport`).

## Remaining / deviations

None known. I did not touch `ApiTestSupport`, `MerchantFixture`, `RegisterService`, or `StaffUserEntity`.
