package org.lite.project.cashiersaas.repository;

import com.blazebit.persistence.CriteriaBuilderFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import lombok.RequiredArgsConstructor;
import org.lite.project.cashiersaas.dto.MerchantOutletChoiceProjection;
import org.lite.project.cashiersaas.entity.shared.billing.QSubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.billing.SubscriptionEntity;
import org.lite.project.cashiersaas.entity.shared.identity.MembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.QMembershipOutletAccessEntity;
import org.lite.project.cashiersaas.entity.shared.identity.QTenantMembershipEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.QOutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.QTenantEntity;
import org.lite.project.cashiersaas.shared.StatusConstants;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class MembershipOutletAccessRepository {
    private static final QMembershipOutletAccessEntity ACCESS = new QMembershipOutletAccessEntity("access");
    private static final QTenantMembershipEntity MEMBERSHIP = new QTenantMembershipEntity("membership");
    private static final QTenantEntity TENANT = new QTenantEntity("tenant");
    private static final QOutletEntity OUTLET = new QOutletEntity("outlet");
    private static final QSubscriptionEntity SUBSCRIPTION = new QSubscriptionEntity("subscription");
    private static final String FNB_BUSINESS_TYPE = "FNB";

    private final EntityManager entityManager;
    private final CriteriaBuilderFactory criteriaBuilderFactory;

    public Optional<MembershipOutletAccessEntity> findAccess(UUID tenantId, UUID membershipId, UUID outletId) {
        return criteriaBuilderFactory.create(entityManager, MembershipOutletAccessEntity.class, ACCESS.getMetadata().getName())
                .innerJoin(ACCESS.tenant.toString(), TENANT.getMetadata().getName())
                .innerJoin(ACCESS.membership.toString(), MEMBERSHIP.getMetadata().getName())
                .innerJoin(ACCESS.outlet.toString(), OUTLET.getMetadata().getName())
                .where(TENANT.id.toString()).eq(tenantId)
                .where(MEMBERSHIP.id.toString()).eq(membershipId)
                .where(OUTLET.id.toString()).eq(outletId)
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public void persist(MembershipOutletAccessEntity access) {
        entityManager.persist(access);
    }

    public Optional<MembershipOutletAccessEntity> findFirstActiveForMembership(UUID tenantId, UUID membershipId) {
        return criteriaBuilderFactory.create(entityManager, MembershipOutletAccessEntity.class, ACCESS.getMetadata().getName())
                .innerJoin(ACCESS.tenant.toString(), TENANT.getMetadata().getName())
                .innerJoin(ACCESS.membership.toString(), MEMBERSHIP.getMetadata().getName())
                .innerJoin(ACCESS.outlet.toString(), OUTLET.getMetadata().getName())
                .where(TENANT.id.toString()).eq(tenantId)
                .where(MEMBERSHIP.id.toString()).eq(membershipId)
                .where(OUTLET.active.toString()).eq(true)
                .orderByAsc(OUTLET.name.toString())
                .setMaxResults(1).getResultList().stream().findFirst();
    }

    public List<MerchantOutletChoiceProjection> listActiveChoices(UUID userId, Instant now) {
        return criteriaBuilderFactory.create(entityManager, Tuple.class)
                .from(MembershipOutletAccessEntity.class, ACCESS.getMetadata().getName())
                .innerJoin(ACCESS.tenant.toString(), TENANT.getMetadata().getName())
                .innerJoin(ACCESS.membership.toString(), MEMBERSHIP.getMetadata().getName())
                .innerJoin(ACCESS.outlet.toString(), OUTLET.getMetadata().getName())
                .selectNew(MerchantOutletChoiceProjection.class)
                    .with(TENANT.id.toString())
                    .with(TENANT.name.toString())
                    .with(OUTLET.id.toString())
                    .with(OUTLET.name.toString())
                    .with(MEMBERSHIP.role.toString())
                .end()
                .where(MEMBERSHIP.user.id.toString()).eq(userId)
                .where(MEMBERSHIP.active.toString()).eq(true)
                .where(MEMBERSHIP.tenant.id.toString()).eqExpression(TENANT.id.toString())
                .where(OUTLET.tenant.id.toString()).eqExpression(TENANT.id.toString())
                .where(TENANT.status.toString()).eq(StatusConstants.ACTIVE)
                .where(TENANT.businessType.code.toString()).eq(FNB_BUSINESS_TYPE)
                .where(TENANT.businessType.active.toString()).eq(true)
                .where(OUTLET.active.toString()).eq(true)
                .whereExists()
                    .from(SubscriptionEntity.class, SUBSCRIPTION.getMetadata().getName())
                    .select(SUBSCRIPTION.id.toString())
                    .where(SUBSCRIPTION.tenant.id.toString()).eqExpression(TENANT.id.toString())
                    .where(SUBSCRIPTION.status.toString()).eq(StatusConstants.ACTIVE)
                    .where(SUBSCRIPTION.startsAt.toString()).le(now)
                    .whereOr()
                        .where(SUBSCRIPTION.endsAt.toString()).isNull()
                        .where(SUBSCRIPTION.endsAt.toString()).gt(now)
                    .endOr()
                .end()
                .orderByAsc(TENANT.name.toString())
                .orderByAsc(OUTLET.name.toString())
                .getResultList();
    }
}
