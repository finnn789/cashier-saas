package org.lite.project.cashiersaas.unit.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lite.project.cashiersaas.component.PlatformPrincipal;
import org.lite.project.cashiersaas.dto.request.platform.PlatformAiTopupRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformGrantPlanRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformMarkPaidRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformReasonRequest;
import org.lite.project.cashiersaas.dto.response.platform.PlatformInvoiceRowResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.BillingCycle;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.PlatformAdminRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.lite.project.cashiersaas.service.MockPaymentGateway;
import org.lite.project.cashiersaas.service.PlatformAccessGuard;
import org.lite.project.cashiersaas.service.PlatformBillingService;
import org.lite.project.cashiersaas.service.PlatformBillingSettlementService;
import org.lite.project.cashiersaas.service.PlatformDashboardService;
import org.lite.project.cashiersaas.service.PlatformTenantAdminService;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PlatformTenantAdminServiceTest {
    private final PlatformPrincipal principal = new PlatformPrincipal(UUID.randomUUID(), "admin@example.com", UUID.randomUUID());
    private final UUID tenantId = UUID.randomUUID();
    private final UUID invoiceId = UUID.randomUUID();
    private final TenantEntity tenant = new TenantEntity();
    private final StaffUserEntity actor = StaffUserEntity.createCashier("admin@example.com", "hash", "Admin", null);

    @Mock
    private PlatformAccessGuard guard;
    @Mock
    private PlatformDashboardService dashboard;
    @Mock
    private PlatformAdminRepository admin;
    @Mock
    private PlatformBillingRepository billing;
    @Mock
    private SubscriptionRepository subscriptions;
    @Mock
    private FnbOrderRepository orders;
    @Mock
    private AuditLogRepository audit;
    @Mock
    private AiUsageRepository aiUsage;
    @Mock
    private PlatformBillingSettlementService settlement;
    @Mock
    private PlatformBillingService billingService;
    @Mock
    private MockPaymentGateway gateway;
    @InjectMocks
    private PlatformTenantAdminService service;

    @BeforeEach
    void identities() {
        ReflectionTestUtils.setField(tenant, "id", tenantId);
        ReflectionTestUtils.setField(actor, "id", principal.getUserId());
    }

    @Test
    void grantPlanCreatesAnActivePaidRowByTheCheckoutStartRuleAndAuditsTheTrimmedReason() {
        PlatformGrantPlanRequest request = new PlatformGrantPlanRequest();
        request.setPlanCode(PlanCode.PREMIUM);
        request.setMonths(3);
        request.setExtraOutlets(2);
        request.setReason("  Kompensasi gangguan  ");
        Instant start = Instant.now().plusSeconds(3600);
        Instant end = start.plus(Duration.ofDays(90));
        allowAndLock();
        when(settlement.subscriptionStart(any(), any(), any())).thenReturn(start);
        when(settlement.periodEnd(tenant, start, 3)).thenReturn(end);

        service.grantPlan(principal, tenantId, request);

        ArgumentCaptor<SubscriptionEntity> row = ArgumentCaptor.forClass(SubscriptionEntity.class);
        verify(subscriptions).persist(row.capture());
        assertEquals(PlanCode.PREMIUM, row.getValue().getPlan());
        assertEquals(StatusConstants.ACTIVE, row.getValue().getStatus());
        assertEquals(start, row.getValue().getStartsAt());
        assertEquals(end, row.getValue().getEndsAt());
        assertEquals(2, row.getValue().getExtraOutlets());
        assertAudit(AuditLogEntity.Action.PLATFORM_PLAN_GRANTED, "Kompensasi gangguan");
    }

    @Test
    void grantPlanRefusesFreetierWithoutWriting() {
        PlatformGrantPlanRequest request = new PlatformGrantPlanRequest();
        request.setPlanCode(PlanCode.FREETIER);
        request.setMonths(1);
        request.setReason("salah paket");
        when(guard.require(principal)).thenReturn(actor);

        assertThrows(InvalidOperationException.class, () -> service.grantPlan(principal, tenantId, request));

        verifyNoInteractions(subscriptions, audit);
    }

    @Test
    void endPlanRefusesTheFreetierBaseRow() {
        SubscriptionEntity free = SubscriptionEntity.activateFreeTier(tenant);
        allowAndLock();
        when(admin.subscription(tenantId, free.getId())).thenReturn(Optional.of(free));

        assertThrows(OperationConflictException.class, () -> service.endPlan(principal, tenantId, free.getId(), reason("tutup")));

        verifyNoInteractions(audit);
    }

    @Test
    void endPlanEndsARunningPaidRowNowAndAudits() {
        SubscriptionEntity row = paid(Instant.now().minus(Duration.ofDays(5)), Instant.now().plus(Duration.ofDays(25)));
        allowAndLock();
        when(admin.subscription(tenantId, row.getId())).thenReturn(Optional.of(row));

        service.endPlan(principal, tenantId, row.getId(), reason("  refund  "));

        assertFalse(row.getEndsAt().isAfter(Instant.now()));
        assertEquals(StatusConstants.ACTIVE, row.getStatus());
        assertAudit(AuditLogEntity.Action.PLATFORM_PLAN_ENDED, "refund");
    }

    @Test
    void endPlanCancelsARowThatHasNotStartedYet() {
        SubscriptionEntity row = paid(Instant.now().plus(Duration.ofDays(10)), Instant.now().plus(Duration.ofDays(40)));
        allowAndLock();
        when(admin.subscription(tenantId, row.getId())).thenReturn(Optional.of(row));

        service.endPlan(principal, tenantId, row.getId(), reason("batal antrian"));

        assertEquals(SubscriptionEntity.CANCELLED, row.getStatus());
        assertAudit(AuditLogEntity.Action.PLATFORM_PLAN_ENDED, "batal antrian");
    }

    @Test
    void endPlanRefusesARowThatAlreadyExpired() {
        SubscriptionEntity row = paid(Instant.now().minus(Duration.ofDays(40)), Instant.now().minus(Duration.ofDays(10)));
        allowAndLock();
        when(admin.subscription(tenantId, row.getId())).thenReturn(Optional.of(row));

        assertThrows(OperationConflictException.class, () -> service.endPlan(principal, tenantId, row.getId(), reason("lama")));

        verifyNoInteractions(audit);
    }

    @Test
    void topUpAiAddsBonusQuestionsToTheTenantsCurrentMonthAndAudits() {
        PlatformAiTopupRequest request = new PlatformAiTopupRequest();
        request.setQuestions(200);
        request.setReason("kompensasi");
        allowAndLock();
        LocalDate today = LocalDate.now(ZoneId.of(tenant.getTimeZone()));

        service.topUpAi(principal, tenantId, request);

        verify(aiUsage).addBonusQuestions(tenantId, today.withDayOfMonth(1), today, 200);
        assertAudit(AuditLogEntity.Action.PLATFORM_AI_TOPUP_GRANTED, "kompensasi");
    }

    @Test
    void suspendMovesAnActiveMerchantToSuspendedAndAudits() {
        allowAndLock();

        service.suspend(principal, tenantId, reason("pelanggaran"));

        assertEquals(TenantEntity.SUSPENDED, tenant.getStatus());
        assertAudit(AuditLogEntity.Action.PLATFORM_TENANT_SUSPENDED, "pelanggaran");
    }

    @Test
    void suspendRefusesAMerchantThatIsNotActive() {
        tenant.suspend();
        allowAndLock();

        assertThrows(OperationConflictException.class, () -> service.suspend(principal, tenantId, reason("lagi")));

        verifyNoInteractions(audit);
    }

    @Test
    void reactivateMovesASuspendedMerchantBackToActiveAndAudits() {
        tenant.suspend();
        allowAndLock();

        service.reactivate(principal, tenantId, reason("sudah beres"));

        assertEquals(StatusConstants.ACTIVE, tenant.getStatus());
        assertAudit(AuditLogEntity.Action.PLATFORM_TENANT_REACTIVATED, "sudah beres");
    }

    @Test
    void reactivateRefusesAMerchantThatIsNotSuspended() {
        allowAndLock();

        assertThrows(OperationConflictException.class, () -> service.reactivate(principal, tenantId, reason("salah")));

        verifyNoInteractions(audit);
    }

    @Test
    void markPaidSettlesWithTheTransferReferenceAdminAndReason() {
        PlatformMarkPaidRequest request = new PlatformMarkPaidRequest();
        request.setTransferReference("  TRF-001 ");
        request.setReason("  transfer BCA diterima ");
        PlatformInvoiceEntity invoice = invoice();
        when(guard.require(principal)).thenReturn(actor);
        when(admin.invoice(invoiceId)).thenReturn(Optional.of(invoice));
        when(settlement.settle(invoiceId, "TRF-001", actor.getId(), "transfer BCA diterima")).thenReturn(invoice);

        PlatformInvoiceRowResponse row = service.markPaid(principal, invoiceId, request);

        assertEquals(tenantId, row.getTenantId());
        assertEquals("INV-1", row.getInvoiceNumber());
    }

    @Test
    void cancelInvoiceDelegatesToTheBillingServiceWithTheInvoiceTenantAndAdmin() {
        PlatformInvoiceEntity invoice = invoice();
        when(guard.require(principal)).thenReturn(actor);
        when(admin.invoice(invoiceId)).thenReturn(Optional.of(invoice));

        service.cancelInvoice(principal, invoiceId, reason("salah tagih"));

        verify(billingService).cancel(tenantId, invoiceId, "salah tagih", actor.getId());
    }

    @Test
    void aRevokedAdminIsStoppedByTheGuardOnEveryWriteBeforeAnythingChanges() {
        when(guard.require(principal)).thenThrow(new ResourceNotFoundException());
        PlatformMarkPaidRequest markPaid = new PlatformMarkPaidRequest();
        markPaid.setTransferReference("TRF");
        markPaid.setReason("alasan");

        assertAll(
                () -> assertThrows(ResourceNotFoundException.class, () -> service.grantPlan(principal, tenantId, new PlatformGrantPlanRequest())),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.endPlan(principal, tenantId, UUID.randomUUID(), reason("x"))),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.topUpAi(principal, tenantId, new PlatformAiTopupRequest())),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.suspend(principal, tenantId, reason("x"))),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.reactivate(principal, tenantId, reason("x"))),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.markPaid(principal, invoiceId, markPaid)),
                () -> assertThrows(ResourceNotFoundException.class, () -> service.cancelInvoice(principal, invoiceId, reason("x"))));
        verifyNoInteractions(orders, admin, subscriptions, audit, aiUsage, settlement, billingService);
    }

    private void allowAndLock() {
        when(guard.require(principal)).thenReturn(actor);
        when(admin.tenant(tenantId)).thenReturn(Optional.of(tenant));
    }

    private void assertAudit(AuditLogEntity.Action action, String reason) {
        ArgumentCaptor<AuditLogEntity> log = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(audit).persist(log.capture());
        assertEquals(action.name(), log.getValue().getAction());
        assertSame(actor, log.getValue().getActor());
        assertSame(tenant, log.getValue().getTenant());
        assertEquals(reason, log.getValue().getReason());
        verify(orders).lockTenant(tenantId);
    }

    private SubscriptionEntity paid(Instant startsAt, Instant endsAt) {
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, PlanCode.PRO, 0);
        row.activate(startsAt, endsAt);
        ReflectionTestUtils.setField(row, "id", UUID.randomUUID());
        return row;
    }

    private PlatformInvoiceEntity invoice() {
        PlatformInvoiceEntity invoice = PlatformInvoiceEntity.issue(tenant, null, "INV-1", InvoiceKind.SUBSCRIPTION, "PRO",
                BillingCycle.MONTHLY, 1, 0, 0, BigDecimal.valueOf(59000), Instant.now());
        ReflectionTestUtils.setField(invoice, "id", invoiceId);
        return invoice;
    }

    private PlatformReasonRequest reason(String text) {
        PlatformReasonRequest request = new PlatformReasonRequest();
        request.setReason(text);
        return request;
    }
}
