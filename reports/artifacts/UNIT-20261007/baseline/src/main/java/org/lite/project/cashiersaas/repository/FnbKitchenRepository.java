package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.QSalesOrderEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbKitchenRepository {
    private static final QSalesOrderEntity ORDER = new QSalesOrderEntity("kitchenOrder");
    private static final List<OrderStatus> KITCHEN_STATUSES = List.of(
            OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY);

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public List<SalesOrderEntity> page(UUID tenantId, UUID outletId, int page, int size) {
        return criteriaBuilderFactory.create(entityManager, SalesOrderEntity.class, ORDER.getMetadata().getName())
                .where(ORDER.tenant.id.toString()).eq(tenantId)
                .where(ORDER.outlet.id.toString()).eq(outletId)
                .where(ORDER.paymentStatus.toString()).eq(PaymentStatus.PAID)
                .where(ORDER.status.toString()).in(KITCHEN_STATUSES)
                .orderByAsc(ORDER.createdAt.toString()).orderByAsc(ORDER.id.toString())
                .setFirstResult(page * size).setMaxResults(size).getResultList();
    }

    public long count(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, SalesOrderEntity.class, ORDER.getMetadata().getName())
                .where(ORDER.tenant.id.toString()).eq(tenantId)
                .where(ORDER.outlet.id.toString()).eq(outletId)
                .where(ORDER.paymentStatus.toString()).eq(PaymentStatus.PAID)
                .where(ORDER.status.toString()).in(KITCHEN_STATUSES).getCountQuery().getSingleResult();
    }
}
