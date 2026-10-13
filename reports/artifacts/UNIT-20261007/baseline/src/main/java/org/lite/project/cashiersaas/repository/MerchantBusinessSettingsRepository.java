package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.laundry.CashierSaleEntity;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MerchantBusinessSettingsRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public boolean hasCurrencyUse(UUID tenantId) {
        return exists(SalesOrderEntity.class, "salesOrder", tenantId)
                || exists(CashierSaleEntity.class, "cashierSale", tenantId);
    }

    private <T> boolean exists(Class<T> entityType, String alias, UUID tenantId) {
        return !criteriaBuilderFactory.create(entityManager, entityType, alias)
                .where(alias + ".tenant.id").eq(tenantId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }
}
