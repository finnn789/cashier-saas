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
    public static final String PENDING = "PENDING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

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

    public static PlatformPaymentAttemptEntity initiate(PlatformInvoiceEntity invoice, String providerCode, String providerAccountRef,
                                                        String providerPaymentRef, String idempotencyKey, String requestFingerprint) {
        PlatformPaymentAttemptEntity attempt = new PlatformPaymentAttemptEntity();
        attempt.tenantId = invoice.getTenant().getId();
        attempt.platformInvoice = invoice;
        attempt.providerCode = providerCode;
        attempt.providerAccountRef = providerAccountRef;
        attempt.providerPaymentRef = providerPaymentRef;
        attempt.amount = invoice.getAmountDue();
        attempt.currencyCode = invoice.getCurrencyCode();
        attempt.status = PENDING;
        attempt.idempotencyKey = idempotencyKey;
        attempt.requestFingerprint = requestFingerprint;
        return attempt;
    }

    public boolean isPending() {
        return PENDING.equals(status);
    }

    /** A null reference keeps the one given when the attempt was created. */
    public void succeed(String paymentRef) {
        this.status = SUCCEEDED;
        if (paymentRef != null) {
            this.providerPaymentRef = paymentRef;
        }
    }

    public void fail() {
        this.status = FAILED;
    }

    public void cancel() {
        this.status = CANCELLED;
    }
}
