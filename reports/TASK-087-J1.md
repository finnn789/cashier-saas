# TASK-087 J1 report

Status: DONE (implementation), not compiled, not tested.
Baseline: commit `20faa7a` + uncommitted TASK-084/085/086 tree.

## Changed files (all modified, none new)
- dto/response: `FnbStaffOrderResponse` (+cashierName, methodLabel, receiptNumber), `FnbProductResponse` (+categoryName), `FnbRecipeResponse.Line` (+costEstimate), `FnbStockMovementResponse` (+actorName; actorMembershipId kept).
- dto: `FnbStaffOrderMapper.order(order, items, options, creatorName, payment)`, `FnbCatalogMapper.recipe(lines, costByItem)` + product categoryName, `FnbInventoryMapper.movement(entity, showCost, actorName)`.
- service: `FnbStaffOrderService`, `FnbPosOrderService`, `FnbCatalogService`, `FnbInventoryService`.
- repository: `FnbOrderRepository`, `FnbCatalogRepository`, `FnbInventoryRepository`.

## Queries added (all batch by id set, tenant/outlet scoped)
- `FnbOrderRepository.creatorNames(tenant, outlet, orderIds)`: audit FNB_ORDER_CREATED actor full name per order.
- `FnbOrderRepository.paymentsByOrder(tenant, outlet, orderIds)`: payments with `join fetch receivedBy`.
- `FnbInventoryRepository.memberNames(tenant, membershipIds)`: membership id to user full name, tenant filtered.
- `FnbCatalogRepository.products`: added `fetch(product.category)` (no per-row category load).
- Recipe cost reuses `inventory.balances(...)` (one query) and `FnbStockService.itemCost(1, List.of(line), balances)` (same package, not copied); result scaled 2 HALF_UP.

## Contract points
- D1 done for create/replay/replaceItems (FnbPosOrderService.response), detail and list. methodLabel falls back to PaymentMethod name; null while UNPAID.
- D2 done. D3 done (product and option GET/PUT). D4 done (list, record, idempotent replay).

## Deviations (please review)
1. D1 `createdByMembershipId` does not exist on `SalesOrderEntity` (only on legacy shared `OrderEntity`), so the order has no creator column and no migration is allowed. cashierName = full name of the actor of the order's `FNB_ORDER_CREATED` audit row (that is the creating staff); if none, falls back to the paying cashier (`payment.receivedBy`); else null (QR/unknown/unpaid without audit). Decide if you prefer a payment-only or a creator column later.
2. D3 `showCost` hiding: recipe endpoints are `requireOwner`, so cost is always owner-visible; no showCost branch added. Cost is null when an ingredient has no balance/average cost or the item is inactive (itemCost semantics).
3. D4 actor lookup is by membership id restricted to the tenant (not outlet). SALE movements without actor give null.

## Existing tests that may need updating
- Any test calling `FnbInventoryService`/`FnbPosOrderService`/`FnbStaffOrderService`/`FnbCatalogService` with strict Mockito stubs: new repository calls (`creatorNames`, `paymentsByOrder`, `memberNames`, `balances` in recipe) return empty Map/List from unstubbed mocks, so should be fine; strict-stubs/verifyNoMoreInteractions tests would need an update. No test source references the changed mapper signatures (grep verified).

## Open questions
- None blocking. Order list now makes 2 extra queries per page (audit names, payments).

Test status: NOT RUN (policy).
