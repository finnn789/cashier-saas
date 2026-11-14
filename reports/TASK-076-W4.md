# TASK-076 W4 report (proto + Python AI)

Not run by policy: pytest, app. Only `compileall` on app/ and tests/ (OK).

## protoc command (run from Ai-services/, uses repo-root proto)
`uv run python -m grpc_tools.protoc -I../src/main/proto --python_out=app/integrations/grpc/generated --grpc_python_out=app/integrations/grpc/generated ../src/main/proto/cashier_service.proto`
(same flags as Ai-services/README.md:157 but proto dir = src/main/proto). Output kept the old gencode headers (protobuf 5.29.0, grpc 1.71.2). protoc dropped the hand-added relative-import `try/except` in `cashier_service_pb2_grpc.py`; I restored it so the diff of that file is empty.

## Changed files
- src/main/proto/cashier_service.proto (C6, exact text)
- Ai-services/app/integrations/grpc/generated/cashier_service_pb2.py (regenerated); _pb2_grpc.py unchanged after restore
- app/integrations/grpc/client.py: `user_id` required kwarg on get_catalog_items/get_order_status, sent in request; `price` = Decimal(item.price_amount), `total_amount` = Decimal(response.total) (invalid string -> existing generic except -> [] / None)
- app/chat/service.py: `Identity.java_user_id` (last field, default None; set from VerifiedMembership/MemberMatch.java_user_id in VERIFIED/LISTED; FALLBACK = None); `handle(java_user_id=None)` puts `java_user_id` in graph state and passes `user_id` to legacy `tools.dispatch`
- app/ai/graph/state.py: `java_user_id: str | None`
- app/ai/graph/tools_adapter.py: ContextVar `current_java_user_id`; schema hides `user_id` like `tenant_id`; `_async_exec` strips model `user_id` and injects it from the ContextVar
- app/ai/graph/nodes.py: sets/resets `current_java_user_id` next to `current_tenant_id`; strips model `user_id`; passes `user_id=java_user_id or None` to `tool.execute`
- app/ai/graph/__init__.py: export `current_java_user_id`
- app/tools/catalog.py, order.py: `user_id` kwarg; no user_id -> "not available" result, no gRPC call. Catalog `price` and order `total_amount` are `str` in tool output.
- app/tools/__init__.py: CpuUsageTool import/registration/export removed; app/tools/cpu.py deleted (file removed from working tree only, `git rm` not run)
- Tests: tests/unit/test_tools.py (rewritten, CPU tests gone), test_grpc_tools.py, test_grpc_client.py, test_grpc_client_security.py, test_langgraph_chat.py, test_tenant_scope.py, test_tenant_identity.py, test_telegram_intake.py, tests/integration/test_grpc_integration.py

## Deviations (files outside the literal C8 list, needed for the contract to work)
- app/tools/registry.py: `dispatch(..., user_id=None)` forwards `user_id` to `execute` and drops a model-supplied `user_id` (legacy non-graph path would otherwise always say "not available").
- app/channels/telegram/handlers.py:296: passes `java_user_id=identity.java_user_id` to `chat.handle` (single line).
- Tests under tests/integration/ also updated (they use the removed proto fields).

## Open questions / leftovers (not touched, outside reserved paths)
- `Ai-services/proto/cashier_service.proto` is a second tracked copy (README:157 regenerates from it). It is now STALE vs src/main/proto. Orchestrator should sync it (copy the file) or point the README at `../src/main/proto`.
- Ai-services/README.md:93, 206, 229 and GEMINI.md:12 still mention CpuUsageTool / check_laptop_cpu_usage; app/tools/README.md had no CPU mention.
- psutil: not declared in pyproject.toml/uv.lock and not imported anywhere (cpu.py used ctypes), so nothing to remove.
- `Identity.java_user_id` of an existing verified membership can be None (nullable column in tenant_memberships); such users get "not available" from both tools until they re-verify the contact.
