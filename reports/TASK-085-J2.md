# TASK-085 J2 report (gRPC GetMenuMargins)

Status: DONE (code written, not compiled). Baseline: commit 20faa7a + uncommitted TASK-084 tree.

## Changed files (all modified, none new)
- src/main/proto/cashier_service.proto: rpc GetMenuMargins and messages GetMenuMarginsRequest, MenuMargin, OutletMenuMargins, GetMenuMarginsResponse (exact contract, appended).
- src/main/java/org/lite/project/cashiersaas/grpc/service/CashierGrpcService.java: getMenuMargins delegates to the insight handler.
- src/main/java/org/lite/project/cashiersaas/grpc/service/CashierInsightGrpcHandler.java: getMenuMargins via the shared handle() path (owner, outlet scope, Premium), new constructor field FnbMenuMarginService (placed between leakageService and mapper), max_margin_pct validation.
- src/main/java/org/lite/project/cashiersaas/grpc/mapper/GrpcInsightMapper.java: toMenuMargins(FnbMenuMarginReportResponse, List<FnbMenuMarginResponse>); nulls map to "".
- src/test/java/org/lite/project/cashiersaas/unit/grpc/CashierInsightGrpcHandlerTest.java: constructor argument only (mock(FnbMenuMarginService.class)).

## Contract points
- Calls FnbMenuMarginService.outletMargins(tenantUuid, outletId) per owner outlet context.
- Empty max_margin_pct: only status BELOW_TARGET; otherwise menus with marginPct non-null and <= value. Max 30 menus per outlet after filtering (service order kept).
- Invalid max_margin_pct (not a number, <0, >100) gives INVALID_ARGUMENT; validated after the tenant/owner/Premium checks (same as other RPCs).

## Dependencies on J1 (compile-time, not present yet when I wrote this)
- FnbMenuMarginService (public outletMargins(UUID, UUID)), FnbMenuMarginReportResponse getters: getOutletId() (UUID), getOutletName(), getTargetMarginPct() (BigDecimal), getMenus(); FnbMenuMarginResponse getters: getName, getPrice, getHpp, getMarginPct, getStatus (String), getSuggestedPrice (BigDecimal). If J1 names differ, fix in handler and mapper.

## Deviations
None. Handler does the filter/limit; mapper stays a pure mapper.

## Existing tests that need updating
Only the constructor in CashierInsightGrpcHandlerTest (done). Recommended new tests (not written, per rule: extend the existing handler test class): owner gets outlets, cashier denied, invalid max_margin_pct INVALID_ARGUMENT.

## Open questions
None. Note: Ai-services/proto copy and regenerated stubs belong to P1 (wave 2); the dirty Python tree already has unrelated TASK-084 changes there.

Test status: NOT RUN (policy)
