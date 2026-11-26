# TASK-108 T1 report (TASK-105 billing unit tests)

Test status: NOT RUN (orchestrator runs). Production code untouched.

Files (new):
- src/test/java/org/lite/project/cashiersaas/unit/service/PlatformBillingServiceTest.java
- src/test/java/org/lite/project/cashiersaas/unit/service/PlatformBillingSettlementServiceTest.java

Style: Mockito mocks for repositories/guard; the real `MockPaymentGateway` and real `PlatformBillingSettlementService` are built with `new` in the test (no Spring). Reason: `expireDue/expireIfDue/releaseHold` are package-private, so cancel/expiry/hold-release is exercised through the public entry points, and provider MOCK vs MANUAL_TRANSFER is decided by the real gateway.

## Behaviour -> test
PlatformBillingServiceTest
- quote prices (PRO/PREMIUM, monthly/yearly, add-on) -> `quotePricesPlanCycleAndOutletAddOnFromTheServerCatalog` (6 rows)
- quote AI top-up 29.000 / 200 -> `quoteOfAiTopupIsFixedPriceFor200QuestionsOnPaidPremium`
- NEW/RENEW/UPGRADE/DOWNGRADE -> `quoteClassifiesTheChangeAgainstTheHighestLivePaidPlan`
- missing/invalid key -> `checkoutRequiresAWellFormedIdempotencyKey` (null, empty, short)
- replay same key+payload -> `checkoutReplayWithTheSameKeyAndPayloadReturnsTheExistingInvoiceWithoutNewRows`
- same key other payload 409 -> `checkoutReusingAKeyForAnotherPayloadIsAConflict`
- open invoice 409 -> `checkoutIsRefusedWhileAnotherInvoiceIsStillOpen`
- PENDING row + ISSUED invoice due 24h + PENDING attempt + audit; MOCK vs MANUAL_TRANSFER -> `checkoutIssuesAPendingRowAnInvoiceDueIn24HoursAnAttemptAndAnAudit`
- AI top-up checkout links Premium row, no new subscription -> `checkoutOfAnAiTopupLinksThePaidPremiumRowWithoutCreatingASubscription`
- top-up without paid Premium 409 (no row / PRO row) -> `checkoutOfAnAiTopupWithoutAPaidPremiumIsAConflict`
- cancel ISSUED releases row/attempts + audit -> `cancellingAnIssuedInvoiceReleasesItsPendingRowAndAttemptsAndAudits`
- cancel PAID 409 -> `cancellingAPaidInvoiceIsAConflictAndWritesNothing`
- foreign invoice 404 -> `cancellingAnInvoiceOfAnotherTenantIsNotFound`
- mock disabled 404 -> `mockPaymentIsNotFoundWhenTheMockGatewayIsDisabled`

PlatformBillingSettlementServiceTest
- start rule (now / stacked same plan / stacked after higher plan = downgrade queued / upgrade now) -> `subscriptionStartsNowOrRightAfterTheLastPaidPeriodOfTheSameOrAHigherPlan`
- open-ended 409 -> `subscriptionStartRefusesAnOpenEndedPaidRow`
- calendar-month period end in tenant zone -> `periodEndAddsCalendarMonthsInTheTenantZone`
- settle subscription (row ACTIVE, dates, attempt SUCCEEDED + ref, audit with reason) -> `settlingASubscriptionInvoiceActivatesItsRowPaysTheAttemptAndAudits`
- settle AI top-up bonus 200 -> `settlingAnAiTopupInvoiceAddsTheBonusQuestionsToThisMonth`
- double settle same ref idempotent -> `settlingAPaidInvoiceAgainWithTheSameReferenceReturnsItWithoutASecondActivation`
- other ref (and null ref) 409 -> `settlingAPaidInvoiceWithAnotherReferenceIsAConflict`
- not ISSUED 409 -> `settlingAnInvoiceThatIsNotIssuedIsAConflict`
- past due: EXPIRED, row/attempts cancelled, 409 -> `settlingAPastDueInvoiceExpiresItAndReleasesItsHoldBeforeRefusing`
- fail keeps ISSUED, attempt FAILED, audit -> `aFailedPaymentKeepsTheInvoiceIssuedAndRecordsTheFailedAttempt`

## Existing tests modified
None.

## Skipped (already covered / out of unit scope)
- `mockPay` SUCCEEDED/FAILED routing: covered through settle/fail tests (mockPay only delegates).
- Cancel via `cancelForOwner`: thin wrapper over `cancel`, covered by the cancel tests.
- Quote validation messages (FREETIER/missing cycle/extra outlets out of range) and `list/get`: not in the packet list.

## Open questions / contract notes
- Signatures matched the packet; `expireDue/expireIfDue/releaseHold` are package-private (hence real settlement in the billing test).
- `TenantPrincipal` has a 3-arg ctor so `outletId` is null; guard is stubbed with `any()` for the outlet argument.

## Expected result
These are test-after tests: they should be GREEN. A failure means code and contract disagree (finding for the orchestrator). Likeliest fragile spots: strict-stubbing (`UnnecessaryStubbingException`) if a code path reads fewer repository calls than stubbed, and `Mockito any()` matching a null `outletId`.
