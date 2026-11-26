package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.component.PlatformPrincipal;
import org.lite.project.cashiersaas.dto.PlatformAdminMapper;
import org.lite.project.cashiersaas.dto.request.platform.PlatformAiTopupRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformGrantPlanRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformMarkPaidRequest;
import org.lite.project.cashiersaas.dto.request.platform.PlatformReasonRequest;
import org.lite.project.cashiersaas.dto.response.platform.PlatformInvoiceRowResponse;
import org.lite.project.cashiersaas.dto.response.platform.PlatformTenantDetailResponse;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
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
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Write side of the platform (SUPERADMIN) panel. Every method passes the access guard, takes the tenant lock like the owner billing
 * flows, and writes an audit row on the target tenant with the admin as actor and the operator's reason.
 */
@Service
@RequiredArgsConstructor
public class PlatformTenantAdminService {
    private final PlatformAccessGuard guard;
    private final PlatformDashboardService dashboard;
    private final PlatformAdminRepository admin;
    private final PlatformBillingRepository billing;
    private final SubscriptionRepository subscriptions;
    private final FnbOrderRepository orders;
    private final AuditLogRepository audit;
    private final AiUsageRepository aiUsage;
    private final PlatformBillingSettlementService settlement;
    private final PlatformBillingService billingService;
    private final MockPaymentGateway gateway;

    /** A complimentary or corrective paid row (no invoice) that starts by the same rule as a paid checkout. */
    @Transactional
    public PlatformTenantDetailResponse grantPlan(PlatformPrincipal principal, UUID tenantId, PlatformGrantPlanRequest request) {
        StaffUserEntity actor = guard.require(principal);
        PlanCode plan = request.getPlanCode();
        if (plan == PlanCode.FREETIER) {
            throw new InvalidOperationException("Paket wajib dipilih: Pro atau Premium.");
        }
        TenantEntity tenant = lockedTenant(tenantId);
        int extra = request.getExtraOutlets() == null ? 0 : request.getExtraOutlets();
        Instant startsAt = settlement.subscriptionStart(tenantId, plan, Instant.now());
        Instant endsAt = settlement.periodEnd(tenant, startsAt, request.getMonths());
        SubscriptionEntity row = SubscriptionEntity.pending(tenant, plan, extra);
        row.activate(startsAt, endsAt);
        subscriptions.persist(row);
        audit.persist(AuditLogEntity.recordAction(tenant, null, actor, AuditLogEntity.Action.PLATFORM_PLAN_GRANTED, row.getId(),
                request.getReason().trim(), "plan=" + plan + " months=" + request.getMonths() + " extraOutlets=" + extra
                        + " startsAt=" + startsAt + " endsAt=" + endsAt));
        return dashboard.tenant(principal, tenantId);
    }

    /** Ends a running paid row now, or cancels one that has not started; the FREETIER base row is never ended. */
    @Transactional
    public PlatformTenantDetailResponse endPlan(PlatformPrincipal principal, UUID tenantId, UUID subscriptionId,
                                                PlatformReasonRequest request) {
        StaffUserEntity actor = guard.require(principal);
        TenantEntity tenant = lockedTenant(tenantId);
        SubscriptionEntity row = admin.subscription(tenantId, subscriptionId).orElseThrow(ResourceNotFoundException::new);
        if (row.getPlan() == PlanCode.FREETIER) {
            throw new OperationConflictException("Paket Freetier tidak dapat diakhiri.");
        }
        Instant now = Instant.now();
        if (row.isActiveAt(now)) {
            row.activate(row.getStartsAt(), now);
        } else if (StatusConstants.ACTIVE.equals(row.getStatus()) && row.getStartsAt() != null && row.getStartsAt().isAfter(now)) {
            row.cancel();
        } else {
            throw new OperationConflictException("Paket tidak sedang aktif.");
        }
        audit.persist(AuditLogEntity.recordAction(tenant, null, actor, AuditLogEntity.Action.PLATFORM_PLAN_ENDED, row.getId(),
                request.getReason().trim(), "plan=" + row.getPlan() + " endedAt=" + now));
        return dashboard.tenant(principal, tenantId);
    }

