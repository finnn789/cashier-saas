# TASK-108 T2 report (TASK-106 platform unit tests)

Test status: NOT RUN (orchestrator runs). Code under test already exists, so these are test-after; expected result is GREEN. No production code changed.

## Behaviour -> test method
Converter (`unit/component/TenantJwtAuthenticationConverterTest`, extended)
- PLATFORM + SUPERADMIN -> PlatformPrincipal, only ROLE_SUPERADMIN: `platformTokenWithSuperadminBecomesPlatformPrincipalWithOnlyTheSuperadminAuthority`
- PLATFORM without SUPERADMIN and MERCHANT with SUPERADMIN -> BadCredentialsException "JWT contains an unsupported role.": `rejectsPlatformScopeWithoutSuperadminAndMerchantScopeWithSuperadmin` (parameterized)

Token (`unit/component/JwtTokenServiceTest`, extended)
- Platform token claims (scope PLATFORM, roles [SUPERADMIN], no tenant_id/outlet_id/membership_id): `platformTokenCarriesSuperadminRoleAndNoTenantClaims`

Guard (`unit/service/PlatformAccessGuardTest`, new)
- active + flag -> user: `returnsTheActiveAccountWithThePlatformFlag`
- unknown / inactive / flag false -> 404: `hidesThePanelBehindA404ForEveryOtherAccount` (parameterized)
- null principal -> 404, no repository call: `anonymousCallerGets404WithoutTouchingTheRepository`

LoginService (`unit/service/LoginServicePlatformTest`, new)
- startPlatformSession success (platformAdmin=true, nextStep PLATFORM): `startPlatformSessionIssuesAPlatformTokenForAnActiveAdmin`
- inactive / non-admin -> 404, nothing issued: `startPlatformSessionReturns404ForAnInactiveOrNonAdminAccountAndIssuesNothing` (parameterized)
- login sets platformAdmin from the user: `loginReportsThePlatformAdminFlagOfTheUser` (parameterized)

PlatformTenantAdminService (`unit/service/PlatformTenantAdminServiceTest`, new)
- grant plan (ACTIVE row with start rule, audit PLATFORM_PLAN_GRANTED, trimmed reason, admin actor, tenant lock): `grantPlanCreatesAnActivePaidRowByTheCheckoutStartRuleAndAuditsTheTrimmedReason`
- grant FREETIER refused: `grantPlanRefusesFreetierWithoutWriting`
- end FREETIER 409: `endPlanRefusesTheFreetierBaseRow`
- end running paid row: `endPlanEndsARunningPaidRowNowAndAudits`
- end not-yet-started row -> CANCELLED: `endPlanCancelsARowThatHasNotStartedYet`
- end expired row 409: `endPlanRefusesARowThatAlreadyExpired`
- AI top-up bonus + audit: `topUpAiAddsBonusQuestionsToTheTenantsCurrentMonthAndAudits`
- suspend / reactivate state + 409s + audits: `suspendMovesAnActiveMerchantToSuspendedAndAudits`, `suspendRefusesAMerchantThatIsNotActive`, `reactivateMovesASuspendedMerchantBackToActiveAndAudits`, `reactivateRefusesAMerchantThatIsNotSuspended`
- mark-paid delegates `settle(invoiceId, trimmed ref, admin id, trimmed reason)`: `markPaidSettlesWithTheTransferReferenceAdminAndReason`
- cancel delegates to `PlatformBillingService.cancel(tenantId, invoiceId, reason, adminId)`: `cancelInvoiceDelegatesToTheBillingServiceWithTheInvoiceTenantAndAdmin`
- revoked admin -> 404 on all 7 writes, no lock/write/audit/settle: `aRevokedAdminIsStoppedByTheGuardOnEveryWriteBeforeAnythingChanges`

PlatformDashboardService (`unit/service/PlatformDashboardServiceTest`, new)
- guard first on all 5 reads, repositories untouched: `everyReadStopsAtTheGuardForARevokedAdminBeforeTouchingData`
- page size clamp 1..50, negative page -> 0: `pageSizeIsClampedToOneThroughFiftyAndNegativePagesStartAtZero` (parameterized)

## Existing tests modified
- `TenantJwtAuthenticationConverterTest.jwt(...)` helper: `scope` and `session_id` now `putIfAbsent` (were `put`, which overrode a PLATFORM scope). Existing tests unaffected.

## Skipped
- Plan buckets FREETIER/PRO/PREMIUM/TRIAL mutually exclusive: lives in HQL of `PlatformAdminRepository.tenantWhere`, not unit-testable without a DB (belongs to an integration test; note `PlatformDashboardService.planInfo` highest-row/trial logic is private and only reachable through `tenants()` with mocked repo, not covered here).
- Pure repository pass-through, DTO mapping, and 404 for unknown tenant/subscription/invoice (trivial `orElseThrow`).
- Merchant-token SUPERADMIN-free happy path already covered by `convertsValidJwtIntoTenantPrincipalAndRoleAuthorities`.

## Contract vs code
- `PlatformTenantAdminService.cancelInvoice` passes `request.getReason()` untrimmed to `cancel`, while other writes trim; test uses an unpadded reason. Minor inconsistency, not asserted.
- `endPlan` on a row not running and not future (expired) throws 409 "Paket tidak sedang aktif." (not in the packet list; covered by a test).
- All signatures matched the packet.

## Open questions / expected RED reason
None expected; if any test fails, the code disagrees with the contract (finding for the orchestrator). Risk spots: `ReflectionTestUtils` field name `id` on entities (assumed in `BaseEntity`), and `IssuedTokens` public constructor from Lombok `@RequiredArgsConstructor`.
