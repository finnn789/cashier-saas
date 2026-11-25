# TASK-098: Daily owner summary includes stock leakage and menu margin (Ai-services)

Status: DONE, REVIEWED (py_compile 0), NOT COMMITTED (2026-11-25, memory v98).

## User instruction (2026-11-25)
"Jika ada task lagi silahkan buatkan dan delegasikan." Picked from the audit backlog in `.ai/TASKS.md`: "AI daily summary has no leakage/margin". PRD v3 slices 1-2 (leakage report, menu HPP/margin) exist as gRPC read models and chat tools, but the daily Telegram summary ignores them.

## Verified facts (orchestrator, source read)
- `Ai-services/app/api/internal_daily_summaries.py` `_summary_for(client, tenant_id, user_id, outlet_id, day)` (:76-96): calls `get_sales_summary`, `get_restock_suggestions`, `get_anomalies`, picks this outlet with `_outlet_section(result, outlet_id)`, then `build_summary(summary, day, restock, anomalies)` and an optional LLM insight (`request_insight`).
- `Ai-services/app/chat/daily_summary.py` `build_summary(summary, business_date, restock=None, anomalies=None)` (:62): deterministic Indonesian text; sections are added only when non-empty; caps `MAX_STOCK_LINES = 8`, `MAX_ANOMALY_LINES = 6`; helpers `format_rupiah`, `_number`, `_count`.
- `Ai-services/app/integrations/grpc/client.py`: `get_leakage_report(tenant_id, user_id, date_from="", date_to="")` (:330; empty = last 30 days) and `get_menu_margins(tenant_id, user_id, max_margin_pct="")` (:341; empty = only menus below target). Both return `None` when unavailable (same as the other insight calls) and a dict with `outlets`.
- Field names used by the existing chat tools (`Ai-services/app/tools/insights.py:217-231, 281-295`): leakage outlet `outlet_name`, `stock_count_count`, `total_loss`, `flagged_count`, `lines[]` {`name`, `unit`, `theoretical_usage`, `shortage`, `variance_pct`, `loss_amount`, `waste`}; margin outlet `outlet_name`, `target_margin_pct`, `menus[]` {`name`, `price`, `hpp`, `margin_pct`, `status`, `suggested_price`}. Check the generated proto/`_call_insight` conversion for whether leakage lines carry a per-line "flagged" marker before choosing which lines to show.
- Leakage and margins are PRO features; the daily summary goes to owners of tenants that already pass the existing gRPC owner checks. A `None` result must simply omit the section.

## Design decisions
- D1 In `_summary_for`, also fetch leakage for that single day (`date_from = date_to = day.isoformat()`, so it only appears on days with a stock count) and menu margins (default = below target only); pick this outlet with `_outlet_section`. Run the extra calls the same way the existing ones are called (sequential is fine; do not add concurrency machinery).
- D2 `build_summary` gains two optional keyword parameters `leakage=None`, `margins=None` (existing callers and positional args unchanged).
- D3 Leakage section only when the outlet section exists and has `stock_count_count > 0`: header `Kebocoran stok (opname hari ini): rugi <Rp> , <flagged_count> bahan menyimpang`, then up to `MAX_LEAKAGE_LINES = 5` lines with the largest `loss_amount`: `- <name>: selisih <shortage> <unit>, rugi <Rp>`; "- dan N bahan lain" when cut.
- D4 Margin section only when menus is non-empty: header `Menu di bawah target margin (<target>%):`, up to `MAX_MARGIN_LINES = 5` lines with the lowest `margin_pct`: `- <name>: margin <pct>%, saran harga <Rp>`; "- dan N menu lain" when cut. Money via `format_rupiah`, numbers via `_number`.
- D5 Numbers only from gRPC (no LLM in these sections). The LLM insight input is the whole text as today.
- D6 No proto change, no Java change, no new dependency, no tests (policy; the user asks for tests separately).

## Wave 1

### P1 (`python-worker`)
Reserved: `Ai-services/app/chat/daily_summary.py`, `Ai-services/app/api/internal_daily_summaries.py` (only `_summary_for`).
Report: `Ai-services/reports/TASK-098-P1.md`.
Acceptance:
- A day with a stock count shows the leakage section with exact gRPC numbers; a day without one shows no leakage section.
- Menus below target show the margin section; none -> no section; a `None` gRPC result for either call -> that section is omitted and the summary is still sent.
- `build_summary(summary, day, restock, anomalies)` still works unchanged for existing callers.
- Do not run tests; a syntax/import check (`.venv/Scripts/python.exe -m py_compile <files>`) is allowed.
