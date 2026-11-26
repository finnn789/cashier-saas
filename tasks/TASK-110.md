# TASK-110: Allow changing onboarding intent (Owner <-> Cashier) before a business exists

Status: DONE (reviewed, not committed). Memory version: 104. Branch `feature/leakage-report`. Baseline commit: `ded6050` (dirty: large pre-existing unrelated tree, incl. `.ai/*`, `AGENTS.md`, `CLAUDE.md`, Ai-services tests, `JwtTokenService`, `TenantJwtAuthenticationConverter`; workers must not touch them).

## User instruction (2026-11-25)
A user who picked Owner or Cashier during onboarding cannot switch. Allow switching Owner <-> Cashier, delegate to workers, use TDD, do not add tests that are not needed.

## Global rules (every worker)
- Reports: `reports/TASK-110-<id>.md`.
- No migration (column `staff_users.onboarding_intent` already nullable, CHECK allows OWNER/CASHIER_ONLY).
- Reuse: `OperationConflictException` (409 via `GlobalExceptionHandler`), `TenantRepository.findLatestPendingOwnedBy`, `OnboardingMapper.toStatus`.

## Verified facts
- `PUT /api/v1/onboarding/intent` -> `OnboardingController.chooseIntent` -> `OnboardingIntentService.choose(principal, intent)` -> `StaffUserEntity.chooseOnboardingIntent(intent)`; an `IllegalStateException` from the entity becomes `OperationConflictException("Jalur pendaftaran tidak dapat diubah.")` (409).
- Current entity rule (`StaffUserEntity.java:79-84`): throws when `!isPendingProfile()` OR when an intent is already set and differs. Same intent again is accepted.
- `OnboardingMapper.toStatus(user, pendingTenant)`: pendingTenant != null -> `COMPLETE_BUSINESS_PROFILE`; intent null -> `CHOOSE_INTENT`; pending -> `START_FREETIER` (OWNER) / `CLAIM_INVITATION` (CASHIER_ONLY); else `SELECT_MERCHANT_OUTLET`.
- `FreeTierOnboardingService.startFreeTier` requires pending + intent OWNER and creates tenant (PENDING_PROFILE) + OWNERMERCHANT membership + FREETIER subscription. `complete` requires intent OWNER.
- `CashierInvitationService.claim` requires intent CASHIER_ONLY and activates the user (status ACTIVE) on success.
- No existing test covers intent switching or `OnboardingIntentService`. `unit/service/FreeTierOnboardingServiceTest.java` shows the unit style for onboarding services.

## Orchestrator design decisions (reversible, user may override)
- D1: Switching is allowed only while the account is `PENDING_PROFILE` (ACTIVE / DISABLED stay rejected, as today).
- D2: Once the user owns a pending tenant (`findLatestPendingOwnedBy` present, i.e. Owner already called `/freetier`), the only accepted intent is OWNER. Switching to CASHIER_ONLY then -> 409 with the existing message. Accepting OWNER in that state also lets a user whose intent somehow became CASHIER_ONLY after a tenant was created recover.
- D3: The entity drops only the "different intent" lock; the tenant rule lives in `OnboardingIntentService` (the entity cannot see tenants). No new endpoint, no reset endpoint, no DTO change.
- D4: UI: an explicit "Change role" action on the profile step (only when `status.pendingTenantId` is empty) and on the cashier waiting step; it shows the existing intent step with a back button. No UI tests (the UI repo has no component tests; logic is covered by the backend tests).

## Wave 0 - RED (`test-worker`, before any production code)
Reserved:
- `src/test/java/org/lite/project/cashiersaas/unit/service/OnboardingIntentServiceTest.java` (new; no existing class covers this service)
Do: write the test cases below as Mockito unit tests against `OnboardingIntentService` with real `StaffUserEntity` objects (`StaffUserEntity.registerWithPassword(...)` gives PENDING_PROFILE; `chooseOnboardingIntent` to preset intent; `createCashier` gives ACTIVE). Mock `StaffUserRepository.findById` and `TenantRepository.findLatestPendingOwnedBy`. Follow `FreeTierOnboardingServiceTest` style. Parameterize variants, one test per behaviour, no helpers/base classes.

