# TASK-103: RBAC permission matrix — CASHIER may manage catalog (no prices), stock, purchases and capped discounts

Status: PREPARED, NOT DELEGATED (memory v100; the user will delegate). J1 (backend) and F1 (UI) can run IN PARALLEL against the fixed contract below. Backend branch: working tree `feature/leakage-report` (TASK-094 J1, 096, 097, 098, 100 are uncommitted there too). UI repo is not git: the orchestrator copies `E:/Java/cashier-saas-ui/src` to the session scratchpad as a baseline before delegating.

## User instruction (2026-11-25)
"Di WEB APP saya belum menggunakan implementasi RBAC, belum dikontrol USER-nya berdasarkan role-nya ... buatkan perancangan fitur design-nya, sesuaikan dengan component dari FE, dan role CASHIER itu bisa mengontrol stock jualan, katalog, mencatat transaksi; pokoknya feature yang di OWNERMERCHANT dibataskan untuk role CASHIER." Decisions (AskUserQuestion, 2026-11-25): catalog without price changes; stock = restock, waste, purchases (+ new inventory items), opname/adjustment/cost stay owner; cashier discount with an owner-set % cap; fixed matrix per role (no per-cashier table).

## Permission matrix (canonical copy: `.ai/ARCHITECTURE.md` "RBAC permission matrix")
| Permission | OWNER | CASHIER | Covers |
|---|---|---|---|
| CATALOG_EDIT | yes | yes | create/rename category; edit existing product non-price fields (name, description, category, image, available, displayOrder); create/edit option group; edit existing option non-price fields + availability; attach option groups to a product |
| CATALOG_PRICE | yes | no | create product, create option (both set a price), change product `price`, change option `priceAdjustment` |
| CATALOG_ARCHIVE | yes | no | archive category / product / option group |
| RECIPE_MANAGE | yes | no | read/write product and option recipes |
| INVENTORY_ITEM_CREATE | yes | yes | create inventory item |
| INVENTORY_ITEM_MANAGE | yes | no | update / deactivate inventory item |
| STOCK_MOVE | yes | yes | RESTOCK, WASTE movements |
| STOCK_ADJUST | yes | no | ADJUSTMENT movement, stock count |
| PURCHASE_RECORD | yes | yes | record purchase, list/detail purchases |
| ORDER_DISCOUNT | yes | capped | discount an unpaid order; cashier only up to `cashierMaxDiscountPct` |
| (all other `/merchant` features) | yes | no | refund, reports, leakage, margins, audit, staff, invitations, business settings, outlets, subscription, payment-label management, shift force-close |

