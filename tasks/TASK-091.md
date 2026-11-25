# TASK-091: Categories and Option Groups tabs wired to the backend (frontend)

Status: PREPARED, NOT DELEGATED (waiting for the user's go-ahead). UI repo `E:\Java\cashier-saas-ui` is not git: take a baseline copy of `src/` into the session scratchpad before delegating.

## User instruction (2026-11-24)
From the readiness audit (item 2): the Categories and Option Groups tabs in Products are fake seed data; changes vanish on refresh. Wire them to the real catalog API. Hooks already exist.

## Verified facts (audit, source read)
- `src/pages/products/components/CategoriesTab.tsx`: `INITIAL_CATEGORIES` hardcoded at :11-16, loaded with `useState` at :20, no api import, no persistence. ~14 hardcoded Indonesian strings.
- `src/pages/products/components/OptionGroupsTab.tsx`: `INITIAL_GROUPS` at :25-70+, `useState` at :112-113, no api import. ~25 hardcoded Indonesian strings.
- Existing but UNUSED hooks: `src/api/product.api.ts:139-244` (`useCategoriesQuery`, `useCreateCategory`, `useUpdateCategory`, `useArchiveCategory`); `src/api/inventory.api.ts:367-663` (option group + option CRUD, `useOutletOptionGroups`, `useAttachProductOptionGroups`, `useOptionRecipe`, `useUpdateOptionRecipe`). The POS already uses `useOptionGroupsQuery`.
- Backend: `FnbCatalogController` (`src/main/java/org/lite/project/cashiersaas/controller/FnbCatalogController.java:23-143`) serves categories, products, option groups, recipes. Option groups are outlet-level and reusable across products (migration V5); categories are dynamic per tenant. Everything under `/merchant/outlets/{outletId}/...` is OWNER only; cashiers only read through `/cashier/...`.
- `/products` page is currently reachable by CASHIER (role gating is a separate task; do not change it here).

## Pre-delegation checklist (orchestrator, read-only; do before writing the final contract into this packet)
1. Read `FnbCatalogController` + request/response DTOs for: category create/update/archive/reorder, option group + option create/update/archive, attach/detach groups to a product, option recipe. Record exact fields, validation (lengths, min/max choices, price delta), and error codes.
2. Record archive rules: archiving a category that still has products; archiving an option group attached to products; reactivation.
3. Diff those DTOs against `src/types/*` used by the hooks; list drift to fix.
4. Open both tabs and `ProductFormPanel.tsx` and list every mock-only feature that has no hook/backend support (those get REMOVED, not faked).
5. Check that `ProductFormPanel` uses real category and option-group ids (not mock ids) when saving a product.
Paste results into a "Verified contract" section here.

## Design decisions (orchestrator, user may override)
- D1 Categories tab: list from `useCategoriesQuery`; create, rename, archive via the existing hooks; show product count only if the backend response provides it. No local seed.
- D2 Option Groups tab: list outlet groups with their options; create/edit group (name, min/max selection, required) and options (name, price delta, availability); archive; attach/detach to products only if a hook + UI entry already exists in `ProductFormPanel`; otherwise leave attach where it is.
- D3 Server is the source of truth: mutations invalidate the list queries; show server messages (`getErrorMessage`) for 400/409; loading, error and empty states per tab; submit buttons show pending state.
- D4 Idempotency: these endpoints take no Idempotency-Key unless the checklist shows otherwise.
- D5 Cleanup in touched files only: all copy into `t.products...` in BOTH `id.ts` and `en.ts`; `cn` from `@/utils/cn`; lucide icons; shared `Modal`; labelled inputs.
- Out of scope: product form redesign, bulk operations, recipe editing UI beyond existing hooks, role gating, backend changes.

## Wave 1

### F1 (`fe-worker`)
Reserved (UI repo): `src/pages/products/components/CategoriesTab.tsx`, `OptionGroupsTab.tsx` (and new small components in the same folder if needed), `src/api/product.api.ts` and `src/api/inventory.api.ts` ONLY to fix type/contract bugs in the category and option-group hooks (list the changes in the report), `src/api/mock/*` for those hooks if mock mode lacks them, `src/types/*` category/option types, `src/language/id.ts` and `en.ts` (products block only).
Report: `reports/TASK-091-F1.md`.
Acceptance:
- No `INITIAL_*` seed data or hardcoded copy left in the two tabs.
- Create, edit, archive persist after a page refresh (real API) and work in mock mode.
- Server errors (400/409) are shown; loading/error/empty states exist.
- `tsc -b` and `oxlint` clean on touched files (orchestrator runs them).
