package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.lite.project.cashiersaas.entity.shared.billing.PlatformInvoiceEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Cross-tenant reads for the platform (SUPERADMIN) panel. Callers must have passed the platform access guard. */
@Repository
@RequiredArgsConstructor
public class PlatformAdminRepository {
    private static final String LIKE_ESCAPE = "!";
    private static final String TRIAL = "(t.premiumTrialStartedAt is not null and t.premiumTrialStartedAt > :trialCutoff)";
    private static final String ACTIVE_ROW = "exists (select 1 from SubscriptionEntity s where s.tenant.id = t.id and s.status = 'ACTIVE' "
            + "and s.startsAt <= :now and (s.endsAt is null or s.endsAt > :now) and s.planCode %s)";

    private final EntityManager entityManager;

    public Optional<TenantEntity> tenant(UUID tenantId) {
        return Optional.ofNullable(entityManager.find(TenantEntity.class, tenantId));
    }

    public Optional<PlatformInvoiceEntity> invoice(UUID invoiceId) {
        return Optional.ofNullable(entityManager.find(PlatformInvoiceEntity.class, invoiceId));
    }

    public Optional<SubscriptionEntity> subscription(UUID tenantId, UUID subscriptionId) {
        return entityManager.createQuery("select s from SubscriptionEntity s where s.id = :id and s.tenant.id = :tenantId",
                        SubscriptionEntity.class)
                .setParameter("id", subscriptionId).setParameter("tenantId", tenantId).getResultStream().findFirst();
    }

    /** {@code plan} is FREETIER, PRO, PREMIUM or TRIAL (a running trial without a paid Premium row); a tenant matches exactly one. */
    public long countTenants(String q, String plan, String status, Instant now, Instant trialCutoff) {
        Map<String, Object> params = new HashMap<>();
        String where = tenantWhere(q, plan, status, now, trialCutoff, params);
        return bind(entityManager.createQuery("select count(t) from TenantEntity t left join t.createdByUser o" + where, Long.class), params)
                .getSingleResult();
    }

