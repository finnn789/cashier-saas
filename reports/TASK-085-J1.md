# TASK-085 J1 report

Status: DONE (implementation complete, not compiled or tested).
Baseline: commit 20faa7a + uncommitted TASK-084 working tree.
Test status: NOT RUN (policy).

## Changed files
New:
- src/main/resources/db/migration/V14__target_margin.sql (tenants.target_margin_pct numeric(5,2) NOT NULL DEFAULT 60.00, CHECK 0 <= x < 100)
- src/main/java/org/lite/project/cashiersaas/service/FnbMenuMarginService.java
- src/main/java/org/lite/project/cashiersaas/controller/FnbMenuMarginReportController.java
- src/main/java/org/lite/project/cashiersaas/dto/response/FnbMenuMarginResponse.java
- src/main/java/org/lite/project/cashiersaas/dto/response/FnbMenuMarginReportResponse.java

Modified:
- entity/shared/merchant/TenantEntity.java (field targetMarginPct default 60.00, `changeTargetMargin`, null = unchanged)
- service/MerchantBusinessSettingsService.java (validation, change detection, audit details now include targetMarginPct)
- dto/request/MerchantBusinessSettingsRequest.java, dto/response/MerchantBusinessSettingsResponse.java, dto/MerchantBusinessSettingsMapper.java (the separate mapper, `targetMarginPct`)
- service/FnbStockService.java (`itemCost` now package-private static, nothing else)
- service/FnbInventoryService.java (new constructor dependency `FnbMenuMarginService margins`; restock `marginWarnings` only)
- dto/response/FnbStockMovementResponse.java (`marginWarnings`, default `List.of()`)
- FnbInventoryMapper not changed (not needed).

## Contract points
- Service for J2: `FnbMenuMarginReportResponse outletMargins(UUID tenantId, UUID outletId)` (read-only transaction, no auth/plan check, foreign outlet throws ResourceNotFoundException). Menus are all non-archived products sorted as specified; `belowTargetCount` counts BELOW_TARGET.
- REST `GET /api/v1/merchant/outlets/{outletId}/fnb/reports/menu-margins?belowTargetOnly=` -> owner (`guard.requireOwner`) + `PlanCode.PRO`; `belowTargetOnly` filters menus only, `belowTargetCount` stays the full count.
- D1 PUT `targetMarginPct` optional, 0..99.99 (bean validation plus service check), omitted = unchanged, change audited in BUSINESS_SETTINGS_CHANGED before/after JSON. GET returns it.
- D2/D3/D4/D5 implemented as written; HPP = `FnbStockService.itemCost(1, lines, balances)` (scale 2 HALF_UP in response, margin uses the unrounded cost), margin scale 2 HALF_UP, suggested price ceil to next Rp500 (scale 2).
- D6 restock: `marginWarnings` only when owner, `unitCost != null` and the average actually changed (compareTo, previous null counts as changed). Computed after `balance.receive` and `inventory.flush()` in the same transaction via `FnbMenuMarginService.restockWarnings`. Text: `Margin Ayam Geprek turun ke 52,00% (target 60,00%), saran harga Rp27.500` (id-ID formatting).
- D7 archived products excluded (`catalog.products`), unavailable included.

## Deviations / decisions
- Price = 0 with known cost: status OK, `hpp` set, `marginPct` null, no suggested price (D4 gives null margin, so BELOW_TARGET is not reachable). Orchestrator may prefer another status.
- An idempotent replay of a restock returns empty `marginWarnings` (no recomputation).
- Cost for the margin: all active recipe lines; inactive ingredients ignored, as in `itemCost`.

## Existing tests that will need updating
- `FnbInventoryServiceTest` and `FnbInventoryCostAndStockCountTest` (`@InjectMocks FnbInventoryService`): add `@Mock FnbMenuMarginService`; owner restock-with-unitCost tests that change the average would otherwise hit a null `margins`.
- `MerchantBusinessSettingsServiceTest`: any assertion on the exact audit JSON string now gets `,"targetMarginPct":"60.00"` appended (before and after).
- `MerchantBusinessSettingsMapperTest`: may assert full response fields; `targetMarginPct` is now set.

## Open questions
- None blocking. Price 0 status (see above).
