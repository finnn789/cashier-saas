# TASK-105 J1 report (backend)

Status: IMPLEMENTED, BLOCKED ON ONE NON-RESERVED EDIT (compile fails until it is applied). Baseline commit `ded6050`.

## Blocking need (outside my reserved paths)
`entity/shared/audit/AuditLogEntity.java` `Action` enum needs four constants (entity type `PLATFORM_INVOICE`):
```
PLATFORM_INVOICE_ISSUED("PLATFORM_INVOICE"),
PLATFORM_INVOICE_PAID("PLATFORM_INVOICE"),
PLATFORM_INVOICE_CANCELLED("PLATFORM_INVOICE"),
PLATFORM_PAYMENT_FAILED("PLATFORM_INVOICE"),
```
The services already reference them. audit_logs has no CHECK on action, so no migration is needed.

## Changed files
New: `db/migration/V18__platform_billing_checkout.sql`, `enums/{BillingCycle,InvoiceKind}.java`, `repository/PlatformBillingRepository.java`,
`service/{PlatformBillingService,PlatformBillingSettlementService,MockPaymentGateway}.java`, `dto/PlatformInvoiceMapper.java`,
`dto/request/{SubscriptionCheckoutRequest,MockPaymentRequest,InvoiceCancelRequest}.java`, `dto/response/{SubscriptionQuoteResponse,PlatformInvoiceResponse}.java`.
Modified: the four billing entities (named state methods, no setters), `SubscriptionRepository` (`findLivePaid`), `AiUsageRepository` (`addBonusQuestions`),
`MembershipPlanCatalogService` (`price`, AI top-up constants), `MerchantSubscriptionService` + `MerchantSubscriptionResponse` (`pendingInvoiceId`, `mockPaymentEnabled`),
`MerchantSubscriptionController` (quote, checkout, invoices, invoices/{id}, mock-pay, cancel), `application.properties` (`cashier.billing.mock-gateway.enabled`).

## Contract points done
- All endpoints of the packet contract; messages as specified; owner only via `requireOwnerIgnoringAllowance`; invoice lookups are tenant scoped (foreign id -> 404).
- D1 mock/manual provider switch; mock-pay 404 when off; one gateway event per mock-pay. D2 PENDING/ACTIVE/CANCELLED (CHECK in V18).
- D3 start rule in `PlatformBillingSettlementService.subscriptionStart` (used by quote and settlement; open-ended row -> 409). D4 server-side price, extra outlets 0..20.
- D5 invoice number, 24h due, lazy expiry (`expireDue`, tenant lock only when something is due), one open invoice per tenant, idempotency key required and fingerprinted.
- D7/D8 `settle(invoiceId, providerPaymentRef, actorUserId)` and `fail(...)` in the settlement service, `cancel(tenantId, invoiceId, reason, actorUserId)` in the billing service, for TASK-106.
  Settle works on the invoice's own tenant (no principal), takes the tenant lock and refreshes the invoice. Same-ref repeat on a PAID invoice returns it; other repeat -> 409.
- `noRollbackFor = OperationConflictException` on settle/fail/mock-pay/cancel so a lazy EXPIRED decision survives the 409 it triggers.

## Deviations / decisions
- Replay of a checkout key returns the invoice's current state, not a frozen first response.
- Mock-pay outcome is a validated String (`SUCCEEDED|FAILED`), no extra enum. `changeType` is a String as in the contract.
- Audit actor is a Hibernate reference by user id (null actor allowed for TASK-106).
- Gateway events are stored only for MOCK attempts (a manual transfer has no provider callback).

## Risks to review
- Upgrade overlap: after PRO(extra=2) -> PREMIUM(extra=2) both rows are active, so `allowedOutletCount` sums to 1+4 while both run (D3 as written, no proration). FE prefilling the current extra makes this likely; consider charging only the delta or zeroing the extra of the lower row.
- Settlement is reachable by TASK-106 with no tenant check by design (SUPERADMIN).

## Existing tests needing update
No existing test references the changed classes. `PlanEntitlementIntegrationTest` / `ApiTestSupport` insert subscription rows with ACTIVE status only, so V18's status CHECK is compatible.

Test status: NOT RUN (policy). Compile: NOT RUN (policy).
