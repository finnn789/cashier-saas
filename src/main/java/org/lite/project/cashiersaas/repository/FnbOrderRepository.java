package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.OrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.OrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.QOrderItemEntity;
import org.lite.project.cashiersaas.entity.fnb.QOrderItemOptionEntity;
import org.lite.project.cashiersaas.entity.fnb.QSalesOrderEntity;
import org.lite.project.cashiersaas.entity.fnb.SalesOrderEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.exception.runtime.ResourceNotFoundException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbOrderRepository {
    private static final QSalesOrderEntity ORDER = new QSalesOrderEntity("salesOrder");
    private static final QOrderItemEntity ITEM = new QOrderItemEntity("orderItem");
    private static final QOrderItemOptionEntity OPTION = new QOrderItemOptionEntity("orderItemOption");
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    // A tenant row lock serializes checkout with the same idempotency key.
    public void lockTenant(UUID tenantId) {
        TenantEntity tenant = criteriaBuilderFactory.create(entityManager, TenantEntity.class, "tenant")
                .where("tenant.id").eq(tenantId).setMaxResults(1).getResultList().stream()
                .findFirst().orElseThrow(ResourceNotFoundException::new);
        entityManager.refresh(tenant, LockModeType.PESSIMISTIC_WRITE);
    }

    public Optional<SalesOrderEntity> byIdempotency(UUID tenant, String key) {
        return criteriaBuilderFactory.create(entityManager, SalesOrderEntity.class, ORDER.getMetadata().getName())
                .where(ORDER.tenant.id.toString()).eq(tenant).where(ORDER.idempotencyKey.toString()).eq(key)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<SalesOrderEntity> byId(UUID tenantId, UUID outletId, UUID orderId) {
        return criteriaBuilderFactory.create(entityManager, SalesOrderEntity.class, ORDER.getMetadata().getName())
                .where(ORDER.tenant.id.toString()).eq(tenantId).where(ORDER.outlet.id.toString()).eq(outletId)
                .where(ORDER.id.toString()).eq(orderId).setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<SalesOrderEntity> page(UUID tenantId, UUID outletId, int page, int size,
                                       OrderStatus status, PaymentStatus paymentStatus, OrderSource source) {
        var query = criteriaBuilderFactory.create(entityManager, SalesOrderEntity.class, ORDER.getMetadata().getName())
                .where(ORDER.tenant.id.toString()).eq(tenantId).where(ORDER.outlet.id.toString()).eq(outletId);
        if (status != null) query.where(ORDER.status.toString()).eq(status);
        if (paymentStatus != null) query.where(ORDER.paymentStatus.toString()).eq(paymentStatus);
        if (source != null) query.where(ORDER.source.toString()).eq(source);
        return query.orderByDesc(ORDER.createdAt.toString()).orderByDesc(ORDER.id.toString())
                .setFirstResult(page * size).setMaxResults(size + 1).getResultList();
    }

    public List<OrderItemEntity> items(UUID tenantId, UUID outletId, List<UUID> orderIds) {
        if (orderIds.isEmpty()) return List.of();
        return criteriaBuilderFactory.create(entityManager, OrderItemEntity.class, ITEM.getMetadata().getName())
                .where(ITEM.tenant.id.toString()).eq(tenantId).where(ITEM.outlet.id.toString()).eq(outletId)
                .where(ITEM.salesOrder.id.toString()).in(orderIds).orderByAsc(ITEM.createdAt.toString()).getResultList();
    }

    public List<OrderItemOptionEntity> itemOptions(UUID tenantId, UUID outletId, List<UUID> itemIds) {
        if (itemIds.isEmpty()) return List.of();
        return criteriaBuilderFactory.create(entityManager, OrderItemOptionEntity.class, OPTION.getMetadata().getName())
                .where(OPTION.tenant.id.toString()).eq(tenantId).where(OPTION.outlet.id.toString()).eq(outletId)
                .where(OPTION.orderItem.id.toString()).in(itemIds).orderByAsc(OPTION.createdAt.toString()).getResultList();
    }

    public void refreshOrder(SalesOrderEntity order) {
        entityManager.refresh(order);
    }

    public void persist(SalesOrderEntity order) {
        entityManager.persist(order);
    }

    public void persist(OrderItemEntity item) {
        entityManager.persist(item);
    }

    public void persist(OrderItemOptionEntity option) {
        entityManager.persist(option);
    }

    public void flush() {
        entityManager.flush();
    }
}
