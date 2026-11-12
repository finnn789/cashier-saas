# TASK-075 W2 report (Java tests)

Status: SUBMITTED. Tests NOT run by policy (no Maven / no test execution).

## Changed files (all under `src/test/**`)

| File | Change |
|---|---|
| `unit/entity/SalesOrderEntityTest.java` | OPEN default; added `confirmPaid` -> COMPLETED+PAID, cancel/discount only on OPEN+UNPAID, `replaceLines` rules (totals = subtotal+tax+service charge; rejects paid, cancelled, discounted, null service type) |
| `unit/service/FnbPosOrderServiceTest.java` (NEW) | Mockito `replaceItems`: happy path (InOrder lockTenant -> byId -> price with snapshot rates 10/5 not tenant 11/2 -> deleteLines -> persist line -> flush -> audit `FNB_ORDER_ITEMS_REPLACED`, details `subtotal=100.00->200.00`), lock before load, not found (404 exception), paid, cancelled, QR source, pending attempt, discount: all `OperationConflictException`, no delete/persist/price/audit |
| `unit/configuration/SecurityConfigurationCorsTest.java` (NEW) | `corsConfigurationSource()` with field `allowedOrigins` set via `ReflectionTestUtils`: valid trimmed list accepted; `*`, path, query, fragment, user info, port 0/70000, ftp, no scheme, empty, blank list -> `IllegalStateException` |
| `integration/fnb/FnbPayFirstFlowIntegrationTest.java` | `NEW` -> `OPEN`, `CONFIRMED` -> `COMPLETED`; report test now also asserts `orderStatus=OPEN|CANCELLED` -> 400 and `COMPLETED` -> 200; new `heldPosOrderIsRepricedThenPaidWithStockDeductedOnce` (create OPEN -> `GET ?status=OPEN` lists it -> PUT items with different product/qty/serviceType -> totals changed and persisted, no stock movement while held -> pay CASH -> COMPLETED/PAID, one SALE movement, balance 100->70 -> PUT again 409); new `replaceItemsRejectsOtherTenantAnonymousAndDiscountedOrders` (other tenant's cashier 404 both on own outlet with foreign order id and on foreign outlet path, anonymous 401, discounted order 409, lines unchanged) |
| `integration/grpc/GrpcSecurityIntegrationTest.java` | seed/expectations `CONFIRMED`->`COMPLETED`, `NEW`->`OPEN`; constant `ORDER_A1_NEW` renamed `ORDER_A1_OPEN` |

No PREPARING/READY tests existed. No existing test of mock-gateway initiation or of the report filter existed beyond the added report assertions (no unit test class for `MockFnbGatewayService` exists; not added, out of W2 brief beyond status expectations).

## Notes / differences vs the brief
- Followed W1's final `replaceItems`: paid/completed/cancelled orders are rejected with `OperationConflictException` (HTTP 409) BEFORE pricing, via an explicit status check in the service (not via the entity's `InvalidOperationException`/400). Tests assert 409 and that pricing is not called.
- CORS field name is `allowedOrigins` (W1 final code, property `app.cors.allowed-origins`).
- Integration "cashier of another tenant" uses `api.onboardCashier(tenantB, ...)` (existing helper); no new helpers added to `ApiTestSupport`. Ingredient/recipe setup is inlined (those helpers are private in `FnbInventoryFlowIntegrationTest`).
- The held-order stock assertion relies on the owner admin routes `/merchant/.../inventory/items` and `/products/{id}/recipe` (same routes as `FnbInventoryFlowIntegrationTest`).

## Grep evidence
`grep -rnE 'OrderStatus\.(NEW|CONFIRMED|PREPARING|READY)|"(NEW|CONFIRMED|PREPARING|READY)"|\b(NEW|CONFIRMED|PREPARING|READY)\b' src/test` -> 0 matches (run after the last edit).

## Test status
Not run by policy. Recommended: `mvnw -B verify` by the orchestrator (new unit classes tagged `unit`; integration classes need Docker/PostgreSQL).
