package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.CashShiftEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbShiftRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public void persist(CashShiftEntity shift) { entityManager.persist(shift); }

    public void lock(CashShiftEntity shift) {
        entityManager.refresh(shift, LockModeType.PESSIMISTIC_WRITE);
    }

    public Optional<CashShiftEntity> openFor(UUID tenantId, UUID outletId, UUID membershipId) {
        return criteriaBuilderFactory.create(entityManager, CashShiftEntity.class, "shift")
                .where("shift.tenant.id").eq(tenantId).where("shift.outlet.id").eq(outletId)
                .where("shift.membership.id").eq(membershipId).where("shift.status").eq(CashShiftEntity.Status.OPEN)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    /** Most recently closed shift of the outlet, whichever member closed it. */
    public Optional<CashShiftEntity> latestClosed(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, CashShiftEntity.class, "shift")
                .where("shift.tenant.id").eq(tenantId).where("shift.outlet.id").eq(outletId)
                .where("shift.status").eq(CashShiftEntity.Status.CLOSED)
                .orderByDesc("shift.closedAt").orderByDesc("shift.id")
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<CashShiftEntity> byId(UUID tenantId, UUID outletId, UUID id) {
        return criteriaBuilderFactory.create(entityManager, CashShiftEntity.class, "shift")
                .where("shift.tenant.id").eq(tenantId).where("shift.outlet.id").eq(outletId)
                .where("shift.id").eq(id).setMaxResults(1).getResultList().stream().findFirst();
    }

    /** openedAt in [from, to); returns up to size + 1 rows so the caller can detect a next page. */
    public List<CashShiftEntity> page(UUID tenantId, UUID outletId, Instant from, Instant to, int page, int size) {
        return criteriaBuilderFactory.create(entityManager, CashShiftEntity.class, "shift")
                .where("shift.tenant.id").eq(tenantId).where("shift.outlet.id").eq(outletId)
                .where("shift.openedAt").ge(from).where("shift.openedAt").lt(to)
                .orderByDesc("shift.openedAt").orderByDesc("shift.id")
                .setFirstResult(page * size).setMaxResults(size + 1).getResultList();
    }

    public CashSummary cashSummary(UUID shiftId) {
        List<MethodTotal> totals = entityManager.createQuery("select p.method, p.methodLabel, count(p), sum(p.amountPaid - p.changeAmount) "
                        + "from PaymentEntity p where p.cashShift.id = :shiftId group by p.method, p.methodLabel "
                        + "order by p.method, p.methodLabel", Object[].class)
                .setParameter("shiftId", shiftId).getResultList().stream()
                .map(row -> new MethodTotal((PaymentMethod) row[0], (String) row[1], (Long) row[2], (BigDecimal) row[3])).toList();
        BigDecimal cashRefunds = entityManager.createQuery("select sum(r.amount) from PaymentRefundEntity r "
                        + "where r.cashShift.id = :shiftId and r.method = :cash", BigDecimal.class)
                .setParameter("shiftId", shiftId).setParameter("cash", PaymentMethod.CASH).getSingleResult();
        BigDecimal cashIn = totals.stream().filter(total -> total.getMethod() == PaymentMethod.CASH)
                .map(MethodTotal::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long paymentCount = totals.stream().mapToLong(MethodTotal::getCount).sum();
        return new CashSummary(cashIn, cashRefunds == null ? BigDecimal.ZERO : cashRefunds, paymentCount, totals);
    }

    @Getter
    @RequiredArgsConstructor
    public static class MethodTotal {
        private final PaymentMethod method;
        private final String methodLabel;
        private final long count;
        private final BigDecimal amount;
    }

    @Getter
    @RequiredArgsConstructor
    public static class CashSummary {
        private final BigDecimal cashIn;
        private final BigDecimal cashRefunds;
        private final long paymentCount;
        private final List<MethodTotal> totalsByMethod;
    }
}
