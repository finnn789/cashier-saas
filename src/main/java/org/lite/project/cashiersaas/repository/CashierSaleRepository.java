package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.laundry.CashierSaleEntity;
import org.lite.project.cashiersaas.entity.laundry.CashierSaleItemEntity;
import org.lite.project.cashiersaas.entity.laundry.QCashierSaleEntity;
import org.lite.project.cashiersaas.entity.laundry.QCashierSaleItemEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CashierSaleRepository {
    private static final QCashierSaleEntity SALE = new QCashierSaleEntity("sale");
    private static final QCashierSaleItemEntity LINE = new QCashierSaleItemEntity("line");

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<CashierSaleEntity> findByKey(UUID tenantId, String key) {
        return criteriaBuilderFactory.create(entityManager, CashierSaleEntity.class, SALE.getMetadata().getName())
                .where(SALE.tenant.id.toString()).eq(tenantId)
                .where(SALE.idempotencyKey.toString()).eq(key)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<CashierSaleEntity> findById(UUID tenantId, UUID outletId, UUID saleId) {
        return criteriaBuilderFactory.create(entityManager, CashierSaleEntity.class, SALE.getMetadata().getName())
                .where(SALE.tenant.id.toString()).eq(tenantId)
                .where(SALE.outlet.id.toString()).eq(outletId)
                .where(SALE.id.toString()).eq(saleId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<CashierSaleEntity> listRecent(UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, CashierSaleEntity.class, SALE.getMetadata().getName())
                .where(SALE.tenant.id.toString()).eq(tenantId)
                .where(SALE.outlet.id.toString()).eq(outletId)
                .orderByDesc(SALE.paidAt.toString())
                .setMaxResults(100).getResultList();
    }

    public List<CashierSaleItemEntity> listItems(UUID tenantId, UUID outletId, UUID saleId) {
        return criteriaBuilderFactory.create(entityManager, CashierSaleItemEntity.class, LINE.getMetadata().getName())
                .where(LINE.tenant.id.toString()).eq(tenantId)
                .where(LINE.outlet.id.toString()).eq(outletId)
                .where(LINE.sale.id.toString()).eq(saleId)
                .getResultList();
    }

    public void persist(CashierSaleEntity sale) {
        entityManager.persist(sale);
    }

    public void persist(CashierSaleItemEntity line) {
        entityManager.persist(line);
    }
}
