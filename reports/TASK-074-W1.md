# TASK-074 W1 report (main code)

Status: DONE (not compiled; tests not run by policy, Maven not run by instruction).

## Deleted (git rm, 38 files, all under src/main/java/org/lite/project/cashiersaas/)
controller: PublicOrderController, FnbOrderQrController, FnbKitchenController, FnbTableController, FnbRealtimeController.
service: PublicOrderService, PublicQrAccessService, FnbOrderQrService, FnbKitchenService, FnbTableService, FnbTableReconciliationService, FnbRealtimeAccessService, FnbRealtimePublisher, FnbRealtimeTicketService.
component: FnbOrderChangedEvent, FnbOrderChangedListener, FnbRealtimeHandshakeInterceptor, FnbRealtimeWebSocketHandler.
configuration: FnbWebSocketConfiguration. repository: FnbKitchenRepository, FnbTableRepository.
entity/fnb: DiningTableEntity, TableQrTokenEntity. enums: DiningTableStatus.
dto: FnbKitchenMapper, FnbRealtimeMapper, FnbOrderMapper (only `publicOrder`), FnbMenuMapper (only qr/table/menu methods; all removed).
dto/request: PublicOrderRequest, FnbTableRequest. dto/response: PublicOrderResponse, PublicMenuResponse, FnbQrResponse, FnbTableResponse, FnbKitchenPageResponse, FnbKitchenOrderResponse, FnbKitchenItemResponse, FnbKitchenOptionResponse, FnbRealtimeEventResponse, FnbRealtimeTicketResponse.
Pre-delete grep: every referrer of a deleted class was itself in the delete list, except the coupling services below and SecurityConfiguration (W2, referenced `FnbWebSocketConfiguration.allowedOrigins` and `app.fnb.realtime.allowed-origins`; W2 has since removed them, see final grep).

## Renamed
service/PublicOrderTokenService -> service/DigestService (git mv). `@Component`, no constructor, only `digest(String value)` (same SHA-256 hex body). HMAC `token(...)` and `SecretKey` deleted. FnbStockService and FnbInventoryService: `tokens` field type -> DigestService (same position).

## Edited
- FnbPosOrderService: removed fields `tables`, `realtime`; deleted `table.markAwaitingPayment()` and `realtime.orderChanged(ORDER_CREATED)`; `selectedTable` replaced by private `requireServiceType(request)` (null -> "Tipe layanan wajib diisi.", not DINE_IN/TAKEAWAY -> "Tipe layanan tidak valid."), called at the same position; `PosDetails` call lost `table`; fingerprint no longer appends tableId. Note: fingerprints of new requests differ from old ones; replay of an old POS key with the same body would now be a 409 (acceptable per contract).
- FnbPaymentService, MockFnbGatewayService, FnbOrderCancellationService: removed fields `reconciliation`, `realtime` and their calls (3 realtime calls in the mock gateway, incl. PAYMENT_PENDING/PAYMENT_FAILED). FnbDiscountService: removed `realtime` + call. lockTenant, audit, stock.consumeForPaidOrder, transactions untouched.
- SalesOrderEntity: removed `diningTable`, `publicStatusToken*`, record `PublicQrDetails`, `publicQr(...)`, `selectPublicServiceType`, `startPreparing`, `markReady`, `complete`, `requirePaidStatus`, unused `Instant` import. `PosDetails(idempotencyKey, fingerprint, orderNumber, serviceType, customerLabel)`. `pos(...)` guard is now `if (details.serviceType() == null) throw new InvalidOperationException("Tipe layanan tidak valid.");`. OrderStatus and confirmPaid untouched.
- FnbPosOrderRequest: `tableId` removed. FnbStaffOrderResponse: `tableId`, `tableName` removed. FnbStaffOrderMapper: table block removed.
- FnbOrderRepository: removed `refreshQr`, `byStatusDigest`, `activeForTable`, unused `TableQrTokenEntity` import; lockTenant comment reworded (behaviour unchanged).
- No other src/main file needed edits. application.properties had no `app.fnb.realtime`/websocket keys (not touched by W1).

## Grep evidence
After: grep of src/main for all deleted class names, `diningTable|DiningTable|tableId|realtime|Realtime|Kitchen|publicQr|PublicQr|PublicOrder|TableQr|startPreparing|markReady|org.springframework.web.socket|FnbWebSocketConfiguration|PublicOrderTokenService` = 1 hit, owned by W2: `configuration/SecurityConfiguration.java:36` (`@Value("${app.fnb.realtime.allowed-origins...}")`) still present at last check (W2 is mid-edit; the `FnbWebSocketConfiguration` reference is already gone). All W1-owned files: 0 hits.
Remaining references outside src (handled by TASK-075): postman/collections/KedaiMbo API/.resources/definition.yaml, postman/collections/KedaiMbo API/10 - F&B Tables/*, postman/KedaiMbo.postman_collection.json, docs/architecture.md, docs/database/legacy-mapping.md, docs/demo.md, docs/entity-organization.md, docs/fnb-operations.md, docs/fnb-pay-first-backend.md, Ai-services/.idea/workspace.xml, Ai-services/reports/TASK-039.md.

## Risks / notes for orchestrator
- Compile depends on W2 removing the remaining `app.fnb.realtime.allowed-origins` @Value in SecurityConfiguration (harmless for compile, the websocket class reference is gone) and on pom websocket starter decision (nothing in src/main imports org.springframework.web.socket now).
- Querydsl Q-classes regenerate on build; QSalesOrderEntity loses `diningTable`.
- Enum OrderStatus still has PREPARING/READY/COMPLETED (TASK-075). OrderSource.QR kept.
- No new helpers except private `requireServiceType`.
- Files use mixed LF/CRLF; edits preserved each file's line endings (DigestService written LF).

Tests not run by policy. Maven not run.
