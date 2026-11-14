# TASK-081 W2 report - Java gRPC + entity tests

Baseline `da2c2cd`. Test code only; no Maven or tests run (policy). Counts = `@Test` + `@ParameterizedTest`.

## Files touched and counts (before -> after)
| File (under `src/test/java/org/lite/project/cashiersaas/unit/`) | Before | After |
|---|---|---|
| grpc/GrpcTenantAccessPolicyTest | 26 | 15 |
| grpc/GrpcRateLimiterTest | 8 | 4 |
| grpc/GrpcServiceKeyInterceptorTest | 12 | 10 |
| grpc/GrpcSecurityPropertiesValidationTest | 7 | 6 |
| grpc/GrpcInsightMapperTest | 6 | 5 |
| grpc/CashierGrpcHandlerRejectionTest | 15 | 12 |
| grpc/CashierInsightGrpcHandlerTest | 10 | 10 (untouched) |
| entity/SalesOrderEntityTest | 10 | 9 |
| entity/InventoryBalanceEntityTest | 17 | 8 |
| entity/CashShiftEntityTest | 6 | 4 |
| Total | 117 | 83 |

## Tier 1 items
- GrpcTenantAccessPolicyTest:89 (upper-case UUID): DELETED. Claim holds, ACTIVE is all digits so `toUpperCase()` is a no-op and the body equals :78.
- GrpcTenantAccessPolicyTest:106 (unknown tenant no leak): DELETED. :114 asserts unknown == inactive and equals the constant message, which contains no id; :95 covers the no-leak check.
- GrpcTenantAccessPolicyTest:318 (owner blank/malformed user): DELETED. `requireOwnerOutlets` and `requireMemberOutlets` share `parseUserId` before any repository call, so the inputs are covered by the merged member test. The `verifyNoInteractions(membershipRepository)` on the owner path with an invalid user is no longer asserted; low risk since parsing happens first.
- GrpcServiceKeyInterceptorTest:171 (rate limit recovers): DELETED. Its keeper `GrpcRateLimiterTest:49` is KEPT (see below).
- GrpcServiceKeyInterceptorTest:76 (header name constant): DELETED per decision. Risk: the Java header-name literal is no longer asserted anywhere in Java (other tests use the constant). Python tests `test_grpc_client.py:98/:159` assert the literal on the client side, and W4 deletes `test_grpc_client_security.py:105` which also asserted it. Cross-language drift is now caught only by the Python metadata assertions plus integration tests.
- GrpcRateLimiterTest:63, :76, :85, :111 (partial refill, cap after idle, idle after partial use, concurrency): DELETED. All four are covered by TokenBucketTest (fractional refill accumulation, capped refill, concurrent grant), and `GrpcRateLimiter` only wraps `TokenBucket`.
- GrpcRateLimiterTest:50 (refills continuously, method at line 49): KEPT, claim partly wrong. The test is the only check that `GrpcRateLimiter` wires refill rate == requestsPerSecond (TokenBucketTest builds buckets with its own rates). With :171 deleted, deleting it too would leave that wiring unguarded. Kept: :40, :49, :95, :105. Unused imports removed (ArrayList, List, Callable, CountDownLatch, ExecutorService, Executors, Future, TimeUnit).
- GrpcSecurityPropertiesValidationTest:52 (defaults): DELETED. The valid baseline is still exercised by :62 (16-char key valid) and the default limits by the clamp test in GrpcTenantAccessPolicyTest.
- GrpcInsightMapperTest:184 (restock with no items): DELETED (trivial; header mapping covered by :156).
- SalesOrderEntityTest:169 (order note): DELETED. Claim "covered by FnbPosOrderServiceTest:203" was only partly right: that test covers `replaceLines` store/trim/clear with the real entity, but not the `pos(...)` factory storing the note. Moved that assertion into `posFactoryCreatesOpenUnpaidOrder...`: it now uses note "tanpa es" instead of null and asserts `getOrderNote()`. `assertNull` import removed.
- InventoryBalanceEntityTest:87 (status after each apply): DELETED (OK/LOW/OUT/NEGATIVE thresholds covered by the status CsvSource; accumulation by `applyAccumulates...`). :111 (new balance no average): DELETED (covered by `receiveWithoutPrice...` unpriced assertNull). :35 (create starts at zero, getter echo): DELETED. `assertSame` import removed.
- CashShiftEntityTest:36 (open): DELETED after moving its scale-2 assertions into the merged close test: opening "100000" -> "100000.00" and "0" -> "0.00" are asserted per row. Open-state getters (isOpen/OPEN/same tenant/outlet/membership/openedBy/null closedAt/expected/difference) dropped as getter echo.

