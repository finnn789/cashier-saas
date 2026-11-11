# TASK-072: Rate limiter for the REST API (HTTP 429 + Retry-After)

Status: DELEGATED (2026-11-11). Memory version at delegation: 66. Branch `feature/fnb-stock-concurrency-tests` (TASK-070/071 are still uncommitted on it; the working tree also holds the user's unrelated dirty files, e.g. `FnbCatalogService.java` and `postman/**`: never touch those). User approval: on 2026-11-11 the user approved the defaults below ("Ya, buat TASK-072 dan jalankan worker"). Tests: the worker WRITES them, nobody runs Maven/tests until the user asks (the orchestrator only compiles).

## Why
The REST API has no rate limiter (only gRPC has `GrpcRateLimiter`). Login/register are open to brute force, the public QR endpoints are anonymous and order creation is open to spam.

## Decisions (binding)
- No new dependency. A servlet filter + in-memory token buckets. Reuse the algorithm of `GrpcRateLimiter`; do not copy-paste it.
- Key = authenticated user when authenticated (never the IP), else the client IP from `request.getRemoteAddr()`. NEVER read `X-Forwarded-For` yourself (spoofable). Behind a trusted proxy the operator sets `server.forward-headers-strategy=native` (Tomcat then fixes `getRemoteAddr()`); add that as a COMMENT line in `application.properties`, not active (the user has not said whether production runs behind a proxy; default assumption: no proxy).
- Single instance only. Mark with `// shortcut: in-memory buckets are per instance, move to Bucket4j + Redis when more than one instance runs.`

## Groups and default limits (`perMinute` = refill rate, `burst` = bucket capacity)
| Group enum | Matches (first match wins, in this order) | Key | perMinute | burst |
|---|---|---|---|---|
| `AUTH` | `POST /api/v1/auth/login`, `/api/v1/auth/register`, `/api/v1/auth/refresh` | IP | 10 | 10 |
| `PUBLIC_READ` | `GET` under `/api/v1/public/**` | IP | 240 | 60 |
| `PUBLIC_ORDER_WRITE` | any non-GET under `/api/v1/public/**` | IP | 20 | 10 |
| `QR_TOKEN` | EXTRA check, in addition to the two public groups, for paths `/api/v1/public/fnb/menu/{qrToken}` and `/api/v1/public/fnb/menu/{qrToken}/orders` | the `qrToken` path segment | 600 | 120 |
| `REALTIME_TICKET` | `POST` path matching `/api/v1/cashier/outlets/*/fnb/realtime-ticket`, authenticated | user | 10 | 5 |
| `AUTHENTICATED` | any other authenticated request | user | 300 | 60 |
| `ANONYMOUS_OTHER` | any other unauthenticated request (`/auth/logout`, `/auth/csrf`, realtime handshake `GET /api/v1/fnb/realtime`, ...) | IP | 120 | 30 |

Note vs the numbers shown to the user: `PUBLIC_READ` is 240 (not 120) because customers of one cafe share one Wi-Fi IP and also poll `/public/fnb/orders/{orderToken}/status`; report this in the final summary.
Skipped entirely (no bucket): `OPTIONS` requests (CORS preflight), `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/error`, and everything when `app.rate-limit.enabled=false`. Rate limiting is for `/api/**`; ignore any path outside `/api/`.
Both checks of `QR_TOKEN` + a public group must allow; a token wasted on the first allowed check when the second denies is acceptable.

## Reserved writes (ONLY these)
Main:
- `src/main/java/org/lite/project/cashiersaas/shared/TokenBucket.java` (NEW, same package as `AuthenticationConstants`)
- `src/main/java/org/lite/project/cashiersaas/grpc/policy/GrpcRateLimiter.java` (EDIT: delegate to `TokenBucket`; keep both public constructors and `tryAcquire()` EXACTLY; `GrpcRateLimiterTest` and `GrpcServiceKeyInterceptor` must keep working untouched)
- `src/main/java/org/lite/project/cashiersaas/configuration/RateLimitProperties.java` (NEW)
- `src/main/java/org/lite/project/cashiersaas/component/HttpRateLimiter.java` (NEW)
- `src/main/java/org/lite/project/cashiersaas/component/RateLimitFilter.java` (NEW)
- `src/main/java/org/lite/project/cashiersaas/configuration/SecurityConfiguration.java` (EDIT)
- `src/main/resources/application.properties` (EDIT: add defaults)
- `src/test/resources/application-test.properties` (EDIT: add `app.rate-limit.enabled=false`)
Tests (all NEW):
- `src/test/java/org/lite/project/cashiersaas/unit/ratelimit/TokenBucketTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/ratelimit/HttpRateLimiterTest.java`
- `src/test/java/org/lite/project/cashiersaas/unit/ratelimit/RateLimitFilterTest.java`
Report: `reports/TASK-072-W1.md`

## Contract

### `TokenBucket` (package `org.lite.project.cashiersaas.shared`)
`public TokenBucket(double capacity, double refillPerSecond, LongSupplier nanoClock)`; validates `capacity >= 1` and `refillPerSecond > 0` (IllegalArgumentException). Starts full. `public boolean tryAcquire()` (thread-safe; same refill maths as `GrpcRateLimiter`: min(capacity, tokens + elapsed * rate), consume 1 when >= 1). `public long secondsUntilNextToken()`: 0 when at least one token is available now (do not consume), otherwise `ceil((1 - tokens) / refillPerSecond)` and never less than 1. `GrpcRateLimiter` becomes a thin wrapper: `new TokenBucket(requestsPerSecond, requestsPerSecond, nanoClock)`; keep its `IllegalArgumentException("requestsPerSecond must be at least 1.")` behaviour and the Javadoc meaning.

### `RateLimitProperties` (package `configuration`)
Follow `GrpcSecurityProperties` style (`@Getter @Setter @Validated @Component @ConfigurationProperties(prefix = "app.rate-limit")`). Fields: `boolean enabled = true`; `@Min(1) int maxBuckets = 100000`; `@Min(1) int idleSeconds = 600`; one `Limit` per group (`auth`, `publicRead`, `publicOrderWrite`, `qrToken`, `realtimeTicket`, `authenticated`, `anonymousOther`) with defaults from the table. `Limit` = `public static class` with `@Getter @Setter`, `@Min(1) int perMinute`, `@Min(1) int burst`, constructor `Limit(int perMinute, int burst)` and a no-arg one for binding. Property names are the kebab-case of these fields (`app.rate-limit.public-read.per-minute`, ...). Write the defaults explicitly in `application.properties` (with a one-line comment: override via env, e.g. `APP_RATE_LIMIT_AUTH_PER_MINUTE`).

### `HttpRateLimiter` (package `component`, `@Component`)
`public enum Group { AUTH, PUBLIC_READ, PUBLIC_ORDER_WRITE, QR_TOKEN, REALTIME_TICKET, AUTHENTICATED, ANONYMOUS_OTHER }` (nested).
`public static final class Decision` (NOT a record) with `isAllowed()` and `getRetryAfterSeconds()` (Lombok `@Getter`), static factories `allow()` and `deny(long retryAfterSeconds)`.
Constructors: `@Autowired HttpRateLimiter(RateLimitProperties)` and `HttpRateLimiter(RateLimitProperties, LongSupplier nanoClock)` (the second one for tests, like `GrpcRateLimiter`).
`public boolean isEnabled()`; `public Decision check(Group group, String key)`.
Storage: `ConcurrentHashMap<String, Entry>` keyed `group.name() + ":" + key`, `Entry` = `TokenBucket` + `volatile long lastSeenNanos`. Bucket = `new TokenBucket(limit.burst, limit.perMinute / 60.0, clock)`. Memory bound: when `size() >= maxBuckets` (checked on insert of a NEW key) sweep entries idle longer than `idleSeconds`; also sweep opportunistically every 1000th call. If still full after the sweep, FAIL OPEN (allow) and log WARN at most once per minute (`// shortcut: fail open when the map is full, move to a bounded cache if this ever logs`). Never log keys (they contain IPs/user ids/QR tokens), only counts.

### `RateLimitFilter` (package `component`)
`extends OncePerRequestFilter`. It is NOT a Spring bean (a bean would be auto-registered in the servlet container BEFORE Spring Security, where the user is still unknown). Construct it inside `SecurityConfiguration.securityFilterChain` with `new RateLimitFilter(httpRateLimiter, objectMapper)` and register `http.addFilterAfter(rateLimitFilter, BearerTokenAuthenticationFilter.class)`; add the two parameters (`HttpRateLimiter`, `tools.jackson.databind.ObjectMapper`) to `securityFilterChain`. Classification and keys exactly per the table. "Authenticated" = `SecurityContextHolder.getContext().getAuthentication()` is non-null, `isAuthenticated()`, and not `AnonymousAuthenticationToken`; user key = `authentication.getName()` (check `TenantJwtAuthenticationConverter`/`TenantPrincipal`/`AccountPrincipal` to confirm `getName()` is the stable user/subject id; if not, use the right stable id and report). On deny: `response.setStatus(429)`, header `Retry-After: <seconds>` (integer), JSON body `ApiResponse.error(429, "Terlalu banyak permintaan. Coba lagi dalam N detik.")` written exactly like `ApiSecurityExceptionHandler.write` (UTF-8, `application/json`, `objectMapper.writeValueAsBytes`), do not continue the chain; log WARN `"Rate limit exceeded: group={}"` with the group only. Use `HttpStatus`/constants, no magic literals where a constant exists. Skip rules per the table.
`SecurityConfiguration` also: in `corsConfigurationSource()` add `"Retry-After"` to `setExposedHeaders` (keep `X-XSRF-TOKEN`) so the browser frontend can read it. Do not change any other security rule.

### Properties
`application.properties`: the `app.rate-limit.*` defaults and the commented `# server.forward-headers-strategy=native` line (comment: enable only behind a trusted reverse proxy). `application-test.properties`: `app.rate-limit.enabled=false` (the concurrency integration tests fire parallel bursts and must never be throttled).

## Tests (JUnit 5, `@Tag("unit")`, no Spring context, no Docker, fake clock via `AtomicLong` like `GrpcRateLimiterTest`)
- `TokenBucketTest`: starts full; exhaust then reject; refill over time up to capacity only; `secondsUntilNextToken` is 0 when available and a correct ceil (>= 1) when empty; invalid constructor args throw; a small multithreaded check that at most `capacity` of N parallel acquires succeed (copy the approach of `GrpcRateLimiterTest`).
- `HttpRateLimiterTest`: per-group limits from `RateLimitProperties` defaults; different keys are independent; same key in different groups independent; deny returns `retryAfterSeconds >= 1`; `enabled=false` -> `isEnabled()` false; idle eviction frees entries (advance the clock past `idleSeconds` with a tiny `maxBuckets`); full map -> fail open (allowed).
- `RateLimitFilterTest`: use `MockHttpServletRequest/Response`, `MockFilterChain`, a real `ObjectMapper` (check how other unit tests obtain one), small limits via `RateLimitProperties`. Cases: path/method -> group and key (login uses IP, `GET /api/v1/public/fnb/menu/x` consumes both `PUBLIC_READ` and `QR_TOKEN`, realtime-ticket, authenticated user key vs IP with a `UsernamePasswordAuthenticationToken` in `SecurityContextHolder` (clear it in `@AfterEach`)); 429 body has `code=429`, `success=false`, the Indonesian message, `Retry-After` header is a positive integer, chain NOT invoked; OPTIONS, `/v3/api-docs/x`, `/swagger-ui/x`, non-`/api/` paths and `enabled=false` pass straight through; `X-Forwarded-For` is IGNORED (two requests with different spoofed headers but the same `remoteAddr` share one bucket).
Do NOT add an integration test (Testcontainers/context changes are out of scope; the orchestrator will decide).

## Rules
- You inherit no conversation. Read this file, `AGENTS.md`, `GrpcRateLimiter.java`, `GrpcRateLimiterTest.java`, `GrpcSecurityProperties.java`, `ApiSecurityExceptionHandler.java`, `SecurityConfiguration.java`, `TenantJwtAuthenticationConverter.java`, `ApiResponse.java`, and only other files you call.
- Java 17; DTO-like classes are named `public class` with Lombok `@Getter/@Setter` (never `record`, never `@Data`); no wildcard generics (`<?>`, `? extends`, `? super`); no `@Deprecated`; no new dependency; messages in Indonesian; no logging of tokens/keys/IPs.
- DO NOT RUN MAVEN or any build/test; no git commands; edit nothing outside the reserved paths. Re-read your code for compile errors (imports, `tools.jackson` ObjectMapper package, Spring Security 7 class names).
- Update `reports/TASK-072-W1.md` at each checkpoint and at the end: changed files, acceptance progress, evidence of what you re-read, risks, deviations, anything in the contract that conflicts with the real code (STOP and report instead of improvising outside your reserved paths).

## Orchestrator acceptance
Compile (`mvnw.cmd -q -B -DskipTests test-compile`, no `clean`), review the diff against this contract and `AGENTS.md`, confirm `GrpcRateLimiter` public API unchanged and `GrpcRateLimiterTest` untouched, confirm the filter is not a bean and the only security-config change is the filter + exposed header. Tests run only when the user asks (then: the three new classes plus `GrpcRateLimiterTest`, and a full `verify` to prove the integration tests are not throttled).

---

## Amendment A1 (2026-11-11, user request) — constants in ONE place, crucial values in application.properties
Supersedes the parts of the contract above that conflict (property defaults in `RateLimitProperties`, the `idleSeconds` property, magic numbers in code).

1. NEW `src/main/java/org/lite/project/cashiersaas/shared/RateLimitConstants.java` (add to your reserved writes; follow the style of `AuthenticationConstants`/`StatusConstants`: `public final class`, private constructor, `public static final`). It is the ONLY place for static non-crucial numbers/names used by the limiter, e.g. `IDLE_BUCKET_SECONDS = 600` (replaces the `idleSeconds` property), `SWEEP_EVERY_CALLS = 1000`, `FULL_MAP_WARN_INTERVAL_SECONDS = 60`, `SECONDS_PER_MINUTE = 60.0`, `RETRY_AFTER_HEADER = "Retry-After"`, the API prefix `"/api/"`. No other numeric literal (other than 0/1 arithmetic) may remain in `TokenBucket`, `HttpRateLimiter`, `RateLimitFilter`. Do NOT move the gRPC numbers.
2. CRUCIAL, operator-tunable values live ONLY in `application.properties` and come from the environment with the `CASHIER_` prefix, exactly like the existing `${CASHIER_DB_POOL_MAX_SIZE:10}` lines. `RateLimitProperties` therefore has NO Java default values for them (keep `@Validated`, `@NotNull`/`@Valid` on each `Limit`, `@Min(1)` on numbers; remove the `idleSeconds` field). Write exactly these lines (property = `${ENV:default}`):
```
app.rate-limit.enabled=${CASHIER_RATE_LIMIT_ENABLED:true}
app.rate-limit.max-buckets=${CASHIER_RATE_LIMIT_MAX_BUCKETS:100000}
app.rate-limit.auth.per-minute=${CASHIER_RATE_LIMIT_AUTH_PER_MINUTE:10}
app.rate-limit.auth.burst=${CASHIER_RATE_LIMIT_AUTH_BURST:10}
app.rate-limit.public-read.per-minute=${CASHIER_RATE_LIMIT_PUBLIC_READ_PER_MINUTE:240}
app.rate-limit.public-read.burst=${CASHIER_RATE_LIMIT_PUBLIC_READ_BURST:60}
app.rate-limit.public-order-write.per-minute=${CASHIER_RATE_LIMIT_PUBLIC_ORDER_WRITE_PER_MINUTE:20}
app.rate-limit.public-order-write.burst=${CASHIER_RATE_LIMIT_PUBLIC_ORDER_WRITE_BURST:10}
app.rate-limit.qr-token.per-minute=${CASHIER_RATE_LIMIT_QR_TOKEN_PER_MINUTE:600}
app.rate-limit.qr-token.burst=${CASHIER_RATE_LIMIT_QR_TOKEN_BURST:120}
app.rate-limit.realtime-ticket.per-minute=${CASHIER_RATE_LIMIT_REALTIME_TICKET_PER_MINUTE:10}
app.rate-limit.realtime-ticket.burst=${CASHIER_RATE_LIMIT_REALTIME_TICKET_BURST:5}
app.rate-limit.authenticated.per-minute=${CASHIER_RATE_LIMIT_AUTHENTICATED_PER_MINUTE:300}
app.rate-limit.authenticated.burst=${CASHIER_RATE_LIMIT_AUTHENTICATED_BURST:60}
app.rate-limit.anonymous-other.per-minute=${CASHIER_RATE_LIMIT_ANONYMOUS_OTHER_PER_MINUTE:120}
app.rate-limit.anonymous-other.burst=${CASHIER_RATE_LIMIT_ANONYMOUS_OTHER_BURST:30}
```
Replace the "override via env, e.g. APP_RATE_LIMIT_..." comment accordingly (`CASHIER_RATE_LIMIT_*`). `application-test.properties` keeps the literal `app.rate-limit.enabled=false`.
3. Unit tests: `RateLimitProperties` no longer has defaults, so the tests build their properties through ONE small private helper/factory per test class (or a tiny shared test helper class `unit/ratelimit/RateLimitTestProperties.java`, NEW and allowed) that sets the former default numbers; no Spring context. Tests may reference `RateLimitConstants` instead of repeating its numbers.
4. NOT yours: `.env`, `.env.example` and `docker-compose.yml` (the orchestrator edits them with the same variable names). Do not touch them.
5. Report `reports/TASK-072-W1.md`: add a short "A1" section listing which numbers went where.
