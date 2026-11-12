# Pivot refactor rules: cut QR ordering, kitchen flow, real-time WebSocket; shorten order status

STATUS: DRAFT rules. Direction chosen by user on 2026-11-12 (cashier-first product, AI assistant/decision support for the owner). PRD wording is still being discussed by the user, so this file is NOT a decision entry and NOT a task. No refactor task may start until the user approves the scope list in section 3.

User answers recorded 2026-11-12 (explicit user decisions, still to be written into DECISIONS.md by the orchestrator after the PRD discussion):
- Hold bill STAYS (unpaid open order that is paid later).
- Dining tables are REMOVED together with QR ordering.
- Telegram bot is NOT stopped; the AI service stays and grows into the owner/cashier assistant (insert, edit, audit and analyze data; import data). A web chat page in the frontend is planned later.
- Order status becomes short: OPEN, COMPLETED, CANCELLED (OPEN = held/unpaid, COMPLETED = paid).

Owner: orchestrator. Workers implement code only inside reserved writes (see AGENTS.md).

## 1. Goal and non-goals

- Goal: remove the customer QR order path, the kitchen queue and the WebSocket real-time layer, and shorten the order status model, without breaking any retained feature.
- Non-goals: no new AI/analytics feature in this refactor, no Telegram customer-service change, no payment gateway work, no UI redesign. These are separate tasks.
- Cutting is deletion of code, not feature flags (dead code costs maintenance and, for `/api/v1/public/**`, attack surface). Git history is the way back.

## 2. Invariants that must stay true after every wave (regression list)

1. Tenant isolation on every read and write; tenant comes from the JWT principal only; no evidence of other tenants' records.
2. Checkout needs an idempotency key; availability is re-validated at checkout; retry with same key returns the same sale and records no second payment.
3. Totals are server-calculated; product names and prices are order snapshots; money is BigDecimal.
4. Paid orders are never deleted and cannot be cancelled; cancellation needs authorization, a reason and an audit record.
5. Stock is deducted only when an order becomes paid, on BOTH settle paths; minus stock allowed with warnings (see fnb-inventory-design).
6. Audit records for price, discount, cancellation and payment actions keep actor, time and reason.
7. Sales reports use paid, non-cancelled transactions only.
8. Roles stay exactly SUPERADMIN, OWNERMERCHANT, CASHIER. JWT rules, ApiResponse, DTO-as-class and package layout rules from AGENTS.md still apply.
9. Rate limiting and security rules for the REMAINING endpoints are unchanged.

## 3. Removal inventory (from grep on 2026-11-12; file contents were NOT read, so every item must be verified by the worker)

### Backend, delete (QR path)
Controllers `PublicOrderController`, `FnbOrderQrController`; services `PublicOrderService`, `PublicOrderTokenService`, `PublicQrAccessService`, `FnbOrderQrService`; entity `TableQrTokenEntity`; DTOs `PublicOrderRequest`, `PublicOrderResponse`; QR parts of `FnbTableService`, `FnbTableRepository`, `FnbMenuMapper`, `FnbOrderMapper`, `SalesOrderEntity` (public QR details).

### Backend, delete (kitchen path)
`FnbKitchenController`, `FnbKitchenService`, `FnbKitchenRepository`, `FnbKitchenMapper`, `FnbKitchen*Response` DTOs (page, order, item, option).

### Backend, delete (real-time path)
`FnbRealtimeController`, `FnbRealtimeWebSocketHandler`, `FnbRealtimeHandshakeInterceptor`, `FnbOrderChangedEvent`, `FnbOrderChangedListener`, `FnbWebSocketConfiguration`, `FnbRealtimeAccessService`, `FnbRealtimePublisher`, `FnbRealtimeTicketService`, `FnbRealtimeMapper`, `FnbRealtime*Response` DTOs; related config properties (`app.fnb.realtime.*`) and the unused websocket dependency in pom only after a grep proves it is unused.

### Backend, delete (dining tables; grep 2026-11-12, 32 Java files matched table/QR names)
`FnbTableController`, `FnbTableService`, `FnbTableReconciliationService`, `FnbTableRepository`, `DiningTableEntity`, `DiningTableStatus`, `FnbTableRequest`, `FnbTableResponse`.
RETAINED endpoints that currently carry a table: `FnbPosOrderRequest` (tableId), `FnbStaffOrderResponse` and `FnbStaffOrderMapper`, `SalesOrderEntity`, `FnbOrderRepository`, `FnbPaymentService`, `FnbOrderCancellationService`, `MockFnbGatewayService`, `FnbMenuMapper`, `ApiTestSupport`, `FnbPayFirstFlowIntegrationTest`. Removing the table from these is an API contract change for a retained endpoint: update UI and Postman in the same wave, drop the field from request/response, and keep the DB column untouched (nullable, data kept; column drop only in a later user-approved migration).
Python AI service: grep found no dining-table dependency (matches are database "table" words only); not verified in depth.

