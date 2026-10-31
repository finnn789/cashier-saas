# AI Services delegation — 2026-10-11

User explicitly authorized reading PLAN.md and delegating implementation. Setup memory16 is superseded by delegation memory17; paused UNIT work is not resumed.

PLAN reviewed in full. Initial bounded stage: TASK-038 implements Python configuration, isolated LangGraph and tenant-scoped approved FAQ; TASK-039 independently reviews current Spring identity/order capabilities and proposes secure integration contracts. Exact disjoint reservations are in tasks/TASK-038.md and tasks/TASK-039.md. All other code, existing scaffolding, metadata, Java, SQL, tests and canonical files excluded from worker writes.

Ruflo memory search found no AI Services match. Keyword routing returned tester due to wording; orchestrator selected implementation/reviewer from actual task scope. Hierarchical swarm initialized; Ruflo registrations are coordination only. Native Ruflo execution previously lacks configured provider; authenticated Codex CLI is the verified alternative, retaining workspace-write and automatic approval review.

Codebase Memory lists Cashier index but it predates new Ai-services scaffolding; current source is authoritative. Existing HS256 staff JWT and public order status capability were inspected; neither establishes WhatsApp customer mapping. `.ai/context/workflow.md` and `.ai/DECISIONS.md` are absent; use AGENTS.md and explicit task packets rather than invent missing guidance.

Acceptance for this stage: actual worker output with checkpoint report and reserved-path source review. Full WebSocket/Redis/Meta/Spring acceptance remains pending. No tests/build/startup/provider/DB operations authorized in this stage.

Worker execution status: both authenticated Codex CLI workers started with automatic approval review/workspace-write. TASK-038 session77928/thread01a12a68-098e-7040-9eaa-6d089d6d1f31; TASK-039 session73216/thread01a12a68-a567-7832-9dd2-9c52275aca1a. JSONL thread/turn/source-inspection events prove actual execution. Sonnet Ruflo records are coordination only; actual executors use configured gpt-5.6-terra, no native Sonnet execution claimed. Evidence directory: reports/artifacts/AI-SERVICES-20261011-DELEGATION/.

Launch checkpoint persisted as memory18; worker baseline17 retained. Reports and application results await review. Next: read worker checkpoints, inspect exact source/file-change events and baseline hashes, reconcile concurrent memory and preserve unrelated dirty files before acceptance. Do not launch downstream integration workers until contracts are reviewed.

## OpenRouter setup and contract review checkpoint

User selected OpenRouter and authorized continuation. Official LangChain installation section explicitly uses `uv add langchain-openrouter`; OpenRouter's LangChain guide links the same package and `ChatOpenRouter`. Sources: https://docs.langchain.com/oss/python/integrations/chat/openrouter and https://openrouter.ai/docs/guides/community/langchain.

Actual setup: uv add succeeded, then uv remove langchain-ollama succeeded. Installed/import-verified: FastAPI0.143.0, LangGraph1.2.14, LangChain1.4.4, langchain-openrouter0.2.9, OpenRouter SDK0.11.46, Pydantic2.12.5. No application imports/startup/model requests/tests. Optional PostgreSQL packages are not installed in default sync; extra remains declared. Orchestrator changed metadata/lock/template/docs; credentials/model remain blank. Worker writes remain separate.

TASK-038 initial thread interrupted for provider change; partial source is unaccepted. Same thread resumed as CLI session80445, with binding packet amendment memory19 and unchanged seven-path reservation. Worker confirmed reading amendment and installed SDK; OpenRouter completion/review pending.

TASK-039 accepted as source/contract inventory only: report and actual JSONL changes match report-only reservation; selected JWT/status/DTO/customer/POM findings verified against current source. Customer mappings/gRPC/ticket API are absent; existing public status capability does not establish customer ownership or WhatsApp mapping. Proposed messages/reservations remain recommendations, not approved public contracts. Runtime remains unverified. Routine Redis implementation choices can be made by orchestrator later; the report's broad approval suggestions do not themselves impose a user gate. Material customer/auth/schema/business decisions still need resolution before affected implementation.

Baseline comparison at this checkpoint finds only expected orchestrator canonical/packet/metadata/docs changes among preexisting captured files; no Java/SQL/test baseline changes. Worker file-event paths separately recorded in artifact directory.

## Final foundation review — accepted, memory21

TASK-038 same-thread implementation and correction passes completed. Reviewed actual six implementation files and final report, not just worker statements. Source implements explicit frozen/sanitized Settings, validated constructor snapshot, OpenRouter fixed URL/explicit key/token bounds, SDK retries explicitly disabled by ainvoke(retries=None), request-isolated LangGraph runtime, one question-only tenant-bound FAQ tool, deterministic terminal rejection of malformed/multiple/unknown/extra-argument/invalid_tool_calls, and cancellation/timeout sanitization. System prompt prohibits fabricated business actions; no approved business FAQ entries invented. Prompt adherence and provider/model tool capability still require future user-authorized runtime testing.

Accepted downstream contracts: `load_settings() -> Settings`; `build_agent(settings, faq_service) -> CustomerServiceAgent`; `respond(context: RequestContext, message: str, history: Sequence[ConversationMessage] = ()) -> AgentReply`; `FaqService(approved_by_tenant: Mapping[str, Sequence[FaqEntry]])`; `create_faq_tool(context, faq_service) -> StructuredTool`. `RequestContext` is trusted-adapter input, not auth proof. History/result are caller-owned and not durable; no delivery/streaming endpoint implemented.

Verification: environment dependency imports succeeded; static AST parsing succeeded for all15 app Python files, without app imports or execution. Full worker JSONL file-event audit shows zero allowlist violations, eight distinct reserved implementation/report paths across TASK-038/039. Existing src/pom baseline has no changes; user dirty work preserved. Worker hashes, baseline changes and path audit saved in artifact directory. No tests/Maven/build/app startup/model/DB/Redis calls. Corrections required several same-thread submissions; final source reflects all required corrections, reports preserve checkpoints.

Result: OPENROUTER ENVIRONMENT AND BOUNDED AI FOUNDATION COMPLETE / SOURCE REVIEWED. Entire PLAN acceptance remains pending authenticated web/queue/Spring/Meta integrations. Model and API key are deliberately unconfigured. No active implementation workers remain; previous UNIT pause preserved. Next stage needs material customer identity and business capability contracts, then exact new task reservations. Do not resume TASK-037 or automatically run tests/services.
