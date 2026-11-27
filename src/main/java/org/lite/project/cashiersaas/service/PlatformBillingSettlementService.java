package org.lite.project.cashiersaas.service;

import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformPaymentAttemptEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.InvoiceKind;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.OperationConflictException;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.lite.project.cashiersaas.repository.AiUsageRepository;
import org.lite.project.cashiersaas.repository.AuditLogRepository;
import org.lite.project.cashiersaas.repository.FnbOrderRepository;
import org.lite.project.cashiersaas.repository.PlatformBillingRepository;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Applies the outcome of a payment: the single entry point a real gateway webhook or an operator (manual transfer) calls.
 * Conflicts do not roll back, so a lazy expiry decided inside a refused call is still saved.
 */
@Service
@RequiredArgsConstructor
public class PlatformBillingSettlementService {
    public static final String TOPUP_PREMIUM_REQUIRED = "Top-up tersedia untuk paket Premium berbayar.";
    private static final String OPEN_ENDED_MESSAGE = "Paket aktif tanpa batas waktu, hubungi admin.";

    private final FnbOrderRepository orders;
    private final PlatformBillingRepository billing;
    private final SubscriptionRepository subscriptions;
    private final AiUsageRepository aiUsage;
    private final AuditLogRepository audit;
    private final PaymentGateway gateway;

    /**
     * Marks the invoice paid and activates what it bought. {@code providerPaymentRef} null keeps the attempt's own reference (mock);
     * a second call with the same reference on a paid invoice returns it unchanged. {@code reason} (operator-entered, may be null)
     * goes to the audit log.
     */
    @Transactional(noRollbackFor = OperationConflictException.class)
    public PlatformInvoiceEntity settle(UUID invoiceId, String providerPaymentRef, UUID actorUserId, String reason) {
        PlatformInvoiceEntity invoice = lockAndLoad(invoiceId);
        Instant now = Instant.now();
        if (PlatformInvoiceEntity.PAID.equals(invoice.getStatus())) {
            PlatformPaymentAttemptEntity latest = billing.latestAttempts(List.of(invoiceId)).get(invoiceId);
            if (providerPaymentRef != null && latest != null && providerPaymentRef.equals(latest.getProviderPaymentRef())) {
                return invoice;
            }
            throw new OperationConflictException("Tagihan sudah dibayar.");
        }
        requirePayable(invoice, now);
        TenantEntity tenant = invoice.getTenant();
        if (invoice.getKind() == InvoiceKind.SUBSCRIPTION) {
            SubscriptionEntity row = invoice.getSubscription();
            if (!row.isPending()) {
                throw new OperationConflictException("Tagihan tidak dapat dibayar.");
            }
            Instant startsAt = subscriptionStart(tenant.getId(), row.getPlan(), now);
            row.activate(startsAt, periodEnd(tenant, startsAt, invoice.getPeriodMonths()));
        } else {
            requirePaidPremium(tenant.getId(), now);
            LocalDate today = LocalDate.now(ZoneId.of(tenant.getTimeZone()));
            aiUsage.addBonusQuestions(tenant.getId(), today.withDayOfMonth(1), today, invoice.getAiQuestions());
        }
        PlatformPaymentAttemptEntity attempt = gateway.pendingOrNewAttempt(invoice);
        attempt.succeed(providerPaymentRef);
        invoice.markPaid(now);
        recordGatewayEvent(attempt, PlatformPaymentAttemptEntity.SUCCEEDED, now);
        audit.persist(AuditLogEntity.recordAction(tenant, null, billing.actor(actorUserId), AuditLogEntity.Action.PLATFORM_INVOICE_PAID,
                invoice.getId(), reason, "invoice=" + invoice.getInvoiceNumber() + " amount=" + invoice.getAmountDue().toPlainString()
                        + " provider=" + attempt.getProviderCode()));
        return invoice;
    }

