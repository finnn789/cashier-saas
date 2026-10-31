# TASK-053: Internal Tenant REST Management API in AI Services (CRUD Endpoints)

Status: PENDING IMPLEMENTATION
Memory version: 41
Date: 2026-10-30 (Asia/Jakarta)

## Task Scope & Acceptance
Implement internal administrative/synchronization REST API endpoints under `/api/v1/internal/tenants` in `Ai-services` (FastAPI) to support full lifecycle management (Create, Read, Update, Delete) of tenants and their associated channel accounts (WHATSAPP/TELEGRAM):

1. **POST `/api/v1/internal/tenants` (Create / Sync Tenant)**:
   - Request Body:
     ```json
     {
       "tenant_id": "UUID string (required)",
       "business_name": "string (optional)",
       "phone_number": "string (optional, for WhatsApp binding)",
       "platform": "string (optional, default: 'WHATSAPP')"
     }
     ```
   - Logic:
     - Validates `tenant_id` as valid UUID.
     - Inserts into `tenants` table (`id`, `created_at`). Idempotent via `ON CONFLICT (id) DO NOTHING`.
     - If `phone_number` is provided, upserts corresponding row into `channel_accounts` table:
       - `platform`: `request.platform` or `'WHATSAPP'`
       - `external_account_id`: normalized phone number
       - `username`: `request.business_name`
       - `tenant_id`: `request.tenant_id`
   - Response: `HTTP 201 Created` with created/synced tenant ID and timestamp.

2. **GET `/api/v1/internal/tenants` (List All Tenants)**:
   - Query Parameters: `limit` (default: 50, max: 200), `offset` (default: 0).
   - Logic:
     - Fetches paginated tenants along with any registered channel accounts (platform, external_account_id, username).
   - Response: `HTTP 200 OK` with JSON array and `total` count:
     ```json
     {
       "total": 1,
       "items": [
         {
           "tenant_id": "...",
           "created_at": "...",
           "channel_accounts": [
             {
               "id": "...",
               "platform": "WHATSAPP",
               "external_account_id": "6281234567890",
               "username": "Kopi Senja"
             }
           ]
         }
       ]
     }
     ```

3. **GET `/api/v1/internal/tenants/{tenant_id}` (Get Single Tenant)**:
   - Logic:
     - Validates `tenant_id` UUID format.
     - Fetches single tenant by ID with channel accounts.
     - If not found, returns `HTTP 404 Not Found`.
   - Response: `HTTP 200 OK` with tenant details.

4. **PUT `/api/v1/internal/tenants/{tenant_id}` (Update Tenant Data)**:
   - Request Body:
     ```json
     {
       "business_name": "string (optional)",
       "phone_number": "string (optional)",
       "platform": "string (optional)"
     }
     ```
   - Logic:
     - Validates tenant existence; returns `HTTP 404 Not Found` if missing.
     - Updates associated `channel_accounts` record (e.g. phone number or business name).
   - Response: `HTTP 200 OK` with updated details.

5. **DELETE `/api/v1/internal/tenants/{tenant_id}` (Delete Tenant)**:
   - Logic:
     - Validates tenant existence; returns `HTTP 404 Not Found` if missing.
     - Safely deletes tenant in compliance with foreign key constraints (cascading or explicit deletion of `tenant_memberships`, `conversations`, `messages`, and `channel_accounts` tied to the tenant).
   - Response: `HTTP 200 OK` with deletion confirmation message.

6. **Security & Authentication Guard**:
   - Header validation: `X-Internal-Service-Key` must match `AI_SERVICE_INTERNAL_KEY` (configured in settings / environment with safe fallback for testing).
   - If header is missing or mismatch: return `HTTP 403 Forbidden` (`{"detail": "Forbidden: Invalid internal service key"}`).

7. **Unit Test Suite**:
   - Create `tests/unit/test_internal_tenants.py` covering:
     - Authorized & unauthorized requests (403 forbidden check).
     - Valid creation (POST 201) and idempotent re-creation.
     - List all tenants (GET 200).
     - Single retrieval (GET 200) and 404 on non-existent tenant.
     - Update (PUT 200) and 404 on non-existent tenant.
     - Delete (DELETE 200) and subsequent 404 confirmation.
     - Invalid UUID validation (400 Bad Request).

## Exclusive Reserved Writes
- `Ai-services/app/api/internal_tenants.py`
- `Ai-services/app/api/__init__.py`
- `Ai-services/app/application.py`
- `Ai-services/app/chat/repositories.py`
- `Ai-services/app/config.py`
- `Ai-services/tests/unit/test_internal_tenants.py`
- `Ai-services/reports/TASK-053.md`

## Invariants & Rules
- Do NOT alter existing minimal multichannel schema (`0001_channels`).
- Maintain tenant isolation and strict UUID format validation.
- Do NOT run unit, API, integration, or test commands during implementation (tests not run by policy per AGENTS.md until explicitly requested).
- Write completion report to `Ai-services/reports/TASK-053.md`.
