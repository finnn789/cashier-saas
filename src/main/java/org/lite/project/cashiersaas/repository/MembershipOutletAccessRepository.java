package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MembershipOutletAccessRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<MembershipOutletAccessEntity> findAccess(UUID tenantId, UUID membershipId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, MembershipOutletAccessEntity.class, "access")
                .where("access.tenant.id").eq(tenantId)
                .where("access.membership.id").eq(membershipId)
                .where("access.outlet.id").eq(outletId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public void persist(MembershipOutletAccessEntity access) {
        entityManager.persist(access);
    }

    public List<MembershipOutletAccessEntity> listActiveForMembership(UUID tenantId, UUID membershipId) {
        return criteriaBuilderFactory.create(entityManager, MembershipOutletAccessEntity.class, "access")
                .where("access.tenant.id").eq(tenantId)
                .where("access.membership.id").eq(membershipId)
                .where("access.outlet.active").eq(true)
                .orderByAsc("access.outlet.name")
                .getResultList();
    }
}
