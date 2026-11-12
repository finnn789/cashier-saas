# TASK-064 W2 report

Status: DONE (code written, not compiled by policy; orchestrator compiles).

## Changed files
- service/FnbCatalogService.java: outlet-level createGroup/updateGroup/archiveGroup/groups/productGroups/replaceProductGroups; createOption/updateOption/options/replaceOptionRecipe/optionRecipe now take groupId only; private `group(tenant, outlet, id)` and `option(tenant, outlet, groupId, id)` reworked (no product ownership). New imports ProductOptionGroupEntity, OperationConflictException.
- controller/FnbCatalogController.java: old `/products/{productId}/groups...` routes replaced by the contract routes table (11 routes incl. recipe GET/PUT).
- dto/FnbCatalogMapper.java: `group` drops setProductId.
- dto/response/FnbOptionGroupResponse.java: productId removed.
- dto/request/FnbProductOptionGroupsRequest.java (new): `@NotNull @Size(max=20) List<UUID> groupIds`.

## Acceptance progress
- archiveGroup -> 409 "Grup pilihan masih dipakai produk." when catalog.groupInUse.
- replaceProductGroups: owner guard, product() check, null/duplicate -> InvalidOperationException, each group loaded via group() (404 if unknown/archived), deleteProductGroups, persist links with displayOrder=index, response in request order.
- Product recipe, category, product methods untouched.

## Evidence
- Grep: no `getProduct()`, no `products/{productId}/groups` left in the three files.
- Relies on W1 contract: OptionGroupEntity.create(tenant, outlet, details), archive(), ProductOptionGroupEntity.create(...), FnbCatalogRepository.outletGroups/groupInUse/deleteProductGroups/persist(ProductOptionGroupEntity).
- Tests/build: not run by policy.

## Remaining / deviations
- Remaining: orchestrator compile. Callers of old service signatures elsewhere (tests, other services) were not edited; tests are TASK-065.
- Deviation: none. Note: archiveGroup performs 404 check before in-use check; no pessimistic lock on group (contract did not ask).
