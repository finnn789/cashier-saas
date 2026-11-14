# TASK-080: M7 — owner AI assistant: sales Q&A, restock, anomalies, daily summary (PRD v2.1 §8.14, AC-01, AC-09)

Status: DELEGATED (2026-11-14). Memory version: 78. Branch `fix/flow-controls-a`; baseline = commit `08ff7af` (clean tree).

Global rules: identical to `tasks/TASK-076.md` "Global rules" 1-7 (reports `reports/TASK-080-<id>.md`). Workers never run Maven, pytest or the app; `uv run python -m grpc_tools.protoc ...` for stub generation is allowed (J2 only). Never edit V1..V9. Java paths are under `src/main/java/org/lite/project/cashiersaas/`, Python paths under `Ai-services/`.

## User decisions (2026-11-14, `.ai/DECISIONS.md` "M7 owner AI")
- D1 Daily summary is sent when the last open shift of the outlet closes; fallback at 23:00 outlet time; once per outlet per business day.
- D2 Summary text = deterministic Python template (exact numbers) + ONE insight sentence from the LLM; if the LLM fails or times out, send without the sentence.
- D3 Cashier anomaly = the day's value > 2 × that cashier's daily average over the previous 30 days AND at least the minimum: cancellations >= 3, discounted orders >= 3, |cash difference| >= 20000.

## Shared rules
- AI is read-only (AC-09): every new RPC is `@Transactional(readOnly = true)`, no writes, no new mutating RPC.
- New RPCs require role OWNERMERCHANT; everyone else gets the same PERMISSION_DENIED as a rejected tenant (no existence evidence). Data is limited to the outlets of the caller's active membership.
- Money crosses gRPC as decimal strings (`toPlainString()`), dates as `YYYY-MM-DD` in the outlet zone, instants as ISO-8601 UTC.
- Business date of a shift = outlet-local date of its `openedAt`.

## Proto contract (J2 writes it to BOTH `src/main/proto/cashier_service.proto` and `Ai-services/proto/cashier_service.proto`, keeping existing messages unchanged)
```proto
rpc GetSalesSummary (GetSalesSummaryRequest) returns (GetSalesSummaryResponse);
rpc GetRestockSuggestions (GetRestockSuggestionsRequest) returns (GetRestockSuggestionsResponse);
rpc GetAnomalies (GetAnomaliesRequest) returns (GetAnomaliesResponse);

message GetSalesSummaryRequest { string tenant_id = 1; string user_id = 2; string start_date = 3; string end_date = 4; }
message LabelTotal { string method = 1; string label = 2; int64 count = 3; string net_sales = 4; }
message ProductTotal { string name = 1; int64 quantity = 2; string gross_sales = 3; string cost_of_goods_sold = 4; string gross_profit = 5; int64 items_without_cost = 6; }
message OutletSalesSummary {
  string outlet_id = 1; string outlet_name = 2; string time_zone = 3; string start_date = 4; string end_date = 5;
  int64 transaction_count = 6; string gross_sales = 7; string discount_amount = 8; string net_sales = 9; string total = 10;
  string tax_amount = 11; string service_charge_amount = 12; string cost_of_goods_sold = 13; string gross_profit = 14;
  int64 items_without_cost = 15; int64 refund_count = 16; string refund_amount = 17; int64 cancellation_count = 18;
  int64 discounted_order_count = 19; string expenses_total = 20; string cash_difference_total = 21; int64 closed_shift_count = 22;
  repeated LabelTotal labels = 23; repeated ProductTotal top_products = 24;
}
message GetSalesSummaryResponse { string tenant_id = 1; repeated OutletSalesSummary outlets = 2; }

message GetRestockSuggestionsRequest { string tenant_id = 1; string user_id = 2; }
message RestockSuggestion { string inventory_item_id = 1; string name = 2; string unit = 3; string quantity_on_hand = 4; string reorder_level = 5; string average_daily_usage = 6; string suggested_quantity = 7; string stock_status = 8; }
message OutletRestock { string outlet_id = 1; string outlet_name = 2; repeated RestockSuggestion items = 3; }
message GetRestockSuggestionsResponse { string tenant_id = 1; repeated OutletRestock outlets = 2; }

message GetAnomaliesRequest { string tenant_id = 1; string user_id = 2; string date = 3; }
message CashierAnomaly { string staff_user_id = 1; string full_name = 2; string kind = 3; string value = 4; string baseline_daily_average = 5; }
message OutletAnomalies { string outlet_id = 1; string outlet_name = 2; string date = 3; repeated CashierAnomaly anomalies = 4; }
message GetAnomaliesResponse { string tenant_id = 1; repeated OutletAnomalies outlets = 2; }
```
`kind` is one of `CANCELLATIONS`, `DISCOUNTS`, `CASH_DIFFERENCE`. Validation: dates parse as ISO, `end >= start`, range < 366 days, else INVALID_ARGUMENT. Top products limit 5.

