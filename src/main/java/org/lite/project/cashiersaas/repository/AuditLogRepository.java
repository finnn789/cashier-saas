package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AuditLogRepository {
    private final EntityManager entityManager;

    public void persist(AuditLogEntity log) {
        entityManager.persist(log);
    }

    /** Audit rows of one action created in [from, to) at the outlet, counted per acting staff user. */
    public Map<UUID, Long> countByActor(UUID tenantId, UUID outletId, AuditLogEntity.Action action, Instant from, Instant to) {
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : entityManager.createQuery("select l.actor.id, count(l) from AuditLogEntity l where l.tenant.id = :tenantId "
                        + "and l.outlet.id = :outletId and l.action = :action and l.actor is not null "
                        + "and l.createdAt >= :from and l.createdAt < :to group by l.actor.id", Object[].class)
                .setParameter("tenantId", tenantId).setParameter("outletId", outletId).setParameter("action", action.name())
                .setParameter("from", from).setParameter("to", to).getResultList())
            counts.put((UUID) row[0], (Long) row[1]);
        return counts;
    }
}
