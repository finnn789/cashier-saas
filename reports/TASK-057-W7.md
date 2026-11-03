# TASK-057 W7 Report: fix legacy gRPC client unit test for service key

## Scope
- Reserved write path: `Ai-services/tests/unit/test_grpc_client.py` only.
- No production code, no other tests, no git commit.

## Changes
1. `asyncSetUp`: `GrpcSettings(...)` now includes `service_key="test-service-key-0123456789"`.
2. `test_get_catalog_items_success`: added assertion that `kwargs["metadata"]` equals `(("x-internal-service-key", "test-service-key-0123456789"),)`. The existing `limit=5` assertion is unchanged; 5 is inside the 1..50 clamp range, so it is not altered.
3. `test_get_order_status_success`: same metadata assertion added.
4. No existing assertions were removed or weakened.

## Test run
Command: `uv run python -m unittest tests.unit.test_grpc_client tests.unit.test_grpc_tools tests.unit.test_grpc_client_security -v` (from `Ai-services`)

| Module | Ran | Result |
|---|---|---|
| tests.unit.test_grpc_client | 7 | OK (0 failures, 0 errors) |
| tests.unit.test_grpc_tools | 17 | OK |
| tests.unit.test_grpc_client_security | 17 | OK |
| **Total** | **41** | **OK (0 failures, 0 errors, 0 skipped)** |

Previously failing `test_get_catalog_items_success` and `test_get_order_status_success` now pass.
Log output includes expected warnings from the fallback tests (for example, "gRPC GetCatalogItem skipped: CASHIER_GRPC_SERVICE_KEY is not configured"); these are intended logs, not failures.

## Remaining risk
- None for this scope. Other modules (test_channel, test_conversation, test_ai, test_database, test_telegram_sender) were not run, per instructions.
