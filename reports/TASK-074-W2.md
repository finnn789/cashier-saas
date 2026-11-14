# TASK-074 W2 report (security, rate limiting, build config, their tests)

Status: implemented; tests NOT run by policy (no Maven, no build).

## Changed files
- `src/main/java/.../configuration/SecurityConfiguration.java`: removed `permitAll` for `/api/v1/public/**` and `GET /api/v1/fnb/realtime` (swagger/api-docs permitAll kept). Anonymous requests to them now fall to `anyRequest().authenticated()` -> 401.
  - Deviation to flag: the CORS bean called `FnbWebSocketConfiguration.allowedOrigins(...)` (class deleted by W1, so main would not compile). Replaced by an inline split/trim of the same `@Value` (key `app.fnb.realtime.allowed-origins` and its default kept, so deployments keep their CORS origins; field renamed `allowedOrigins`, import `java.util.Arrays` added). The old strict origin validation (explicit http(s) host:port, no wildcard) is dropped; Spring's `CorsConfiguration` still rejects `*` with `allowCredentials=true`. The key name still says "realtime"; renaming it is a follow-up for TASK-075 (config + docs).
- `component/HttpRateLimiter.java`: enum now `AUTH, AUTHENTICATED, ANONYMOUS_OTHER`; switch and javadoc updated.
- `component/RateLimitFilter.java`: removed PUBLIC_* / QR_TOKEN / REALTIME_TICKET branches, constants (`PUBLIC_PREFIX`, `REALTIME_TICKET_PATTERN`, `QR_MENU_PREFIX`, `QR_ORDERS_SUFFIX`, `PATH_MATCHER`), `qrToken(...)`, unused `get` local and `AntPathMatcher` import. Classification now: POST auth paths -> AUTH; authenticated -> AUTHENTICATED; else ANONYMOUS_OTHER.
- `configuration/RateLimitProperties.java`: removed fields publicRead, publicOrderWrite, qrToken, realtimeTicket.
- `shared/RateLimitConstants.java`: untouched (holds no group data).
- `src/main/resources/application.properties`: removed 8 `app.rate-limit.{public-read,public-order-write,qr-token,realtime-ticket}.*` lines.
- `.env.example`, `.env` (exists, local; only rate-limit keys/comment touched, no secrets printed), `docker-compose.yml`: removed the 8 `CASHIER_RATE_LIMIT_{PUBLIC_READ,PUBLIC_ORDER_WRITE,QR_TOKEN,REALTIME_TICKET}_*` lines each; QR_TOKEN sentence removed from the comment in `.env`/`.env.example`. AUTH/AUTHENTICATED/ANONYMOUS_OTHER numbers unchanged.
- `pom.xml`: removed `spring-boot-starter-websocket` (see decision).
- `src/test/resources/application-test.properties`: untouched (only `app.rate-limit.enabled=false`).

## Tests
- `unit/ratelimit/RateLimitTestProperties`: removed the 4 removed-group setters (other numbers identical).
- `unit/ratelimit/HttpRateLimiterTest`: `everyGroupAllowsExactlyItsDefaultBurst` lost the 4 removed-group asserts (AUTH 10, AUTHENTICATED 60, ANONYMOUS_OTHER 30 kept); `sameKeyInDifferentGroupsIsIndependent` and the retry-after tail of `denyCarriesRetryAfterOfAtLeastOneSecond` moved from PUBLIC_READ/REALTIME_TICKET to AUTHENTICATED (same assertions).
- `unit/ratelimit/RateLimitFilterTest`: deleted methods `publicMenuConsumesBothThePublicGroupAndTheQrTokenGroup`, `publicOrderWritesUseTheirOwnGroupAndTheQrTokenOfTheOrdersPath`, `otherPublicPathsAreNotQrTokenLimited`, `realtimeTicketIsLimitedPerAuthenticatedUser`, constant `TICKET`. Added `formerPublicPathsAreClassifiedAsAnonymousOther` (`GET /api/v1/public/fnb/menu/abc` shares the ANONYMOUS_OTHER per-IP bucket with `/api/v1/auth/csrf`: 200, 200, 429, other IP 200). `anonymousRequestsAreLimitedPerIp` now uses `/api/v1/cashier/products` instead of `/api/v1/fnb/realtime` (same assertions). Kept unchanged: login/AUTH, authenticated, tenant principal, 429 body + Retry-After, skip rules, disabled, X-Forwarded-For.
- `integration/security/SecurityAuthorizationIntegrationTest`: `publicEndpointIsNotBlockedBySecurityWhenNoBearerTokenIsPresent` replaced by `removedPublicAndRealtimeRoutesAreProtectedLikeAnyOtherApiPath` asserting anonymous `GET /api/v1/public/fnb/menu/x` and `GET /api/v1/fnb/realtime` return 401 (same chain result as the retained `/api/v1/cashier/probe` test; `anyRequest().authenticated()` + the shared entry point). Other tests untouched.

## Grep evidence
`grep -rnE "PUBLIC_READ|PUBLIC_ORDER_WRITE|QR_TOKEN|REALTIME_TICKET|public-read|public-order-write|qr-token|realtime-ticket|getPublicRead|getQrToken|getRealtimeTicket|getPublicOrderWrite" src .env .env.example docker-compose.yml pom.xml` -> zero hits (excluding SQL table name `table_qr_tokens`, which stays by contract).

## pom decision
`grep -rn "org.springframework.web.socket" src/main` -> zero hits at the time of checking (W1 had already deleted `FnbWebSocketConfiguration`); `grep -rn "websocket\|web.socket" src` -> zero. `spring-boot-starter-websocket` removed. If W1 later reintroduces nothing, no action; orchestrator should confirm at `test-compile`.

## Other references (outside reserved scope, listed only)
- `postman/`, `docs/` may still mention public/realtime routes and rate-limit groups (TASK-075).
- `FnbWebSocketConfigurationTest` (W3 delete list) used `FnbWebSocketConfiguration.allowedOrigins`; its deletion covers the removed validation.

## Blocked / needs orchestrator
None.
