package org.lite.project.cashiersaas.entity.fnb;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import org.lite.project.cashiersaas.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Entity
@Table(name = "payment_refunds", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_refunds_payment", columnNames = "payment_id"),
        @UniqueConstraint(name = "uk_payment_refunds_tenant_idempotency", columnNames = {"tenant_id", "idempotency_key"})
})
@NoArgsConstructor
public class PaymentRefundEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private PaymentEntity payment;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_order_id", nullable = false)
    private SalesOrderEntity salesOrder;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentMethod method;
    @Column(nullable = false, length = 500)
    private String reason;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refunded_by_staff_user_id", nullable = false)
    private StaffUserEntity refundedBy;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_shift_id")
    private CashShiftEntity cashShift;
    @Column(name = "refunded_at", nullable = false)
    private Instant refundedAt;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    /** The method is copied from the payment; refundedAt is stamped now. */
    public static PaymentRefundEntity record(TenantEntity tenant, OutletEntity outlet, PaymentEntity payment,
                                             SalesOrderEntity order, BigDecimal amount, String reason,
                                             StaffUserEntity refundedBy, CashShiftEntity cashShift,
                                             String idempotencyKey, String fingerprint) {
        PaymentRefundEntity refund = new PaymentRefundEntity();
        refund.tenant = tenant;
        refund.outlet = outlet;
        refund.payment = payment;
        refund.salesOrder = order;
        refund.amount = amount;
        refund.method = payment.getMethod();
        refund.reason = reason;
        refund.refundedBy = refundedBy;
        refund.cashShift = cashShift;
        refund.refundedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        refund.idempotencyKey = idempotencyKey;
        refund.requestFingerprint = fingerprint;
        return refund;
    }
}
