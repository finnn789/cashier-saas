# Test audit — 2026-11-14 (read-only)

Method: 4 parallel read-only auditors (sonnet) using Codebase Memory (`cashier-saas`, `cashier-ai-services`) + source Read/Grep; orchestrator spot-checked samples. Graph index is stale (2026-11-12, before M6/M7), so all claims were verified against current source. No test or coverage tool was run; a JaCoCo/coverage before-after run is recommended when deleting.

Baseline: Java unit 48 files, ~537 methods (609 runs); Java IT 22 files (148 runs); Python unit 25 files (323); Python integration 3 files (24).

## Tiers
- **Tier 1 — safe delete (DUPLICATE + TRIVIAL):** same path + same assertion, or asserts nothing meaningful.
- **Tier 2 — merge into @ParameterizedTest / subTest:** fewer methods, same coverage.
- **Tier 3 — REDUNDANT-WITH-IT:** an integration test proves the same end to end. Deleting costs fast feedback (ITs need Docker/Postgres). Not recommended by default.

| Area | Tier 1 | Tier 2 (methods saved) | Tier 3 |
|---|---|---|---|
| Java F&B catalog/option/inventory/stock (163) | 13 | 21 | 39 |
| Java gRPC/insight/shift/payment/entity (234) | ~21 | ~18 | 57 |
| Java other (merchant, ratelimit, JWT, sync, ...) (~140) | 16 | 10 | 19 |
| Python unit (323) | ~39 | ~28 | — |
| Python integration (24) | 4 | — | — |
| **Total** | **~93** | **~77** | **~115** |

## Tier 1 details
### Java F&B (R=FnbCatalogRecipeServiceTest, OG=FnbOptionGroupServiceTest, I=FnbInventoryServiceTest)
- R:339 = R:312 (identical stubs/assert, verified). R:251 -> fold `never(deleteOptionRecipe)` into R:178. R:430 subset of R:231. R:482 and OG:366 = R:135 (same archived-category branch).
- OG:328 mock-identical to OG:315. OG:544 = R:702. OG:529 = R:689.
- I:427, I:438, I:450 covered by strict stubs of I:464/477/490/503.
- Trivial: R:522, R:592 (empty mock -> empty list). Assertion-only: `verify(catalog, never()).product(...)` at OG:133/216/485/515/541 asserts the removed product-scoped design.
### Java gRPC/insight/shift
- GrpcTenantAccessPolicyTest:89 = :78 (upper-case no-op on digits), :106 covered by :95/:114, :318 = :237/:246.
- GrpcServiceKeyInterceptorTest:171 = GrpcRateLimiterTest:49. FnbSalesReportServiceTest:79 = mapper tests. FnbPosOrderServiceTest:145 = :157. SalesOrderEntityTest:169 covered by FnbPosOrderServiceTest:203. InventoryBalanceEntityTest:87, :111 subsumed. DailySummaryClientTest:165 = :138, :173 = :156.
- Trivial: FnbOwnerInsightServiceTest:357 (tautology on mock), :428 (mocks the thing under test); GrpcServiceKeyInterceptorTest:76 (constant); GrpcSecurityPropertiesValidationTest:52 (defaults); GrpcInsightMapperTest:184; CashShiftEntityTest:36, InventoryBalanceEntityTest:35 (getter echo); FnbSalesReportMapperTest:112, :124 (copy mappers); GrpcTenantAccessPolicyTest:222+:230 -> merge to one.
- Orchestrator rejected: CashierGrpcHandlerRejectionTest order-vs-catalog "twins" (different handlers, keep); FnbPosOrderServiceTest:192 QR guard (protects legacy pre-pivot rows, keep).
### Java other
- GrpcRateLimiterTest:50, :63, :76, :85, :111 = TokenBucketTest refill/cap/concurrency (limiter only wraps TokenBucket); keep :40, :95, :105.
- GlobalExceptionHandlerTest:35, :45 covered by :55 (verified). MerchantAuditMapperTest:47 = :63. RateLimitFilterTest:105 = :145.
- Trivial: MerchantBusinessSettingsServiceTest:88 (tautology, dup of :80), :259 (asserts an unrelated object); HttpRateLimiterTest:94, :101; RateLimitFilterTest:183; MemberSyncListenerTest:61 (annotation reflection); MemberSyncClientTest:301.
- Not recommended: MerchantAuditRepositoryTest:56/:101 are mock chains but the only repository-level coverage of audit-log queries (no IT calls `/audit-logs`).
### Python
- **False-positive tests (pass without testing anything):** test_grpc_tools.py:47, :57, :67, :85 build `GrpcSettings` without `service_key`, so the client returns before reaching the stub/socket (verified). Real coverage: test_grpc_client.py:195/:219/:244, test_grpc_client_security.py:119/:132, integration test_grpc_integration.py:227.
- Duplicates: tools.py:72 + insights.py:241 = grpc_tools.py:282; insights.py:331 = tools.py:29; langgraph_chat.py:261 = tenant_scope.py:241; grpc_client_security.py:92, :105, :221; daily_summary.py:253; ai.py:78; langgraph_chat.py:467/:478/:488 = conversation.py:165/:177/:192; database.py:90; internal_members.py:447, :504; internal_daily_summaries.py:157, :265; phone.py:21.
- Trivial: grpc_tools.py:24, :107, :181; grpc_client_security.py:64, :215 (vacuous); daily_summary.py:226, :249; internal_members.py:222/:231/:249 (FakeStore logic), :312, :460; migration_0003.py:72; repository.py:247 (hard-coded 45 columns, breaks on every schema change); langgraph_chat.py:508; tenant_identity.py:231.
- Integration duplicates of unit endpoint tests: test_internal_members_api.py:126, :136, :149, :178.
- **Dead production code:** `app/chat/members.py` `get_member` (122) and `disable_member` (144) have no caller in `app/` (verified); only internal_members.py:395 tests them.

