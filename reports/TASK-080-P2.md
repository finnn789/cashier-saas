# TASK-080 P2 report: daily summary endpoint

Status: implemented, `uv run python -m compileall app` OK and `import app.application` OK. Tests not run (policy).

## Changed files
- NEW `Ai-services/app/api/internal_daily_summaries.py`: `POST /api/v1/internal/daily-summaries`.
  - Reuses `_authorize`, `_validation_error`, `_error_fields`, `_json`, `MAX_BODY_BYTES`, `get_internal_api_settings` from `internal_members.py` (imported, nothing moved, that file untouched).
  - Order: key check (503 unset / 401 wrong) -> body size/validation (422 with field names only; `tenant_id` UUID, `outlet_id` UUID, `business_date` ISO date) -> 503 if `app.state.telegram_channel` is missing or not `ready()` -> recipient lookup (503 on DB error) -> per recipient send.
  - Per recipient: `get_sales_summary(t, u, d, d)`, `get_restock_suggestions(t, u)`, `get_anomalies(t, u, d)` (P1 client, used via `async with CashierGrpcClient`, injected by the `get_grpc_client` dependency so tests can override it). Takes the section whose `outlet_id` equals the request; no summary section (None / PERMISSION_DENIED / outlet missing) = skipped. Restock and anomalies sections are optional (omitted if missing).
  - Sends through `channel.intake.sender` (the running `Sender`), `split_chunks`, up to 3 attempts per chunk on PENDING (sleep `min(retry_after, 10)`). Delivered only if every chunk is SENT. Any exception for one recipient is logged by type only and counted as skipped.
  - Response 200 `{"delivered": n, "skipped": m}`.
- NEW `Ai-services/app/chat/daily_summary.py`:
  - `format_rupiah` (`Rp12.500`, negative `-Rp500`, HALF_UP), `build_summary(summary, business_date, restock, anomalies)` (deterministic Indonesian template: omzet bersih, transaksi, tunai (method CASH) and each non-cash label with count, pengeluaran kas kecil, HPP and laba kotor, "N item tanpa HPP" when > 0, 3 menu terlaris, selisih kas and shift ditutup, pembatalan/order diskon/refund, stok minus/habis/menipis with saran beli (max 8 lines), anomalies (max 6; CASH_DIFFERENCE as money)).
  - `request_insight(text)`: existing `OpenRouterClient` + `load_ai_settings()` with a short-lived `aiohttp.ClientSession`, `asyncio.timeout(8)`, any exception -> `None` (only the error type is logged). Takes the first non-empty line, max 300 chars, else `None`. The endpoint appends `\n\nCatatan: <sentence>` only when present.
- EDIT `Ai-services/app/chat/repositories.py`: one query `list_telegram_owner_recipients(connection, tenant_id) -> list[(java_user_id, chat_id:int)]`: `tenant_memberships` (java_user_id not null) join `channel_users` (platform TELEGRAM; the private chat id equals `external_user_id`, non-numeric skipped) join `tenant_members` (role OWNERMERCHANT, status ACTIVE), tenant-scoped. Adds `TenantMember`, `TenantMembership` imports and `MEMBERS`/`MEMBERSHIPS` table constants.
- EDIT `Ai-services/app/application.py`: one import and one `include_router`.

## Privacy
No phone numbers, chat ids, tokens or keys are logged or returned. Log lines carry only counts and exception type names.

## Deviations and notes
- Report is in `Ai-services/reports/` (same place as P1's report), not repo-root `reports/`.
- The first write attempt of this worker was interrupted by a shell quoting error; only the repository query had landed, the rest was written afterwards (no duplicates).
- Telegram `Sender` defaults to `parse_mode="Markdown"`; product names with `_` or `*` may trigger its built-in plain-text fallback, which is acceptable.
- Recipients are processed sequentially, and each one costs up to 8 s for the insight. Many owners per tenant means a slow response; the Java client timeout is 30 s (`daily-summary-read-timeout`). For tenants with more than about 3 owners, consider generating the summary text once per outlet (it is identical per owner as long as all owners see the same outlet data), which was not done to keep per-recipient authorisation of the gRPC data.
- Not verified at runtime: the P1 return shape (`{"outlets": [...]}` with `outlet_id` strings) was coded against the contract only.

## Open questions
None blocking.

## Fix round 1
- Bug (found by T3): `_summary_for` in `Ai-services/app/api/internal_daily_summaries.py` called `get_sales_summary`, `get_restock_suggestions` and `get_anomalies` positionally, but the real `CashierGrpcClient` methods are keyword-only. Every call raised TypeError, was swallowed by the per-recipient handler and counted as skipped.
- Fix: the three calls now use keywords matching `client.py`: `get_sales_summary(tenant_id=, user_id=, start_date=, end_date=)`, `get_restock_suggestions(tenant_id=, user_id=)`, `get_anomalies(tenant_id=, user_id=, date=)`. Nothing else changed.
- Check: `uv run python -m compileall app` OK. Tests not run.
