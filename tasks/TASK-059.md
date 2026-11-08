# TASK-059: Dynamic tenant resolution for the Telegram AI bot (staff sync + phone match)

Status: DELEGATED (2026-11-07). Memory version at delegation: 48.
Plan: tasks/TASK-059-PLAN.md. Contract: docs/contracts/member-sync.md (+ fixtures in docs/contracts/). Contract wins over any worker assumption; report conflicts, do not silently deviate.
User decisions (2026-11-07): (1) phoneNumber becomes REQUIRED in the registration form/API (UI already sends it); (2) gRPC tenant allowlist is REPLACED by a database check (tenant exists and active); (3) people who chat with the bot are staff (OWNERMERCHANT, CASHIER), not end customers. User asked for tests and integration tests to be written and executed.

## Waves and models

| Wave | Worker | Model | Scope |
|---|---|---|---|
| 1 | A | sonnet | Java: staff phone field, Flyway V3, registration, normalizer |
| 1 | B | sonnet | Java: gRPC tenant policy -> DB check |
| 1 | C | sonnet | Python: member API, migration 0002, phone util, config |
| 1 | D | haiku | Config/docs/compose for the new env vars |
| 2 | E | sonnet | Java: MemberSyncClient + after-commit hooks (after A) |
| 2 | F | opus | Python: contact verification, multi-tenant resolution, schema 0003 (after C) |
| 3 | runners | haiku | raw test runs; orchestrator reviews |

## Rules for EVERY worker

- You inherit no conversation. Read this file (your section + the common rules), `AGENTS.md`, and `docs/contracts/member-sync.md`.
- Write ONLY your reserved paths. Everything else is read-only. Never edit `.ai/*`, `tasks/*`, `.env*` real files (you may edit `*.example`/docs only if your section reserves them), `docs/contracts/*`.
- Never read or print real `.env` files or secrets. Never log phone numbers, names with phones, tokens, or keys.
- Write real, meaningful tests (unit + integration as listed). No assertion may be weakened to go green. If something in the contract or existing code blocks you, stop and report it instead of improvising across reserved boundaries.
- Update your report file `reports/TASK-059-<X>.md` (or `Ai-services/reports/TASK-059-<X>.md` for Python) at each checkpoint and at the end: changed files, acceptance progress, exact commands run + raw results, test status, remaining work, deviations.
- Do NOT run `git add/commit/push`. Do not touch the dirty files of other tasks.
- Java workers (A, B, E): DO NOT RUN MAVEN or any build (concurrent builds corrupt `target/`; the orchestrator compiles and runs tests). Re-read your code carefully for compile errors instead.
- Python workers (C, F): run only unittest for your own tests from `Ai-services`: `uv run --locked python -m unittest <module> -v`, and the full `tests/unit` once at the end. No live Telegram/OpenRouter/network. No real DB unless your section says it is gated by an env var.

### Java conventions (AGENTS.md, summarized)
Java 17, Spring Boot 4.1.1. Existing top-level packages only (`controller, service, repository, dto, entity, component, configuration, enums, exception, grpc, shared`); no new feature root packages. DTOs: named `public class` + Lombok `@Getter/@Setter`, never `record`, no `@Data`. Controllers return `ApiResponse<T>`, no `ResponseEntity`. No wildcard generics, no `@Deprecated`/compat overloads. Money/time rules unchanged. Services own transactions. Tests live under `src/test/java/org/lite/project/cashiersaas/{unit,integration}`; integration tests extend `AbstractPostgresIntegrationTest` (Testcontainers postgres:17). Entities are mapped by JPA/Blaze; Flyway migrations are in `src/main/resources/db/migration` (current: V1, V2).

### Python conventions
Python 3.12, uv, 4-space indent, 100-column Ruff limit, standard `unittest` (no pytest). FastAPI app factory in `app/application.py`. SQLAlchemy 2 async + Alembic (migration `0001_channels.py` is IMMUTABLE; new schema = new revision). Tenant isolation rules in the existing `app/chat/*` stay.

---

## Section A (sonnet): Java staff phone number

Reserved writes:
- `src/main/resources/db/migration/V3__staff_user_phone_number.sql` (new)
- `src/main/resources/db/demo/R__demo_seed.sql` (give demo staff users a phone; idempotent; keep the file valid)
- `src/main/java/org/lite/project/cashiersaas/entity/shared/identity/StaffUserEntity.java`
- `src/main/java/org/lite/project/cashiersaas/dto/request/identity/RegisterUserRequest.java`
- `src/main/java/org/lite/project/cashiersaas/service/RegisterService.java`
- `src/main/java/org/lite/project/cashiersaas/shared/PhoneNumberNormalizer.java` (new; follow the style of other classes in `shared`)
- any other main file that must change ONLY to call the changed `StaffUserEntity` factories (find with search; list them in the report)
- `src/test/**` EXCEPT anything under a `grpc` directory and EXCEPT `integration/support/AbstractPostgresIntegrationTest.java` (those belong to B)
- `reports/TASK-059-A.md`

