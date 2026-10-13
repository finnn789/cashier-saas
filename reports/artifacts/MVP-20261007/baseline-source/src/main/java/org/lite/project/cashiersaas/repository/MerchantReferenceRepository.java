package org.lite.project.cashiersaas.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MerchantReferenceRepository {
    private final EntityManager entityManager;

    // These are JPA references, not ownership lookups. The signed merchant JWT
    // supplies the IDs; composite foreign keys enforce tenant/outlet writes.
    public TenantEntity tenant(UUID tenantId) {
        return entityManager.getReference(TenantEntity.class, tenantId);
    }

    public OutletEntity outlet(UUID outletId) {
        return entityManager.getReference(OutletEntity.class, outletId);
    }

    public StaffUserEntity user(UUID userId) {
        return entityManager.getReference(StaffUserEntity.class, userId);
    }

    public TenantMembershipEntity membership(UUID membershipId) {
        return entityManager.getReference(TenantMembershipEntity.class, membershipId);
    }
}