## Wave 1 - GREEN (parallel, after RED is confirmed; never edit the Wave 0 tests)

### J1 - backend (`java-worker`)
Reserved:
- `src/main/java/org/lite/project/cashiersaas/entity/shared/identity/StaffUserEntity.java` (method `chooseOnboardingIntent` only)
- `src/main/java/org/lite/project/cashiersaas/service/OnboardingIntentService.java`
Do: minimal change implementing D1-D3. Keep the existing 409 message. Reuse the pending tenant lookup already done in `choose` (one query, not two).
Acceptance: Wave 0 tests green; `FreeTierOnboardingServiceTest` still green; `mvnw.cmd compile` clean.

### F1 - UI (`fe-worker`, repo `E:\Java\cashier-saas-ui`)
Reserved:
- `src/pages/onboarding/OnboardingPage.tsx`
- `src/language/id.ts`, `src/language/en.ts` (onboarding keys only)
- `src/api/mock/onboarding.mock.ts` (only if its `chooseIntent` must accept a switch; read it first)
Do:
- In `OnboardingPage`, a local `changingIntent` boolean. When true render `IntentStep` with an optional `onCancel` prop (renders a "Back" text button) and preselect the current `status.intent`; `onDone` stores the new status in `ONBOARDING_STATUS_KEY` (as today) and resets `changingIntent`.
- Add a "Change role" text button in `ProfileStep` (hidden when `status.pendingTenantId` is set, because the server rejects it) and in `CashierWaitingStep`. Same visual style as the existing `LogoutLaterButton` in that file.
- 409 from the server is already shown by `IntentStep` via `AuthAlert`; keep that.
- Strings in both language files, no hardcoded UI text.
Acceptance: `tsc -b` and `npm run lint` clean (run by orchestrator); existing vitest suite green.

## Test cases (TDD - written and RED before any production code)
All in `OnboardingIntentServiceTest`:
1. `switchesIntentWhilePendingWithoutBusiness` (parameterized): pending user with intent OWNER, no pending tenant, choose CASHIER_ONLY -> intent CASHIER_ONLY, nextStep `CLAIM_INVITATION`; and intent CASHIER_ONLY, choose OWNER -> intent OWNER, nextStep `START_FREETIER`. RED today (entity throws -> 409).
2. `ownerWithPendingBusinessCannotSwitchToCashier`: pending user, intent OWNER, pending tenant present, choose CASHIER_ONLY -> `OperationConflictException`, intent stays OWNER. Guard for D2: passes today because of the old lock, kept deliberately so the GREEN change cannot open this hole (orchestrator decision).
3. `pendingBusinessAcceptsOwnerIntent`: pending user, intent CASHIER_ONLY, pending tenant present, choose OWNER -> intent OWNER, nextStep `COMPLETE_BUSINESS_PROFILE`, pendingTenantId = tenant id. RED today.
4. `activeAccountCannotChangeIntent`: ACTIVE user (`createCashier`), choose OWNER -> `OperationConflictException`. Guard for D1 (passes today, kept deliberately).
- RED evidence (orchestrator): `mvnw.cmd -q test -Dtest=OnboardingIntentServiceTest` -> cases 1 and 3 fail with `OperationConflictException`.
  - Recorded 2026-11-25: 5 run, 4 errors. Case 1 (both rows) and case 3: `OperationConflictException: Jalur pendaftaran tidak dapat diubah.` (expected). Case 2: `UnnecessaryStubbingException` because today the entity throws before the tenant lookup; GREEN must look up the tenant, then it passes. Case 4 passes (guard).
- GREEN evidence (orchestrator): same command + `-Dtest=OnboardingIntentServiceTest,FreeTierOnboardingServiceTest` -> passes.
  - Recorded 2026-11-25 after T1 fixture fix (user id set via ReflectionTestUtils; service passes `user.getId()`): `mvnw.cmd -q test -Dtest=OnboardingIntentServiceTest,FreeTierOnboardingServiceTest` exit 0. UI: `tsc -b` 0, oxlint no new warnings, vitest 17 files / 138 tests pass.
