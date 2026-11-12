# TASK-064: Reusable option groups across products (Phase 2)

Status: DELEGATED (2026-11-10). Memory version at delegation: 59 (-> 60 after this packet). Branch `feature/fnb-reusable-option-groups`, stacked on `feature/fnb-inventory-recipe` @ `d41d731` (the inventory PR is not merged yet). Dirty files at delegation: only `postman/**` and `.postman/resources.yaml` — never touch them.
Companion packets: `tasks/TASK-065.md` (tests), `tasks/TASK-066.md` (Postman). All three run in PARALLEL against the contract below; the orchestrator compiles afterwards.

## User decisions (binding, 2026-11-10)

1. Option groups become OUTLET-LEVEL (a library). A product attaches groups through a join table. "Boba" is created once and attached to many products. Option recipes (stock) are already keyed by option id, so stock sharing needs no change.
2. `required / minimumSelections / maximumSelections` live on the GROUP only (no per-product override).
3. Archiving a group that is still attached to a non-archived product -> 409. Orders keep snapshots, and `order_item_options.option_group_id` references `option_groups(id)`, so groups are ARCHIVED (soft), never hard-deleted.
4. The old product-scoped group/option/option-recipe endpoints are REPLACED (no compatibility routes, no `@Deprecated`). New CRUD is outlet-level; attachment is `PUT /products/{id}/option-groups` (replace-all, ordered).
5. Migration backfills: every existing group becomes a library group attached to its original product; `option_groups.product_id` is DROPPED.
6. Workers MUST reuse existing code (see `tasks/TASK-061.md` "Reuse rules for EVERY worker" — they apply here verbatim). New file only where listed below.

## Facts verified by the orchestrator (do not re-audit)

- Current: `option_groups.product_id` NOT NULL (V1). `OptionGroupEntity.product` is read in `FnbCatalogMapper.group` and `FnbMenuMapper` (line ~48) and `FnbCatalogService.group(...)`. `FnbOrderPricingService.selectedOptions` and `FnbTableService.publicMenu` call `catalog.groups(tenant, outlet, productId)` — that signature MUST stay and neither file is edited except `FnbTableService.publicMenu` (W3).
- Flyway: V1–V4 exist. New = `V5__reusable_option_groups.sql`. `spring.jpa.hibernate.ddl-auto=validate`, so entity columns must match the SQL exactly. `BaseEntity` supplies `id` + `createdAt` (like `RecipeLineEntity`, table `recipe_lines`); `MutableBaseEntity` adds `updatedAt` + `@Version`.
- `product_options.option_group_id` FK stays. `order_item_options.option_group_id` FK -> `option_groups(id)` stays.
- Demo seed `src/main/resources/db/demo/R__demo_seed.sql` inserts `option_groups` WITH `product_id` (4 groups: ids `...3001`..`...3004`, products `...2002`, `...2002`, `...2003`, `...2004`) — must be rewritten (W1).
- Role by path: `/merchant/**` OWNER only, `/cashier/**` OWNER+CASHIER. Guards: `MerchantAccessGuard.requireOwner/requireOutlet`.
- Exceptions: `InvalidOperationException` 400, `OperationConflictException` 409, `ResourceNotFoundException` 404.
- Existing tests that will no longer compile and are fixed by TASK-065: `FnbMenuMapperTest`, `FnbCatalogRecipeServiceTest`, `FnbInventoryFlowIntegrationTest`. Do not edit tests in this task.

## Waves (all parallel; disjoint reserved writes)

| Worker | Model | Scope |
|---|---|---|
| W1 | sonnet | Migration V5, demo seed, `OptionGroupEntity`, new `ProductOptionGroupEntity`, `FnbCatalogRepository` |
| W2 | sonnet | `FnbCatalogService`, `FnbCatalogController`, `FnbCatalogMapper`, group DTOs |
| W3 | sonnet | `FnbMenuMapper`, `FnbTableService.publicMenu` |

