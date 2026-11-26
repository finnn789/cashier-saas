# TASK-110 J1 report

Status: DONE (implementation), not verified by run.
Baseline commit: `ded6050` (dirty tree).

## Changed files (modified)
- `src/main/java/org/lite/project/cashiersaas/entity/shared/identity/StaffUserEntity.java`: `chooseOnboardingIntent` now throws only when the account is not PENDING_PROFILE (the "different intent" lock is removed).
- `src/main/java/org/lite/project/cashiersaas/service/OnboardingIntentService.java`: `choose` looks up the pending tenant once; if one exists and intent != OWNER it throws `OperationConflictException("Jalur pendaftaran tidak dapat diubah.")`; the same tenant is passed to `OnboardingMapper.toStatus` (one query). Message extracted to a private constant (used twice).

## Contract points
- D1: ACTIVE/DISABLED still rejected (entity throws -> 409; DISABLED rejected earlier by `requireAccount`).
- D2: pending tenant present -> only OWNER accepted, also recovers CASHIER_ONLY -> OWNER.
- D3: tenant rule in the service, no new endpoint/DTO/migration.

## Deviations
None.

## Existing tests to update
None found in the reserved scope. Any other test asserting the old "different intent" lock is not known (not searched beyond the Wave 0 test).

## Open questions
None.

## Test status
NOT RUN (orchestrator runs GREEN).
