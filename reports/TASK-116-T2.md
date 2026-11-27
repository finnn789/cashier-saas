# TASK-116-T2 (Java tests, RED)

Test status: NOT RUN (orchestrator runs RED).

## Behaviours covered
- CashierInsightGrpcHandlerTest
  - bad/blank date, start > end, range 366 days apart -> INVALID_ARGUMENT, service/mapper untouched: `productPerformanceRejectsBadDatesReversedAndTooLongRangesAsInvalidArgument` (parameterized)
  - non-owner -> PERMISSION_DENIED before argument feedback: `productPerformanceIsDeniedForANonOwnerBeforeArgumentFeedback`
  - owner -> one outlet row, service called with limit 500, tenant id + mapped rows: `productPerformanceReturnsOneRowPerOwnedOutletReadingAtMost500ProductsPerOutlet`
- GrpcInsightMapperTest
  - `ProductTotal.product_id` in sales summary top products: existing `salesSummaryMapsEveryFieldWithMoneyAsPlainStringsAndDatesAsIso` extended
  - new RPC mapping (outlet, dates, products incl. product_id): `productPerformanceMapsTheOutletPeriodAndEveryProductIncludingItsId`

## Existing tests modified
- `salesSummaryMapsEveryFieldWithMoneyAsPlainStringsAndDatesAsIso` (sets and asserts product id); new field `productId` in the mapper test class.

## Skipped (already covered)
- Below-Premium FAILED_PRECONDITION: shared `handle(...)` path, covered by existing `salesLeakageAndMarginRpcsAreDeniedBeforeArgumentFeedbackForANonOwnerOrNonPremiumTenant` style; not repeated.
- Zone conversion / repository limit live in `FnbOwnerInsightService.productPerformance`; not in T2's reserved paths (handler test mocks the service).

## Open questions (signatures beyond the packet text; J1 must match or the orchestrator adjusts)
- `insightService.productPerformance(UUID tenantId, UUID outletId, LocalDate start, LocalDate end, int limit)` returning `List<FnbSalesProductResponse>` (packet signature; limit 500 asserted here).
- `mapper.toProductPerformance(MerchantAccessContext, LocalDate start, LocalDate end, List<FnbSalesProductResponse>)` returning `OutletProductPerformance` (not specified in the packet).
- `handler.getProductPerformance(GetProductPerformanceRequest, StreamObserver<GetProductPerformanceResponse>)`; handler constructor assumed unchanged.

## Expected RED reason
Compile errors: `GetProductPerformance*`, `OutletProductPerformance`, `ProductTotal.getProductId`, `getProductPerformance`, `productPerformance`, `toProductPerformance` do not exist yet.
