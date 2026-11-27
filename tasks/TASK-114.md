# TASK-114: AI assistant hardening (quota refusal messages, no lost quota on complex questions, no dev tenant fallback in compose)

Status: DONE 2026-11-27 (uncommitted). RED 5 failing -> GREEN 268/268 `unittest discover -s tests/unit`. Orchestrator follow-up: P1's copied RPC body folded into `_call_insight(..., on_permission_denied=)`. Branch `feature/leakage-report`. Baseline commit: `545ac38`.

## User instruction (2026-11-27)
After the ai-services review: fix the risks "kuota tetap terpotong kalau agent kehabisan langkah" and "AI_TENANT_ID fallback aktif di production", and make refusals understandable. AI stays owner-only (current Java behaviour, `CashierAiUsageGrpcHandler.java:44` calls `requireOwnerOutlets`).

## Global rules (every worker)
- Reports: `reports/TASK-114-<id>.md`.
- No proto change, no Java change, no Alembic migration, no new dependency.
- Reuse: `CashierGrpcClient._call_insight` / `_log_rpc_error` (`app/integrations/grpc/client.py`), `_QUOTA_REFUSALS` + `_finish` (`app/chat/service.py`), `create_agent_node` (`app/ai/graph/nodes.py`), `_rpc_error` helper in `tests/unit/test_insights.py`.
- Out of scope (decided): role-based tool filtering (Java already refuses non-owners before the LLM), gRPC TLS (internal compose network only), quota refund RPC, the `AI_NOT_CONFIGURED`-after-consume check at `service.py:425-433` (unreachable: the graph is only built when the key exists, `service.py:146`).
- Orchestrator does `docker-compose.yml:87` itself: `AI_TENANT_ID: ${AI_TENANT_ID:-}` (no dev UUID default).

## Orchestrator design decisions (reversible, user may override)
- D1 Owner-only refusal: `consume_ai_question` maps gRPC `PERMISSION_DENIED` to `{'allowed': False, 'reason': 'OWNER_ONLY'}`. Every other error still returns `None` (fail closed, message "sedang tidak tersedia"). Other insight RPCs keep returning `None` on every error.
- D2 `_QUOTA_REFUSALS['OWNER_ONLY'] = 'Asisten AI hanya tersedia untuk pemilik usaha.'`
- D3 No verified staff user (`java_user_id is None`, i.e. the dev fallback tenant): `handle` does NOT call `consume_ai_question`; it replies `NOT_VERIFIED_MESSAGE = 'Akun Anda belum terverifikasi. Kirim /start lalu bagikan nomor Telegram Anda.'` and the user message is still stored as today (same `_finish` path as other refusals).
- D4 Forced final answer: `AgentState.agent_runs: int` (missing = 0). The agent node returns `agent_runs + 1`. On the run where `agent_runs + 1 >= MAX_AGENT_RUNS` (`MAX_AGENT_RUNS = 4`, module constant in `nodes.py`), it invokes the LLM bound with the same tools and `tool_choice="none"` (`llm.bind_tools(lc_tools, tool_choice="none")`, built once in the factory next to `llm_with_tools`), so the model must answer from the tool results it already has. Tools stay bound because some providers reject tool-call history without tool declarations. With 4 agent runs on supersteps 0,2,4,6 the existing `recursion_limit` 7 still fits, so `MAX_AGENT_STEPS` and the `GraphRecursionError` branch in `service.py` stay unchanged as a safety net.

## Contracts shared between workers
- `CashierGrpcClient.consume_ai_question(*, tenant_id, user_id) -> dict | None`: as D1. Provider P1, consumer P1 (`service.py`).
- `AgentState.agent_runs: int` (in `state.py`), read and returned by the agent node. Provider and consumer P2.
- `nodes.MAX_AGENT_RUNS = 4`.

## Wave 0 — RED (`test-worker`, before any production code)

### T1 (`test-worker`)
Reserved:
- `Ai-services/tests/unit/test_insights.py` (change `test_consume_ai_question_returns_none_on_rpc_error_so_the_caller_fails_closed` at ~line 287: PERMISSION_DENIED now maps to OWNER_ONLY; keep UNAVAILABLE -> None)
- `Ai-services/tests/unit/test_langgraph_chat.py` (add cases; existing test at ~line 359 `consume_ai_question.return_value = None` stays)
- `Ai-services/tests/unit/test_llm_error_codes.py` only if the agent-node cases fit better next to its `create_agent_node` test (~line 96); otherwise put them in `test_langgraph_chat.py`
Do: write the cases under "Test cases". Patch the LLM the way existing tests do (`patch("langchain_openai.ChatOpenAI.ainvoke", ...)` or by patching `bind_tools`). They must fail until Wave 1.

## Wave 1 — GREEN (parallel, after RED is confirmed; never edit the Wave 0 tests)

### P1 — quota refusals (`python-worker`)
Reserved:
- `Ai-services/app/integrations/grpc/client.py` (`consume_ai_question` only)
- `Ai-services/app/chat/service.py` (`_QUOTA_REFUSALS`, new `NOT_VERIFIED_MESSAGE`, the consume block in `handle` ~lines 412-424 only)
Do: D1, D2, D3. Keep the `_log_rpc_error` logging for PERMISSION_DENIED.
Acceptance: T1 client and service cases green; all existing `tests/unit` still green.

### P2 — forced final answer (`python-worker`)
Reserved:
- `Ai-services/app/ai/graph/state.py`
- `Ai-services/app/ai/graph/nodes.py` (`create_agent_node` only)
Do: D4. The agent node's error path (`return {"error_code": code}`) is unchanged. Log `agent_runs` and `forced_final` in the existing `agent_node_started` log extra.
Acceptance: T1 node cases green; existing tests green.

## Test cases (TDD - written and RED before any production code)
- Client owner-only: `ConsumeAiQuestion` raises PERMISSION_DENIED -> `{'allowed': False, 'reason': 'OWNER_ONLY'}` (test_insights::test_consume_ai_question_maps_permission_denied_to_owner_only)
- Client other error: UNAVAILABLE -> `None` (existing test, keep that half)
- Service owner-only: consume returns `{'allowed': False, 'reason': 'OWNER_ONLY'}` -> reply contains "hanya tersedia untuk pemilik", graph not invoked (test_langgraph_chat)
- Service unverified: `handle(..., java_user_id=None)` with a graph -> `consume_ai_question` not awaited, graph not invoked, reply contains "belum terverifikasi" (test_langgraph_chat). Check existing tests that call `handle` without `java_user_id` and still expect consume/graph to run (e.g. `self.input` in test_langgraph_chat, test_tenant_scope ~lines 192/257, test_conversation ~line 65): give them a `java_user_id` so their intent is kept.
- Node forced final: `create_agent_node` with tools, state `agent_runs=3` -> the LLM used is the `tool_choice="none"` binding, returned state has `agent_runs == 4` (test_langgraph_chat or test_llm_error_codes)
- Node normal: state without `agent_runs` -> normal tool binding used, returned `agent_runs == 1`
- RED evidence (orchestrator): `cd Ai-services && uv run python -m unittest discover -s tests/unit` -> new cases fail
- GREEN evidence (orchestrator): same command -> all pass
