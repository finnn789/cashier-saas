# TASK-082 T2 report: Python tests for quota gate, recursion cap, usage

Not run (policy). `py_compile` OK on all six changed files. No `app/**` change, no new file, no new helper/base class/fake class (only inline `Mock`/`AsyncMock` stubs and the existing fixtures).

## Changed files (all under `Ai-services/tests/unit/`)
- `test_langgraph_chat.py`
- `test_conversation.py`
- `test_tenant_scope.py`
- `test_daily_summary.py`
- `test_insights.py`
- `test_internal_daily_summaries.py`

Not changed: `tests/integration/test_shared_bot_tenancy.py` and `test_tenant_identity.py` (their `ChatService` has no `ai`/`graph`, so `ai_path == 'unconfigured'` and the gate is skipped).

## Part A: existing tests kept valid
- `test_langgraph_chat.py`: `setUp` gets `self.grpc` (Mock with `consume_ai_question` AsyncMock returning allowed, `record_ai_usage` AsyncMock). Every `ChatService(...)` passes `grpc_client=self.grpc`. Both `fake_ainvoke` now accept `config`.
- `test_conversation.py`: `setUp` builds the same stub and passes it to `ChatService`.
- `test_tenant_scope.py`: the two `ChatService` constructions that drive TEXT get an inline `Mock` with `consume_ai_question`; `ainvoke` fake accepts `config`. (`test_handle_requires_a_uuid_tenant` raises before the gate, left alone.)
- `test_daily_summary.py`: all `request_insight` calls pass `(self.grpc, "t1")`; `self.grpc = AsyncMock()` in `RequestInsightTests.setUp`.
- Legacy fake completions: `Completion` has int defaults, so no change was needed beyond the one test below that sets real counts.

## Part B: new coverage (search first, extend where possible)

| # | Behaviour | Where | Existing tests searched / why not a duplicate |
|---|---|---|---|
| 1 | Three refusal reasons: fixed message, no graph call, no ASSISTANT message, no usage (one subTest loop) | new `test_quota_refusal_returns_fixed_message_without_graph_or_assistant_message` in `LangGraphChatServiceTests` | Searched `test_langgraph_chat.py` (error tests at 231/263/295), `test_conversation.py` (hard error/exhaustion tests), `test_tenant_scope.py`. They cover AIError and graph error codes only; none touches the gate, which did not exist before. |
| 2 | `warn=True` appends `used/limit` line to the reply, not to the persisted message | new `test_quota_warning_is_appended_to_the_reply_but_not_persisted` | Same search; no test asserts reply suffixes or persisted-vs-returned difference. |
| 3 | `GraphRecursionError` returns the "terlalu rumit" reply, nothing persisted; `ainvoke` gets `config={'recursion_limit': 7}` | new `test_recursion_limit_returns_too_complex_reply_and_persists_no_assistant` | The existing `test_graph_exceptions_*` loop covers AIError/Timeout/Runtime only, and GraphRecursionError is caught by a separate `except` before the generic one. The config assertion is the cap contract, asserted here instead of a standalone constant test. |
| 4a | Usage summed from `AIMessage.usage_metadata` goes to `record_ai_usage`; consume is called with the verified `java_user_id` | new `test_question_is_consumed_for_the_verified_user_and_graph_usage_is_recorded` | No existing test passes `java_user_id` to `handle` or inspects usage; one test covers both facts of the same successful graph run. |
| 4b | Daily summary insight records tokens for the tenant, never consumes a question; no record when the LLM fails | **extended** `test_returns_the_sentence_and_sends_summary_without_tools` (outcome now carries tokens, +2 assertions) and `test_llm_failure_gives_none_and_logs_only_the_error_type` (+1 assertion) in `test_daily_summary.py` | No parallel test added. |
| 4c | Endpoint wires `(client, tenant_id)` into `request_insight` | **extended** `test_delivers_to_each_owner_and_counts` in `test_internal_daily_summaries.py` (+1 assertion) | `request_insight` is patched there, so this is the only place that can see the wiring. |
| 4d | Legacy `ai.reply` path: consume before the LLM, tokens recorded afterwards | **extended** `test_user_commits_before_ai_and_assistant_commits_before_return` in `test_conversation.py`: the shared `reply` fixture now returns tokens 11/5, +2 assertions | Not a new test; legacy-path coverage of the A3 change. |
| 5 | `consume_ai_question` returns `None` (gRPC error) -> "tidak tersedia" reply, graph not called | new `test_quota_check_failure_fails_closed_without_calling_the_graph` in `test_langgraph_chat.py` | No test covers a `None` from the client; the client-side `None` is tested in 6b. |
| 6a | `consume_ai_question` request fields and response mapping to dict | new `test_consume_ai_question_maps_the_quota_decision` in `InsightClientTests` (`test_insights.py`) | Existing `CASES` loop tests assert `result["tenant_id"]`, which `ConsumeAiQuestionResponse` does not have, so the parametrized loops could not be reused as is. The other loops (metadata, timeout, empty key -> None) use `_call_insight` and already cover those branches for every insight method. |
| 6b | `consume_ai_question` returns `None` on `AioRpcError` | new `test_consume_ai_question_returns_none_on_rpc_error_so_the_caller_fails_closed` | Existing `test_rpc_error_including_permission_denied_returns_none` loops only over `CASES`, which lacks the new RPC. |
| 6c | `record_ai_usage` sends tenant + token counts | new `test_record_ai_usage_sends_tenant_and_token_counts` | `record_ai_usage` takes no `user_id`, so it cannot join `CASES`. |
| 6d | `record_ai_usage` swallows errors (subTest: `AioRpcError`, `RuntimeError`) | new `test_record_ai_usage_swallows_errors` | Same reason. |

## Notes and risks for the orchestrator
- `test_init_with_unconfigured_ai_settings` (existing): `ai` is set (legacy path) so the gate runs; the stub allows it and the legacy path then fails with AI_NOT_CONFIGURED as before. This is P1 Deviation 4 (a question is consumed with no LLM call); the test still passes but does not assert on it. Not tested further to avoid pinning a behaviour that is arguably a production defect.
- Not tested (accepted shortcuts): tokens of a run that hits the recursion limit are not recorded (P1 Deviation 3), the legacy second-completion usage record, unknown refusal reason -> "tidak tersedia", and the zero-token skip in the graph path.
- No production bug found while writing the tests.
- The tests have not been executed. The user should run `Ai-services\.venv\Scripts\python.exe -m unittest discover -s tests/unit -p test_*.py`.
