package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SubscriptionRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    /** Every subscription row of the tenant that is active at {@code now}: the FREETIER base plus any paid row. */
    public List<SubscriptionEntity> findActiveAt(UUID tenantId, Instant now) {
        return criteriaBuilderFactory.create(entityManager, SubscriptionEntity.class, "subscription")
                .where("subscription.tenant.id").eq(tenantId)
                .where("subscription.status").eq(StatusConstants.ACTIVE)
                .where("subscription.startsAt").le(now)
                .whereOr()
                    .where("subscription.endsAt").isNull()
                    .where("subscription.endsAt").gt(now)
                .endOr()
                .getResultList();
    }

    public void persist(SubscriptionEntity subscription) {
        entityManager.persist(subscription);
    }
}