    /** A failed payment attempt: the invoice stays ISSUED and can be paid again until it is due. */
    @Transactional(noRollbackFor = OperationConflictException.class)
    public PlatformInvoiceEntity fail(UUID invoiceId, UUID actorUserId) {
        PlatformInvoiceEntity invoice = lockAndLoad(invoiceId);
        Instant now = Instant.now();
        requirePayable(invoice, now);
        PlatformPaymentAttemptEntity attempt = gateway.pendingOrNewAttempt(invoice);
        attempt.fail();
        recordGatewayEvent(attempt, PlatformPaymentAttemptEntity.FAILED, now);
        audit.persist(AuditLogEntity.recordAction(invoice.getTenant(), null, billing.actor(actorUserId),
                AuditLogEntity.Action.PLATFORM_PAYMENT_FAILED, invoice.getId(), null,
                "invoice=" + invoice.getInvoiceNumber() + " provider=" + attempt.getProviderCode()));
        return invoice;
    }

    /** Start of a new period: now, or right after the last paid period of the same or a higher plan. */
    public Instant subscriptionStart(UUID tenantId, PlanCode plan, Instant now) {
        List<SubscriptionEntity> rows = subscriptions.findLivePaid(tenantId, now).stream()
                .filter(row -> row.getPlan().compareTo(plan) >= 0).toList();
        if (rows.stream().anyMatch(row -> row.getEndsAt() == null)) {
            throw new OperationConflictException(OPEN_ENDED_MESSAGE);
        }
        return rows.stream().map(SubscriptionEntity::getEndsAt).max(Instant::compareTo).orElse(now);
    }

    /** Calendar months in the tenant's zone. */
    public Instant periodEnd(TenantEntity tenant, Instant startsAt, int months) {
        return startsAt.atZone(ZoneId.of(tenant.getTimeZone())).plusMonths(months).toInstant();
    }

    /** The paid PREMIUM row running now (a trial is no row); the AI top-up is linked to it. */
    public SubscriptionEntity requirePaidPremium(UUID tenantId, Instant now) {
        return subscriptions.findActiveAt(tenantId, now).stream().filter(row -> row.getPlan() == PlanCode.PREMIUM).findFirst()
                .orElseThrow(() -> new OperationConflictException(TOPUP_PREMIUM_REQUIRED));
    }

    /** Lazy expiry of every due ISSUED invoice of the tenant; takes the tenant lock only when there is something to expire. */
    void expireDue(UUID tenantId, Instant now) {
        if (billing.dueIssued(tenantId, now).isEmpty()) {
            return;
        }
        orders.lockTenant(tenantId);
        for (PlatformInvoiceEntity invoice : billing.dueIssued(tenantId, now)) {
            billing.refresh(invoice);
            expireIfDue(invoice, now);
        }
    }

    /** Turns a due ISSUED invoice into EXPIRED and frees what it held. Returns whether it expired. */
    boolean expireIfDue(PlatformInvoiceEntity invoice, Instant now) {
        if (!invoice.isIssued() || !invoice.isPastDue(now)) {
            return false;
        }
        invoice.expire();
        releaseHold(invoice);
        return true;
    }

    /** Cancels the open attempts and the PENDING subscription row an unpaid invoice was holding. */
    void releaseHold(PlatformInvoiceEntity invoice) {
        billing.pendingAttempts(invoice.getId()).forEach(PlatformPaymentAttemptEntity::cancel);
        SubscriptionEntity row = invoice.getSubscription();
        if (row.isPending()) {
            row.cancel();
        }
    }

    private PlatformInvoiceEntity lockAndLoad(UUID invoiceId) {
        PlatformInvoiceEntity invoice = billing.invoiceById(invoiceId).orElseThrow(ResourceNotFoundException::new);
        orders.lockTenant(invoice.getTenant().getId());
        billing.refresh(invoice);
        return invoice;
    }

    private void requirePayable(PlatformInvoiceEntity invoice, Instant now) {
        if (!invoice.isIssued()) {
            throw new OperationConflictException("Tagihan tidak dapat dibayar.");
        }
        if (expireIfDue(invoice, now)) {
            throw new OperationConflictException("Tagihan sudah kedaluwarsa.");
        }
    }

    /** Only simulated payments produce a gateway event; a manual transfer has no provider callback. */
    private void recordGatewayEvent(PlatformPaymentAttemptEntity attempt, String status, Instant now) {
        if (PaymentGateway.MOCK_PROVIDER.equals(attempt.getProviderCode())) {
            gateway.recordEvent(attempt, status, now);
        }
    }
}
