# TASK-057: gRPC security hardening (service auth, token-derived tenant, input limits)

Status: PENDING — NOT STARTED (recorded per user request, implementation not yet authorized)
Memory version: 43
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

Not yet reserved. To be defined (task contract, disjoint write paths, memory baseline) when the user authorizes implementation.
