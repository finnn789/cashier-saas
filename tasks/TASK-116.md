# TASK-116: `app/analysis` package + product analysis tool (ABC, menu matrix, ranking, growth)

Status: DONE 2026-11-27 (uncommitted). GREEN: Python 280/280; Java `CashierInsightGrpcHandlerTest`+`GrpcInsightMapperTest`+`FnbOwnerInsightServiceTest` 72/72. Integration tests NOT run. Orchestrator fixes: missing space in leakage line (P1), ABC test expected C where D1 gives B, growth test expected `50,0%` (display drops trailing zero), registry test counts 8 tools, Rupiah assertions in test_insights updated, Ai-services proto re-copied from Java + stubs regenerated. Branch `feature/leakage-report`. Baseline: `545ac38` (dirty: TASK-114 changes, uncommitted).

## User instruction (2026-11-27)
"Buat package baru `analysis` berisi modul proses perhitungan seperti analisis produk." Money format: Rupiah `Rp12.500` everywhere in bot replies. Audit verdict (orchestrator): base numbers (qty, sales, COGS) stay in Java/SQL; Python `analysis` owns only pure derived calculations on Java aggregates.

## Global rules (every worker)
- Reports: `reports/TASK-116-<id>.md`.
- No Flyway/Alembic migration, no new dependency.
- Money is `Decimal` in Python (never float), `BigDecimal` in Java. Proto money fields stay strings.
- Reuse (Java): `CashierInsightGrpcHandler.handle(...)` + its date parsing/range validation used by `getSalesSummary` (<366 days), `FnbSalesReportRepository.products(tenantId, outletId, start, end, status, method, limit)` (`:83`), the zone conversion used by sales summary (`FnbSalesReportService.java:190-211`), the `ProductTotal` builder in `GrpcInsightMapper.java:71-80`.
- Reuse (Python): `_InsightTool`, `_valid_iso_date`, `UNAVAILABLE`, `_bad_input` (`app/tools/insights.py`), `CashierGrpcClient._call_insight`.
- Out of scope (need raw data Java does not expose): basket/co-purchase, price elasticity, hourly peak, products with zero sales (the product query is an inner join on order items).

## Orchestrator design decisions (reversible, user may override)
- D1 ABC: sort by `gross_sales` desc, then name. Cumulative share BEFORE the item < 80% -> A, < 95% -> B, else C (so the first product is always A). Total sales 0 -> empty list.
- D2 Menu matrix (Kasavana–Smith), per outlet, margin from actual sales: products with `items_without_cost > 0` go to `COST_UNKNOWN` and are excluded from the thresholds. For the rest with `quantity > 0`: `mix = quantity / total_quantity`; popular if `mix >= 0.7 / n`; `unit_margin = (gross_sales - cost_of_goods_sold) / quantity`; high margin if `unit_margin >= sum(gross - cogs) / sum(quantity)`. Quadrants: STAR (popular+high), PLOWHORSE (popular+low), PUZZLE (unpopular+high), DOG (unpopular+low).
- D3 Growth: `(current - previous) / previous * 100`, `Decimal`, 1 decimal `ROUND_HALF_UP`; previous 0 -> `None` (shown as "baru"). Previous period = same length, ending the day before `start_date`.
- D4 Product cap per outlet: 500 (`MAX_PERFORMANCE_PRODUCTS` in the handler), ordered like the existing query (sales desc).
- D5 Money text: `format_rupiah` (`Rp12.500`, `-Rp500`); percent text `12,5%` (comma decimal). Empty/absent value stays `-`.

## Contracts shared between workers
### Proto (both copies identical: `src/main/proto/cashier_service.proto` and `Ai-services/proto/cashier_service.proto`)
```proto
rpc GetProductPerformance (GetProductPerformanceRequest) returns (GetProductPerformanceResponse);

message ProductTotal { ... existing 1-6 ...; string product_id = 7; }

message GetProductPerformanceRequest {
  string tenant_id = 1; string user_id = 2; string start_date = 3; string end_date = 4;
}
message OutletProductPerformance {
  string outlet_id = 1; string outlet_name = 2; string start_date = 3; string end_date = 4;
  repeated ProductTotal products = 5;
}
message GetProductPerformanceResponse { string tenant_id = 1; repeated OutletProductPerformance outlets = 2; }
```
Java fills `product_id` also in `GetSalesSummary.top_products` (same builder).
Errors: same as `GetSalesSummary` (non-owner PERMISSION_DENIED, below Premium FAILED_PRECONDITION, bad/blank date or range > 366 days or start > end INVALID_ARGUMENT).

