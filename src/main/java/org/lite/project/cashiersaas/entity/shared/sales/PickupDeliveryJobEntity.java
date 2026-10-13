package org.lite.project.cashiersaas.entity.shared.sales;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;

@Getter
@Entity
@Table(name="pickup_delivery_jobs")
@NoArgsConstructor
public class PickupDeliveryJobEntity extends MutableBaseEntity {
    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private UUID outletId;
    @Column(name="customer_id",nullable=false)
    private UUID customerId;
    @Column(name="order_id")
    private UUID orderId;
    @Column(name="assigned_membership_id")
    private UUID assignedMembershipId;
    @Column(name="job_type",nullable=false,length=12)
    private String jobType;
    @Column(nullable=false,length=16)
    private String status;
    @Column(name="recipient_name_snapshot",nullable=false,length=160)
    private String recipientNameSnapshot;
    @Column(name="recipient_phone_snapshot",nullable=false,length=40)
    private String recipientPhoneSnapshot;
    @Column(name="address_snapshot",nullable=false,columnDefinition="TEXT")
    private String addressSnapshot;
    @Column(name="fee_amount_snapshot",nullable=false,precision=19,scale=2)
    private BigDecimal feeAmountSnapshot;
    @Column(name="currency_code",nullable=false,length=3)
    private String currencyCode;
    private Instant scheduledFrom;
    private Instant scheduledUntil;
    private Instant completedAt;
}
