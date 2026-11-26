# TASK-110 F1 report

Status: DONE (tests NOT RUN, orchestrator runs GREEN)

## Changed files (all modified)
- E:\Java\cashier-saas-ui\src\pages\onboarding\OnboardingPage.tsx
- E:\Java\cashier-saas-ui\src\language\id.ts (`onboarding.changeRole`, `onboarding.intent.back`)
- E:\Java\cashier-saas-ui\src\language\en.ts (same keys)

## Packet items
- `changingIntent` local state in `OnboardingPage` (declared before early returns): done. When true and step is profile/cashier, renders `IntentStep` with `initialIntent={status.intent}` and `onCancel`; `onDone` stores status in `ONBOARDING_STATUS_KEY` and resets the flag.
- `IntentStep`: optional `initialIntent` and `onCancel` props (Back ghost button): done. AuthAlert for 409 unchanged.
- "Change role" ghost button (new small `ChangeRoleButton`, same style as `LogoutLaterButton`) in `ProfileStep` (hidden when `status.pendingTenantId` set) and `CashierWaitingStep`: done.
- Strings in both language files: done.
- `src/api/mock/onboarding.mock.ts`: NOT changed; its `chooseIntent` already accepts any intent.

## Deviations
None.

## Callers outside my paths that may break
None (`ProfileStep`/`CashierWaitingStep`/`IntentStep` are file-local).

## Open questions
- Mock `chooseIntent` always returns `START_FREETIER` even for CASHIER_ONLY, so in mock mode switching to Cashier lands on the profile step; pre-existing behavior, left alone.
- Server in D2 rejects CASHIER_ONLY after a pending tenant exists; UI hides the button in that case, so no further handling.

## Credentials/token/outlet gaps
None.

## Tests
NOT RUN (orchestrator runs GREEN)
