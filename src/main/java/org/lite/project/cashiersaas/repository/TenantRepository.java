package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import com.blazebit.persistence.CriteriaBuilderFactory;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.QTenantEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TenantRepository {

    private static final QTenantEntity TENANT = new QTenantEntity("tenant");

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<TenantEntity> findById(UUID tenantId) {
        return criteriaBuilderFactory.create(entityManager, TenantEntity.class, TENANT.getMetadata().getName())
                .where(TENANT.id.toString()).eq(tenantId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<TenantEntity> findOwnedOnboarding(UUID tenantId, UUID userId) {
        return criteriaBuilderFactory.create(entityManager, TenantEntity.class, TENANT.getMetadata().getName())
                .where(TENANT.id.toString()).eq(tenantId)
                .where(TENANT.createdByUser.id.toString()).eq(userId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<TenantEntity> findByOnboardingKey(UUID userId, String key) {
        return criteriaBuilderFactory.create(entityManager, TenantEntity.class, TENANT.getMetadata().getName())
                .where(TENANT.createdByUser.id.toString()).eq(userId)
                .where(TENANT.onboardingIdempotencyKey.toString()).eq(key)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public void persist(TenantEntity tenant) {
        entityManager.persist(tenant);
    }

    public List<TenantEntity> listPendingOwnedBy(UUID userId) {
        return criteriaBuilderFactory.create(entityManager, TenantEntity.class, TENANT.getMetadata().getName())
                .where(TENANT.createdByUser.id.toString()).eq(userId)
                .where(TENANT.status.toString()).eq("PENDING_PROFILE")
                .where("tenant.businessType.code").eq("FNB")
                .where("tenant.businessType.active").eq(true)
                .orderByDesc(TENANT.createdAt.toString())
                .getResultList();
    }
}
