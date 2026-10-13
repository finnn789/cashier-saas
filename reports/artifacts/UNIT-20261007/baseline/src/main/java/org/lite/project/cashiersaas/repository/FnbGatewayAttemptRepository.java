package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.FnbGatewayAttemptEntity;
import org.lite.project.cashiersaas.enums.FnbGatewayOutcome;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbGatewayAttemptRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;
    public Optional<FnbGatewayAttemptEntity> byId(UUID tenantId, UUID outletId, UUID attemptId) {
        return scoped(tenantId, outletId).where("attempt.id").eq(attemptId).setMaxResults(1).getResultList().stream().findFirst();
    }
    public Optional<FnbGatewayAttemptEntity> byKey(UUID tenantId, String key) {
        return criteriaBuilderFactory.create(entityManager, FnbGatewayAttemptEntity.class, "attempt")
                .where("attempt.tenant.id").eq(tenantId)
                .where("attempt.idempotencyKey").eq(key)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }
    public Optional<FnbGatewayAttemptEntity> pendingForOrder(UUID tenantId, UUID outletId, UUID orderId) {
        return scoped(tenantId, outletId).where("attempt.salesOrder.id").eq(orderId).where("attempt.outcome").eq(FnbGatewayOutcome.PENDING)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
    private com.blazebit.persistence.CriteriaBuilder<FnbGatewayAttemptEntity> scoped(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, FnbGatewayAttemptEntity.class, "attempt")
                .where("attempt.tenant.id").eq(tenantId).where("attempt.outlet.id").eq(outletId);
    }
    public void persist(FnbGatewayAttemptEntity attempt) { entityManager.persist(attempt); }
}
