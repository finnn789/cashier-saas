package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.laundry.CashierCatalogItemEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CashierCatalogRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<CashierCatalogItemEntity> findActive(UUID tenantId, UUID outletId, UUID itemId) {
        return criteriaBuilderFactory.create(entityManager, CashierCatalogItemEntity.class, "item")
                .where("item.tenant.id").eq(tenantId)
                .where("item.outlet.id").eq(outletId)
                .where("item.id").eq(itemId)
                .where("item.active").eq(true)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<CashierCatalogItemEntity> listActive(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, CashierCatalogItemEntity.class, "item")
                .where("item.tenant.id").eq(tenantId)
                .where("item.outlet.id").eq(outletId)
                .where("item.active").eq(true)
                .orderByAsc("item.name")
                .getResultList();
    }

    public void persist(CashierCatalogItemEntity item) {
        entityManager.persist(item);
    }
}
