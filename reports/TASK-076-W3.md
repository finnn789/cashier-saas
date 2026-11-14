# TASK-076 W3 report (gRPC money strings + user/outlet scoping, C6 Java side + C7)

Not run: Maven, tests, app (per rules). Java compiles only against W4's proto (setPriceAmount, setTotal, getUserId).

## Changed files
- `src/main/java/org/lite/project/cashiersaas/repository/MembershipOutletAccessRepository.java`: new `findActiveOutletIds(UUID tenantId, UUID userId)` (line ~63). Joins access->membership->outlet; requires membership.tenant = tenant, membership.user = user, membership.active, membership.user.status = ACTIVE, outlet.tenant = tenant, outlet.active. Same conditions as `MerchantAccessRepository.findMember/findOutlet` (membership.active + user.status ACTIVE + outlet.active). Does NOT re-check tenant/subscription (handler already calls `requireAllowedTenant` first).
- `grpc/policy/GrpcTenantAccessPolicy.java`: constructor now `(TenantRepository, MembershipOutletAccessRepository, GrpcSecurityProperties)`; new `requireMemberOutlets(UUID tenantId, String rawUserId)` returns `List<UUID>`. Blank -> INVALID_ARGUMENT "user_id must not be blank"; non-UUID -> INVALID_ARGUMENT "user_id must be a valid UUID"; empty outlet list (unknown/inactive user, no/disabled membership, no active outlet access) -> PERMISSION_DENIED with the unchanged `TENANT_NOT_PERMITTED_MESSAGE`.
- `grpc/service/CashierCatalogGrpcHandler.java`: calls `requireMemberOutlets` after `requireAllowedTenant`, adds `.where("p.outlet.id").in(outletIds)`.
- `grpc/service/CashierOrderGrpcHandler.java`: calls `requireMemberOutlets` right after `requireAllowedTenant` (before order_number validation), adds `.where(ORDER.outlet.id.toString()).in(outletIds)`; not found remains NOT_FOUND "Order not found" (also for other-outlet orders).
- `grpc/mapper/GrpcCatalogMapper.java`: `setPriceAmount(price.setScale(2, HALF_UP).toPlainString())`; no `longValue()`. Null price leaves the field empty.
- `grpc/mapper/GrpcOrderMapper.java`: `setTotal(total.setScale(2, HALF_UP).toPlainString())` (null total -> "0.00"); no `longValue()`.
- `docs/contracts/cashier-service.grpc.schema.json`: `user_id` required on both requests, `price_amount`/`total` as `money` string (pattern `^-?[0-9]+[.][0-9]{2}$`), `int64` def removed, outlet-scoping text, examples updated. Valid JSON (checked with python json).
- `docs/contracts/cashier-service.grpc.test-messages.json`: `user_id` added to every message, new placeholders `<USER_ID>`, `<OUTSIDER_USER_ID>`, `<OTHER_OUTLET_ORDER_NUMBER>`, new cases (missing/invalid user_id, outsider user, unknown user, other-outlet order). Valid JSON.

## Deviations / notes
- `requireMemberOutlets` does not re-check the tenant (callers must call `requireAllowedTenant` first, as both handlers do); any role (OWNERMERCHANT or CASHIER) with an active membership + outlet access is accepted, per C7 wording.
- RoundingMode.HALF_UP is a no-op for DB numeric(.,2) values.
- No `grep longValue` hits left in `grpc/`.

## Existing tests that need updating (W5), all under `src/test/java/org/lite/project/cashiersaas/`
- `unit/grpc/GrpcTenantAccessPolicyTest.java` line 54: `new GrpcTenantAccessPolicy(tenantRepository, properties)` needs the new `MembershipOutletAccessRepository` argument (mock). Add tests for `requireMemberOutlets` (blank, invalid UUID, empty list -> PERMISSION_DENIED with identical message, non-empty list returned).
- `unit/grpc/CashierGrpcHandlerRejectionTest.java` line 69: same constructor change; requests now need `user_id`; add rejection cases for missing/invalid user_id and unauthorized user.
- `integration/grpc/GrpcSecurityIntegrationTest.java`: requests (catalog/order) need `.setUserId(...)` with a seeded ACTIVE staff user + active membership + outlet access; line 173 `item.getPrice()` -> `item.getPriceAmount()` ("18000.00"); lines 340, 346 `getTotalAmount()` -> `getTotal()` ("75000.00", "25000.00"). Add cases: user of another tenant/outsider -> PERMISSION_DENIED, deactivated membership -> PERMISSION_DENIED, order in an outlet the user cannot access -> NOT_FOUND, catalog limited to the user's outlets.
- `unit/service/FnbCatalogRecipeServiceTest.java`: matched only on unrelated REST DTO price getters, no change expected.
