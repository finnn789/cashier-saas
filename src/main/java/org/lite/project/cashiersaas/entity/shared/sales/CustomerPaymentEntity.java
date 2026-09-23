package org.lite.project.cashiersaas.entity.shared.sales;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="customer_payments", uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","idempotency_key"}))
@NoArgsConstructor
public class CustomerPaymentEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private UUID tenantId;
    @Column(name="outlet_id",nullable=false)
    private UUID outletId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="customer_invoice_id")
    private CustomerInvoiceEntity customerInvoice;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal amount;
    @Column(name="tendered_amount",nullable=false,precision=19,scale=2)
    private BigDecimal tenderedAmount;
    @Column(name="change_amount",nullable=false,precision=19,scale=2)
    private BigDecimal changeAmount;
    @Column(name="currency_code",nullable=false,length=3)
    private String currencyCode;
    @Column(nullable=false,length=20)
    private String method;
    @Column(name="reference_number",length=120)
    private String referenceNumber;
    @Column(nullable=false,length=16)
    private String status;
    @Column(name="idempotency_key",nullable=false,length=128)
    private String idempotencyKey;
    @Column(name="request_fingerprint",nullable=false,length=64)
    private String requestFingerprint;
    @Column(name="received_by_membership_id",nullable=false)
    private UUID receivedByMembershipId;
    @Column(name="paid_at",nullable=false)
    private Instant paidAt;
}
