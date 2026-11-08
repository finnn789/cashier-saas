# TASK-059 (DRAFT PLAN v2): Dynamic tenant resolution for the Telegram AI bot

Status: PLAN ONLY - nothing implemented, nothing delegated. Awaiting user decisions (section F).
Memory version at drafting: 47. Date: 2026-11-07. Revision 2: sync payload changed from "tenant + business phone" to per-user "tenant_id, user name, phone, role" (user instruction 2026-11-07).
Evidence method: Codebase Memory MCP (`E-Java-cashier-saas`, `cashier-ai-services`, both at 89a2ad4) verified against current source.

## A. Goal (user)

1. When a staff user is registered into a tenant in the Java backend, send to the AI-Service database: `tenant_id`, user name, phone number, role (via a Java REST client).
2. When someone chats with the Telegram bot, resolve the tenant from the chatter's phone number by matching it to those records.
3. That tenant_id reaches `CatalogSearchTool.execute(*, tenant_id, query)` (tool and model-facing schema unchanged).

## B. Verified current state

| # | Fact | Evidence |
|---|---|---|
| 1 | Java has NO outbound REST client. `spring-boot-starter-restclient` is in pom.xml (L56) but unused in `src/main`. | pom.xml; text search |
| 2 | A tenant membership is created in TWO places: OWNER in `FreeTierOnboardingService.startFreeTier` (L89 `TenantMembershipEntity.grantOwner`, becomes effective when `complete()` activates the tenant, L137-142) and CASHIER in `CashierInvitationService.claim` (L99 `grantCashier`). Roles: OWNERMERCHANT, CASHIER (matches the AI schema CHECK). | service sources |
| 3 | **There is NO phone number on the user.** `StaffUserEntity` has only email, passwordHash, fullName, status, onboardingIntent. `RegisterUserRequest` = email/password/fullName; `IssueCashierInvitationRequest` = email only. The only phone in the system is the OPTIONAL business phone (`BusinessProfileRequest.phoneNumber`, tenant/outlet). | StaffUserEntity; DTOs |
| 4 | User name IS available: `StaffUserEntity.fullName`. Role IS available on `TenantMembershipEntity`. | entity |
| 5 | Python has no write API (only 5 GET health routes). TASK-053 PENDING. | architecture |
| 6 | Tenant is bound once per process: `AI_TENANT_ID` (telegram/config.py:27) -> `Repository(tenant_id, channel_id)` (bot.py:222) -> `ChatService` -> graph state (service.py:128) -> `tool.execute` (graph/nodes.py:177). | source |
| 7 | AI schema: `channel_accounts.tenant_id` NOT NULL + `conversations` -> `channel_accounts` => one bot = one tenant today. `tenant_memberships(tenant_id, user_id->channel_users.id, role, verified_at)` exists but no code uses it. | chat/entities.py |
| 8 | `Intake.process` handles text only; no Telegram contact handling. | telegram/handlers.py |
| 9 | Java gRPC authorizes tenants via a static env allowlist (`CASHIER_GRPC_ALLOWED_TENANT_IDS`). | TASK-057 |
| 10 | A separate frontend repo exists (`E:/Java/cashier-saas-ui`, indexed as `cashier-saas-ui`); adding a phone field changes the registration API contract it consumes. | list_projects |

## C. Target design

AI-side data (new Alembic revision 0002; 0001 stays immutable):
- `tenants` gains name/status.
- NEW `tenant_members` (pre-provisioned by Java): `tenant_id`, `java_user_id`, `full_name`, `phone_normalized`, `role`, `status`. Unique (tenant_id, java_user_id).
- Existing `tenant_memberships` (tenant, channel_user, role, verified_at) = the VERIFIED link created after a Telegram user proves the phone.

```
Java: owner completes profile (FreeTierOnboardingService.complete) / cashier claims invitation (CashierInvitationService.claim)
  -> AFTER_COMMIT event -> MemberSyncClient (RestClient, retry, idempotent)
  -> PUT /api/v1/internal/tenants/{tenantId}/members/{userId}   header X-Internal-Service-Key
     {tenant_id, full_name, phone_number, role, status}
  -> AI DB: upsert tenants + tenant_members

Telegram user -> /start -> bot asks to share contact (request_contact button)
  accept ONLY message.contact with contact.user_id == from_user.id
  normalize phone (E.164) -> find ACTIVE tenant_members rows with that phone
    0 rows  -> "nomor tidak terdaftar"
    1 row   -> create tenant_memberships(verified_at) and use that tenant
    >1 rows -> user picks a tenant (/pilih), selection stored per conversation
Later messages: tenant (and role) read from verified membership, per message
  -> ChatService(tenant_id) -> graph state -> tool.execute(tenant_id, query) -> gRPC (service key) -> Java
```

