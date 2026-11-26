# TASK-109 J1: Java unit test cleanup

Test status: NOT RUN (orchestrator runs). Not compiled either.

## Changes (all under `src/test/java/org/lite/project/cashiersaas/unit/`)
1. `service/FnbShiftServiceTest.java`: deleted `openingSuggestionIsZeroWithoutHistoryElseTheCountedCashOfTheLatestClosedShift`
   and the unused imports `FnbShiftOpeningSuggestionResponse`, `assertNull`.
2. `service/RegisterServiceTest.java`: deleted `registerRejectsAnInvalidPhoneNumberBeforeTouchingPersistence` and
   `registerRejectsAMissingPhoneNumber`, plus the imports only they used (`InvalidOperationException`, `assertFalse`,
   `verifyNoInteractions`). Coordinator correction: `registerStoresTheNormalizedPhoneNumber` is KEPT as in the
   baseline, with `stubSuccessfulRegistration` and its imports, because it is the only check of full-name trim and
   email normalization. This overrides the packet's J1 item 2 for that test.
3. `grpc/CashierInsightGrpcHandlerTest.java`: renamed the parameterized test to
   `salesLeakageAndMarginRpcsAreDeniedBeforeArgumentFeedbackForANonOwnerOrNonPremiumTenant`; it now calls
   `getSalesSummary` with garbage dates and asserts PERMISSION_DENIED and the description. Deleted
   `aRejectedCallerGetsPermissionDeniedBeforeAnyArgumentFeedback`; its `verifyNoInteractions(accessRepository)`
   is covered by the existing final `verifyNoInteractions`.

The sales call runs in both branches: the handler's shared `handle` calls `requireAllowedTenant`,
`requireOwnerOutlets`, `requirePremium`, then validation, for every RPC.

## Coverage of deleted tests
- Opening suggestion -> `FnbPettyCashOrderIntegrationTest.openingSuggestionIsZeroWithoutHistoryElseTheCountedCashOfTheLatestClosedShiftOfAnyMember`
- Invalid / missing phone -> `RegisterPhoneNumberIntegrationTest.invalidPhoneNumberReturnsBadRequestWithoutEchoingItAndCreatesNoUser`,
  `missingOrBlankPhoneNumberIsRejectedByValidation`
- Rejected caller on sales -> the merged parameterized test

## Expected result
No RED expected (deletion/merge of existing behaviour); all three classes should pass.

## Open questions / lost assertions
- Missing/blank phone is now covered only at HTTP validation level, not by a direct service call.