    /** Bonus questions on the tenant's current month; it does not require a Premium row (complimentary or correction). */
    @Transactional
    public PlatformTenantDetailResponse topUpAi(PlatformPrincipal principal, UUID tenantId, PlatformAiTopupRequest request) {
        StaffUserEntity actor = guard.require(principal);
        TenantEntity tenant = lockedTenant(tenantId);
        LocalDate today = LocalDate.now(ZoneId.of(tenant.getTimeZone()));
        aiUsage.addBonusQuestions(tenantId, today.withDayOfMonth(1), today, request.getQuestions());
        audit.persist(AuditLogEntity.recordAction(tenant, null, actor, AuditLogEntity.Action.PLATFORM_AI_TOPUP_GRANTED, tenantId,
                request.getReason().trim(), "questions=" + request.getQuestions() + " month=" + today.withDayOfMonth(1)));
        return dashboard.tenant(principal, tenantId);
    }

    /** No data is deleted; owner and cashier sessions stop at their next refresh and new logins are refused. */
    @Transactional
    public PlatformTenantDetailResponse suspend(PlatformPrincipal principal, UUID tenantId, PlatformReasonRequest request) {
        StaffUserEntity actor = guard.require(principal);
        TenantEntity tenant = lockedTenant(tenantId);
        if (!tenant.isActive()) {
            throw new OperationConflictException("Hanya merchant aktif yang dapat ditangguhkan.");
        }
        tenant.suspend();
        audit.persist(AuditLogEntity.recordAction(tenant, null, actor, AuditLogEntity.Action.PLATFORM_TENANT_SUSPENDED, tenantId,
                request.getReason().trim(), "status=" + TenantEntity.SUSPENDED));
        return dashboard.tenant(principal, tenantId);
    }

    @Transactional
    public PlatformTenantDetailResponse reactivate(PlatformPrincipal principal, UUID tenantId, PlatformReasonRequest request) {
        StaffUserEntity actor = guard.require(principal);
        TenantEntity tenant = lockedTenant(tenantId);
        if (!tenant.isSuspended()) {
            throw new OperationConflictException("Merchant tidak sedang ditangguhkan.");
        }
        tenant.reactivate();
        audit.persist(AuditLogEntity.recordAction(tenant, null, actor, AuditLogEntity.Action.PLATFORM_TENANT_REACTIVATED, tenantId,
                request.getReason().trim(), "status=" + StatusConstants.ACTIVE));
        return dashboard.tenant(principal, tenantId);
    }

    /** Manual transfer received: settles the invoice with the transfer reference and the operator's reason (audited by the settlement). */
    @Transactional(noRollbackFor = OperationConflictException.class)
    public PlatformInvoiceRowResponse markPaid(PlatformPrincipal principal, UUID invoiceId, PlatformMarkPaidRequest request) {
        StaffUserEntity actor = guard.require(principal);
        admin.invoice(invoiceId).orElseThrow(ResourceNotFoundException::new);
        PlatformInvoiceEntity invoice = settlement.settle(invoiceId, request.getTransferReference().trim(), actor.getId(),
                request.getReason().trim());
        return row(invoice);
    }

    @Transactional(noRollbackFor = OperationConflictException.class)
    public PlatformInvoiceRowResponse cancelInvoice(PlatformPrincipal principal, UUID invoiceId, PlatformReasonRequest request) {
        StaffUserEntity actor = guard.require(principal);
        PlatformInvoiceEntity invoice = admin.invoice(invoiceId).orElseThrow(ResourceNotFoundException::new);
        billingService.cancel(invoice.getTenant().getId(), invoiceId, request.getReason(), actor.getId());
        return row(invoice);
    }

    private PlatformInvoiceRowResponse row(PlatformInvoiceEntity invoice) {
        return PlatformAdminMapper.toInvoiceRow(invoice, billing.latestAttempts(List.of(invoice.getId())).get(invoice.getId()),
                gateway.isEnabled());
    }

    /** 404 for an unknown tenant; the lock serializes with the owner's checkout and payment flows. */
    private TenantEntity lockedTenant(UUID tenantId) {
        orders.lockTenant(tenantId);
        return admin.tenant(tenantId).orElseThrow(ResourceNotFoundException::new);
    }
}
