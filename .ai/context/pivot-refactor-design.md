# Design: cashier-first pivot refactor (remove QR ordering, kitchen, tables, real-time; short order status)

STATUS: DESIGN for a later session. Companion of `pivot-refactor-rules.md` (rules and removal inventory). Written by the orchestrator on 2026-11-12 from explicit user decisions. It is not a PRD change: the user will discuss PRD wording separately, and the orchestrator adds the `DECISIONS.md` entry after that discussion. No code was changed while writing this design. File contents behind the inventory were not read (grep and file names only), so every "verify" below is a worker obligation.

## 1. Context and decisions

User decisions, 2026-11-12:
- Product becomes a plain cashier (POS) app. The main differentiator becomes an AI assistant for the owner (insert, edit, audit and analyze data, import data, decision support) and a web chat page later. The AI work is NOT part of this refactor.
- Cut: customer QR ordering, kitchen queue/KDS flow, dining tables, WebSocket real-time.
- Keep: hold bill, receipts, discounts, owner audit viewer, business settings, catalog, ingredient inventory and recipes, reusable option groups, sales report, onboarding and invitations, Telegram bot and the AI service.
- Order status becomes `OPEN`, `COMPLETED`, `CANCELLED`.
- Order of work: the user merges the stacked feature branches into main first, then a new session runs this refactor.

## 2. Target product after the refactor

Flow: menu -> POS order (optionally held as OPEN) -> payment -> COMPLETED -> sales report. Stock is deducted when an order becomes paid, on both settle paths.

### 2.1 Order state machine
```
OPEN  --pay (cash / manual non-cash / mock gateway success)--> COMPLETED   (paymentStatus PAID, stock deducted, receipt available)
OPEN  --cancel (authorized, reason, audit)-------------------> CANCELLED
COMPLETED: terminal. Paid orders cannot be cancelled or deleted.
CANCELLED: terminal.
```
- `paymentStatus` (`UNPAID`, `PAID`) stays as a separate field. Expected invariant: OPEN <-> UNPAID, COMPLETED <-> PAID. A pending or failed mock gateway attempt leaves the order OPEN/UNPAID (verify against `MockFnbGatewayService` and `FnbGatewayOutcome`).
- Removed transitions: `NEW`, `CONFIRMED`, `PREPARING`, `READY` and the kitchen prepare/ready/complete actions. Completion now happens at payment, in the single place(s) that settle a paid order.
- Named domain methods on `SalesOrderEntity` stay the only way to change status (no public setters). Remove kitchen transition methods; keep a single complete-on-payment method and the cancel method. Verify the exact existing method names and the two settle paths (`FnbPaymentService`, `FnbPosOrderService` checkout) and reuse one completion helper rather than duplicating logic.
- Status history (`FnbOrderStatusHistoryEntity`) keeps recording transitions; old rows keep their old status text and are not rewritten.

### 2.2 API surface
Kept (unchanged unless noted): auth (login, register, select-merchant, outlets, csrf, refresh, logout), onboarding, cashier invitations, outlets, membership plan catalog, business settings, owner audit logs, catalog (categories, products, option groups, options), inventory and recipes, discounts, receipts, sales report, cashier orders (create, list, detail), payments, cancellation.
Changed: POS order create request drops `tableId`; staff order response drops table fields and returns the new statuses; list filters by status accept only the new values.
Removed: `GET /api/v1/public/fnb/menu/{qrToken}` and public order create/read, merchant table CRUD and `rotate-qr`, cashier kitchen queue and prepare/ready/complete, `POST .../fnb/realtime-ticket`, WebSocket `GET /api/v1/fnb/realtime`.
Needs a decision before Wave 2 (see section 9 of the rules file and section 8 here): is there a replacement way for a second cashier device to see changes quickly.

### 2.3 Client freshness without real-time
Server push is removed. The frontend refreshes order lists by refetch (focus, interval, after own mutations) with TanStack Query. The PRD target "visible within 5 seconds" (section 12) is NOT guaranteed by polling at a long interval; the user will settle this in the PRD discussion. Pick the interval there, do not invent it in code.

## 3. Data and migration design

