# TASK-054 - Green `mvnw verify` with real integration tests

Result: `./mvnw.cmd verify` BUILD SUCCESS (about 42 s).
- Surefire (unit): `Tests run: 200, Failures: 0, Errors: 0, Skipped: 0`
- Failsafe (integration): `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`

Baseline before changes: the two existing integration tests could not run without a local DB, and
`src/test/resources/application.properties` shadowed the main `application.properties` (no datasource/Flyway config).

## Changed files
- `pom.xml`: test-scope `spring-boot-testcontainers`, `testcontainers-junit-jupiter`, `testcontainers-postgresql` (versions managed by Boot 4.1.1); `maven-failsafe-plugin` (groups `${it.groups}`=`integration`, includes `*Test`/`*Tests`, `classesDirectory` = target/classes so it does not run against the repackaged Boot jar); property `it.groups`. Surefire still runs only `unit`; integration runs in `verify` (`mvn test` stays DB-free).
- `src/test/resources/application.properties` (deleted) -> `src/test/resources/application-test.properties` (test JWT secret, issuer/audience, `refresh-cookie-secure=false`, `grpc.server.port=0`, open-in-view off). Main config now loads normally, so Flyway migrates real `db/migration` V1+V2 and Hibernate `ddl-auto=validate` checks them.
- `integration/support/AbstractPostgresIntegrationTest.java`: `@Tag("integration")`, `@SpringBootTest(RANDOM_PORT)`, `@ActiveProfiles("test")`, singleton `postgres:17-alpine` container, `@DynamicPropertySource`.
- `integration/support/ApiTestSupport.java`, `MerchantFixture.java`: drive the real API (register, intent, freetier, business-profile, catalog, tables), seed `business_types` FNB row, truncate tenant data.
- `CashierSaasApplicationTests`, `SecurityAuthorizationIntegrationTest`: now extend the base (no local DB needed).
- `integration/fnb/FnbPayFirstFlowIntegrationTest.java`: new (below).
- Main code: `repository/FnbSalesReportRepository.java` (see bug 1).

## Tests (FnbPayFirstFlowIntegrationTest, real security chain, real transactions)
1. `posOrderCashPaymentKitchenLifecycleAndSalesReport`: owner onboarding, category, product, table; POS order is NEW/UNPAID; prepare on unpaid order is rejected (4xx); CASH payment gives change 5000 exactly; order CONFIRMED/PAID; PREPARING, READY, COMPLETED; sales report shows 1 transaction, correct total, orderId, CASH, topProducts qty 2, paymentBreakdown.
2. `publicQrOrderWithIdempotencyKeyThenCashPayment`: public menu via table QR; public order NEW/UNPAID; same key+same body returns same order number and only 1 DB row; same key+different body returns 409; public status; staff pays; public status becomes PAID/CONFIRMED.
3. `paymentIdempotencyReplaysSameRequestAndRejectsDifferentBody`: same key+body replays same receipt, 1 payment row; same key+different amount returns 409; new key on paid order returns 409.
4. `concurrentPaymentsWithDifferentKeysSettleExactlyOnce`: 4 threads, different keys, released by a latch: exactly 1 success, 3 x 409, exactly 1 `payments` row, order PAID.
5. `tenantBCannotSeeOrPayTenantAOrder`: tenant B token gets 404 for A's order (own outlet path and A's outlet path); cross-tenant payment refused; A's order stays UNPAID with 0 payments.
Plus the 4 security tests and `contextLoads` now run on Testcontainers (proves V1/V2 migrations vs entities).

Cleanup: test class `@AfterAll` truncates all public tables except `flyway_schema_history` and `business_types` (CASCADE).

## Production findings
1. FIXED, real bug: `GET /api/v1/merchant/outlets/{id}/fnb/reports/sales` always returned 500 ("No matching constructor", Blaze-Persistence `selectNew`) because `SummaryRow`, `ProductRow` and `MethodRow` constructors took primitive `long` while the COUNT/SUM results are boxed `Long`. Fix: constructor parameters changed to `Long` (fields stay `long`). Minimal, 3 lines; this endpoint was previously untested.
2. NOT FIXED, important for TASK-055: `V1__initial_schema.sql` was dumped with pg_dump 18.3 and contains `SET transaction_timeout = 0;`, which fails on PostgreSQL 16 ("unrecognized configuration parameter"). Tests therefore use `postgres:17-alpine` (PG17+ required). The shared contract's `postgres:16-alpine` in docker-compose will FAIL to migrate; compose must use `postgres:17-alpine` or newer, or V1 must drop that line (main resources are outside my reservation).
3. Observation (not changed): several create endpoints (`/auth/register`, `/onboarding/freetier`, `/business-profile`, categories, products) return HTTP 200 with body `code: 201` because they lack `@ResponseStatus(CREATED)`. Test helper accepts either.
4. Observation: the migrations seed no `business_types` row; free-tier onboarding fails with 409 until an `FNB` row exists. Tests insert it; TASK-055's demo seed must include it (and any environment needs it).

## Remaining risks
- Docker is required for `mvn verify` (Testcontainers).
- Concurrency test relies on tenant row lock (`lockTenant`); passes consistently (ran 4 times), but timing-based tests could in theory be flaky on very slow machines (it asserts outcomes, not timing).
- Cashier-role (non-owner) token paths and MOCK_GATEWAY payments are not covered.
