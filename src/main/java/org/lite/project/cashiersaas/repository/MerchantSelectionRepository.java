package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.QMembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.QTenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.QOutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.QTenantEntity;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MerchantSelectionRepository {
    private static final QTenantMembershipEntity MEMBERSHIP = new QTenantMembershipEntity("membership");
    private static final QTenantEntity TENANT = new QTenantEntity("tenant");
    private static final QOutletEntity OUTLET = new QOutletEntity("outlet");
    private static final QMembershipOutletAccessEntity ACCESS = new QMembershipOutletAccessEntity("access");

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<Selection> findAccessible(UUID userId, UUID tenantId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, Tuple.class)
                .from(TenantMembershipEntity.class, MEMBERSHIP.getMetadata().getName())
                .innerJoinFetch(MEMBERSHIP.tenant.toString(), TENANT.getMetadata().getName())
                .innerJoinOn(OutletEntity.class, OUTLET.getMetadata().getName())
                    .on(OUTLET.tenant.id.toString()).eqExpression(TENANT.id.toString())
                .end()
                .innerJoinOn(MembershipOutletAccessEntity.class, ACCESS.getMetadata().getName())
                    .on(ACCESS.membership.id.toString()).eqExpression(MEMBERSHIP.id.toString())
                    .on(ACCESS.tenant.id.toString()).eqExpression(TENANT.id.toString())
                    .on(ACCESS.outlet.id.toString()).eqExpression(OUTLET.id.toString())
                .end()
                .select(MEMBERSHIP.toString())
                .select(OUTLET.toString())
                .fetch(MEMBERSHIP.user.toString(), TENANT.businessType.toString())
                .where(MEMBERSHIP.user.id.toString()).eq(userId)
                .where(TENANT.id.toString()).eq(tenantId)
                .where(OUTLET.id.toString()).eq(outletId)
                .where(MEMBERSHIP.active.toString()).eq(true)
                .setMaxResults(1)
                .getResultList().stream()
                .map(row -> new Selection(row.get(0, TenantMembershipEntity.class),
                        row.get(1, OutletEntity.class)))
                .findFirst();
    }

    @Getter
    @RequiredArgsConstructor
    public static class Selection {
        private final TenantMembershipEntity membership;
        private final OutletEntity outlet;
    }
}
