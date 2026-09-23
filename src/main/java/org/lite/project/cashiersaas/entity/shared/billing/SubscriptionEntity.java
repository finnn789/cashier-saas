package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name="subscriptions")
@NoArgsConstructor
public class SubscriptionEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="plan_version_id")
    private PlanVersionEntity planVersion;
    @Column(name="plan_price_id",nullable=false)
    private java.util.UUID planPriceId;
    @Column(nullable=false,length=20)
    private String status;
    @Column(name="starts_at")
    private Instant startsAt;
    @Column(name="ends_at")
    private Instant endsAt;
}
