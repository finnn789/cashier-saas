# TASK-081 W3 report (Java services / dto / ratelimit / component / exception)

Baseline `da2c2cd`. Test code only. No Maven or tests run (policy). Nothing committed. Counts are `@Test` + `@ParameterizedTest` before -> after.
Note: the audit line numbers mix `@Test` lines and `void` lines between files; each item below is identified by method name.

## Counts

| File | Before | After |
|---|---|---|
| unit/service/FnbOwnerInsightServiceTest | 22 | 17 |
| unit/service/DailySummaryClientTest | 12 | 7 |
| unit/service/FnbSalesReportServiceTest | 2 | 1 |
| unit/service/FnbPosOrderServiceTest | 8 | 5 |
| unit/service/MerchantBusinessSettingsServiceTest | 15 | 14 |
| unit/service/MemberSyncClientTest | 17 | 11 |
| unit/service/MemberSyncPublisherTest | 4 | 3 |
| unit/service/DigestServiceTest | 2 | 1 |
| unit/dto/FnbSalesReportMapperTest | 8 | 5 |
| unit/dto/MerchantBusinessSettingsMapperTest | 3 | 3 |
| unit/dto/MerchantAuditMapperTest | 3 | 2 |
| unit/ratelimit/HttpRateLimiterTest | 9 | 6 |
| unit/ratelimit/RateLimitFilterTest | 10 | 8 |
| unit/component/MemberSyncListenerTest | 6 | 5 |
| unit/component/TenantJwtAuthenticationConverterTest | 5 | 4 |
| unit/shared/PhoneNumberNormalizerTest | 6 | 5 |
| exception/GlobalExceptionHandlerTest | 4 | 2 |
| Total | 136 | 99 |

Note: parameterized tests count as one method but run several cases (about 8 extra invocations: ownerInsight 3+2, dailySummary 3+3, posOrder 3, mapper 3, memberSyncClient 2+3+3, publisher 2, jwt 2), so executed invocations drop less than the method count.