- Never edit V1..V5. The next migration is V6 (verify the folder first; main at the time of writing only had V1..V3 plus whatever the user has merged since).
- Only constraint found on order status: `sales_orders_status_check` in V1 (grep result; worker must re-grep for any other status check or enum-typed column, including status-history and laundry tables, and leave laundry tables alone).
- V6 content (single Flyway script, written by a worker, reviewed by the orchestrator):
  1. Drop `sales_orders_status_check`.
  2. Update rows: status in (NEW, CONFIRMED, PREPARING, READY) with payment PAID -> COMPLETED; same four with payment UNPAID -> OPEN. COMPLETED and CANCELLED untouched. Verify the real payment column and whether any PAID order can be CANCELLED (should not exist).
  3. Add a new check allowing exactly OPEN, COMPLETED, CANCELLED.
  The order matters: relax first, migrate rows, then tighten. Run inside Flyway's transaction.
- Do not apply V6 to any live or shared database without the user's explicit approval. Validate it on a copy of data that contains old statuses.
- Table/QR tables and columns (`dining_tables`, QR tokens, order table reference) stay in the schema as unused, nullable data. A later cleanup migration is a separate user-approved task.
- Never delete paid orders, payments or audit rows.

## 4. Code design

- Order status: `OrderStatus` enum becomes {OPEN, COMPLETED, CANCELLED}. All switch statements, queries, specifications and DTO mappers that name removed values are updated in the same wave (Wave 4). The JPA string mapping must not meet an old value at runtime: the V6 migration must be applied before the enum change ships.
- Sales report (`FnbSalesReportService`/`FnbSalesReportRepository`): keep "paid and not cancelled" semantics; replace status predicates that name removed statuses. Verify the report numbers before and after on the same sample data (regression scenario in the rules file section 8).
- Coupling services (`FnbPaymentService`, `FnbPosOrderService`, `FnbDiscountService`, `FnbOrderCancellationService`, `MockFnbGatewayService`): remove only publisher calls and QR/kitchen/table branches. Keep transaction boundaries, `lockTenant` ordering, idempotency, audit calls and stock deduction untouched.
- Realtime removal: delete the event, after-commit listener, publisher, ticket/access services, handler, interceptor, config, mapper and DTOs together with their tests. Remove the `app.fnb.realtime.*` properties and the websocket starter from `pom.xml` only after grep proves no remaining use.
- Security: remove `permitAll` for `/api/v1/public/**` and `GET /api/v1/fnb/realtime` when no such route remains. Remove rate-limit groups PUBLIC_READ, PUBLIC_ORDER_WRITE, QR_TOKEN, REALTIME_TICKET and keep AUTH, AUTHENTICATED, ANONYMOUS_OTHER exactly as they are. Update `RateLimitConstants`, `HttpRateLimiter`, `RateLimitFilter` and their three tests.
- Tables: remove `FnbTableController/Service/ReconciliationService/Repository`, `DiningTableEntity`, `DiningTableStatus`, DTOs; remove `tableId` and table fields from POS order request/response and mappers; keep the DB column untouched.
- Tests: delete only tests of deleted classes; rewrite tests of retained classes that mention removed statuses or tables. Never weaken a retained test to pass.
- The Telegram bot (`OrderStatusTool`, gRPC `GetOrderStatus`) keeps working: update the Python tool, the gRPC mapper/contract (`docs/contracts/cashier-service.grpc.*`) and its tests together in the status wave. Decide the customer-facing text for OPEN/COMPLETED/CANCELLED first (rules file section 9 item 4).

## 5. Frontend design (`E:\Java\cashier-saas-ui`)

- Remove real-time (`services/fnbRealtime.ts`, `hooks/useFnbRealtime.ts`), the kitchen queue UI (`CoffeeKdsQueue.tsx` and its dashboard wiring), table selection in POS and order types, and the public QR pages if any exist.
- Keep: POS, held orders drawer, payment modal, receipt modals, transactions, products, inventory/recipes, option groups, settings, audit viewer.
- Order types/mocks/i18n (`types/fnbOrder.ts`, `types/pos.ts`, `api/mock/fnbOrder.mock.ts`, `language/en.ts`, `language/id.ts`) move to the three statuses.
- Open item: whether "held orders" are stored in the backend as OPEN orders or only locally was NOT checked. If local only, decide whether OPEN becomes the server-side hold (preferred, it also lets a second device see held bills).
- The frontend lives in a separate repository and needs its own branch/PR. Backend and frontend contract changes (dropping `tableId`, new statuses) must ship together or behind a coordinated merge order: backend first would break the old UI, so run Wave 1 (UI stops using removed endpoints/fields) before the backend waves.

