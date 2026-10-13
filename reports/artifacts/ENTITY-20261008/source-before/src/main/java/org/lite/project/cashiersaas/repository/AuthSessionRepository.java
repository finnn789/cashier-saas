package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.AuthSessionEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class AuthSessionRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<AuthSessionEntity> findById(UUID sessionId, UUID userId) {
        return criteriaBuilderFactory.create(entityManager, AuthSessionEntity.class, "session")
                .where("session.id").eq(sessionId)
                .where("session.user.id").eq(userId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public void lockAndRefresh(AuthSessionEntity session) {
        entityManager.lock(session, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(session);
    }

    public void persist(AuthSessionEntity session) {
        entityManager.persist(session);
    }
}
