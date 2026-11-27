# TASK-116-J1 (Java GREEN)

Status: DONE (pending orchestrator run). Baseline: 545ac38 (dirty).

## Changed files (all modified)
- src/main/proto/cashier_service.proto: rpc GetProductPerformance, ProductTotal.product_id = 7, 3 new messages (verbatim contract).
- grpc/service/CashierInsightGrpcHandler.java: `getProductPerformance` (same validation as getSalesSummary, limit 500 via MAX_PERFORMANCE_PRODUCTS).
- grpc/service/CashierGrpcService.java: delegates the new RPC.
- grpc/mapper/GrpcInsightMapper.java: `toProductPerformance(...)`; shared private `productTotal(row)` now also fills product_id for sales summary top products.
- service/FnbOwnerInsightService.java: `productPerformance(tenantId, outletId, start, end, limit)`.

## Deviations / notes
- `productPerformance` takes only UUIDs, so the outlet zone is read via `MerchantReferenceRepository.outlet(outletId).getTimeZone()` (outlet ids come from already-authorised owner contexts; the repository query is tenant scoped). Two new constructor dependencies: `FnbSalesReportRepository`, `MerchantReferenceRepository`.
- `FnbOwnerInsightServiceTest` uses `@InjectMocks`; the new fields stay null there (no change needed, existing tests do not call the new method).
- `OwnerInsightIntegrationTest` constructs via Spring; both beans exist.

Test status: NOT RUN (orchestrator runs GREEN).
