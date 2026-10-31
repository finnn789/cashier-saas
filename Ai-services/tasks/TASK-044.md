# TASK-044 — gRPC Infrastructure and app/tools Execution Pipeline

## Overview

Establish the asynchronous gRPC client infrastructure within `Ai-services` so domain tools under `app/tools/` can execute remote procedure calls (RPC) to backend services (such as Spring Boot order/catalog endpoints) in a secure, tenant-isolated, and non-blocking manner.

---

## Reserved Worker Writes

- `pyproject.toml` (add `grpcio`, `grpcio-tools`, `protobuf` dependencies)
- `proto/` (new directory for `.proto` contract schemas)
- `proto/cashier_service.proto` (initial domain contract for catalog & order inquiry)
- `app/integrations/grpc/` (new subpackage for gRPC client infrastructure)
- `app/integrations/grpc/__init__.py`
- `app/integrations/grpc/config.py` (dataclass `GrpcSettings` with host, port, timeout, TLS)
- `app/integrations/grpc/client.py` (async `grpc.aio` channel & stub lifecycle manager)
- `app/integrations/grpc/generated/` (compilation output directory with `__init__.py`)
- `app/config.py` (incorporate `GrpcSettings` into root `Settings`)
- `app/tools/catalog.py` (wire gRPC client execution with graceful offline fallback)
- `app/tools/order.py` (new order status tool executing gRPC call)
- `app/tools/__init__.py` (register new gRPC-backed tool into `get_default_tool_registry`)
- `README.md` (add gRPC setup, proto compilation command, and config docs)
- `reports/TASK-044.md` (worker progress, diff, AST checks, and verification evidence)

No writes permitted to `main.py`, `.env` (use `.env.example`), SQL migrations, or Spring Java backend files unless explicitly reserved. Concurrent user edits must be preserved.

---

## Architecture Brief

### 1. Protobuf Contracts (`proto/cashier_service.proto`)
- Define service `CashierService` with initial read-only methods:
  - `GetCatalogItem(GetCatalogItemRequest) returns (GetCatalogItemResponse)`
  - `GetOrderStatus(GetOrderStatusRequest) returns (GetOrderStatusResponse)`
- Invariant: Requests MUST include `tenant_id` and query/order parameters. Responses return bounded domain attributes (item name, price, stock status, order status, total) without exposing internal sensitive IDs, tokens, or raw payment details.

### 2. Async gRPC Channel & Client (`app/integrations/grpc/client.py`)
- Use `grpc.aio` (asyncio-native gRPC).
- Channel lifecycle:
  - Managed via FastAPI lifespan in `app/application.py` or lazy async singleton with graceful `channel.close()`.
  - Include interceptors or default metadata injection for `x-tenant-id`.
  - Enforce bounded timeouts (`deadline`) on all RPC calls (default: 5.0 seconds).
- Error Handling:
  - Catch `grpc.aio.AioRpcError` (e.g., `StatusCode.UNAVAILABLE`, `StatusCode.DEADLINE_EXCEEDED`, `StatusCode.NOT_FOUND`).
  - Translate gRPC errors into clean application domain exceptions or safe tool fallback dictionaries. Never crash the chat loop or leak connection strings/secrets to the LLM or chat output.

### 3. Tool Execution Pipeline (`app/tools/`)
- Tools inherit from `BaseTool`.
- `execute(self, *, tenant_id: str, **kwargs)` invokes the gRPC client method using the provided `tenant_id`.
- If the gRPC backend is offline/unreachable, tool returns a graceful fallback message (e.g. `{"status": "unavailable", "message": "Layanan backend kasir sedang tidak dapat dihubungi."}`) so the AI agent can communicate politely to the user instead of throwing an unhandled exception.

---

## Acceptance Criteria

1. **Dependency & Protobuf Compilation**:
   - `grpcio`, `grpcio-tools`, and `protobuf` cleanly resolved in `pyproject.toml`.
   - Proto compilation command documented and executable via `uv run python -m grpc_tools.protoc ...`.
2. **Channel & Connection Safety**:
   - Asynchronous channel does not block the FastAPI/aiogram event loop.
   - Resource cleanup (closing channel) is handled cleanly on shutdown.
3. **Tenant Boundary Enforcement**:
   - All gRPC requests pass `tenant_id` derived from verified context, never inferred from user prompt text.
4. **Tool Integration**:
   - `CatalogSearchTool` and `OrderStatusTool` can invoke gRPC methods and return LLM-digestible structured dictionaries.
   - Graceful error handling verified for backend timeout and offline states.
5. **No Test/Runtime Violations**:
   - Static AST and type-hint validation pass.
   - Testing remains NOT RUN by policy until explicit user request.
