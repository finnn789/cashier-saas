# TASK-114 T1 (test-worker, RED)

Test status: NOT RUN (orchestrator runs RED).

## Behaviours covered
- Client PERMISSION_DENIED -> OWNER_ONLY: `test_insights.py::InsightClientTests::test_consume_ai_question_maps_permission_denied_to_owner_only`
- Service OWNER_ONLY refusal (message + graph not invoked): added `("OWNER_ONLY", "hanya tersedia untuk pemilik")` to `test_langgraph_chat.py::test_quota_refusal_returns_fixed_message_without_graph_or_assistant_message`
- Service unverified user: `test_langgraph_chat.py::test_unverified_user_is_refused_without_consuming_quota_or_calling_the_graph`
- Node forced final answer (agent_runs=3 -> tool_choice="none" binding, agent_runs 4) and normal (no agent_runs -> normal binding, agent_runs 1): `test_llm_error_codes.py::AgentNodeFailureTests::test_last_allowed_agent_run_forces_a_final_answer_with_tool_choice_none` (subTests)

## Existing tests modified
- `test_insights.py::test_consume_ai_question_returns_none_on_rpc_error_so_the_caller_fails_closed`: error code PERMISSION_DENIED -> UNAVAILABLE (PERMISSION_DENIED now maps to OWNER_ONLY).
- `test_langgraph_chat.py`: `self.input` gets `java_user_id` (unverified users no longer reach graph); `test_basic_conversation...` now asserts `user_id=str(java_user_id)` instead of `""`; `test_question_is_consumed_for_the_verified_user...` passes java_user_id by overriding `self.input` (avoids duplicate kwarg).
- `test_conversation.py`: `self.input` gets `java_user_id` (graph-running tests keep intent).
- `test_tenant_scope.py::test_same_telegram_user_has_isolated_conversation_per_tenant`: `base` gets `java_user_id`.

## Already covered (skipped)
- Client UNAVAILABLE -> None: existing (kept).
- Fail-closed None message: `test_quota_check_failure_fails_closed_without_calling_the_graph` (unchanged).

## Expected RED reason
- client OWNER_ONLY: currently returns None.
- OWNER_ONLY service case: `_QUOTA_REFUSALS` lacks it -> "tidak tersedia" message.
- unverified: consume currently awaited with user_id "" and graph invoked.
- node test: `bind_tools` never called with tool_choice, `agent_runs` missing in result (KeyError).

## Open questions
- Node test patches `app.ai.graph.nodes.adapt_tool_registry` and `ChatOpenAI.bind_tools`; the forced run needs `bind_tools(..., tool_choice="none")` as a keyword argument.
- `test_tenant_scope.py::VerifiedTenantReachesToolTests` goes through Intake with a membership that has java_user_id; left unchanged (assumed it passes the id through).
