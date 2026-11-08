# TASK-059-C Worker Report (Python member API, migration 0002, phone util)

Status: SUBMITTED with one known red existing test (see "Blocking item"). Worker C, 2026-11-07.

## Files changed (all under Ai-services/)
New:
- `app/api/__init__.py`, `app/api/internal_members.py` (router, pydantic `MemberUpsertRequest`/`MemberUpsertResponse`, `DatabaseMemberStore`)
- `app/core/phone.py` (`normalize_phone`)
- `app/chat/members.py` (Core repository functions)
- `migrations/versions/0002_tenant_members.py`
- `tests/unit/test_phone.py`, `tests/unit/test_internal_members.py`, `tests/integration/test_internal_members_api.py`
- `reports/TASK-059-C.md`

Edited:
- `app/application.py` (import + `app.include_router(internal_members_router)`; health routes and lifespan untouched)
- `app/config.py` (`InternalApiSettings` with `api_key` `repr=False`, `configured` = len >= 16, `load_internal_api_settings()` reading `AI_INTERNAL_API_KEY`; `Settings.internal_api` added as the LAST field with a default, so positional construction in existing tests is unaffected)
- `app/chat/entities.py` (`Tenant.name`, new `TenantMember`; existing models unchanged)

Not touched: `0001_channels.py`, `app/chat/repositories.py`, `app/chat/service.py`, `app/channels/**`, `app/core/database.py`.

## Behavior summary (contract section 1)
- Auth runs first (before UUID/body validation): key not configured (or < 16 chars) -> 503 `{"error":"service_unavailable"}`; missing/wrong -> 401 `{"error":"unauthorized"}`; `hmac.compare_digest` on UTF-8 bytes.
- Path UUIDs and body are validated by hand so the 422 body lists field NAMES only (`{"error":"validation_error","fields":[...]}`); FastAPI/pydantic default 422 would echo the submitted input (including the phone). Body capped at 16 KiB (422).
- Success 200 `{tenant_id, java_user_id, status}`; no phone or key anywhere in responses; logs carry no body, phone, name or key (tested).
- Storage: `DatabaseMemberStore` uses the running channel's `Database.transaction()` (`app.state.telegram_channel.database`); no channel/DB -> 503. SQLAlchemyError/OwnershipLost/timeouts -> generic 503.
- Idempotent upsert; tenant row created on demand; `tenant_name` overwrites only when given (COALESCE keeps the old one).

## Public functions in `app/chat/members.py` (for Worker F)
```python
@dataclass(frozen=True)
class MemberMatch:  # no phone field; full_name has repr=False
    tenant_id: UUID; java_user_id: UUID; tenant_name: str | None; role: str; status: str; full_name: str

async def upsert_tenant(connection, tenant_id: UUID, name: str | None) -> None
async def upsert_member(connection, tenant_id: UUID, java_user_id: UUID, full_name: str,
                        phone_normalized: str, role: str, status: str = "ACTIVE",
                        tenant_name: str | None = None) -> None
async def find_active_members_by_phone(connection, phone_normalized: str) -> list[MemberMatch]
    # ACTIVE only, across tenants, ordered by tenant name (NULLs last), tenant_id, java_user_id
async def get_member(connection, tenant_id: UUID, java_user_id: UUID) -> MemberMatch | None
    # any status; for the per-message "still ACTIVE?" re-check
async def disable_member(connection, tenant_id: UUID, java_user_id: UUID) -> bool
```
Also: `normalize_phone(raw: str) -> str | None` in `app/core/phone.py`.

## Commands and raw results
- `uv run --locked python -m unittest tests.unit.test_phone tests.unit.test_internal_members -v`
  -> `Ran 41 tests in 0.215s` / `OK`
- `uv run --locked python -m unittest tests.integration.test_internal_members_api -v`
  -> `Ran 13 tests in 0.128s` / `OK (skipped=7)` (6 ASGI tests ran; the 7 PostgreSQL tests were skipped)
- Full: `uv run --locked python -m unittest discover -s tests/unit -p "test_*.py"`
  -> `Ran 177 tests in 3.348s` / `FAILED (failures=1)` (baseline 136 + 41 new = 177; the single failure is NOT in my tests)

