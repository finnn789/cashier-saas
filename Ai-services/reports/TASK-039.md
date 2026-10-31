# TASK-039 — AI Service integration contract review

Date: 2026-10-11 (Asia/Jakarta)  
Worker: designated read-only contract review worker  
Memory baseline: 17 (setup baseline 16)

## Initial checkpoint — baseline and scope acknowledgement

- Read `tasks/TASK-039.md`, `AGENTS.md`, `.ai/MEMORY.md`, `.ai/TASKS.md`, `.ai/context/INDEX.md`, `PRD-MVP.md` header decisions, `Ai-services/PLAN.md`, the delegation baseline/status artifacts, and the current relevant Spring source.
- The exact writable reservation is this report only. No application, proto, schema, dependency, canonical `.ai`, or test edits were made. No nested agents, build, test, startup, provider, Redis, database, network, or credential/.env reads were performed.
- Baseline is memory 17 plus `reports/artifacts/AI-SERVICES-20261011-DELEGATION/baseline.csv` and `git-status-before.txt`. The working tree was already materially dirty at baseline; it is preserved. TASK-038 is concurrent, has disjoint writes, and is not used as proof of any integration.
- The paused UNIT work and superseded TASK-037 remain out of scope.

## Source-evidence checkpoint

### 1. Existing identity boundary

**Source evidence**

- JWT signing requires a configured Base64 secret of at least 32 bytes, uses `HmacSHA256`, and the decoder is pinned to HS256: `src/main/java/org/lite/project/cashiersaas/configuration/JwtConfiguration.java:32-63`.
- Decoder validation requires expiry, issued-at, JWT ID and subject, and combines issuer and audience validators: `JwtConfiguration.java:64-75`.
- Issued merchant JWTs contain issuer, audience, subject/user ID, issue/expiry times, JWT ID, `tenant_id`, `outlet_id`, `membership_id`, `session_id`, `scope=MERCHANT`, email, and a roles array: `src/main/java/org/lite/project/cashiersaas/component/JwtTokenService.java:25-50`.
- Account JWTs are a separate `scope=ACCOUNT` form with user, email and session, not tenant/outlet/roles: `JwtTokenService.java:53-85`. The converter creates `AccountPrincipal` only for that scope, requires `MERCHANT` otherwise, and constructs `TenantPrincipal` from validated UUID tenant/outlet/membership/session claims and recognized roles: `TenantJwtAuthenticationConverter.java:19-42,53-78`.
- `TenantPrincipal` represents a staff user/membership/outlet/tenant/session/roles; it has no customer identity field: `src/main/java/org/lite/project/cashiersaas/component/TenantPrincipal.java:10-38`. Active role authorization is routed to platform `SUPERADMIN`, merchant `OWNERMERCHANT`, or cashier `OWNERMERCHANT|CASHIER`: `SecurityConfiguration.java:52-65`.

**Conclusion and contract rule**

The current Cashier Bearer token is staff/account authentication. It must never be accepted as customer or WhatsApp identity, and it must not be forwarded to Python, a model, or a customer browser as an AI-service credential. A future web-chat customer authenticator needs its own approved issuer/audience/key-verification contract or a Spring-issued limited capability; `AUTH_JWKS_URL` in the Python planning material is not a current Spring capability.

### 2. Public order-status capability and customer persistence

**Source evidence**

- A public QR checkout creates a status token from tenant + idempotency key, stores only its SHA-256 digest and a 30-day expiry, and returns the raw token at checkout: `PublicOrderService.java:34,42-81`; `PublicOrderTokenService.java:16-38`; `SalesOrderEntity.java:69-74,88-107`.
- The public status reader accepts only a 43-character URL-safe token, looks up its digest, rejects expiry as not-found, and maps the order without a raw token: `PublicOrderService.java:106-113`; repository lookup `FnbOrderRepository.java:54-58`.
- The public endpoint is `GET /api/v1/public/fnb/orders/{orderToken}/status`, and the entire `/api/v1/public/**` path is permitted without staff authentication: `PublicOrderController.java:14-27`; `SecurityConfiguration.java:49-56`.
- Its response exposes order number, status/payment status, totals/tax/service-charge, plus token/expiry only when a raw token is supplied to the mapper: `PublicOrderResponse.java:11-22`; `FnbOrderMapper.java:8-15`.
- `SalesOrderEntity` holds a short `customerLabel`, not a `CustomerEntity` relation: `SalesOrderEntity.java:51-52`. A separate customer table does exist and is tenant-owned with `name`, `phone`, optional email and notes: `src/main/java/org/lite/project/cashiersaas/entity/shared/customer/CustomerEntity.java:13-24`.

**Conclusion and contract rule**

The status token is an existing public *order capability*, not a customer account, phone binding, WhatsApp identity, or tenant-selection proof. The customer table's phone column does not establish `wa_id -> customer`, and no reviewed source associates a customer with `SalesOrderEntity`. Do not infer ownership from matching labels, a supplied phone number, an order number, or a model-selected identifier.

