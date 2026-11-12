# TASK-075 W1 report (main Java + V6 + Amendment A1)

Status: implementation complete, tests not run by policy (no Maven, no pytest). Not compiled by me.

## Changed files (all under src/main or root config named by contract/A1)
- enums/OrderStatus.java: OPEN, COMPLETED, CANCELLED
- entity/fnb/SalesOrderEntity.java: default OPEN; confirmPaid -> COMPLETED; requireNewUnpaid -> requireOpenUnpaid (status==OPEN && UNPAID, message "...pesanan terbuka yang belum dibayar."); new `replaceLines`
- entity/shared/audit/AuditLogEntity.java: constant FNB_ORDER_ITEMS_REPLACED("SALES_ORDER")
- service/MockFnbGatewayService.java: NEW -> OPEN, message "...untuk pesanan terbuka yang belum dibayar."
- service/FnbSalesReportService.java: filter rejects OPEN or CANCELLED
- service/FnbPosOrderService.java: replaceItems, shared private lineInputs(request), existing requireServiceType reused; 3 fields appended after `pricing` (attempts, discounts, audit)
- repository/FnbOrderRepository.java: deleteLines
- controller/FnbStaffOrderController.java: PUT /{orderId}/items
- src/main/resources/db/migration/V6__order_status_open_completed_cancelled.sql (new)
- A1: configuration/SecurityConfiguration.java (property app.cors.allowed-origins, allowedOrigins/validateOrigin moved as private static from cf949ff FnbWebSocketConfiguration; messages mention app.cors.allowed-origins), application.properties (`app.cors.allowed-origins=${CASHIER_CORS_ALLOWED_ORIGINS:http://localhost:5173,http://127.0.0.1:5173}`, @Value has no inline default now), .env.example, docker-compose.yml (CASHIER_CORS_ALLOWED_ORIGINS)
- Only other src/main reference to a removed status: none beyond the files above (FnbSalesReportRepository/FnbReceiptService only use CANCELLED).

## V1..V5 constraint grep
- sales_orders_status_check (V1 line 870) is the only order-status constraint; V2..V5 add none.
- fnb_order_status_history: no CHECK (columns from_status/to_status varchar(24)); history rows untouched.
- audit_logs.action: varchar(80), NO CHECK -> new action is safe.
- sales_orders.payment_status CHECK has UNPAID/PAID/REFUNDED (REFUNDED + old status aborts V6 by design).

## V6 SQL
Drop sales_orders_status_check; UPDATE old statuses + PAID -> COMPLETED; old + UNPAID -> OPEN; DO $$ guard counting status NOT IN ('OPEN','COMPLETED','CANCELLED') -> RAISE EXCEPTION; ADD CONSTRAINT sales_orders_status_check CHECK (status IN ('OPEN','COMPLETED','CANCELLED')). See the file for exact text.

## replaceItems order (as implemented)
requireOutlet; lockTenant(tenantId); requireOutlet again + active/fnb/outlet check (404); byId + refreshOrder (404); source != POS -> 409 "Hanya pesanan POS yang dapat diubah."; NOT OPEN/UNPAID -> 409 (see deviation); pending attempt -> 409; existing discount -> 409; requireServiceType; pricing.price with order snapshot rates; deleteLines (options then items); order.replaceLines; persistLines; flush; audit "subtotal=old->new"; response(order).

## Deviation / note for orchestrator
Contract wants "paid/completed -> conflict" (W2: PUT again 409). The entity's requireOpenUnpaid throws InvalidOperationException which maps to 400, so I added one service-level pre-check (not in the contract's step list) throwing OperationConflictException("Pesanan hanya dapat diubah selama masih terbuka dan belum dibayar.") right after the POS source check. The entity check stays as a second guard (a cancelled/completed order therefore gives 409, not 400).

## Checkout replay after a replace
`replay` returns the CURRENT order (response(existing) reads the current lines/totals) when source is POS, same outlet and the request fingerprint equals the stored `requestFingerprint`. replaceItems does not touch requestFingerprint/idempotencyKey, so retrying checkout with the original key and the original payload returns the replaced (current) order with its new items; a different payload gives 409 "Idempotency-Key telah digunakan untuk pesanan berbeda." (note: checkout returns 201 status code from the controller regardless of replay, unchanged).

## Evidence
grep src/main for `OrderStatus.(NEW|CONFIRMED|PREPARING|READY)|requireNewUnpaid|app.fnb.realtime`: 0 matches (exit 1). Word-grep NEW|CONFIRMED|PREPARING|READY in src/main java/properties/yml/proto: 0 matches.
Tests: not run by policy.

## Blocked
None.
