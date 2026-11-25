# TASK-099 F1 report

Status: DONE (not compiled or linted, policy).

## Changed files
New: E:\Java\cashier-saas-ui\src\pages\products\components\ProductOptionGroupsSection.tsx
Modified:
- E:\Java\cashier-saas-ui\src\pages\products\components\ProductFormPanel.tsx (renders the section, 2 lines)
- E:\Java\cashier-saas-ui\src\api\inventory.api.ts (mock branch of attach mutation only)
- E:\Java\cashier-saas-ui\src\api\mock\inventory.mock.ts (PRODUCT_GROUP_IDS key, attachProductOptionGroups, getOptionGroups prefers attached ids)
- E:\Java\cashier-saas-ui\src\language\id.ts, en.ts (products.formPanel.groups* keys)

## Items
- D1 done: checkbox list (name, required/optional, min-max, option count), preselected from useOptionGroupsQuery, up/down buttons for selected rows (selected shown first in order).
- D2 done: own "Simpan grup pilihan" button, pending state, success toast, server message toast on error. New product shows the "save first" hint.
- D3 done: mock persists ids per product in localStorage; getOptionGroups returns them in order (legacy productId filter when no attachment).
- D4 done: empty state pointing to Option Groups tab. Copy in both languages.

## Deviations / notes
- Section is rendered for create mode too (productId null) to show the hint; queries are disabled then.
- Mock outlet groups list is not product-scoped, so mock attach can attach any group.

## Callers outside paths that may break
None expected; getOptionGroups mock behaviour is unchanged unless an attachment exists.

## Open questions / credentials
None. Outlet comes from the existing hook (throws if missing, unchanged).

Tests: NOT RUN (policy).
