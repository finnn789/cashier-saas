# TASK-084: Stok opname ber-header + laporan kebocoran bahan (PRD v3 slice 1)

Status: DELEGATED (wave 1). Memory version: 90. Branch `feature/leakage-report`. Baseline commit: `20faa7a` (dirty: `.postman/resources.yaml` (user, do not touch), `PRD-MVP.md` (orchestrator, PRD v3), `tasks/_TEMPLATE.md`).

## User instruction (2026-11-20)
Hapus PRD lama, pakai PRD v3 (`PRD-MVP.md`), refactor repo yang ada (bukan build ulang). Orchestrator mendelegasikan. Slice 1 = FR-IN-03 (opname memicu periode) + FR-AI-02 (laporan kebocoran per outlet per periode, varians % dan Rupiah, penjelasan teks oleh AI) per `PRD-MVP.md` §0 dan §5.1.

## Global rules (every worker)
Worker agents already carry the standing rules (`.claude/agents/*.md` + `AGENTS.md`). Task-specific extras:
- Reports: `reports/TASK-084-<id>.md`.
- Next migration: `V13__stock_counts_and_leakage.sql`.
- Reuse: `MerchantAccessGuard.requireOwner/requireOwnerIgnoringAllowance`, `PlanEntitlementService.require(tenantId, PlanCode.PRO)` and `usableOutletIds`, `FnbInventoryRepository` query style (Blaze/Querydsl), the 366-day date-range validation used by `FnbSalesReportService` (reuse, do not add a 5th copy), `GrpcTenantAccessPolicy` + `requireOwnerOutlets` as used by `CashierInsightGrpcHandler.getAnomalies`, `GrpcInsightMapper` decimal-string money.
- Money/quantity `BigDecimal`; percentages scale 2, HALF_UP; Rupiah loss scale 2.

## Orchestrator design decisions (reversible, user may override)
- D1 Leakage is computed from the existing ledger, not a new calculation model. For a counted item, the system balance before the count already equals start + restock − SALE (recipe) − waste ± adjustments, so **shortage = previous_quantity − counted_quantity** at the count. PRD §5.1 "Aktual − Teoritis" equals this shortage.
- D2 New header + lines tables. Lines are stored for every counted item, including zero-difference lines (today a zero difference creates no movement, so it is invisible).
- D3 Period is per item: from the `counted_at` of the latest earlier stock count line of the same item in the same outlet (exclusive) to this count's `counted_at` (inclusive). No earlier line → from the item's first movement. Supports partial opname (PRD §17 risk mitigation).
- D4 theoretical_usage = −SUM(quantity_delta) of SALE and SALE_REVERSAL movements of the item/outlet in the period (≥ 0 normally). waste = −SUM(WASTE) in the period, restock = SUM(RESTOCK) in the period, both informational. Manual ADJUSTMENT movements that do not belong to a stock count are informational (`manualAdjustment`) and are not leakage.
- D5 variance_pct = shortage / theoretical_usage × 100; null when theoretical_usage = 0. loss_amount = shortage × line.unit_cost (balance average cost snapshotted at the count); null when unit_cost is null. A negative shortage (surplus) gives a negative loss.
- D6 flagged = shortage > 0 AND (variance_pct > 5.00 OR theoretical_usage = 0). Threshold is a constant 5.00 for now (per-ingredient threshold deferred).
- D7 Outlet total_loss = SUM(loss_amount) of lines with loss_amount > 0. Consolidated ranking sorts outlets by total_loss desc over counts whose `counted_at` falls in [from, to] (outlet time zone days, same convention as the consolidated sales report).
- D8 Owner only (merchant routes), plan PRO minimum (full analytics). AI tool stays PREMIUM via existing gRPC gating.
- D9 Stock counts recorded before V13 have no header; they are ignored by the report (no backfill).
- D10 Per-shift / per-cashier breakdown (PRD §5.1 bullet 2) and stored AI insights are deferred.

## Contracts shared between workers

V13 (W J1):
- `stock_counts`: id uuid PK, tenant_id, outlet_id (FK outlets), counted_at timestamptz, actor_membership_id uuid, note varchar(255) null, idempotency_key varchar(…) same size as movements', request_fingerprint, created_at; UNIQUE (tenant_id, idempotency_key); INDEX (tenant_id, outlet_id, counted_at DESC).
- `stock_count_lines`: id uuid PK, tenant_id, stock_count_id FK, inventory_item_id FK, previous_quantity numeric, counted_quantity numeric, difference numeric, unit_cost numeric(19,4) null, movement_id uuid null FK inventory_movements; UNIQUE (stock_count_id, inventory_item_id); INDEX (tenant_id, inventory_item_id, stock_count_id).
- Column types match the existing inventory quantity columns (check V4).