Offboarding: Java sends status=DISABLED (staff disabled / cashier removed) -> AI marks member inactive and drops the verified membership.

## D. Work breakdown (workers write code; orchestrator reviews)

Contract first (orchestrator): member upsert/delete JSON above, idempotent, 200/204.

| Task | Scope | Main paths (reserve at delegation) | Depends |
|---|---|---|---|
| 059-0 Java phone field | Flyway V3 add nullable `phone_number` (normalized E.164) to `staff_users`; add phone to `RegisterUserRequest` (+ validation/normalization) and to the entity factory methods; response/mapper if exposed; profile update endpoint for existing users | db/migration, entity/shared/identity, dto/request|response/identity, service/RegisterService, tests | decision F1 |
| 059-1 Java sync client | `app.ai-service.*` properties; `MemberSyncClient` (RestClient); AFTER_COMMIT event from `complete()` and `claim()` (+ disable path); bounded retry, failure logging (outbox if durability wanted); never log phone/key | service/, dto/request/, application.properties, tests | contract, 059-0 |
| 059-2 Python internal API (revised TASK-053) | member upsert/get/disable, service-key guard (constant-time), Alembic 0002 | app/api/internal_*.py, app/application.py, app/chat/repositories.py, app/chat/entities.py, migrations/versions/0002_*.py, tests | contract |
| 059-3 Python identity resolution | contact handler, phone match, membership create, multi-tenant selection, per-message tenant + role, refactor `Repository`/`ChatService` away from process-wide tenant, conversations scoped by tenant (channel_accounts.tenant_id cannot stay NOT NULL for one shared bot), `AI_TENANT_ID` -> dev-only fallback | channels/telegram/*, chat/*, migrations, tests | 059-2 |
| 059-4 Java gRPC tenant policy | replace static allowlist with DB check (tenant exists + active); keep service key and limits | grpc/*, GrpcTenantAccessPolicy, tests | none |
| 059-5 Verification | `mvnw verify`, Python unit, manual Telegram end-to-end (user-run) | tests only | all |
| (UI) | `cashier-saas-ui` registration form phone field - separate repo, outside this plan's workers unless user adds it | - | 059-0 |

Parallel after contract: 059-0, 059-2, 059-4. Then 059-1 and 059-3.

## E. Risks and security notes

1. Trust only a Telegram `contact` whose `user_id == message.from_user.id`. A typed or forwarded number must never bind a tenant.
2. Existing users have no phone: they cannot be matched until they add one; need an update path and a clear bot message.
3. One phone can belong to several tenants (one person owns/works in many). Never guess: require explicit selection; each message uses only the selected, verified tenant.
4. Phone uniqueness per tenant, not global. A phone reused by different people over time (number recycling) -> offboarding sync matters.
5. Store normalized phone (consider salted hash); never log phone numbers, names with phone, or the service key.
6. Cross-service consistency: Java commits first and REST may fail -> idempotent upsert + retry/outbox; periodic reconcile is a later option.
7. Tenant must come only from server-side verified membership, never from message text; existing three guards (schema hides tenant_id, args purged, state-only) stay.
8. Role is available per message: decide later whether tools differ by role (CASHIER vs OWNERMERCHANT); out of scope now.
9. Schema/API changes: new Alembic and Flyway revisions only; registration API contract change affects the UI repo.
10. Testing policy: tests only when the user asks; real Telegram check is manual.

## F. Open decisions (need the user)

1. Where to collect the user's phone: add a required `phoneNumber` to `RegisterUserRequest` (covers owner and invited cashier uniformly, since cashiers also register an account first) - recommended; existing users get a profile-update path.
2. gRPC tenant authorization: replace the static env allowlist with a database check (tenant exists and active) - recommended.
3. Confirm the people who chat are staff (owner and cashier), not end customers of the cafe.