## Tier 2 groups (summary)
- Java F&B: 10 guard-denied tests -> 1 parameterized (R:113/283/462/533/605/723, OG:384/395/407/418); price reason null/blank; validateGroup x3; delta-sign x4; restock reason; initial qty; count deficit/surplus; audit shape.
- Java gRPC: blank tenant/user param groups in GrpcTenantAccessPolicyTest; DailySummaryClientTest failure statuses; FnbOwnerInsightServiceTest below-minimum per kind; FnbPosOrderServiceTest conflict trio; InventoryBalanceEntityTest threshold/rounding/reset groups; CashShiftEntityTest; FnbSalesReportMapperTest.
- Java other: MemberSyncClientTest groups; HttpRateLimiterTest isolation; TenantJwtAuthenticationConverterTest UUID; PhoneNumberNormalizerTest; DigestServiceTest; MemberSyncPublisherTest.
- Python: database.py revisions x4; langgraph_chat.py error tables x8; grpc_client_security.py clamp x4; grpc_client.py RPC errors x3; observability settings; ai.py; conversation; tenant_intake; internal_daily_summaries.

## Tier 3 (kept unless the user decides otherwise)
Whole files that are fully re-proven by ITs: FnbPaymentLabelServiceTest (7, PLIT), FnbOrderCancellationServiceTest (3, MSIT:368-380), most of FnbShiftServiceTest (15 of 18), FnbRefundServiceTest (8 of 11), FnbPaymentServiceTest (6 of 8), DailySummaryServiceTest (9), and 39 F&B catalog/inventory rejection tests. Full line lists are in the auditor outputs summarised above.

## Side findings
- Stale pre-pivot statuses (PROCESSING, READY_FOR_PICKUP) in Python fixtures grpc_client.py:128, grpc_tools.py:190/:315.
- test_channel.py:226 dead assignment.
- No HTTP-level IT asserts 429 (AbstractPostgresIntegrationTest sets a generous limit), so the ratelimit unit tests are the only 429 coverage — keep them.
