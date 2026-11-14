# TASK-083 A1 report: risk fixes F1, F2, F3

Status: implemented, compile-checked by reading only (no Maven, no tests run, by policy). Baseline `97eafa2`. Paths under `src/main/java/org/lite/project/cashiersaas/`.

## Changed files
New: `exception/runtime/OutletOverAllowanceException.java`, `dto/response/OutletOverAllowanceResponse.java`.
Modified: `repository/MerchantAccessRepository.java`, `service/MerchantAccessGuard.java`, `service/PlanEntitlementService.java`, `service/OutletService.java`, `service/FnbSalesReportService.java`, `exception/GlobalExceptionHandler.java`, `shared/response/ApiResponse.java` (added factory `error(int code, String message, T data)`; there was no way to return `data` on an error).
`LoginService` needs no edit: `selectMerchant` already calls `planEntitlementService.requireOutletUsable`, which now throws `OutletOverAllowanceException` (402 with data). `listChoices` unchanged. No order repository change (F3 reuses `FnbOrderRepository.countOpenUnpaid`).

## F1 final shape of `MerchantAccessRepository.findOutlet`
One Blaze Tuple query (same joins/filters as before) with two extra scalar select subqueries correlated on `outlet`:
- `outletRank` = `select count(ranked.id) from OutletEntity ranked where ranked.tenant.id = outlet.tenant.id and ranked.active = true and (ranked.createdAt < outlet.createdAt or (ranked.createdAt = outlet.createdAt and ranked.id < outlet.id))`
- `extraOutlets` = `select sum(addOn.extraOutlets) from SubscriptionEntity addOn where addOn.tenant.id = outlet.tenant.id and addOn.planCode in ('PRO','PREMIUM') and addOn.status = ACTIVE and addOn.startsAt <= :now and (addOn.endsAt is null or addOn.endsAt > :now)` (null when no paid row)
- Java: `usable = outletRank < 1 + coalesce(extraOutlets, 0)`. `OutletAccess` now has a third field `boolean usable` (`isUsable()`). Same ordering/semantics as `PlanEntitlementService.usableOutletIds` (which stays for lists). The "no access" case still returns empty (404); the guard throws on `!usable`.
- Guard no longer calls `requireOutletUsable`. That method stays only for `LoginService.selectMerchant`. It now throws `OutletOverAllowanceException(usableIds)` (it extends `PlanUpgradeRequiredException`, so existing `assertThrows(PlanUpgradeRequiredException.class, ...)` still hold).

## F2
- `OutletOverAllowanceException extends PlanUpgradeRequiredException`, message `PlanEntitlementService.OUTLET_OVER_ALLOWANCE_MESSAGE`, holds `usableOutletIds`. The handler `handleOutletOverAllowance` is more specific, so plain plan 402s still use `handlePlanUpgradeRequired` unchanged.
- 402 body: `{"success":false,"code":402,"message":"Outlet ini melebihi kuota outlet paket Anda. Tambahkan add-on outlet (Rp49.000/outlet/bulan) atau nonaktifkan outlet lain.","data":{"reason":"OUTLET_OVER_ALLOWANCE","usableOutletIds":["<uuid>",...]},"timestamp":...}`.
- Guard: `requireOutlet` / `requireOwner` enforce allowance (throw with `usableOutletIds(tenant, now)` fetched only on the failure path). New `requireOwnerIgnoringAllowance(principal, outletId)` = same checks (token outlet match, membership, role OWNERMERCHANT -> else 404) minus the allowance. Shared private `resolve(principal, outletId, enforceAllowance)`.
- Routes using `requireOwnerIgnoringAllowance`: `OutletService` private `requireOwner` -> `GET/POST /api/v1/merchant/outlets`, `PUT /{outletId}`, `POST /{outletId}/deactivate`, `POST /{outletId}/reactivate`; and `GET /api/v1/merchant/reports/sales` (`FnbSalesReportService.consolidated`). Everything else keeps the allowance check, including `OutletService.listAccessible` (`GET /api/v1/cashier/outlets`) and the per-outlet `report`. Create/reactivate still call `requireOutletCapacity`.
- Caveat: an owner whose token outlet was deactivated still gets 404 (findOutlet requires `outlet.active`); unchanged by this task.

## F3
`OutletService.deactivate`, after the last-outlet and open-shift checks: `orders.countOpenUnpaid(tenantId, outletId)` (existing; `select count(o) from SalesOrderEntity o where o.tenant.id = :t and o.outlet.id = :o and o.status = OPEN and o.paymentStatus = UNPAID`). If > 0 -> `OperationConflictException` (409) "Outlet masih memiliki N pesanan terbuka. Selesaikan atau batalkan dulu." Runs under the existing tenant+outlet lock.

## Existing tests that need updating (not edited, per rule)
- `src/test/.../unit/service/MerchantAccessGuardTest.java:56, 102, 115`: `new MerchantAccessRepository.OutletAccess(membership, outlet)` -> add third arg `true` (`false` at 115). Test at 110-123 (`anOutletOverTheAllowanceIsRefusedAfterTheAccessCheck...`): drop the `doThrow(...requireOutletUsable...)` stub and the `verify(planEntitlementService).requireOutletUsable`; use `usable=false` + stub `planEntitlementService.usableOutletIds(...)`, assert `OutletOverAllowanceException`, and a new case that `requireOwnerIgnoringAllowance` passes with `usable=false`.
- `src/test/.../unit/grpc/CashierInsightGrpcHandlerTest.java:90`: `OutletAccess(ownerMembership, outlet)` -> 3 args (`true`).
- `src/test/.../unit/service/PlanEntitlementServiceTest.java` (no `requireOutletUsable` usage found; lines 96/122 assert `PlanUpgradeRequiredException`, still valid).
- `src/test/.../integration/identity/PlanEntitlementIntegrationTest.java:165-192` (over-allowance flow): still valid; can additionally assert the 402 body `data.reason` and `data.usableOutletIds`, and that the owner token on the over-allowance outlet can still `GET /api/v1/merchant/outlets` and deactivate.
- `src/test/.../integration/identity/MerchantOutletIntegrationTest.java:205-235`: valid unless an open unpaid order exists on the outlet being deactivated; add the F3 409 case there.
- `guard.requireOwner(...)` mocks in service tests (`FnbInventoryServiceTest`, `FnbCatalogRecipeServiceTest`, etc.) are unaffected (2-arg method kept). Any unit test of `OutletService` / `FnbSalesReportService` that mocks `guard.requireOwner` for the outlet-management or consolidated paths must switch to `requireOwnerIgnoringAllowance` (none found by grep of `new OutletService(` / `consolidated`; verify T1 tests).
- `selectMerchant` tests (`MerchantStaffIntegrationTest:108,118,216`, `PlanEntitlementIntegrationTest:97,141,158,181`): status codes unchanged.

## Risks (not verified by running)
- Blaze `selectSubquery` with `whereOr()/whereAnd()` and UUID `<` comparison in the correlated subquery, plus `sum(int)` returning `Long`/null mapped via `Number`: written to match Blaze API by reading, needs the first IT run to confirm. If Hibernate rejects the UUID `<`, fallback is ordering by `createdAt` only plus an id tiebreak in Java.
- `exception.runtime.OutletOverAllowanceException` imports the service constant (exception -> service dependency, single constant).
