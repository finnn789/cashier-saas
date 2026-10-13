package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MerchantAuditRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public List<AuditRow> page(UUID tenantId, UUID outletId, Instant start, Instant end, String action,
                               UUID actorId, String entityType, int page, int size) {
        return filteredQuery(tenantId, outletId, start, end, action, actorId, entityType)
                .selectNew(AuditRow.class)
                .with("audit.id")
                .with("audit.createdAt")
                .with("audit.action")
                .with("audit.entityType")
                .with("audit.entityId")
                .with("actor.id")
                .with("actor.fullName")
                .with("audit.reason")
                .with("audit.details")
                .end()
                .orderByDesc("audit.createdAt")
                .orderByDesc("audit.id")
                .setFirstResult(page * size)
                .setMaxResults(size + 1)
                .getResultList();
    }

    public long count(UUID tenantId, UUID outletId, Instant start, Instant end, String action,
                      UUID actorId, String entityType) {
        return filteredQuery(tenantId, outletId, start, end, action, actorId, entityType)
                .getCountQuery()
                .getSingleResult();
    }

    private CriteriaBuilder<AuditLogEntity> filteredQuery(UUID tenantId, UUID outletId, Instant start, Instant end,
                                                           String action, UUID actorId, String entityType) {
        CriteriaBuilder<AuditLogEntity> query = criteriaBuilderFactory.create(entityManager, AuditLogEntity.class, "audit")
                .leftJoin("audit.actor", "actor")
                .where("audit.tenant.id").eq(tenantId)
                .where("audit.outlet.id").eq(outletId)
                .where("audit.createdAt").ge(start)
                .where("audit.createdAt").lt(end);
        if (action != null) {
            query.where("audit.action").eq(action);
        }
        if (actorId != null) {
            query.where("audit.actor.id").eq(actorId);
        }
        if (entityType != null) {
            query.where("audit.entityType").eq(entityType);
        }
        return query;
    }

    public static class AuditRow {
        public final UUID id;
        public final Instant createdAt;
        public final String action;
        public final String entityType;
        public final UUID entityId;
        public final UUID actorId;
        public final String actorDisplayName;
        public final String reason;
        public final String details;

        public AuditRow(UUID id, Instant createdAt, String action, String entityType, UUID entityId, UUID actorId,
                        String actorDisplayName, String reason, String details) {
            this.id = id;
            this.createdAt = createdAt;
            this.action = action;
            this.entityType = entityType;
            this.entityId = entityId;
            this.actorId = actorId;
            this.actorDisplayName = actorDisplayName;
            this.reason = reason;
            this.details = details;
        }
    }
}
