# TASK-072 W1 report

Status: implemented, NOT compiled or run (policy: workers never run Maven/tests). Re-read for imports and Jackson 3 / Spring Security 7 names.

## Changed files
Main:
- `shared/TokenBucket.java` (new): capacity, refillPerSecond, nanoClock; starts full; `tryAcquire()`; `secondsUntilNextToken()` (0 if a token is available, else `max(1, ceil((1 - tokens) / rate))`); IllegalArgumentException for capacity < 1 or refill <= 0. Same refill maths as the old GrpcRateLimiter.
- `grpc/policy/GrpcRateLimiter.java` (edit): thin wrapper over `TokenBucket(rps, rps, clock)`; both public constructors, `tryAcquire()` and the `"requestsPerSecond must be at least 1."` check unchanged. `GrpcRateLimiterTest` untouched.
- `configuration/RateLimitProperties.java` (new): `app.rate-limit.*`, defaults per table, nested `Limit` (`@Min(1)`).
- `component/HttpRateLimiter.java` (new): `Group` enum, `Decision` (final class, `@Getter`, `allow()`/`deny(n)`), `check(group, key)`, `isEnabled()`, ConcurrentHashMap of buckets with idle sweep (when full on a NEW key, and every 1000th call), fail open + WARN at most once a minute (counts only, never keys). Both constructors are `public` (the clock one must be public because the tests live in another package).
- `component/RateLimitFilter.java` (new, NOT a bean): classification exactly per the table; 429 + `Retry-After` + `ApiResponse.error(429, "Terlalu banyak permintaan. Coba lagi dalam N detik.")` written like `ApiSecurityExceptionHandler.write`; WARN logs the group only. Skips via `shouldNotFilter`: disabled, OPTIONS, path not starting with `/api/` (this already covers `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/error`).
- `configuration/SecurityConfiguration.java` (edit): `securityFilterChain` gets `HttpRateLimiter` and `tools.jackson.databind.ObjectMapper` params; `.addFilterAfter(new RateLimitFilter(...), BearerTokenAuthenticationFilter.class)`; `Retry-After` added to exposed CORS headers (kept `X-XSRF-TOKEN`). Nothing else changed.
- `src/main/resources/application.properties`: explicit defaults + commented `# server.forward-headers-strategy=native`.
- `src/test/resources/application-test.properties`: `app.rate-limit.enabled=false`.

Tests (new, `@Tag("unit")`, no Spring context, fake clock): `unit/ratelimit/TokenBucketTest`, `HttpRateLimiterTest`, `RateLimitFilterTest`.

## Acceptance progress
All contract items implemented. Assertions covered:
- TokenBucketTest (7): starts full/exhausts; refill capped at capacity; fractional rate; `secondsUntilNextToken` 0 and non-consuming; ceil and >= 1 when empty; invalid args throw; concurrent acquire grants exactly capacity (frozen clock).
- HttpRateLimiterTest (8): default burst of every group; configured limits; independent keys; independent groups; deny retryAfter >= 1; allow has 0; `enabled=false`; idle eviction with tiny maxBuckets; fail open when full.
- RateLimitFilterTest (13): login per IP and POST only, register/refresh share AUTH; public menu consumes PUBLIC_READ and QR_TOKEN (shared across IPs); orders path -> PUBLIC_ORDER_WRITE + QR_TOKEN; other public paths not QR-limited; realtime-ticket per user; authenticated keyed by user across IPs; `TenantPrincipal` keyed by userId; anonymous per IP; 429 envelope (`code`, `success`, Indonesian message, `Retry-After` integer >= 1, chain not invoked); allowed passes untouched; OPTIONS / api-docs / swagger / `/error` / non-`/api/` pass; disabled passes; `X-Forwarded-For` ignored.

## Evidence (files re-read)
TASK-072, AGENTS.md, `GrpcRateLimiter`, `GrpcRateLimiterTest`, `GrpcSecurityProperties`, `ApiSecurityExceptionHandler`, `SecurityConfiguration`, `TenantJwtAuthenticationConverter`, `TenantPrincipal`, `AccountPrincipal`, `ApiResponse`, both properties files. Jackson 3 usage (`tools.jackson.databind`, `new ObjectMapper()`, `asString()`) matches existing tests.

