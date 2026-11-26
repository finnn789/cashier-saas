# TASK-101: Copy the menu from one outlet to another (owner)

Status: PREPARED, NOT DELEGATED (memory v100; the user will delegate). J1 (backend) and F1 (UI) can run IN PARALLEL against the contract below, and in parallel with TASK-103 (disjoint files; see "Parallel rule"). Backend branch: working tree `feature/leakage-report`, not committed. UI repo is not git: the orchestrator copies `E:/Java/cashier-saas-ui/src` to the scratchpad as a baseline before delegating.

## User instruction (2026-11-25)
"Saya maunya harga menu bisa berbeda per outlet ... silahkan buatkan." Prices already differ per outlet (products are outlet-scoped), but a new outlet starts with an empty catalog. The owner copies a whole menu once, then edits each outlet's prices independently.

## Verified facts (orchestrator, source read 2026-11-25)
- Everything is outlet-scoped (`tenant_id` + `outlet_id`): `CategoryEntity`, `ProductEntity` (own `price`), `OptionGroupEntity`, `ProductOptionEntity` (`option_group_id`, `priceAdjustment`), `ProductOptionGroupEntity` (`product_id`, `option_group_id`, display order), `InventoryItemEntity`, `RecipeLineEntity` (`product_id` OR `product_option_id`, `inventory_item_id`). So a recipe can only point to inventory items of the same outlet: copying recipes requires copying the inventory item masters too.
- No copy logic exists (`OutletService` has none). Token is bound to one outlet: `TenantPrincipal.requireOutlet(id)` rejects any other outlet id, so `MerchantAccessGuard.require*` cannot be used for the source/target. Tenant-level owner pattern to reuse: `OutletService` — `merchantAccessGuard.requireOwnerIgnoringAllowance(principal, principal.getOutletId())` then `outletRepository.findInTenant(principal.getTenantId(), outletId)` (404 if not in the tenant).
- Entity factories used by `FnbCatalogService` (`createCategory` :51, `createProduct` :77, `createGroup` :112, `createOption` :166, `replaceProductGroups` :149 via `ProductOptionGroupEntity.create`, recipes :193/:207) and `FnbInventoryService.createItem` :61 are the creation paths to reuse (factories, not the guarded service methods).
- Outlet write lock exists for operational writes (per-outlet lock, TASK-083; e.g. `orders.lockOutlet(tenantId, outletId)` in `FnbDiscountService`). Audit: `AuditLogEntity.Action` + `recordAction`.
- UI: `src/pages/outlets/OutletsPage.tsx` (owner-only page: list/create/update/(de)activate), `src/api/outlet.api.ts` + `src/api/mock/outlet.mock.ts`; product/category/option-group queries in `src/api/product.api.ts` / `src/api/inventory.api.ts`.

## Contract (FINAL)
`POST /api/v1/merchant/outlets/{targetOutletId}/catalog/copy` (OWNER, tenant level), body `FnbCatalogCopyRequest { sourceOutletId: UUID (required) }`.
- Both outlets must belong to the principal's tenant (else 404). `source == target` -> 400 "Outlet sumber dan tujuan harus berbeda.". Target must be active (else 409 "Outlet tujuan tidak aktif."); allowance is not enforced (copy is setup).
- Target must have NO products, NO option groups and NO inventory items (archived ones count) -> else 409 "Outlet tujuan sudah memiliki menu atau bahan.". Existing target categories are reused by exact name (case-insensitive, trimmed); missing ones are created.
- Copied (non-archived source rows only): categories; products (name, description, price, imageUrl, available, displayOrder, category mapped); option groups (name, required, min/max, displayOrder) with their options (name, priceAdjustment, available, displayOrder); product <-> group links with order; active inventory items (name, sku, unit, reorderLevel) with ZERO stock and NO cost and no movements; recipe lines of copied products and options mapped to the copied items (lines whose item is inactive in the source are skipped and counted).
- Not copied: stock, average cost, movements, orders, shifts, payment labels, purchases, stock counts, business settings.
- One transaction, target outlet write lock taken first; one audit row `CATALOG_COPIED("OUTLET")` (entityId = target outlet id, details `source=<id> categories=.. products=.. optionGroups=.. options=.. inventoryItems=.. recipeLines=..`).
- Response `FnbCatalogCopyResponse { categoriesCreated, categoriesReused, products, optionGroups, options, inventoryItems, recipeLines, recipeLinesSkipped }` (ints). Message "Menu berhasil disalin.".
- A repeat call after success returns the 409 above (that is the idempotency guard).

## Design decisions (orchestrator; user may override)
- D1 New `controller/FnbCatalogCopyController.java` + `service/FnbCatalogCopyService.java` + DTOs `dto/request/FnbCatalogCopyRequest.java`, `dto/response/FnbCatalogCopyResponse.java`; read/persist methods added to `repository/FnbCatalogRepository.java` and `repository/FnbInventoryRepository.java` (bulk reads per table, no per-row queries: no N+1). Do NOT edit `FnbCatalogService`, `FnbInventoryService`, `FnbCatalogController`, `FnbInventoryController` (TASK-103 owns them).
- D2 Owner only; cashier never sees the action.
- D3 UI: in `OutletsPage`, each outlet row gets a "Salin menu" action (lucide `Copy` icon) that opens a `Modal`: `Select` "Salin dari outlet" (other outlets of the tenant), warning text (what is and is not copied, target must be empty), confirm `Button` with pending state; on success a toast with the counts and invalidation of the category/product/option-group/inventory queries; 409/400 server message inline.

## Wave 1 (parallel)

### J1 (`java-worker`)
Reserved (under `src/main/java/org/lite/project/cashiersaas/`): `controller/FnbCatalogCopyController.java`, `service/FnbCatalogCopyService.java`, `dto/request/FnbCatalogCopyRequest.java`, `dto/response/FnbCatalogCopyResponse.java` (new); `repository/FnbCatalogRepository.java`, `repository/FnbInventoryRepository.java` (additions only); `entity/shared/audit/AuditLogEntity.java` (one enum value). Entity classes only if a needed factory is missing (report it). No migration expected; if one is needed, stop and report.
Report: `reports/TASK-101-J1.md`.
Acceptance: contract exactly; tenant isolation (404 for another tenant's outlet, no existence leak); no N+1; target emptiness rule enforced inside the lock; AGENTS.md DTO/code rules.

### F1 (`fe-worker`)
Reserved (UI repo): `src/pages/outlets/**`, `src/api/outlet.api.ts` (new `useCopyCatalogMutation`), `src/api/mock/outlet.mock.ts` (mock copy), `src/types/outlet.ts`, `src/language/id.ts`, `en.ts` (`outletsPage` block only).
Report: `reports/TASK-101-F1.md`.
Acceptance: owner can copy from another outlet and sees the counts; errors show the server message; after copy, switching to the target outlet shows the copied menu; no literal copy. The orchestrator runs `tsc -b`/`oxlint`; do not run tests.

## Parallel rule (with TASK-103)
- Backend: TASK-101 J1 must not edit any file reserved by TASK-103 J1 (`FnbCatalogService`, `FnbCatalogController`, `FnbInventoryService`, `FnbInventoryController`, `MerchantAccessGuard`, discount and business-settings files). TASK-103 J1 must not edit `FnbCatalogRepository`, `FnbInventoryRepository`, `AuditLogEntity`.
- UI: `src/language/id.ts` / `en.ts` are shared: small `Edit` calls inside your own block only, never `Write` the whole file; on a failed Edit re-read and retry.