### Coupling points: retained services that call the real-time publisher or mention these paths (HIGH RISK, edit minimally)
`FnbPaymentService`, `FnbPosOrderService`, `FnbDiscountService`, `FnbOrderCancellationService`, `MockFnbGatewayService`, `FnbSalesReportService`, `SalesOrderEntity`, `FnbOrderRepository`. Also matched by the grep and still to be verified (may be a false match): `FnbStockService`, `FnbInventoryService`.
Rule: in these files remove only the publisher call and QR/kitchen branches; do not change business logic, locking order, transaction boundaries, idempotency or audit calls.

### Security and rate limiting
`SecurityConfiguration`: remove the `permitAll` for `GET /api/v1/fnb/realtime` and `/api/v1/public/**` only when no public route remains. `RateLimitFilter`/`HttpRateLimiter` (+ `RateLimitConstants`): remove groups PUBLIC_READ, PUBLIC_ORDER_WRITE, QR_TOKEN, REALTIME_TICKET; keep AUTH, AUTHENTICATED, ANONYMOUS_OTHER unchanged. Update `RateLimitFilterTest`, `HttpRateLimiterTest`, `RateLimitTestProperties` accordingly.

### Frontend (`E:\Java\cashier-saas-ui`), 28 files matched, notably
`services/fnbRealtime.ts`, `hooks/useFnbRealtime.ts`, `pages/dashboard/components/CoffeeKdsQueue.tsx`, `pages/dashboard/DashboardPage.tsx`, `api/fnbOrder.api.ts`, `api/mock/fnbOrder.mock.ts`, `types/fnbOrder.ts`, `types/pos.ts`, `pages/pos/*`, `language/en.ts`, `language/id.ts`. Many other matches are false positives (the words READY/NEW in unrelated UI); review each.

### Other systems that depend on order status or these routes
- Python AI service: `OrderStatusTool`, `CatalogSearchTool`, gRPC client and proto contract (`grpc/service`, `grpc/mapper`, `GrpcSecurityIntegrationTest`). Status values returned over gRPC change with the status model.
- `postman/` collections and `docs/contracts/`, `docs/architecture.md`, `docs/demo.md`: update or mark the removed routes.
- `.ai/ARCHITECTURE.md` section on WebSocket, TASKS entries for TASK-032 and kitchen tasks: orchestrator updates after review.

### Tests
Delete tests only for deleted classes (list in section 3 backend). Never delete or weaken a test for a retained feature to make a wave pass. Tests that mention removed statuses must be rewritten for the new statuses, not deleted.

## 4. Data and schema rules

- Do NOT edit applied migrations V1..V5. The next migration number is V6 (verify the folder before assigning).
- V1 has `sales_orders_status_check` allowing exactly NEW, CONFIRMED, PREPARING, READY, COMPLETED, CANCELLED. Any new status value (for example OPEN) needs a new migration that replaces the constraint. A Java-only enum change will not pass the database constraint, and removing enum values breaks reading old rows.
- Order of the status change: (a) V6 adds OPEN to the allowed values and maps old rows (any of NEW/CONFIRMED/PREPARING/READY with payment PAID -> COMPLETED; the same four with payment UNPAID -> OPEN; COMPLETED and CANCELLED unchanged) in one migration. Also check `SalesOrderEntity` named transition methods and `OrderStatusHistory` rows that reference old statuses (history rows are kept as they are); (b) Java enum switches to the new set; (c) a later cleanup migration removes obsolete values only after the user confirms no old data remains. Never in one step.
- Do not drop QR/table-token tables or columns in this refactor. Data cleanup is a separate later migration the user approves.
- Do not apply any migration to a live or shared database without explicit user approval. Paid orders and audit rows are never deleted.

## 5. Wave plan (leaf-first, each wave leaves the system compiling and the retained flow intact)

