package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TenantMembershipRepository {

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public List<TenantMembershipEntity> findActiveByStaffUserId(UUID staffUserId) {
        return criteriaBuilderFactory.create(entityManager, TenantMembershipEntity.class, "membership")
                .where("membership.user.id").eq(staffUserId)
                .where("membership.active").eq(true)
                .getResultList();
    }

    public void persist(TenantMembershipEntity membership) {
        entityManager.persist(membership);
    }
}
