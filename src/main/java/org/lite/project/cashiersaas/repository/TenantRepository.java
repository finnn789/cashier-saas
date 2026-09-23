package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class TenantRepository {

    private final EntityManager entityManager;

    public void persist(TenantEntity tenant) {
        entityManager.persist(tenant);
    }
}
