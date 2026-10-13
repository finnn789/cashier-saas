package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.lite.project.cashiersaas.entity.shared.billing.QBusinessTypeEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class BusinessTypeRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;
    private final QBusinessTypeEntity TYPECODE = QBusinessTypeEntity.businessTypeEntity;

    public Optional<BusinessTypeEntity> findActiveByCode(String code) {
        return criteriaBuilderFactory.create(entityManager, BusinessTypeEntity.class, TYPECODE.getMetadata().getName())
                .where(TYPECODE.code.toString()).eq(code)
                .where(TYPECODE.active.toString()).eq(true)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
}
