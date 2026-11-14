# TASK-083 T1 report: Java tests (phase 1 + phase 2)

Status: written, NOT compiled or run (no Maven by policy). Only `src/test/**` and this report were touched. Baseline `f9ed23d`.

## 1. Existing tests fixed
- `FnbPaymentLabelServiceTest`, `FnbRefundServiceTest`, `FnbPosOrderServiceTest`, `FnbShiftServiceTest`: `lockTenant(tenantId)` -> `lockOutlet(tenantId, outletId)` (incl. `never().lockOutlet(any(), any())`).
- `MerchantBusinessSettingsServiceTest`: still `lockTenant`; `countActiveOutlets` stubbed to 1 in the test that asserts the tenant zone changes (and verified, because it ends with `verifyNoMoreInteractions(orders...)`).
- `MerchantAccessGuardTest`: `@InjectMocks` needs the new `PlanEntitlementService` mock (otherwise NPE on the happy path).
- `GrpcTenantAccessPolicyTest` (setUp stubs `usableOutletIds` -> `[OUTLET]`) and `CashierGrpcHandlerRejectionTest` (stubs `usableOutletIds` -> the user's outlet; added constant `USER_OUTLET`).
- `PlanEntitlementServiceTest.activeRows` now accepts `"PRO:2"` (add-on outlets) via the already existing reflection pattern.
- `GrpcSecurityIntegrationTest.seedTenant`: the seeded PREMIUM row now has `extra_outlets = 1`; without it tenant A1's second seeded outlet is over the allowance and `catalogIsLimitedToTheOutletsOfTheCaller` / `orderOfAnotherOutletIsNotFound...` would fail (USER_A1_OUTLET2 denied). Not a production bug, a fixture update.
- Checked: `FnbReusableOptionGroupIntegrationTest` inserts a 2nd outlet of a PREMIUM tenant by SQL, but only uses the owner's first (oldest) outlet afterwards -> unaffected. `FnbSalesReportServiceTest` uses `@InjectMocks` (constructor injection passes null for the new `MerchantAccessRepository`; `report` does not use it) -> unaffected.

## 2. New tests and why each is not a duplicate
Searched first: all of `src/test` for `outlet` management routes (`merchant/outlets"`), `allowedOutletCount`, `usableOutletIds`, `requireOutletUsable`, `countActiveOutlets`, `reports/sales`, `public/plans`, `OUTLET_` audit actions. None existed (no test class exists for `OutletService`/`MerchantOutletController`/`FnbConsolidatedSalesReportController`).

Unit (extended existing classes):
- `PlanEntitlementServiceTest.theOutletAllowanceIsOnePlusTheAddOnOutletsOfEveryActivePaidRow` (parameterized, 6 rows): `allowedOutletCount` did not exist before; no test on it.
- `PlanEntitlementServiceTest.creatingAnOutletBeyondTheAllowanceAnswers402NamingProOnFreetierAndTheAddOnOtherwise` (parameterized) + `anOutletWithinTheAllowanceHasCapacity`: `requireOutletCapacity` is new; existing `requireRefusesBelowTheMinimumPlan...` covers `require`, a different method/message.
- `MerchantAccessGuardTest.anOutletOverTheAllowanceIsRefusedAfterTheAccessCheckAndNeverForANonMember`: existing guard tests have no plan dependency; asserts 402 propagation and that a non-member still gets 404 (plan check only after access found). One test, no mock-asserts-mock.
- `MerchantBusinessSettingsServiceTest.timeZoneChangeLeavesTheBillingZoneAloneWhenTheTenantHasSeveralActiveOutlets`: the existing update test now covers the single-outlet branch only (count = 1); this is the other branch (count = 2). Different behaviour.
- `GrpcTenantAccessPolicyTest.outletsOverThePlanAllowanceAreDroppedFromMemberAndOwnerScopeAndAnOnlyOverAllowanceScopeIsDenied`: existing tests only return `[OUTLET]`, never filter; one test covers member + owner + only-over-allowance denial.

Integration, new class `integration/identity/MerchantOutletIntegrationTest` (no existing IT drives outlet management; reuses `ApiTestSupport`, `AbstractPostgresIntegrationTest`, `MerchantFixture`, only private one-line helpers inside the class: `pro`, `addOutlet`, `login`, `ownerEmail`, `consolidated`, `auditRows`, same style as `PlanEntitlementIntegrationTest`):
- `anOutletIsCreatedOnlyWithinThePlanAllowanceAndTheListReportsIt`: FREETIER 402 (Pro message), PRO `extra_outlets=0` 402 (add-on message), `=1` creates (201, active, usable), third 402; list shows allowed/active counts and `extraOutletPrice` 49000. Existing `PlanEntitlementIntegrationTest` only 402s PRO features, not outlets.
- `aNewOutletReachesTheOwnerWithDefaultPaymentLabelsAndIsAudited`: owner sees the new outlet in `/api/v1/auth/outlets` (2 choices), can select it, new outlet has the same number (>0) of default labels as the first, one `OUTLET_CREATED` audit row.
- `updateRejectsABadTimeZoneAndADuplicateNameAndAuditsOnlyRealChanges`: 400 bad zone, 409 duplicate name, real change audited once, identical re-update writes no row.
- `anOutletWithAnOpenShiftAndTheLastActiveOutletCannotBeDeactivated`: 409 with shift message, deactivating the other outlet works (+ audit), last active 409.
- `reactivationIsBoundByTheAllowanceAndAuditedWhenItSucceeds`: 402 add-on message over allowance, then 200 + `OUTLET_REACTIVATED` audit.
- `cashiersCannotManageOutletsAndAnotherTenantsOutletLooksUnknown`: cashier 403 (list, create), other tenant 404 on update/deactivate/reactivate, other tenant's list shows only its own outlet.
- `aSecondOutletTransactsWhileAnotherOutletRowIsLocked`: behavioural lock test without timing assumptions on success: another connection holds `FOR NO KEY UPDATE` on outlet 1's row (conflicts with `lockOutlet`'s `FOR UPDATE`, not with FK inserts); a payment in outlet 2 must finish within 30 s (a tenant-wide or cross-outlet lock would wait until the timeout and fail). Existing `FnbPayFirstFlowIntegrationTest.concurrentPaymentsWithDifferentKeysSettleExactlyOnce` is same-outlet.
- `aPaymentKeyAlreadyUsedInAnotherOutletOfTheTenantIsAConflictNotAServerError`: sequential, no timing: same payment idempotency key in outlet 1 then outlet 2 -> 409. Existing payment idempotency test is same-outlet.
- `theTenantTimeZoneFollowsTheOutletOnlyWhileItIsTheSingleActiveOutlet`: real `countActiveOutlets` query against Postgres via `PUT .../business-settings` (tenant zone changes while 1 active outlet, stays with 2). Unit tests mock the count; this is the only DB-level proof.
- `theConsolidatedReportSumsEachOutletInItsOwnTimeZoneAndSkipsUnpaidAndCancelledOrders` (phase 2): Jakarta + Makassar outlet; payments forced to `2026-03-10T16:30Z` (23:30 Jakarta, 00:30 next day Makassar). For 10..10 only the Jakarta outlet counts (1, 0, totals 1); for 10..11 totals 3; `totals.total` equals the sum of rows; each row equals that outlet's own per-outlet report; one OPEN unpaid and one cancelled order are not counted.
- `theConsolidatedReportDropsOutletsOverTheAllowanceAndIsOwnerOnlyAndTenantScoped` (phase 2): 2 outlets listed; after `extra_outlets=0` only the oldest remains; cashier 403; another tenant's owner sees only its own outlet.