## Verified facts (orchestrator, source read 2026-11-25)
- Roles: `enums/Role { SUPERADMIN, OWNERMERCHANT, CASHIER }`. `SecurityConfiguration` (:66-69): `/api/v1/merchant/**` -> `hasRole(OWNERMERCHANT)`, `/api/v1/cashier/**` -> `hasAnyRole(OWNERMERCHANT, CASHIER)`. AccessDenied and the guard both answer 404 for non-owners.
- `service/MerchantAccessGuard`: `requireOutlet(principal, outletId)` (membership + usable outlet), `requireOwner(...)` (same + role must be OWNERMERCHANT, else `ResourceNotFoundException`), `requireOwnerIgnoringAllowance(...)`. The membership role is `context.getMembership().getRole()`.
- Catalog: `controller/FnbCatalogController` uses a full path per method (no class `@RequestMapping`): writes under `/merchant/outlets/{outletId}/fnb/...`, reads under `/cashier/...`. `service/FnbCatalogService` write methods all call `guard.requireOwner`: `createCategory` :51, `updateCategory` :58, `archiveCategory` :65, `createProduct` :77, `updateProduct` :86, `archiveProduct` :99, `createGroup` :112, `updateGroup` :120, `archiveGroup` :128, `replaceProductGroups` :149, `createOption` :166, `updateOption` :175, recipes :193-216.
- DTOs: `FnbProductRequest` {categoryId, name, description, price, imageUrl, available, displayOrder, priceChangeReason} (full replace PUT); `FnbOptionRequest` {name, priceAdjustment (required), available, displayOrder, priceChangeReason}; `FnbCategoryRequest` {name, displayOrder}; `FnbOptionGroupRequest` {name, required, minimumSelections, maximumSelections, displayOrder}. Price changes are already audited (`FNB_PRODUCT_PRICE_CHANGED`, `FNB_OPTION_PRICE_CHANGED`).
- Inventory: `controller/FnbInventoryController` — item create/update/delete and stock counts and purchases under `/merchant`, item list + movements under `/cashier`. `service/FnbInventoryService`: `createItem` :61, `updateItem` :83, `deactivateItem` :96 (`requireOwner`); `recordMovement` :116 (ADJUSTMENT -> `requireOwner`, else `requireOutlet`); `stockCount` :155; `purchase` POST :245, GET :309, list :316 (`requireOwner`). `showCost(context)` (:390) hides cost data from non-owners; the purchase POST sets `marginWarnings`.
- Discount: `controller/FnbDiscountController` has class `@RequestMapping("/api/v1/merchant/outlets/{outletId}/fnb/orders")`; `service/FnbDiscountService.grant` (:34): `requireOwner`, once per order, PERCENTAGE <= 100, amount = NOMINAL value or subtotal * pct / 100 (HALF_UP, 2 dp).
- Business settings: tenant-level `targetMarginPct` pattern (`MerchantBusinessSettingsRequest` optional `@DecimalMin 0 @DecimalMax 99.99`, `MerchantBusinessSettingsService` :62 `tenant.changeTargetMargin`, :89 validation, :160 audit JSON, mapper `MerchantBusinessSettingsMapper` :25). Next Flyway: **V17**.
- UI role checks that gate access (to replace): `routes/AppRoutes.tsx:32` `OwnerRoute`, `components/layout/Sidebar.tsx:146` `ownerOnly` filter, `pages/pos/PosPage.tsx:269` `canDiscount`, `pages/stock/StockPage.tsx:27` `isOwner`, `pages/transactions/TransactionsPage.tsx:183` (CSV), `pages/transactions/components/TransactionDetailModal.tsx:28` (refund). Role LABEL displays (`SelectOutletPage`, `ProfilePage`, `ProfileSummaryCard`, `OutletSwitcherModal`, Sidebar user block) stay. `AuthContext` exposes `hasRole(roles)`.
- UI hooks that call owner paths for actions now open to cashier: `src/api/product.api.ts` (category create/update, product update, `useBulkUpdateStatusMutation` = product PUT), `src/api/inventory.api.ts` (`useCreateInventoryItemMutation`, `useAttachProductOptionGroupsMutation`, option group create/update, option update), `src/api/purchase.api.ts` (all), `src/api/fnbOrder.api.ts` `useGrantDiscountMutation`.
- UI components to reuse: `components/common` (`Button`, `Badge`, `Modal`, `Input`, `Select`, `Table`, `Pagination`), `components/feedback` (`EmptyState`, `LoadingSpinner`), `useToast`, lucide icons, `cn`; pages `ProductsPage` + `ProductFormPanel` + `CategoriesTab` + `OptionGroupsTab` + `ProductOptionGroupsSection`, `StockPage`, `PurchasesPage` + `PurchaseFormModal`, `PaymentModal` + `DiscountModal`, `TransactionDetailModal`, `SettingsPage`.

## Contract (FINAL; J1 and F1 build to it)
Backend, every path below is ADDED as a second mapping on the SAME controller method (e.g. `@PutMapping({"/merchant/outlets/{outletId}/fnb/products/{id}", "/cashier/outlets/{outletId}/fnb/products/{id}"})`); the old `/merchant` paths keep working for the owner. Prefix `/api/v1`.
| Method + `/cashier/outlets/{outletId}/fnb/...` | Permission | Server rule for CASHIER |
|---|---|---|
| `POST categories`, `PUT categories/{id}` | CATALOG_EDIT | — |
| `PUT products/{id}` | CATALOG_EDIT | if `price` differs (`compareTo != 0`) from the stored price -> 400 "Kasir tidak dapat mengubah harga." |
| `POST option-groups`, `PUT option-groups/{id}` | CATALOG_EDIT | — |
| `PUT products/{productId}/option-groups` | CATALOG_EDIT | — |
| `PUT option-groups/{groupId}/options/{id}` | CATALOG_EDIT | if `priceAdjustment` differs -> 400 "Kasir tidak dapat mengubah harga." |
| `POST inventory/items` | INVENTORY_ITEM_CREATE | response without cost (existing `showCost`) |
| `POST inventory/purchases`, `GET inventory/purchases`, `GET inventory/purchases/{purchaseId}` | PURCHASE_RECORD | `marginWarnings` omitted for non-owners |
| `POST orders/{orderId}/discount` | ORDER_DISCOUNT | effective % = PERCENTAGE value, or NOMINAL value / subtotal * 100 (subtotal 0 -> over the cap); if > `cashierMaxDiscountPct` -> 400 "Diskon kasir maksimal {cap}%." (cap printed without trailing zeros) |
Owner-only, unchanged paths: `POST products`, `POST option-groups/{groupId}/options`, every `DELETE` (archive), recipes, `PUT/DELETE inventory/items/{id}`, `POST inventory/stock-counts`, refund, reports, settings, staff, outlets, subscription, audit, payment-label management.
Existing `/cashier` movement endpoint: RESTOCK/WASTE = STOCK_MOVE, ADJUSTMENT = STOCK_ADJUST (behaviour unchanged, now via permissions).
Business settings: `GET|PUT /merchant/outlets/{outletId}/business-settings` gains `cashierMaxDiscountPct` (number, 0..100, 2 dp; PUT optional like `targetMarginPct`: null keeps the current value; invalid -> 400 "Batas diskon kasir harus antara 0 dan 100 dengan maksimal dua angka desimal."). Changes are included in the existing business-settings audit details.
Denied permission anywhere = 404 (`ResourceNotFoundException`), same as today.

