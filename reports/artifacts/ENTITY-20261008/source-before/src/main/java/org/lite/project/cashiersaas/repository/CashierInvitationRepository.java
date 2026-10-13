package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.CashierInvitationEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class CashierInvitationRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    // The random invitation code is the only public lookup key; tenant and outlet
    // are taken from the matched record after email and expiry are checked.
    public Optional<CashierInvitationEntity> findByDigest(String digest) {
        return criteriaBuilderFactory.create(entityManager, CashierInvitationEntity.class, "invitation")
                .where("invitation.codeDigest").eq(digest)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public void lockAndRefresh(CashierInvitationEntity invitation) {
        entityManager.lock(invitation, LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(invitation);
    }

    public void persist(CashierInvitationEntity invitation) {
        entityManager.persist(invitation);
    }
}
