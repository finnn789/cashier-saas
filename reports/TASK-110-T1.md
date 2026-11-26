# TASK-110-T1 (test-worker, Wave 0 RED)

Test status: NOT RUN (orchestrator runs RED).

Written: `src/test/java/org/lite/project/cashiersaas/unit/service/OnboardingIntentServiceTest.java` (new). No production code touched; no existing tests modified.

| Behaviour | Test method | Expected RED reason |
|---|---|---|
| Switch OWNER<->CASHIER_ONLY while pending, no business (parameterized, 2 rows) | `switchesIntentWhilePendingWithoutBusiness` | Entity throws IllegalStateException -> `OperationConflictException` |
| Owner with pending tenant cannot switch to CASHIER_ONLY | `ownerWithPendingBusinessCannotSwitchToCashier` | Passes today (old lock); guard for D2 |
| Pending tenant accepts OWNER intent (from CASHIER_ONLY), nextStep COMPLETE_BUSINESS_PROFILE, pendingTenantId | `pendingBusinessAcceptsOwnerIntent` | Entity throws -> `OperationConflictException` |
| ACTIVE account cannot change intent | `activeAccountCannotChangeIntent` | Passes today; guard for D1 |

Fixture fix (coordinator request): users now get `id = userId` via `ReflectionTestUtils.setField(user, "id", userId)` (field `id` on `BaseEntity`) in `pendingUser` (now an instance method) and in the ACTIVE cashier of `activeAccountCannotChangeIntent`, so the `findLatestPendingOwnedBy(userId)` stub matches. No assertion, case or stub changed. Still NOT RUN.

Skipped because already covered: none (no existing test covers this service).

Open questions: none. Note: `findLatestPendingOwnedBy` is deliberately unstubbed in cases 1 and 4 (Mockito returns `Optional.empty`), so GREEN may query the tenant before or after the entity call. Strict stubs: GREEN must call the lookup in cases 2 and 3 (it does for the status anyway).
