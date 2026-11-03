# TASK-057 W2 report: Python gRPC client service-key auth

## Changed files
- `Ai-services/app/integrations/grpc/config.py`: `GrpcSettings.service_key: str = field(default="", repr=False)`; `load_grpc_settings()` reads `CASHIER_GRPC_SERVICE_KEY` (stripped).
- `Ai-services/app/integrations/grpc/client.py`: header `x-internal-service-key` sent via `metadata=` on both RPCs; fail closed when key empty (warning log, no channel creation, no RPC, returns `[]` / `None`); catalog limit clamped (<=0 or invalid -> 10, >50 -> 50, else kept); auth-ish errors logged as `code=<STATUS_NAME>` only via `_log_rpc_error`; NOT_FOUND on order status still returns `None` silently.
- `Ai-services/tests/unit/test_grpc_client_security.py`: new offline tests (mocked stub, no network).
- `Ai-services/reports/TASK-057-W2.md`: this file.

## Evidence
- `uv run python -m py_compile` on the three Python files: OK.
- Tests NOT run (per instruction).

## Notes / remaining work
- Existing tests in `tests/unit/test_grpc_tools.py` use key-less `GrpcSettings`; their assertions (`[]` / `None`) still hold under fail-closed behaviour (the offline-fallback and mocked-stub cases now short-circuit before the stub). Worker running tests should confirm.
- `app/integrations/grpc/__init__.py` unchanged (not required).
- Compose/env wiring for `CASHIER_GRPC_SERVICE_KEY` in ai-services is W3's scope.
- Constants exported from client.py: `SERVICE_KEY_HEADER`, `DEFAULT_CATALOG_LIMIT`, `MAX_CATALOG_LIMIT`.
