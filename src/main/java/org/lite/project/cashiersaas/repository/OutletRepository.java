package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.springframework.stereotype.Repository;

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

    public void persist(OutletEntity outlet) {
        entityManager.persist(outlet);
    }
}
