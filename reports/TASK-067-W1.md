# TASK-067 W1 report

Status: IMPLEMENTED, NOT COMPILED, NOT RUN (worker rule: no Maven, no git).

## Changed files
- NEW `src/test/java/org/lite/project/cashiersaas/integration/fnb/FnbStockConcurrencyIntegrationTest.java` (extends `AbstractPostgresIntegrationTest`, `@TestInstance(PER_CLASS)`, `ApiTestSupport` set up like the sibling tests).
- NEW `reports/TASK-067-W1.md` (this file).

## Parallel harness (private `runInParallel`)
Fixed pool of 8 threads; each task does `ready.countDown(); go.await(); task.call()`; `ready` counts `min(tasks, 8)` (queued tasks beyond 8 run after the gate is open); `ready.await(60 s)` asserted, then `go.countDown()`, every `future.get(60 s)`; `finally { go.countDown(); pool.shutdownNow(); }`. No `Thread.sleep`. Calls use raw `api.call` (status not asserted inside the thread); the test asserts the legal outcome set afterwards. A timeout (deadlock) surfaces as `TimeoutException`.

## Scenarios and exact assertions (each test onboards its own owner/tenant/outlet/ingredient)
- A `sameMovementKeyAndPayloadInParallelAppliesExactlyOnce`: stock 0 (no initial movement), 8 parallel RESTOCK 100, one shared key. No status >= 500; every non-201 is exactly 409; >= 1 response 201; all 201 bodies carry one distinct movement id; exactly 1 movement row for (tenant, key); exactly 1 movement for the item; balance == 100; ledger == balance.
- B `sameMovementKeyWithDifferentPayloadsInParallelAppliesExactlyOneOfThem`: 8 parallel RESTOCK 100..107, one key. No 5xx; every non-201 is 409; exactly one distinct id in 201 bodies AND exactly one 201 response (payloads differ so a replay 201 is impossible); exactly 1 row for the key; that row's id == the 201 id; stored delta in [100,107] and balance == stored delta; ledger == balance.
- C `parallelRestocksAndWastesWithDistinctKeysLoseNoUpdate`: start 100; 15 parallel requests (10 RESTOCK 10, 5 WASTE 4, interleaved, unique keys). All 15 exactly 201 with 15 distinct ids; balance == 180; 15 movements with the scenario key prefix; 16 movements for the item; ledger == balance.
- D `parallelPaymentsOfDifferentOrdersDeductTheSharedIngredientExactlyOncePerOrder`: stock 1000 g, recipe 50 g, 8 unpaid POS orders paid in parallel (unique keys). No 5xx; all 200/201; 8 SALE movements for the item; per order: 1 SALE movement, delta -50, key `sale:{orderId}:{itemId}`, 1 payment row, order PAID; balance == 600; ledger == balance.
- E1 `parallelPaymentsOfOneOrderWithTheSameKeySettleAndDeductOnce`: 8 parallel pays of one order with the same key and body. No 5xx; each non-success is exactly 409; >= 1 success; all successful responses carry one distinct `receiptNumber`; 1 payment row; 1 SALE movement; balance == 950; order PAID; ledger == balance. (Reading of `FnbPaymentService.pay`: the tenant lock serialises the requests, so the 7 later ones should all be replays answered 200/201; the assertion follows the packet's wider legal set and does not require that.)
- E2 `parallelPaymentsOfOneOrderWithDifferentKeysAllowExactlyOneAndDeductOnce` (own tenant): 8 parallel pays, different keys. No 5xx; exactly 1 success; exactly 7 responses 409 (the status asserted by `concurrentPaymentsWithDifferentKeysSettleExactlyOnce`); 1 payment row; 1 SALE movement; balance == 950; order PAID; ledger == balance.
- F `mixedPaymentsRestocksAndWastesFinishWithoutDeadlockAndKeepTheLedgerConsistent`: stock 1000, recipe 50; 12 requests released together (4 payments of different orders, 4 RESTOCK 100, 4 WASTE 30, unique keys, interleaved). All futures finish in 60 s (no deadlock); no 5xx; payments 200/201, movements exactly 201; per order 1 SALE movement and 1 payment; balance == 1080; 13 movements for the item (initial + 8 manual + 4 sale); ledger == balance.
- G `negativeStockDoesNotBlockParallelPaymentsAndReportsMinusWarnings`: stock 100, reorder 10, recipe 50, 6 orders paid in parallel. No 5xx; all 200/201; balance == -200; 6 SALE movements, one per order; orders PAID; exactly 4 responses carry a `(minus)` warning; the flattened multiset of all `stockWarnings` equals `{"Susu: sisa 0 g (habis)", "... -50 g (minus)", "... -100 g (minus)", "... -150 g (minus)", "... -200 g (minus)"}` (wording format copied from `FnbInventoryFlowIntegrationTest`: `"Susu: sisa -26 ml (minus)"`, `"Telur: sisa 0 butir (habis)"`); ledger == balance. The multiset is deterministic because payments are serialised by the tenant lock and the running balances are 50, 0, -50, ... -200.

## Helpers copied (private, none shared)
From `FnbInventoryFlowIntegrationTest`: `adminItems`, `stockItems`, `orders`, `productRecipeUrl`, `itemJson`, `movementJson`, `paymentJson`, `createIngredient`, a one-line `recipeJson`/`setRecipe` (single-line variant), `createOrder` (single quantity, no options), `getOrder`, `readList`, `decimal`, `assertDecimal`, `balance`, `count`, `movementsOfItem`, `saleMovements`, `assertLedgerMatchesBalance`. From `FnbPayFirstFlowIntegrationTest`: `countPayments`, the latch/pool pattern (generalised into `runInParallel`). New small private helpers: `payCashCall`, `postMovement` (raw, no status assertion), `createdMovementIds`, `assertNoServerError`, `assertAllStatus`, `assertAllPaymentSuccess`, `describe`. Reused `ApiTestSupport` (onboardOwner, createCategory, createProduct, call, send, read, jdbc).

## Remaining gaps / risks (for the orchestrator)
- Not compiled/run by policy: please check imports/generics at test-compile time.
- Scenario B asserts exactly one 201; this relies on differing fingerprints per quantity (as in the existing 409 test for a changed quantity).
- G assumes the stock warning is computed from the post-deduction balance of each payment and that the "OK" (50 > reorder 10) payment carries no warning; if `FnbStockService` batches or orders warnings differently the multiset assertion will show it (kept strict on purpose).
- Hikari pool size is not changed; 8 concurrent requests assume the test pool has at least 8 connections (default 10).
- Known gaps from the packet remain open (mock gateway resolve concurrency, concurrent ingredient deactivation, multi-outlet isolation).

## Deviations
None. No production code, existing test, `.ai`, `tasks`, `postman` file touched.
