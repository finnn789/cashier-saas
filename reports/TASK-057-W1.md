# TASK-057 W1 report: Java gRPC server hardening

Status: implemented, compile-only check passed (`mvnw.cmd -q -o -DskipTests compile`, no output = success). Tests not run (policy; W6 runs them).

## Changed files
- NEW `src/main/java/org/lite/project/cashiersaas/configuration/GrpcSecurityProperties.java`
- NEW `src/main/java/org/lite/project/cashiersaas/grpc/interceptor/GrpcServiceKeyInterceptor.java`
- NEW `src/main/java/org/lite/project/cashiersaas/grpc/policy/GrpcRateLimiter.java`
- NEW `src/main/java/org/lite/project/cashiersaas/grpc/policy/GrpcTenantAccessPolicy.java`
- MOD `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierCatalogGrpcHandler.java`
- MOD `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierOrderGrpcHandler.java`
- MOD `src/main/resources/application.properties` (only the gRPC block; the pre-existing unrelated `open-in-view=false` diff preserved)
- `application-demo.properties`: unchanged (key comes from env; nothing needed).

## Public signatures (test workers code against these)
- `GrpcSecurityProperties` (`@Component @Validated @ConfigurationProperties("app.grpc")`, Lombok getters/setters): `String serviceKey` (`@NotBlank @Size(min=16)`), `List<String> allowedTenantIds` (default empty), `int maxCatalogLimit=50`, `int defaultCatalogLimit=10`, `int requestsPerSecond=50` (all `@Min(1)`).
- `GrpcRateLimiter`: `@Autowired GrpcRateLimiter(GrpcSecurityProperties)`, `GrpcRateLimiter(int requestsPerSecond, java.util.function.LongSupplier nanoClock)`, `boolean tryAcquire()`. Capacity = rps, continuous refill, starts full; synchronized.
- `GrpcTenantAccessPolicy` (`@Component`): ctor `(GrpcSecurityProperties)` parses allowlist at construction (invalid UUID -> `IllegalStateException`); `UUID requireAllowedTenant(String raw)` throws `StatusRuntimeException` (INVALID_ARGUMENT for null/blank/invalid with messages "tenant_id must not be blank" / "tenant_id must be a valid UUID"; PERMISSION_DENIED "Tenant is not permitted for this service"); `int clampLimit(int)` (<=0 -> min(default,max), >max -> max); `static String escapeLikePattern(String)` (null -> ""); `static String shortTenantRef(String)` (first 8 chars, extra helper for logging).
- `GrpcServiceKeyInterceptor` (`@GrpcGlobalServerInterceptor`, which is itself meta-annotated `@Component`): ctor `(GrpcSecurityProperties, GrpcRateLimiter)`; public constant `Metadata.Key<String> SERVICE_KEY_HEADER` ("x-internal-service-key"). Missing/wrong -> `call.close(UNAUTHENTICATED "Invalid or missing service credentials")`; rate exceeded -> `RESOURCE_EXHAUSTED "Rate limit exceeded"`; both return a no-op `ServerCall.Listener`.
- Handlers: public method signatures unchanged; constructor gained a `GrpcTenantAccessPolicy` field (Lombok `@RequiredArgsConstructor`, last parameter).

## Acceptance mapping
- Service auth: interceptor, constant-time `MessageDigest.isEqual` on UTF-8 bytes, key never logged/echoed.
- Rate limit: token bucket applied after auth check in the interceptor.
- Tenant allowlist: both handlers call `requireAllowedTenant`; empty list denies all; StatusRuntimeException is passed to `onError` (not turned into INTERNAL).
- Limits: catalog `setMaxResults(clampLimit(limit))` always applied.
- LIKE: query lowercased, escaped (`\`, `%`, `_`), wrapped in `%...%`, `.like().value(p).escape('\\')` (Blaze `EscapeBuilder.escape(char)` verified in 1.6.20 sources; compiles).
- Order lookup: tenant filter kept, NOT_FOUND message "Order not found" (no echo of order number / tenant).
- Logging: only short tenant ref (8 chars) and exception simple class name; no request dump, no stack trace.
- Reflection: `grpc.server.reflection-service-enabled=false`.

## Deviations / notes
- `GrpcSecurityProperties` is registered via `@Component` instead of `@EnableConfigurationProperties` (JwtConfiguration is outside the reserved paths). Binding and `@Validated` behave the same.
- Negative `limit` is no longer rejected with INVALID_ARGUMENT; per the contract it maps to the default limit.
- Response `tenant_id` is now the canonical UUID string (lowercase, trimmed) rather than the raw trimmed input.
- The `x-internal-service-key` header is the same name as the HTTP TASK-053 header, as per contract.
- `app.grpc.service-key=${CASHIER_GRPC_SERVICE_KEY}` has no default: any context start without the env var (including existing integration tests, e.g. `AbstractPostgresIntegrationTest`) fails until `app.grpc.service-key` (>=16 chars) is supplied. W5 must add test properties (it owns that file). Unit tests that construct classes directly are unaffected.

## Remaining work
- W4 unit tests, W5 integration test + test property setup, W6 test run. Python client (W2) and compose (W3) are separate.
