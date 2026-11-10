# TASK-064 W3 report

Status: DONE (not compiled or tested by policy).

## Changed files
- `src/main/java/org/lite/project/cashiersaas/dto/FnbMenuMapper.java`: `menu(...)` 4th parameter is now `Map<UUID, List<OptionGroupEntity>> groupsByProduct`; per product uses `groupsByProduct.getOrDefault(product.getId(), List.of())` (order preserved). Added imports `java.util.Map`, `java.util.UUID`. Rest of the response unchanged.
- `src/main/java/org/lite/project/cashiersaas/service/FnbTableService.java`: `publicMenu` builds a `LinkedHashMap<UUID, List<OptionGroupEntity>>` from `catalog.groups(tenantId, outletId, product.getId())` per product and loads `catalog.options(...)` once per distinct group id via a `HashSet<UUID>` (`seenGroups.add`). Added imports `HashSet`, `LinkedHashMap`, `Map`, `Set`. `ArrayList` import still used (options list).

## Evidence
Re-read for compile errors: no use of `OptionGroupEntity.getProduct()` remains in either file; lambda variable `g` no longer clashes. No Maven/build run (policy).

## Deviations
None. Existing `FnbMenuMapperTest` still passes the old list and is expected to be fixed by TASK-065.
