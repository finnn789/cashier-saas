# Contract: owner daily summary (Java backend -> Ai-services -> Telegram)

Owner: orchestrator. Version 1 (2026-11-14), TASK-080 (M7). Companion of `member-sync.md` (same key, same header).
The summary is read-only for the AI (AC-09): Ai-services only calls the three owner-only read RPCs (`cashier-service.grpc.schema.json`) and sends a Telegram message. It never changes cashier data.

## 1. Endpoint (implemented by Ai-services, called by the Java backend)

`POST /api/v1/internal/daily-summaries`

- Header `X-Internal-Service-Key`: the same shared secret as member sync (`AI_INTERNAL_API_KEY` on both services, compared in constant time). Never logged.
- Body (`application/json`, max 16 KiB):

| Field | Type | Rules |
|---|---|---|
| `tenant_id` | string | UUID of the tenant |
| `outlet_id` | string | UUID of the outlet |
| `business_date` | string | ISO date `YYYY-MM-DD`, the outlet-local business day (outlet-local date of the shift `openedAt`) |

- Responses:

| Status | Body | When |
|---|---|---|
| `200` | `{"delivered": n, "skipped": m}` | Request accepted and processed. `delivered` = owners who received every message chunk, `skipped` = owners not served (no summary section for the outlet, gRPC failure, Telegram send failure). A 200 with `delivered = 0` still counts as success for the Java side (best-effort, see section 3). |
| `401` | `{"error": "unauthorized"}` | Missing or wrong key. Generic body, no detail. |
| `422` | `{"error": "validation_error", "fields": [...]}` | Invalid body. Field names only, the submitted values are never echoed. |
| `503` | `{"error": "service_unavailable"}` | `AI_INTERNAL_API_KEY` not configured (fail closed), Telegram channel not ready, or the recipient lookup failed. |

- Check order: key (503 unset / 401 wrong), body validation (422), channel readiness (503), recipient lookup (503), delivery.
- Logs and responses never contain phone numbers, full chat ids or the key.

## 2. Recipients and message

- Recipients: members of the tenant with role `OWNERMERCHANT`, status `ACTIVE`, a `java_user_id`, and a verified Telegram account (private chat). Other roles never receive it.
- For each owner, Ai-services calls `GetSalesSummary` (start = end = `business_date`), `GetRestockSuggestions` and `GetAnomalies(date = business_date)` with that owner's `user_id`, takes the section whose `outlet_id` equals the request, and skips an owner without a summary section for that outlet (PERMISSION_DENIED, outlet not theirs, gRPC error).
- Text = deterministic Indonesian template (exact numbers from the RPCs, money formatted `Rp12.500`, negative `-Rp500`): omzet bersih, transaksi, tunai and each non-cash label with count, pengeluaran kas kecil, HPP and laba kotor (plus "N item tanpa HPP" when > 0), 3 menu terlaris, selisih kas and shifts closed, pembatalan / order diskon / refund, stok minus / habis / menipis with suggested purchase quantity (max 8 lines), cashier anomalies (max 6).
- Exactly ONE insight sentence from the LLM (OpenRouter, 8 second timeout) is appended as `Catatan: ...`. On any LLM error or timeout the message is sent without it. The LLM sees only the computed text, and never changes the numbers.
- Long messages are split into chunks (Telegram limit); each chunk is retried up to 3 times when Telegram asks to wait.

## 3. Java sender behavior (`DailySummaryService`, `DailySummaryClient`)

- Table `daily_summary_dispatches` (migration V10): one row per (`outlet_id`, `business_date`), `UNIQUE`, so the summary is sent at most once per outlet per business day. Columns `trigger` (`SHIFT_CLOSED` or `FALLBACK`), `status` (`PENDING`, `SENT`, `FAILED`), `attempts`, `last_attempt_at`.
- Triggers:
  - `SHIFT_CLOSED`: when closing or force-closing a shift leaves the outlet with no other OPEN shift. The row is inserted with `INSERT ... ON CONFLICT DO NOTHING`; only the call that inserted the row publishes the dispatch event, which is handled after the transaction commits.
  - `FALLBACK`: a scheduler (every `app.daily-summary.fallback-interval`, default `PT10M`) inserts a row for every active outlet of an active F&B tenant whose outlet-local time is 23:00 or later and that has no row for today yet.
- Dedupe: the unique row. A second last-shift close the same day, or a fallback after a shift-close summary, inserts nothing and sends nothing. A shift reopened after the summary was sent is not included.
- Client: one HTTP attempt per dispatch (no retry loop inside the client). Any 2xx marks the row `SENT`; any other status, I/O error or timeout marks it `FAILED` and increments `attempts`. Dispatch never throws into the shift-close path.
- Retries come from the scheduler: rows `PENDING` or `FAILED` with `attempts < 3`, last attempt (or creation) older than 10 minutes, and `business_date >= today - 2 days` (UTC date), at most 50 per sweep. After 3 attempts a row stays `FAILED`.
- Timeouts: connect 2 s, read `app.ai-service.daily-summary-read-timeout` (default 30 s, because Ai-services may call the LLM per owner).
- Disabled (rows may still be created on shift close, nothing is sent) when `app.daily-summary.enabled=false`, `AI_SERVICE_BASE_URL` is blank, or `AI_INTERNAL_API_KEY` is blank.
- Single application instance assumed for the scheduler: the unique row prevents duplicate rows, not two instances retrying the same row at once. Delivery is best-effort per owner; there is no per-recipient record.

## 4. Configuration

| Property | Environment variable (`.env.example`) | Default | Meaning |
|---|---|---|---|
| `app.daily-summary.enabled` | `CASHIER_DAILY_SUMMARY_ENABLED` | `true` (`false` in the test profile) | Guards the scheduler and dispatch |
| `app.daily-summary.fallback-interval` | `CASHIER_DAILY_SUMMARY_FALLBACK_INTERVAL` | `PT10M` | ISO-8601 interval of the fallback and retry sweep |
| `app.ai-service.daily-summary-read-timeout` | `CASHIER_DAILY_SUMMARY_READ_TIMEOUT` | `30s` | Read timeout of the POST |
| `app.ai-service.base-url` | `AI_SERVICE_BASE_URL` | blank (disabled) | Shared with member sync |
| `app.ai-service.api-key` | `AI_INTERNAL_API_KEY` | blank | Shared with member sync, at least 16 chars, distinct from `CASHIER_GRPC_SERVICE_KEY` |

Ai-services additionally needs `CASHIER_GRPC_SERVICE_KEY`, the Telegram bot token and the OpenRouter settings it already uses for chat.

## 5. Manual test

```
curl -X POST "$AI_SERVICE_BASE_URL/api/v1/internal/daily-summaries" \
  -H "X-Internal-Service-Key: $AI_INTERNAL_API_KEY" -H "Content-Type: application/json" \
  -d '{"tenant_id":"<TENANT_ID>","outlet_id":"<OUTLET_ID>","business_date":"2026-11-14"}'
```

Expected: `200 {"delivered": 1, "skipped": 0}` for a tenant with one verified owner; without the header `401`; with `"business_date":"14-11-2026"` `422` with `"fields": ["business_date"]`.
