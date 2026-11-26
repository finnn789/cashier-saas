package org.lite.project.cashiersaas.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.exception.runtime.OutletOverAllowanceException;
import org.lite.project.cashiersaas.exception.runtime.PlanUpgradeRequiredException;
import org.lite.project.cashiersaas.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlanEntitlementService {
    private static final long TRIAL_PAID_ORDER_THRESHOLD = 50L;
    public static final String OUTLET_ADDON_REQUIRED_MESSAGE = "Outlet tambahan memerlukan add-on outlet (Rp49.000/outlet/bulan).";
    public static final String OUTLET_OVER_ALLOWANCE_MESSAGE = "Outlet ini melebihi kuota outlet paket Anda. "
            + "Tambahkan add-on outlet (Rp49.000/outlet/bulan) atau nonaktifkan outlet lain.";

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

    /** 1 on FREETIER or a trial without a paid row; otherwise 1 plus the add-on outlets of every active paid row. */
    public int allowedOutletCount(UUID tenantId, Instant now) {
        return 1 + subscriptions.findActiveAt(tenantId, now).stream()
                .filter(row -> row.getPlan() != PlanCode.FREETIER).mapToInt(SubscriptionEntity::getExtraOutlets).sum();
    }

    /** Active outlets oldest first, cut to the allowance: the rest are over allowance and have no operational access. */
    public List<UUID> usableOutletIds(UUID tenantId, Instant now) {
        List<UUID> active = activeOutletIds(tenantId);
        return active.subList(0, Math.min(active.size(), allowedOutletCount(tenantId, now)));
    }

    /** 402 when the outlet is active but over the allowance. One active outlet is always usable, so it skips the subscription query. */
    public void requireOutletUsable(UUID tenantId, UUID outletId, Instant now) {
        List<UUID> active = activeOutletIds(tenantId);
        int allowed = active.size() > 1 ? allowedOutletCount(tenantId, now) : 1;
        if (active.indexOf(outletId) >= allowed) {
            throw new OutletOverAllowanceException(active.subList(0, allowed));
        }
    }

    /** Refuses one more active outlet when {@code activeOutlets} already fills the allowance (create and reactivate). */
    public void requireOutletCapacity(UUID tenantId, long activeOutlets, Instant now) {
        if (activeOutlets >= allowedOutletCount(tenantId, now)) {
            throw effectivePlan(tenantId, now) == PlanCode.FREETIER
                    ? new PlanUpgradeRequiredException(PlanCode.PRO)
                    : new PlanUpgradeRequiredException(OUTLET_ADDON_REQUIRED_MESSAGE);
        }
    }

    public int activeOutletCount(UUID tenantId) {
        return activeOutletIds(tenantId).size();
    }

    private List<UUID> activeOutletIds(UUID tenantId) {
        return entityManager.createQuery("select o.id from OutletEntity o where o.tenant.id = :tenantId and o.active = true "
                + "order by o.createdAt, o.id", UUID.class).setParameter("tenantId", tenantId).getResultList();
    }
}
