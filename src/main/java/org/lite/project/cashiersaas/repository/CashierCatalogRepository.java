package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.laundry.CashierCatalogItemEntity;
import org.lite.project.cashiersaas.entity.laundry.QCashierCatalogItemEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CashierCatalogRepository {
    private static final QCashierCatalogItemEntity ITEM = new QCashierCatalogItemEntity("item");

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<CashierCatalogItemEntity> findActive(UUID tenantId, UUID outletId, UUID itemId) {
        return criteriaBuilderFactory.create(entityManager, CashierCatalogItemEntity.class, ITEM.getMetadata().getName())
                .where(ITEM.tenant.id.toString()).eq(tenantId)
                .where(ITEM.outlet.id.toString()).eq(outletId)
                .where(ITEM.id.toString()).eq(itemId)
                .where(ITEM.active.toString()).eq(true)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<CashierCatalogItemEntity> listActive(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, CashierCatalogItemEntity.class, ITEM.getMetadata().getName())
                .where(ITEM.tenant.id.toString()).eq(tenantId)
                .where(ITEM.outlet.id.toString()).eq(outletId)
                .where(ITEM.active.toString()).eq(true)
                .orderByAsc(ITEM.name.toString())
                .getResultList();
    }

    public List<CashierCatalogItemEntity> findActiveByIds(UUID tenantId, UUID outletId, Collection<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        return criteriaBuilderFactory.create(entityManager, CashierCatalogItemEntity.class, ITEM.getMetadata().getName())
                .where(ITEM.tenant.id.toString()).eq(tenantId)
                .where(ITEM.outlet.id.toString()).eq(outletId)
                .where(ITEM.id.toString()).in(itemIds)
                .where(ITEM.active.toString()).eq(true)
                .getResultList();
    }

    public void persist(CashierCatalogItemEntity item) {
        entityManager.persist(item);
    }
}
