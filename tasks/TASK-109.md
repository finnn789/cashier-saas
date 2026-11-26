# TASK-109: remove redundant tests, dead legacy chat path, duplicated helpers

Status: DONE, REVIEWED, TESTS PASS, NOT COMMITTED (RED: only the unconfigured-quota test failed; J1 correction:
`registerStoresTheNormalizedPhoneNumber` was restored, it alone checks name trim + email normalization). Memory version: 103. Branch `feature/leakage-report`. Baseline commit: `ded6050`
(dirty: TASK-105..108 work; none of the files reserved below was modified before this task).

## User instruction (2026-11-25)
Delete the redundant code found by the test duplication audit, following the audit's suggestions.
Frontend repo is out of scope.

## Global rules (every worker)
- This is a deletion/refactor task. Deleting or merging exactly the tests listed here is authorized.
- Edit only your reserved files + your report. Keep behaviour coverage; remove imports/helpers/fields that become unused.
- No new test files, base classes or utility classes. Do not touch anything not listed. Do not run tests.
- Line numbers are from the baseline.

## Wave 0 - tests (3 `test-worker`, parallel)

### T1 - Python tests. Report `Ai-services/reports/TASK-109-T1.md`
Reserved (under `Ai-services/tests/unit/`): `test_grpc_tools.py`, `test_grpc_client.py`,
`test_grpc_client_security.py`, `test_internal_members.py`, `test_conversation.py`, `test_langgraph_chat.py`;
and `Ai-services/tests/integration/test_internal_members_api.py`.
1. `test_grpc_tools.py`: delete `test_dispatch_catalog_and_order_tools` (covered by `test_tools.py` registry dispatch).
2. `test_grpc_client_security.py` `GrpcClientSafeErrorTests`: add `grpc.StatusCode.UNAVAILABLE` to the codes
   looped by both tests; rename `SAFE_CODES` -> `RPC_ERROR_CODES` and the two tests from `..._auth_errors_...`
   to `..._rpc_errors_...`.
   `test_grpc_client.py` `test_rpc_error_fallback`: keep only the NOT_FOUND order-status case (that branch
   returns None without logging); rename to `test_order_status_not_found_returns_none`, drop the table loop.
3. `test_internal_members_api.py`: delete `test_database_failure_is_a_generic_503_and_rolls_back`.
   `test_internal_members.py` `test_store_runs_the_upsert_inside_one_database_transaction`: also prove rollback -
   the local `transaction()` fake records "rollback" and re-raises when the body raises; a second pass with
   `upsert_member` raising `RuntimeError` asserts the error propagates and events end `["begin", "rollback"]`.
4. Legacy chat path is being removed (`ChatService` will only use a LangGraph `graph`; with no graph it replies
   "AI belum dikonfigurasi..." and does NOT consume an AI quota question).
   - `test_conversation.py`: delete `test_user_commits_before_ai_and_assistant_commits_before_return`,
     `test_hard_ai_error_is_ephemeral_after_user_commit_and_never_retries`,
     `test_transient_retry_is_bounded_and_waits_outside_transaction`,
     `test_transient_exhaustion_never_stores_assistant`. Move every remaining test to a mocked graph:
     `ChatService(database, channels, graph=<Mock with ainvoke AsyncMock>, grpc_client=grpc)`, graph returns
     `{"final_response": "answer"}`; replace `ai.reply` assertions with `graph.ainvoke` ones. Keep what each
     remaining test proves (duplicate, commands, invalid/unsupported input, assistant persistence failure).
     Remove unused fakes/imports.
   - `test_langgraph_chat.py` `test_basic_conversation_invokes_graph_and_commits_two_transactions`: add an
     assertion that `completed_history` was awaited with (connection, conversation_id, user_id, chat id,
     id of the just inserted USER message).
   - `test_langgraph_chat.py` `test_init_with_unconfigured_ai_settings`: add
     `self.grpc.consume_ai_question.assert_not_awaited()`. This is the only test expected RED before Wave 1.