### Java signatures (fixed by T2's tests, J1 must match)
- `FnbOwnerInsightService.productPerformance(UUID tenantId, UUID outletId, LocalDate start, LocalDate end, int limit): List<FnbSalesProductResponse>` (handler passes limit 500).
- `GrpcInsightMapper.toProductPerformance(MerchantAccessContext context, LocalDate start, LocalDate end, List<FnbSalesProductResponse> products): OutletProductPerformance`.
- Handler constructor unchanged. See `reports/TASK-116-T2.md`.

### Python test assumptions (fixed by T1's tests, P1/P2 must match; see `Ai-services/reports/TASK-116-T1.md`)
- Product dict keys: `product_id, name, quantity, gross_sales, cost_of_goods_sold, items_without_cost`.
- `abc_classes` returns items sorted by sales desc then name. Tool `execute(*, tenant_id, user_id, start_date, end_date, kind)`.

### Python `app/analysis/` (new package; inputs are the dicts produced by `_message_to_dict`, numbers may be `str` or `int`)
- `money.py`: `to_decimal(value) -> Decimal` (invalid/None -> `Decimal(0)`), `format_rupiah(value) -> str`, `format_number(value) -> str` (comma decimal). Moved from `app/chat/daily_summary.py` (`_decimal`, `format_rupiah`, `_number`); `daily_summary.py` imports them (keep the names `format_rupiah`, `_decimal`, `_number` importable from `daily_summary` because tests import them).
- `product.py`:
  - `abc_classes(products: list[dict]) -> list[dict]`: copies of the input dicts plus `share_pct: Decimal`, `cumulative_pct: Decimal` (1 dp, cumulative INCLUDING the item), `abc: "A"|"B"|"C"` (D1).
  - `menu_matrix(products: list[dict]) -> dict[str, list[dict]]`: keys exactly `STAR, PLOWHORSE, PUZZLE, DOG, COST_UNKNOWN` (always present); item = input dict copy plus `mix_pct: Decimal`, `unit_margin: Decimal` (rupiah, 0 dp) — `COST_UNKNOWN` items get neither (D2).
  - `rank(products: list[dict], by: str = "quantity", n: int = 5, worst: bool = False) -> list[dict]`: `by` in `quantity|gross_sales`, ties by name.
  - `growth_pct(current, previous) -> Decimal | None` (D3).

### Python client / tool
- `CashierGrpcClient.get_product_performance(*, tenant_id, user_id, start_date, end_date) -> dict | None` via `_call_insight`.
- Tool `product_analysis` (`app/tools/product_analysis.py`, class `ProductAnalysisTool(_InsightTool)`), params `start_date`, `end_date` (required, ISO), `kind` (required, enum `abc|matrix|ranking|growth`). Owner-only like other insight tools (`user_id` missing -> `UNAVAILABLE`). `growth` calls the RPC twice (current + previous period, D3) and reports outlet total and per-product `gross_sales` growth for products in either period. Returns plain text per outlet using D5 formats; "Belum ada penjualan." when an outlet has no products.

## Wave 0 — RED (parallel, before any production code)

### T1 — Python tests (`test-worker`)
Reserved:
- `Ai-services/tests/unit/test_analysis.py` (new)
- `Ai-services/tests/unit/test_product_analysis.py` (new)
Do: cases under "Test cases" (Python). Mock `CashierGrpcClient.get_product_performance` with `AsyncMock` like `test_insights.py` does.

### T2 — Java tests (`test-worker`)
Reserved:
- `src/test/java/org/lite/project/cashiersaas/unit/grpc/CashierInsightGrpcHandlerTest.java` (extend)
- `src/test/java/org/lite/project/cashiersaas/unit/grpc/GrpcInsightMapperTest.java` (extend)
Do: cases under "Test cases" (Java). They must fail to compile until J1 ships the proto + handler.

