# TASK-070 W1 report

Status: implemented, NOT compiled or run (policy: workers never run Maven). Only changed
`src/test/java/org/lite/project/cashiersaas/integration/fnb/FnbStockConcurrencyIntegrationTest.java` and this report.

## Verified against real code (before use)
- Discount: `POST /api/v1/merchant/outlets/{o}/fnb/orders/{id}/discount`, owner only, `FnbDiscountRequest {type (NOMINAL|PERCENTAGE), value, reason}`, 200 `ApiResponse`. Table `discounts(amount, value, type, reason, sales_order_id, ...)`.
- Cancel: `POST /api/v1/cashier/outlets/{o}/fnb/orders/{id}/cancel`, `FnbCancelOrderRequest {reason}`, 200.
- Payment: `FnbPaymentRequest {method, amountTendered, idempotencyKey}`; tables `payments(amount_paid, change_amount, sales_order_id)`, `sales_orders(subtotal, discount_amount, tax_amount, service_charge_amount, total, payment_status, status)`.
- Movement fingerprint = type|item|delta (stripTrailingZeros)|reason, so replay with `RESTOCK`, same quantity and reason "Belanja" matches.
- `applyDiscount` needs rate snapshots, which POS orders get at creation (`FnbPosOrderService` -> `snapshotRates`).

## Changes
Helpers added: `ROUNDS=15`, `import java.util.Map`, `cancelCall`, `discountCall` (NOMINAL 1000, reason "Promo"), `is2xx`, `is4xx`. Everything else reused (`runInParallel`, `createOrder`, `payCashCall`, `postMovement`, `countPayments`, `saleMovements`, `balance`, `assertLedgerMatchesBalance`, ...). `payCashCall` tenders 1000000.

### A (`sameMovementKeyAndPayloadInParallelAppliesExactlyOnce`)
Existing assertions untouched. Added after them: sequential `postMovement` with the same key/body asserts status exactly 201, `$.data.id == ids.get(0)`, still exactly 1 movement row for (tenant, key), balance exactly 100, ledger == balance. Summary line `[concurrency] A same key+payload: 201=.. 409=.. of 8 parallel requests`.

### B (`sameMovementKeyWithDifferentPayloadsInParallelAppliesExactlyOneOfThem`)
Existing assertions untouched. Added: winner quantity = `delta.intValueExact()` of the stored movement. (1) sequential request, same key, quantity winner+50 -> exactly 409, 1 row for the key, balance == delta. (2) sequential request with the winner's exact payload (RESTOCK, winner quantity, "Belanja") -> exactly 201, same movement id as the 201 winner, still 1 row, balance unchanged, ledger == balance. Summary line `[concurrency] B ...`.

### H (`paymentRacingWithCancelLeavesExactlyOneConsistentOutcomePerOrder`)
Own owner + cashier, product, ingredient (initial 10000 g, recipe 50 g). 15 rounds, each: fresh order (created by the cashier), `runInParallel` of CASH payment (unique key, cashier) and cancel with reason (cashier). Per round: no 5xx; exactly one of the two is 2xx (XOR); the loser is `>=400 && <500`. DB state: payment won -> `payment_status='PAID'`, status != CANCELLED, 1 payment row, 1 SALE movement with delta -50; cancel won -> `UNPAID` + `CANCELLED`, 0 payment rows, 0 SALE movements. After the loop: wins sum to 15, balance == 10000 - 50 x paymentWins, SALE movement count for the item == paymentWins, ledger == balance. Summary line `[concurrency] H pay vs cancel: payment won=.. cancel won=.. of 15 rounds`.

### I (`paymentRacingWithDiscountKeepsTheChargedTotalAndTheDiscountRowConsistent`)
Same setup. Per round: undiscounted total read from DB right after creation; parallel CASH payment (cashier) and NOMINAL 1000 discount (owner). Asserts: no 5xx; payment always 2xx; discount 2xx or 4xx. Consistency from DB: order PAID; 1 payment row; 1 SALE movement; `change_amount == amount_paid - persisted total`; `total == subtotal - discount_amount + tax + service_charge`. Discount 2xx -> 1 `discounts` row, `discounts.amount == 1000`, `sales_orders.discount_amount == 1000`, total < undiscounted total. Discount 4xx -> 0 rows, `discount_amount == 0`, total == undiscounted total. After loop: balance == 10000 - 50 x 15, 15 SALE movements, ledger == balance. Summary line `[concurrency] I pay vs discount: discount applied before payment=.. rejected=.. of 15 rounds`.

## Re-read for compile safety
Lambdas capture only effectively-final locals declared inside the loop body; tasks added via `ArrayList<Callable<MvcResult>>`; all test methods `throws Exception`; `Map<String,Object>` with BigDecimal casts; no wildcards, no Thread.sleep.

## Risks / notes
- No contradiction found between the packet's legal-outcome sets and the real code (pay on a cancelled order -> `confirmPaid` -> `requireNewUnpaid` -> 400; cancel/discount on a paid order -> 400; second discount -> 409).
- The discount wins only if tenant rate snapshots are non-null (POS creation sets them from the tenant); if they were null the discount would always be 400 and I would only ever log "rejected". Assertions stay valid either way.
- Counts are timing dependent and are not asserted; the orchestrator must read the log lines to judge whether M3 can be killed. With MockMvc on a pool, overlap of two requests is likely but not guaranteed.
- `send()` in ApiTestSupport treats 200 and 201 as interchangeable; the new A/B 201 assertions use `postMovement` (raw `call`) so they are strict.
- Not compiled, not run.
