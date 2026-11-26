# TASK-106 F1 report

Status: DONE (code written, not compiled). Tests: NOT RUN (policy).

## Changed files (UI repo `E:\Java\cashier-saas-ui`)
New:
- `src/api/platform.api.ts`, `src/api/mock/platform.mock.ts`
- `src/pages/platform/PlatformLayout.tsx`, `PlatformOverviewPage.tsx`, `PlatformTenantsPage.tsx`, `PlatformTenantDetailPage.tsx`, `PlatformInvoicesPage.tsx`, `PlatformAuditPage.tsx`, `platformUtils.ts`
- `src/pages/platform/components/PlatformActionDialog.tsx`, `PlatformInvoiceTable.tsx`

Modified:
- `src/api/auth.api.ts` (`LoginSession` type with `platformAdmin`, `PLATFORM_LANDING_PATH`, `landingPathFor('SUPERADMIN')` -> `/platform`, `nextRouteFor` handles `nextStep: 'PLATFORM'` and platformAdmin + COMPLETE_ONBOARDING -> `/select-outlet`, new `usePlatformLoginMutation` = POST `/auth/platform`)
- `src/api/session.ts` (non-secret `isPlatformSession` / `setPlatformSession` flag in sessionStorage)
- `src/api/client.ts` (`refreshAuthSession`: for a platform session, re-calls `/auth/platform` with the fresh ACCOUNT token)
- `src/api/index.ts` (export line)
- `src/context/AuthContext.tsx` (new `platformAdmin` in context, set from login/refresh response or role SUPERADMIN, reset on clearSession)
- `src/constants/permissions.ts` (`PLATFORM_MANAGE`, only SUPERADMIN)
- `src/routes/AppRoutes.tsx` (`AreaRoute`: SUPERADMIN only reaches `/platform/*`; other roles are redirected away from it and SUPERADMIN is redirected away from the tenant app)
- `src/pages/auth/SelectOutletPage.tsx` ("Masuk sebagai Superadmin" button when `platformAdmin`)
- `src/language/id.ts`, `src/language/en.ts` (new `platform` section at the end only)

Not changed: `ProtectedRoute.tsx` (SUPERADMIN has a role, so nothing needed), `LoginPage.tsx` (routing is done by `nextRouteFor`).

## Packet items
- SUPERADMIN only on `/platform/*`, others never reach it: done (AreaRoute, server remains authority).
- Pages: overview cards, tenant table (search, plan/status filters, paging), tenant detail (subscriptions, invoices, AI usage), dialogs grant/end/AI top-up/suspend/reactivate/mark-paid/cancel each with required reason 3..500, invoice list, audit list: done. Server messages are shown as-is.
- Mock layer follows the contract: done (`mockPlatformAdapter`; mock mode shows the Superadmin entry on select-outlet).
- Refresh for platform sessions: done in `client.ts`.

## Deviations
- `src/types/auth.ts` is not reserved, so `platformAdmin` lives in a local `LoginSession = AuthResponse & { platformAdmin?: boolean }` exported from `auth.api.ts`. If the orchestrator wants it on `AuthResponse`, move it there and drop the cast in `AuthContext.rememberPlatformAdmin`.
- Audit page has no tenant filter input (the hook accepts `tenantId`); `details` is not shown, only reason.
- `platform.api.ts` has one local non-exported helper `usePlatformWrite` to avoid seven copies of the same mutation/invalidation code.
- No idempotency key: the platform write contracts define none.

## Callers outside my paths that may break
- Anything that builds an `AuthContextType` value by hand (new required `platformAdmin` field); none found in `src/`.
- Bundled `nextRouteFor` now takes `LoginSession` (superset of `AuthResponse`); existing callers (LoginPage, OnboardingPage) compile unchanged.

## Open questions
- If `/auth/refresh` does not return `platformAdmin`, the Superadmin entry on select-outlet disappears after a page reload until the next login. A backend refresh field would fix it.
- A pure admin with no tenant gets `nextStep` from J1; the UI assumes `COMPLETE_ONBOARDING` (-> `/select-outlet`) or `PLATFORM`. Confirm with J1.
- A platform session that fails the `/auth/platform` call during refresh (flag revoked) ends in logged out state; the sessionStorage flag is reset at the next login or logout.

## Credential/outlet checks
Platform endpoints send no tenant/outlet id; the token comes from the in-memory access token only. No token is stored in storage (only a boolean session marker).
