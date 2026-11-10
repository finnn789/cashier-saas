# TASK-061 W2 report

Status: SUBMITTED (not compiled, no tests run by policy; orchestrator compiles).

## Changed files (all new)
- src/main/java/org/lite/project/cashiersaas/controller/FnbInventoryController.java
- src/main/java/org/lite/project/cashiersaas/service/FnbInventoryService.java
- src/main/java/org/lite/project/cashiersaas/dto/FnbInventoryMapper.java
- src/main/java/org/lite/project/cashiersaas/dto/request/FnbInventoryItemRequest.java
- src/main/java/org/lite/project/cashiersaas/dto/request/FnbStockMovementRequest.java
- src/main/java/org/lite/project/cashiersaas/dto/response/FnbInventoryItemResponse.java
- src/main/java/org/lite/project/cashiersaas/dto/response/FnbStockMovementResponse.java
- reports/TASK-061-W2.md

## Acceptance progress
- Six endpoints with exact paths/guards: item create/update/delete = requireOwner under /merchant; list, movements = requireOutlet under /cashier; POST movements = `type == ADJUSTMENT ? requireOwner : requireOutlet`.
- Create: item persisted + flush (duplicate name -> DB unique violation mapped by existing handler), zero balance, optional RESTOCK "Stok awal" with key `init:{itemId}`.
- Update: rejects unit change when `movements(...,0,1)` non-empty; flush after update; `initialQuantity` ignored.
- Delete: `deactivate()`, returns item.
- List: items + ONE `balances(...)` call, in-memory `status` filter; missing balance reported as 0 / OUT.
- Movement: SALE rejected; zero rejected; RESTOCK/WASTE must be > 0; ADJUSTMENT signed; reason required for WASTE/ADJUSTMENT, default "Restock"; fingerprint via `PublicOrderTokenService.digest`; replay same fingerprint returns existing, different -> `OperationConflictException("Kunci idempotensi telah digunakan.")`; balance locked with `lock` (created if row missing); flush after persist so a concurrent duplicate key surfaces in-request.
- Movement list: page 0..1_000_000, size 1..50 else `InvalidOperationException`; plain list; item must exist (404).
- Tenant+outlet scoping via principal tenant id and path outlet on every repository call.

## Verified by reading
Entities, FnbInventoryRepository signatures, MerchantAccessGuard, PublicOrderTokenService.digest, ApiResponse factories, exception classes, DTO style. Re-read for imports/generics; no records, no @Data, no wildcards, no new exception types.

## Deviations / notes
- Reason is trimmed before fingerprinting (so whitespace-only differences replay as same request).
- Mapper takes `(item, balance)` with nullable balance (not in contract; fields exactly as contract).
- Movement replay is looked up after item existence check, so an unknown item returns 404 even for a used key.
- Movement `reason` trimmed; the `status` query param binds directly to `InventoryBalanceEntity.Status` (invalid value -> framework 400).

## Remaining
Orchestrator compile and review.
