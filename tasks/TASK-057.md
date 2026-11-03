# TASK-057: gRPC security hardening (service auth, token-derived tenant, input limits)

Status: IN PROGRESS — user authorized 2026-11-02 (items 1–4 + all tests; service key + tenant allowlist; test execution authorized, run by lightest model)
Memory version: 43 (baseline); dirty tree at baseline: only untracked `.playwright-mcp/`
Correction: `spring.jpa.open-in-view` is already `false` (application.properties:13); earlier memory note was stale.
Date: 2026-10-31 (Asia/Jakarta)
Related: TASK-045/046/047 (gRPC server), TASK-044 (Python gRPC client), TASK-053 (internal tenant sync API, X-Internal-Service-Key), TASK-055 (docker-compose).

## Problem (source-verified 2026-10-31)

- `CashierGrpcService`, `CashierCatalogGrpcHandler`, `CashierOrderGrpcHandler`: no ServerInterceptor, no caller authentication; `SecurityConfiguration` covers HTTP only.
- `tenant_id` is taken from the request payload (`cashier_service.proto`) and used directly in queries — violates AGENTS.md "tenant identity comes from the validated principal, never from request payload".
- Python client `Ai-services/app/integrations/grpc/client.py` defaults to `insecure_channel` (plaintext).
- `docker-compose.yml` publishes `9090` to the host.
- `GetCatalogItem`: `limit=0` means unbounded; `query` goes into `LIKE ... noEscape()` so `%` matches everything. Shared Hikari pool (max 10) with HTTP → pool exhaustion can take down POS for all tenants.
- `GetOrderStatus` looks up by sequential `order_number` → enumeration of other customers' orders/revenue.
- `grpc-services` on classpath; server reflection likely enabled by default.
- Asymmetry: HTTP sync (TASK-053) is guarded by X-Internal-Service-Key, gRPC reads are not.

## Worst-case scenarios (summary)

1. DB pool exhaustion via `query="%", limit=0` floods → POS outage for every tenant.
2. Cross-tenant business espionage: full catalog/prices and order totals/revenue for any known tenant UUID (demo UUIDs are public in README/docs; UUIDs also leak via logs).
3. Phone-number → tenant mapping (planned feature) exposed if a lookup RPC is added → PII enumeration, UU PDP No. 27/2022 exposure.
4. Future write RPCs (create/cancel order) would be fully exploitable.
5. MITM on plaintext channel (spoofed PAID status to customers).
6. Even with service auth: intra-tenant order lookup by order_number; Telegram contact spoofing (must require `request_contact` and `contact.user_id == from.id`); phone registration without OTP.

## Planned remediation (layered)

1. Network (no code): do not publish 9090 in compose; internal docker network only (`backend:9090`).
2. Service authentication: mTLS between ai-services and backend (preferred) or a service JWT in `authorization` metadata validated by a gRPC ServerInterceptor (net.devh Spring Security integration).
3. Tenant from token, not payload: during HTTP tenant/phone sync, Java issues a tenant-bound token; AI service stores it and sends it in gRPC metadata; Java derives tenant_id from the validated token; remove `tenant_id` from proto requests.
4. Input limits: `limit` clamped 1–50, escape LIKE wildcards, per-caller rate limit, disable reflection in production.
5. Customer-level authorization: order status via opaque public order token (`PublicOrderTokenService`) instead of order_number; owner-level data only for OTP-verified phone bound to an OWNERMERCHANT membership.
6. Audit log for every gRPC call (caller, tenant, RPC).

Proposed first delegation scope: items 1–4 (Java server + compose + Python client), then 5–6 as a follow-up.

## Reservations

## Approved design (user decision 2026-11-02)

Service key + server-side tenant allowlist. `tenant_id` stays in proto (no contract break) but the server rejects any tenant not in the allowlist. Token-bound tenant (JWT) deferred to follow-up with TASK-053.

### Shared contract (all workers)

Wire: gRPC metadata header `x-internal-service-key` carries the shared secret.

