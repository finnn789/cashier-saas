# TASK-079: M6 — unit cost / COGS, stock count, inactive ingredient fix, owner reports (PRD v2.1 §8.5, §8.6, §8.12)

Status: DELEGATED (2026-11-14). Memory version: 77. Branch `fix/flow-controls-a`; baseline = commit `b383d90` (clean tree).

Global rules: identical to `tasks/TASK-076.md` "Global rules" 1-7 (reports `reports/TASK-079-W<n>.md`). Workers never run Maven. Never edit V1..V8. Paths below are under `src/main/java/org/lite/project/cashiersaas/` unless absolute.

## User decisions (2026-11-14, `.ai/DECISIONS.md` "M6 data owner")
- D1 Cashiers MAY send a purchase price on RESTOCK; average cost and movement unit cost are returned ONLY to OWNERMERCHANT (null for CASHIER on every inventory response).
- D2 Per-menu gross profit = line totals − COGS (before order discount). Total gross profit = net sales (after discount) − total COGS.
- D3 Stock count is a batch request for many ingredients in one transaction.

## Shared rules (all workers)
- Money: `BigDecimal`, never double. Average unit cost scale 4 `RoundingMode.HALF_UP`; `order_items.cost_amount` scale 2 HALF_UP.
- Moving average on RESTOCK with `unitCost` c and quantity q, before-balance on-hand s and average a:
  `a' = c` when `s <= 0` or `a == null`; else `a' = (s*a + q*c) / (s + q)` (scale 4 HALF_UP). RESTOCK without a price: average unchanged. No other movement changes the average.
- `inventory_movements.unit_cost`: RESTOCK = the entered price (may be null); SALE, WASTE, ADJUSTMENT = the balance average at the time (may be null); SALE_REVERSAL = the `unit_cost` of the SALE it reverses.
- Inactive ingredients (`InventoryItemEntity.isActive() == false`) are never deducted on payment and add no cost.

## Wave 1 (parallel)

### W1 — V9, entities, FnbStockService
`src/main/resources/db/migration/V9__unit_cost_and_cogs.sql`:
- `inventory_balances ADD COLUMN average_unit_cost numeric(19,4)` (nullable, CHECK `>= 0`).
- `inventory_movements ADD COLUMN unit_cost numeric(19,4)` (nullable, CHECK `>= 0`).
- `order_items ADD COLUMN cost_amount numeric(19,2)` (nullable, CHECK `>= 0`). No backfill (old rows stay null = "without cost").
Java:
- `InventoryBalanceEntity`: field `averageUnitCost` + getter; `receive(BigDecimal quantity, BigDecimal unitCost)` = moving-average rule above, then the same on-hand change as `apply(quantity)` (call `apply`, no copy). Keep `apply` for everything else.
- `InventoryMovementEntity`: field `unitCost` (column `unit_cost`) + getter. Replace the 11-arg `record(...)` with a 12-arg one ending `UUID reversalOfMovementId, BigDecimal unitCost`; the 10-arg `record(...)` delegates with `null, null`. Update every caller of the old 11-arg form (only `FnbStockService`).
- `OrderItemEntity`: field `costAmount` (column `cost_amount`) + getter; `recordCost(BigDecimal cost)` (null allowed, scale 2 HALF_UP). If the class or base is `@Immutable`, report it instead of working around it.
- `FnbStockService.consumeForPaidOrder`:
  - skip recipe lines whose ingredient is inactive (in `addNeeds`, one condition);
  - SALE movement `unitCost` = that ingredient's balance `averageUnitCost` (read before deducting);
  - for EACH order item: cost = Σ over the product recipe lines and the recipe lines of that item's options (active ingredients only) of `quantityPerUnit × item quantity × averageUnitCost`; `recordCost(null)` when the item has no active recipe line at all or any used ingredient has no average (balance missing or average null); otherwise `recordCost(cost)`. Items with `product == null` get null.
  - Idempotency unchanged: when a SALE key already exists, do not deduct again, and do not overwrite an already non-null `costAmount`.