## Blocking item (outside my reserved paths, not edited)
`tests/unit/test_repository.py::RepositoryTests::test_minimal_metadata_constraints_are_declared` now fails because the required new model/column/index changed `Base.metadata`:
```
AssertionError: Items in the first set but not the second: 'tenant_members'
```
Needed edit (orchestrator/F to apply; I did not weaken anything): table set gains `"tenant_members"`; column total `31 -> 40` (tenants.name +1, tenant_members +8); ForeignKeyConstraint count `6 -> 7`; index set gains `"tenant_member_active_phone"`. Values verified by evaluating `Base.metadata` directly.

## Other findings for the orchestrator
- `app/core/database.py:83` `Database.acquire` hard-requires `alembic_version == "0001_channels"`; after migration 0002 is applied the bot will refuse to start ("Migrasi AI belum sesuai"). It must accept the new head (ideally derived from the script directory or a constant bumped per migration). `tests/unit/test_database.py` also asserts the 0001 string. Neither file is reserved for C; F's list does not include `database.py` either, so someone must own it. The same change is needed for F's 0003.
- The member API depends on the running Telegram channel's `Database` (single advisory-lock owner connection, serialized by its lock). If the bot is not running (e.g. missing token) the endpoint answers 503. Acceptable for v1 but note the coupling.
- Short keys (< 16 chars) are treated as "not configured" -> 503 (fail closed), consistent with the contract's "at least 16 chars".
- Ruff is not configured/installed in the project; I did not run it. Some test lines exceed 100 columns (the app code does not).

## Static migration verification (no DB)
Covered by `MigrationRevisionTests` in `tests/unit/test_internal_members.py`: revision id and `down_revision == "0001_channels"`; Alembic `ScriptDirectory` has exactly one head and 0002 sits directly after 0001 in the history; `upgrade()`/`downgrade()` rendered as offline PostgreSQL SQL and asserted (column types/nullability, PK `(tenant_id, java_user_id)`, FK, both CHECKs, `status DEFAULT 'ACTIVE'`, partial index `WHERE status='ACTIVE'`, downgrade order); model shape (`Tenant.name` String(160) nullable, `TenantMember` columns, partial index `where`). 0001 file confirmed free of the new table.

## Gated PostgreSQL tests
`PostgresMemberApiTests` (6 tests) and `PostgresMigrationRoundTripTests` (1 test) are gated by `AI_TEST_DATABASE_URL` (role must be able to CREATE DATABASE; a scratch database `ai_members_<hex>` is created, `alembic upgrade 0002_tenant_members` is run via subprocess with `DATABASE_URL` pointing at it, and the database is dropped afterwards). **They were NOT run: the variable is not set in this environment, and no real database was used. Their logic is therefore unverified against PostgreSQL**; the SQL they exercise was compile-checked against the postgresql dialect in the unit tests. Coverage: upsert + tenant creation, idempotency/updated_at/created_at, overwrite, phone lookup across tenants (ACTIVE only), disable_member, per-tenant separation, constraint violations (role, status, FK, PK, varchar(15)), status default, partial index definition, downgrade/upgrade round trip.

## Remaining work
- Apply the `test_repository.py` expectation update above and decide ownership of the `Database.acquire` version check.
- Run the gated tests once with a real `AI_TEST_DATABASE_URL`.

## Follow-up (orchestrator-extended paths) - DONE
- `app/core/database.py`: new module constant `EXPECTED_ALEMBIC_REVISION = "0002_tenant_members"` (comment: bump this single constant for each new revision, F -> 0003); `acquire` compares against it. No other Database behavior changed.
- `tests/unit/test_database.py`: imports the constant instead of hard-coding "0001_channels" in setUp; new test `test_database_still_at_revision_0001_is_rejected` (rejected with "Migrasi AI belum sesuai", rollback, not owned).
- `tests/unit/test_repository.py::test_minimal_metadata_constraints_are_declared`: only the legitimately changed expectations updated (tables + `tenant_members`, columns 31 -> 40, ForeignKeyConstraint 6 -> 7, indexes + `tenant_member_active_phone`).
- Full suite: `uv run --locked python -m unittest discover -s tests/unit -p "test_*.py"` -> `Ran 178 tests in 3.152s` / `OK`.
- The "Blocking item" and database.py finding above are now resolved. Gated PostgreSQL tests still not run.
