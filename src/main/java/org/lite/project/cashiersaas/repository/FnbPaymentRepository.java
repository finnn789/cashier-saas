package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.PaymentEntity;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbPaymentRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;
    public Optional<PaymentEntity> byOrder(UUID tenantId, UUID outletId, UUID orderId) {
        return criteriaBuilderFactory.create(entityManager, PaymentEntity.class, "payment")
                .where("payment.tenant.id").eq(tenantId).where("payment.outlet.id").eq(outletId)
                .where("payment.salesOrder.id").eq(orderId).setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<PaymentEntity> byKey(UUID tenantId, String key) {
        return criteriaBuilderFactory.create(entityManager, PaymentEntity.class, "payment")
                .where("payment.tenant.id").eq(tenantId)
                .where("payment.idempotencyKey").eq(key)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    public void persist(PaymentEntity payment) { entityManager.persist(payment); }

    /** Atomically advances the per-outlet daily counter; the row lock serialises concurrent payments. */
    public int nextReceiptSequence(UUID outletId, LocalDate businessDate) {
        return ((Number) entityManager.createNativeQuery("INSERT INTO receipt_sequences (outlet_id, business_date, last_value) "
                        + "VALUES (:outletId, :businessDate, 1) ON CONFLICT (outlet_id, business_date) "
                        + "DO UPDATE SET last_value = receipt_sequences.last_value + 1 RETURNING last_value")
                .setParameter("outletId", outletId).setParameter("businessDate", businessDate).getSingleResult()).intValue();
    }
}
