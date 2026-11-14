package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilder;
import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.fnb.PaymentLabelEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class FnbPaymentLabelRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public void persist(PaymentLabelEntity label) { entityManager.persist(label); }

    public Optional<PaymentLabelEntity> byId(UUID tenantId, UUID outletId, UUID id) {
        return criteriaBuilderFactory.create(entityManager, PaymentLabelEntity.class, "label")
                .where("label.tenant.id").eq(tenantId).where("label.outlet.id").eq(outletId)
                .where("label.id").eq(id).setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<PaymentLabelEntity> list(UUID tenantId, UUID outletId, boolean activeOnly) {
        CriteriaBuilder<PaymentLabelEntity> builder = criteriaBuilderFactory.create(entityManager, PaymentLabelEntity.class, "label")
                .where("label.tenant.id").eq(tenantId).where("label.outlet.id").eq(outletId);
        if (activeOnly) builder.where("label.active").eq(true);
        return builder.orderByAsc("label.displayOrder").orderByAsc("label.name").getResultList();
    }

    public long count(UUID tenantId, UUID outletId) {
        return entityManager.createQuery("select count(l) from PaymentLabelEntity l "
                        + "where l.tenant.id = :tenantId and l.outlet.id = :outletId", Long.class)
                .setParameter("tenantId", tenantId).setParameter("outletId", outletId).getSingleResult();
    }

    /** Case-insensitive; excludeId (nullable) skips the label being edited. */
    public boolean existsName(UUID tenantId, UUID outletId, String name, UUID excludeId) {
        CriteriaBuilder<PaymentLabelEntity> builder = criteriaBuilderFactory.create(entityManager, PaymentLabelEntity.class, "label")
                .where("label.tenant.id").eq(tenantId).where("label.outlet.id").eq(outletId)
                .where("LOWER(label.name)").eq(name.trim().toLowerCase());
        if (excludeId != null) builder.where("label.id").notEq(excludeId);
        return !builder.setMaxResults(1).getResultList().isEmpty();
    }
}
