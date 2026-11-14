# TASK-083 J3 report: consolidated sales report

Status: implemented, compile-checked by reading only (no Maven, no tests run, by policy). Baseline `f9ed23d`.

## Files (under `src/main/java/org/lite/project/cashiersaas/`)
New: `controller/FnbConsolidatedSalesReportController.java`, `dto/response/FnbConsolidatedSalesReportResponse.java`, `dto/response/FnbOutletSalesSummaryResponse.java`.
Modified: `service/FnbSalesReportService.java`, `dto/FnbSalesReportMapper.java`.

## Route
`GET /api/v1/merchant/reports/sales?startDate&endDate[&orderStatus&paymentMethod]` -> `ApiResponse<FnbConsolidatedSalesReportResponse>`, message "Laporan penjualan gabungan diambil.". OWNERMERCHANT only (`/api/v1/merchant/**` security rule + `guard.requireOwner(principal, principal.getOutletId())`). `page/size/topProducts` query params are ignored.

## Response
`startDate`, `endDate` (min start / max end across outlets, because each outlet resolves its own dates; equal to the request dates when supplied), `totals` (`FnbSalesSummaryResponse`), `outlets[]` = `outletId, outletName, timeZone, summary`. No `currency`: the existing per-outlet report has none.

## Behaviour
- Outlets = `planEntitlementService.usableOutletIds(tenantId, now)` (active, within allowance, oldest first). For each, `MerchantAccessRepository.findOutlet(userId, tenantId, outletId, roles, now)`; no access row or membership role != OWNERMERCHANT -> skipped (guard not used for other outlets).
- Per outlet: `query(context, filter)` (same validation: 366-day range, status filter, outlet zone `LocalDate.now(zone)` defaults, start/end instants in that outlet's own zone) then the Free 30-day rule (extracted to private `requireWithinPlanWindow`, now shared with `report`; FREETIER + start older than 29 days before today in that outlet zone -> 402 `PlanUpgradeRequiredException(PRO)`), then `assemble(...)` with `full=false` and returns only its `summary` (reuses the existing SQL; skips hourly/cashiers/shifts). Page 0 / size 1 / top 1 are forced (smallest `query` accepts: `top >= 1`), so `summarize` itself was not reused because it hard-codes `full=true` and no Free rule; `assemble` is the same engine.
- Paid, non-cancelled only is inherited from the repository queries.

## How totals are summed (`FnbSalesReportMapper.sum`)
BigDecimal `add` for subtotal, netSales, total, amountCollected, discountAmount, taxAmount, serviceChargeAmount, costOfGoodsSold, refundAmount. `long` `+` for transactionCount, cancellationCount, itemsWithoutCost, refundCount. Derived and recomputed with the mapper's own formulas (not summed): `averageTransaction` = total / transactionCount, scale 2 HALF_UP (0 when no transactions); `grossProfit` = netSales - costOfGoodsSold. No outlets -> all zeros (cannot happen after the owner check unless the token outlet is filtered).

## Deviations / notes
- `FnbSalesReportService` gained a constructor dependency `MerchantAccessRepository` (no test builds it manually: grep `new FnbSalesReportService(` found none).
- Money mixing: all outlets share the tenant currency (tenant-level setting), so summing is valid; no currency conversion.
- Response top-level dates when the request omits them use per-outlet "today"; the response reports min start / max end.

## Recommended tests (extend existing report test classes)
Two outlets in different zones (e.g. Asia/Jakarta, Asia/Makassar) with paid orders near midnight: totals equal the sum, each outlet row matches its per-outlet report; cancelled/unpaid excluded; average/gross profit recomputed; over-allowance outlet excluded; owner without access row to a second outlet skipped; FREETIER with start older than 30 days -> 402; range > 366 days or end < start -> 400; cashier -> 403; other tenant sees only own outlets.