    public List<TenantEntity> tenantPage(String q, String plan, String status, Instant now, Instant trialCutoff, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = tenantWhere(q, plan, status, now, trialCutoff, params);
        return bind(entityManager.createQuery("select t from TenantEntity t left join fetch t.createdByUser o" + where
                        + " order by t.createdAt desc, t.id desc", TenantEntity.class), params)
                .setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    /** Active rows (FREETIER base included) of every given tenant at {@code now}, in one query. */
    public Map<UUID, List<SubscriptionEntity>> activeRows(List<UUID> tenantIds, Instant now) {
        Map<UUID, List<SubscriptionEntity>> rows = new HashMap<>();
        if (tenantIds.isEmpty()) {
            return rows;
        }
        for (SubscriptionEntity row : entityManager.createQuery("select s from SubscriptionEntity s where s.tenant.id in :ids "
                        + "and s.status = 'ACTIVE' and s.startsAt <= :now and (s.endsAt is null or s.endsAt > :now)", SubscriptionEntity.class)
                .setParameter("ids", tenantIds).setParameter("now", now).getResultList()) {
            rows.computeIfAbsent(row.getTenant().getId(), key -> new java.util.ArrayList<>()).add(row);
        }
        return rows;
    }

    public Map<UUID, Integer> activeOutletCounts(List<UUID> tenantIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (tenantIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : entityManager.createQuery("select o.tenant.id, count(o) from OutletEntity o where o.tenant.id in :ids "
                        + "and o.active = true group by o.tenant.id", Object[].class)
                .setParameter("ids", tenantIds).getResultList()) {
            counts.put((UUID) row[0], ((Long) row[1]).intValue());
        }
        return counts;
    }

    /** Newest first, capped. */
    public List<SubscriptionEntity> subscriptions(UUID tenantId, int max) {
        return entityManager.createQuery("select s from SubscriptionEntity s where s.tenant.id = :tenantId "
                        + "order by s.createdAt desc, s.id desc", SubscriptionEntity.class)
                .setParameter("tenantId", tenantId).setMaxResults(max).getResultList();
    }

    public long countOpenInvoices(Instant now) {
        return entityManager.createQuery("select count(i) from PlatformInvoiceEntity i where i.status = :issued and i.dueAt > :now", Long.class)
                .setParameter("issued", PlatformInvoiceEntity.ISSUED).setParameter("now", now).getSingleResult();
    }

    public Revenue paidBetween(Instant from, Instant to) {
        Object[] row = entityManager.createQuery("select count(i), sum(i.amountDue) from PlatformInvoiceEntity i "
                        + "where i.status = :paid and i.paidAt >= :from and i.paidAt < :to", Object[].class)
                .setParameter("paid", PlatformInvoiceEntity.PAID).setParameter("from", from).setParameter("to", to).getSingleResult();
        return new Revenue((Long) row[0], row[1] == null ? BigDecimal.ZERO : (BigDecimal) row[1]);
    }

    /** Tenants whose paid plan ends within (now, until] with no later paid row queued behind it. */
    public long countExpiring(Instant now, Instant until) {
        return entityManager.createQuery("select count(distinct s.tenant.id) from SubscriptionEntity s where s.status = 'ACTIVE' "
                        + "and s.planCode <> 'FREETIER' and s.startsAt <= :now and s.endsAt > :now and s.endsAt <= :until "
                        + "and not exists (select 1 from SubscriptionEntity n where n.tenant.id = s.tenant.id and n.status = 'ACTIVE' "
                        + "and n.planCode <> 'FREETIER' and (n.endsAt is null or n.endsAt > :until))", Long.class)
                .setParameter("now", now).setParameter("until", until).getSingleResult();
    }

    public long countInvoices(String status, String q) {
        Map<String, Object> params = new HashMap<>();
        String where = invoiceWhere(status, q, params);
        return bind(entityManager.createQuery("select count(i) from PlatformInvoiceEntity i join i.tenant t" + where, Long.class), params)
                .getSingleResult();
    }

    public List<PlatformInvoiceEntity> invoicePage(String status, String q, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = invoiceWhere(status, q, params);
        return bind(entityManager.createQuery("select i from PlatformInvoiceEntity i join fetch i.tenant t join fetch i.subscription"
                        + where + " order by i.issuedAt desc, i.id desc", PlatformInvoiceEntity.class), params)
                .setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    public long countAudit(UUID tenantId) {
        Map<String, Object> params = new HashMap<>();
        String where = auditWhere(tenantId, params);
        return bind(entityManager.createQuery("select count(l) from AuditLogEntity l" + where, Long.class), params).getSingleResult();
    }

    public List<AuditLogEntity> auditPage(UUID tenantId, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = auditWhere(tenantId, params);
        return bind(entityManager.createQuery("select l from AuditLogEntity l join fetch l.tenant left join fetch l.actor" + where
                        + " order by l.createdAt desc, l.id desc", AuditLogEntity.class), params)
                .setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    /** Lower-cases and escapes a search term for a LIKE with escape '!'. */
    public static String likePattern(String term) {
        String escaped = term.trim().toLowerCase(java.util.Locale.ROOT).replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%").replace("_", LIKE_ESCAPE + "_");
        return "%" + escaped + "%";
    }

    private String tenantWhere(String q, String plan, String status, Instant now, Instant trialCutoff, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (q != null) {
            where.append(" and (lower(t.name) like :q escape '").append(LIKE_ESCAPE).append("' or lower(o.email) like :q escape '")
                    .append(LIKE_ESCAPE).append("')");
            params.put("q", likePattern(q));
        }
        if (status != null) {
            where.append(" and t.status = :status");
            params.put("status", status);
        }
        if (plan != null) {
            where.append(" and ").append(planPredicate(plan));
            params.put("now", now);
            // Hibernate rejects a parameter the query does not use; the PREMIUM predicate has no trial clause.
            if (!"PREMIUM".equals(plan)) {
                params.put("trialCutoff", trialCutoff);
            }
        }
        return where.toString();
    }

    private String planPredicate(String plan) {
        return switch (plan) {
            case "PREMIUM" -> ACTIVE_ROW.formatted("= 'PREMIUM'");
            case "TRIAL" -> TRIAL + " and not " + ACTIVE_ROW.formatted("= 'PREMIUM'");
            case "PRO" -> "not " + TRIAL + " and " + ACTIVE_ROW.formatted("= 'PRO'") + " and not " + ACTIVE_ROW.formatted("= 'PREMIUM'");
            default -> "not " + TRIAL + " and not " + ACTIVE_ROW.formatted("<> 'FREETIER'");
        };
    }

    private String invoiceWhere(String status, String q, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        if (status != null) {
            where.append(" and i.status = :status");
            params.put("status", status);
        }
        if (q != null) {
            where.append(" and (lower(i.invoiceNumber) like :q escape '").append(LIKE_ESCAPE).append("' or lower(t.name) like :q escape '")
                    .append(LIKE_ESCAPE).append("')");
            params.put("q", likePattern(q));
        }
        return where.toString();
    }

    private String auditWhere(UUID tenantId, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" where l.action like 'PLATFORM").append(LIKE_ESCAPE).append("_%' escape '")
                .append(LIKE_ESCAPE).append("'");
        if (tenantId != null) {
            where.append(" and l.tenant.id = :tenantId");
            params.put("tenantId", tenantId);
        }
        return where.toString();
    }

    private <T> TypedQuery<T> bind(TypedQuery<T> query, Map<String, Object> params) {
        params.forEach(query::setParameter);
        return query;
    }

    @Getter
    @RequiredArgsConstructor
    public static class Revenue {
        private final long count;
        private final BigDecimal amount;
    }
}