Do:
1. Flyway V3: `ALTER TABLE staff_users ADD COLUMN phone_number varchar(20)`; nullable (legacy rows), no unique constraint. Do not edit V1/V2.
2. `StaffUserEntity`: add `phoneNumber` (column `phone_number`, length 20) with getter; `registerWithPassword(...)` and `createCashier(...)` take the NORMALIZED phone. Keep other behavior.
3. `RegisterUserRequest`: add required `phoneNumber` (`@NotBlank`, `@Size(max=32)`, Indonesian messages like the other fields).
4. `PhoneNumberNormalizer` per contract section 3 (pure static function; invalid -> a clear exception mapped to HTTP 400 through the EXISTING exception types, e.g. `InvalidOperationException` or the existing validation path; do not create feature-specific handlers). `RegisterService.register` normalizes and stores; invalid -> 400 with message "Nomor telepon tidak valid."
5. Fix every existing caller/test that registers users so they send a valid phone (`ApiTestSupport`, `MerchantFixture`, unit tests, etc.). Do not weaken assertions.
6. Tests (write them): `PhoneNumberNormalizerTest` that loads `docs/contracts/phone-normalization-cases.json` (resolve path relative to the project root via the Maven working directory) and asserts every case; register-service unit tests (phone stored normalized, invalid phone rejected, missing phone rejected); an integration test in the `integration` package: register with `0812-3456-7890` stores `6281234567890`, invalid number returns 400, and the response never echoes the phone.

Acceptance: compiles by inspection, all callers updated, tests written, report lists every touched file.

---

## Section B (sonnet): Java gRPC tenant authorization via database

Reserved writes:
- everything under `src/main/java/org/lite/project/cashiersaas/grpc/**`
- `src/main/java/org/lite/project/cashiersaas/repository/TenantRepository.java` (add a minimal query; B is its only editor in this task)
- `src/main/resources/application.properties` ONLY the `app.grpc.allowed-tenant-ids` line/comment removal (single-line edits; do not touch other lines)
- `src/test/**/grpc/**` (unit and integration grpc tests) and `src/test/java/org/lite/project/cashiersaas/integration/support/AbstractPostgresIntegrationTest.java`
- `reports/TASK-059-B.md`

Do:
1. Replace the static env allowlist (`app.grpc.allowed-tenant-ids`, `GrpcSecurityProperties`, `GrpcTenantAccessPolicy`) with a database check: a request tenant is allowed only if a `tenants` row exists AND is active (and F&B enabled, same predicates the code already uses, e.g. `TenantEntity.isActive()` / `isFnbEnabled()`). Keep the service-key interceptor, rate limiter, limit clamp, LIKE escape unchanged.
2. A missing tenant and an inactive tenant MUST produce the identical gRPC status and message (no existence oracle). Keep the status code the current policy returns for a disallowed tenant. Invalid UUID handling unchanged.
3. Remove the allowlist property and its validation entirely (properties class, validation test cases, comments). Do not leave dead config. The service-key rule stays.
4. Keep tenant isolation in catalog/order handlers exactly as is.
5. Update/extend tests: rewrite `GrpcTenantAccessPolicyTest` for the DB-backed policy (mocked repository, include active, inactive, unknown, null/blank/invalid UUID), update `GrpcSecurityPropertiesValidationTest`, update `GrpcSecurityIntegrationTest` (active seeded tenant allowed with no allowlist; unknown tenant and a disabled tenant denied with identical result; cross-tenant isolation still passes; all other existing cases preserved) and `AbstractPostgresIntegrationTest` (remove allowlist property registration).
6. Document in the report which user-visible config was removed so D can update docs (D works in parallel and is told the allowlist variable disappears).

---

## Section C (sonnet): Python member API, migration 0002, phone util

