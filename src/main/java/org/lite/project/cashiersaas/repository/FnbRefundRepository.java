package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.PaymentRefundEntity;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
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

    public RefundTotals totals(UUID tenantId, UUID outletId, Instant start, Instant end) {
        return criteriaBuilderFactory.create(entityManager, Tuple.class)
                .from(PaymentRefundEntity.class, "refund")
                .where("refund.tenant.id").eq(tenantId).where("refund.outlet.id").eq(outletId)
                .where("refund.refundedAt").ge(start).where("refund.refundedAt").lt(end)
                .selectNew(RefundTotals.class)
                .with("COUNT(refund.id)").with("COALESCE(SUM(refund.amount), 0)")
                .end().getSingleResult();
    }

    public void persist(PaymentRefundEntity refund) { entityManager.persist(refund); }

    public static class RefundTotals {
        public final long count;
        public final BigDecimal amount;

        public RefundTotals(Long count, BigDecimal amount) {
            this.count = count;
            this.amount = amount;
        }
    }
}
