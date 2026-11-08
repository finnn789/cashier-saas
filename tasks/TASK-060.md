# TASK-060: JSON logging, end-to-end log trace, and AI error behaviour fix (Ai-services)

Status: IMPLEMENTED / NOT TESTED BY POLICY (2026-11-07). Memory version at delegation: 51.
User request: make the Telegram E2E run observable (handler + sender trace), JSON logging with a dedicated directory,
stdout + optional rotating file, `python-json-logger`, extend to ChatService and gRPC client, then add graph logs and fix
the AI error behaviour. Tests were NOT run (AGENTS.md policy: run only on explicit user request).

## Waves, workers and reserved writes (all Ai-services; orchestrator wrote the shared contracts)

| Wave | Worker | Model | Reserved writes |
|---|---|---|---|
| 0 | orchestrator | opus/sonnet session | first pass: plain logs in `app/channels/telegram/{handlers,sender,bot}.py`, `app/application.py`; user-approved direct exception for this step |
| 1 | A | sonnet | `app/observability/*` (new), `application.py`, `main.py`, `pyproject.toml`, `uv.lock`, `.env.example`, `README.md` (3.6 Logging), `docker-compose.yml` (LOG_* env), `tests/unit/test_observability.py` |
| 1 | B | sonnet | `app/channels/telegram/{handlers,sender,bot}.py`, `tests/unit/test_tenant_intake.py` |
| 1 | C | sonnet | `app/chat/service.py` (structured logs) |
| 1 | D | sonnet | `app/integrations/grpc/client.py` (`_rpc_span`) |
| 2 | E | sonnet | `app/ai/graph/error_codes.py` (new), `app/ai/graph/nodes.py`, `tests/unit/test_llm_error_codes.py` (new) |
| 2 | F | sonnet | `app/chat/service.py`, `tests/unit/test_langgraph_chat.py` |
| 2 | G | sonnet | `app/ai/graph/{edges,workflow,tools_adapter}.py`, `app/tools/registry.py` |

Shared contracts (scratchpad, not committed): `logging-contract.md`, `ai-error-contract.md`.

## Behaviour changes (not only logging)

- Agent node on LLM failure returns only `{"error_code": <code>}` (no `final_response`, no fallback `AIMessage`).
- `ChatService.handle` treats ANY non-empty `error_code` from the graph as an error: returns the matching `_error_message`
  and persists NO ASSISTANT message. The outer `except` classifies via `classify_llm_error` (type/status based) instead of
  searching `401/402/403` in `str(exc)`.
- A bare `TimeoutError` from `graph.ainvoke` now yields the transport message ("Koneksi AI terputus"), not the generic one.
- `aiogram` logger level `CRITICAL` -> `WARNING` (polling failures, 409 conflict, bad token visible; RedactionFilter masks token patterns).

## Evidence (orchestrator)

- `py_compile` OK on every touched file; import smoke of all modules OK.
- Simulated Telegram message (mocked chat/sender): 11 JSON lines with update_id/message_id/chat_id/user_id/tenant_id on each; reply text and long digit runs absent.
- Real compiled graph with `ChatOpenAI.ainvoke` forced to raise a 402: state = `error_code=AI_HTTP_402`, no `final_response`, 1 message; log chain `agent_node_started -> llm_invocation_failed -> graph_route_decided(end)`.
- Unit/integration tests: NOT RUN. New: `test_observability.py` (15), `test_llm_error_codes.py`; adjusted: `test_tenant_intake.py`, `test_channel.py` (supervisor check now scans `logs.records`), `test_langgraph_chat.py` (timeout expectation + 4 new tests).

## Open risks / follow-ups

- Pre-existing logs that print exception MESSAGES remain (E2E phase decision): `nodes.py` `LLM agent node invocation failed`, `Tool execution error for tool`, `service.py` `Chat graph execution failed`, `sender.py` parse-mode warning and `Telegram send API error`, `tools/registry.py` `Failed to execute tool`. Tighten before production (RedactionFilter masks tokens/keys/long digits only, not user text).
- File logging in Docker needs a host `logs` dir writable by container user `app` (volume left commented in compose).
- `tool_registered` INFO fires on every `register()` (noisy in tests). A leftover tool `check_laptop_cpu_usage` is registered in the default registry (not investigated).
- `tenant_id`/`user_id` are logged by design for E2E tracing.
- Not covered: `app/ai/openrouter.py` legacy client, `app/api/internal_members.py`.
- READY FOR TESTING when the user asks: run `uv run --locked python -m unittest` on `tests/unit` from `Ai-services`.