## Design decisions (orchestrator; user may override)
- D1 Backend: `enums/Permission` enum where each value holds its allowed roles (e.g. `CATALOG_EDIT(Role.OWNERMERCHANT, Role.CASHIER)`) and `boolean allows(Role)`; `MerchantAccessGuard.require(TenantPrincipal, UUID outletId, Permission)` = `requireOutlet` + `allows` check (404). Replace `requireOwner` with `require(..., Permission.X)` only in the methods listed in the contract and in `recordMovement`; leave every other `requireOwner` call as it is. No Spring method-security annotations, no per-user table.
- D2 V17 `V17__cashier_discount_cap.sql`: `ALTER TABLE tenants ADD COLUMN cashier_max_discount_pct numeric(5,2) NOT NULL DEFAULT 0` + CHECK `0 <= x <= 100`. `TenantEntity` field + a named change method (like `changeTargetMargin`).
- D3 The cap check lives in `FnbDiscountService.grant` (owner uncapped). Price-change rejection lives in `FnbCatalogService.updateProduct` / `updateOption` for principals without CATALOG_PRICE.
- D4 UI permission module `src/constants/permissions.ts`: `type Permission` (same names, camel or SCREAMING as you prefer, one style), `ROLE_PERMISSIONS: Record<UserRole, Permission[]>`, `can(role, permission)`. `AuthContext` adds `can(permission)`. `AppRoutes`: `PermissionRoute permission=...` replaces `OwnerRoute` (redirect to `landingPathFor(role)`); Sidebar items get `permission` instead of `ownerOnly`. Replace the 6 gating checks listed in the facts with `can(...)`; keep role label displays.
- D5 UI hooks for actions open to both roles call the `/cashier/...` paths for EVERY role (same backend method, so the owner is unaffected); owner-only actions keep `/merchant/...`.
- D6 Out of scope: MANAGER role, per-cashier permissions, PIN, audit log viewer for cashier, cashier discount hint from the server (the 400 message is the hint), backend tests (policy).

## UI design (component-aligned; F1 implements)
Navigation (Sidebar, `components/layout/Sidebar.tsx`):
```
OWNER  : Dashboard | POS | Transaksi | Produk | Stok | Pembelian | Laporan | Outlet | Staff | Pengaturan | Langganan
CASHIER: POS | Transaksi | Produk | Stok | Pembelian            (+ role Badge "Kasir" in the user block, already there)
```
Routes: CASHIER allowed `/pos`, `/transactions`, `/products`, `/stock`, `/purchases`, `/profile`; everything else -> `PermissionRoute` redirect to `/pos`.

