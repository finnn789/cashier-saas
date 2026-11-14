# TASK-079 W4 report: unit tests for M6

Status: written, NOT compiled and NOT run (policy: no Maven/tests). No production code touched, no integration test touched.

## Files (all under `src/test/java/org/lite/project/cashiersaas/unit/`)
- `entity/InventoryBalanceEntityTest.java` (extended): 10 `receive`/average cases.
- `service/FnbStockServiceTest.java` (extended): cases 13-20 plus helpers `sale(item, delta, unitCost)` and `balance(item, onHand, average)`.
- `service/FnbInventoryCostAndStockCountTest.java` (new): restock price, cost visibility, stock count.
- `dto/FnbSalesReportMapperTest.java` (new): report mapper math.
- `service/FnbSalesReportServiceTest.java` (new): `FnbSalesReportService.report` wiring (new `FnbRefundRepository` dependency).
- `reports/TASK-079-W4.md` (this file).

## Existing tests vs new signatures
No existing unit test needed an edit: all existing `InventoryMovementEntity.record` calls use the 10-arg form (still present), no test used `FnbInventoryMapper`, `FnbSalesReportMapper`, `ProductRow` or built `FnbSalesReportService` by hand.

## Cases
Moving average (`InventoryBalanceEntityTest`): no average on a new balance; first price; weighted blend; scale 4 HALF_UP (1.6667, 0.00015 -> 0.0002, price with 5 decimals); zero stock reset; negative stock reset; positive stock without average takes price; receive without price keeps average (and stays null if none); `apply` never changes the average.

`FnbStockService` (cases 13-20): inactive ingredient not deducted and excluded from cost; recipe of only inactive ingredients touches no stock and cost null; SALE unitCost = balance average, average unchanged, cost scale 2 HALF_UP (0.4428 -> 0.44); item cost = product + that item's own option recipes (150 / 100 split across two lines); cost null when ingredient has no average, mixed averages, or no balance row (and SALE unitCost null there); item with no recipe / product null -> null while costed items keep cost; existing `costAmount` not overwritten when SALE key already exists (12.345 -> 12.35 kept); SALE_REVERSAL copies the sale's unit cost (including null) and average unchanged.

`FnbInventoryCostAndStockCountTest`:
- recordMovement: RESTOCK with price (blend 6.25, movement unitCost = price, fingerprint suffix `|7.5`, cashier response hides cost); owner response shows cost; RESTOCK without price keeps average and fingerprint has no suffix (accepted deviation: movement unitCost = balance average; null when no average); first priced RESTOCK on missing balance; WASTE/ADJUSTMENT record average before change and never move it; unitCost on WASTE/ADJUSTMENT rejected ("Harga beli hanya untuk restock.") before any inventory call; replay with same price (7.000 vs 7) returns existing, different price / missing price -> conflict; replay hides cost from cashier.
- reads: item list (owner sees average, null when never priced, cashier always null), movement history owner/cashier, deactivate shows owner average.
- stockCount: ADJUSTMENT shortfall with reason "Stok opname", key `count:<digest(key|itemId)>`, fingerprint from sorted lines + note, unitCost = average before change, average unchanged, response lines; surplus; note trimmed into reason and fingerprint, blank note -> no note; zero difference writes no movement but reports the line (movementId null); missing balance created, null cost; processing and response in ascending item id regardless of request order (InOrder lock/persist); duplicate item ids rejected before any lookup; unknown/inactive item -> `ResourceNotFoundException`, nothing written; owner-only (`requireOwner`, never `requireOutlet`); replay returns previous result (previous = counted - delta, zero-difference line has null movementId) with no lock/persist/balance read; conflict on same key with different content.

`FnbSalesReportMapperTest`: total gross profit = net sales (after discount) - COGS (900 - 400 = 500); no cost known -> profit = net sales, and can be negative; summary carries itemsWithoutCost, refundCount/Amount and existing fields; empty period; per-menu gross profit = grossSales (before discount) - COGS with itemsWithoutCost; hourly, cashier and shift mapping (status name, no-shift row keeps null fields).

`FnbSalesReportServiceTest`: full wiring for a filtered report (cost, refund totals, hourly with zone `Asia/Jakarta`, cashiers, shifts, top products); refunds queried by date range only while cost/hourly/cashiers/shifts get the order-status and payment-method filters; date range converted to outlet-zone instants.

## Suspected production issues / notes (none blocking)
- `FnbInventoryService.stockCount`: item lookups (404) run before the replay/conflict check, so replaying a count after one of its ingredients was deactivated returns 404 instead of the stored result (W2 already noted).
- `FnbStockService.consumeForPaidOrder`: on a replayed payment where an item's `costAmount` is still null, cost is recomputed from the CURRENT average, which can differ from the average used for the original SALE movements. Minor, only when the first run left cost null.
- `itemCost` counts a line as "used" when an active recipe line exists, even if that ingredient's SALE key already existed; fine, noted only for completeness.
- Risk to verify at compile time: `FnbSalesReportServiceTest` mocks the concrete repositories `FnbSalesReportRepository` / `FnbRefundRepository` (non-final classes; Mockito inline default should be fine). `@InjectMocks` relies on the constructor generated by `@RequiredArgsConstructor` (`guard`, `reports`, `refunds`).

## Fix round 1
Correction: my earlier claim "no existing unit test needed an edit" was wrong.
- `FnbInventoryServiceTest`: `FnbInventoryService` now reads `context.getMembership().getRole()` (showCost) in updateItem, deactivateItem, items and movements, so unstubbed guards returned null (NPE). Added `when(guard.requireOwner(owner, outletId)).thenReturn(context)` to the 3 owner-path tests (updateItemAllowsUnitChangeWhenNoMovementExists, updateItemWithUnchangedUnitNeverQueriesMovements, deactivateItemMarksItemInactiveAndReturnsIt) and `when(guard.requireOutlet(cashier, outletId)).thenReturn(context)` to `listFixture()` (items tests) and the 3 movements tests (boundary paging, unknown item, preserve order). Reused the class's existing `context` fixture; no new helpers.
- `FnbInventoryCostAndStockCountTest.stockCountCreatesAMissingBalanceAndRecordsNoCostForIt`: Mockito strict stubs treat the `persist` overloads as the same stubbed method, so the `persist(InventoryMovementEntity)` stub from `stubMovementIds()` made the `persist(InventoryBalanceEntity)` call (new balance) fail with PotentialStubbingProblem. The test asserts no movement id, so the `stubMovementIds()` call was removed there (class stays strict). Other tests using `stubMovementIds()` never persist a balance.
- Not run (no Maven).
