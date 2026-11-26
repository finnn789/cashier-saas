# TASK-103 F1 report

Status: DONE (tests NOT RUN (policy); tsc/oxlint not run, orchestrator to run).

## Changed files (UI repo, E:\Java\cashier-saas-ui\src)
New: `constants/permissions.ts` (Permission union, ROLE_PERMISSIONS, `can(role, permission)`).
Modified: `context/AuthContext.tsx` (`can`), `routes/AppRoutes.tsx` (`PermissionRoute` replaces `OwnerRoute`, redirect to `landingPathFor(role)`), `components/layout/Sidebar.tsx` (`permission` replaces `ownerOnly`), `pages/products/ProductsPage.tsx`, `pages/products/components/{ProductFormPanel,CategoriesTab,OptionGroupsTab}.tsx`, `pages/stock/StockPage.tsx`, `pages/pos/PosPage.tsx`, `pages/pos/components/{PaymentModal,DiscountModal}.tsx`, `pages/transactions/TransactionsPage.tsx`, `pages/transactions/components/TransactionDetailModal.tsx`, `pages/settings/components/SettingsForm.tsx`, `types/settings.ts`, `api/mock/settings.mock.ts`, `api/product.api.ts`, `api/inventory.api.ts`, `api/purchase.api.ts`, `api/fnbOrder.api.ts`, `language/id.ts`, `language/en.ts`.

## Items
- D4 permission module and `AuthContext.can`: done. Extra permission names beyond the matrix (DASHBOARD_VIEW, REPORT_VIEW, OUTLET_MANAGE, STAFF_MANAGE, SETTINGS_MANAGE, SUBSCRIPTION_MANAGE, ORDER_REFUND, TRANSACTION_EXPORT, ORDER_DISCOUNT_UNCAPPED) are owner-only and exist so every old OWNERMERCHANT gate maps to a `can(...)`.
- Routes: CASHIER reaches /pos, /stock, /transactions, /profile, /products (CATALOG_EDIT), /purchases (PURCHASE_RECORD); others redirect to /pos. Sidebar matches.
- Products: add-product button needs CATALOG_PRICE; price Input read-only with Lock icon and helper for CASHIER; recipe section hidden and recipe query disabled (no /merchant call) without RECIPE_MANAGE; product delete button needs CATALOG_ARCHIVE; cost/margin columns hidden without RECIPE_MANAGE. Categories: archive button needs CATALOG_ARCHIVE. Option groups: archive group needs CATALOG_ARCHIVE; "add option" row needs CATALOG_PRICE; availability toggle stays for both (price sent unchanged).
- Stock: create item = INVENTORY_ITEM_CREATE; stock opname and ADJUSTMENT movement type = STOCK_ADJUST; cost column = INVENTORY_ITEM_MANAGE. Restock/Waste for both.
- Purchases: page open to both; margin warnings already render only when present.
- POS discount: `canDiscount={can('ORDER_DISCOUNT')}`; DiscountModal shows note "Diskon kasir dibatasi oleh owner" without ORDER_DISCOUNT_UNCAPPED; server 400 already shown inline.
- Transactions: CSV = TRANSACTION_EXPORT, refund = ORDER_REFUND.
- Settings: new field `cashierMaxDiscountPct` (0..100, step 0.01, empty = undefined), type, mock, id/en copy.
- D5 paths switched to `/cashier/...`: category create/update, product update, bulk availability PUT, inventory item create, attach option groups (both places), option group create/update, option update, purchases (list/detail/create), order discount. Left on `/merchant`: product create/delete, option create, option "delete" (PUT unavailable, owner-only archive flow), group delete + its detach PUT, categories archive, recipes, item update/deactivate, stock counts.

## Deviations / notes
- The Edit button for existing options' name/price was not added (no such UI existed); only availability toggle exists.
- Unit-cost input on RESTOCK in StockPage left visible to cashier (not in packet).
- No `/merchant` call is made from cashier-visible actions, except `useDeleteOptionGroupMutation`'s detach PUT, which is only reachable through the owner-only archive button.

## Grep: gating OWNERMERCHANT checks outside permissions.ts
Remaining `OWNERMERCHANT` hits are only role label displays (Sidebar:217, OutletSwitcherModal:185, SelectOutletPage:109-110, ProfilePage:16, ProfileSummaryCard:15), session/token parsing (`api/session.ts`, `auth.api.ts:121`), mocks and `types/auth.ts`. `hasRole` remains defined in AuthContext but has no callers.

## Callers outside reserved paths that may break
None known. `AuthContextType` gained `can`; any test mocking `useAuth` without `can` would fail (none checked under tests/).

## Open questions
- Cashier `/products` list shows the server's `costPrice`; hidden in UI, confirm server omits it.

## Credential/outlet checks
None missing; all hooks keep the outlet guard.
