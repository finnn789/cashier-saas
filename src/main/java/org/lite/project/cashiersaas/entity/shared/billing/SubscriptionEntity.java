package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.shared.StatusConstants;

@Getter
@Entity
@Table(name="subscriptions")
@NoArgsConstructor
public class SubscriptionEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @Column(name="plan_code",nullable=false,length=40)
    private String planCode;
    @Column(nullable=false,length=20)
    private String status;
    @Column(name="starts_at")
    private Instant startsAt;
    @Column(name="ends_at")
    private Instant endsAt;

    public static SubscriptionEntity activateFreeTier(TenantEntity tenant) {
        SubscriptionEntity subscription = new SubscriptionEntity();
        subscription.tenant = tenant;
        subscription.planCode = "FREETIER";
        subscription.status = StatusConstants.ACTIVE;
        subscription.startsAt = Instant.now();
        return subscription;
    }

    public boolean isActiveAt(Instant instant) {
        return StatusConstants.ACTIVE.equals(status) && startsAt != null && !startsAt.isAfter(instant)
                && (endsAt == null || endsAt.isAfter(instant));
    }
}
