package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.service.DigestService;
import org.lite.project.cashiersaas.configuration.MidtransProperties;
import org.lite.project.cashiersaas.service.MidtransClient;
import org.lite.project.cashiersaas.service.PaymentGateway;
import org.lite.project.cashiersaas.service.PlatformBillingSettlementService;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlatformBillingSettlementServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

    @Mock private FnbOrderRepository orders;
    @Mock private PlatformBillingRepository billing;
    @Mock private SubscriptionRepository subscriptions;
    @Mock private AiUsageRepository aiUsage;
    @Mock private AuditLogRepository audit;
    @Mock private MidtransClient midtrans;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID invoiceId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final TenantEntity tenant = withId(new TenantEntity(), tenantId);
    private PlatformBillingSettlementService service;

    @BeforeEach
    void setUp() {
        service = new PlatformBillingSettlementService(orders, billing, subscriptions, aiUsage, audit,
                new PaymentGateway(billing, new DigestService(), midtrans, new MidtransProperties(), "MOCK"));
    }

    private SubscriptionEntity liveRow(PlanCode plan, Instant endsAt) {
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, plan, 0);
        row.activate(NOW.minus(Duration.ofDays(5)), endsAt);
        return row;
    }

    private PlatformInvoiceEntity invoiceFor(SubscriptionEntity row, InvoiceKind kind, Instant issuedAt) {
        boolean topup = kind == InvoiceKind.AI_TOPUP;
        return withId(PlatformInvoiceEntity.issue(tenant, row, "INV-1", kind, topup ? "PREMIUM" : "PRO", topup ? null : BillingCycle.MONTHLY,
                topup ? null : 1, 0, topup ? 200 : 0, new BigDecimal("59000"), issuedAt), invoiceId);
    }

    private PlatformPaymentAttemptEntity pendingAttempt(PlatformInvoiceEntity invoice) {
        PlatformPaymentAttemptEntity attempt = attemptOf(invoice);
        when(billing.pendingAttempts(invoiceId)).thenReturn(List.of(attempt));
        return attempt;
    }

    private static PlatformPaymentAttemptEntity attemptOf(PlatformInvoiceEntity invoice) {
        return withId(PlatformPaymentAttemptEntity.initiate(invoice, "MOCK", "mock", "mock_ref", "key-0000000000001", "fp"), UUID.randomUUID());
    }

    private void invoiceIsLoaded(PlatformInvoiceEntity invoice) {
        when(billing.invoiceById(invoiceId)).thenReturn(Optional.of(invoice));
    }

    @ParameterizedTest
    @CsvSource(value = {"null,PRO,false", "PRO,PRO,true", "PREMIUM,PRO,true", "PRO,PREMIUM,false"}, nullValues = "null")
    void subscriptionStartsNowOrRightAfterTheLastPaidPeriodOfTheSameOrAHigherPlan(PlanCode live, PlanCode requested, boolean queued) {
        Instant liveEnds = NOW.plus(Duration.ofDays(10));
        when(subscriptions.findLivePaid(tenantId, NOW)).thenReturn(live == null ? List.of() : List.of(liveRow(live, liveEnds)));

        assertEquals(queued ? liveEnds : NOW, service.subscriptionStart(tenantId, requested, NOW));
    }

    @Test
    void subscriptionStartRefusesAnOpenEndedPaidRow() {
        when(subscriptions.findLivePaid(tenantId, NOW)).thenReturn(List.of(liveRow(PlanCode.PREMIUM, null)));

        OperationConflictException error = assertThrows(OperationConflictException.class,
                () -> service.subscriptionStart(tenantId, PlanCode.PRO, NOW));

        assertEquals("Paket aktif tanpa batas waktu, hubungi admin.", error.getMessage());
    }

    @Test
    void periodEndAddsCalendarMonthsInTheTenantZone() {
        // 31 Jan 17:00 Jakarta + 1 month clamps to 28 Feb 17:00 Jakarta
        assertEquals(Instant.parse("2026-02-28T10:00:00Z"), service.periodEnd(tenant, Instant.parse("2026-01-31T10:00:00Z"), 1));
    }

    @Test
    void settlingASubscriptionInvoiceActivatesItsRowPaysTheAttemptAndAudits() {
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, PlanCode.PRO, 0);
        PlatformInvoiceEntity invoice = invoiceFor(row, InvoiceKind.SUBSCRIPTION, Instant.now());
        invoiceIsLoaded(invoice);
        PlatformPaymentAttemptEntity attempt = pendingAttempt(invoice);

        PlatformInvoiceEntity settled = service.settle(invoiceId, "bank-ref-1", adminId, "transfer dicek");

        assertSame(invoice, settled);
        assertEquals(PlatformInvoiceEntity.PAID, invoice.getStatus());
        assertNotNull(invoice.getPaidAt());
        assertEquals("ACTIVE", row.getStatus());
        assertNotNull(row.getStartsAt());
        assertEquals(service.periodEnd(tenant, row.getStartsAt(), 1), row.getEndsAt());
        assertEquals(PlatformPaymentAttemptEntity.SUCCEEDED, attempt.getStatus());
        assertEquals("bank-ref-1", attempt.getProviderPaymentRef());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("PLATFORM_INVOICE_PAID", log.getValue().getAction());
        assertEquals("transfer dicek", log.getValue().getReason());
    }

    @Test
    void settlingAnAiTopupInvoiceAddsTheBonusQuestionsToThisMonth() {
        SubscriptionEntity premium = liveRow(PlanCode.PREMIUM, NOW.plus(Duration.ofDays(30)));
        when(subscriptions.findActiveAt(eq(tenantId), any())).thenReturn(List.of(premium));
        PlatformInvoiceEntity invoice = invoiceFor(premium, InvoiceKind.AI_TOPUP, Instant.now());
        invoiceIsLoaded(invoice);
        pendingAttempt(invoice);

        service.settle(invoiceId, null, adminId, null);

        LocalDate today = LocalDate.now(java.time.ZoneId.of(tenant.getTimeZone()));
        verify(aiUsage).addBonusQuestions(tenantId, today.withDayOfMonth(1), today, 200);
        assertEquals(PlatformInvoiceEntity.PAID, invoice.getStatus());
    }

    @Test
    void settlingAPaidInvoiceAgainWithTheSameReferenceReturnsItWithoutASecondActivation() {
        PlatformInvoiceEntity invoice = invoiceFor(liveRow(PlanCode.PRO, NOW.plus(Duration.ofDays(30))), InvoiceKind.SUBSCRIPTION, Instant.now());
        invoice.markPaid(Instant.now());
        invoiceIsLoaded(invoice);
        PlatformPaymentAttemptEntity attempt = attemptOf(invoice);
        attempt.succeed("bank-ref-1");
        when(billing.latestAttempts(List.of(invoiceId))).thenReturn(Map.of(invoiceId, attempt));

        assertSame(invoice, service.settle(invoiceId, "bank-ref-1", adminId, null));

        verifyNoInteractions(audit, aiUsage, subscriptions);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "other-ref")
    void settlingAPaidInvoiceWithAnotherReferenceIsAConflict(String reference) {
        PlatformInvoiceEntity invoice = invoiceFor(liveRow(PlanCode.PRO, NOW.plus(Duration.ofDays(30))), InvoiceKind.SUBSCRIPTION, Instant.now());
        invoice.markPaid(Instant.now());
        invoiceIsLoaded(invoice);
        PlatformPaymentAttemptEntity attempt = attemptOf(invoice);
        attempt.succeed("bank-ref-1");
        when(billing.latestAttempts(List.of(invoiceId))).thenReturn(Map.of(invoiceId, attempt));

        assertThrows(OperationConflictException.class, () -> service.settle(invoiceId, reference, adminId, null));

        verifyNoInteractions(audit);
    }

    @Test
    void settlingAnInvoiceThatIsNotIssuedIsAConflict() {
        PlatformInvoiceEntity invoice = invoiceFor(SubscriptionEntity.pending(tenant, PlanCode.PRO, 0), InvoiceKind.SUBSCRIPTION, Instant.now());
        invoice.cancel("batal", Instant.now());
        invoiceIsLoaded(invoice);

        OperationConflictException error = assertThrows(OperationConflictException.class, () -> service.settle(invoiceId, "ref", adminId, null));

        assertEquals("Tagihan tidak dapat dibayar.", error.getMessage());
        verifyNoInteractions(audit);
    }

    @Test
    void settlingAPastDueInvoiceExpiresItAndReleasesItsHoldBeforeRefusing() {
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, PlanCode.PRO, 0);
        PlatformInvoiceEntity invoice = invoiceFor(row, InvoiceKind.SUBSCRIPTION, Instant.now().minus(Duration.ofDays(2)));
        invoiceIsLoaded(invoice);
        PlatformPaymentAttemptEntity attempt = pendingAttempt(invoice);

        OperationConflictException error = assertThrows(OperationConflictException.class, () -> service.settle(invoiceId, "ref", adminId, null));

        assertEquals("Tagihan sudah kedaluwarsa.", error.getMessage());
        assertEquals(PlatformInvoiceEntity.EXPIRED, invoice.getStatus());
        assertEquals(SubscriptionEntity.CANCELLED, row.getStatus());
        assertEquals(PlatformPaymentAttemptEntity.CANCELLED, attempt.getStatus());
        verify(audit, never()).persist(any(AuditLogEntity.class));
    }

    @Test
    void aFailedPaymentKeepsTheInvoiceIssuedAndRecordsTheFailedAttempt() {
        PlatformInvoiceEntity invoice = invoiceFor(SubscriptionEntity.pending(tenant, PlanCode.PRO, 0), InvoiceKind.SUBSCRIPTION, Instant.now());
        invoiceIsLoaded(invoice);
        PlatformPaymentAttemptEntity attempt = pendingAttempt(invoice);

        service.fail(invoiceId, adminId);

        assertEquals(PlatformInvoiceEntity.ISSUED, invoice.getStatus());
        assertEquals(PlatformPaymentAttemptEntity.FAILED, attempt.getStatus());
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals("PLATFORM_PAYMENT_FAILED", log.getValue().getAction());
    }

    private static <T> T withId(T entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
