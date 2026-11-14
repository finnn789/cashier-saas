# TASK-082 P1 report: Python quota gate, recursion cap, usage

Not run (policy). `py_compile` OK on `app/chat/service.py` and `app/integrations/grpc/client.py`.

## Changed files
- `Ai-services/app/integrations/grpc/client.py`: `consume_ai_question(*, tenant_id, user_id)` and `record_ai_usage(*, tenant_id, input_tokens, output_tokens)`.
- `Ai-services/app/chat/service.py`: quota gate, recursion cap, usage recording, warn line.
- NOT changed: `app/chat/daily_summary.py`, `workflow.py`, `nodes.py` (see Deviations 1 and 2).

## Contract points
- **client.py**: both methods reuse the existing `_call_insight` (key check, `_rpc_span`, timeout, `_log_rpc_error`, catch-all). `consume_ai_question` returns `{allowed, reason, used, limit, warn}` or `None` on any gRPC/config error (None = fail closed). `record_ai_usage` returns None and never raises; failures are logged by the shared helper (status code / error type only).
- **service.py `ChatService`**: new optional constructor arg `grpc_client` (default `CashierGrpcClient(load_grpc_settings())`, env read only; the channel opens lazily). In `handle`, only for `kind == 'TEXT'` (commands return earlier) and only when an AI path exists (`ai_path != 'unconfigured'`), after the user message is stored and before the graph or legacy `ai.reply`:
  - `None` -> "Asisten sedang tidak tersedia, coba lagi nanti."
  - `allowed=false` -> fixed Indonesian message per reason (PLAN_PREMIUM_REQUIRED / MONTHLY_QUOTA_EXHAUSTED / DAILY_CAP_REACHED); unknown reason -> the unavailable message.
  - `warn=true` -> `"\n\nKuota AI: {used}/{limit} pertanyaan bulan ini."` appended to the returned reply (both graph and legacy path). The persisted ASSISTANT message does not include the line (so history fed to the LLM stays clean).
  - Refusal/unavailable/too-complex replies are returned via `_finish` and NOT persisted as ASSISTANT messages, same as the existing `_finish_error` path (the USER message stays stored).
  - User id sent is the verified `java_user_id` (same as tools); `''` when missing (FALLBACK identity), which Java refuses -> fail closed.
- **Recursion cap**: `config={'recursion_limit': MAX_AGENT_STEPS}` with `MAX_AGENT_STEPS = 7`. `GraphRecursionError` (caught before the generic `except Exception`) -> "Pertanyaannya terlalu rumit, coba dipersempit." The question stays consumed.
- **Token usage**: after the graph returns (before the error_code check) the `usage_metadata` input/output tokens of the `AIMessage`s in the final state are summed and `record_ai_usage` is awaited (it never raises and is bounded by the gRPC timeout, so no background task exists that could be "destroyed pending"). Skipped when both sums are 0.

## recursion_limit calculation
Graph shape: entry `agent`; `agent -> tools` when the last AIMessage has tool_calls else END; `tools -> agent`. Supersteps: agent=0, tools=1, agent=2, tools=3, agent=4, tools=5, agent=6, tools=7, agent=8. LangGraph (installed version in `.venv`, `pregel/loop.py`: `stop = step + recursion_limit + 1`, check `step > stop`) executes exactly `recursion_limit` supersteps. I verified with a throw-away stub graph (agent/tools stubs, no LLM, script deleted): limit 5 and 6 -> 3 agent runs, limit 7 -> 4 agent runs, limit 8 -> 4 agent runs (plus a wasted tools step). So 7 = exactly 4 agent (LLM) runs, and when the 4th run still asks for tools the graph stops without executing them (error reply). A 4th agent run that answers ends normally.

## Deviations
1. **Daily summary tokens not recorded (daily_summary.py unchanged).** Two blockers outside reserved paths: (a) `app/ai/openrouter.py` `Completion` carries no usage (`data['usage']` is discarded); (b) `request_insight(summary_text)` has no tenant id or gRPC client, and its only caller is `app/api/internal_daily_summaries.py::_summary_for` (which has `client` and `tenant_id`). Needed follow-up (orchestrator to assign): add `usage: dict | None = None` to `Completion` and fill it from `data.get('usage')` (`prompt_tokens`/`completion_tokens`) in `OpenRouterClient.reply`; change `request_insight(summary_text, client, tenant_id)` to await `client.record_ai_usage(...)` after the reply (it already swallows everything); pass `client, tenant_id` from `_summary_for`. It never calls `consume_ai_question`.
2. **Legacy path (`self.ai.reply`, graph None)**: gated by the quota and the warn line, but its tokens are not recorded for the same `Completion` reason as (1); the fix in (1) lets it record too (small follow-up in service.py).
3. **GraphRecursionError: usage of the partial run is not recorded** (the exception carries no state and there is no checkpointer). Documented in a code comment; cheap capture would need a checkpointer or callback.
4. **AI_NOT_CONFIGURED edge**: the existing "graph present but settings missing key" check sits inside the graph branch, after the gate, so in that rare misconfiguration a question is consumed with no LLM call. Moving the gate below that check would restructure existing code; left as is.
5. `GrpcSettings.service_key` missing in env -> `_has_service_key` False -> `None` -> every chat question is refused as "tidak tersedia" (intended fail closed).

