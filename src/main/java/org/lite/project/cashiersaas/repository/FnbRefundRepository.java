package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.PaymentRefundEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbRefundRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<PaymentRefundEntity> byKey(UUID tenantId, String key) {
        return criteriaBuilderFactory.create(entityManager, PaymentRefundEntity.class, "refund")
                .where("refund.tenant.id").eq(tenantId).where("refund.idempotencyKey").eq(key)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<PaymentRefundEntity> byPayment(UUID tenantId, UUID paymentId) {
        return criteriaBuilderFactory.create(entityManager, PaymentRefundEntity.class, "refund")
                .where("refund.tenant.id").eq(tenantId).where("refund.payment.id").eq(paymentId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public void persist(PaymentRefundEntity refund) { entityManager.persist(refund); }
}
