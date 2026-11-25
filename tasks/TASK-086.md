# TASK-086: Tests for TASK-084 (leakage) and TASK-085 (menu margin)

Status: DELEGATED (wave 1). Branch `feature/leakage-report`. Baseline: working tree with TASK-084/085 uncommitted (all production code is final; do NOT change it). User explicitly requested testing and `mvnw verify` on 2026-11-20.

## Facts from the orchestrator's unit run (Java, `mvnw -Punit-tests test`, 726 run)
- Only `FnbInventoryCostAndStockCountTest` fails: 7 errors. (a) stock count tests: `inventory.persist(StockCountEntity/StockCountLineEntity)` hits a strict stub `persist(any(InventoryMovementEntity))` in `stubMovementIds` (line ~590) → `PotentialStubbingProblem`; `FnbInventoryService.stockCount` now also calls `inventory.stockCountByKey(...)` (returns Optional; stub to empty), `persist(StockCountEntity)`, `persist(StockCountLineEntity)`, and the response carries `stockCountId`. (b) priced restock tests (`firstPricedRestockOnAMissingBalance…`, `pricedRestockBlendsTheAverage…[2]`): `FnbInventoryService` has a new dependency `FnbMenuMarginService margins` and calls `margins.restockWarnings(tenantId, outletId, itemId, context.getTenant().getTargetMarginPct())` for an owner priced restock that changed the average; add `@Mock FnbMenuMarginService` and stub per the test's needs (the tenant's `getTargetMarginPct()` default is 60.00 in the entity).
- Integration tests (Testcontainers) were not run yet; Docker is starting.

## Global rules (every worker)
Worker agent already carries the standing rules. Extras: reports `reports/TASK-086-<id>.md`; extend existing classes and fixtures; one test per behaviour, parameterize variants; no helpers/base classes/builders; no trivial tests; never edit production code; never run Maven/pytest (orchestrator runs).

## Wave 1 (parallel)

### T1 — Java unit tests (`test-worker`)
Reserved: `src/test/java/org/lite/project/cashiersaas/unit/service/FnbInventoryCostAndStockCountTest.java`, `unit/service/FnbInventoryServiceTest.java` (only if it needs the new mock), `unit/service/MerchantBusinessSettingsServiceTest.java`, `unit/dto/MerchantBusinessSettingsMapperTest.java`, `unit/grpc/CashierInsightGrpcHandlerTest.java`, `unit/grpc/GrpcInsightMapperTest.java`, and NEW `unit/service/FnbLeakageServiceTest.java`, `unit/service/FnbMenuMarginServiceTest.java`.
Do:
1. Fix the 7 failing tests (stubs/mocks only, keep assertions' intent; additionally assert the stock count header/lines are persisted where the existing test already checks persistence).
2. `FnbLeakageServiceTest`: shortage = previous − counted; variancePct math and HALF_UP; theoretical ≤ 0 → null pct and flagged on any shortage; flag threshold boundary (exactly 5.00 not flagged, >5.00 flagged); loss = shortage × unitCost, null when unitCost null; surplus gives negative loss and is excluded from totalLoss; period start = previous count of that item (else null); waste/restock/manualAdjustment/theoretical (SALE + SALE_REVERSAL) from `inventory.movementTotals`; outletsLeakage ranking by totalLoss desc and omission of outlets without counts; consolidated range validation via `salesReports.requireValidRange`; report() 404 when no count / foreign id. Read `service/FnbLeakageService.java` and mock its collaborators.
3. `FnbMenuMarginServiceTest`: statuses NO_RECIPE (also all-inactive ingredients), COST_UNKNOWN (+ sorted distinct `missingCostItems`), BELOW_TARGET, OK, price 0 → OK with null margin; margin math; suggestedPrice rounds UP to Rp500 (parameterize); ordering (BELOW_TARGET by margin asc, then COST_UNKNOWN, NO_RECIPE, OK); `restockWarnings` only for products using the item and below target, Indonesian text; foreign outlet → 404.
4. Settings: `targetMarginPct` update (omitted = unchanged, bounds 0 ≤ x < 100 → InvalidOperationException/400 as the service does), audit JSON contains it; mapper response field.
5. gRPC: extend `CashierInsightGrpcHandlerTest`/`GrpcInsightMapperTest` for `getLeakageReport` (owner ok, cashier/non-premium denied like GetAnomalies, bad dates, range ≥ 92 days, `to` before `from`) and `getMenuMargins` (empty max → only BELOW_TARGET, max filter, invalid max → INVALID_ARGUMENT, cap 30 per outlet); mapper decimal strings and "" for null.

### T2 — Java integration tests (`test-worker`)
Reserved: `src/test/java/org/lite/project/cashiersaas/integration/fnb/FnbCostAndStockCountIntegrationTest.java` (extend; add a second class only if that file would exceed 500 lines: then `FnbLeakageAndMarginIntegrationTest.java`), `integration/grpc/*` existing insight gRPC IT (extend, find with grep `GetAnomalies`).
Do (use existing fixtures such as `ApiTestSupport`; PREMIUM is granted by default there; for the FREETIER case use the way existing ITs downgrade a plan, grep `PlanEntitlementIntegrationTest`):
1. Leakage: create item + recipe, pay orders with the recipe (cash), owner stock count with a shortage → `GET .../fnb/reports/leakage` returns expected shortage, theoretical usage, variancePct, lossAmount; stock count POST returns `stockCountId`, same key replay returns the same id; zero-difference line stored (count history `lineCount`); cashier 403 on the merchant routes; foreign tenant stockCountId 404; FREETIER 402; second count uses the previous count as period start; consolidated `GET /api/v1/merchant/reports/leakage` ranks and respects from/to.
2. Margin: set `targetMarginPct` via settings PUT then GET; owner `GET .../reports/menu-margins` shows BELOW_TARGET with suggested price and `belowTargetOnly`; owner priced restock returns `marginWarnings`, cashier restock returns none; FREETIER 402; cashier 403.
3. gRPC IT: `GetLeakageReport` and `GetMenuMargins` owner gets data, cashier/foreign tenant denied/empty (mirror the existing `GetAnomalies` cases).

### T3 — Python tests (`test-worker`)
Reserved: `Ai-services/tests/unit/test_tools.py`, `test_grpc_tools.py`, `test_insights.py`, `test_grpc_client.py` (only the new client methods), report `Ai-services/reports/TASK-086-T3.md`.
Do: add `leakage_report` and `menu_margins` to the tool-name lists (check whether exact-match); in `test_insights.py` add CASES entries and formatting tests (empty outlets message, "-" for empty values, invalid dates / range > 92 days, invalid `max_margin_pct` "abc", "-1", "101", "NaN" → `invalid_input`, missing user_id → UNAVAILABLE, None result → UNAVAILABLE); client `get_leakage_report` / `get_menu_margins` return None on gRPC error like `get_anomalies`.

## After the wave (orchestrator)
`mvnw -B verify` (Docker needed), `.venv` unittest unit + integration; fix packets for failures; persist evidence.