- `FnbStockService.reverseForRefundedOrder`: SALE_REVERSAL `unitCost` = `sale.getUnitCost()`. Average unchanged.
Reserved: the V9 file, `entity/shared/inventory/InventoryBalanceEntity.java`, `entity/shared/inventory/InventoryMovementEntity.java`, `entity/fnb/OrderItemEntity.java`, `service/FnbStockService.java`.

### W2 — restock price, cost visibility, batch stock count
Against W1's exact API above (`receive`, 12-arg `record`, getters).
- `dto/request/FnbStockMovementRequest`: optional `unitCost` (`@DecimalMin("0")`, `@Digits(integer = 15, fraction = 4)`). In `FnbInventoryService.recordMovement`: non-null `unitCost` with a type other than RESTOCK -> `InvalidOperationException("Harga beli hanya untuk restock.")`; RESTOCK uses `balance.receive(delta, unitCost)`; WASTE/ADJUSTMENT use `apply` and record `unitCost` = the balance average before the change. Fingerprint: append `"|" + unitCost.stripTrailingZeros().toPlainString()` ONLY when unitCost is non-null (old keys keep replaying).
- `createItem` initial stock: unchanged (no price; average stays null).
- Responses: `FnbInventoryItemResponse.averageUnitCost`, `FnbStockMovementResponse.unitCost`. `FnbInventoryMapper` takes a `boolean showCost`; the service passes `context.getMembership().getRole() == Role.OWNERMERCHANT` (create/update/deactivate/list/record/movements). Cashier -> both null.
- Stock count, owner only: `POST /api/v1/merchant/outlets/{outletId}/fnb/inventory/stock-counts` in `FnbInventoryController`, service `FnbInventoryService.stockCount(principal, outletId, request)` with `guard.requireOwner`.
  - Request `dto/request/FnbStockCountRequest`: `@NotBlank @Size(max=128) idempotencyKey`, `@Size(max=500) note`, `@NotEmpty @Size(max=200) @Valid List<FnbStockCountLineRequest> lines`. Line class `dto/request/FnbStockCountLineRequest`: `@NotNull UUID inventoryItemId`, `@NotNull @DecimalMin("0") @Digits(integer=11, fraction=3) BigDecimal countedQuantity`. Duplicate item ids -> `InvalidOperationException("Bahan duplikat dalam stok opname.")`. Unknown/inactive/other-outlet item -> `ResourceNotFoundException`.
  - Request fingerprint = `tokens.digest` of the lines sorted by item id (`id=qty` stripped plain, joined `;`) + `|` + trimmed note.
  - Per line movement key = `"count:" + tokens.digest(idempotencyKey + "|" + itemId)`. If ANY line's key exists: same fingerprint -> return the replay built from existing movements (previous = counted − delta); different -> `OperationConflictException("Kunci idempotensi telah digunakan.")`.
  - Process lines in ascending item id; lock or create each balance like `recordMovement`; delta = counted − on-hand; delta 0 -> no movement; else ADJUSTMENT with reason `"Stok opname"` (or `"Stok opname: " + note` when the note is non-blank), `unitCost` = average before the change.
  - Response `dto/response/FnbStockCountResponse` `{ List<FnbStockCountLineResponse> lines }`; line `{inventoryItemId, name, unit, previousQuantity, countedQuantity, difference, UUID movementId (null when 0)}`.
Reserved: `service/FnbInventoryService.java`, `controller/FnbInventoryController.java`, `dto/FnbInventoryMapper.java`, `dto/request/FnbStockMovementRequest.java`, `dto/response/FnbInventoryItemResponse.java`, `dto/response/FnbStockMovementResponse.java`, the four new stock-count DTOs.