## Tier 2 merges
- GrpcTenantAccessPolicyTest :133+:142+:152 -> `nullBlankOrMalformedTenantIsInvalidArgumentWithoutTouchingTheDatabase` (`@NullSource` + `@ValueSource`). Dropped the duplicate blank value "   ". Kept "", " ", "\t", "\n" and all five malformed inputs. Added `NullSource` import.
- :198+:203+:210+:216 -> rows added to the existing `escapeLikePatternEscapesWildcards` CsvSource: `\`, `a\%b`, `\\`, null->"", ""->"", plain text, unicode. Single test.
- :237+:246 -> `nullBlankOrMalformedUserIsInvalidArgumentWithoutTouchingTheDatabase` (null, "", " ", tab, "not-a-uuid"), still `verifyNoInteractions(outletAccessRepository)`.
- :269+:325 -> `memberOutletsAreReturnedForAnActiveMemberWithoutConsultingTheMembershipRole`: padded upper-case user assertion plus `verifyNoInteractions(membershipRepository)`. The old :325 `contains` check is subsumed by equality. `assertTrue` import removed.
- :222+:230 -> `shortTenantRefTruncatesToEightCharactersAndMapsNullAndBlankToNone`, all 7 assertions kept.
- CashierGrpcHandlerRejectionTest :111+:119+:126 -> `catalogRejectsBlankMissingOrInvalidTenant` (CsvSource: "" / "   " / "not-a-uuid"+"latte"). The missing-field case is the proto default instance, i.e. row 1 (tenant "" and query ""). `CsvSource` import added.
- CashierGrpcHandlerRejectionTest :198+:205 (catalog side) -> `catalogRejectsMissingOrInvalidUserForAllowedTenant` (ValueSource "", "nope"). Order-side tests :154/:163/:172/:222/:230 untouched.
- InventoryBalanceEntityTest :60+:66+:73+:81 -> one CsvSource test (reorder level, quantity, expected Status), 11 rows with all original inputs. :138+:154 -> `averageIsRoundedHalfUpToFourDecimals` CsvSource, 3 rows (the third has only one receive, so the test has an `if (secondQuantity != null)`). :163+:175+:187 -> `averageResetsToTheNewPriceWhenStockIsZeroNegativeOrUncosted`, 3 rows; the uncosted case uses `emptyBalance()` + `apply(20)`, equivalent to the old `balanceOf("10","20")`. `ParameterizedTest`/`CsvSource` imports added.
- CashShiftEntityTest :63+:79 -> `closeStoresExpectedCountedAndSignedDifference` CsvSource, 3 rows: deficit with padded note closed by owner; surplus with blank note; exact count with null note (the old exact case asserted only signum 0, now asserts equality with 0.00, which holds because `close` scales both to 2). Per-row assertions are a superset of the originals.

## Not changed
`CashierInsightGrpcHandlerTest` (not listed), `TokenBucketTest`, `MerchantAuditRepositoryTest` (out of scope).

## Risks
- Nothing compiled or run. Check that the CsvSource quoting edge cases behave as intended: `'  selisih kecil  '` keeps padding, `|''` gives null input with "" expected, `\` rows in the `|`-delimited escape test.
- Header-name constant risk noted under GrpcServiceKeyInterceptorTest:76.
