package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SubscriptionRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<SubscriptionEntity> findActiveForTenant(UUID tenantId) {
        return criteriaBuilderFactory.create(entityManager, SubscriptionEntity.class, "subscription")
                .where("subscription.tenant.id").eq(tenantId)
                .where("subscription.status").eq(StatusConstants.ACTIVE)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public void persist(SubscriptionEntity subscription) {
        entityManager.persist(subscription);
    }
}
