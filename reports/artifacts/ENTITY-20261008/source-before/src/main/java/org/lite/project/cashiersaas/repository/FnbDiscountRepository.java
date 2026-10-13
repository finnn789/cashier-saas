package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.DiscountEntity;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbDiscountRepository {
    private final EntityManager entityManager;
    private final com.blazebit.persistence.CriteriaBuilderFactory criteriaBuilderFactory;
    public Optional<DiscountEntity> byOrder(UUID tenantId, UUID outletId, UUID orderId) {
        return criteriaBuilderFactory.create(entityManager, DiscountEntity.class, "discount")
                .where("discount.tenant.id").eq(tenantId).where("discount.outlet.id").eq(outletId)
                .where("discount.salesOrder.id").eq(orderId).setMaxResults(1).getResultList().stream().findFirst();
    }
    public void persist(DiscountEntity discount) { entityManager.persist(discount); }
}
