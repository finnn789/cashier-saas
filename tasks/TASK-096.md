# TASK-096: Add productId to staff order item response (backend)

Status: DONE, REVIEWED, COMPILED, NOT COMMITTED (2026-11-25, memory v95).

## User instruction (2026-11-25)
Unblock TASK-095 "reopen held order into the cart": the POS needs the product id of each order line to rebuild the cart and call replace-items.

## Verified facts (orchestrator, source read)
- `dto/response/FnbStaffOrderItemResponse.java` fields: `itemId`, `productName`, `unitPrice`, `quantity`, `lineTotal`, `note`, `options`. No product id. Class has `@JsonInclude(NON_NULL)`.
- `entity/fnb/OrderItemEntity.java:29-30` `@JoinColumn(name = "product_id") ProductEntity product`.
- Only builder: `dto/FnbStaffOrderMapper.java:52-62` `item(...)`; options already map `optionId` with a null guard (:66).

## Work (J1, `java-worker`)
- Add `private UUID productId;` to `FnbStaffOrderItemResponse` (after `itemId`).
- In `FnbStaffOrderMapper.item`, set it from `item.getProduct().getId()` with the same null-guard style as options. Reading the id of a lazy proxy must not trigger a load; add no fetches.
- Nothing else: no service, query, migration, proto or Postman change. No tests (policy).

Reserved: `src/main/java/org/lite/project/cashiersaas/dto/response/FnbStaffOrderItemResponse.java`, `src/main/java/org/lite/project/cashiersaas/dto/FnbStaffOrderMapper.java`.
Report: `reports/TASK-096-J1.md`.
Acceptance: every `FnbStaffOrderResponse` item carries `productId`; additive only; follows AGENTS.md DTO rules.
