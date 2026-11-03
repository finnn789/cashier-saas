# TASK-057 W6 Test Run Report

Worker: W6. Scope: run tests and report raw results. No source or test files were edited, and nothing was committed.

## Environment

- Docker: `docker info` reported the client (29.4.3) but the server was unreachable. The pipe `//./pipe/dockerDesktopLinuxEngine` does not exist, so the Docker Desktop engine is not running.
- Consequence: Testcontainers (postgres:17) cannot start. All integration tests (failsafe phase) are NOT RUN.
- `docker ps` was not usable (daemon down), so no leftover containers could be verified. No containers were started by this worker.

## 1. Maven unit phase

Command: `.\mvnw.cmd -B test` in `E:\Java\cashier-saas`. Only the surefire unit phase was run; `verify` was not run because Docker is down (integration/failsafe NOT RUN).

Result: **BUILD SUCCESS**, exit 0. Log: `C:\Users\arips\AppData\Local\Temp\claude\E--Java-cashier-saas\f2aea72e-21dc-4bb9-b4df-874ad0ebe8b9\scratchpad\mvn-test.log`

Totals: Tests run 286, Failures 0, Errors 0, Skipped 0. Maven total time 20.452 s.

Requested classes:

| Class | Run | Fail | Error | Skip |
|---|---|---|---|---|
| unit.grpc.GrpcServiceKeyInterceptorTest | 12 | 0 | 0 | 0 |
| unit.grpc.GrpcRateLimiterTest | 8 | 0 | 0 | 0 |
| unit.grpc.GrpcTenantAccessPolicyTest | 42 | 0 | 0 | 0 |
| unit.grpc.GrpcSecurityPropertiesValidationTest | 10 | 0 | 0 | 0 |
| unit.grpc.CashierGrpcHandlerRejectionTest | 14 | 0 | 0 | 0 |

Integration (NOT RUN, Docker unavailable): `integration/grpc/GrpcSecurityIntegrationTest`, `FnbPayFirstFlowIntegrationTest`, `SecurityAuthorizationIntegrationTest`, `CashierSaasApplicationTests`. Context startup for these was therefore not verified.

## 2. Python unit tests (Ai-services)

- `uv run pytest tests/unit -q`: FAILED to start. `pytest` is not installed in the project venv (`uv: Failed to spawn: pytest`).
- `uv run python -m pytest tests/unit -q`: FAILED. `No module named pytest`.
- Fallback `uv run python -m unittest discover -s tests/unit -v`, run on the full suite.

Result: **Ran 136 tests in 2.993 s, FAILED (failures=5, errors=5)**, exit 1. Log: `...\scratchpad\unittest.log`

Failures and errors:

| Module | Test | Kind | Message excerpt |
|---|---|---|---|
| test_channel | test_startup_binds_registered_channel_to_chat_service | ERROR | `RuntimeError: Startup Telegram/database gagal; periksa konfigurasi dan konektivitas.` |
| test_channel | test_supervised_db_failure_stops_poller_without_logging_details | ERROR | (error during test; see log) |
| test_conversation | test_assistant_persistence_failure_propagates_after_user_commit | ERROR | `TypeError: ConversationTests.reply() got an unexpected keyword argument 'tools'` |
| test_conversation | test_transient_retry_is_bounded_and_waits_outside_transaction | ERROR | `TypeError: ...retry_reply() got an unexpected keyword argument ...` (test helper signature mismatch) |
| test_conversation | test_user_commits_before_ai_and_assistant_commits_before_return | ERROR | `TypeError: ConversationTests.reply() got an unexpected keyword argument 'tools'` |
| test_ai | test_maximum_emoji_profile_and_current_keep_complete_newest_pairs | FAIL | `AssertionError: Lists differ: [] != [{'role': 'user', 'content': 'recent'}, ...]` |
| test_database | test_old_telegram_revision_is_rejected | FAIL | `AssertionError: True is not false` |
| test_grpc_client | test_get_catalog_items_success | FAIL | `Expected GetCatalogItem to have been awaited once. Awaited 0 times.` |
| test_grpc_client | test_get_order_status_success | FAIL | `Expected GetOrderStatus to have been awaited once. Awaited 0 times.` |
| test_telegram_sender | test_fatal_api_errors_are_safe_and_terminal | FAIL | `Expected send_message to have been awaited once. Awaited 2 times.` |

Log extract for the `test_conversation` ERROR items: the `TypeError` is raised by the test double's `reply()` signature, which does not accept `tools`. Those tests exercise the tool-calling path, so the error is at the test-harness level, not in the production call site.

Requested files, run separately (`unittest discover -s tests/unit -p <file>`):

| File | Run | Result |
|---|---|---|
| tests/unit/test_grpc_client_security.py | 17 | OK (0 fail, 0 error) |
| tests/unit/test_grpc_tools.py | 17 | OK (0 fail, 0 error) |

The two `test_grpc_client` failures (`test_get_catalog_items_success`, `test_get_order_status_success`) are in the older `test_grpc_client.py` and are not part of `test_grpc_client_security.py` or `test_grpc_tools.py`. Note that `test_grpc_tools.py` also has the `GrpcClientTests` class name, but its tests pass in isolation.

Note: the failures above were not investigated further or fixed, per the W6 scope. Whether these are pre-existing or caused by the TASK-057 working-tree changes was not determined. The modified `Ai-services/app/integrations/grpc/client.py` and `config.py` are the likely area for the gRPC client failures and should be checked by the orchestrator.

## Not run

- Maven `verify` / failsafe integration tests: Docker Desktop engine not running.
- pytest proper: not installed in the project venv. Tests were run via unittest discovery instead. Adding pytest would require changing project dependencies, which was out of scope.

## Totals

| Suite | Run | Pass | Fail | Error | Skipped | Status |
|---|---|---|---|---|---|---|
| Maven unit (surefire) | 286 | 286 | 0 | 0 | 0 | BUILD SUCCESS |
| Maven integration (failsafe) | - | - | - | - | - | NOT RUN (Docker down) |
| Python unittest, full tests/unit | 136 | 121 | 5 | 5 | 0 | FAILED |
| Python test_grpc_client_security.py | 17 | 17 | 0 | 0 | 0 | OK |
| Python test_grpc_tools.py | 17 | 17 | 0 | 0 | 0 | OK |

Duration: Maven test phase about 20 s (plus wrapper startup); Python runs about 3 s plus about 12 s for the discovery attempt. Overall wall time was roughly 3 minutes.

## Summary

Java unit tests pass (286/286). Integration tests were not run because Docker is down. The Python suite has 10 failing or erroring tests, all listed above; the two requested gRPC security/tools modules pass.
