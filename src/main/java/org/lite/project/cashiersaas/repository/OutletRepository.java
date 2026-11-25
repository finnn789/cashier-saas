package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class OutletRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<OutletEntity> findActive(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, OutletEntity.class, "outlet")
                .where("outlet.tenant.id").eq(tenantId)
                .where("outlet.id").eq(outletId)
                .where("outlet.active").eq(true)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    /** The outlet of the tenant whatever its active flag; other tenants' outlets are never returned. */
    public Optional<OutletEntity> findInTenant(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, OutletEntity.class, "outlet")
                .where("outlet.tenant.id").eq(tenantId)
                .where("outlet.id").eq(outletId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    /** Every outlet of the tenant, oldest first (the same order that ranks them against the plan allowance). */
    public List<OutletEntity> listByTenant(UUID tenantId) {
        return criteriaBuilderFactory.create(entityManager, OutletEntity.class, "outlet")
                .where("outlet.tenant.id").eq(tenantId)
                .orderByAsc("outlet.createdAt").orderByAsc("outlet.id")
                .getResultList();
    }

    public boolean nameTaken(UUID tenantId, String name, UUID exceptOutletId) {
        CriteriaBuilder<UUID> query = criteriaBuilderFactory.create(entityManager, UUID.class)
                .from(OutletEntity.class, "outlet").select("outlet.id")
                .where("outlet.tenant.id").eq(tenantId)
                .where("outlet.name").eq(name);
        if (exceptOutletId != null) {
            query.where("outlet.id").notEq(exceptOutletId);
        }
        return !query.setMaxResults(1).getResultList().isEmpty();
    }

    public boolean hasOpenShift(UUID tenantId, UUID outletId) {
        return !entityManager.createQuery("select s.id from CashShiftEntity s where s.tenant.id = :tenantId "
                        + "and s.outlet.id = :outletId and s.status = :open", UUID.class)
                .setParameter("tenantId", tenantId).setParameter("outletId", outletId)
                .setParameter("open", CashShiftEntity.Status.OPEN).setMaxResults(1).getResultList().isEmpty();
    }

    public List<TenantMembershipEntity> activeOwnerMemberships(UUID tenantId) {
        return entityManager.createQuery("select m from TenantMembershipEntity m where m.tenant.id = :tenantId "
                        + "and m.role = :owner and m.active = true", TenantMembershipEntity.class)
                .setParameter("tenantId", tenantId).setParameter("owner", Role.OWNERMERCHANT).getResultList();
    }

    public void persist(OutletEntity outlet) {
        entityManager.persist(outlet);
    }
}
