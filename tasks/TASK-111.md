# TASK-111: Tidy secondary actions on register-success and onboarding screens

Status: DONE (reviewed). Memory version: 104. UI repo `E:\Java\cashier-saas-ui` (not git). Backend untouched.

## User instruction (2026-11-25)
"Delegasikan lagi ke FE worker dan perbaiki UI-nya" (after TASK-110 added the change-role and logout-later buttons).

## Global rules (every worker)
- Report: `reports/TASK-111-F1.md` (backend repo).
- Not test-first: layout/styling only, no behaviour change (AGENTS.md TDD exception). Existing vitest suite must stay green.
- Reuse: `AuthShell` `footer` slot (`src/pages/auth/AuthShell.tsx:252-259`, already used by `LoginPage.tsx:95` and `RegisterPage.tsx:168`; copy their footer markup/style), existing `Button`, existing language keys.

## Verified facts (current UI after TASK-110)
- `src/pages/onboarding/OnboardingPage.tsx`:
  - `IntentStep` (change mode): form, then a ghost "Kembali" button, then a ghost `LogoutLaterButton`, stacked full-width under the primary button.
  - `ProfileStep`: after submit, ghost `ChangeRoleButton` (hidden when `status.pendingTenantId`) + ghost `LogoutLaterButton`, stacked full-width.
  - `CashierWaitingStep`: outline full-width `backToLogin` button (does `logout()` + navigate `/login`, i.e. the same action as `LogoutLaterButton`) + ghost `ChangeRoleButton`.
  - `LogoutLaterButton` and the success-page logout have no pending state; a double click sends two `/auth/logout` calls.
- `src/pages/auth/RegisterSuccessPage.tsx`: primary "Lanjutkan atur usaha" + outline full-width "Keluar, lanjutkan nanti"; no stepper.
- Result: 2-3 full-width buttons of similar weight below each form; the primary action does not stand out and logout appears twice on the cashier screen.

## Orchestrator design decisions (reversible)
- D1: Each screen keeps exactly one full-width primary action inside the card. Secondary actions move to the `AuthShell` `footer` as one compact row of text links, styled like the existing Login/Register footer links: "Ganti peran" on the left (only where it applies), "Keluar, lanjutkan nanti" on the right; single centered link when only logout applies. Must wrap cleanly at 360px width.
- D2: `IntentStep` in change mode: "Kembali" becomes a small text link with a left-arrow icon (lucide `ArrowLeft`) ABOVE the radio group, not a bottom button. Logout goes to the footer per D1.
- D3: `CashierWaitingStep`: remove the duplicate outline `backToLogin` button; logout is the footer link per D1. Keep the claim form and its primary button. Drop the `backToLogin` key from both language files only if nothing else uses it (grep first).
- D4: `RegisterSuccessPage`: keep the primary button; the logout becomes the footer link per D1.
- D5: Logout links are disabled while logout is running (local `useState` pending flag), no new hook.
- D6: Keyboard/a11y: links are real `<button type="button">`, visible focus ring as the existing footer links, icons `aria-hidden`.

## F1 - UI (`fe-worker`)
Reserved:
- `src/pages/onboarding/OnboardingPage.tsx`
- `src/pages/auth/RegisterSuccessPage.tsx`
- `src/language/id.ts`, `src/language/en.ts` (only removing an unused key or adding a missing one)
Do: implement D1-D6 with the smallest diff; one small shared footer-row component local to `OnboardingPage.tsx` is fine, export it for `RegisterSuccessPage` only if that avoids duplicate markup. Do not change `AuthShell`.
Acceptance: no behaviour change (same API calls and navigation); `tsc -b`, `npm run lint` (no new warnings), `npm run test` green - run by orchestrator.
