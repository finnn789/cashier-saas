# TASK-079 W2 report

Changed (all under src/main/java/org/lite/project/cashiersaas/): service/FnbInventoryService.java, controller/FnbInventoryController.java, dto/FnbInventoryMapper.java, dto/request/FnbStockMovementRequest.java, dto/response/FnbInventoryItemResponse.java, dto/response/FnbStockMovementResponse.java. New: dto/request/FnbStockCountRequest.java, FnbStockCountLineRequest.java, dto/response/FnbStockCountResponse.java, FnbStockCountLineResponse.java.

Done: optional unitCost on movement request (RESTOCK only, else "Harga beli hanya untuk restock."; RESTOCK uses balance.receive, others apply; movement unitCost = entered price for RESTOCK, balance average before change otherwise); fingerprint suffix only when unitCost non-null; mapper showCost (owner only) for create/update/deactivate/list/record/movements and replay; stock count endpoint POST /merchant/outlets/{outletId}/fnb/inventory/stock-counts (201, ApiResponse.created) with owner guard, duplicate check, 404 for unknown/inactive items, request fingerprint, per-line keys, replay/conflict, ADJUSTMENT with "Stok opname[: note]" and average-before unitCost. Extracted private lockedBalance helper shared by recordMovement and stockCount.

Deviations / notes:
- Replay: lines without a movement (zero difference in the first call) are returned with previous = counted, difference 0, movementId null; replay never re-applies any line once any key exists. All-zero count: no movement, so re-evaluated (accepted limit).
- Duplicate-id check runs before item lookup; item lookups (404) run before the replay/conflict check, so a replay after an ingredient was deactivated returns 404.
- Stock count returns HTTP 201 (like recordMovement) also on replay.
- Not compiled (no Maven by rule); depends on W1: getAverageUnitCost, receive, 12-arg record, getUnitCost.

Open questions: none.