## Tier 1 deletions
- FnbOwnerInsightServiceTest `ownersAreExcludedBecauseOnlyCashierMembershipsAreEvaluated` (:357): deleted. It only proves the mocked `listCashiersForOutlet` list is the list (service code comment "owners are never listed"; the real filter is the repository query).
- FnbOwnerInsightServiceTest `salesInsightValidatesTheRangeBeforeAnyExtraQuery` (:428): deleted. The throw comes from a mocked `salesReports.summarize`; range validation is proven in FnbSalesReportService. Mapping/extras stay covered by `salesInsightCombinesTheReport...`.
- DailySummaryClientTest `theSpringConstructorBuildsNoHttpClientWhenTheBaseUrlIsBlank` (:165): deleted after folding. Claim "= :138" is only partly right: :138 uses the 3-arg test constructor, :165 uses the Spring constructor (skips building the RestClient). Its two assertions (`isEnabled` false, `send` false) were moved into the next item.
- DailySummaryClientTest `theSpringConstructorIsEnabledWithUrlAndKey...` (:173): KEPT (renamed `theSpringConstructorIsEnabledWithUrlAndKeyAndDisabledWhenTheFlagIsOffOrTheBaseUrlIsBlank`). Claim "= :156" is wrong: :156 uses the test constructor with an injected RestClient, :173 is the only test that builds the real Spring-constructor client and asserts `isEnabled()` true. It now also carries the blank-URL assertions from :165.
- FnbSalesReportServiceTest `reportWiresCostRefundsAndBreakdownsIntoTheResponseUsingTheOutletZone` (:79): deleted. Its mapped values duplicate the mapper tests; hourly/cashier/shift values are also asserted by FnbCostAndStockCountIntegrationTest (`$.data.hourly`, `$.data.cashiers`). Two assertions the keeper (:123) lacked were moved into it: `response.getTimeZone() == "Asia/Jakarta"` and `verify(reports).products(..., 10)` (default top). Unused imports removed (CashShiftEntity, CashierRow, HourlyRow, ProductRow, ShiftRow, List, assertNull).
- FnbPosOrderServiceTest `lockTenantRunsBeforeTheOrderIsLoaded` (:145): deleted. Claim "= :157" is wrong (:157 does not assert lock order), but the `lockTenant` then `byId` `InOrder` is already asserted in `happyPathReplacesLinesWithSnapshotRatesAndWritesAudit` (:95), so no assertion is lost.
- FnbSalesReportMapperTest `hourlyRowsKeepOrderAndValues` (:112) and `cashierRowsKeepOrderAndValues` (:124): deleted (copy mappers). Imports FnbSalesCashierResponse, FnbSalesHourlyResponse, CashierRow, HourlyRow removed.
- GlobalExceptionHandlerTest `optimisticLockFailureIsReturnedAsConflictResponse` (:35) and `optimisticLockHandlerDeclaresConflictStatus` (:45): deleted. `optimisticLockFailureIsMappedToHttp409` (:55) asserts code 409, success false, message and HTTP 409 through MockMvc; `unexpectedExceptionIsStillHttp500` (:63) kept. Unused imports removed (ApiResponse, HttpStatus, ResponseStatus, assertEquals, assertFalse).
- MerchantAuditMapperTest `nullOptionalProjectionFieldsAreAbsentFromJacksonJson` (:47): deleted. `jacksonThreeOmitsNullFieldsInPageAndNestedEntries...` (:63) asserts the same absent fields on the nested entry plus the `action` value.
- RateLimitFilterTest `formerPublicPathsAreClassifiedAsAnonymousOther` (:105): deleted. The filter has no menu-path special case; `anonymousRequestsAreLimitedPerIp` (:145) covers shared ANONYMOUS_OTHER bucket, per-IP isolation, 429 on the third call. The now-unused private constant `MENU` was removed.
- RateLimitFilterTest `allowedRequestContinuesTheChainUntouched` (:183): deleted; its unique assertions (chain passed, status 200, no `Retry-After`) moved into the first call of `rejectedRequestGetsTooManyRequestsEnvelope...`.
- HttpRateLimiterTest `allowDecisionHasNoRetryAfter` (:94) and `reportsDisabledWhenConfiguredOff` (:101): deleted (trivial; `RateLimitFilterTest.disabledFilterPassesEverythingThrough` covers the disabled switch end to end).
- MerchantBusinessSettingsServiceTest `updatingFutureRatesPreservesExistingOrderSnapshotsAndTotals` (:259): deleted (asserts a local order the service never touches; `lockTenant` is verified in the update tests). Imports SalesOrderEntity and ServiceType removed.
- MerchantBusinessSettingsServiceTest `ownerGuardFailureDoesNotMutateSettingsOrWriteAudit` (:88): KEPT. Claim "dup of :80" is wrong: :80 tests `service.get`; this one is the only test of the `update` path when the guard denies (lockTenant runs before the guard, no settings/audit interaction).
- MemberSyncListenerTest `listenerRunsOnlyAfterCommit` (:61): deleted (annotation reflection; commit and rollback behavior is tested through a real Spring context). Imports TransactionPhase, TransactionalEventListener removed.
- MemberSyncClientTest `configuredClientIsEnabled` (:301): deleted as trivial. Side effect: the one-arg Spring constructor `isEnabled()` true assertion no longer exists in this file.

