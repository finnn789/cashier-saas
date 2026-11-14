# TASK-080 J2 report — gRPC + proto + Python stubs

Status: implemented; nothing compiled or run (Maven/tests forbidden by rule). Only the protoc stub-generation command was executed.

## Changed files
- `src/main/proto/cashier_service.proto` and `Ai-services/proto/cashier_service.proto` — 3 RPCs + messages exactly per packet (existing messages untouched; files byte-identical).
- `Ai-services/app/integrations/grpc/generated/cashier_service_pb2.py`, `cashier_service_pb2_grpc.py` — regenerated with `uv run python -m grpc_tools.protoc -Iproto --python_out=... --grpc_python_out=... proto/cashier_service.proto`. The `_pb2_grpc` import was re-patched to the existing `try: from . import ... except ImportError: import ...` style. Import smoke test (`uv run python -c`) loaded the stubs OK.
- `grpc/policy/GrpcTenantAccessPolicy.java` — `requireOwnerOutlets(UUID, String)`; user-id parsing and outlet lookup extracted into private helpers shared with `requireMemberOutlets` (behaviour unchanged). Owner check = `TenantMembershipRepository.findActive(userId, tenantId)` role must be OWNERMERCHANT, otherwise the identical PERMISSION_DENIED.
- NEW `grpc/service/CashierInsightGrpcHandler.java` — three `@Transactional(readOnly = true)` RPC methods, shared private `handle(...)`: tenant check, owner check, then argument validation (so rejected callers get no argument feedback), then per-outlet `MerchantAccessContext` built from `MerchantAccessRepository.findOutlet(user, tenant, outlet, {OWNERMERCHANT}, now)` (outlets that fail the check are skipped). Date validation: ISO, end >= start, `DAYS.between >= 366` rejected (same rule as `FnbSalesReportService`), INVALID_ARGUMENT. Error handling as `CashierOrderGrpcHandler` (StatusRuntimeException passthrough; INTERNAL fixed message; short tenant ref in log).
- NEW `grpc/mapper/GrpcInsightMapper.java` — money via `toPlainString()`, dates ISO, nulls to "" / "0". Gross sales = `summary.subtotal`; labels from `paymentBreakdown` (method name, methodLabel, transactionCount, netSales); top products from `topProducts` (J1 supplies the limit 5).
- `grpc/service/CashierGrpcService.java` — delegates the three RPCs to the insight handler.

## Contract usage (J1 classes, read from the actual files)
`FnbOwnerInsightService.salesInsight(ctx, start, end)`, `restock(ctx)`, `anomalies(ctx, date)` (service class did not exist yet when I wrote; called exactly as named). `FnbOwnerSalesInsight.getReport()` (field name `report`), `getDiscountedOrderCount/ExpensesTotal/CashDifferenceTotal/ClosedShiftCount`; `FnbRestockSuggestion` getters and `stockStatus` as `InventoryBalanceEntity.Status` (`.name()`); `FnbCashierAnomaly` getters. If J1's service signatures or return types (`List<FnbRestockSuggestion>`, `List<FnbCashierAnomaly>`) differ, the handler will not compile.

## Notes for the orchestrator
- Rate limiting: already done by `GrpcServiceKeyInterceptor` for every RPC of the service, no extra code.
- BREAKING for tests: `GrpcTenantAccessPolicy` constructor now takes `TenantMembershipRepository` as its 3rd argument (`tenantRepository, outletAccessRepository, membershipRepository, properties`). Callers to update (T1): `src/test/.../unit/grpc/CashierGrpcHandlerRejectionTest.java:79` and `GrpcTenantAccessPolicyTest.java:58`. `CashierGrpcService` constructor also gained `CashierInsightGrpcHandler` (check any test that constructs it).
- Owner check uses `findActive(user, tenant)`, which returns one membership; assumes a user has at most one active membership per tenant (unique in the model).
- Outlets reachable by an owner: `findActiveOutletIds` (owners are expected to have outlet access rows; an owner without access rows gets PERMISSION_DENIED, same as the member policy).
- Subscription check (`FREETIER` active) is enforced inside `findOutlet`; a tenant without it yields an empty `outlets` list rather than an error.
- Not verified: compile, runtime behaviour, tests (policy: not run by rule).
