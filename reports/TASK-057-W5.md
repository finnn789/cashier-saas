# TASK-057 W5 report: gRPC security integration tests

Status: written; `mvnw.cmd -q -o -DskipTests test-compile` passed (no output). Tests NOT run (W6 runs `mvnw verify` with Docker).

## Files
- MOD `src/test/java/org/lite/project/cashiersaas/integration/support/AbstractPostgresIntegrationTest.java` (properties and constants only; Postgres container and datasource properties unchanged)
- NEW `src/test/java/org/lite/project/cashiersaas/integration/grpc/GrpcSecurityIntegrationTest.java`
- NEW `src/test/java/org/lite/project/cashiersaas/integration/grpc/GrpcServerPortHolder.java`
- NEW `reports/TASK-057-W5.md`

No production code, pom.xml, unit tests or `.ai/*` touched. No commit.

## Wiring
- Context start fix: second `@DynamicPropertySource` (`grpc`) in the base class sets `app.grpc.service-key` (fixed test-only value `GRPC_TEST_SERVICE_KEY`, 37 chars), `app.grpc.allowed-tenant-ids` (A1,A2), `grpc.server.port=0`, and `app.grpc.requests-per-second=1000` (so the global limiter never interferes). Every existing integration test context now starts (they inherit it).
- Port: `application-test.properties` already had `grpc.server.port=0`; the base class repeats it explicitly. net.devh 3.1.0 has no `@LocalGrpcPort`, and `GrpcServerFactory.getPort()` returns the configured 0, so `GrpcServerPortHolder` (an `ApplicationListener<GrpcServerStartedEvent>`, registered through a nested `@TestConfiguration`) reads `event.getServer().getPort()`. Each cached context gets its own free port, so there is no 9090 clash.
- Tenants: `GRPC_ALLOWED_TENANT_A1` = `a1a1a1a1-0000-4000-8000-0000000000a1`, `GRPC_ALLOWED_TENANT_A2` = `a2a2a2a2-...a2` are public constants in the base class (the allowlist is read at startup). Tenant C (`c3c3c3c3-...c3`, real, not allowlisted) and a never-inserted tenant (`dddddddd-...dd`) are constants in the test.
- Data: seeded in `@BeforeAll` with explicit-id native SQL via `JdbcTemplate` (tenants, outlets, categories, products, sales_orders; no HTTP onboarding because it generates random tenant ids). `@AfterAll` shuts down the channel and truncates all public tables except `flyway_schema_history` and `business_types` (same rule as `ApiTestSupport.truncateTenantData`).
  - A1: category Minuman (Kopi Susu available, Kopi Arsip archived, Kopi Habis unavailable), category Snack with 60 available "Bulk Item NNN", orders ORD-A1-0001 (CONFIRMED/PAID, 75000) and ORD-A1-0002 (NEW/UNPAID, 25000).
  - A2: Kopi Tetangga, "Promo 100% Off", order ORD-A2-0001.
  - C: Kopi Tenant C, order ORD-C-0001.
- Client: real `ManagedChannel` (plaintext, 127.0.0.1, real port), generated `CashierServiceGrpc` blocking stub, header `x-internal-service-key` attached with `MetadataUtils.newAttachHeadersInterceptor` (omitted entirely for the no-key case). The header name is written literally in the test, not taken from production code.

## Cases (22 tests)
Auth: no key (both RPCs); wrong / empty / key+"x" / uppercased key (both RPCs, description never contains the key or tenant id); strict prefix of the real key.
Tenant: A1 sees only its own available, non-archived products (archived, unavailable and A2's "Kopi" excluded; id, price, category, isAvailable verified); query is case-insensitive, trimmed and matches category name; A2 sees only its products and none of A1's bulk items; tenant C -> PERMISSION_DENIED for both RPCs (description leaks neither tenant id nor order number); never-inserted tenant gives the identical PERMISSION_DENIED description as C; blank, whitespace, "not-a-uuid", "12345" tenant -> INVALID_ARGUMENT for both RPCs.
Limits (query "bulk item", 60 matches): limit 0 -> 10, limit -3 -> 10, 1000 and Integer.MAX_VALUE -> 50, 5 -> 5, 1 -> 1; empty query with limit 0 -> 10.
LIKE: queries `%`, `_`, `%%`, `bulk%`, `bulk item 00_`, `_ulk item 001`, `\` return nothing on A1; "100%" finds exactly "Promo 100% Off" on A2 and nothing on A1; `_` on A2 returns nothing.
Orders: own orders return the right order number, status, payment status, total, non-empty createdAt, tenant id; A1 order number requested as A2 -> NOT_FOUND and the reverse, descriptions do not contain the order number or either tenant id; unknown order -> NOT_FOUND with the same description as the cross-tenant miss; blank order_number -> INVALID_ARGUMENT.
Reflection: `grpc.reflection.v1alpha` and `grpc.reflection.v1` ServerReflectionInfo (with a valid key) both end in UNIMPLEMENTED with no response (grpc-services 1.85.1 stubs are on the classpath, so nothing skipped).

## Skipped
- Rate limiting (RESOURCE_EXHAUSTED): would need a separate context with a tiny `requests-per-second`; covered by W4 unit tests (`GrpcRateLimiter`).
- No missing test dependencies: grpc-stub (MetadataUtils), grpc-netty-shaded (channel) and grpc-services (reflection stubs) are available transitively; no pom change.

## Risks
- Not executed; seed SQL column lists were taken from `V1__initial_schema.sql` (all NOT NULL columns supplied). First run may reveal a mismatch with Hibernate mappings (e.g. a column the entity requires non-null in memory), fix inside this reservation.
- Ordering assumption: the test class truncates all tenant data in `@AfterAll` like `FnbPayFirstFlowIntegrationTest`; JUnit runs classes sequentially, so no cross-class clash. If classes ever run in parallel, the shared database would conflict.
- The test adds one extra Spring context (nested `@TestConfiguration` makes the context key unique), so one more gRPC server and Hikari pool (max 10) live in the cached-context set; Postgres default max_connections (100) is sufficient.
- Rate limiter is set to 1000 rps for all test contexts, so the integration tests do not exercise the default of 50.
