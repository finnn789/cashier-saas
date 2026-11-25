package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.fnb.DailySummaryDispatchEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class DailySummaryDispatchRepository {
    private final EntityManager entityManager;

    /** Inserts the PENDING row unless the outlet already has one for the date; empty when it already existed. */
    public Optional<UUID> insertIfAbsent(UUID tenantId, UUID outletId, LocalDate businessDate,
                                         DailySummaryDispatchEntity.Trigger trigger) {
        List<?> inserted = entityManager.createNativeQuery("INSERT INTO daily_summary_dispatches "
                        + "(id, created_at, updated_at, version, tenant_id, outlet_id, business_date, trigger, status, attempts) "
                        + "VALUES (gen_random_uuid(), now(), now(), 0, :tenantId, :outletId, :businessDate, :trigger, 'PENDING', 0) "
                        + "ON CONFLICT (outlet_id, business_date) DO NOTHING RETURNING id")
                .setParameter("tenantId", tenantId).setParameter("outletId", outletId)
                .setParameter("businessDate", businessDate).setParameter("trigger", trigger.name())
                .getResultList();
        return inserted.isEmpty() ? Optional.empty() : Optional.of(UUID.fromString(inserted.get(0).toString()));
    }

    public Optional<DailySummaryDispatchEntity> byId(UUID id) {
        return Optional.ofNullable(entityManager.find(DailySummaryDispatchEntity.class, id));
    }

    /** PENDING/FAILED rows below the attempt cap whose last attempt (or creation, if never tried) is older than the cutoff. */
    public List<UUID> retryable(int maxAttempts, Instant olderThan, LocalDate minBusinessDate, int limit, Instant now) {
        return entityManager.createQuery("select d.id from DailySummaryDispatchEntity d "
                        + "where d.status in (:pending, :failed) and d.attempts < :maxAttempts "
                        + "and d.businessDate >= :minBusinessDate "
                        + "and coalesce(d.lastAttemptAt, d.createdAt) < :olderThan and " + premium("d.tenant")
                        + " order by d.createdAt", UUID.class)
                .setParameter("pending", DailySummaryDispatchEntity.Status.PENDING)
                .setParameter("failed", DailySummaryDispatchEntity.Status.FAILED)
                .setParameter("maxAttempts", maxAttempts).setParameter("olderThan", olderThan)
                .setParameter("minBusinessDate", minBusinessDate)
                .setParameter("now", now).setParameter("trialCutoff", now.minus(TenantEntity.PREMIUM_TRIAL_DURATION))
                .setMaxResults(limit).getResultList();
    }

    /** Effective PREMIUM at :now: an active PREMIUM subscription row or a running trial (same rule as PlanEntitlementService). */
    private static String premium(String tenant) {
        return "(exists (select 1 from SubscriptionEntity s where s.tenant = " + tenant + " and s.planCode = 'PREMIUM' "
                + "and s.status = 'ACTIVE' and s.startsAt <= :now and (s.endsAt is null or s.endsAt > :now)) "
                + "or " + tenant + ".premiumTrialStartedAt > :trialCutoff)";
    }

    public long countOpenShifts(UUID tenantId, UUID outletId) {
        return entityManager.createQuery("select count(s) from CashShiftEntity s "
                        + "where s.tenant.id = :tenantId and s.outlet.id = :outletId and s.status = :open", Long.class)
                .setParameter("tenantId", tenantId).setParameter("outletId", outletId)
                .setParameter("open", CashShiftEntity.Status.OPEN).getSingleResult();
    }

    /** Active outlets of active, effectively PREMIUM F&B tenants (the fallback sweep filters by local time in memory). */
    public List<OutletRef> activeFnbOutlets(Instant now) {
        return entityManager.createQuery("select o.id, o.tenant.id, o.timeZone from OutletEntity o "
                        + "where o.active = true and o.tenant.status = 'ACTIVE' "
                        + "and o.tenant.businessType.active = true and o.tenant.businessType.code = 'FNB' and "
                        + premium("o.tenant"), Object[].class)
                .setParameter("now", now).setParameter("trialCutoff", now.minus(TenantEntity.PREMIUM_TRIAL_DURATION))
                .getResultList().stream().map(row -> new OutletRef((UUID) row[1], (UUID) row[0], (String) row[2])).toList();
    }

    @Getter
    @RequiredArgsConstructor
    public static class OutletRef {
        private final UUID tenantId;
        private final UUID outletId;
        private final String timeZone;
    }
}
