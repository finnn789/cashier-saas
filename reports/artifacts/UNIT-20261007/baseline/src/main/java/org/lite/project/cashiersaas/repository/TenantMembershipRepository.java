package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TenantMembershipRepository {

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public void persist(TenantMembershipEntity membership) {
        entityManager.persist(membership);
    }

    public Optional<TenantMembershipEntity> findActive(UUID userId, UUID tenantId) {
        return criteriaBuilderFactory.create(entityManager, TenantMembershipEntity.class, "membership")
                .where("membership.user.id").eq(userId)
                .where("membership.tenant.id").eq(tenantId)
                .where("membership.active").eq(true)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
}