### W3 — owner sales report additions
Against W1's `OrderItemEntity.costAmount`. All new data respects the existing filters (date range in outlet zone, `orderStatus`, `paymentMethod`) via `paidSalesQuery`, except refunds (below).
- `FnbSalesSummaryResponse` adds: `costOfGoodsSold` (Σ non-null `cost_amount` of items of included orders), `grossProfit` (= `netSales − costOfGoodsSold`), `itemsWithoutCost` (count of included order item rows with null cost), `refundCount`, `refundAmount` (from `payment_refunds` of the outlet with `refunded_at` in the range, no status/method filter).
- `FnbSalesProductResponse` adds `costOfGoodsSold`, `grossProfit` (= `grossSales − costOfGoodsSold`, before order discount, D2), `itemsWithoutCost`.
- `FnbSalesReportResponse` adds:
  - `hourly`: `List<FnbSalesHourlyResponse>` `{int hour (0-23, outlet zone), long transactionCount, BigDecimal netSales}`, only hours with data, ascending. Group in SQL (native query with `extract(hour from paid_at at time zone :zone)` is allowed in the repository); never load all payments into Java.
  - `cashiers`: `List<FnbSalesCashierResponse>` `{UUID staffUserId, String fullName, long transactionCount, BigDecimal netSales, BigDecimal amountCollected}` grouped by `payment.receivedBy`, ordered by netSales desc.
  - `shifts`: `List<FnbSalesShiftResponse>` `{UUID shiftId, String cashierName (shift openedBy), Instant openedAt, Instant closedAt, String status, long transactionCount, BigDecimal netSales}` grouped by `payment.cashShift`, ordered by openedAt desc; payments without a shift are one row with null shift fields.
- Mapper + service wiring in `FnbSalesReportService.report` only; CSV unchanged.
Reserved: `repository/FnbSalesReportRepository.java`, `service/FnbSalesReportService.java`, `dto/FnbSalesReportMapper.java`, `dto/response/FnbSalesReportResponse.java`, `dto/response/FnbSalesSummaryResponse.java`, `dto/response/FnbSalesProductResponse.java`, the three new response classes, `repository/FnbRefundRepository.java` (add one totals query only).

## Wave 2 (parallel, after wave 1 review + compile)
- **W4 unit tests** (`src/test/java/**/unit/**`): moving average (first price, weighted, negative/zero stock reset, no price), inactive ingredient skipped, item cost null/non-null rules incl. options, SALE/WASTE/ADJUSTMENT/SALE_REVERSAL unit cost, restock price rejected on non-RESTOCK, cost hidden for cashier, stock count (duplicates, zero delta, replay, conflict, note reason), report mapper math (gross profit total and per menu). Adapt existing tests broken by the new signatures.
- **W5 integration tests** (`src/test/java/**/integration/**`): V9 on PG17 via existing harness; restock with prices -> pay -> `cost_amount` + SALE unit cost; deactivated ingredient not deducted; refund copies unit cost; stock count roles (cashier 403), replay/conflict, tenant isolation 404; report hourly/cashiers/shifts/COGS/refunds numbers; cashier never sees costs.
- **W6 docs/Postman/UI prompt**: Postman (RESTOCK with `unitCost`, new `Stock Count` request, report notes), `docs/fnb-operations.md` + README M6 sections, new `prompts/m6-ui-owner.md` (restock price field, average cost column owner-only, stock count screen, report tabs per hour/cashier/shift, COGS and gross profit with "items without cost" hint). No code.

## Orchestrator gate
Review each wave's diff against reserved paths; `mvnw -B -DskipTests compile` after wave 1, `test-compile` after wave 2; `mvnw -B verify` only on the user's request. Then update PRD milestone table, `.ai/MEMORY.md`, `.ai/TASKS.md`.

## Known accepted limits
- An all-zero stock count leaves no movement, so its replay re-evaluates against the current stock.
- Orders paid before V9 have null cost and count as "items without cost".
- Positive ADJUSTMENT/stock count does not change the average.