## Open risks for the orchestrator
- **Cashiers**: Java `ConsumeAiQuestion` is owner-only, so a CASHIER chatting with the bot now gets "Asisten sedang tidak tersedia" (consume fails with PERMISSION_DENIED -> None). If cashiers should keep catalog/order Q&A, that needs a decision (role-based skip needs the role in `handle`, which currently receives no role).
- Dev FALLBACK identity (no Java user) is refused the same way.

## Existing tests that will need stubs (wave 4)
Every test that drives `ChatService.handle(kind='TEXT')` with a graph or `ai` now calls `self.grpc_client.consume_ai_question`; without a stub it hits the real client (no service key -> None -> "tidak tersedia"). Provide a stub in the existing fixtures by passing `grpc_client=` to the constructor (a Mock/AsyncMock with `consume_ai_question` returning `{'allowed': True, 'reason': '', 'used': 1, 'limit': 300, 'warn': False}` and an AsyncMock `record_ai_usage`):
- `tests/unit/test_langgraph_chat.py` (constructions at lines ~159, 219, 247, 288, 302, 318). Also: assertions on `mock_graph.ainvoke` call args must now accept `config={'recursion_limit': 7}`; graph-state `AIMessage`s with `usage_metadata` can cover the usage case.
- `tests/unit/test_conversation.py` (line ~67, `ai` legacy path).
- `tests/unit/test_tenant_scope.py` (lines ~191, 212, 252): graph mocks and tool registry; same stub.
- `tests/integration/test_shared_bot_tenancy.py` (line ~191) if it sends TEXT with an AI configured.
- `tests/unit/test_tenant_identity.py` only constructs without calling `handle` TEXT with AI: probably unaffected.
- `tests/unit/test_grpc_client.py` / `test_insights.py`: add cases for `consume_ai_question` (dict on success, None on AioRpcError and missing key) and `record_ai_usage` (never raises), using their existing stub style.

## A3 (usage for daily summary and legacy path)
py_compile OK on `app/chat/service.py`, `app/ai/openrouter.py`, `app/chat/daily_summary.py`, `app/api/internal_daily_summaries.py`. Not run (policy). This resolves Deviations 1 and 2 above.

Changed files:
- `app/ai/openrouter.py`: `Completion` gets `input_tokens: int = 0`, `output_tokens: int = 0` (defaults, after the existing fields, so every existing constructor keeps working). `reply` fills them from `data['usage']` `prompt_tokens` / `completion_tokens` (non-dict usage or non-int values give 0, never an error) for both the tool-call and the plain answer return.
- `app/chat/daily_summary.py`: `request_insight(summary_text, client, tenant_id)` (smaller change than returning usage). After a successful reply it awaits `client.record_ai_usage(...)` when either count is non-zero, even if the insight text is later rejected by the length check (tokens were spent). It never calls `consume_ai_question`. Failures stay silent (the client call never raises; an LLM failure still returns None before recording).
- `app/api/internal_daily_summaries.py`: `_summary_for` passes `client, tenant_id` (the existing values) to `request_insight`. Nothing else touched.
- `app/chat/service.py` legacy `ai.reply` path: records tokens after the first completion and after the second (post-tool) completion, each with the same awaited never-raising `record_ai_usage` call, so a failure of the second step still keeps the first step's tokens. Unlike the graph path there is no zero-sum guard (a zero record is a harmless upsert).

Tests (wave 4): `request_insight` signature changed (called directly in `tests/unit/test_daily_summary.py` lines ~192-208, which must pass a client stub and tenant id; also check tests of `_summary_for`). Tests whose fake `ai.reply` returns a Mock instead of a real `Completion` need integer `input_tokens`/`output_tokens` (or a real `Completion`), because the legacy path now reads them; the grpc_client stub from the main section covers `record_ai_usage`.
