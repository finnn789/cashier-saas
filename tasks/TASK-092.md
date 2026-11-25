# TASK-092: Staff / cashier invite page and business settings page (frontend)

Status: PREPARED, NOT DELEGATED (waiting for the user's go-ahead). UI repo is not git: take a baseline copy of `src/` into the session scratchpad before delegating.

## User instruction (2026-11-24)
From the readiness audit (item 3): the backend has staff, invite and business-settings endpoints but the UI has no api file and no page. Add them. This supports the Free to Pro model: the "Undang kasir" button on Free shows a Pro offer instead of an error.

## Verified facts (audit, source read)
- Backend (`src/main/java/org/lite/project/cashiersaas/controller/`): `MerchantStaffController.java:25-43` (list, deactivate, reactivate, password reset), `CashierInvitationController.java:26` (create invite) and `:33` (claim), `MerchantBusinessSettingsController.java:26,32` (GET/PUT; includes `targetMarginPct`, default 60, range 0 <= x < 100, audited). Staff/settings routes are `/merchant/**` = OWNER only (`SecurityConfiguration.java:66-69`).
- PRD Appendix A.1: Free plan "Undang kasir" shows a Pro offer (not an error); a cashier is deactivated until the plan is Pro again.
- UI: no `staff`, `invitation` or `settings` api file and no page exists. `ProfileForms.tsx:17,47` has disabled "coming soon" stubs (account and password forms) that are NOT part of this task.
- Reusable UI infra: `OwnerRoute` in `src/routes/AppRoutes.tsx`, Sidebar `ownerOnly` flag, `useLocale()`, `Modal`, `EmptyState`, `LoadingSpinner`, `useToast`, `getErrorMessage`, `unwrapApiResponse`, `apiClient`.

## Pre-delegation checklist (orchestrator, read-only; paste results into a "Verified contract" section)
1. For every endpoint above: method + path, request DTO fields + validation, response DTO fields, 400/402/404/409 codes and messages, role guard.
2. Invitation flow: how an invite is created (phone? email? link or code?), expiry, how the cashier claims it (public route or authenticated?), what the cashier sees after claiming, whether a claim page already exists in the UI router.
3. How the Free plan blocks invites (status and message; PlanUpgradeRequired 402?). Is there a cashier-count limit per plan?
4. Business settings: full field list (tax, service charge, receipt text, margin target, ...) and which are editable.
5. Whether staff list response includes role, active flag, last login.

## Design decisions (orchestrator, user may override)
- D1 New route `/staff` (OWNER only via `OwnerRoute`, Sidebar item `ownerOnly`): list staff with status, invite button, deactivate/reactivate, password reset, all with confirm modals and server error messages.
- D2 Invite: form + the result shown exactly as the backend returns it (link or code with copy button). A 402 on Free opens an inline upgrade card (link `/subscription`), not a toast.
- D3 New route `/settings` (OWNER only): form over the GET/PUT fields found in the checklist; `targetMarginPct` with its bounds; save pending state; unsaved-change guard not required.
- D4 If a public claim route is required and missing, add it under `src/pages/auth/` and register it in `AppRoutes.tsx` OUTSIDE the protected layout, following the pattern of the existing login/register routes. Never store tokens in localStorage beyond what existing auth code already does.
- D5 Every hook has an `env.apiMode === 'mock'` branch with a small in-memory mock.
- D6 All copy in BOTH `id.ts` and `en.ts` (new blocks `staffPage`, `settingsPage`, nav keys); `cn` from `@/utils/cn`; lucide icons; labelled inputs; loading/error/empty states.
- Out of scope: Profile page stubs, role editing, cashier permissions matrix, MANAGER role, billing.

## Wave 1

### F1 (`fe-worker`)
Reserved (UI repo): `src/pages/staff/**` (new), `src/pages/settings/**` (new), `src/api/staff.api.ts`, `src/api/settings.api.ts`, `src/api/mock/staff.mock.ts`, `src/api/mock/settings.mock.ts` (new), `src/types/staff.ts`, `src/types/settings.ts` (new), `src/routes/AppRoutes.tsx` (only the new routes), `src/components/layout/Sidebar.tsx` (only the two nav items), `src/language/id.ts`, `en.ts` (new blocks + nav keys only), `src/pages/auth/**` only if D4 applies.
Report: `reports/TASK-092-F1.md`.
Acceptance:
- Owner can list, invite, deactivate, reactivate and reset the password of staff against the real API; Free owner sees the upgrade card on 402.
- Settings page loads and saves real values; invalid margin shows the server message.
- Cashier cannot see or open `/staff` or `/settings`.
- No hardcoded copy, ids or phone numbers; `tsc -b` and `oxlint` clean on touched files.
