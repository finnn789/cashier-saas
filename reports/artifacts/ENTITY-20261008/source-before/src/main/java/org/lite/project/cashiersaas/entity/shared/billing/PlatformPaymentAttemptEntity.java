package org.lite.project.cashiersaas.entity.shared.billing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;

@Getter
@Entity
@Table(name="platform_payment_attempts",uniqueConstraints=@UniqueConstraint(columnNames={"tenant_id","idempotency_key"}))
@NoArgsConstructor
public class PlatformPaymentAttemptEntity extends MutableBaseEntity {
    @Column(name="tenant_id",nullable=false)
    private java.util.UUID tenantId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="platform_invoice_id")
    private PlatformInvoiceEntity platformInvoice;
    @Column(name="provider_code",nullable=false,length=50)
    private String providerCode;
    @Column(name="provider_account_ref",nullable=false,length=120)
    private String providerAccountRef;
    @Column(name="provider_payment_ref",length=160)
    private String providerPaymentRef;
    @Column(nullable=false,precision=19,scale=2)
    private BigDecimal amount;
    @Column(name="currency_code",nullable=false,length=3)
    private String currencyCode;
    @Column(nullable=false,length=20)
    private String status;
    @Column(name="idempotency_key",nullable=false,length=128)
    private String idempotencyKey;
    @Column(name="request_fingerprint",nullable=false,length=64)
    private String requestFingerprint;
}