Service (provider J1, consumer J2):
```java
// FnbLeakageService (new, service package)
public List<FnbOutletLeakageResponse> outletsLeakage(UUID tenantId, List<UUID> outletIds, Instant from, Instant to, int topLines)
```
Returns one entry per outlet in `outletIds` that has ≥1 stock count in [from, to], sorted by totalLoss desc; `lines` = up to `topLines` flagged lines over those counts sorted by lossAmount desc (nulls last). Caller has already authorized tenant/outlets. Read-only transaction.

DTOs (J1, `dto/response`, named public classes, `@Getter @Setter`):
- `FnbLeakageLineResponse`: inventoryItemId, name, unit, periodStart (Instant, null = since first movement), periodEnd, theoreticalUsage, waste, restock, manualAdjustment, previousQuantity, countedQuantity, shortage, variancePct, unitCost, lossAmount, flagged.
- `FnbLeakageReportResponse`: stockCountId, outletId, countedAt, note, totalLoss, flaggedCount, lines (sorted flagged first, then lossAmount desc).
- `FnbStockCountSummaryResponse`: stockCountId, countedAt, note, lineCount, flaggedCount, totalLoss.
- `FnbOutletLeakageResponse`: outletId, outletName, stockCountCount, totalLoss, flaggedCount, lines (`List<FnbLeakageLineResponse>`).
- `FnbLeakageConsolidatedResponse`: from, to (LocalDate), totalLoss, outlets (`List<FnbOutletLeakageResponse>`).

REST (J1):
- `POST /api/v1/merchant/outlets/{outletId}/fnb/inventory/stock-counts` (existing): additionally persists header + one line per request line inside the same transaction; `FnbStockCountResponse` gains `stockCountId`. Replay with the same key returns the same `stockCountId`; idempotency/fingerprint behaviour otherwise unchanged.
- `GET /api/v1/merchant/outlets/{outletId}/fnb/inventory/stock-counts?page=0&size=20` → `List<FnbStockCountSummaryResponse>` newest first (size 1..50).
- `GET /api/v1/merchant/outlets/{outletId}/fnb/reports/leakage?stockCountId=` → `FnbLeakageReportResponse`; without `stockCountId` the latest count; none → 404; foreign id → 404.
- `GET /api/v1/merchant/reports/leakage?from=YYYY-MM-DD&to=YYYY-MM-DD` → `FnbLeakageConsolidatedResponse` over usable outlets (same access rule as `GET /api/v1/merchant/reports/sales`).

gRPC (J2) — append to `src/main/proto/cashier_service.proto`:
```proto
rpc GetLeakageReport (GetLeakageReportRequest) returns (GetLeakageReportResponse);

message GetLeakageReportRequest { string tenant_id = 1; string user_id = 2; string from = 3; string to = 4; }  // dates YYYY-MM-DD, empty = last 30 days ending today (tenant zone)
message LeakageLine { string name = 1; string unit = 2; string theoretical_usage = 3; string shortage = 4; string variance_pct = 5; string loss_amount = 6; string waste = 7; }  // decimal strings, "" when null
message OutletLeakage { string outlet_id = 1; string outlet_name = 2; int32 stock_count_count = 3; string total_loss = 4; int32 flagged_count = 5; repeated LeakageLine lines = 6; }
message GetLeakageReportResponse { string tenant_id = 1; repeated OutletLeakage outlets = 2; }
```
Owner-only and outlet-scoped exactly like `GetAnomalies`; range max 92 days; topLines = 10.

## Wave 1 (parallel)

### J1 — schema, stock count header, leakage service and REST (`java-worker`)
Reserved:
- `src/main/resources/db/migration/V13__stock_counts_and_leakage.sql`
- `src/main/java/org/lite/project/cashiersaas/entity/shared/inventory/StockCountEntity.java` (new), `StockCountLineEntity.java` (new)
- `src/main/java/org/lite/project/cashiersaas/repository/FnbInventoryRepository.java`
- `src/main/java/org/lite/project/cashiersaas/service/FnbInventoryService.java`, `service/FnbLeakageService.java` (new)
- `src/main/java/org/lite/project/cashiersaas/service/FnbSalesReportService.java` only if needed to expose the existing date-range helper for reuse (no behaviour change)
- `src/main/java/org/lite/project/cashiersaas/controller/FnbInventoryController.java`, `controller/FnbLeakageReportController.java` (new, `@RequestMapping("/api/v1/merchant")`, both leakage routes)
- `src/main/java/org/lite/project/cashiersaas/mapper/FnbInventoryMapper.java` (or the existing inventory mapper location)
- `src/main/java/org/lite/project/cashiersaas/dto/response/FnbStockCountResponse.java` + the 5 new response DTOs above
Do: everything under "REST (J1)", "Service", V13, D1–D9. Entities with named factory methods (`StockCountEntity.record(...)`, `StockCountLineEntity.record(...)`), no public setters.
Acceptance: routes and DTOs match the contract; tenant + outlet scoped; PRO gate; leakage numbers follow D1–D7; replay returns the same `stockCountId`; zero-difference lines stored; compiles in principle (orchestrator compiles).

