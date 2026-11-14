package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.CashExpenseEntity;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbCashExpenseRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public void persist(CashExpenseEntity expense) { entityManager.persist(expense); }

    public Optional<CashExpenseEntity> byKey(UUID tenantId, String key) {
        return criteriaBuilderFactory.create(entityManager, CashExpenseEntity.class, "expense")
                .where("expense.tenant.id").eq(tenantId).where("expense.idempotencyKey").eq(key)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<CashExpenseEntity> forShift(UUID shiftId) {
        return criteriaBuilderFactory.create(entityManager, CashExpenseEntity.class, "expense")
                .where("expense.cashShift.id").eq(shiftId)
                .orderByAsc("expense.recordedAt").orderByAsc("expense.id").getResultList();
    }

    /** Expenses recorded in [from, to) at the outlet. */
    public BigDecimal totalForOutlet(UUID tenantId, UUID outletId, Instant from, Instant to) {
        BigDecimal total = entityManager.createQuery("select sum(e.amount) from CashExpenseEntity e where e.tenant.id = :tenantId "
                        + "and e.outlet.id = :outletId and e.recordedAt >= :from and e.recordedAt < :to", BigDecimal.class)
                .setParameter("tenantId", tenantId).setParameter("outletId", outletId)
                .setParameter("from", from).setParameter("to", to).getSingleResult();
        return total == null ? new BigDecimal("0.00") : total;
    }

    public BigDecimal totalForShift(UUID shiftId) {
        BigDecimal total = entityManager.createQuery("select sum(e.amount) from CashExpenseEntity e "
                + "where e.cashShift.id = :shiftId", BigDecimal.class).setParameter("shiftId", shiftId).getSingleResult();
        return total == null ? new BigDecimal("0.00") : total;
    }
}
