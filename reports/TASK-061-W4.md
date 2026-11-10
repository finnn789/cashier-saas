# TASK-061 W4 report: stock deduction on paid + warnings

Status: implemented, not compiled (orchestrator compiles), tests not run by policy.

## Changed files
- `src/main/java/org/lite/project/cashiersaas/service/FnbStockService.java` (new)
- `src/main/java/org/lite/project/cashiersaas/service/FnbPaymentService.java` (settle hook, `FnbStockService stock` injected, `java.util.List` import)
- `src/main/java/org/lite/project/cashiersaas/service/MockFnbGatewayService.java` (private settle hook, `FnbStockService stock` injected, `java.util.List` import)
- `src/main/java/org/lite/project/cashiersaas/dto/response/FnbPaymentResponse.java` (`List<String> stockWarnings = new ArrayList<>()`)

## Behaviour
- `consumeForPaidOrder(context, order)`: `orders.items` + `orders.itemOptions` (one call each), ONE `productRecipes` and ONE `optionRecipes` call, needs aggregated in `TreeMap<UUID, BigDecimal>`; lines with null product/option skipped; option need = option recipe qty x parent item quantity. Empty needs -> `List.of()` (no queries beyond the 4 above).
- One `balances(...)` call; per item in key order: `movementByKey("sale:{orderId}:{itemId}")` present -> skip; else missing balance -> `InventoryBalanceEntity.create` + persist, existing -> `lock`; `apply(delta.negate())`; persist `InventoryMovementEntity.record(..., SALE, -delta, "Penjualan " + order.getOrderNumber(), key, tokens.digest("sale|orderId|itemId|delta"), membershipId)`.
- Warnings: `"<name>: sisa <qty> <unit> (minus|habis|menipis)"` for touched balances with status != OK. Never throws on stock level.
- Hook position in both settle methods: right after `orders.flush()` (order confirmed paid, payment persisted+flushed), before the audit persist, inside the existing transaction; the response gets `setStockWarnings(...)`. Replay/idempotent paths untouched (empty default list).

## Verified by reading
- Real signatures of FnbInventoryRepository, InventoryBalanceEntity (create/apply/status), InventoryMovementEntity.record + Type.SALE, RecipeLineEntity getters, OrderItemEntity/OrderItemOptionEntity getters, `SalesOrderEntity.getOrderNumber()` (class-level @Getter), `PublicOrderTokenService.digest(String)` (public), BaseEntity.getId.
- No other code constructs the two services manually; no dependency cycle (FnbStockService depends only on FnbOrderRepository, FnbInventoryRepository, PublicOrderTokenService).

## Remaining / notes
- Compile by orchestrator. Warning for a skipped (already-moved) item is not emitted (replay-safe).
- `lock(balance)` is `refresh`, run on the freshly-queried balance; no unflushed state is lost on it.
- No deviations from the contract.