## Wave 1 (parallel)

### J1 — Java read models (service layer)
- `FnbSalesReportService`: extract the data assembly of `report(...)` into a public method `FnbSalesReportResponse summarize(MerchantAccessContext context, LocalDate startDate, LocalDate endDate, int topProducts)` (no status/method filter, page 0 size 1 for transactions). `report(...)` keeps its guard and delegates where possible; no duplicated query code. Range validation reused from the existing `query(...)`.
- New `service/FnbOwnerInsightService` (read-only, no guard: callers pass an already authorised `MerchantAccessContext`):
  - `FnbOwnerSalesInsight salesInsight(MerchantAccessContext context, LocalDate start, LocalDate end)` = `summarize(..., 5)` + `discountedOrderCount` (paid non-cancelled orders with discount > 0 in range) + `expensesTotal` (cash expenses recorded in range) + `cashDifferenceTotal` and `closedShiftCount` (CLOSED shifts whose business date is in range).
  - `List<FnbRestockSuggestion> restock(MerchantAccessContext context)`: active ingredients; usage = −Σ `quantity_delta` of SALE, SALE_REVERSAL and WASTE movements in the last 14 × 24 h (clamped at 0); `averageDailyUsage = usage / 14` (scale 3 HALF_UP); `suggested = max(0, averageDailyUsage × 7 + reorderLevel − onHand)` (scale 3 HALF_UP). Return items with `suggested > 0` or status NEGATIVE/OUT/LOW, ordered NEGATIVE, OUT, LOW, OK then name. One grouped query for usage (no per-item loop query).
  - `List<FnbCashierAnomaly> anomalies(MerchantAccessContext context, LocalDate date)`: per CASHIER member of the outlet (owners are excluded), for the outlet-local day and the 30 previous days: cancellations = audit rows `FNB_ORDER_CANCELLED` by that membership; discounts = paid orders with discount > 0 received by that staff user; cash difference = Σ |cash_difference| of that member's CLOSED shifts by business date. Rule D3; baseline = Σ previous 30 days / 30 (scale 2). Grouped queries, no N+1.
  - The three result classes are plain `public class` with Lombok `@Getter @Setter` in `dto/response` (`FnbOwnerSalesInsight` holds the `FnbSalesReportResponse` plus the extra fields).
- Repository additions only where the data lives (`FnbShiftRepository`, `FnbCashExpenseRepository`, `FnbInventoryRepository`, the audit log repository, `FnbOrderRepository`), all tenant + outlet scoped.
Reserved: `service/FnbSalesReportService.java`, new `service/FnbOwnerInsightService.java`, the three new result classes, and the five repositories named above (additions only).

