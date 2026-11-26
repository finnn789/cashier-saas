# TASK-101 F1 report

Status: DONE (not compiled, not run)

## Changed files (all modified, none new)
- E:\Java\cashier-saas-ui\src\types\outlet.ts (`CatalogCopyResult`)
- E:\Java\cashier-saas-ui\src\api\outlet.api.ts (`useCopyCatalogMutation`; on success invalidates `categoryKeys.all`, `productKeys.all`, `inventoryKeys.all`)
- E:\Java\cashier-saas-ui\src\api\mock\outlet.mock.ts (`copyCatalog`: 400 same outlet, 404, 409 inactive, 409 on second copy to the same target, fixed counts)
- E:\Java\cashier-saas-ui\src\pages\outlets\OutletsPage.tsx ("Salin menu" row action with Copy icon, Modal with Select, warning, inline 400/409 message, toast with counts)
- E:\Java\cashier-saas-ui\src\language\id.ts, en.ts (`outletsPage.toast.catalogCopied`, `outletsPage.copyModal`, `outletsPage.table.copyCatalog`)

## Packet items
- Action per outlet, modal, source Select (other outlets), warning, pending state, counts toast, inline server message: done.
- Query invalidation for category/product/inventory (option groups live under `inventoryKeys.all`): done.
- Copy action shown only for active outlets when more than one outlet exists. The page is owner-only already.

## Deviations / notes
- Source Select lists all other outlets (including inactive/over-limit ones); the server decides validity.
- `recipeLinesSkipped` is shown in the toast only when greater than 0. `categoriesCreated/Reused` are returned but not displayed.
- 402 handling in `showError` is unchanged (not expected from this endpoint).

## Callers outside my paths that may break
None. `outlet.api.ts` now imports `product.api.ts` and `inventory.api.ts` (key factories only); no cycle found.

## Open questions
None. No credential/outlet requirement missing: the target outlet id is in the path, the tenant comes from the JWT.

Tests: NOT RUN (policy)
