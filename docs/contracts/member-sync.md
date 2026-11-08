# Contract: staff member sync (Java backend -> Ai-services) and phone normalization

Owner: orchestrator. Version 1 (2026-11-07). Both sides MUST follow this file; tests on both sides read the fixtures in this folder.
Fixtures: `member-sync.example.json`, `phone-normalization-cases.json`.

## 1. Endpoint (implemented by Ai-services, called by the Java backend)

`PUT /api/v1/internal/tenants/{tenant_id}/members/{java_user_id}`

- `tenant_id`, `java_user_id`: UUID path parameters (Java `tenants.id` and `staff_users.id`). Invalid UUID -> 422.
- Header `X-Internal-Service-Key`: shared secret. Env `AI_INTERNAL_API_KEY` on BOTH services (Java property `app.ai-service.api-key`, Python `Settings`). Compared in constant time (`hmac.compare_digest`).
  - Missing/wrong key -> `401` with a generic body, no detail about why.
  - Key not configured on the Ai-services side -> `503` (fail closed, never accept).
- Body (JSON, `application/json`):

| Field | Type | Rules |
|---|---|---|
| `tenant_name` | string or null | 1..160 chars when present; business name |
| `full_name` | string | 1..160 chars, trimmed |
| `phone_number` | string | already NORMALIZED digits, regex `^[0-9]{9,15}$` (no `+`) |
| `role` | string | `OWNERMERCHANT` or `CASHIER` |
| `status` | string | `ACTIVE` (default) or `DISABLED` |

- Success: `200` `{"tenant_id": "<uuid>", "java_user_id": "<uuid>", "status": "ACTIVE|DISABLED"}`.
- Idempotent upsert: same call twice = same state, no error. Changed fields overwrite. The tenant row is created if absent (and `tenant_name` stored/updated when given).
- The response and logs must NOT contain the phone number or the key. Never log the request body.

## 2. Java sender behavior (`MemberSyncClient`)

- Triggered AFTER the owning transaction commits: owner at `FreeTierOnboardingService.complete(...)`, cashier at `CashierInvitationService.claim(...)` (also when the invitation was already claimed by the same user, because the call is idempotent and heals an earlier failed sync).
- Sent fields: `tenant_name` = tenant business name, `full_name` = `StaffUserEntity.fullName`, `phone_number` = `StaffUserEntity.phoneNumber` (already normalized when stored), `role` from the membership, `status` = `ACTIVE`. If the user has no phone: skip and log at WARN with ids only.
- Disabled (no-op, INFO log once) when `app.ai-service.base-url` is blank. Default is blank, so existing tests and deployments are unaffected.
- Timeouts: connect 2 s, read 5 s. Up to 3 attempts, backoff 200 ms then 800 ms; retry on I/O errors and 5xx only, never on 4xx.
- Never throws into the business transaction/response path. Failure is logged (tenant id + user id + HTTP status/class only).
- Durable outbox is OUT OF SCOPE for v1; the risk (sync lost if Ai-services is down for all attempts) is documented, healed by re-claim/re-complete or a later reconcile task.

## 3. Phone normalization (Java `PhoneNumberNormalizer` and Python `normalize_phone` MUST be equivalent)

Input: raw string. Output: digits-only string 9..15 chars, or INVALID (Java: validation error 400; Python: returns None).

1. Trim. Allowed characters: digits, spaces, `-`, `(`, `)`, `.`, and a single `+` only as the FIRST character. Any other character -> INVALID.
2. `d` = the digits only. If the raw started with `+`, the number is international: output `d` as is.
3. Otherwise (no `+`): if `d` starts with `0` -> `"62" + d[1:]`; else if `d` starts with `62` -> `d`; else if `d` starts with `8` -> `"62" + d`; else -> `d` (assumed to already include a country code).
4. Result length must be 9..15, else INVALID. Empty -> INVALID.

Shared cases: `phone-normalization-cases.json` (`input` -> `expected`, `null` = invalid).

## 4. Environment variables

| Variable | Used by | Meaning |
|---|---|---|
| `AI_SERVICE_BASE_URL` | Java -> property `app.ai-service.base-url` | Base URL of Ai-services, e.g. `http://ai-services:8001`. Blank = sync disabled |
| `AI_INTERNAL_API_KEY` | Java (`app.ai-service.api-key`) and Python | Shared secret for `X-Internal-Service-Key`, at least 16 chars. Distinct from `CASHIER_GRPC_SERVICE_KEY` |

## 5. Telegram side (Ai-services only)

- Phone is read ONLY from a Telegram `contact` message whose `contact.user_id == message.from_user.id`, normalized with section 3. Typed numbers and forwarded contacts are rejected.
- A normalized phone may match `tenant_members` rows in several tenants: the user must choose; never guess.
