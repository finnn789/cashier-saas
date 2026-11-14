# TASK-080 P1 report (Python gRPC client + AI tools)

Status: DONE (compileall only; pytest not run by policy).

## Changed files
- `Ai-services/app/integrations/grpc/client.py`: `_message_to_dict` (proto -> plain dict, int64 stay int, money stay str), `_call_insight` (key check, span, metadata, timeout, `_log_rpc_error`; any error incl. PERMISSION_DENIED -> `None`), `get_sales_summary(tenant_id, user_id, start_date, end_date)`, `get_restock_suggestions(tenant_id, user_id)`, `get_anomalies(tenant_id, user_id, date)`. Return dict mirrors proto field names (`outlets`, ...).
- `Ai-services/app/tools/insights.py` (new): `SalesSummaryTool` (`sales_summary`), `RestockSuggestionsTool` (`restock_suggestions`), `CashierAnomaliesTool` (`cashier_anomalies`). ISO date validated in the tool (else `invalid_input` message); no `user_id` or client `None` -> Indonesian "Data tidak tersedia; fitur ini hanya untuk owner". `user_id` comes from the trusted dispatch/adapter context, never the model.
- `Ai-services/app/tools/__init__.py`: tools registered in `get_default_tool_registry` and exported (`registry.py` unchanged; it has no registration list).
- `Ai-services/app/ai/prompts.py`: `SYSTEM` gets the read-only/owner-only rule; new `system_prompt(now=None)` appends "Tanggal hari ini (Asia/Jakarta): YYYY-MM-DD" (fixed UTC+7, no tzdata dependency); `build_prompt` uses it and counts its bytes in `MAX_PROMPT_BYTES` (~+0.5 KB, still bounded by existing trimming).

## Notes for orchestrator / T3
- Existing `tests/unit/test_ai.py` asserts `messages[0]["content"] == SYSTEM` (3 places); it must now compare to `system_prompt()` (or startswith SYSTEM). Not edited (outside reservation).
- `app/ai/graph/nodes.py` fallback path (system message injected only when none exists) still uses plain `SYSTEM` (no date); live path (`chat/service.py` -> `build_prompt`) has the date. nodes.py was not in my reserved files.
- Each insight tool lazily creates its own `CashierGrpcClient` (same pattern as `OrderStatusTool`).
- protobuf pinned <6, so `field.label == LABEL_REPEATED` is used (`is_repeated` needs 6.x).
- Stubs from J2 contain the new messages; verified by grep and `uv run python -m compileall app` passes.
- No phone numbers, tokens or keys are logged; errors log only the gRPC code.

## Amendment A1
`app/ai/graph/nodes.py`: import `system_prompt` instead of `SYSTEM`; both system-message uses (plain and with profile JSON) now call `system_prompt()`, so the graph path also gets today's Jakarta date and the owner-only rule. No other change; tests untouched. compileall passes.