The status API's fields are the maximum currently evidenced safe read model for an AI status reply: `orderNumber`, `status`, `paymentStatus`, `subtotal`, `taxAmount`, `serviceChargeAmount`, and `total`. The AI must never echo `orderToken`, status-token expiry, idempotency key, cancellation reason, staff/payment details, or internal identifiers. Runtime behavior and externally observable error uniformity remain unverified.

### 3. Spring gRPC and support-ticket state

**Source evidence**

- The root Maven dependencies contain MVC, Security/resource-server, JPA, WebSocket, Flyway/Liquibase, etc., but no Spring gRPC, `io.grpc`, protobuf, or proto-generation dependency: `pom.xml:35-141`.
- A source search finds no Java gRPC server, proto, generated stubs, customer WhatsApp identity fields, or support-ticket API/persistence. `Ai-services/PLAN.md` describes these only as future locations/plans: `Ai-services/PLAN.md:9-11,19-26,43-56`.
- The support-ticket package is presently only a scaffold marker under `Ai-services`; this is not Spring persistence or a write contract.

**Conclusion and minimal proposed read-only messages (not implemented)**

No gRPC endpoint currently exists. Reserve a future reviewed Spring contract that exposes only:

1. `GetPublicOrderStatus(GetPublicOrderStatusRequest) -> GetPublicOrderStatusResponse`
2. Request: `request_id` and a single opaque `public_status_capability`; do **not** accept tenant ID, outlet ID, customer ID, phone, `wa_id`, staff JWT, order number, or raw SQL/query predicates.
3. Response: request ID plus the seven fields enumerated above, or one deliberately uniform `NOT_FOUND_OR_UNAUTHORIZED` application outcome. No token, expiry, tenant/outlet IDs, price-item details, audit/payment actor, customer label, or internal order ID.
4. Boundary: the Python service authenticates to Spring with a separately provisioned service identity (mTLS is the recommended transport baseline); Spring verifies that caller identity, hashes/resolves the capability, checks expiry, and derives tenant/order itself. Deadline and correlation ID are transport metadata, not authority. Python must use a shared channel with no mutable request identity.

This is intentionally capability-based because it reuses a source-evidenced public status boundary. It does not solve authenticated web-chat identity or WhatsApp customer mapping; a message supplied in chat is untrusted and cannot grant the AI the capability.

Support-ticket reads/writes remain absent and prohibited from the first integration slice. Any ticket API requires an explicit public behavior, persistence, authorization, retention/audit, and idempotency decision first.

### 4. Shared downstream channel, queue, and delivery contracts

**Source evidence**

- Existing staff realtime is a separate one-time ticket flow: an already authenticated staff caller obtains a ticket, the service re-decodes and compares its bearer JWT/principal, stores a SHA-256 ticket digest, caps expiry, and revalidates live access: `FnbRealtimeController.java:19-28`; `FnbRealtimeTicketService.java:36-70`.
- The native WebSocket handshake consumes that ticket from `Sec-WebSocket-Protocol`, rejects query-string tickets, and the handler scopes outbound order events to the ticket's tenant/outlet with bounded delivery; it accepts no inbound messages: `FnbRealtimeHandshakeInterceptor.java:19-34`; `FnbRealtimeWebSocketHandler.java:44-80,93-125`.
- The plan correctly calls for a distinct AI WebSocket protocol and durable Redis Stream consumer-group flow, ACK after result/persist/send stage, dedup, per-conversation ordering, and REST resync; this is planned only, not currently implemented: `Ai-services/PLAN.md:35-37,43-45,65-74`.

**Recommended shared Python contracts (not implemented)**

- WebSocket auth: a future trusted adapter creates immutable server-side `ChatPrincipal(tenant_id, subject_id, channel, conversation_id, auth_expiry)` only after approved verification. The client cannot select tenant, recipient, customer ID, routing ID, or authorization scope. `connection_id` is transport-only. Never reuse the staff FNB realtime ticket protocol for customers.
- Events: server sequence each conversation and send concrete envelopes `{event_id, conversation_id, correlation_id, sequence, type, occurred_at, payload}` where `type` is only `accepted|started|delta|done|failed|resync_required|heartbeat`. Replay/resume must be authorized against the same revalidated principal and bounded by a server cursor. `delta` is web-only; WhatsApp receives a final approved message.
- Queue: persist a normalized immutable inbound envelope before enqueue with `message_id`, `provider_message_id` (where available), `tenant_id`, `channel`, `conversation_id`, authorized routing reference, received time, content hash, and schema version. Deduplicate atomically by provider/channel identity and tenant; lock/partition one active worker per tenant+channel+conversation; retry with bounded attempts/backoff and a durable failure state. ACK the stream only after the defined durable terminal checkpoint, not merely after model invocation.
- WhatsApp: verify the raw request body signature before parsing/routing; verification GET validates a separate verify token. Resolve `phone_number_id -> tenant/channel configuration` only from server-side approved configuration, then resolve `wa_id -> customer/authorized conversation` from a tenant-scoped approved mapping. Persist the original authorized recipient/routing context and send only to it; never trust a model or inbound payload to choose a recipient. Provider timeout/unknown send result is ambiguous: retain a delivery-pending/reconciliation state and do not claim exactly-once delivery or successful delivery from HTTP acknowledgement.