### J2 — Java gRPC + proto + Python stubs
- Proto as above (both files). Regenerate Python stubs in `Ai-services/app/integrations/grpc/generated/` with the same command used before (see `Ai-services/README.md` or the header of `cashier_service_pb2.py`); Java stubs come from Maven.
- `GrpcTenantAccessPolicy.requireOwnerOutlets(UUID tenantId, String rawUserId) -> List<UUID>`: same validation as `requireMemberOutlets` plus the active membership role must be OWNERMERCHANT, else the identical PERMISSION_DENIED. Reuse, do not copy, the existing parsing (extract a private helper if needed).
- New `grpc/service/CashierInsightGrpcHandler` with the three RPCs; for each outlet id builds a `MerchantAccessContext` (tenant, outlet, the caller's membership) via existing repositories/guard helpers and calls J1's `FnbOwnerInsightService` methods exactly as named above (code against them even though they are written concurrently). `CashierGrpcService` delegates the three RPCs. Error handling identical to `CashierOrderGrpcHandler` (StatusRuntimeException passthrough, INTERNAL with a fixed message, short tenant ref in logs).
- New `grpc/mapper/GrpcInsightMapper`.
- Rate limiting: same limiter path as the existing RPCs.
Reserved: both proto files, `Ai-services/app/integrations/grpc/generated/*`, `grpc/policy/GrpcTenantAccessPolicy.java`, `grpc/service/CashierGrpcService.java`, new `grpc/service/CashierInsightGrpcHandler.java`, new `grpc/mapper/GrpcInsightMapper.java`.

### J3 — Java daily summary dispatch
- `src/main/resources/db/migration/V10__daily_summary_dispatches.sql`: `daily_summary_dispatches` (`id uuid PK`, `created_at`, `updated_at`, `version` like other mutable tables, `tenant_id` FK, `outlet_id` FK, `business_date date NOT NULL`, `trigger varchar(16) NOT NULL CHECK IN ('SHIFT_CLOSED','FALLBACK')`, `status varchar(16) NOT NULL CHECK IN ('PENDING','SENT','FAILED')`, `attempts integer NOT NULL DEFAULT 0`, `last_attempt_at timestamptz`), `UNIQUE (outlet_id, business_date)`, index on `status`.
- Entity `entity/fnb/DailySummaryDispatchEntity` (MutableBaseEntity) with named transitions `markSent()`, `markFailed()` (increments attempts, sets last_attempt_at). Repository `repository/DailySummaryDispatchRepository`: `insertIfAbsent(tenantId, outletId, date, trigger) -> Optional<UUID>` via native `INSERT ... ON CONFLICT (outlet_id, business_date) DO NOTHING RETURNING id`, `byId`, `retryable(int maxAttempts, Instant olderThan, int limit)`.
- `service/DailySummaryService`:
  - `onShiftClosed(MerchantAccessContext context, CashShiftEntity shift)`: if the outlet has no other OPEN shift, `insertIfAbsent(..., businessDate(shift), SHIFT_CLOSED)` and, if a row was inserted, publish a Spring event with the dispatch id. Called with ONE line from `FnbShiftService.closeCurrent` and `forceClose` after the close (the only edit to that file).
  - `runFallback()`: for every active outlet of an active F&B tenant whose outlet-local time is >= 23:00, `insertIfAbsent(today, FALLBACK)` and dispatch inserted rows; then retry rows `PENDING`/`FAILED` with attempts < 3 and `last_attempt_at` null or older than 10 minutes (limit 50).
  - `dispatch(UUID dispatchId)`: POST to the AI service; 2xx -> `markSent`, else `markFailed`. Never throws.
- Listener `component/DailySummaryListener`: `@TransactionalEventListener(AFTER_COMMIT)` on the event, runs `dispatch` on an executor (same pattern as `MemberSyncListener`). Scheduler `component/DailySummaryScheduler`: `@Scheduled(fixedDelayString = "${app.daily-summary.fallback-interval:PT10M}")` calls `runFallback()`; `@EnableScheduling` on a configuration class; `app.daily-summary.enabled` (default true; `false` in `application-test.properties`) guards both scheduler and dispatch.
- Client: `service/DailySummaryClient` reusing `AiServiceProperties` (base URL blank = disabled, same `X-Internal-Service-Key` header as `MemberSyncClient`, no copy of retry logic: single attempt, retries come from the scheduler) with read timeout `app.ai-service.daily-summary-read-timeout=30s`. Request `POST {base}/api/v1/internal/daily-summaries` body `{"tenant_id","outlet_id","business_date":"YYYY-MM-DD"}`. Never log the key.
- Properties in `application.properties` (`${CASHIER_DAILY_SUMMARY_*:default}` style), mirrored in `.env.example`.
Reserved: V10, the new entity/repository/service/client/listener/scheduler/event classes, one configuration class for `@EnableScheduling` (new or existing scheduling-free config), `service/FnbShiftService.java` (two call lines + field only), `application.properties`, `src/test/resources/application-test.properties` (one line), `.env.example`.

### P1 — Python gRPC client + AI tools
- `app/integrations/grpc/client.py`: `get_sales_summary(tenant_id, user_id, start_date, end_date)`, `get_restock_suggestions(tenant_id, user_id)`, `get_anomalies(tenant_id, user_id, date)` returning plain dicts (money as strings) or `None` on error/PERMISSION_DENIED, same span/metadata/error logging as the existing methods.
- Tools in `app/tools/` (new `insights.py`): `sales_summary` (params `start_date`, `end_date`, ISO dates; the description tells the model to call it twice to compare periods and that dates are in the outlet time zone), `restock_suggestions`, `cashier_anomalies` (param `date`). `user_id` injected like `OrderStatusTool`; on `None` return an Indonesian "data tidak tersedia; fitur ini hanya untuk owner" message. Register in `registry.py` / `get_default_tool_registry`. Add today's date (Asia/Jakarta) and the read-only/owner-only rule to the system prompt where it is built (`app/ai/prompts*`), keeping `MAX_PROMPT_BYTES`.
Reserved: `app/integrations/grpc/client.py`, new `app/tools/insights.py`, `app/tools/__init__.py`, `app/tools/registry.py`, the prompt file(s) under `app/ai/`.

### P2 — Python daily summary endpoint
- New `app/api/internal_daily_summaries.py` router `POST /api/v1/internal/daily-summaries` with the same key check as `internal_members.py` (reuse `_authorize`/settings; move shared bits only if needed and report it). Body validated (UUIDs, ISO date) -> 422 on error.
- Recipients: members of the tenant with role OWNERMERCHANT, a `java_user_id`, an ACTIVE status and a verified Telegram channel account (private chat id); add one repository query in `app/chat/repositories.py`.
- For each recipient: call P1's `get_sales_summary(tenant, user, date, date)`, `get_restock_suggestions`, `get_anomalies(date)` (signatures above), take the section whose `outlet_id` equals the request; skip a recipient without that outlet. Format with a deterministic Indonesian template in new `app/chat/daily_summary.py` (omzet bersih, transaksi, tunai vs non-tunai per label, pengeluaran kas kecil, HPP & laba kotor (+ "N item tanpa HPP"), 3 menu terlaris, selisih kas, pembatalan/diskon/refund, stok menipis/minus with restock suggestions, anomalies). Money formatted `Rp12.500`. Then ask the LLM (existing OpenRouter client/settings) for ONE short insight sentence from the computed numbers with a 8 s timeout; on any failure send without it. Send through `app.state.telegram_channel`'s sender (existing `Sender`, split chunks).
- Response 200 `{"delivered": n, "skipped": m}`; 503 if the Telegram channel is not ready. Never log phone numbers, chat ids in full, or the key. Include the router in `app/application.py`.
Reserved: new `app/api/internal_daily_summaries.py`, new `app/chat/daily_summary.py`, `app/chat/repositories.py` (one query), `app/application.py` (one include), `app/api/internal_members.py` only if a shared helper must move (report it).

## Wave 2 (parallel, after wave 1 review + compile)
- **T1 Java unit** (`src/test/java/**/unit/**`): insight math (restock formula, anomaly rule incl. minimums and owner exclusion), policy owner check, mapper, dispatch state machine and fallback time logic, client disabled/2xx/5xx.
- **T2 Java IT** (`src/test/java/**/integration/**`): gRPC owner-only (cashier PERMISSION_DENIED identical to unknown tenant), tenant/outlet isolation, sales summary numbers vs REST report, restock and anomalies on seeded data, V10, closing the last shift creates one dispatch row and a second close the same day does not, fallback with a fake clock or direct `runFallback` call, AI base URL blank => no HTTP.
- **T3 Python unit** (`Ai-services/tests/unit/**`): client methods, tools (owner-only message), endpoint auth/validation/503, recipient filter, template formatting, LLM failure fallback.
- **T4 docs/contracts**: `docs/contracts/cashier-service.grpc.schema.json` + `.test-messages.json` for the new RPCs, new `docs/contracts/daily-summary.md`, `Ai-services/README.md`, `docs/architecture.md`, README M7 line.

## Orchestrator gate
Review each wave's diff against reserved paths; `mvnw -B -DskipTests compile` + Python `compileall` after wave 1; `test-compile` after wave 2; `mvnw -B verify` and pytest only on the user's request.

## Known accepted limits
- A shift reopened after the summary was sent is not included (once per outlet per day, D1).
- Dispatch retries stop after 3 attempts; delivery is best-effort per recipient (no per-recipient record).
- Single application instance assumed for the scheduler (insert-if-absent prevents duplicate rows, not duplicate concurrent retries).

## Wave 3 — verify fixes (2026-11-14, user-requested `mvnw -B verify` run 1)
Run 1: 609 unit OK, 148 IT with 2 failures; Python `unittest discover tests/unit` 323 OK (pytest is not a project dependency; README command used).
- **F1** (`DailySummaryService.runFallback`): a FALLBACK row inserted and dispatched in a sweep is picked again by `retryable(...)` in the same sweep when `lastAttemptAt` (wall clock) is older than the sweep's `now - 10 min` (test clock 2031) -> two calls. Fix: compute the retryable id list BEFORE the fallback insert loop so one sweep never sends a row twice. No other behaviour change.
  Reserved: `src/main/java/org/lite/project/cashiersaas/service/DailySummaryService.java`.
- **F2** (`OwnerInsightIntegrationTest.salesSummaryEqualsTheRestOwnerReportAndAddsTheOwnerExtras:299`): REST `methodLabel` is null for cash, gRPC sends `""` (proto string default via `GrpcInsightMapper.text`). Test-only fix: compare against `""` when the REST value is null.
  Reserved: `src/test/java/org/lite/project/cashiersaas/integration/grpc/OwnerInsightIntegrationTest.java`.
Report: `reports/TASK-080-W3.md`. No test runs by the worker; orchestrator reruns verify.
- **F3** (Python, user asked to run tests via `Ai-services/.venv`): unit 323 OK; `tests/integration` 24 run, 14 skipped, 2 failures in `test_grpc_integration.py` (`test_real_get_catalog_items_roundtrip`, `test_real_get_order_status_roundtrip`): the test builds `GrpcSettings` without `service_key`, so the client skips the call ("CASHIER_GRPC_SERVICE_KEY is not configured"); the test predates the service-key requirement. Test-only fix: pass a dummy `service_key` in the test's `GrpcSettings`.
  Reserved: `Ai-services/tests/integration/test_grpc_integration.py`. Report appended to `reports/TASK-080-W3.md`.