Reserved writes (all under `Ai-services/` unless noted):
- `app/api/__init__.py`, `app/api/internal_members.py` (new)
- `app/application.py` (register the router only)
- `app/config.py` (+ settings for `AI_INTERNAL_API_KEY`; secret never in repr/logs)
- `app/core/phone.py` (new: `normalize_phone(raw: str) -> str | None` per contract section 3)
- `app/chat/members.py` (new: repository functions: `upsert_member`, `find_active_members_by_phone`, `disable_member`, tenant upsert; SQLAlchemy Core like `app/chat/repositories.py`; do NOT edit `repositories.py`)
- `app/chat/entities.py` (add `TenantMember` model and `Tenant.name`; keep existing models unchanged)
- `migrations/versions/0002_tenant_members.py` (new; chain from `0001_channels`; `tenants.name varchar(160) null`; `tenant_members(tenant_id uuid FK tenants.id, java_user_id uuid, full_name varchar(160) not null, phone_normalized varchar(15) not null, role varchar(32) check in (OWNERMERCHANT, CASHIER), status varchar(16) check in (ACTIVE, DISABLED) default ACTIVE, created_at, updated_at timestamptz, PRIMARY KEY (tenant_id, java_user_id))`; partial index on `phone_normalized` where status = 'ACTIVE')
- `tests/unit/test_phone.py`, `tests/unit/test_internal_members.py`, `tests/integration/test_internal_members_api.py` (new)
- `Ai-services/reports/TASK-059-C.md`

Do:
1. `normalize_phone` equivalent to the Java rules; `test_phone.py` runs every case in `docs/contracts/phone-normalization-cases.json` (path relative to repo root, derived from `__file__`).
2. `PUT /api/v1/internal/tenants/{tenant_id}/members/{java_user_id}` exactly per the contract (401/503/422/200, pydantic request/response models as named classes, constant-time key check, no body logging, idempotent upsert, tenant row created on demand). Router wired in `application.py` without disturbing the existing health routes and lifespan.
3. Unit tests (offline, fake DB/members layer): key missing/wrong/unset (401/401/503), invalid UUIDs, invalid role/status/phone, idempotent repeat, update changes fields, DISABLED, no phone/key in responses or logs, example payloads from `docs/contracts/member-sync.example.json` parse and are accepted.
4. Integration test (`tests/integration/test_internal_members_api.py`): ASGI-level test through the FastAPI app (httpx/TestClient, whatever is already installed; do not add dependencies without reporting). A variant that needs a real PostgreSQL runs only when env `AI_TEST_DATABASE_URL` is set and is skipped otherwise (`unittest.skipUnless`); it must apply Alembic 0001+0002 to a scratch schema/database and verify upsert + idempotency + constraints. State clearly in the report whether the gated variant was run (it probably cannot be).
5. Verify the Alembic revision statically (import, `down_revision`, `Base.metadata` matches the model) since a DB may be unavailable.

---

## Section D (haiku): config and docs for the new variables

Reserved writes: `.env.example`, `Ai-services/.env.example`, `docker-compose.yml`, `README.md`, `docs/demo.md`, `docs/architecture.md`, `Ai-services/README.md`, `reports/TASK-059-D.md`.

Do (text/config only, no source code):
1. Add `AI_SERVICE_BASE_URL` (backend, blank = sync disabled) and `AI_INTERNAL_API_KEY` (both services, >= 16 random chars, distinct from `CASHIER_GRPC_SERVICE_KEY`) to both `.env.example` files with comments; compose: pass them to `backend` (`AI_SERVICE_BASE_URL` default `http://ai-services:8000`, `AI_INTERNAL_API_KEY`) and `ai-services` (`AI_INTERNAL_API_KEY`); key required via the same `${VAR:?message}` style compose already uses for the gRPC key.
2. Remove every mention of `CASHIER_GRPC_ALLOWED_TENANT_IDS` (the allowlist is being replaced by a database tenant check: a tenant is allowed when it exists and is active) from `.env.example`, compose, README and docs; say so in the gRPC security text.
3. Document in README/docs: registration now requires a phone number; staff data (tenant, name, phone, role) is pushed to Ai-services on owner profile completion and cashier invitation claim; the Telegram bot identifies staff by sharing their own Telegram contact; `AI_TENANT_ID` becomes a development fallback only (F will implement; describe as "development fallback"). Keep claims accurate: mark the feature as "in progress" until verified.
4. Never put real secrets; placeholders only.

---

## Section E (sonnet, wave 2): Java MemberSyncClient