### J2 — gRPC GetLeakageReport (`java-worker`)
Reserved:
- `src/main/proto/cashier_service.proto` (append only)
- `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierInsightGrpcHandler.java`, `grpc/service/CashierGrpcService.java`, `grpc/mapper/GrpcInsightMapper.java`
Do: the gRPC contract, calling `FnbLeakageService.outletsLeakage(...)` (written in parallel by J1; code against the signature above). Reuse the GetAnomalies access/validation path; invalid dates → INVALID_ARGUMENT; range > 92 days → INVALID_ARGUMENT.
Acceptance: owner-only, outlet scope from the member, money as decimal strings, empty string for null.

## Wave 2 (after wave 1 review + orchestrator compile)

### P1 — AI tool `leakage_report` (`python-worker`)
Reserved: `Ai-services/app/integrations/grpc/generated/*` (regenerate stubs from the updated proto), `Ai-services/app/integrations/grpc/client.py`, `Ai-services/app/tools/insights.py`, `Ai-services/app/tools/registry.py`, `Ai-services/app/ai/prompts.py` (one tool hint line only), `Ai-services/reports/TASK-084-P1.md`.
Do: client method `get_leakage_report(*, tenant_id, user_id, date_from, date_to)` like `get_anomalies`; tool `leakage_report` (Indonesian description: "Laporan kebocoran bahan per outlet dari stok opname: selisih, persen, dan rugi Rupiah") with optional from/to; output a compact text table the LLM can explain (3–5 sentences, PRD §5.1); numbers come only from the tool.

### D1 — Postman requests (`java-worker` or haiku `tidy-worker` for file placement)
Reserved: new request files under `postman/collections/KedaiMbo API/21 - F&B Leakage/` (folder definition + 3 GET requests + updated stock-count example if present). Not executed.

### Wave 1 review (orchestrator, 2026-11-20)
- `mvnw -q -DskipTests compile` OK. Diff limited to reserved paths; no record/ResponseEntity/wildcard/@Deprecated/@Data in new files.
- Accepted deviations: half-open range `[from, to)` (J2 passes next-day midnight, consistent); note varchar(500); `created_at` on lines; theoretical ≤ 0 → null pct; stock-count list route lives in `FnbLeakageReportController`; gRPC converts dates in the tenant zone (one Instant range for all outlets).
- Known debt: count adjustments are told apart from manual ones by the `count:` idempotency-key prefix, not by `stock_count_lines.movement_id`; stock-count list runs the line computation per count (≤ 50).
- `test-compile` FAILS only at `CashierInsightGrpcHandlerTest:85` (new constructor arg) → A1.

### A1 — fix test compile (`java-worker`, test file reserved explicitly)
Reserved: `src/test/java/org/lite/project/cashiersaas/unit/grpc/CashierInsightGrpcHandlerTest.java`, `reports/TASK-084-A1.md`.
Do: add `@Mock FnbLeakageService` and pass it to the constructor in the new order `(policy, accessRepository, insightService, leakageService, mapper)`. No other change, no new test.

## Recommended tests (for when the user requests testing)
- Unit `FnbLeakageService`: shortage/variance/loss math incl. theoretical 0, unit_cost null, surplus negative loss, per-item period from the previous line, manual adjustment excluded, flag threshold boundary 5.00.
- IT (extend `FnbCostAndStockCountIntegrationTest`): pay orders with recipes → stock count with shortage → leakage report numbers; replay same key → same stockCountId; zero-difference line stored; cashier 403 on merchant routes; foreign tenant stockCountId 404; FREETIER 402; consolidated ranks two outlets.
- gRPC IT (extend existing insight gRPC IT): owner gets leakage, cashier denied, other tenant empty.
- Python: extend `test_grpc_tools`/insights tests for the new tool formatting.