## Tier 2 merges
- DailySummaryClientTest :113+:122+:129 -> `aFailedResponseIsReportedAsFailedWithoutThrowing` (`@MethodSource`, 500 / 401 / IOException). All three rows now use `assertDoesNotThrow` and `server.verify()` (the 401 case previously lacked both; stricter, still true as the client catches RuntimeException).
- DailySummaryClientTest :138+:147+:156 -> `aBlankBaseUrlOrApiKeyOrTheFeatureFlagOffDisables...` (`@MethodSource`: blank URL, "  " key, flag off); each keeps `isEnabled` false, `send` false, `server.verify()`. Flag-off row uses the 3-arg constructor as before.
- FnbOwnerInsightServiceTest :181+:195 -> `restockRoundsAverageAndSuggestionToThreeDecimalsHalfUp` (`@CsvSource`: -1.000 -> 0.071/0.497, -0.007 -> 0.001/0.007). The flour row now also asserts suggested 0.007, scale 3 and OUT status (derived from the service formula: 0.001*7 + 0 - 0).
- FnbOwnerInsightServiceTest :279+:316+:373 -> `belowTheMinimumOfThreeOrWithNoActivityProducesNoAnomaly` (`@ValueSource` CANCELLATIONS / DISCOUNTS / NONE).
- FnbPosOrderServiceTest :167+:180+:221 -> `paidCancelledOrDiscountedOrderIsRejected` (`@ValueSource` PAID / CANCELLED / DISCOUNTED); keeps the status/total post-assertions per state and `verifyUntouched()`. `qrSourcedOrderIsRejected` (:192) left alone (out of scope).
- FnbSalesReportMapperTest :42+:53 -> `grossProfitIsNetSalesAfterDiscountMinusCogs` (`@CsvSource`, 3 rows: base case, no cost known with itemsWithoutCost 3, negative profit). The compareTo assertion became exact `equals` on scale-2 values (500.00 - 0 = 500.00).
- MemberSyncClientTest :124+:137 -> `requestMatchesTheContractFixture` (`@ValueSource` owner / cashier). `deactivatedMemberIsSentAsDisabled` (:150) kept separate: it asserts a status override, not the fixture body (so 3 -> 2, not 3 -> 1).
- MemberSyncClientTest :231+:240+:249 -> `doesNotRetryClientAuthenticationOrRedirectResponses` (`@ValueSource` 422 / 401 / 302). The spec listed :240+:249; I also folded the identical 422 test (:231). All rows use `assertDoesNotThrow` and `server.verify()`.
- MemberSyncClientTest :269+:281+:289 -> `blankBaseUrlOrApiKeyDisablesTheSyncAndMakesNoCall` (`@MethodSource`: blank URL with client, blank URL with the one-arg constructor, blank key). The spec listed :281+:289; I also folded :269 (same assertions).
- HttpRateLimiterTest :59+:68 -> `differentKeysAndDifferentGroupsAreIndependent` (one test, all four assertions kept; no parameter fit).
- TenantJwtAuthenticationConverterTest :48+:57 -> `rejectsMissingOrInvalidTenantIdClaim` (`@NullSource` = claim absent, `@ValueSource("not-a-uuid")`).
- PhoneNumberNormalizerTest :41 into :55 -> `everyContractCaseProducesTheExpectedResultFromTryNormalizeAndNormalize` (single loop: `tryNormalize` check, `size >= 16` check, `normalize` result/exception and message; messages preserved).
- MerchantBusinessSettingsMapperTest :60 into :23: `mapsExplicitTenantAndOutletValuesAndOmitsNullOptionalFieldsFromJson` trimmed and renamed `nullOptionalFieldsStayNullAndAreOmittedFromJson`. Removed only the response-field `assertEquals` that :23 already proves (outletId, business name, currency, tax, service charge, outlet name, time zone); kept every null assertion and every JSON assertion. Still 3 methods (null inputs are distinct, so it cannot fold into :23's populated entity).
- DigestServiceTest :24 into :15 -> single `digestIsStableProducesAStoredLengthDigestAndDiffersForDifferentInput`; all assertions kept.
- MemberSyncPublisherTest :62 into :46 -> `publishesTheContractFieldsFromTheEntitiesAndCarriesTheStatus` (`@CsvSource` OWNERMERCHANT/ACTIVE, CASHIER/DISABLED, every field asserted on both rows).

## Risks / not verified
- Nothing compiled or executed. Syntax rechecked by an unused-import scan; added imports: `org.junit.jupiter.params.*` (already used elsewhere in the repo). CRLF line endings preserved.
- Parameterized rows that now assert slightly more than before (DailySummary 401 row, FnbSalesReportMapper rows, restock flour row) are derived by reading the production code, but only a test run proves them.
- Unit-level hourly/cashier mapping coverage now relies on `FnbCostAndStockCountIntegrationTest`.
