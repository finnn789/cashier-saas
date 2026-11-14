package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    /** All CASHIER memberships of the tenant with an access row for the outlet, active or not, by full name. */
    public List<TenantMembershipEntity> listCashiersForOutlet(UUID tenantId, UUID outletId) {
        return cashiersForOutlet(tenantId, outletId).orderByAsc("user.fullName").orderByAsc("membership.id")
                .getResultList();
    }

    public Optional<TenantMembershipEntity> findCashierForOutlet(UUID tenantId, UUID outletId, UUID userId) {
        return cashiersForOutlet(tenantId, outletId).where("membership.user.id").eq(userId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public boolean hasActiveMembershipOutsideTenant(UUID userId, UUID tenantId) {
        return !criteriaBuilderFactory.create(entityManager, TenantMembershipEntity.class, "membership")
                .where("membership.user.id").eq(userId)
                .where("membership.tenant.id").notEq(tenantId)
                .where("membership.active").eq(true)
                .setMaxResults(1).getResultList().isEmpty();
    }

    private CriteriaBuilder<TenantMembershipEntity> cashiersForOutlet(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, TenantMembershipEntity.class, "membership")
                .innerJoin("membership.user", "user")
                .fetch("membership.user")
                .where("membership.tenant.id").eq(tenantId)
                .where("membership.role").eq(Role.CASHIER)
                .whereExists()
                    .from(MembershipOutletAccessEntity.class, "access")
                    .select("access.id")
                    .where("access.membership.id").eqExpression("membership.id")
                    .where("access.outlet.id").eq(outletId)
                .end();
    }
}
