package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MerchantAccessRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<TenantMembershipEntity> findMember(UUID userId, UUID tenantId, Set<Role> roles, Instant now) {
        return criteriaBuilderFactory.create(entityManager, TenantMembershipEntity.class, "membership")
                .innerJoin("membership.user", "user")
                .innerJoin("membership.tenant", "tenant")
                .innerJoin("tenant.businessType", "businessType")
                .fetch("membership.tenant")
                .where("membership.user.id").eq(userId)
                .where("membership.tenant.id").eq(tenantId)
                .where("membership.active").eq(true)
                .where("membership.role").in(roles)
                .where("user.status").eq(StatusConstants.ACTIVE)
                .where("tenant.status").eq(StatusConstants.ACTIVE)
                .where("businessType.code").eq("FNB")
                .where("businessType.active").eq(true)
                .whereExists()
                    .from(SubscriptionEntity.class, "subscription")
                    .select("subscription.id")
                    .where("subscription.tenant.id").eqExpression("tenant.id")
                    .where("subscription.planCode").eq("FREETIER")
                    .where("subscription.status").eq(StatusConstants.ACTIVE)
                    .where("subscription.startsAt").le(now)
                    .whereOr()
                        .where("subscription.endsAt").isNull()
                        .where("subscription.endsAt").gt(now)
                    .endOr()
                .end()
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<OutletAccess> findOutlet(UUID userId, UUID tenantId, UUID outletId,
                                             Set<Role> roles, Instant now) {
        return criteriaBuilderFactory.create(entityManager, Tuple.class)
                .from(MembershipOutletAccessEntity.class, "access")
                .innerJoin("access.membership", "membership")
                .innerJoin("membership.user", "user")
                .innerJoin("membership.tenant", "tenant")
                .innerJoin("tenant.businessType", "businessType")
                .innerJoin("access.outlet", "outlet")
                .select("membership")
                .select("outlet")
                .fetch("membership.tenant")
                .where("access.tenant.id").eq(tenantId)
                .where("membership.tenant.id").eq(tenantId)
                .where("outlet.tenant.id").eq(tenantId)
                .where("membership.user.id").eq(userId)
                .where("outlet.id").eq(outletId)
                .where("membership.active").eq(true)
                .where("membership.role").in(roles)
                .where("user.status").eq(StatusConstants.ACTIVE)
                .where("tenant.status").eq(StatusConstants.ACTIVE)
                .where("businessType.code").eq("FNB")
                .where("businessType.active").eq(true)
                .where("outlet.active").eq(true)
                .whereExists()
                    .from(SubscriptionEntity.class, "subscription")
                    .select("subscription.id")
                    .where("subscription.tenant.id").eqExpression("tenant.id")
                    .where("subscription.planCode").eq("FREETIER")
                    .where("subscription.status").eq(StatusConstants.ACTIVE)
                    .where("subscription.startsAt").le(now)
                    .whereOr()
                        .where("subscription.endsAt").isNull()
                        .where("subscription.endsAt").gt(now)
                    .endOr()
                .end()
                .setMaxResults(1).getResultList().stream()
                .map(row -> new OutletAccess(row.get(0, TenantMembershipEntity.class),
                        row.get(1, OutletEntity.class)))
                .findFirst();
    }

    @Getter
    @RequiredArgsConstructor
    public static class OutletAccess {
        private final TenantMembershipEntity membership;
        private final OutletEntity outlet;
    }
}
