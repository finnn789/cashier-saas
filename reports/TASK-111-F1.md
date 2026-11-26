# TASK-111 F1 report

Status: DONE (code written, not run).

Modified (all under E:\Java\cashier-saas-ui):
- src\pages\onboarding\OnboardingPage.tsx
- src\pages\auth\RegisterSuccessPage.tsx
- src\language\id.ts, src\language\en.ts (removed unused `onboarding.cashier.backToLogin`; the other `auth.backToLogin` key is untouched)

Items:
- D1 done: `SecondaryActionsFooter` (exported from OnboardingPage.tsx) renders in the AuthShell `footer`. "Ganti peran" on the left (only when `onChangeRole` is passed), "Keluar, lanjutkan nanti" on the right, or one centered link. It uses flex-wrap with gap, so it wraps at 360px. Link style is text-primary, font-semibold, hover underline, plus a focus-visible outline.
- D2 done: change-mode IntentStep "Kembali" is now a text button with `ArrowLeft` (aria-hidden) above the form. The bottom buttons are removed.
- D3 done: CashierWaitingStep no longer has the outline backToLogin button or the ChangeRoleButton. Both are now in the footer. The `cashier.backToLogin` key was removed after grep showed no other use.
- D4 done: RegisterSuccessPage keeps the primary button; logout is the footer link.
- D5 done: local `useState` `loggingOut` disables the logout link while it runs (try/finally).
- D6 done: real `<button type="button">` elements with a focus ring.
- ProfileStep: change-role is hidden via `status.pendingTenantId`, same condition as before.

Deviations:
- RegisterSuccessPage imports `SecondaryActionsFooter` from `../onboarding/OnboardingPage`, which avoids duplicate markup, as the packet allows.
- CashierWaitingStep logout now awaits `logout()` before navigating (it didn't before). This matches the other logout flows.
- The footer logout link has no bottom margin tweaks. AuthShell's `mt-6` footer wrapper provides the spacing.

Callers outside my paths that may break: none found. `LogoutLaterButton` and `ChangeRoleButton` were file-local and have been removed. Check that no test queries them by role "button" with ghost styling. Tests that find "Keluar, lanjutkan nanti" or "Ganti peran" by role button still match (they are buttons). A test that looks for the cashier `backToLogin` text ("Kembali ke Masuk") would now fail. I did not grep tests/, so please check.

Open questions: none.
Missing credential/token/outlet requirements: none.
Tests: NOT RUN (orchestrator runs GREEN).
