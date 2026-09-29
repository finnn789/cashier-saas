package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.*;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbTableRepository {
    private static final QDiningTableEntity TABLE = new QDiningTableEntity("diningTable");
    private static final QTableQrTokenEntity QR = new QTableQrTokenEntity("tableQrToken");
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<DiningTableEntity> find(UUID tenant, UUID outlet, UUID id) {
        return criteriaBuilderFactory.create(entityManager, DiningTableEntity.class, TABLE.getMetadata().getName())
                .where(TABLE.tenant.id.toString()).eq(tenant).where(TABLE.outlet.id.toString()).eq(outlet)
                .where(TABLE.id.toString()).eq(id).setMaxResults(1).getResultList().stream().findFirst();
    }
    public List<DiningTableEntity> list(UUID tenant, UUID outlet) {
        return criteriaBuilderFactory.create(entityManager, DiningTableEntity.class, TABLE.getMetadata().getName())
                .where(TABLE.tenant.id.toString()).eq(tenant).where(TABLE.outlet.id.toString()).eq(outlet)
                .orderByAsc(TABLE.name.toString()).getResultList();
    }
    public Optional<TableQrTokenEntity> byTable(UUID tenant, UUID outlet, UUID table) {
        return criteriaBuilderFactory.create(entityManager, TableQrTokenEntity.class, QR.getMetadata().getName())
                .where(QR.tenant.id.toString()).eq(tenant).where(QR.outlet.id.toString()).eq(outlet)
                .where(QR.diningTable.id.toString()).eq(table).setMaxResults(1).getResultList().stream().findFirst();
    }
    // The public token selects server-controlled tenant/outlet; no client ownership fields are trusted.
    public Optional<TableQrTokenEntity> byToken(String token) {
        return criteriaBuilderFactory.create(entityManager, TableQrTokenEntity.class, QR.getMetadata().getName())
                .where(QR.token.toString()).eq(token).where(QR.active.toString()).eq(true)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
    public void persist(DiningTableEntity table) { entityManager.persist(table); }
    public void persist(TableQrTokenEntity qr) { entityManager.persist(qr); }
}
