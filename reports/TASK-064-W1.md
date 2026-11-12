# TASK-064 W1 report

Status: SUBMITTED (not compiled, no tests run by policy; other workers edit dependents in parallel).

## Changed files
- `src/main/resources/db/migration/V5__reusable_option_groups.sql` (new): `option_groups.archived`, `product_option_groups` (FKs to tenants/outlets/products/option_groups, unique (product_id, option_group_id), index on option_group_id), backfill one link per old group, drop `option_groups.product_id`.
- `src/main/resources/db/demo/R__demo_seed.sql`: option_groups INSERT without `product_id` (with `archived=false`); new `product_option_groups` INSERT with fixed ids `...3101`..`...3104`, `ON CONFLICT DO NOTHING`.
- `entity/shared/catalog/OptionGroupEntity.java`: `product` removed, `archived` + `archive()`, `create(tenant, outlet, details)`.
- `entity/shared/catalog/ProductOptionGroupEntity.java` (new): per contract.
- `repository/FnbCatalogRepository.java`: `group` filters archived; `groups(tenant,outlet,productId)` joins links (archived=false, order link.displayOrder, group.name); new `outletGroups`, `groupInUse`, `deleteProductGroups`, `persist(ProductOptionGroupEntity)`.

## Evidence
- Re-read V1 (option_groups columns, parent tables `tenants/outlets/products`) and V4 style; entity columns match SQL (`archived`, `display_order`).
- Blaze style copied from `MerchantAccessRepository` (`create(em, Class).from(Entity, alias).innerJoin(path, alias).select(...)`).
- Grep of src/main: remaining `getProduct()` on group is only `FnbCatalogMapper:31` and `FnbCatalogService:247` (W2 scope).

## Remaining / risks
- Compile and Blaze query validation by the orchestrator (explicit-join queries in `groups` and `groupInUse` are the ones to watch).
- Seed: an existing DB that ran the old seed gets V5 backfill links (random ids); re-run of the seed skips them via the unique constraint.

## Deviations
None.
