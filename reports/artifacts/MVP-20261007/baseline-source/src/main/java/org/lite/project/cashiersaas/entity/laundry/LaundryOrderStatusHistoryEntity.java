package org.lite.project.cashiersaas.entity.laundry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.UUID;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="laundry_order_status_history")
@NoArgsConstructor
public class LaundryOrderStatusHistoryEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private UUID outletId;
    @Column(name="order_id",nullable=false)
    private UUID orderId;
    @Column(name="from_status",length=16)
    private String fromStatus;
    @Column(name="to_status",nullable=false,length=16)
    private String toStatus;
    @Column(columnDefinition="TEXT")
    private String reason;
    @Column(name="actor_membership_id",nullable=false)
    private UUID actorMembershipId;
    @Column(name="occurred_at",nullable=false)
    private Instant occurredAt;
}
