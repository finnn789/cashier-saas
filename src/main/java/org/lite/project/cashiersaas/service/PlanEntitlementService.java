package org.lite.project.cashiersaas.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.PlanUpgradeRequiredException;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanEntitlementService {
    private static final long TRIAL_PAID_ORDER_THRESHOLD = 50L;

    private final SubscriptionRepository subscriptions;
    private final EntityManager entityManager;

    /** Highest active plan of the tenant; a running Premium trial counts as PREMIUM. */
    public PlanCode effectivePlan(UUID tenantId, Instant now) {
        TenantEntity tenant = entityManager.find(TenantEntity.class, tenantId);
        if (tenant != null && tenant.isPremiumTrialActiveAt(now)) {
            return PlanCode.PREMIUM;
        }
        return subscriptions.findActiveAt(tenantId, now).stream().map(SubscriptionEntity::getPlan)
                .max(Comparator.naturalOrder()).orElse(PlanCode.FREETIER);
    }

    public void require(UUID tenantId, PlanCode minimum) {
        if (effectivePlan(tenantId, Instant.now()).compareTo(minimum) < 0) {
            throw new PlanUpgradeRequiredException(minimum);
        }
    }

    /** Starts the one-time Premium trial once a FREETIER tenant has 50 paid orders. Call inside the payment transaction. */
    public void startTrialIfEligible(UUID tenantId, Instant now) {
        TenantEntity tenant = entityManager.find(TenantEntity.class, tenantId);
        if (tenant == null || tenant.getPremiumTrialStartedAt() != null
                || effectivePlan(tenantId, now) != PlanCode.FREETIER) {
            return;
        }
        long paidOrders = entityManager.createQuery("select count(o) from SalesOrderEntity o where o.tenant.id = :tenantId "
                        + "and o.paymentStatus = :paid and o.status <> :cancelled", Long.class)
                .setParameter("tenantId", tenantId).setParameter("paid", PaymentStatus.PAID)
                .setParameter("cancelled", OrderStatus.CANCELLED).getSingleResult();
        if (paidOrders >= TRIAL_PAID_ORDER_THRESHOLD) {
            tenant.startPremiumTrial(now);
        }
    }
}
