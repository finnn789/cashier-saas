# TASK-079 W1 report

## Changed files
- `src/main/resources/db/migration/V9__unit_cost_and_cogs.sql` (new)
- `entity/shared/inventory/InventoryBalanceEntity.java`
- `entity/shared/inventory/InventoryMovementEntity.java`
- `entity/fnb/OrderItemEntity.java`
- `service/FnbStockService.java`
- `reports/TASK-079-W1.md`

## Contract points done
- V9: `average_unit_cost numeric(19,4)`, `unit_cost numeric(19,4)`, `cost_amount numeric(19,2)`; all nullable with named CHECK `IS NULL OR >= 0`; no backfill; V1..V8 untouched.
- `InventoryBalanceEntity`: `averageUnitCost` + getter; `receive(quantity, unitCost)` = moving average (reset to price when on-hand <= 0 or average null; weighted otherwise; scale 4 HALF_UP; null price leaves average unchanged), then `apply(quantity)`.
- `InventoryMovementEntity`: `unitCost` + getter; 12-arg `record(..., reversalOfMovementId, unitCost)` replaces the 11-arg one; 10-arg delegates with `null, null`.
- `OrderItemEntity`: `costAmount` + getter; `recordCost(BigDecimal)` (null ok, scale 2 HALF_UP). Class is not `@Immutable`, so mutation works.
- `FnbStockService`: inactive ingredients skipped in `addNeeds`; SALE movement `unitCost` = balance average read before deducting; per-item cost computed in a second loop after the deductions (product recipe + that item's options' recipes, active only; null when no active line or any used ingredient lacks a balance/average; `product == null` stays null); already non-null `costAmount` is not overwritten; SALE idempotency key check unchanged. `reverseForRefundedOrder` passes `sale.getUnitCost()` as SALE_REVERSAL unit cost.

## Deviations
None.

## Notes / open questions
- Existing tests in `src/test/.../FnbStockServiceTest.java` (lines ~217, ~441) and `FnbInventoryServiceTest.java` (~148, ~768) call `InventoryMovementEntity.record`; check whether any use the removed 11-arg form (W4 to adapt). Main code: only `FnbStockService` used it; `FnbInventoryService` still uses the 10-arg form.
- A balance created on the fly inside `lockedBalance` is not added to the `balances` map, so its items get null cost (average is null anyway) — consistent with the rule.
- Not compiled or tested (policy).
