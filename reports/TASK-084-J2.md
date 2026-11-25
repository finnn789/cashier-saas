# TASK-084 J2 report: gRPC GetLeakageReport

Status: DONE (code written, not compiled). Baseline commit: `20faa7a`. Test status: NOT RUN (policy).

## Changed files (all modified, none new)
- `src/main/proto/cashier_service.proto`: appended `rpc GetLeakageReport` and messages `GetLeakageReportRequest`, `LeakageLine`, `OutletLeakage`, `GetLeakageReportResponse` exactly per the packet contract.
- `grpc/service/CashierInsightGrpcHandler.java`: new `getLeakageReport`, reuses the shared `handle(...)` flow (tenant check, `requireOwnerOutlets`, `requirePremium`, per-outlet `findOutlet` with OWNER role). New dependency `FnbLeakageService` (field added after `insightService`).
- `grpc/service/CashierGrpcService.java`: override delegating to the handler.
- `grpc/mapper/GrpcInsightMapper.java`: `toLeakage(FnbOutletLeakageResponse)`; decimals via `toPlainString()`, `""` when null (private `optionalDecimal`, distinct from the existing `money()` which maps null to "0").

## Contract points
- Owner-only, outlet-scoped, Premium-gated exactly like `GetAnomalies` (it is the same `handle` path).
- `from`/`to` optional per field: parse failure -> INVALID_ARGUMENT; missing `to` = today in tenant zone, missing `from` = `to - 29 days`; `to < from` or span >= 92 days (92 days inclusive max) -> INVALID_ARGUMENT.
- Calls `FnbLeakageService.outletsLeakage(tenantId, outletIds, fromInstant, toInstant, 10)`. `outletIds` are the outlets the owner actually has access to (from the contexts built in `handle`).
- Mapper reads: `FnbOutletLeakageResponse` getOutletId/OutletName/StockCountCount/TotalLoss/FlaggedCount/Lines; `FnbLeakageLineResponse` getName/Unit/TheoreticalUsage/Shortage/VariancePct/LossAmount/Waste.

## Deviations / decisions
- Time zone: the service signature takes one Instant range for all outlets, so dates are converted in the TENANT time zone (`TenantEntity.getTimeZone()` of the first context), not each outlet's zone. Packet D7 says outlet zone per outlet for the consolidated REST report; the gRPC contract cannot express that.
- `toInstant` = `to.plusDays(1).atStartOfDay(zone)` (exclusive next-day midnight), so J1 should treat the upper bound as inclusive-or-exclusive harmlessly (only a count exactly at midnight differs).
- Empty `contexts` (cannot happen after `requireOwnerOutlets` unless access vanished between queries) returns an empty response without calling the service.
- Mapper assumes `getStockCountCount()` and `getFlaggedCount()` are `int`/`Integer` (proto fields are int32). If J1 declares `long`, use `Math.toIntExact`.

## Existing tests that need updating (not edited, not reserved)
- `src/test/java/org/lite/project/cashiersaas/unit/grpc/CashierInsightGrpcHandlerTest.java` line 85: constructor is now `new CashierInsightGrpcHandler(policy, accessRepository, insightService, leakageService, mapper)`; needs a `@Mock FnbLeakageService`.
- Recommended new cases (extend existing class): invalid date, range > 92, default window, cashier denied.

## Not done / other files touched by contract
- `docs/contracts/cashier-service.grpc.schema.json` and any copy of the proto under `Ai-services` are not updated (not reserved; P1 regenerates stubs).

## Open questions
- None blocking.
