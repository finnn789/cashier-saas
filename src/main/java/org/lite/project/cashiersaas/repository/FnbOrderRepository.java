package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.*;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbOrderRepository {
    private static final QSalesOrderEntity ORDER = new QSalesOrderEntity("salesOrder");
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    // A tenant row lock serializes checkout with the same idempotency key even across QR tables.
    public void lockTenant(UUID tenantId) {
        if (entityManager.find(TenantEntity.class, tenantId, LockModeType.PESSIMISTIC_WRITE) == null)
            throw new ResourceNotFoundException();
    }
    public void refreshQr(TableQrTokenEntity qr) { entityManager.refresh(qr); }
    public Optional<SalesOrderEntity> byIdempotency(UUID tenant, String key) {
        return criteriaBuilderFactory.create(entityManager, SalesOrderEntity.class, ORDER.getMetadata().getName())
                .where(ORDER.tenant.id.toString()).eq(tenant).where(ORDER.idempotencyKey.toString()).eq(key)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
    public Optional<SalesOrderEntity> byStatusDigest(String digest) {
        return criteriaBuilderFactory.create(entityManager, SalesOrderEntity.class, ORDER.getMetadata().getName())
                .where(ORDER.publicStatusTokenDigest.toString()).eq(digest)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
    public void persist(SalesOrderEntity order) { entityManager.persist(order); }
    public void persist(OrderItemEntity item) { entityManager.persist(item); }
    public void persist(OrderItemOptionEntity option) { entityManager.persist(option); }
    public void flush() { entityManager.flush(); }
}
