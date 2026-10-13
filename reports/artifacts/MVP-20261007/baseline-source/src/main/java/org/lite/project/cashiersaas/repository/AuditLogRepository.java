package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.audit.AuditLogEntity;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class AuditLogRepository {
    private final EntityManager entityManager;

    public void persist(AuditLogEntity log) {
        entityManager.persist(log);
    }
}
