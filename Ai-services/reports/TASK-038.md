# TASK-038 — AI Service foundation worker report

## Baseline and reservation acknowledgement

- Memory baseline observed: **17** (`.ai/MEMORY.md`).
- Baseline artifacts observed: `reports/artifacts/AI-SERVICES-20261011-DELEGATION/baseline.csv` and `git-status-before.txt`.
- The repository was already broadly dirty. `Ai-services/` was an untracked scaffold containing only package markers and setup material; none of the reserved implementation paths existed.
- Reserved write paths owned by this worker, and no others: `app/config.py`, `app/ai/state.py`, `app/ai/agent.py`, `app/ai/system_prompt.md`, `app/cases/faq/service.py`, `app/cases/faq/tool.py`, and this report.
- TASK-037 and the paused Java UNIT work were not resumed. No tests, builds, startup, dependency installation, provider, database, Redis, or network operations will be run.

## Checkpoint 1 — scope read

- Read the task packet, repository guidance, `Ai-services/PLAN.md`, `pyproject.toml`, and `.env.example`.
- Verified installed source APIs for LangGraph 1.2.14 and LangChain-Ollama without executing an application/import/provider call. The design will use `StateGraph`, `ChatOllama`, and a closure-bound `StructuredTool`.
- Pending implementation: settings, immutable trusted context/state, tenant-filtered FAQ service/tool, bounded graph/agent, and Indonesian safety prompt.

## Checkpoint 2 — provider amendment and implementation

- Re-read the TASK-038 binding provider amendment and memory **19**. The orchestrator’s concurrent `pyproject.toml`, `uv.lock`, `.env.example`, `README`, and `PLAN` changes are expected and were not edited by this worker.
- Read installed `langchain_openrouter` 0.2.9 source. `ChatOpenRouter` accepts explicit `api_key`, `timeout` in milliseconds, `max_tokens`, `max_retries`, and `bind_tools(..., parallel_tool_calls=False)`.
- Replaced the interrupted Ollama-only partial configuration with OpenRouter-only runtime configuration. `OPENROUTER_API_KEY` is `SecretStr`, required only by `validate_ai_runtime()` during `build_agent`; no arbitrary base URL is configured or exposed. Validation errors are fixed messages and do not echo values or secrets.
- Implemented all six application paths in the reservation: immutable internal request/history/reply values; copy-on-construction tenant FAQ data; a closure-bound FAQ `StructuredTool` with no selectable identity fields; an Indonesian safety prompt; and a reusable `StateGraph`/`ChatOpenRouter` agent.
- Per request, the graph receives a new immutable runtime object containing the trusted context and FAQ service. It neither retains conversation state nor places context, secrets, tenant ID, subject ID, or conversation ID into messages/tool schemas. The only model-visible tool is tenant-bound FAQ search.
- Bounds implemented: 4,000-character inbound/final reply; ten history messages of 2,000 characters each; FAQ question/answer/result caps 500/2,000/3; one tool call; configured graph recursion limit 2–32; response timeout 1–300 seconds passed both to OpenRouter (milliseconds) and an `asyncio.timeout`; output 64–1,024 tokens; `max_retries=0`.
- Model/tool/runtime failures return sanitized Indonesian unavailable/escalation wording. `asyncio.CancelledError` is re-raised cleanly. No test, build, startup, import execution, provider, Redis, database, dependency, or network operation was run.

## Final handoff — FOUNDATION SUBMITTED FOR SOURCE REVIEW

### Downstream signatures

- `load_settings() -> Settings` reads process environment only; it does not discover or load `.env` files.
- `FaqService(approved_by_tenant: Mapping[str, Sequence[FaqEntry]])`; `search(tenant_id: str, question: str) -> FaqLookup`. The supplied collection is copied into immutable tuples and a lookup reads only the exact trusted tenant key.
- `create_faq_tool(context: RequestContext, faq_service: FaqService) -> StructuredTool`. Its model schema accepts only `question`; tenant, customer, channel, subject, and conversation identity are captured server-side and cannot be model-selected.
- `build_agent(settings: Settings, faq_service: FaqService) -> CustomerServiceAgent` validates the OpenRouter-only runtime before constructing a client.
- `await CustomerServiceAgent.respond(context: RequestContext, message: str, history: Sequence[ConversationMessage] = ()) -> AgentReply`. `ConversationMessage` is concrete `role: Literal["user", "assistant"]` plus bounded plain-text `content`; `AgentReply` contains `message`, `status: Literal["completed", "unavailable"]`, and `escalation_offered`.

### Static source evidence

- `app/config.py`: OpenRouter provider/model/key and timeout/step/output/temperature validation at lines 21–73; `SecretStr` protects the provider key and other configured secrets.
- `app/ai/agent.py`: `ChatOpenRouter` uses an explicit API key, pinned official `https://openrouter.ai/api/v1` base URL, configured timeout converted to milliseconds, 1,024-or-less output tokens, and `max_retries=0` at lines 56–63. The shared graph passes a fresh `_InvocationContext` per `respond` call (lines 91–103), limits execution with `recursion_limit`, exposes one FAQ tool with `parallel_tool_calls=False`, and caps tool calls at one.
- `app/cases/faq/service.py` and `tool.py`: FAQ content is bounded and tenant-filtered; empty knowledge returns clarification/escalation guidance. The tool has no identity argument.
- `app/ai/system_prompt.md`: requires Indonesian, approved-FAQ-only responses and forbids claimed order, payment, refund, delivery, or ticket outcomes.
- A source search found no remaining Ollama/`ChatOllama`/`OLLAMA` references in the seven reserved paths. All changed paths are within this task’s exact reservation.

