# TASK-057 W4 report: gRPC security unit tests

Status: tests written; `.\mvnw.cmd -q -o -DskipTests test-compile` succeeded (no output). Tests NOT executed (policy; W6 runs them). No production code, pom.xml, .ai or other test files changed.

All classes are in `src/test/java/org/lite/project/cashiersaas/unit/grpc/`, annotated `@Tag("unit")` (picked up by profile `-Punit-tests`, `test.groups=unit`, `**/*Test.java`).

| Class | @Test methods | Approx. executions (parameterized expanded) |
|---|---|---|
| GrpcServiceKeyInterceptorTest | 12 | 12 |
| GrpcRateLimiterTest | 8 | 8 |
| GrpcTenantAccessPolicyTest | 18 | 46 |
| GrpcSecurityPropertiesValidationTest | 7 | 12 |
| CashierGrpcHandlerRejectionTest | 10 | 15 |

## Coverage summary
- Interceptor: valid key passes through (same listener, `startCall(call, headers)`); missing header, wrong key, prefix-of-real-key, extra trailing char, case difference, empty value -> UNAUTHENTICATED, `next.startCall` never called, no-op listener, description never contains the real or supplied key; rejected listener callbacks do not touch downstream; RESOURCE_EXHAUSTED when rps=1 exhausted (fake clock), recovery after time advance, and unauthenticated calls do not consume tokens.
- Rate limiter: burst to capacity, reject, continuous refill (250 ms at 10 rps = 2 grants), partial accumulation, cap at capacity after 1 h idle, clock going backwards, rps 0/-3 -> IllegalArgumentException, deterministic 8-thread x 50 attempts with frozen clock grants exactly capacity. Refill assertions use half-token margins to avoid floating-point edge cases.
- Tenant policy: allowlisted (plain, whitespace, upper-case, mixed-case config entry), non-allowlisted PERMISSION_DENIED without leaking ids, empty/null allowlist denies all, blank config entries ignored, null/blank/malformed -> INVALID_ARGUMENT, invalid UUID in config -> IllegalStateException (message does not echo value), clampLimit table (-5,0,1,10,50,51,100000,Integer.MAX) plus default>max config, escapeLikePattern (`%`, `_`, backslash, mixed, null, empty, plain, unicode), shortTenantRef (long, whitespace, short, null, blank).
- Validation: uses jakarta.validation with hibernate-validator (available via spring-boot-starter-validation; compiled, to be confirmed at runtime by W6). Null/blank/short key, rps 0/-1, catalog limits <1 are violations; 16-char key and defaults pass.
- Handler rejections: catalog and order handlers with Mockito mocks for EntityManager/CriteriaBuilderFactory/mapper and a real policy: blank tenant, missing tenant, invalid UUID, non-allowlisted tenant (no id leakage), null request, blank order_number; each asserts `onError` with the proper code, no `onNext`/`onCompleted`, and `verifyNoInteractions` on EntityManager, CriteriaBuilderFactory and mapper.

## Not covered / caveats
- Happy paths of the handlers (Blaze fluent query, LIKE escape clause wiring, limit applied via setMaxResults, mapper output) and INTERNAL error mapping are left to the W5 integration test.
- `@GrpcGlobalServerInterceptor` registration and Spring property binding (`CASHIER_GRPC_SERVICE_KEY`, `@Validated` failing context start) need a Spring context; not unit-testable here.
- `Metadata` ASCII marshaller rejects non-ASCII header values at the gRPC layer, so non-ASCII key tests were not written.
- Hibernate Validator needs an EL implementation at runtime; assumed present through the starter (not executed).
