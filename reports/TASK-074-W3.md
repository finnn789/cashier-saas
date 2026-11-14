# TASK-074 W3 report (tests under src/test, excluding W2 files)

Status: DONE (tests NOT run, NOT compiled, by policy). Compiled against the packet's Shared contract, not the live main source.

| Test class | Action |
|---|---|
| unit/component/FnbOrderChangedListenerTest | deleted (git rm) |
| unit/component/FnbRealtimeHandshakeInterceptorTest | deleted |
| unit/component/FnbRealtimeWebSocketHandlerTest | deleted |
| unit/configuration/FnbWebSocketConfigurationTest | deleted |
| unit/dto/FnbRealtimeMapperTest | deleted |
| unit/dto/FnbMenuMapperTest | deleted (all FnbMenuMapper methods were qr/table/menu) |
| unit/service/FnbOrderQrServiceTest | deleted |
| unit/service/FnbRealtimeAccessServiceTest | deleted |
| unit/service/FnbRealtimePublisherTest | deleted |
| unit/service/FnbRealtimeTicketServiceTest | deleted |
| unit/service/PublicOrderServiceTest | deleted |
| unit/service/PublicQrAccessServiceTest | deleted |
| diningtable/api, diningtable/unit, kitchen/api, kitchen/unit `.gitkeep` | deleted (empty placeholder dirs for removed features) |
| unit/service/PublicOrderTokenServiceTest -> DigestServiceTest | git mv + rewritten: `new DigestService()`, 2 digest tests (stable/64 hex, differs per input); HMAC token tests dropped |
| unit/service/FnbStockServiceTest | edited: mock type PublicOrderTokenService -> DigestService (field `tokens` unchanged) |
| unit/service/FnbInventoryServiceTest | edited: same type swap |
| unit/service/MerchantBusinessSettingsServiceTest | edited: `PosDetails` loses the leading `null` table component |
| unit/entity/SalesOrderEntityTest | edited: all 3 cases used `publicQr`/`PublicQrDetails`/`DiningTableEntity`/`selectPublicServiceType`, so none could stay as-is. Ported to `SalesOrderEntity.pos(...)` with the new `PosDetails`: factory snapshot case (kept all total/status/payment/source assertions, source now POS, diningTable/token-digest assertions dropped) and takeaway + null-serviceType rejection case. The table+takeaway rejection case was deleted (table gone). No kitchen-transition case existed. |
| integration/fnb/FnbPayFirstFlowIntegrationTest | edited: `posOrderJson`/`createPosOrder` lose `tableId`; all `createTable` calls removed; `posOrderCashPaymentKitchenLifecycleAndSalesReport` -> `posOrderCashPaymentAndSalesReport` (kitchen prepare/ready/complete steps and the "kitchen cannot start unpaid order" check removed; status stays CONFIRMED after pay; payment, change, receipt, report, countPayments assertions unchanged); `publicQrOrderWithIdempotencyKeyThenCashPayment` -> `posOrderWithIdempotencyKeyThenCashPayment` (POS create with Idempotency-Key, same-key retry returns same order and 1 row, changed body 409, NEW/UNPAID before pay, list source=POS, cash payment + change, CONFIRMED/PAID after); `publicOrderJson` removed. Idempotency-replay, concurrent-payment, tenant-isolation tests only lost the table argument. |
| integration/fnb/FnbReusableOptionGroupIntegrationTest | edited: `publicMenuListsTheSharedGroup...` -> `cashierListsTheSharedGroupUnderBothProductsWithEachOptionExactlyOnce`, using retained cashier endpoints (`GET products/{id}/option-groups` once per product, empty for plain product, `GET option-groups/{id}/options` has Boba+Jelly exactly once). The public menu response shape (categories/products/groups/options nesting) has no retained equivalent. |
| integration/support/ApiTestSupport | edited: `createTable(...)` removed (only callers were the two files above) |
| integration/fnb/FnbInventoryFlowIntegrationTest, FnbStockConcurrencyIntegrationTest, grpc/*, identity/*, application/*, other unit tests | untouched (grep showed no references) |
| W2 files (HttpRateLimiterTest, RateLimitFilterTest, RateLimitTestProperties, SecurityAuthorizationIntegrationTest, application-test.properties) | not touched |

## Grep evidence (src/test excluding W2 files)
Pattern: FnbRealtimePublisher|FnbTableReconciliationService|FnbTableRepository|DiningTable|diningTable|tableId|tableName|publicQr|PublicQr|PublicOrder|Kitchen|kitchen|realtime|/public/|startPreparing|markReady|TableQr|FnbMenuMapper|FnbOrderMapper|.complete()|createTable|FnbOrderQr|FnbTable|Realtime|WebSocket -> 0 matches. `PosDetails` -> 4 matches, all new 5-component shape (SalesOrderEntityTest x3, MerchantBusinessSettingsServiceTest x1). `QR`/`qr` -> 0.
No unit test existed that builds FnbPosOrderService/FnbPaymentService/MockFnbGatewayService/FnbDiscountService/FnbOrderCancellationService by constructor.

## Blocked / needs orchestrator
- None. Note: SalesOrderEntityTest.pos factory tests assume `pos(...)` throws InvalidOperationException for null service type (per contract).
- `OrderSource.QR` kept in main per packet; no test references it any more.
- Tests not run by policy.