---

## Shared contract

### Migration `V5__reusable_option_groups.sql` (W1)
```sql
ALTER TABLE public.option_groups ADD COLUMN archived boolean NOT NULL DEFAULT false;

CREATE TABLE public.product_option_groups (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid NOT NULL,
    outlet_id uuid NOT NULL,
    product_id uuid NOT NULL,
    option_group_id uuid NOT NULL,
    display_order integer NOT NULL,
    CONSTRAINT product_option_groups_pkey PRIMARY KEY (id),
    CONSTRAINT uk_product_option_groups_product_group UNIQUE (product_id, option_group_id),
    FKs (named fk_product_option_groups_tenant/outlet/product/option_group) -> tenants/outlets/products/option_groups
);
CREATE INDEX idx_product_option_groups_group ON public.product_option_groups (option_group_id);

INSERT INTO public.product_option_groups (id, created_at, tenant_id, outlet_id, product_id, option_group_id, display_order)
SELECT gen_random_uuid(), now(), tenant_id, outlet_id, product_id, id, display_order FROM public.option_groups;

ALTER TABLE public.option_groups DROP COLUMN product_id;   -- drops its FK automatically
```
Verify the real parent table names in V1/V4 before writing FKs. Do not edit V1–V4. `archived` keeps `DEFAULT false` (fine for `validate`).

### Entities (package `org.lite.project.cashiersaas.entity.shared.catalog`) (W1)
`OptionGroupEntity` (edit): REMOVE the `product` field. Add `@Column(nullable = false) private boolean archived;` + `public void archive() { this.archived = true; }` (Lombok `@Getter` gives `isArchived()`). New factory signature `public static OptionGroupEntity create(TenantEntity tenant, OutletEntity outlet, Details details)`. `Details` record and `update(Details)` unchanged. Group `displayOrder` stays (library order).

`ProductOptionGroupEntity` (NEW, `extends BaseEntity`, `@Entity @Table(name = "product_option_groups")`, style of `RecipeLineEntity`): `tenant`, `outlet`, `product` (ProductEntity), `optionGroup` (OptionGroupEntity) — all `@ManyToOne(LAZY, optional=false)` with `@JoinColumn(name=..., nullable=false)`; `@Column(name = "display_order", nullable = false) private int displayOrder;`. Factory `public static ProductOptionGroupEntity create(TenantEntity tenant, OutletEntity outlet, ProductEntity product, OptionGroupEntity optionGroup, int displayOrder)`. Q-class `QProductOptionGroupEntity` is generated at compile.

