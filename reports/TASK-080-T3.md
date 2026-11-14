# TASK-080 T3 report (Python unit tests)

Status: written; `uv run python -m compileall -q tests` passes. pytest NOT run (policy).

## New files (`Ai-services/tests/unit/`)
- `test_insights.py`: client methods (proto -> plain dict mapping incl. nested/repeated, request fields, `x-internal-service-key` metadata + timeout, `None` on RpcError incl. PERMISSION_DENIED/UNAVAILABLE, `None` on unexpected error, empty key fails closed with no stub call, no key in logs) and the three tools (schema hides tenant/user, success args, owner-only message on `None`, `user_id` missing, invalid date, registry dispatch ignores model-supplied `user_id`).
- `test_daily_summary.py`: `format_rupiah` (`Rp12.500`, HALF_UP, negative, garbage), `build_summary` (core lines, "N item tanpa HPP" only when > 0, 3 best sellers, omitted empty sections, stock + restock suggestion, caps 8 stock / 6 anomaly lines, CASH_DIFFERENCE as money, empty input), `request_insight` (first line, empty/overlong -> None, LLM error/timeout -> None, logs error type only, 8 s contract), `system_prompt()` (today's Jakarta date, UTC->WIB rollover, base prompt, bounded `build_prompt`).
- `test_internal_daily_summaries.py`: endpoint with fake channel/gRPC/LLM (401 wrong/missing key, 503 unset key, 422 field names only incl. oversized body, 503 channel missing/not ready/DB error, delivered/skipped counts, outlet section match/skip for summary, restock and anomalies, recipient None/other outlet skipped, one failing recipient does not stop others, no insight on LLM failure, PENDING retry / give up after 3 / FAILED / every chunk must be SENT, no chat id or key in logs/response) and `list_telegram_owner_recipients` compiled SQL (tenant, OWNERMERCHANT, ACTIVE, TELEGRAM, java_user_id not null, member join) + non-numeric chat ids skipped.

## Existing tests fixed
- `test_ai.py`: `messages[0]` now compared to `system_prompt()` (2 places; the third `SYSTEM` mention was the import, kept for nothing else, harmless). `test_langgraph_chat.py` and others have no such assertion.
- `test_tools.py::test_default_tool_registry` and `test_grpc_tools.py::test_default_tool_registry_contains_all_tools`: schema count 2 -> 5 (names asserted).

## Suspected app bug (tests will FAIL on it until fixed)
`app/api/internal_daily_summaries.py::_summary_for` calls the client positionally (`client.get_sales_summary(tenant_id, user_id, iso, iso)`, `get_restock_suggestions(tenant_id, user_id)`, `get_anomalies(tenant_id, user_id, iso)`), but the real `CashierGrpcClient` methods are keyword-only (`*, tenant_id=...`). Against the real client every call raises `TypeError`, is swallowed by the per-recipient `except Exception`, and every recipient is counted as skipped, so no summary is ever sent. Fix: call with keywords. Detected by `DailySummaryEndpointTests.test_calls_match_the_real_client_signatures` (uses `create_autospec(CashierGrpcClient)`); the other endpoint tests use a permissive fake and are unaffected.

## Minor notes
- `build_summary` renders anomaly `baseline_daily_average` for CANCELLATIONS/DISCOUNTS through `int()` (truncates 1.20 to 1); acceptable, not asserted beyond integer input.
- Two sample strings in tests use names like "Kedai Senja"; all ids/keys are synthetic, no real tokens or phones.