### J1 - Java unit tests. Report `reports/TASK-109-J1.md`
Reserved (under `src/test/java/org/lite/project/cashiersaas/unit/`): `service/FnbShiftServiceTest.java`,
`service/RegisterServiceTest.java`, `grpc/CashierInsightGrpcHandlerTest.java`.
1. `FnbShiftServiceTest`: delete `openingSuggestionIsZeroWithoutHistoryElseTheCountedCashOfTheLatestClosedShift`.
2. `RegisterServiceTest`: delete `registerStoresTheNormalizedPhoneNumber`,
   `registerRejectsAnInvalidPhoneNumberBeforeTouchingPersistence`, `registerRejectsAMissingPhoneNumber`
   (covered by `RegisterPhoneNumberIntegrationTest`). Keep the already-registered-email test and what it needs.
3. `CashierInsightGrpcHandlerTest`: merge `aRejectedCallerGetsPermissionDeniedBeforeAnyArgumentFeedback` (:345)
   into the parameterized `leakageAndMarginRpcsAreDeniedLikeAnomaliesForANonOwnerOrNonPremiumTenant` (:318):
   also call getSalesSummary with garbage dates in the non-owner case and assert PERMISSION_DENIED with the same
   description and `verifyNoInteractions(accessRepository)`; rename to reflect all three RPCs; delete :345.
   Note the existing `notPremium` branch stubs `requirePremium`; keep the sales assertion valid in both branches
   (check the handler source for which policy methods each RPC calls).

### J2 - Java integration tests. Report `reports/TASK-109-J2.md`
Reserved: everything under `src/test/java/org/lite/project/cashiersaas/integration/`.
1. `fnb/FnbPettyCashOrderIntegrationTest`: delete `expensesAreCountedAgainstTheDrawerWhenClosingTheShift`.
2. `fnb/FnbInventoryFlowIntegrationTest` `deactivatedIngredientLeavesListAndNewRecipesAndIsNoLongerDeducted`:
   drop the payment part (from `createOrder` to the end; non-deduction is covered by
   `FnbCostAndStockCountIntegrationTest.deactivatedIngredientIsNotDeductedAndAddsNoCost`); rename to
   `deactivatedIngredientLeavesListAndNewRecipes`.
3. Move duplicated private helpers into the EXISTING `support/ApiTestSupport` (public; static when they need no
   instance state) and delete the copies. Only these names: `count(String, Object...)`, `balance(String itemId)`,
   `assertStatus`, `orders(MerchantFixture)`, `money(MvcResult, String)`, `assertMoney` (both overloads),
   `saleMovements`, `assertLedgerMatchesBalance`, `readList`, `runInParallel`, and `product(owner)`/`productFor`.
   Merge a copy only when its behaviour is identical, including every constant it reads (compare `PRICE`,
   URL prefixes, assertion messages; a differing copy stays local). Do NOT move `setUpAll`/`cleanUp`.

## Wave 1 - GREEN: P1 (`python-worker`), after orchestrator confirms RED. Report `Ai-services/reports/TASK-109-P1.md`
Reserved: `Ai-services/app/chat/service.py`.
- In `ChatService.handle` remove the whole legacy branch (`elif self.ai is not None:` ... direct `ai.reply`,
  retries, tool dispatch). Without a graph: return `_finish_error(started, 'AI_NOT_CONFIGURED',
  'ai_not_configured')` and never call `consume_ai_question`. Remove the `ai_path` variable/'legacy' value and
  the imports that become unused. Keep the `ai` constructor parameter (settings source + existing check).
- Do not delete `build_prompt`, `ToolRegistry.dispatch` or `OpenRouterClient` fields (orchestrator will report them).

## Orchestrator (housekeeping)
- Delete Postman `Get-Tenant` request and the two legacy `postman/*.postman_collection.json` exports; point
  `.postman.json` at the YAML collection folders.

## Decisions (reversible)
- Keep `CashierGrpcHandlerRejectionTest`: every case asserts that no data lookup happens, which the integration test cannot.
- Keep `Get Receipt For Order List`: `List Paid Orders` depends on the variable it sets.
- Keep the `F&B Catalog Controller` collection, SQL-string migration/scope tests, replace-items tests.
- No shared `withId` unit utility: conflicts with the "no new test helpers" rule.

## Acceptance
- RED evidence (orchestrator): Python targeted modules -> only the unconfigured-quota assertion fails.
- GREEN evidence (orchestrator): targeted Python modules OK; Java `test-compile` 0; changed Java test classes pass.
