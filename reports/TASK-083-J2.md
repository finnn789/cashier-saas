# TASK-083 J2 report: per-outlet lock and tenant time zone rule

Status: implemented, compile-correct by reading only. Maven, tests and the app were not run (policy).

## Changed files (all under src/main/java/org/lite/project/cashiersaas/)
- repository/FnbOrderRepository.java: added `lockOutlet(UUID tenantId, UUID outletId)` (tenant-scoped lookup, `ResourceNotFoundException` if missing, `refresh(..., PESSIMISTIC_WRITE)`, same style as `lockTenant`); added `countActiveOutlets(UUID tenantId)` (JPQL count, active = true); `lockTenant` kept; import `OutletEntity`.
- service/FnbDiscountService.java:36, FnbOrderCancellationService.java:32, FnbPaymentLabelService.java:36 and :51, FnbPaymentService.java:53, FnbPosOrderService.java:50 and :81, FnbRefundService.java:45, FnbShiftService.java:67, :89, :97, :183 (+ one comment text at ~:143): `lockTenant` replaced by `lockOutlet`.
- service/MerchantBusinessSettingsService.java: M6 rule; still calls `lockTenant` (line 44).
- exception/GlobalExceptionHandler.java: NOT changed (see item 3).

## Call sites and which outlet id is used
All use the `outletId` method parameter that is the request's path/context outlet (same value later passed to `guard.requireOutlet/requireOwner`); none was restructured, the lock order (lock first, then guard) is unchanged except in FnbPosOrderService which already calls the guard before and after the lock.
- FnbDiscountService.grant:36, FnbOrderCancellationService.cancel:32, FnbPaymentLabelService.create:36 / update:51, FnbPaymentService.pay:53, FnbRefundService.refund:45, FnbShiftService.open:67 / closeCurrent:89 / forceClose:97 / recordExpense:183: `outletId` parameter. It is not yet validated when locked (lock precedes `guard`), but `lockOutlet` is tenant-scoped from the principal's tenant, so a foreign or unknown outlet gives ResourceNotFoundException (404, no evidence another tenant's outlet exists). A same-tenant outlet the user cannot access is locked briefly, then the guard rejects (403/404 as before).
- FnbPosOrderService.checkout:50 and replaceItems:81: `outletId` parameter, already validated by the preceding `guard.requireOutlet` call at lines 47 / 79 (guard re-run after lock as before). Tenant id comes from `context.getTenant().getId()` / `tenantId` as before.
- Other `lockTenant` callers: grep of src/ (including grpc, all .java) shows only MerchantBusinessSettingsService:44 remains. No grpc caller.

## M6 rule
`MerchantBusinessSettingsService.update` passes `orders.countActiveOutlets(tenant.getId()) == 1 ? values.timeZone() : tenant.getTimeZone()` as the tenant time zone to `TenantEntity.updateBusinessSettings`; the outlet zone always changes via `OutletEntity.updateBusinessSettings`. No existing count query existed (OutletRepository only has findActive/persist and is J1's), so `countActiveOutlets` lives in FnbOrderRepository (reserved). The audit "after" JSON uses `outlet.getTimeZone()` so it already reflects the outlet zone.

## Item 3: duplicate idempotency key
Current behaviour already is 409, not 500. `GlobalExceptionHandler.handleDatabaseConflict` maps Spring `DataIntegrityViolationException` to 409 "Data sudah berubah atau terjadi duplikasi." The unique-constraint violations arrive as that type: `orders.flush()`/`persist` run inside `@Repository` classes (exception translation), and any commit-time failure is translated by `JpaTransactionManager`. So no change was made. Residual risk: it is the generic message, not a dedicated idempotency message; and a raw Hibernate exception escaping a non-`@Repository` path would hit the 500 fallback (none found in these flows).

## Deviations
- `countActiveOutlets` added to FnbOrderRepository (not OutletRepository) because of the reservation.
- GlobalExceptionHandler untouched per instruction ("only if it maps to 500").

## Tests that mock lockTenant (not edited; need updating by T1)
- src/test/.../unit/service/FnbPaymentLabelServiceTest.java:89, :141 -> lockOutlet(tenantId, outletId)
- src/test/.../unit/service/FnbRefundServiceTest.java:140, :174
- src/test/.../unit/service/FnbPosOrderServiceTest.java:114
- src/test/.../unit/service/FnbShiftServiceTest.java:142, :228, :285 (`never().lockTenant(any())` -> never lockOutlet), :335, :371
- src/test/.../unit/service/MerchantBusinessSettingsServiceTest.java:95, :139: these should STAY lockTenant; but the mocked `orders.countActiveOutlets` returns 0 by default (Mockito), so the tenant time zone would not change: stub `countActiveOutlets(tenantId)` to 1 in the single-outlet case.

## Recommended tests
- Per-outlet lock: each service calls `lockOutlet(tenantId, outletId)` before the guard (inOrder), once per method.
- Settings: with 1 active outlet tenant zone follows the request; with 2 active outlets only the outlet zone changes and the tenant zone is kept; lockTenant still called.
- IT: two outlets can transact concurrently; cross-outlet duplicate idempotency key returns 409.
- `lockOutlet` unknown or other-tenant outlet -> 404.

## Risks
- J1's outlet create/deactivate should itself guard the active-outlet count against a concurrent settings update (settings holds only the tenant lock; outlet create does not). Low impact: only the billing zone could be updated at the moment a second outlet is added.
- Idempotency keys are still unique per tenant; cross-outlet same-key retries are no longer serialized by the lock, so the DB constraint decides (409).
