package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import com.blazebit.persistence.WhereBuilder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.TenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.enums.Role;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MerchantAccessRepository {
    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<TenantMembershipEntity> findMember(UUID userId, UUID tenantId, Set<Role> roles, Instant now) {
        return requirePlanAccess(criteriaBuilderFactory.create(entityManager, TenantMembershipEntity.class, "membership")
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
                .where("businessType.active").eq(true), now)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public Optional<OutletAccess> findOutlet(UUID userId, UUID tenantId, UUID outletId,
                                             Set<Role> roles, Instant now) {
        return requirePlanAccess(criteriaBuilderFactory.create(entityManager, Tuple.class)
                .from(MembershipOutletAccessEntity.class, "access")
                .innerJoin("access.membership", "membership")
                .innerJoin("membership.user", "user")
                .innerJoin("membership.tenant", "tenant")
                .innerJoin("tenant.businessType", "businessType")
                .innerJoin("access.outlet", "outlet")
                .select("membership")
                .select("outlet")
                // Active outlets of the tenant ranked before this one by (createdAt, id); same order as PlanEntitlementService.
                .selectSubquery("outletRank")
                    .from(OutletEntity.class, "ranked")
                    .select("count(ranked.id)")
                    .where("ranked.tenant.id").eqExpression("outlet.tenant.id")
                    .where("ranked.active").eq(true)
                    .whereOr()
                        .where("ranked.createdAt").ltExpression("outlet.createdAt")
                        .whereAnd()
                            .where("ranked.createdAt").eqExpression("outlet.createdAt")
                            .where("ranked.id").ltExpression("outlet.id")
                        .endAnd()
                    .endOr()
                .end()
                // Add-on outlets of every active paid row; null (no paid row, FREETIER or trial) means an allowance of 1.
                .selectSubquery("extraOutlets")
                    .from(SubscriptionEntity.class, "addOn")
                    .select("sum(addOn.extraOutlets)")
                    .where("addOn.tenant.id").eqExpression("outlet.tenant.id")
                    .where("addOn.planCode").in(List.of(PlanCode.PRO.name(), PlanCode.PREMIUM.name()))
                    .where("addOn.status").eq(StatusConstants.ACTIVE)
                    .where("addOn.startsAt").le(now)
                    .whereOr()
                        .where("addOn.endsAt").isNull()
                        .where("addOn.endsAt").gt(now)
                    .endOr()
                .end()
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
                .where("outlet.active").eq(true), now)
                .setMaxResults(1).getResultList().stream()
                .map(row -> new OutletAccess(row.get(0, TenantMembershipEntity.class), row.get(1, OutletEntity.class),
                        row.get(2, Long.class) < 1 + (row.get(3) == null ? 0L : ((Number) row.get(3)).longValue())))
                .findFirst();
    }

    /**
     * Any active subscription qualifies a membership. A CASHIER membership additionally needs an active paid row
     * or a running Premium trial, so a downgrade blocks cashiers without touching their data.
     */
    static <B extends WhereBuilder<B>> B requirePlanAccess(B query, Instant now) {
        return query
                .whereExists()
                    .from(SubscriptionEntity.class, "subscription")
                    .select("subscription.id")
                    .where("subscription.tenant.id").eqExpression("tenant.id")
                    .where("subscription.status").eq(StatusConstants.ACTIVE)
                    .where("subscription.startsAt").le(now)
                    .whereOr()
                        .where("subscription.endsAt").isNull()
                        .where("subscription.endsAt").gt(now)
                    .endOr()
                .end()
                .whereOr()
                    .where("membership.role").notEq(Role.CASHIER)
                    .whereExists()
                        .from(SubscriptionEntity.class, "paidSubscription")
                        .select("paidSubscription.id")
                        .where("paidSubscription.tenant.id").eqExpression("tenant.id")
                        .where("paidSubscription.planCode").in(List.of(PlanCode.PRO.name(), PlanCode.PREMIUM.name()))
                        .where("paidSubscription.status").eq(StatusConstants.ACTIVE)
                        .where("paidSubscription.startsAt").le(now)
                        .whereOr()
                            .where("paidSubscription.endsAt").isNull()
                            .where("paidSubscription.endsAt").gt(now)
                        .endOr()
                    .end()
                    .where("tenant.premiumTrialStartedAt").gt(now.minus(TenantEntity.PREMIUM_TRIAL_DURATION))
                .endOr();
    }

    @Getter
    @RequiredArgsConstructor
    public static class OutletAccess {
        private final TenantMembershipEntity membership;
        private final OutletEntity outlet;
        /** False when the active outlet is over the plan's outlet allowance (see PlanEntitlementService.usableOutletIds). */
        private final boolean usable;
    }
}
