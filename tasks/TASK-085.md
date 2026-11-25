# TASK-085: HPP/margin menu + peringatan margin (PRD v3 slice 2)

Status: DELEGATED (wave 1). Memory version: 91. Branch `feature/leakage-report`. Baseline: commit `20faa7a` + uncommitted TASK-084 working tree (files listed in `.ai/MEMORY.md` TASK-084 entry; do not modify them unless reserved below). Dirty user file `.postman/resources.yaml`: do not touch.

## User instruction (2026-11-20)
"lanjutkan pengembangan Backendnya" after TASK-084. Slice 2 = FR-CS-01 (HPP per menu from recipe × ingredient cost) + FR-CS-02 (margin below target warning, default target 60%, configurable, list affected menus) per `PRD-MVP.md` §0, §4, §5.2 step 5, §5.3 tool `get_menu_margin`.

## Global rules (every worker)
Worker agents already carry the standing rules (`.claude/agents/*.md` + `AGENTS.md`). Task-specific extras:
- Reports: `reports/TASK-085-<id>.md`.
- Next migration: `V14__target_margin.sql`.
- Reuse: `FnbStockService.itemCost(...)` (make it package-private `static`, do not copy it), `FnbInventoryRepository.productRecipes/optionRecipes/balances`, `FnbCatalogRepository.products`, `MerchantAccessGuard.requireOwner`, `PlanEntitlementService.require(tenantId, PlanCode.PRO)`, `GrpcInsightMapper` decimal strings, the `GetAnomalies`/`GetLeakageReport` access path in `CashierInsightGrpcHandler`.
- Money `BigDecimal`; percentages scale 2 HALF_UP; HPP scale 2 HALF_UP.

## Orchestrator design decisions (reversible, user may override)
- D1 Target margin is a tenant setting `tenants.target_margin_pct numeric(5,2) NOT NULL DEFAULT 60.00`, CHECK 0 ≤ x < 100, exposed in the existing business settings GET/PUT (`targetMarginPct`, optional on PUT = unchanged). Change is audited like the other settings fields.
- D2 HPP is computed on demand (always live, satisfies "< 5 detik"): HPP of a product = `FnbStockService.itemCost(1, productRecipeLines, balances)` using each ingredient's current outlet `average_unit_cost`. Options are not included (base menu only; option margins deferred).
- D3 Status per product: `NO_RECIPE` (no active recipe line), `COST_UNKNOWN` (an active ingredient has no average cost; list their names in `missingCostItems`), `BELOW_TARGET` (marginPct < target), `OK`.
- D4 marginPct = (price − hpp) / price × 100; null when price = 0 or status is NO_RECIPE/COST_UNKNOWN.
- D5 suggestedPrice only for BELOW_TARGET = hpp / (1 − target/100), rounded UP to the next Rp500.
- D6 Restock warning: when a RESTOCK with `unitCost` changes the average cost, the movement response gains `marginWarnings` (List<String>, Indonesian), e.g. "Margin Ayam Geprek turun ke 52,00% (target 60,00%), saran harga Rp27.500". Only for products of that outlet whose recipe uses the item and that are BELOW_TARGET after the change. Empty list for cashiers (cost data is owner-only, same rule as `showCost`) and for every other movement type.
- D7 Owner only, PRO minimum for REST; AI tool Premium via existing gRPC gating. Archived products excluded; unavailable products included.

## Contracts shared between workers

Service (provider J1, consumer J2):
```java
// FnbMenuMarginService (new, service package), read-only, caller has authorised tenant/outlet
public FnbMenuMarginReportResponse outletMargins(UUID tenantId, UUID outletId)
```

DTOs (J1, `dto/response`, named public classes, `@Getter @Setter`):
- `FnbMenuMarginResponse`: productId, name, categoryName, price, hpp, marginPct, status (String: NO_RECIPE|COST_UNKNOWN|BELOW_TARGET|OK), suggestedPrice, missingCostItems (`List<String>`).
- `FnbMenuMarginReportResponse`: outletId, outletName, targetMarginPct, belowTargetCount, menus (`List<FnbMenuMarginResponse>`, sorted BELOW_TARGET by marginPct asc, then COST_UNKNOWN, NO_RECIPE, OK by name).
- `FnbStockMovementResponse` gains `marginWarnings` (`List<String>`, default empty list).
- `MerchantBusinessSettingsRequest/Response` gain `targetMarginPct` (BigDecimal).

REST (J1):
- `GET /api/v1/merchant/outlets/{outletId}/fnb/reports/menu-margins` → `FnbMenuMarginReportResponse` (owner, PRO). Optional `?belowTargetOnly=true` filters menus to BELOW_TARGET.

