package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.BusinessTypeEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class BusinessTypeRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<BusinessTypeEntity> findActiveLaundry() {
        return criteriaBuilderFactory.create(entityManager, BusinessTypeEntity.class, "type")
                .where("type.code").eq("LAUNDRY")
                .where("type.active").eq(true)
                .setMaxResults(1).getResultList().stream().findFirst();
    }
}
