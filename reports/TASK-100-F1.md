# TASK-100 F1 report

Status: DONE (not compiled; tests NOT RUN (policy))

## Changed files (UI repo E:\Java\cashier-saas-ui)
New:
- src\types\purchase.ts (types + `previewLineTotal`, preview only)
- src\api\purchase.api.ts (`usePurchasesQuery` infinite/"muat lagi", `usePurchaseQuery`, `useCreatePurchaseMutation`)
- src\api\mock\purchase.mock.ts (in-memory, replay by key, 409/400 messages; stock is updated through `mockInventoryAdapter.recordMovement`)
- src\pages\purchases\PurchasesPage.tsx
- src\pages\purchases\components\PurchaseFormModal.tsx
- src\pages\purchases\components\PurchaseDetailModal.tsx
Modified:
- src\routes\AppRoutes.tsx (lazy import + `/purchases` inside `OwnerRoute`)
- src\components\layout\Sidebar.tsx (one `ownerOnly` item)
- src\language\id.ts, src\language\en.ts (`nav.purchases`, `purchasesPage` block)

## Packet items
- List (date, supplier, lines, total, actor, load more), detail modal, form: done.
- Form: date default today (max today), supplier, note, lines with item picker (`useInventoryItemsQuery`, already-chosen items hidden), quantity labelled with item unit, unit cost, live line/total PREVIEW (integer cents). Request carries no total.
- Idempotency key generated once per form open (form content mounted only while open) and reused on retry.
- Margin warnings (distinct) shown after save; otherwise the modal closes with a toast. Invalidates `inventoryKeys.all` and `purchaseKeys.all`.
- Errors via `getErrorMessage` in toast (400/404/409 messages from the server).
- Owner only: route under `OwnerRoute`, sidebar `ownerOnly`.
- Money via `formatCurrency`; copy in both language files.

## Review fix
- PurchaseFormModal.tsx line 105: `<AlertTriangleclassName=` fixed to `<AlertTriangle className=` (slip from my icon rename edit). Re-scanned all files under src\pages\purchases for missing-space JSX attributes: none left. The other edited files (routes, sidebar, language, api, mock, types) were not touched by a rename edit.

## Deviations / notes
- Sidebar uses `iconName: 'shopping_cart'` (Material Symbols), matching the existing nav items, not lucide.
- `previewLineTotal` lives in `src/types/purchase.ts` so page and mock share one helper.
- Date strings use the browser's local date (`en-CA`), not the tenant time zone; the server enforces the real rule.
- If a 4xx leaves the user editing the form, the same key is resent; a changed payload after a request that actually succeeded would get 409 (by design of the contract).
- Detail modal flashes the error text for one frame while closing (query disabled when id becomes null).

## Callers outside my paths that may break
None (only additive edits to routes, sidebar, language).

## Open questions
None. No place missing a required credential/token/outlet: outlet comes from `user?.outletId || getLastOutletSelection()?.outletId`, throws "Outlet belum dipilih" when absent.

Tests: NOT RUN (policy)
