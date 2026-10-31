# TASK-049: Checkout Service Refactoring & Optimization

Status: COMPLETE / UNIT TESTS PASS
Memory version: 38

## Task Scope & Acceptance
Refactor `CashierSaleService.checkout(...)` in `src/main/java/org/lite/project/cashiersaas/service/CashierSaleService.java` according to the approved plan (`checkout-refactoring-plan.md`):
1. **Eliminate Redundant Lists**: Remove `catalogItems`, `quantities`, and `lineTotals` lists.
2. **Single-Pass Item Processing**: Introduce a private helper record or static nested class `VerifiedItem(CashierCatalogItemEntity catalogItem, BigDecimal quantity, BigDecimal lineTotal)` inside `CashierSaleService`. In the single loop over `request.getItems()`, perform distinct validation, active item lookup, quantity validation, line total calculation, total accumulation, and collect into `List<VerifiedItem>`.
3. **Preserve Exact Invariants**:
   - `merchantAccessGuard.requireOutlet(principal, outletId)`
   - `validatedKey(key)` and `fingerprint(outletId, request)`
   - Idempotency replay check via `saleRepository.findByKey(...)` and `validateReplay(...)`
   - Distinct item check via `validateDistinctItem(...)`
   - Quantity check via `validateQuantity(...)`
   - Total check via `validateTotal(...)`
   - Payment validation via `validatePayment(...)`
   - Create sale via `CashierSaleEntity.createPaidSale(...)`
   - `saleRepository.persist(sale)`
   - For each verified item, snapshot via `CashierSaleItemEntity.snapshot(...)`, call `saleRepository.persist(line)`, and add to `lines`
   - `auditLogRepository.persist(...)` with exact action and details format
   - Return `CashierMapper.toSaleResponse(sale, lines)`
4. **Preserve Line Ordering**: The resulting `lines` and response must preserve the exact order of `request.getItems()`.
5. **Preserve Test Mock Compatibility**: Retain `saleRepository.persist(line)` invocations so existing unit test mocks (`verify(saleRepository).persist(lineCaptor.capture())`) remain valid.

## Exclusive Reserved Writes
- `src/main/java/org/lite/project/cashiersaas/service/CashierSaleService.java`
- `reports/TASK-049.md`

## Invariants & Rules
- Do NOT modify any other files (no changes to controllers, repositories, entities, or test suites).
- Do NOT run unit, API, integration, or Maven test commands (tests not run by policy per AGENTS.md).
- Follow clean Java 17 Spring Boot coding standards.
- Persist report at `reports/TASK-049.md` upon completion.