- Wave 0 (orchestrator): precondition: the stacked feature branches (inventory, reusable option groups, stock concurrency tests) are merged into main and the working tree is clean. Then clean baseline. Inspect `git status`, record memory version, save baseline SHA-256 of reserved files, confirm the target branch and that the existing stacked/uncommitted work (TASK-070, unmerged inventory PR) is not mixed into this refactor.
- Wave 1: remove frontend callers of QR, kitchen and real-time (UI, mocks, types, i18n). Backend untouched, so nothing can break while callers disappear.
- Wave 2: remove the QR and kitchen backend (controllers, services, repos, DTOs, tests, rate-limit groups, security permitAll for public routes).
- Wave 3: remove real-time (handler, config, publisher, event/listener) and its publisher calls in the coupling-point services.
- Wave 4: status model change (V6 migration, enum, services, reports, gRPC contract, UI types, Postman, docs). Depends on the user's answer to section 9.
- Wave 5: orchestrator updates `.ai` state, docs, Postman; handoff as READY FOR TESTING with the scenarios in section 8.
Waves 1 to 3 may be split further by reserved write sets; two workers must never share a file. Wave 4 is strictly sequential after Wave 3.

## 6. Worker task rules (add to every task packet)

1. Reserved writes list AND an explicit delete list; touching any other file is a rejection.
2. Before deleting a symbol, grep the whole workspace (Java, tests, UI, Python, Postman, docs) and report the reference list; after the change, report the grep showing zero remaining references.
3. Follow `.ai/context` code-reuse rules: no new helper classes, no duplicate functions, no stubs, no `@Deprecated`, no compatibility overloads, no wildcard generics.
4. Keep transaction boundaries, tenant lock order (`lockTenant`), idempotency and audit calls in coupling-point services byte-for-byte unless the task states the exact change.
5. Do not run Maven or any test command. The orchestrator runs compile only. Report test status as not run by policy.
6. Update the assigned report after each checkpoint: files changed, deleted files, acceptance progress, grep evidence, remaining work. Never write canonical `.ai` files.

## 7. Orchestrator review gates per wave

- Compare actual diff to baseline; reject out-of-scope edits without discarding user work.
- Grep-zero proof for every deleted symbol; confirm no orphan config keys, rate-limit groups or imports.
- Walk the section 2 regression list against the diff of every coupling-point file.
- Compile (`mvnw.cmd` compile by the orchestrator; frontend type/build check) before marking a wave reviewed. Persist reviewed state in `.ai/MEMORY.md` and `.ai/TASKS.md` after each wave, including partial or blocked results.
- Commit only on explicit user approval per step; one commit per wave so a wave can be reverted alone.

## 8. Recommended test scenarios to hand back (run only when the user asks)

POS checkout end to end (cash and non-cash), idempotent retry, stock deduction on both settle paths, cancel unpaid order with reason and audit, paid order cannot be cancelled, discount audit, sales report numbers, tenant isolation on remaining order/inventory endpoints, removed routes return 404/401 as agreed, rate limit on login/register/refresh, V6 migration on a copy of data with old statuses, gRPC order-status contract against the Python service.

## 9. Still open (user decides before tasks are created)

1. RESOLVED 2026-11-12: receipts, discounts and the owner audit viewer are fully retained.
2. Which branch carries the refactor: the user merges the stacked inventory/option-group/concurrency branches into main FIRST, then the refactor starts from the updated main in a new session (user decision 2026-11-12).
3. The held-orders drawer exists in the UI (`HeldOrdersDrawer.tsx`); whether hold is stored in the backend (OPEN order) or only locally was NOT checked. A worker must report this before Wave 4.
4. The existing gRPC `GetOrderStatus` contract is used by the Telegram bot, which stays: decide the new status text returned to customers (OPEN/COMPLETED/CANCELLED) and update the Python tool and proto together.

## 10. Constraints that protect the later AI assistant (not part of this refactor)

The AI service will later insert, edit, audit and analyze data and import data for owners, and help cashiers through a web chat. To keep that possible and safe:
- Keep the existing service-layer methods (checkout, payment, catalog, inventory, audit) as the SINGLE write path. Do not inline or duplicate business logic into controllers or delete service methods that the assistant may call; do not rename them without need.
- Any AI write must go through the same authorization, tenant isolation, idempotency and audit rules as a human action, with the human user as actor. The LLM never chooses the tenant. These are design requirements for the later task, not decisions made here.
- The web chat and import features need their own design (authentication from the frontend to the AI service, role limits for CASHIER vs OWNERMERCHANT, confirmation before writes). Do not mix them into the refactor waves.
