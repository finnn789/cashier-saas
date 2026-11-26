package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.component.MerchantAccessContext;
import org.lite.project.cashiersaas.component.TenantPrincipal;
import org.lite.project.cashiersaas.dto.request.MockPaymentRequest;
import org.lite.project.cashiersaas.dto.request.SubscriptionCheckoutRequest;
import org.lite.project.cashiersaas.dto.response.PlatformInvoiceResponse;
import org.lite.project.cashiersaas.dto.response.SubscriptionQuoteResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.service.DigestService;
import org.lite.project.cashiersaas.service.MembershipPlanCatalogService;
import org.lite.project.cashiersaas.service.MerchantAccessGuard;
import org.lite.project.cashiersaas.service.MockPaymentGateway;
import org.lite.project.cashiersaas.service.PlatformBillingService;
import org.lite.project.cashiersaas.service.PlatformBillingSettlementService;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlatformBillingServiceTest {
    private static final String KEY = "checkout-key-0000001";

    @Mock private MerchantAccessGuard guard;
    @Mock private FnbOrderRepository orders;
    @Mock private PlatformBillingRepository billing;
    @Mock private SubscriptionRepository subscriptions;
    @Mock private AiUsageRepository aiUsage;
    @Mock private AuditLogRepository audit;
    private final DigestService digests = new DigestService();
    private final MembershipPlanCatalogService catalog = new MembershipPlanCatalogService();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID invoiceId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private final MerchantAccessContext context = new MerchantAccessContext(tenant, null, null);
    private final TenantPrincipal principal = new TenantPrincipal(UUID.randomUUID(), tenantId, Set.of(Role.OWNERMERCHANT));
    private PlatformBillingService service;

    @BeforeEach
    void setUp() {
        service = serviceWith(true);
    }

    private PlatformBillingService serviceWith(boolean mockGateway) {
        MockPaymentGateway gateway = new MockPaymentGateway(billing, digests, mockGateway);
        PlatformBillingSettlementService settlement = new PlatformBillingSettlementService(orders, billing, subscriptions, aiUsage, audit, gateway);
        return new PlatformBillingService(guard, orders, billing, subscriptions, audit, digests, catalog, settlement, gateway);
    }

    private void ownerCalls() {
        when(guard.requireOwnerIgnoringAllowance(eq(principal), any())).thenReturn(context);
    }

    private static SubscriptionCheckoutRequest subscription(PlanCode plan, BillingCycle cycle, Integer extraOutlets) {
        SubscriptionCheckoutRequest request = new SubscriptionCheckoutRequest();
        request.setKind(InvoiceKind.SUBSCRIPTION);
        request.setPlanCode(plan);
        request.setBillingCycle(cycle);
        request.setExtraOutlets(extraOutlets);
        return request;
    }

    private static SubscriptionCheckoutRequest topup() {
        SubscriptionCheckoutRequest request = new SubscriptionCheckoutRequest();
        request.setKind(InvoiceKind.AI_TOPUP);
        return request;
    }

    private SubscriptionEntity paidRow(PlanCode plan) {
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, plan, 0);
        row.activate(Instant.now().minus(Duration.ofDays(1)), Instant.now().plus(Duration.ofDays(10)));
        return row;
    }

    private PlatformInvoiceEntity issuedSubscriptionInvoice(SubscriptionEntity row) {
        return withId(PlatformInvoiceEntity.issue(tenant, row, "INV-1", InvoiceKind.SUBSCRIPTION, "PRO", BillingCycle.MONTHLY, 1, 0, 0,
                new BigDecimal("59000"), Instant.now()), invoiceId);
    }

    private PlatformPaymentAttemptEntity attempt(PlatformInvoiceEntity invoice, String fingerprint) {
        return withId(PlatformPaymentAttemptEntity.initiate(invoice, "MOCK", "mock", "mock_ref", KEY, fingerprint), UUID.randomUUID());
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "amount was " + actual);
    }

    @ParameterizedTest
    @CsvSource({"PRO,MONTHLY,0,59000", "PRO,YEARLY,0,590000", "PREMIUM,MONTHLY,0,119000", "PREMIUM,YEARLY,0,1190000",
            "PRO,MONTHLY,2,157000", "PREMIUM,YEARLY,1,1778000"})
    void quotePricesPlanCycleAndOutletAddOnFromTheServerCatalog(PlanCode plan, BillingCycle cycle, int extraOutlets, String total) {
        ownerCalls();

        SubscriptionQuoteResponse quote = service.quote(principal, subscription(plan, cycle, extraOutlets));

        assertAmount(total, quote.getTotalAmount());
        assertEquals(cycle.getMonths(), quote.getPeriodMonths());
        assertEquals(extraOutlets, quote.getExtraOutlets());
    }

    @Test
    void quoteOfAiTopupIsFixedPriceFor200QuestionsOnPaidPremium() {
        ownerCalls();
        when(subscriptions.findActiveAt(eq(tenantId), any())).thenReturn(List.of(paidRow(PlanCode.PREMIUM)));

        SubscriptionQuoteResponse quote = service.quote(principal, topup());

        assertAmount("29000", quote.getTotalAmount());
        assertEquals(200, quote.getAiQuestions());
        assertEquals("AI_TOPUP", quote.getChangeType());
    }

    @ParameterizedTest
    @CsvSource(value = {"null,PRO,NEW", "PRO,PRO,RENEW", "PRO,PREMIUM,UPGRADE", "PREMIUM,PRO,DOWNGRADE"}, nullValues = "null")
    void quoteClassifiesTheChangeAgainstTheHighestLivePaidPlan(PlanCode live, PlanCode requested, String changeType) {
        ownerCalls();
        when(subscriptions.findLivePaid(eq(tenantId), any())).thenReturn(live == null ? List.of() : List.of(paidRow(live)));

        SubscriptionQuoteResponse quote = service.quote(principal, subscription(requested, BillingCycle.MONTHLY, 0));

        assertEquals(changeType, quote.getChangeType());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "too-short")
    void checkoutRequiresAWellFormedIdempotencyKey(String key) {
        ownerCalls();

        assertThrows(InvalidOperationException.class, () -> service.checkout(principal, key, subscription(PlanCode.PRO, BillingCycle.MONTHLY, 0)));

        verify(billing, never()).persist(any(PlatformInvoiceEntity.class));
    }

    @Test
    void checkoutReplayWithTheSameKeyAndPayloadReturnsTheExistingInvoiceWithoutNewRows() {
        ownerCalls();
        PlatformInvoiceEntity invoice = issuedSubscriptionInvoice(SubscriptionEntity.pending(tenant, PlanCode.PRO, 0));
        PlatformPaymentAttemptEntity attempt = attempt(invoice, digests.digest("SUBSCRIPTION|PRO|MONTHLY|0"));
        when(billing.attemptByKey(tenantId, KEY)).thenReturn(Optional.of(attempt));
        when(billing.latestAttempts(List.of(invoiceId))).thenReturn(Map.of(invoiceId, attempt));

        PlatformInvoiceResponse response = service.checkout(principal, KEY, subscription(PlanCode.PRO, BillingCycle.MONTHLY, 0));

        assertEquals(invoiceId, response.getId());
        verify(billing, never()).persist(any(PlatformInvoiceEntity.class));
        verify(billing, never()).persist(any(PlatformPaymentAttemptEntity.class));
        verify(subscriptions, never()).persist(any());
        verifyNoInteractions(audit);
    }

    @Test
    void checkoutReusingAKeyForAnotherPayloadIsAConflict() {
        ownerCalls();
        PlatformInvoiceEntity invoice = issuedSubscriptionInvoice(SubscriptionEntity.pending(tenant, PlanCode.PRO, 0));
        when(billing.attemptByKey(tenantId, KEY)).thenReturn(Optional.of(attempt(invoice, digests.digest("SUBSCRIPTION|PRO|MONTHLY|0"))));

        OperationConflictException error = assertThrows(OperationConflictException.class,
                () -> service.checkout(principal, KEY, subscription(PlanCode.PREMIUM, BillingCycle.YEARLY, 0)));

        assertEquals("Idempotency-Key telah digunakan untuk tagihan berbeda.", error.getMessage());
    }

    @Test
    void checkoutIsRefusedWhileAnotherInvoiceIsStillOpen() {
        ownerCalls();
        when(billing.openInvoice(eq(tenantId), any())).thenReturn(Optional.of(issuedSubscriptionInvoice(SubscriptionEntity.pending(tenant, PlanCode.PRO, 0))));

        OperationConflictException error = assertThrows(OperationConflictException.class,
                () -> service.checkout(principal, KEY, subscription(PlanCode.PRO, BillingCycle.MONTHLY, 0)));

        assertEquals("Masih ada tagihan yang belum dibayar.", error.getMessage());
        verify(billing, never()).persist(any(PlatformInvoiceEntity.class));
    }

    @ParameterizedTest
    @CsvSource({"true,MOCK", "false,MANUAL_TRANSFER"})
    void checkoutIssuesAPendingRowAnInvoiceDueIn24HoursAnAttemptAndAnAudit(boolean mockGateway, String provider) {
        service = serviceWith(mockGateway);
        ownerCalls();

        PlatformInvoiceResponse response = service.checkout(principal, KEY, subscription(PlanCode.PRO, BillingCycle.YEARLY, 1));

        ArgumentCaptor<SubscriptionEntity> row = ArgumentCaptor.forClass(SubscriptionEntity.class);
        verify(subscriptions).persist(row.capture());
        assertTrue(row.getValue().isPending());
        assertEquals(PlanCode.PRO, row.getValue().getPlan());
        assertEquals(1, row.getValue().getExtraOutlets());
        ArgumentCaptor<PlatformInvoiceEntity> invoice = ArgumentCaptor.forClass(PlatformInvoiceEntity.class);
        verify(billing).persist(invoice.capture());
        assertTrue(invoice.getValue().isIssued());
        assertAmount("1178000", invoice.getValue().getAmountDue());
        assertEquals(12, invoice.getValue().getPeriodMonths());
        assertEquals(Duration.ofHours(24), Duration.between(invoice.getValue().getIssuedAt(), invoice.getValue().getDueAt()));
        ArgumentCaptor<PlatformPaymentAttemptEntity> attempt = ArgumentCaptor.forClass(PlatformPaymentAttemptEntity.class);
        verify(billing).persist(attempt.capture());
        assertEquals(provider, attempt.getValue().getProviderCode());
        assertTrue(attempt.getValue().isPending());
        assertEquals(KEY, attempt.getValue().getIdempotencyKey());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("PLATFORM_INVOICE_ISSUED", log.getValue().getAction());
        assertEquals("ISSUED", response.getStatus());
        assertEquals(provider, response.getProviderCode());
        assertEquals(mockGateway, response.isMockPaymentEnabled());
    }

    @Test
    void checkoutOfAnAiTopupLinksThePaidPremiumRowWithoutCreatingASubscription() {
        ownerCalls();
        SubscriptionEntity premium = paidRow(PlanCode.PREMIUM);
        when(subscriptions.findActiveAt(eq(tenantId), any())).thenReturn(List.of(premium));

        PlatformInvoiceResponse response = service.checkout(principal, KEY, topup());

        verify(subscriptions, never()).persist(any());
        ArgumentCaptor<PlatformInvoiceEntity> invoice = ArgumentCaptor.forClass(PlatformInvoiceEntity.class);
        verify(billing).persist(invoice.capture());
        assertEquals(premium, invoice.getValue().getSubscription());
        assertEquals(200, invoice.getValue().getAiQuestions());
        assertAmount("29000", response.getAmountDue());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void checkoutOfAnAiTopupWithoutAPaidPremiumIsAConflict(boolean hasProRow) {
        ownerCalls();
        when(subscriptions.findActiveAt(eq(tenantId), any())).thenReturn(hasProRow ? List.of(paidRow(PlanCode.PRO)) : List.of());

        OperationConflictException error = assertThrows(OperationConflictException.class, () -> service.checkout(principal, KEY, topup()));

        assertEquals("Top-up tersedia untuk paket Premium berbayar.", error.getMessage());
        verify(billing, never()).persist(any(PlatformInvoiceEntity.class));
    }

    @Test
    void cancellingAnIssuedInvoiceReleasesItsPendingRowAndAttemptsAndAudits() {
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, PlanCode.PRO, 0);
        PlatformInvoiceEntity invoice = issuedSubscriptionInvoice(row);
        PlatformPaymentAttemptEntity attempt = attempt(invoice, "fp");
        when(billing.invoice(tenantId, invoiceId)).thenReturn(Optional.of(invoice));
        when(billing.pendingAttempts(invoiceId)).thenReturn(List.of(attempt));

        PlatformInvoiceResponse response = service.cancel(tenantId, invoiceId, "  salah paket ", principal.getUserId());

        assertEquals(PlatformInvoiceEntity.CANCELLED, invoice.getStatus());
        assertEquals("salah paket", invoice.getCancelReason());
        assertEquals(SubscriptionEntity.CANCELLED, row.getStatus());
        assertEquals(PlatformPaymentAttemptEntity.CANCELLED, attempt.getStatus());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("PLATFORM_INVOICE_CANCELLED", log.getValue().getAction());
        assertEquals("salah paket", log.getValue().getReason());
        assertEquals(PlatformInvoiceEntity.CANCELLED, response.getStatus());
    }

    @Test
    void cancellingAPaidInvoiceIsAConflictAndWritesNothing() {
        PlatformInvoiceEntity invoice = issuedSubscriptionInvoice(SubscriptionEntity.pending(tenant, PlanCode.PRO, 0));
        invoice.markPaid(Instant.now());
        when(billing.invoice(tenantId, invoiceId)).thenReturn(Optional.of(invoice));

        OperationConflictException error = assertThrows(OperationConflictException.class,
                () -> service.cancel(tenantId, invoiceId, "x", principal.getUserId()));

        assertEquals("Tagihan tidak dapat dibatalkan.", error.getMessage());
        assertEquals(PlatformInvoiceEntity.PAID, invoice.getStatus());
        verifyNoInteractions(audit);
    }

    @Test
    void cancellingAnInvoiceOfAnotherTenantIsNotFound() {
        when(billing.invoice(tenantId, invoiceId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.cancel(tenantId, invoiceId, "x", principal.getUserId()));

        verifyNoInteractions(audit);
    }

    @Test
    void mockPaymentIsNotFoundWhenTheMockGatewayIsDisabled() {
        service = serviceWith(false);
        ownerCalls();
        MockPaymentRequest request = new MockPaymentRequest();
        request.setOutcome(PlatformPaymentAttemptEntity.SUCCEEDED);

        assertThrows(ResourceNotFoundException.class, () -> service.mockPay(principal, invoiceId, request));

        verify(billing, never()).invoice(any(), any());
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
