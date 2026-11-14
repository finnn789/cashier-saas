# TASK-077 W2 report (shift use cases, controller, payment wiring)

Nothing compiled or run (no Maven by rule). Base package `org.lite.project.cashiersaas` (paths under `src/main/java/org/lite/project/cashiersaas/`).

## Files
- NEW `service/FnbShiftService.java`, `controller/FnbShiftController.java`, `dto/FnbShiftMapper.java`
- NEW `dto/request/FnbShiftOpenRequest.java`, `dto/request/FnbShiftCloseRequest.java`
- NEW `dto/response/FnbShiftResponse.java`, `dto/response/FnbShiftMethodTotalResponse.java`, `dto/response/FnbShiftPageResponse.java` (page wrapper, see deviations)
- EDIT `service/FnbPaymentService.java` (field `shifts`, `pay` line 68, `settle(...)` now takes trailing `CashShiftEntity shift`, passed to `PaymentEntity.settle`)
- EDIT `service/MockFnbGatewayService.java` (field `shifts`, private `settle` line 108 requires the shift, passed to `PaymentEntity.settle`)

## Contract points for W3 (exact signatures, `FnbShiftService`)
- `public Optional<CashShiftEntity> findOpenShift(MerchantAccessContext context)` line 47: `FnbShiftRepository.openFor(tenantId, outletId, membershipId)` of the context member (own shift only).
- `public CashShiftEntity requireOpenShift(MerchantAccessContext context)` line 51: throws `OperationConflictException("Buka shift terlebih dahulu sebelum menerima pembayaran.")`.
- Neither takes the tenant lock; callers must already hold `orders.lockTenant` (pay, resolve and W3 refund do). Neither is `@Transactional` (join the caller transaction).

## Payment wiring
- `FnbPaymentService.pay`: shift required right before `settle`, i.e. AFTER the idempotent replay (keyedPayment) check, the key-conflict, pending attempt, already-paid, and amount validations (line 68). MOCK_GATEWAY `initiate` is unchanged (no money received yet).
- `MockFnbGatewayService.resolve`: shift required inside the private `settle` (only for SUCCEEDED, line 108). Terminal replay (`terminalReplay`) returns before it, so a replay after the shift is closed still returns the stored payment. If no shift, the 409 rolls back the whole transaction including `attempt.resolve`, so the attempt stays PENDING and can be resolved after opening a shift.
- The payment is attributed to the shift of the member who performs the pay/resolve (the member of the access context), not of the attempt creator.

## Routes (all `ApiResponse<...>`)
| Method + path | Role | Notes |
|---|---|---|
| POST `/api/v1/cashier/outlets/{outletId}/fnb/shifts` | OWNER+CASHIER | body `{openingCash}`; 201; 409 "Anda masih memiliki shift terbuka." (check under tenant lock + unique index backstop); audit SHIFT_OPENED `openingCash=<x>` |
| GET `/api/v1/cashier/outlets/{outletId}/fnb/shifts/current` | OWNER+CASHIER | caller's open shift with live summary; 404 when none |
| POST `/api/v1/cashier/outlets/{outletId}/fnb/shifts/current/close` | OWNER+CASHIER | body `{countedCash, note?}`; 404 when no open shift |
| GET `/api/v1/merchant/outlets/{outletId}/fnb/shifts?from=&to=&page=&size=` | OWNER | `from`/`to` ISO dates in the outlet zone (default today), max 366 days (same rule as the sales report), page 0..1000000, size 1..50 (default 20); openedAt desc; response `FnbShiftPageResponse {shifts, page, size, hasNext}` |
| GET `/api/v1/merchant/outlets/{outletId}/fnb/shifts/{shiftId}` | OWNER | 404 if not in tenant/outlet |
| POST `/api/v1/merchant/outlets/{outletId}/fnb/shifts/{shiftId}/close` | OWNER | force close any shift of the outlet, closedBy = owner; already closed -> 409 "Shift sudah ditutup." |

Close flow (`FnbShiftService.close`, line 135): `orders.lockTenant` (done by the callers) -> `shifts.lock(shift)` (refresh, sees concurrent close) -> `cashSummary` -> `expected = openingCash + cashIn - cashRefunds` -> `shift.close(...)` -> flush -> audit SHIFT_CLOSED `expected=<e> counted=<c> difference=<d>`.
`expectedCash` in responses is live while OPEN and the stored value once CLOSED.

## Deviations / notes
1. `openOrderCount`: `FnbOrderRepository` has no count query and is not reserved, so the service counts through `orders.page(tenant, outlet, 0, 100, OPEN, UNPAID, null).size()` (line 158). The value is therefore CAPPED at 101 (a shortcut comment is in the code). Need for exactness: `FnbOrderRepository.countOpenUnpaid(UUID tenantId, UUID outletId)`; then change only `FnbShiftService.openOrderCount`. The owner list computes it once per response and puts the same value in every row.
2. Extra DTO `FnbShiftPageResponse` (not in the reserved list) is needed for the owner list, same pattern as `FnbOrderPageResponse`. Nested method-total DTO is `FnbShiftMethodTotalResponse` (as allowed).
3. The owner list/detail run `cashSummary` (2 queries) per shift row (max 50 rows) - acceptable at this size.
4. Responses expose instants (ISO) as in other responses; no outlet-zone rendering.
5. `FnbShiftService.close` re-reads the open-order count after closing, so the close response carries the current value.

## Tests that will break (shift now required to pay; W4 must open a shift first)
- `src/test/.../integration/fnb/FnbPayFirstFlowIntegrationTest.java`, `FnbInventoryFlowIntegrationTest.java`, `FnbReusableOptionGroupIntegrationTest.java`, `FnbStockConcurrencyIntegrationTest.java`: every flow that POSTs `/payments` or resolves a mock payment as a cashier/owner needs `POST /cashier/outlets/{id}/fnb/shifts` first for the SAME user who pays. In the stock concurrency test, concurrent payers each need their own open shift (one open shift per member and outlet).
- `src/test/.../unit/service/MockFnbGatewayServiceTest.java`: `@InjectMocks` service now also needs a `@Mock FnbShiftService shifts` (it would otherwise be null -> NPE on SUCCEEDED resolve); stub `shifts.requireOpenShift(...)`.
- Any unit test constructing `FnbPaymentService` needs the new `FnbShiftService` mock (none found in `src/test` by grep).
- `PaymentEntity.settle` has no direct test callers.