## Deviations / contract conflicts
- User key: `authentication.getName()` is NOT stable here. The converter builds `UsernamePasswordAuthenticationToken(principal, ...)` with `TenantPrincipal`/`AccountPrincipal`, neither implements `Principal`/`UserDetails` nor overrides `toString()`, so `getName()` would be `ClassName@hash`. The filter uses `TenantPrincipal.getUserId()` / `AccountPrincipal.getUserId()` and falls back to `getName()` for any other principal (used by the unit tests with a String principal).
- Path is read as `getServletPath() + getPathInfo()` (same basis as the CSRF matcher in `SecurityConfiguration`), so the unit tests set `setServletPath`.
- Separate Skip-list for `/v3/api-docs`, `/swagger-ui`, `/error` is implied by the `/api/` rule instead of repeated.

## Amendment A1 (applied; supersedes the property defaults / idleSeconds / magic numbers above)
- NEW `shared/RateLimitConstants.java`: `IDLE_BUCKET_SECONDS=600` (replaces the `idleSeconds` property), `SWEEP_EVERY_CALLS=1000`, `FULL_MAP_WARN_INTERVAL_SECONDS=60`, `SECONDS_PER_MINUTE=60.0`, `NANOS_PER_SECOND=1e9` (moved out of `TokenBucket`), `RETRY_AFTER_HEADER="Retry-After"` (used by the filter and the CORS exposed header in `SecurityConfiguration`), `API_PREFIX="/api/"`. gRPC numbers not moved (`GrpcRateLimiter` no longer has its own nanos constant, since `TokenBucket` owns the maths).
- `RateLimitProperties`: no Java defaults, `idleSeconds` removed; `@Min(1)` on `maxBuckets`, `@NotNull @Valid` on every `Limit`, `@Min(1)` on `perMinute`/`burst`.
- `application.properties`: the exact `${CASHIER_RATE_LIMIT_*:default}` lines from the amendment (enabled, max-buckets, 7 groups x per-minute/burst); the comment now names `CASHIER_RATE_LIMIT_*`; the commented `server.forward-headers-strategy=native` line kept. `application-test.properties` keeps literal `app.rate-limit.enabled=false`. `.env`, `.env.example`, `docker-compose.yml` not touched.
- Tests: new `unit/ratelimit/RateLimitTestProperties.java` (package-private `defaults()` sets the former default numbers); `HttpRateLimiterTest` and `RateLimitFilterTest` use it. The idle-eviction test was rewritten because idle time is now the constant 600 s (a 1/min bucket refills fully by then, so eviction is observed through fail-open: before the idle time a full map lets "c" through unlimited, after `IDLE_BUCKET_SECONDS + 1` "c" is tracked and its second request is throttled).
- Remaining literals in `TokenBucket`, `HttpRateLimiter`, `RateLimitFilter` are only 0/1 arithmetic, the `"UTF-8"` charset and path/pattern strings (kept in the filter next to their matching logic).
- Env validation risk: if a `CASHIER_RATE_LIMIT_*` value is not an integer or is < 1, startup fails (intended, `@Validated`).

## Risks
- Requests with an invalid/expired Bearer token are rejected by `BearerTokenAuthenticationFilter` before this filter runs, so they are not throttled (they are also not authenticated, so no user bucket exists to key on). Brute force of tokens is not possible anyway, but such a flood is only bounded by the server. Moving the filter before the Bearer filter would lose the user key; a possible follow-up is a second IP-only filter earlier.
- Not compiled: floating point edge cases in tests were avoided by using non-boundary times (1.5 s, 31 s) and a 6..7 s range for Retry-After at 10/min.
- Single instance, in-memory only (marked with `shortcut:` comments). Behind a proxy without `server.forward-headers-strategy=native`, all clients share the proxy IP.
- `PUBLIC_READ` is 240/min (not 120) because of shared cafe Wi-Fi IPs and order-status polling, as the task specified.
