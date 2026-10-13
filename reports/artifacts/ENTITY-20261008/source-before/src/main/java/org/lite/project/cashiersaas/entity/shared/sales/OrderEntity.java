package org.lite.project.cashiersaas.entity.shared.sales;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.customer.CustomerEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name="orders", uniqueConstraints={
    @UniqueConstraint(columnNames={"tenant_id","outlet_id","order_number"}),
    @UniqueConstraint(columnNames={"tenant_id","idempotency_key"})})
@NoArgsConstructor
public class OrderEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id")
    private OutletEntity outlet;
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="customer_id")
    private CustomerEntity customer;
    @Column(name="order_number",nullable=false,length=50)
    private String orderNumber;
    @Column(nullable=false,length=16)
    private String source;
    @Column(nullable=false,length=16)
    private String status;
    @Column(name="customer_name_snapshot",nullable=false,length=160)
    private String customerNameSnapshot;
    @Column(name="customer_phone_snapshot",length=40)
    private String customerPhoneSnapshot;
    @Column(name="subtotal_amount",nullable=false,precision=19,scale=2)
    private BigDecimal subtotalAmount;
    @Column(name="total_amount",nullable=false,precision=19,scale=2)
    private BigDecimal totalAmount;
    @Column(name="currency_code",nullable=false,length=3)
    private String currencyCode;
    @Column(name="idempotency_key",nullable=false,length=128)
    private String idempotencyKey;
    @Column(name="request_fingerprint",nullable=false,length=64)
    private String requestFingerprint;
    @Column(name="received_at",nullable=false)
    private Instant receivedAt;
    private Instant readyAt;
    private Instant handedOverAt;
    private Instant completedAt;
    private Instant cancelledAt;
    @Column(columnDefinition="TEXT")
    private String cancellationReason;
    @Column(name="created_by_membership_id",nullable=false)
    private java.util.UUID createdByMembershipId;
}