## 6. Wave plan with candidate reserved writes

Each wave leaves the system compiling and the retained flow working. The orchestrator creates the task packet when the wave starts (memory version, dirty tree and baseline hashes are recorded then, not now). The latest existing task file at the time of writing is TASK-073, so numbering resumes after the highest number in `tasks/`.

| Wave | Repo | Work | Depends on |
|---|---|---|---|
| 0 | orchestrator | Preconditions: stack merged into main, clean tree, memory version, branch chosen, `INDEX.md` line for these two docs, `DECISIONS.md` entry (after PRD discussion). Worker to report the held-orders storage question and the gRPC consumer list. | merge |
| 1 | UI | Remove UI use of real-time, kitchen queue, tables, QR; keep hold bill; types/mocks/i18n to three statuses is deferred to Wave 4, but stop calling removed endpoints now. | 0 |
| 2 | backend | Delete QR + public order path, kitchen path, table path (controllers, services, repositories, entities, DTOs, tests), rate-limit groups, public `permitAll`. Drop `tableId` from POS DTOs and mappers. | 1 |
| 3 | backend | Delete real-time layer and every publisher call in the coupling services; config properties; pom dependency if unused. | 2 |
| 4 | backend + UI + Python + docs | V6 migration, `OrderStatus` to three values, entity transitions, services, report, gRPC contract and Python tool, UI types/mocks/i18n, Postman, docs. | 3 |
| 5 | orchestrator | Update `.ai` state, `ARCHITECTURE.md` (WebSocket section), `TASKS.md`, Postman collections, `docs/contracts`; hand back as READY FOR TESTING. | 4 |

Waves 2 and 3 touch overlapping coupling files, so they run sequentially, never in parallel. Inside a wave, split workers only by disjoint file sets.

## 7. Acceptance for the whole refactor

1. Retained flow works end to end: create POS order, hold (OPEN), pay (cash and non-cash and mock gateway), COMPLETED with receipt, cancel unpaid with reason and audit, discount with audit, sales report, owner audit viewer, settings.
2. `rg` finds no reference to deleted classes, endpoints, config keys, rate-limit groups or removed statuses in Java, tests, UI, Python, Postman and docs (except historical reports).
3. Section 2 invariants of the rules file all hold; stock still deducts once on paid, on both settle paths.
4. V6 migrates a copy of data containing old statuses without errors and with the mapping above.
5. The Telegram bot's order-status answer still works against the new statuses.
6. Compile passes (`mvnw.cmd` compile by the orchestrator; UI type/build check). Tests are NOT run by policy until the user asks; the rules file section 8 lists the scenarios to hand over.

## 8. Risks and open points

- Real-time removal weakens multi-device freshness; the PRD 5-second target conflicts with polling (user decision pending).
- Status mapping loses the "being prepared" history meaning for old paid orders (acceptable: kitchen is cut).
- Dropping `tableId` is a breaking API change for any client; coordinate with UI and Postman in one merge order.
- Held-order storage (backend vs local) is unknown.
- `FnbStockService` and `FnbInventoryService` matched the removal grep; likely a false match, to be verified.
- Two files were already modified in the user's working tree before this planning (real-time component files for null annotations). The refactor must not start on a dirty tree; decide whether those edits are committed or dropped in Wave 0.

## 9. Constraints from the later AI assistant (not in scope here)

Service-layer methods stay the single write path; AI writes go through the same authorization, tenant isolation, idempotency and audit as a human action with the human as actor; the LLM never chooses the tenant; web chat, import and confirmation-before-write need their own design and security review. See the rules file section 10.

## 10. How the next session should start

1. Read `AGENTS.md`, `.ai/MEMORY.md`, `.ai/TASKS.md`, `.ai/context/INDEX.md`, `PRD-MVP.md`, `.ai/DECISIONS.md`, then these two files.
2. Confirm the precondition (stack merged, tree clean) and run Wave 0.
3. Ask the user only for items still open in the rules file section 9.
4. Delegate every wave to workers with reserved writes; the orchestrator reviews diffs and persists state.
