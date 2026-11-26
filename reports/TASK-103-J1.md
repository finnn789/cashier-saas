# TASK-103 J1 report

Status: DONE (backend), test status NOT RUN (policy). Baseline: working tree on `feature/leakage-report` at memory v100.

## Changed files
New: `enums/Permission.java` (CATALOG_EDIT, CATALOG_PRICE, INVENTORY_ITEM_CREATE, STOCK_MOVE, STOCK_ADJUST, PURCHASE_RECORD, ORDER_DISCOUNT; `allows(Role)`), `db/migration/V17__cashier_discount_cap.sql`.
Modified: `MerchantAccessGuard` (+`require(principal, outletId, Permission)`: resolve + allows else 404), `FnbCatalogController`, `FnbCatalogService`, `FnbInventoryController`, `FnbInventoryService`, `FnbDiscountController` (class mapping now `/api/v1`, full paths per method), `FnbDiscountService`, `TenantEntity` (`cashierMaxDiscountPct`, `changeCashierMaxDiscount`), `MerchantBusinessSettingsRequest/Response/Mapper/Service`.

## Guard calls changed (requireOwner -> require)
- FnbCatalogService: createCategory, updateCategory, createGroup, updateGroup, replaceProductGroups, updateProduct, updateOption -> CATALOG_EDIT. updateProduct/updateOption throw 400 "Kasir tidak dapat mengubah harga." (`requirePriceRole`, via CATALOG_PRICE) when the price differs and the role lacks CATALOG_PRICE.
- FnbInventoryService: createItem -> INVENTORY_ITEM_CREATE; recordMovement -> STOCK_ADJUST (ADJUSTMENT) / STOCK_MOVE (others); purchase POST, purchase GET, purchases list -> PURCHASE_RECORD. Purchase POST `marginWarnings` only set when `showCost(context)` (owner).
- FnbDiscountService.grant -> ORDER_DISCOUNT; non-owner capped by `requireWithinCap` ("Diskon kasir maksimal {cap}%."; nominal with subtotal 0 is over the cap).
- Unchanged owner-only: createProduct, createOption, archives, recipes, updateItem, deactivateItem, stockCount.

## Contract points
- All second `/cashier/...` mappings added on the same methods; `/merchant` kept.
- Settings: `cashierMaxDiscountPct` in request (optional, null keeps), response, audit before/after JSON. Service message for invalid values as specified; Bean Validation (0..100, 2 dp) triggers first for out-of-range input, same as targetMarginPct.

## Deviations
None. Note: `MerchantAccessGuard.require` enforces outlet allowance like `requireOutlet`/`requireOwner`.

## Tests likely needing updates (not edited)
- Mocks stubbing `guard.requireOwner` for the changed methods: `FnbInventoryServiceTest`, `FnbInventoryCostAndStockCountTest`, `FnbCatalogRecipeServiceTest` (only for non-recipe methods), `FnbOptionGroupServiceTest`, any `FnbDiscountService` test, `FnbInventoryFlowIntegrationTest`; `MerchantBusinessSettingsServiceTest` (response/audit JSON gains `cashierMaxDiscountPct`; `Values` record grew).
- Mocked `MerchantAccessGuard` returns need `require(..., Permission)` stubs.
- Existing tests asserting a CASHIER gets 404 on purchases/discount/category/etc. now change behaviour.

## Open questions
None.
