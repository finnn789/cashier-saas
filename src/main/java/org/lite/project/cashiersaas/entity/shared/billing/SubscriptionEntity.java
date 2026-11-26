package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PlanCode;
import org.lite.project.cashiersaas.shared.StatusConstants;

@Getter
@Entity
@Table(name="subscriptions")
@NoArgsConstructor
public class SubscriptionEntity extends MutableBaseEntity {
    /** Created with an unpaid invoice; never entitles anything (no dates) until the invoice is paid. */
    public static final String PENDING = "PENDING";
    public static final String CANCELLED = "CANCELLED";

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
    /** Outlet add-on: outlets beyond the first that this paid row allows (0 on FREETIER). */
    @Column(name="extra_outlets",nullable=false)
    private int extraOutlets;

    public static SubscriptionEntity activateFreeTier(TenantEntity tenant) {
        SubscriptionEntity subscription = new SubscriptionEntity();
        subscription.tenant = tenant;
        subscription.planCode = PlanCode.FREETIER.name();
        subscription.status = StatusConstants.ACTIVE;
        subscription.startsAt = Instant.now();
        return subscription;
    }

    public static SubscriptionEntity pending(TenantEntity tenant, PlanCode plan, int extraOutlets) {
        SubscriptionEntity subscription = new SubscriptionEntity();
        subscription.tenant = tenant;
        subscription.planCode = plan.name();
        subscription.status = PENDING;
        subscription.extraOutlets = extraOutlets;
        return subscription;
    }

    public void activate(Instant startsAt, Instant endsAt) {
        this.status = StatusConstants.ACTIVE;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public void cancel() {
        this.status = CANCELLED;
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    public PlanCode getPlan() {
        return PlanCode.valueOf(planCode);
    }

    public boolean isActiveAt(Instant instant) {
        return StatusConstants.ACTIVE.equals(status) && startsAt != null && !startsAt.isAfter(instant)
                && (endsAt == null || endsAt.isAfter(instant));
    }
}