### Limitations and next work

- This is **FOUNDATION SUBMITTED FOR SOURCE REVIEW**, not READY FOR TESTING and not an end-to-end service. HTTP/WebSocket entry points, authentication and customer mapping, authorization proof, queue/dedup/order handling, durable state, Spring/gRPC, Meta WhatsApp, ticketing, observability, and delivery remain unimplemented.
- `RequestContext` is an internal trusted-adapter value, not an authorization mechanism. Future adapters must authenticate and authorize before constructing it.
- Recommended later tests (only after authorization): missing/invalid runtime configuration without secret echo; tenant FAQ isolation and prompt/tool argument injection; history/message/FAQ bounds; cancellation/timeout/model failure; one-tool-call cap; OpenRouter client construction with pinned endpoint; then separately authenticated WebSocket, queue, Spring, and WhatsApp integration scenarios.
- Tests were **NOT RUN BY POLICY**. No build, startup, dependency installation, provider, network, Redis, database, or generated-code operation occurred.

## Checkpoint 3 — source-review corrections

- Re-read the new TASK-038 source-review corrections. The prior final handoff is superseded by this corrected submission; the historical Ollama checkpoint remains superseded by the binding OpenRouter amendment.
- `Settings` is now Pydantic-frozen with `hide_input_in_errors=True`. `load_settings()` catches Pydantic parse failures and raises only `RuntimeError("Invalid service configuration.")` without exception chaining. Runtime validation continues to use fixed error messages and never formats supplied values.
- `CustomerServiceAgent.__init__` now re-parses `settings.model_dump()` into a fresh frozen `Settings` snapshot and calls runtime validation before creating `ChatOpenRouter`. Direct construction and any caller-side object bypass therefore cannot retain mutable or unvalidated runtime configuration. The pinned official OpenRouter endpoint remains explicit.
- The graph now accepts a tool turn only when it contains exactly one call named `search_approved_faq`, with a nonblank call ID and an arguments object whose exact key set is `{question}` containing a bounded nonblank string. Multiple calls, unknown tools, missing/invalid IDs, missing questions, non-string questions, blank questions, oversized questions, and extra argument keys route to `tool_rejected` and end. No such AI tool-call message reaches a subsequent provider invocation.
- No test, build, startup, import execution, dependency installation, provider, database, Redis, or network operation occurred during correction.

## Corrected final handoff — FOUNDATION SUBMITTED FOR SOURCE REVIEW

- Downstream signatures remain unchanged from the preceding handoff. `CustomerServiceAgent(settings, faq_service, system_prompt)` is now also a validation boundary: it snapshots and validates the frozen `Settings` itself; callers should still prefer `build_agent(settings, faq_service)`.
- Source evidence: `app/config.py` lines 12–17 configure frozen/hidden validation errors and lines 80–85 sanitize `load_settings`; `app/ai/agent.py` lines 52–69 validate and snapshot construction, lines 152–183 deterministically reject invalid tool-call envelopes, and line 63 pins `https://openrouter.ai/api/v1`.
- Limitations remain unchanged: this is not an HTTP/auth/queue/Spring/Meta/customer-mapping implementation and `RequestContext` remains trusted-adapter-only, not authorization proof. Tests remain **NOT RUN BY POLICY**.

## Checkpoint 4 — final source-review gap corrections

- Re-read the current TASK-038 packet and inspected the current implementation plus installed `langchain_openrouter` source. This checkpoint supersedes the preceding corrected handoff where it described `max_retries=0` as sufficient to disable all SDK retry behavior.
- `app/config.py` now imports and catches both `pydantic.ValidationError` and `pydantic_settings.SettingsError` in `load_settings()`. JSON/environment parsing failures therefore become only `RuntimeError("Invalid service configuration.")` with no chained raw input or secret/DSN rendering.
- `app/ai/agent.py` now routes any `AIMessage.invalid_tool_calls` to `tool_rejected` before considering `tool_calls`. This includes parsed-invalid tool calls when `tool_calls` is empty, so they cannot be treated as a completed customer answer or lead to another provider turn.
- `app/ai/agent.py` now calls `await model.ainvoke(state["messages"], retries=None)`. Installed `langchain_openrouter` 0.2.9 `_agenerate` forwards invocation kwargs to `send_async`; `retries=None` is the supported explicit per-call override for the OpenRouter SDK default retry/backoff. Constructor `max_retries=0` remains, but is no longer claimed to be sufficient by itself. The outer `asyncio.timeout` remains in place.
- No tests, builds, imports, startup, provider calls, database/Redis/network operations, dependency installation, canonical writes, or out-of-reservation edits occurred.

## Final submission — FOUNDATION SUBMITTED FOR SOURCE REVIEW

- All downstream signatures and limitations remain as documented above. Final source evidence for the three requested corrections: `app/config.py` catches `SettingsError` and `ValidationError` in `load_settings`; `app/ai/agent.py` checks `invalid_tool_calls` before `tool_calls`; and `_model_node` passes `retries=None` to `ainvoke`.
- This remains a bounded foundation only, not READY FOR TESTING or an end-to-end service. Tests were **NOT RUN BY POLICY**.