`PlanEntitlementIntegrationTest.anOutletOverTheAllowanceAfterTheAddOnLapsesIsRefusedEverywhereWhileTheFirstOutletKeepsWorking` (extends the existing class and its helpers): paid row expires -> second outlet 402 on guard and select-merchant, omitted from choices, first outlet 200; renewed row restores the same token; deactivate allowed while over allowance. Not a duplicate: existing downgrade tests are about cashiers/PRO features, never outlets.

`SecurityAuthorizationIntegrationTest.publicPlanCatalogShowsTheOutletAddOnPriceOnPaidPlansOnly`: existing test only asserts 200/anonymous; this asserts add-on price 49000 on PRO and PREMIUM and absent on FREETIER.

gRPC owner outlet list excluding an over-allowance outlet is covered at unit level (policy test above); no new gRPC IT (the policy is the single place that filters, `CashierInsightGrpcHandler` goes through it).

Deliberately NOT added: unit tests that mock `EntityManager.createQuery` chains for `usableOutletIds`/`requireOutletUsable` (ranking is proven end to end by the ITs above); a FREETIER 30-day window test for the consolidated report (inherited rule, not in the T1 list); `GlobalExceptionHandler` tests (unchanged).

## 3. Production observations (no code changed)
1. `MerchantAccessGuard.requireOutlet` now runs an extra query on every guarded request (`activeOutletIds`), plus the subscription query when the tenant has 2+ active outlets. Correct but a cost on the hot path (J1 already noted it).
2. An owner whose token outlet falls over the allowance gets 402 on every owner route including outlet management (guard checks the token outlet); recovery needs a re-login/select-merchant into a usable outlet. Same for `consolidated` (`guard.requireOwner(principal, principal.getOutletId())`). My PlanEntitlement IT deliberately uses the first outlet's token for the deactivate step.
3. Cross-outlet duplicate payment key is a 409 only because `payments.byKey` is tenant-wide and `replayPayment` compares the outlet; the DB unique-constraint path (true concurrency) was not exercised (would need timing).
4. Tests assume outlets are ranked by `created_at`; two outlets inserted in the same transaction timestamp tie-break by random UUID (not relevant for the API path).