gRPC (J2) — append to `src/main/proto/cashier_service.proto`:
```proto
rpc GetMenuMargins (GetMenuMarginsRequest) returns (GetMenuMarginsResponse);

message GetMenuMarginsRequest { string tenant_id = 1; string user_id = 2; string max_margin_pct = 3; } // "" = only BELOW_TARGET; else menus with margin_pct <= value
message MenuMargin { string name = 1; string price = 2; string hpp = 3; string margin_pct = 4; string status = 5; string suggested_price = 6; }
message OutletMenuMargins { string outlet_id = 1; string outlet_name = 2; string target_margin_pct = 3; repeated MenuMargin menus = 4; }
message GetMenuMarginsResponse { string tenant_id = 1; repeated OutletMenuMargins outlets = 2; }
```
Owner-only, outlet scope from the member, Premium, same path as `GetAnomalies`; max 30 menus per outlet; invalid `max_margin_pct` (not a number or outside 0..100) → INVALID_ARGUMENT. Decimal strings, "" for null.

## Wave 1 (parallel)

### J1 — target margin, margin service, REST, restock warnings (`java-worker`)
Reserved:
- `src/main/resources/db/migration/V14__target_margin.sql`
- `src/main/java/org/lite/project/cashiersaas/entity/shared/merchant/TenantEntity.java`
- `src/main/java/org/lite/project/cashiersaas/service/MerchantBusinessSettingsService.java`, `dto/request/MerchantBusinessSettingsRequest.java`, `dto/response/MerchantBusinessSettingsResponse.java` (+ its mapper if separate; name it in the report)
- `src/main/java/org/lite/project/cashiersaas/service/FnbStockService.java` (only: `itemCost` visibility private → package-private)
- `src/main/java/org/lite/project/cashiersaas/service/FnbMenuMarginService.java` (new), `controller/FnbMenuMarginReportController.java` (new)
- `src/main/java/org/lite/project/cashiersaas/service/FnbInventoryService.java` (restock `marginWarnings` only)
- `src/main/java/org/lite/project/cashiersaas/dto/response/FnbStockMovementResponse.java`, `dto/FnbInventoryMapper.java` (only if needed), new `dto/response/FnbMenuMarginResponse.java`, `FnbMenuMarginReportResponse.java`
Acceptance: D1–D7 and the REST/DTO contract; tenant + outlet scoped; restock warnings computed after the average is updated in the same transaction.

### J2 — gRPC GetMenuMargins (`java-worker`)
Reserved: `src/main/proto/cashier_service.proto` (append only), `grpc/service/CashierInsightGrpcHandler.java`, `grpc/service/CashierGrpcService.java`, `grpc/mapper/GrpcInsightMapper.java`, `src/test/java/org/lite/project/cashiersaas/unit/grpc/CashierInsightGrpcHandlerTest.java` (constructor argument only, if the handler constructor changes).
Do: the gRPC contract, calling `FnbMenuMarginService.outletMargins(...)` per owner outlet (written in parallel by J1; code against the signature). Filter per D-contract.

## Wave 2 (after wave 1 review + orchestrator compile)

### P1 — AI tool `menu_margins` (`python-worker`)
Reserved: `Ai-services/app/integrations/grpc/generated/*` (regenerate), `Ai-services/proto/cashier_service.proto` (copy of the Java proto), `Ai-services/app/integrations/grpc/client.py`, `Ai-services/app/tools/insights.py`, `Ai-services/app/tools/__init__.py`, `Ai-services/app/ai/prompts.py` (tool name in the analytics hint line only), `Ai-services/reports/TASK-085-P1.md`.
Do: client `get_menu_margins(*, tenant_id, user_id, max_margin_pct)`; tool `menu_margins` (Indonesian description: "HPP dan margin per menu per outlet, menu di bawah target margin dan saran harga") with optional `max_margin_pct`; compact text output; numbers only from the tool.

### D1 — Postman (`java-worker`, haiku)
Reserved: new folder `postman/collections/KedaiMbo API/22 - F&B Menu Margins/` (folder definition + GET menu-margins with and without `belowTargetOnly`), and the existing business settings PUT request file in that collection if present (add `targetMarginPct` to the body only). Not executed.

## Recommended tests (for when the user requests testing)
- Unit `FnbMenuMarginService`: statuses (no recipe, missing cost, below target, ok), margin math, price 0, suggested price rounding to Rp500, inactive ingredient ignored.
- Unit/IT restock with unitCost → `marginWarnings` for owner, empty for cashier and for WASTE.
- IT settings PUT/GET `targetMarginPct` (bounds 0..<100, omitted = unchanged); FREETIER 402 on menu-margins; cashier 403; foreign outlet 404.
- gRPC: owner gets outlets, cashier denied, invalid max_margin_pct INVALID_ARGUMENT.
- Python: tool formatting; tool-name lists in `test_tools.py`/`test_grpc_tools.py`.
