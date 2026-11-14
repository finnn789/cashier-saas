package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.AuthSessionEntity;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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

    public int revokeForMembership(UUID membershipId, Instant now) {
        return entityManager.createQuery("update AuthSessionEntity s set s.revokedAt = :now, "
                        + "s.updatedAt = :now where s.membership.id = :membershipId "
                        + "and s.revokedAt is null")
                .setParameter("now", now).setParameter("membershipId", membershipId).executeUpdate();
    }

    /** Revokes every non-revoked session of the user; a null {@code keepSessionId} keeps none. */
    public int revokeForUserExcept(UUID userId, UUID keepSessionId, Instant now) {
        String keepClause = keepSessionId == null ? "" : " and s.id <> :keepSessionId";
        var query = entityManager.createQuery("update AuthSessionEntity s set s.revokedAt = :now, "
                        + "s.updatedAt = :now where s.user.id = :userId and s.revokedAt is null"
                        + keepClause)
                .setParameter("now", now).setParameter("userId", userId);
        if (keepSessionId != null) {
            query.setParameter("keepSessionId", keepSessionId);
        }
        return query.executeUpdate();
    }
}
