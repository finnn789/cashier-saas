package org.lite.project.cashiersaas.repository;

import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class StaffUserRepository {

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<StaffUserEntity> findByNormalizedEmail(String normalizedEmail) {
        return criteriaBuilderFactory.create(entityManager, StaffUserEntity.class, "staffUser")
                .where("LOWER(staffUser.email)").eq(normalizedEmail)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    public Optional<StaffUserEntity> findById(UUID staffUserId) {
        return criteriaBuilderFactory.create(entityManager, StaffUserEntity.class, "staffUser")
                .where("staffUser.id").eq(staffUserId)
                .setMaxResults(1)
                .getResultList()
                .stream()
                .findFirst();
    }

    public void persistAndFlush(StaffUserEntity staffUser) {
        entityManager.persist(staffUser);
        entityManager.flush();
    }
}