## Wave 1 — GREEN (parallel, after RED is confirmed; never edit Wave 0 tests)

### J1 — RPC (`java-worker`)
Reserved:
- `src/main/proto/cashier_service.proto`
- `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierInsightGrpcHandler.java`
- `src/main/java/org/lite/project/cashiersaas/grpc/service/CashierGrpcService.java` (delegate the new RPC only)
- `src/main/java/org/lite/project/cashiersaas/grpc/mapper/GrpcInsightMapper.java`
- `src/main/java/org/lite/project/cashiersaas/service/FnbOwnerInsightService.java` (one method `productPerformance(tenantId, outletId, startDate, endDate, limit)` reusing the sales-summary zone conversion, only if the handler cannot reach it otherwise)
Acceptance: T2 green; `OwnerInsightIntegrationTest` still green.

### P1 — analysis package + Rupiah everywhere (`python-worker`)
Reserved:
- `Ai-services/app/analysis/__init__.py`, `money.py`, `product.py` (new)
- `Ai-services/app/chat/daily_summary.py` (move helpers, import them back)
- `Ai-services/app/tools/insights.py` (money fields rendered with `format_rupiah`, percents with D5; no logic change)
Acceptance: `test_analysis.py` green; existing `test_daily_summary.py`, `test_insights.py` green (if an existing assertion expects `Rp 12500`-style text, report it — do not edit tests).

### P2 — client, tool, proto copy (`python-worker`)
Reserved:
- `Ai-services/proto/cashier_service.proto` (copy J1's contract verbatim)
- `Ai-services/app/integrations/grpc/generated/*` (regenerate with the protoc command in `Ai-services/README.md:163`)
- `Ai-services/app/integrations/grpc/client.py` (`get_product_performance` only)
- `Ai-services/app/tools/product_analysis.py` (new), `Ai-services/app/tools/__init__.py` (register + export)
- `Ai-services/app/ai/prompts.py` (add `product_analysis` to the owner-only analytics list)
Uses P1's contract signatures (import from `app.analysis`); do not write them.
Acceptance: `test_product_analysis.py` green.

## Test cases (TDD)
Python `test_analysis.py`:
- ABC: sales 500/300/150/50 -> cumulative-before 0%,50%,80%,95% -> A, A, B, C; `share_pct` 50.0/30.0/15.0/5.0.
- ABC: one product -> A; all zero sales -> `[]`.
- Matrix: 4 products crafted so each lands in a different quadrant; a product with `items_without_cost=1` lands only in `COST_UNKNOWN` and does not change the thresholds; all 5 keys present on empty input.
- Matrix: quantity 0 product is ignored (no division by zero).
- rank: top 2 by quantity; worst=True by gross_sales; tie broken by name.
- growth_pct: (150,100) -> 50.0; (50,100) -> -50.0; (10,0) -> None; (0,0) -> None; string inputs accepted.
- money: `format_rupiah("12500")` -> `Rp12.500`; `-500` -> `-Rp500`; `"abc"` -> `Rp0`; `format_number("12.5")` -> `12,5`.
Python `test_product_analysis.py`:
- invalid date / start > end -> invalid_input, RPC not called; missing user_id -> UNAVAILABLE; RPC None -> UNAVAILABLE.
- kind=abc -> text contains `Rp` formatted sales and class letters.
- kind=growth with 2024-03-08..2024-03-14 -> RPC called for 2024-03-01..2024-03-07 and for the current range; previous 0 shows "baru".
- unknown kind -> invalid_input.
Java:
- `CashierInsightGrpcHandlerTest`: non-owner -> PERMISSION_DENIED; range > 366 days -> INVALID_ARGUMENT; owner -> one outlet with products incl. `product_id`, repository called with limit 500.
- `GrpcInsightMapperTest`: `ProductTotal.product_id` filled (also in sales summary top products).
- RED evidence: `uv run python -m unittest discover -s tests/unit` (Ai-services) and `./mvnw -q test -Dtest=CashierInsightGrpcHandlerTest,GrpcInsightMapperTest` fail.
- GREEN evidence: both pass, plus full `uv run python -m unittest discover -s tests/unit` and `./mvnw -q test -Dtest=*Insight*`.
