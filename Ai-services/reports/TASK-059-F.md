# TASK-059-F Worker Report (Python identity resolution, shared bot, migration 0003)

Status: SUBMITTED - READY FOR REVIEW. Worker F, 2026-11-07. Memory version at delegation: 48 (not changed by me).

## Files changed (all under `Ai-services/`)
New:
- `migrations/versions/0003_shared_bot_tenants.py` (revision `0003_shared_bot_tenants`, down `0002_tenant_members`)
- `tests/unit/test_tenant_identity.py` (19), `tests/unit/test_tenant_intake.py` (14),
  `tests/unit/test_tenant_scope.py` (7), `tests/unit/test_memberships_sql.py` (6),
  `tests/unit/test_migration_0003.py` (9)
- `tests/integration/test_shared_bot_tenancy.py` (7, all PostgreSQL-gated)
- `reports/TASK-059-F.md`

Edited (production):
- `app/chat/entities.py`: `ChannelAccount.tenant_id` nullable; `Conversation.tenant_id` NOT NULL + FK `conversation_tenant`; `one_active_conversation` now `(tenant_id, channel_id, platform, user_id, external_chat_id)`; `TenantMembership.java_user_id` (nullable) + FK `tenant_membership_member` -> `tenant_members` ON DELETE CASCADE + index `tenant_membership_user`; new `TenantSelection` (`tenant_selections`).
- `app/chat/repositories.py`: `register_telegram_channel(connection, external_bot_id, username)` (no tenant); new `ChannelRepository(channel_id, platform)` with `upsert_user` and `for_tenant(tenant_id)`; `Repository(tenant_id, channel_id, platform)` is now the per-request tenant view (subclass).
- `app/chat/members.py` (extend only; C's functions untouched): `VerifiedMembership`, `list_memberships`, `replace_memberships`, `revoke_memberships`, `get_selected_tenant`, `select_tenant`, `clear_selected_tenant`.
- `app/chat/service.py`: `ChatService(database, channel_repository, ai, tools, graph, ai_settings, fallback_tenant_id=None)`; new `identify`, `verify_contact`, `choose_tenant`; `Identity`/`TenantOption` dataclasses and status constants; `handle(*, tenant_id, ...)` scopes with `for_tenant(tenant_id)` and puts `str(tenant_id)` into graph state / legacy dispatch. UNSUPPORTED help text now lists `/tenant`.
- `app/channels/telegram/config.py`: `TelegramSettings.tenant_id` -> `fallback_tenant_id: UUID | None = None`; `AI_TENANT_ID` optional (blank = none, malformed = safe RuntimeError).
- `app/channels/telegram/handlers.py`: contact handling, `/tenant`, per-message identity, Indonesian prompts, `deliver()` helper with optional keyboard.
- `app/channels/telegram/sender.py`: optional `reply_markup` (passed only when given, also on the plain-text fallback).
- `app/channels/telegram/bot.py`: registers the shared bot without a tenant, upserts the fallback tenant row when configured (+ WARNING log without the id), builds `ChannelRepository`.
- `app/core/database.py`: ONLY `EXPECTED_ALEMBIC_REVISION = "0003_shared_bot_tenants"`.

Edited (existing tests):
- `tests/unit/test_conversation.py`, `tests/unit/test_langgraph_chat.py`: setup only (a `ChannelRepository` mock whose `for_tenant` returns the existing repository mock; `tenant_id` added to the input dict). No assertion changed.
- `tests/unit/test_telegram_intake.py`: setup (`chat.identify` returns a VERIFIED identity; `TelegramSettings` without tenant). Added `identify` not-awaited check to the access test; `handle` expectation gains `tenant_id=` (the new required argument).
- `tests/unit/test_config.py`: missing/blank `AI_TENANT_ID` now yields `None` (requirement: optional); malformed still raises without echo. Field rename `tenant_id` -> `fallback_tenant_id`.
- `tests/unit/test_repository.py`: registration test rewritten for the tenant-less shared bot (2 statements, `tenant_id` NULL, no `tenants` insert, SET clause still never touches tenant/external id) + new invalid-bot-id test; metadata counts updated (tables + `tenant_selections`, columns 40 -> 45, FKs 7 -> 11, indexes + `tenant_membership_user`). All other repository assertions unchanged and still pass (tenant still in every scoped statement).
- `tests/unit/test_channel.py`: startup test now asserts registration without tenant and a tenant-less `channel_repository`; new fallback-startup test.
- `tests/unit/test_database.py`: new `test_database_still_at_revision_0002_is_rejected`.

Not touched: `app/api/internal_members.py`, `app/core/phone.py`, migrations 0001/0002, `app/ai/**`, `app/tools/**`, `migrations/env.py`, `app/config.py`, any Java file.

## Design decisions (and deviations, with reasons)
1. Migration 0003 upgrade: add `conversations.tenant_id`, backfill from the channel's tenant, SET NOT NULL, FK, rebuild `one_active_conversation` with tenant first; `channel_accounts.tenant_id` DROP NOT NULL and set to NULL (the legacy bot becomes the shared bot; otherwise the legacy single-tenant row would block other tenants).
2. Downgrade: drop selections/membership link; a `DO $$ ... RAISE EXCEPTION` guard refuses when a channel holds conversations of >1 tenant (0002 cannot represent that without mixing tenants; transaction rolls back); restore each NULL channel tenant from its conversations; delete shared channel rows that have no conversations (only the registration row; the 0002 service re-registers its bot at startup); SET NOT NULL; restore the old index; drop the column.
3. Verified membership remembers the Java member (`tenant_memberships.java_user_id`, FK ON DELETE CASCADE). Reason: per-message ACTIVE re-check by exact member row instead of storing the phone a second time. Legacy rows with NULL `java_user_id` count as not verified and are revoked.
4. Tenant choice: table `tenant_selections(user_id PK -> channel_users, tenant_id, selected_at)` with FK `(tenant_id, user_id) -> tenant_memberships` ON DELETE CASCADE, so the database itself refuses an unverified choice and a revoked membership drops its choice.
5. Contact flow: all ACTIVE matches become verified memberships (the user proved the phone); memberships of tenants that no longer match are removed. 1 match = auto-selected; >1 = selection cleared and the user must choose (`/tenant <n>`; the number indexes the server-side ordered list, so text can never name a tenant id). Two staff rows of one tenant with the same phone: least privileged role (CASHIER) is kept.
6. Per message: `identify` (transaction 1) upserts the channel user, reads the choice, re-checks each membership against `tenant_members` (DISABLED or missing -> revoke). Selected+ACTIVE -> VERIFIED with the member row's CURRENT role; selected but revoked -> REVOKED (never silently switches tenant); exactly one active left and no choice -> auto-select; several -> CHOOSE; none -> REVOKED/fallback/UNVERIFIED. Then `handle` runs the unchanged chat flow on `for_tenant(tenant)`. Deviation note: `handle` upserts the user again (tenant view guard) so the existing chat flow and its tests stay unchanged; cost one idempotent statement. A DISABLED push landing between `identify` and `handle` of the same message takes effect from the next message.
7. Role is resolved and returned in `Identity` but not passed into graph state (would need `app/ai/**` changes; tools do not use role; PLAN risk 8 says out of scope).
8. `AI_TENANT_ID` fallback: used only when the user has NO membership at all; never when verified, when a choice is pending, or on the message that revokes. Documented dev behavior: after a revocation removed every membership, later messages use the fallback. Startup logs a WARNING (no id) and ensures the tenant row exists (conversation FK).
9. Contact acceptance: only `message.contact` with `contact.user_id == from_user.id`, and not forwarded (`forward_origin`/`forward_date`) and not `via_bot`. Typed numbers are ordinary text (unverified -> ask for contact). No phone, name or contact data is logged; replies never echo the phone or tenant ids.
10. Tenant isolation guards kept and extended: every conversation/message statement carries `conversations.tenant_id = :tenant`; channel guard is `channel id + platform + (channel tenant IS NULL OR = tenant)`; tools/schemas unchanged; the `tenant_id` arg purge in the graph still applies.

## Commands and raw results (run from `Ai-services`)
- Baseline full unit: `uv run --locked python -m unittest discover -s tests/unit -p "test_*.py"` -> `Ran 178 tests in 3.170s` / `OK`
- New modules: `uv run --locked python -m unittest tests.unit.test_tenant_intake tests.unit.test_tenant_identity tests.unit.test_tenant_scope tests.unit.test_memberships_sql tests.unit.test_migration_0003 tests.unit.test_database -v` -> `Ran 67 tests in 0.837s` / `OK`
- Integration (no DB): `uv run --locked python -m unittest tests.integration.test_shared_bot_tenancy tests.integration.test_internal_members_api -v` -> `Ran 20 tests in 0.130s` / `OK (skipped=14)` (my 7 + C's 7 PostgreSQL tests skipped; C's 6 ASGI tests ran)
- FINAL full unit: `uv run --locked python -m unittest discover -s tests/unit -p "test_*.py"` -> `Ran 237 tests in 3.458s` / `OK` (178 baseline + 59 new)

## Gated PostgreSQL tests: NOT RUN
`AI_TEST_DATABASE_URL` is not set here; no real database was used. `tests/integration/test_shared_bot_tenancy.py` (reuses C's scratch-database helpers) covers: 0001 -> seed single-tenant data -> 0002 -> 0003 (conversations keep their tenant, messages kept, channel tenant NULL, NOT NULL, new index) -> downgrade to 0002 (channel tenant restored, data kept) -> upgrade again; downgrade refused for a two-tenant channel (version stays 0003); empty shared channel pruned on downgrade; real ChatService + repositories: same Telegram user in two tenants gets two conversations, duplicate detection/history/insert are tenant-bound; DISABLED member revokes membership and (by cascade) choice; FK rejects an unverified choice; one ACTIVE conversation per tenant/channel/user/chat. Their logic is unverified against PostgreSQL until the orchestrator runs them.

## Findings / risks
- `alembic.ini` lacks `path_separator` (pre-existing DeprecationWarning from Alembic; seen in C's tests too).
- Phone change in Java for an ACTIVE member does not revoke an existing Telegram verification (membership is linked to the member id); offboarding must send `DISABLED`. Phone recycling remains the PLAN risk 4.
- Pre-existing lines > 100 columns in `service.py:108`, `telegram/config.py:29`, `sender.py:70` (not mine); several of my test lines exceed 100 (as in C's tests). Ruff is not installed.
- Docs (D's scope) should mention `/tenant`, the contact button, migration `0003`, and that `AI_TENANT_ID` is a development fallback.

## Manual steps (real DB + real bot)
1. `cd Ai-services`; set `DATABASE_URL` (PostgreSQL), `TELEGRAM_BOT_TOKEN`, `AI_INTERNAL_API_KEY` (>= 16 chars), OpenRouter vars; leave `AI_TENANT_ID` unset for the real flow.
2. `uv run --locked alembic upgrade head` (-> `0003_shared_bot_tenants`; the service refuses to start on 0002).
3. Optional gated tests: `AI_TEST_DATABASE_URL=postgresql://<role-with-CREATEDB>@host/db uv run --locked python -m unittest tests.integration.test_shared_bot_tenancy tests.integration.test_internal_members_api -v`.
4. Start the service (`uv run --locked uvicorn app.application:app --port 8001` or compose).
5. Push staff: via Java (owner completes profile / cashier claims invitation with `AI_SERVICE_BASE_URL` set) or `PUT /api/v1/internal/tenants/{tenant}/members/{user}` with `X-Internal-Service-Key` and your own phone; for the multi-tenant case push the same phone into two tenants.
6. In Telegram (private chat): send `halo` -> asked to share contact; press "Bagikan nomor saya" -> "Verifikasi berhasil ..." (1 tenant) or a numbered list (several) -> `/tenant 2`, `/tenant`; ask a catalog question (tool runs with the chosen tenant); switch with `/tenant 1` and confirm the history is separate; try forwarding someone's contact or typing a number (rejected/ignored).
7. Revocation: re-PUT the member with `"status":"DISABLED"`; the next message answers "Akses Anda ... sudah tidak berlaku" and asks to re-verify.
