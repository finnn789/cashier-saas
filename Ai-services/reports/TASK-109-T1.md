# TASK-109 T1 report (Python tests)

Test status: NOT RUN (orchestrator runs RED).

## Changed files (all under `Ai-services/tests`)

1. `unit/test_grpc_tools.py`: deleted `test_dispatch_catalog_and_order_tools`; removed the unused `ToolRegistry` import.
2. `unit/test_grpc_client_security.py`: `SAFE_CODES` -> `RPC_ERROR_CODES`, added `UNAVAILABLE`; tests renamed to
   `test_catalog_rpc_errors_return_empty_without_leaking_key` and
   `test_order_status_rpc_errors_return_none_without_leaking_key`.
3. `unit/test_grpc_client.py`: `test_rpc_error_fallback` -> `test_order_status_not_found_returns_none` (single case, no table).
4. `integration/test_internal_members_api.py`: deleted `test_database_failure_is_a_generic_503_and_rolls_back`;
   removed the now unused `RecordingDatabase.error`, its rollback branch and the `OperationalError` import.
5. `unit/test_internal_members.py`: `test_store_runs_the_upsert_inside_one_database_transaction` also proves rollback.
6. `unit/test_conversation.py`: deleted the four listed legacy tests; remaining four use a mocked graph; removed unused
   fakes (`reply`, `insert_message`, `self.ai`, `self.events`, `self.prompt`) and imports. `record` now takes only the connection.
7. `unit/test_langgraph_chat.py`: added the `completed_history` assertion (basic conversation test) and
   `consume_ai_question.assert_not_awaited()` (`test_init_with_unconfigured_ai_settings`).

## Behaviour coverage

| Behaviour | Test |
|---|---|
| Registry dispatch | `test_tools.py::test_registry_registration_and_dispatch` (existing) |
| UNAVAILABLE gives [] / None and logs the code only | the two `..._rpc_errors_...` tests |
| NOT_FOUND order status gives None | `test_order_status_not_found_returns_none` |
| DB failure gives generic 503 | `test_internal_members.py::test_storage_failures_become_generic_503` (existing) |
| Store rolls back and propagates | `test_store_runs_the_upsert_inside_one_database_transaction` |
| History query arguments | `test_basic_conversation_invokes_graph_and_commits_two_transactions` |
| Duplicate, commands, invalid input, assistant write failure | `test_conversation.py` (4 tests) |
| Unconfigured AI consumes no quota | `test_init_with_unconfigured_ai_settings` |

## Expected RED

Only `test_init_with_unconfigured_ai_settings`: `consume_ai_question` is awaited once, because the current code treats
`ai` without a graph as the legacy path and consumes a question before replying. All other changes should pass now.

## Open questions

- Resolved (orchestrator correction): the coverage lost with the deleted legacy test (`consume_ai_question` receiving
  `user_id=""` when `java_user_id` is None) is restored in
  `test_basic_conversation_invokes_graph_and_commits_two_transactions`, awaited once with the tenant id and `user_id=""`.
  It should pass now.
- Pre-existing unused import `cashier_service_pb2` in `test_grpc_tools.py` left as is.