Products page (`ProductsPage`, tabs Produk | Kategori | Grup pilihan):
```
[Produk]  toolbar: Search ... [+ Tambah produk]  <- OWNER only (CATALOG_PRICE)
 row: Nama | Kategori | Harga | Tersedia (toggle, both roles) | ⋯ menu: Edit (both) · Arsipkan (OWNER)
 ProductFormPanel (edit):
   Nama, Deskripsi, Kategori, Gambar, Urutan       -> editable for both
   Harga  [Rp 15.000] 🔒  helper "Hanya owner yang dapat mengubah harga"  -> read-only Input for CASHIER
   Tab/section Resep & HPP                          -> hidden for CASHIER (RECIPE_MANAGE)
   Grup pilihan (ProductOptionGroupsSection)        -> both
[Kategori] (CategoriesTab): [+ Kategori] and rename for both; Arsipkan button OWNER only
[Grup pilihan] (OptionGroupsTab): create/edit group for both; Arsipkan OWNER only;
   options list: availability toggle + rename for both; price field read-only 🔒 for CASHIER; [+ Opsi] OWNER only
```
Stock page (`StockPage`): item list (no cost columns for CASHIER, server already omits cost); `[+ Bahan baru]` both (INVENTORY_ITEM_CREATE); row actions `Restock` / `Waste` both; `Adjustment`, `Edit bahan`, `Nonaktifkan`, `[Stok opname]` OWNER only.
Purchases page (`PurchasesPage`): list, detail and `[Catat pembelian]` for both; margin-warning block renders only when the response has warnings (owner).
POS (`PaymentModal` -> `DiscountModal`): `[Beri diskon]` shown for both roles; for CASHIER the modal shows a neutral note "Diskon kasir dibatasi oleh owner" (no number); a 400 shows the server message inline in the modal.
Transactions: CSV export and Refund stay OWNER (now via `can`).
Settings page (`SettingsPage`, OWNER): new field "Batas diskon kasir (%)" next to target margin (0..100, 2 dp, helper "0 = kasir tidak dapat memberi diskon").
States: every hidden action is not rendered (no disabled ghost buttons) except the price Input, which is read-only with the lock icon so the cashier sees the price. All copy in `id.ts` and `en.ts`.

## Wave 1 (parallel)

### J1 (`java-worker`)
Reserved (under `src/main/java/org/lite/project/cashiersaas/` unless noted): `enums/Permission.java` (new), `service/MerchantAccessGuard.java`, `controller/FnbCatalogController.java`, `service/FnbCatalogService.java`, `controller/FnbInventoryController.java`, `service/FnbInventoryService.java`, `controller/FnbDiscountController.java`, `service/FnbDiscountService.java`, `entity/shared/merchant/TenantEntity.java`, `dto/request/MerchantBusinessSettingsRequest.java`, `dto/response/MerchantBusinessSettingsResponse.java`, `dto/MerchantBusinessSettingsMapper.java`, `service/MerchantBusinessSettingsService.java`, `src/main/resources/db/migration/V17__cashier_discount_cap.sql` (new). Do not touch `SecurityConfiguration` (the existing `/cashier/**` rule is enough) and do not touch files of other uncommitted tasks outside this list.
Report: `reports/TASK-103-J1.md` (list each changed guard call; list unit tests whose mocks/assertions will need updates, e.g. guard `requireOwner` stubs).
Acceptance:
- Every row of the contract works for CASHIER on the `/cashier` path and still for OWNER on both paths; every owner-only action returns 404 for CASHIER.
- Cashier price change and over-cap discount return the stated 400 messages; owner is unaffected.
- `cashierMaxDiscountPct` round-trips through business settings and is audited.
- AGENTS.md rules (named DTO classes, `ApiResponse`, no wildcard generics, no `@Deprecated`, no new abstractions beyond D1).

### F1 (`fe-worker`)
Reserved (UI repo): `src/constants/permissions.ts` (new), `src/context/AuthContext.tsx` (add `can`), `src/routes/AppRoutes.tsx`, `src/components/layout/Sidebar.tsx`, `src/pages/products/**`, `src/pages/stock/**`, `src/pages/purchases/**`, `src/pages/pos/components/PaymentModal.tsx`, `src/pages/pos/components/DiscountModal.tsx`, `src/pages/pos/PosPage.tsx` (only `canDiscount`), `src/pages/transactions/**` (only the two gating checks), `src/pages/settings/**` (the new field), `src/types/settings.ts`, `src/api/settings.api.ts` + `src/api/mock/settings.mock.ts` (new field), `src/api/product.api.ts`, `src/api/inventory.api.ts`, `src/api/purchase.api.ts`, `src/api/fnbOrder.api.ts` (paths of the shared actions only, D5), matching mocks only if they key on the path, `src/language/id.ts`, `en.ts`.
Report: `reports/TASK-103-F1.md` (include a grep showing no gating `OWNERMERCHANT` checks remain outside `permissions.ts` and role-label displays).
Acceptance:
- CASHIER sees exactly the nav above; typing an owner-only URL redirects to `/pos`.
- CASHIER can edit a product (not its price), toggle availability, manage categories and option groups (no archive, no new product/option, no recipe), create an inventory item, restock/waste, record a purchase, and open the discount modal; owner sees everything as before.
- No `/merchant` call is made by a CASHIER session (shared actions use `/cashier`).
- `tsc -b` and `oxlint` clean (run by the orchestrator). Do not run tests.