Java properties (class `org.lite.project.cashiersaas.configuration.GrpcSecurityProperties`, prefix `app.grpc`, `@Validated`, Lombok `@Getter/@Setter`, no `@Data`):
- `app.grpc.service-key=${CASHIER_GRPC_SERVICE_KEY}` — required, no default, `@NotBlank`, min length 16 (startup fails clearly otherwise)
- `app.grpc.allowed-tenant-ids=${CASHIER_GRPC_ALLOWED_TENANT_IDS:}` — comma separated UUIDs; empty means deny every tenant (fail closed)
- `app.grpc.max-catalog-limit=50`, `app.grpc.default-catalog-limit=10`
- `app.grpc.requests-per-second=50` (global token bucket, in-memory, no new dependency)
- `grpc.server.reflection-service-enabled=false` (explicit)

Java classes (package layout is fixed; no new feature root packages):
- `org.lite.project.cashiersaas.grpc.interceptor.GrpcServiceKeyInterceptor` — `ServerInterceptor` registered as `@GrpcGlobalServerInterceptor`; checks header with `MessageDigest.isEqual` (constant time); missing/wrong -> `UNAUTHENTICATED` with a generic message (never echo the key); also applies the rate limiter -> `RESOURCE_EXHAUSTED`.
- `org.lite.project.cashiersaas.grpc.policy.GrpcRateLimiter` — token bucket, `boolean tryAcquire()`.
- `org.lite.project.cashiersaas.grpc.policy.GrpcTenantAccessPolicy` — `UUID requireAllowedTenant(String raw)` (blank/invalid UUID -> `INVALID_ARGUMENT`, not in allowlist -> `PERMISSION_DENIED` with a generic message), `int clampLimit(int requested)` (<=0 -> default, >max -> max), `static String escapeLikePattern(String)` escaping `\`, `%`, `_`.
- Handlers `CashierCatalogGrpcHandler` / `CashierOrderGrpcHandler`: use the policy; LIKE uses escape char `\` (no more `noEscape()`); log only the exception class and a hashed/short tenant reference, never the full request.

Python env: `CASHIER_GRPC_SERVICE_KEY` (required to call; fail closed, no RPC when empty), header name above, catalog `limit` clamped to 1..50 client-side. `GrpcSettings` gains `service_key`.

Compose/env: backend and ai-services both receive `CASHIER_GRPC_SERVICE_KEY: ${CASHIER_GRPC_SERVICE_KEY:?...}`; backend receives `CASHIER_GRPC_ALLOWED_TENANT_IDS: ${AI_TENANT_ID:-00000000-0000-4000-8000-0000000000b1}`; `9090` is no longer published to the host (`expose` only).

### Waves and reserved writes (disjoint)

Wave 1 (parallel, after contract freeze):
- W1 sonnet — Java server hardening. Writes: `src/main/java/org/lite/project/cashiersaas/configuration/GrpcSecurityProperties.java`, `.../grpc/interceptor/GrpcServiceKeyInterceptor.java`, `.../grpc/policy/GrpcRateLimiter.java`, `.../grpc/policy/GrpcTenantAccessPolicy.java`, `.../grpc/service/CashierCatalogGrpcHandler.java`, `.../grpc/service/CashierOrderGrpcHandler.java`, `src/main/resources/application.properties`, `src/main/resources/application-demo.properties`, `reports/TASK-057-W1.md`.
- W2 sonnet — Python client. Writes: `Ai-services/app/integrations/grpc/config.py`, `Ai-services/app/integrations/grpc/client.py`, `Ai-services/tests/unit/test_grpc_client_security.py`, `Ai-services/reports/TASK-057-W2.md`.
- W3 haiku — compose/env/docs. Writes: `docker-compose.yml`, `.env.example`, `Ai-services/.env.example`, `docs/demo.md`, `README.md`, `reports/TASK-057-W3.md`.

Wave 2 (parallel, after W1 review):
- W4 sonnet — Java unit tests. Writes only new files under `src/test/java/org/lite/project/cashiersaas/unit/grpc/**`, `reports/TASK-057-W4.md`.
- W5 sonnet — Java integration test. Writes: `src/test/java/org/lite/project/cashiersaas/integration/grpc/**`, `src/test/java/org/lite/project/cashiersaas/integration/support/AbstractPostgresIntegrationTest.java` (add `app.grpc.*` test properties and random gRPC port only), `reports/TASK-057-W5.md`.

Wave 3:
- W6 haiku — run `mvnw verify` and `uv run pytest` (user-authorized), report raw results only. Writes: `reports/TASK-057-W6.md`.

Fix cycles go back to the owning worker within the same reservation. Workers never write `.ai/*`.
