# TASK-093: Role gating, cashier landing page and i18n cleanup (frontend)

Status: PREPARED, NOT DELEGATED (waiting for the user's go-ahead). UI repo is not git: take a baseline copy of `src/` into the session scratchpad before delegating.

## User instruction (2026-11-24)
From the readiness audit (item 4): cashiers can see pages whose endpoints are owner-only, and about 100 Indonesian strings sit outside the language files. Fix gating and i18n. The audit's "dashboard says today but shows all-time" claim is WRONG (the backend defaults to today when no dates are sent): do not change dashboard data logic.

## Verified facts (audit, source read)
- Backend: `/merchant/**` = OWNER only, `/cashier/**` = OWNER + CASHIER (`SecurityConfiguration.java:66-69`; project convention).
- UI gating today: only `/outlets` and `/reports` are owner-only (`OwnerRoute` in `src/routes/AppRoutes.tsx`; Sidebar `ownerOnly` flag, role check `hasRole(['OWNERMERCHANT'])`). `/dashboard`, `/products`, `/stock`, `/subscription` are open to CASHIER and the Sidebar shows them to every role.
- `DASHBOARD_PATH` is the same for all roles (`src/api/auth.api.ts:15`). `DashboardPage.tsx:20` uses `useFnbSalesReportQuery` (OWNER only), so a cashier lands on an error banner.
- Sidebar hardcodes `'ID: KMB-001'` and `'Pak Mbo'`.
- Hardcoded Indonesian strings outside `src/language` (approximate regex counts, lower bounds): `StockPage` ~41, `OnboardingPage` 1, `AuthShell` 1, `CartPanel` 2, `ProductFormPanel` 1, `outletStatus.ts` 1. `StockPage` also hardcodes default reorder level 500 and initial quantity 1000 in the create-item form (:50-51). Skip `CategoriesTab`, `OptionGroupsTab` and `SubscriptionPage`: they are their own tasks.

## Pre-delegation checklist (orchestrator, read-only)
1. For each route in `AppRoutes.tsx`, list the backend endpoints its page calls (follow the api hooks) and whether they are `/merchant` (OWNER) or `/cashier` (both). Decide per page: OWNER only / both / needs a split. Expect: dashboard (sales report) OWNER; products and stock may use `/cashier` for reads and `/merchant` for writes: check each hook.
2. Decide the cashier landing path (recommended: `/pos`) and where `DASHBOARD_PATH` is consumed (login, select-outlet, redirects).
3. Get the exact list and line ranges of hardcoded strings for the pages above and check which already have keys in `id.ts`/`en.ts`.
4. Confirm what `useAuth()` exposes for the Sidebar user block (name, role, outlet/store name) so the hardcoded block can be replaced by real data.
Paste results into a "Verified matrix" section here.

## Design decisions (orchestrator, user may override)
- D1 One source of truth for role gating: reuse `OwnerRoute` + `ownerOnly`; add a small `allowedRoles` only if the matrix needs a page that is open to both with owner-only sections. No new gating framework.
- D2 CASHIER landing = `/pos` (via `DASHBOARD_PATH` becoming role-aware or the login redirect); OWNER landing unchanged. Owner-only routes redirect a cashier to `/pos`.
- D3 Pages that mix reads (both roles) and writes (owner): keep the page for both, hide write actions for CASHIER (buttons not rendered), never call `/merchant` endpoints for a cashier.
- D4 Sidebar user block shows the real name, role label and outlet from `useAuth()`; remove `KMB-001` and `Pak Mbo`.
- D5 i18n: move every listed string to `id.ts` and `en.ts`; no new keys for strings that already have one; `StockPage` defaults (500/1000) become named constants in the page file (comment why), not literals in JSX.
- Out of scope: dashboard data logic, Subscription/Categories/OptionGroups pages, new pages, backend changes, MANAGER role.

## Wave 1

### F1 (`fe-worker`)
Reserved (UI repo): `src/routes/AppRoutes.tsx`, `src/components/layout/Sidebar.tsx`, `src/components/layout/Header.tsx` (only if the user block lives there), `src/api/auth.api.ts` (only `DASHBOARD_PATH` / redirect logic), `src/pages/stock/**`, `src/pages/dashboard/**` (only the cashier error path, if the matrix says dashboard is owner-only: redirect instead of showing an error), `src/pages/products/ProductsPage.tsx` and `src/pages/products/components/ProductFormPanel.tsx` (only gating and the single hardcoded string), `src/pages/pos/components/CartPanel.tsx` (2 strings), `src/pages/auth/**` and `src/pages/onboarding/**` (the 1-string fixes), `src/pages/outlets/outletStatus.ts`, `src/language/id.ts`, `en.ts`.
Report: `reports/TASK-093-F1.md`.
Acceptance:
- A CASHIER logging in lands on `/pos`, sees no owner-only nav item, and cannot open owner-only routes by URL.
- No `/merchant` call is made while logged in as CASHIER (grep of hooks used by the cashier-visible pages).
- Sidebar shows real user data; grep for `KMB-001` and `Pak Mbo` returns nothing.
- No Indonesian literal left in the listed files; both language files have every new key.
- `tsc -b` and `oxlint` clean on touched files.