## Unresolved material decisions — user approval required

1. **Customer identity and mapping (security/schema):** define the authoritative source, lifecycle, verification/privacy rules, uniqueness, revocation, and retention for `phone_number_id -> tenant` and `wa_id -> customer/conversation`. Existing `CustomerEntity.phone` alone is insufficient. Until approved and implemented server-side, WhatsApp must fail closed before any order/status tool call.
2. **Web-chat identity (public security contract):** choose customer authentication/capability issuance, renewal/revocation, allowed origins, and resume semantics. Staff JWT and staff realtime tickets are not eligible substitutes.
3. **gRPC service identity and capability policy (security/public contract):** approve mTLS/service credential provisioning, service authorization, deadlines, error taxonomy, capability lifetime/revocation, rate limits, and audit/redaction policy. Decide whether exposing the existing 30-day status capability to an AI adapter is acceptable or whether a narrower/rotatable customer capability is required.
4. **Public status response (public contract):** explicitly approve the exact seven fields above and disclosure of money/payment state to any holder of the capability. Existing source proves the current REST behavior but not that it is an approved AI/WhatsApp disclosure model.
5. **Support ticket (product/schema/public contract):** decide whether tickets exist in scope at all, who may create/read them, fields/attachments, idempotency, audit/retention, and human escalation semantics. No implementation should imply that an escalation created a ticket.
6. **Redis delivery semantics/operations:** approve persistence/HA, retention, dead-letter handling, deduplication keys and retention, replay window, ordering key, queue-full behavior, and operational ownership. Exactly-once external WhatsApp delivery is not attainable from a provider timeout alone.
7. **Meta/provider configuration:** select API version, verification/signature algorithm, retry expectations, outbound idempotency/reconciliation mechanism, opt-in/privacy/compliance controls, and production key management. These are runtime-unverified.

## Smallest safe next slice and dependency order

1. Accept TASK-038 only on its own bounded foundation evidence; it may provide FAQ behavior but does not satisfy an HTTP/auth/queue/Meta/Spring acceptance path.
2. Obtain decisions 1-4 above. Without them, the smallest safely implementable AI slice remains trusted-adapter-only FAQ; no customer status access and no WhatsApp ingress.
3. After decisions, delegate an identity/mapping and gRPC-contract architecture task first. It must reserve Java/POM/proto/schema paths exactly and retain Spring-side authorization; do not hand a raw staff JWT to Python.
4. Implement Spring public-status gRPC endpoint and separately generated stubs only after the proto/service identity/capability contract is approved.
5. Implement durable Python queue + authenticated web adapter against the approved principal/event contract; then test/review ordering, dedup, replay and tenant isolation when testing is explicitly authorized.
6. Implement WhatsApp verification/routing/enqueue/send only after mapping and queue contracts are complete. Ticket work is a later separate product slice.

## Proposed future reservations (not authorized by this review)

| Worker | Exact proposed reservation | Prerequisite |
|---|---|---|
| Contract/Spring worker | `pom.xml`; `src/main/proto/customer_order_status.proto`; `src/main/java/org/lite/project/cashiersaas/configuration/*Grpc*.java`; `src/main/java/org/lite/project/cashiersaas/service/*CustomerOrderStatus*.java`; `src/main/java/org/lite/project/cashiersaas/repository/FnbOrderRepository.java`; unique report | Approved identity, gRPC, capability and response decisions; no concurrent POM/proto writer |
| Customer-mapping worker | approved new migration path; `CustomerEntity.java`; narrowly scoped customer-mapping entity/repository/service/controller/DTO paths; unique report | Explicit mapping schema, privacy/verification/revocation decisions; separate from order-status worker unless contracts require one transaction |
| Python messaging/web worker | `Ai-services/app/chat/websocket.py`; `service.py`; `schemas.py`; `Ai-services/app/messaging/queue.py`; `worker.py`; unique report | Approved web principal/event/Redis contracts and completed foundation review |
| Python WhatsApp worker | `Ai-services/app/whatsapp/webhook.py`; `service.py`; `client.py`; `schemas.py`; unique report | Completed trusted mapping and durable queue; approved Meta controls |

Reservations are proposals only. They must be rebased to a fresh baseline and made disjoint with TASK-038 (including its report) before delegation. Generated stubs, tests, and schema migrations need their own explicit authorization/reservations; do not fold them silently into another worker's scope.

## Final checkpoint / handoff

- Review result: **CONTRACT REVIEW SUBMITTED — NOT READY FOR INTEGRATION IMPLEMENTATION.** Existing source supports a narrowly proposed public-status capability contract, but no gRPC server, trusted customer mapping, customer web authenticator, durable queue, WhatsApp routing, or support-ticket contract exists.
- Report-only change: `Ai-services/reports/TASK-039.md` (this file). No other task-owned or application paths changed by this worker.
- Static/source review only. Tests, Maven/build, startup, provider, Redis, database, and network calls were **not run by policy and task instruction**. Runtime behavior is unverified.