## 4. Risks for the orchestrator when running tests
- New/edited IT assertions depend on: `audit_logs.entity_id` uuid equality with a `UUID` parameter, `payments.paid_at` being updatable, default payment-label count > 0, JSON paths `$.data.id`, `$.data.outlets[*]`, `$.data.totals.*`, `$.data.outlets[i].summary.*`, `$.message` for 402/409.
- The lock test uses a real second connection from the Hikari pool; if the payment path ever locks outlet 1's row it fails by timeout (30 s), which is the intended signal.

## Phase 3 (amendment A1: F1-F3), tests only, not compiled or run
Fixed:
- `CashierInsightGrpcHandlerTest`: `OutletAccess(..., true)`.
- `MerchantAccessGuardTest`: `OutletAccess` gets the 3rd `usable` arg in all tests. The over-allowance test (the old one used `doThrow(requireOutletUsable)` and a verify that no longer apply, since the guard reads `usable` from `findOutlet`) was rewritten as `anOverAllowanceOutletIsRefusedWithTheUsableOutletsExceptForOwnerManagementAndNeverForANonMember`: `usable=false` -> `OutletOverAllowanceException` with `usableOutletIds` from the stubbed `usableOutletIds`, also for `requireOwner`; `requireOwnerIgnoringAllowance` passes; a non-member still gets 404 (no 402). One test replaces the old one, so no duplicate.
- Checked: no other `OutletAccess` constructor or `requireOwner` mock on the outlet-management / consolidated paths exists in `src/test` (no unit test of `OutletService`).

Extended (existing classes only):
- `PlanEntitlementIntegrationTest.anOutletOverTheAllowanceAfterTheAddOnLapses...`: now asserts the 402 body `data.reason = OUTLET_OVER_ALLOWANCE` and `data.usableOutletIds` (length 1, the first outlet) on an operational route and on select-merchant. The old final "deactivate while over allowance via the first token" step was dropped (superseded by the next test).
- New `PlanEntitlementIntegrationTest.anOwnerWhoseTokenOutletIsOverTheAllowanceCanStillManageOutletsAndReadTheConsolidatedReport` (F2, reuses the class helpers): with the over-allowance token outlet, operational route 402, `GET /api/v1/merchant/outlets` 200 (`usable=false` for it), consolidated report 200 (only the usable outlet), deactivating the other outlet 200, after which the token outlet is usable (200). Not a duplicate: no existing test uses an over-allowance token for owner routes.
- `MerchantOutletIntegrationTest.anOutletWithAnOpenUnpaidOrderCannotBeDeactivatedUntilTheOrderIsCancelled` (F3): 409 "pesanan terbuka", then cancel the order, then 200. Kept separate from the open-shift test: that one needs a shift and the last-outlet assertions, merging would hide which rule refused.
- `MerchantOutletIntegrationTest.withThreeActiveOutletsAndOneAddOnOnlyTheTwoOldestStayUsable` (F1 boundary on real Postgres): `extra_outlets=2`, 3 outlets all 200 (active == allowance); lowered to 1: outlets 1 and 2 stay 200, outlet 3 is 402 with `reason` and 2 usable ids; the list flags `usable` true/false for rank 2/3. This exercises the new `findOutlet` subqueries (rank vs `1 + sum(extra_outlets)`), including the UUID tie-break path only when timestamps tie.

Not covered: the equal-`created_at` tie-break (cannot be forced through the API); the Blaze subquery risk in the A1 report is exercised by every guarded IT request, so the first IT run will show it.