Starts after A is reviewed. Reserved writes:
- `src/main/java/org/lite/project/cashiersaas/configuration/AiServiceProperties.java` (new, `@ConfigurationProperties("app.ai-service")`: base-url, api-key, timeouts, attempts)
- `src/main/java/org/lite/project/cashiersaas/service/MemberSyncClient.java` (new, Spring `RestClient`; `spring-boot-starter-restclient` is already in pom)
- a small event class and publisher/listener following the existing `FnbRealtimePublisher` after-commit pattern (put in `service`/`component` per existing convention; list them)
- `src/main/java/org/lite/project/cashiersaas/service/FreeTierOnboardingService.java` and `CashierInvitationService.java` (add the event publication only)
- `src/main/resources/application.properties` (add `app.ai-service.*` with env placeholders, defaults blank)
- `src/test/**` new test files for this feature only (+ minimal edits to existing tests that construct the two services)
- `reports/TASK-059-E.md`

Do: implement section 2 of the contract exactly. Tests: unit tests with `MockRestServiceServer` (or equivalent already available) covering payload equals the fixture shape, header present, retry on 5xx/IO, no retry on 4xx, disabled when blank, never throws, no phone/key in logs; integration test (extends `AbstractPostgresIntegrationTest`): with the property pointing to a JDK `com.sun.net.httpserver.HttpServer` stub started in the test, register owner -> start free tier -> complete profile -> stub receives one `PUT` matching the contract; invite + claim cashier -> second `PUT`; stub returning 500 does not break registration; sync disabled makes no call. Use no new dependencies.

---

## Section F (opus, wave 2): Python identity resolution

Starts after C is reviewed. Reserved writes (under `Ai-services/`):
- `app/channels/telegram/**` (handlers, bot, config, sender if needed)
- `app/chat/service.py`, `app/chat/repositories.py`, `app/chat/members.py` (C's file: extend only), `app/chat/entities.py`
- `app/ai/**`, `app/tools/**` ONLY if a tenant-propagation fix is unavoidable (report it)
- `migrations/versions/0003_*.py` (new), `migrations/env.py` only if required
- all existing tests that must change because tenant becomes per-request, plus new tests
- `Ai-services/reports/TASK-059-F.md`

Design (decided by the orchestrator; deviate only with a written reason):
1. One shared bot serves many tenants. `channel_accounts.tenant_id` becomes nullable (shared bot) and `conversations` gain `tenant_id` (backfilled from the old channel tenant, then NOT NULL, FK to `tenants`); the active-conversation uniqueness includes tenant. Migration 0003 must upgrade existing single-tenant data without loss and have a working downgrade.
2. Unverified Telegram user: any text -> bot replies (Indonesian) asking them to share their contact via a `request_contact` reply keyboard button. A `contact` message is accepted only if `contact.user_id == from_user.id`; normalize with `normalize_phone`; look up ACTIVE `tenant_members` by phone: 0 matches -> polite "nomor tidak terdaftar" (no hint about other data); 1 match -> create/refresh the verified `tenant_memberships` row (`verified_at=now`, role from the member row) for the user's `channel_users.id`; >1 -> bot lists tenants (name only) and the user chooses; the choice is stored (per user) and only verified memberships can be chosen. Provide `/tenant` to switch among the user's verified tenants.
3. Verified user messages: tenant and role are resolved server-side from the verified membership on EVERY message (re-check the member row is still ACTIVE; if DISABLED, revoke and ask to re-verify). The tenant passes into `ChatService` -> graph state `tenant_id` -> `tool.execute(tenant_id=...)` exactly like today; tools and their model-facing schemas are unchanged. The process-wide `AI_TENANT_ID` stops being required: keep it only as an OPTIONAL development fallback (if set and the user is not verified, behavior is documented and covered by a test), never overriding a verified tenant.
4. Repository scope: refactor `Repository` away from a process-wide tenant to a per-request tenant scope (for example a channel-scoped repository with a `for_tenant(tenant_id)` view), keeping every existing tenant-isolation guard and query scoping. `register_telegram_channel` must register the shared bot without a tenant. Keep user-visible chat behavior (commands, history, duplicate handling, retries) unchanged.
5. Security: never trust text for tenant/phone; never log phone/name; membership writes only after the contact check; verified lookups include tenant id in every conversation/message query; a user cannot read another tenant's conversation even if they belong to both (conversation is per tenant).
6. Tests: update the existing suites you break (do not weaken assertions: change setup, not expectations), and add: contact verification (own contact ok, forwarded/foreign contact rejected, typed number ignored), 0/1/many match flows, tenant switch, disabled-member revocation, per-message tenant resolution, conversation isolation between two tenants for the same Telegram user, migration 0003 static checks (chain, metadata), fallback behavior, and a ChatService-level test proving `tool.execute` receives the verified tenant. Integration tests that need real PostgreSQL are gated by `AI_TEST_DATABASE_URL` as in section C.
