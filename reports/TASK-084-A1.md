# TASK-084 A1 report: fix test compile in CashierInsightGrpcHandlerTest

Status: DONE (pending orchestrator review)
Baseline commit: 20faa7a (branch feature/leakage-report)
Memory version: 90

## Changed files
- Modified: `src/test/java/org/lite/project/cashiersaas/unit/grpc/CashierInsightGrpcHandlerTest.java`
  - added import `org.lite.project.cashiersaas.service.FnbLeakageService`
  - added field `leakageService` and `leakageService = mock(FnbLeakageService.class)` in `setUp()`
  - constructor call now `new CashierInsightGrpcHandler(policy, accessRepository, insightService, leakageService, mapper)`
- New: `reports/TASK-084-A1.md` (this file)

## Contract points
- Constructor argument order matches `CashierInsightGrpcHandler` (`@RequiredArgsConstructor` field order: policy, accessRepository, insightService, leakageService, mapper). Verified in source.
- No other `new CashierInsightGrpcHandler(` call exists under `src/`.
- No new tests added, no assertions changed, no production code touched.

## Deviations
- Packet said `@Mock FnbLeakageService`. The test class does not use `MockitoExtension`; it builds all mocks with `mock(...)` in `setUp()`. I followed the existing style rather than adding a Mockito extension annotation or a new mechanism.

## Open questions
- None.

## Test status
NOT RUN (policy). Compile was not verified by a shell; the change is a mechanical constructor/field addition that matches the handler source.