### Repository `FnbCatalogRepository` (W1) — keep every existing method; changes/additions only:
```
Optional<OptionGroupEntity> group(UUID tenant, UUID outlet, UUID id)          // now also filters archived = false
List<OptionGroupEntity> groups(UUID tenant, UUID outlet, UUID productId)       // SIGNATURE UNCHANGED; joins product_option_groups, archived = false, ORDER BY link.displayOrder asc, group.name asc
List<OptionGroupEntity> outletGroups(UUID tenant, UUID outlet)                 // NEW: archived = false, ORDER BY displayOrder asc, name asc
boolean groupInUse(UUID tenant, UUID outlet, UUID groupId)                     // NEW: true if a link exists whose product has archived = false
void deleteProductGroups(UUID tenant, UUID outlet, UUID productId)             // NEW: removes the product's links then entityManager.flush() (same style as FnbInventoryRepository.deleteProductRecipe)
void persist(ProductOptionGroupEntity link)                                    // NEW
```
Every query filters tenant AND outlet. Use the existing Blaze/Querydsl style (entity join with Blaze `innerJoinOn(...)` or a subquery is the worker's choice; one query, no N+1). `lock(OptionGroupEntity)` unchanged.

### DTOs (W2)
- `dto/request/FnbOptionGroupRequest` unchanged.
- `dto/request/FnbProductOptionGroupsRequest` (NEW): `@NotNull @Size(max = 20) private List<UUID> groupIds;` (`@Getter @Setter`).
- `dto/response/FnbOptionGroupResponse`: REMOVE `productId`; keep `id, name, required, minimumSelections, maximumSelections, displayOrder`.

### Service `FnbCatalogService` (W2) — signatures (all take `TenantPrincipal principal, UUID outletId` first)
```
FnbOptionGroupResponse createGroup(principal, outletId, FnbOptionGroupRequest request)              // owner
FnbOptionGroupResponse updateGroup(principal, outletId, UUID id, FnbOptionGroupRequest request)       // owner
FnbOptionGroupResponse archiveGroup(principal, outletId, UUID id)                                     // owner; groupInUse -> OperationConflictException("Grup pilihan masih dipakai produk.")
List<FnbOptionGroupResponse> groups(principal, outletId)                                              // outlet; outletGroups
List<FnbOptionGroupResponse> productGroups(principal, outletId, UUID productId)                       // outlet; product() check then catalog.groups
List<FnbOptionGroupResponse> replaceProductGroups(principal, outletId, UUID productId, FnbProductOptionGroupsRequest request)
FnbOptionResponse createOption(principal, outletId, UUID groupId, FnbOptionRequest request)           // owner
FnbOptionResponse updateOption(principal, outletId, UUID groupId, UUID id, FnbOptionRequest request)  // owner
List<FnbOptionResponse> options(principal, outletId, UUID groupId)                                    // outlet
FnbRecipeResponse replaceOptionRecipe(principal, outletId, UUID groupId, UUID id, FnbRecipeRequest request)  // owner
FnbRecipeResponse optionRecipe(principal, outletId, UUID groupId, UUID id)                            // owner
```
Rules: `replaceProductGroups` = owner guard, `product()` check, reject null/duplicate ids (`InvalidOperationException("Grup pilihan tidak boleh duplikat.")`), load every id with the private `group(tenant, outlet, id)` (unknown / other tenant / other outlet / archived -> 404), then `catalog.deleteProductGroups`, persist one `ProductOptionGroupEntity` per id with `displayOrder = index`, return the groups in request order. Empty list clears. Private `group(tenant, outlet, id)` = `catalog.group(...).orElseThrow(ResourceNotFoundException::new)` (no product ownership check any more). Private `option(tenant, outlet, groupId, id)` keeps the "option belongs to groupId" check. `validateGroup` and `groupDetails` reused. Remove the old productId-bearing group/option/recipe methods entirely. Product-recipe methods unchanged.
Mapper `FnbCatalogMapper.group` drops `setProductId`.

### Controller `FnbCatalogController` (W2) — routes (`/api/v1` prefix; replace the old `/products/{productId}/groups...` routes completely)
| Method + path | Service |
|---|---|
| `POST /merchant/outlets/{outletId}/fnb/option-groups` (201) | createGroup |
| `PUT /merchant/outlets/{outletId}/fnb/option-groups/{id}` | updateGroup |
| `DELETE /merchant/outlets/{outletId}/fnb/option-groups/{id}` | archiveGroup |
| `GET /cashier/outlets/{outletId}/fnb/option-groups` | groups |
| `PUT /merchant/outlets/{outletId}/fnb/products/{productId}/option-groups` | replaceProductGroups |
| `GET /cashier/outlets/{outletId}/fnb/products/{productId}/option-groups` | productGroups |
| `POST /merchant/outlets/{outletId}/fnb/option-groups/{groupId}/options` (201) | createOption |
| `PUT /merchant/outlets/{outletId}/fnb/option-groups/{groupId}/options/{id}` | updateOption |
| `GET /cashier/outlets/{outletId}/fnb/option-groups/{groupId}/options` | options |
| `PUT` and `GET /merchant/outlets/{outletId}/fnb/option-groups/{groupId}/options/{id}/recipe` | replaceOptionRecipe / optionRecipe |
Messages in Indonesian like the existing ones ("Grup pilihan dibuat.", "Grup pilihan diarsipkan.", "Grup pilihan produk diperbarui.", ...). `@Valid @RequestBody`, `ApiResponse.success/created`, `@ResponseStatus(HttpStatus.CREATED)` on creates. No `ResponseEntity`.

### Public menu (W3)
`FnbMenuMapper.menu(TableQrTokenEntity qr, List<CategoryEntity> categories, List<ProductEntity> products, Map<UUID, List<OptionGroupEntity>> groupsByProduct, List<ProductOptionEntity> options)` — 4th parameter changes from a flat list to a map keyed by product id (a group may now appear under several products). For a product use `groupsByProduct.getOrDefault(product.getId(), List.of())` (order preserved). Options are still matched to a group by `option.getOptionGroup().getId()`; the `options` list MUST NOT contain duplicates.
`FnbTableService.publicMenu`: build the map with `catalog.groups(tenantId, outletId, product.getId())` per product (use `LinkedHashMap`) and load `catalog.options(...)` ONCE per DISTINCT group id (track seen ids) — otherwise a shared group's options are duplicated. Nothing else in `FnbTableService` changes. W3 edits no other file.

---

## W1 reserved writes
- `src/main/resources/db/migration/V5__reusable_option_groups.sql` (new)
- `src/main/resources/db/demo/R__demo_seed.sql` (edit the option_groups INSERT: drop `product_id`; then add `INSERT INTO product_option_groups (id, created_at, tenant_id, outlet_id, product_id, option_group_id, display_order) ... ON CONFLICT DO NOTHING` with fixed UUIDs for the same 4 attachments; keep it idempotent and re-runnable on a DB that already ran V5's backfill — the unique `(product_id, option_group_id)` makes `ON CONFLICT DO NOTHING` skip duplicates; do not change any other seed content)
- `entity/shared/catalog/OptionGroupEntity.java`, `entity/shared/catalog/ProductOptionGroupEntity.java` (new)
- `repository/FnbCatalogRepository.java`
- `reports/TASK-064-W1.md`

## W2 reserved writes
- `service/FnbCatalogService.java`, `controller/FnbCatalogController.java`, `dto/FnbCatalogMapper.java`
- `dto/request/FnbProductOptionGroupsRequest.java` (new), `dto/response/FnbOptionGroupResponse.java`
- `reports/TASK-064-W2.md`

## W3 reserved writes
- `dto/FnbMenuMapper.java`, `service/FnbTableService.java`
- `reports/TASK-064-W3.md`

## Acceptance (orchestrator verifies)
- `mvnw compile` clean; diff vs `d41d731` limited to reserved paths; mechanical greps (no record DTOs, no wildcards, no `ResponseEntity`, no `@Deprecated`, no new SHA-256 helper).
- No remaining reference to `OptionGroupEntity.getProduct()`, `/products/{productId}/groups`, or the old productId-bearing service methods anywhere in `src/main`.
- Migration V5 verified on a throwaway PostgreSQL 17 that already has V1–V4 data with groups (backfill produces one link per old group), then Flyway on the full suite (`mvnw verify` after TASK-065).
- Not in scope / known gaps: group response does not list which products use it; no audit-log row for attach/detach; per-product group overrides.

## Worker rules recap
Inherit no conversation. Write ONLY your reserved paths. Do NOT run Maven or any build/test. No git add/commit/push. Never edit `.ai/*`, `tasks/*`, `postman/**`. Re-read your code for compile errors (imports, Lombok, Q-class names). Update your `reports/TASK-064-<W>.md` at each checkpoint and at the end: changed files, acceptance progress, evidence, remaining work, deviations. If the contract conflicts with real code, STOP and report instead of improvising outside your reserved paths.
