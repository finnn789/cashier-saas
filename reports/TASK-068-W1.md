# TASK-068-W1: Mutant copies report

Worker W1 (haiku). Six mutated copies written to `/c/Users/arips/AppData/Local/Temp/claude/b86bedade2d6e9cd/1a275043-4c0d-4192-889c-4c9f74f1bb86/scratchpad/mutants` (outside the repo). Each was made with `cp` from the original and then exactly one line-anchored edit; each edit was preceded by a check that the target line matched the expected text exactly. No file under `src/**` was touched; no Maven or git write commands were run.

Each diff below is `git diff --no-index` (original vs mutant); each shows exactly one hunk.

## Summary
- M1 FnbInventoryService.recordMovement: deleted `inventory.lock(balance);` (line 111), leaving an empty `else { }`.
- M2 FnbStockService.consumeForPaidOrder: deleted `inventory.lock(balance);` (line 73), leaving an empty `else { }`.
- M3 FnbPaymentService.pay: deleted only `orders.lockTenant(principal.getTenantId());` (line 50); CRLF line endings of the other lines preserved.
- M4 FnbInventoryService.recordMovement: replaced `!existing.getRequestFingerprint().equals(fingerprint)` with `false` (line 102).
- M5 FnbStockService.consumeForPaidOrder: `balance.apply(delta.negate());` changed to `balance.apply(delta);` (line 75).
- M6 FnbStockService.consumeForPaidOrder: deleted `if (inventory.movementByKey(tenantId, key).isPresent()) continue;` (line 65).

## M1_FnbInventoryService

```diff
@@ -108,7 +108,6 @@ public class FnbInventoryService {
             balance = InventoryBalanceEntity.create(tenantId, outletId, item);
             inventory.persist(balance);
         } else {
-            inventory.lock(balance);
         }
         balance.apply(delta);
         InventoryMovementEntity movement = InventoryMovementEntity.record(tenantId, outletId, item, null, type, delta,
```

## M2_FnbStockService

```diff
@@ -70,7 +70,6 @@ public class FnbStockService {
                 balance = InventoryBalanceEntity.create(tenantId, outletId, inventoryItem);
                 inventory.persist(balance);
             } else {
-                inventory.lock(balance);
             }
             balance.apply(delta.negate());
             inventory.persist(InventoryMovementEntity.record(tenantId, outletId, inventoryItem, order.getId(), InventoryMovementEntity.Type.SALE,
```

## M3_FnbPaymentService

```diff
@@ -47,7 +47,6 @@ public class FnbPaymentService {
         if (request.getMethod() == PaymentMethod.MOCK_GATEWAY)
             return mockGateway.initiate(principal, outletId, orderId, request);
         if (request.getMethod() == null) throw new InvalidOperationException("Metode pembayaran wajib diisi.");
-        orders.lockTenant(principal.getTenantId());
         MerchantAccessContext context = guard.requireOutlet(principal, outletId);
         SalesOrderEntity order = current(principal, outletId, orderId);
         String fingerprint = fingerprint(request.getMethod(), request.getAmountTendered());
```

## M4_FnbInventoryService

```diff
@@ -99,7 +99,7 @@ public class FnbInventoryService {
         String fingerprint = fingerprint(type, itemId, delta, reason);
         InventoryMovementEntity existing = inventory.movementByKey(tenantId, request.getIdempotencyKey()).orElse(null);
         if (existing != null) {
-            if (!existing.getRequestFingerprint().equals(fingerprint))
+            if (false)
                 throw new OperationConflictException("Kunci idempotensi telah digunakan.");
             return FnbInventoryMapper.movement(existing);
         }
```

## M5_FnbStockService

```diff
@@ -72,7 +72,7 @@ public class FnbStockService {
             } else {
                 inventory.lock(balance);
             }
-            balance.apply(delta.negate());
+            balance.apply(delta);
             inventory.persist(InventoryMovementEntity.record(tenantId, outletId, inventoryItem, order.getId(), InventoryMovementEntity.Type.SALE,
                     delta.negate(), "Penjualan " + order.getOrderNumber(), key,
                     tokens.digest("sale|" + order.getId() + "|" + itemId + "|" + delta.toPlainString()), context.getMembership().getId()));
```

## M6_FnbStockService

```diff
@@ -62,7 +62,6 @@ public class FnbStockService {
         for (Map.Entry<UUID, BigDecimal> need : needs.entrySet()) {
             UUID itemId = need.getKey();
             String key = "sale:" + order.getId() + ":" + itemId;
-            if (inventory.movementByKey(tenantId, key).isPresent()) continue;
             InventoryItemEntity inventoryItem = inventoryItems.get(itemId);
             BigDecimal delta = need.getValue();
             InventoryBalanceEntity balance = balances.get(itemId);
```

