package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.identity.StaffUserEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.enums.PaymentMethod;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Getter
@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_sales_order", columnNames = "sales_order_id"),
        @UniqueConstraint(name = "uk_payments_tenant_idempotency", columnNames = {"tenant_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_payments_outlet_receipt", columnNames = {"outlet_id", "receipt_number"})
})
@NoArgsConstructor
public class PaymentEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_order_id", nullable = false)
    private SalesOrderEntity salesOrder;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentMethod method;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amountPaid;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal changeAmount = BigDecimal.ZERO;
    @Column(nullable = false, length = 32)
    private String receiptNumber;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "method_label", length = 40)
    private String methodLabel;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "received_by_staff_user_id", nullable = false)
    private StaffUserEntity receivedBy;
    @Column(nullable = false)
    private Instant paidAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cash_shift_id")
    private CashShiftEntity cashShift;

    public static PaymentEntity settle(TenantEntity tenant, OutletEntity outlet, SalesOrderEntity order,
                                       PaymentMethod method, BigDecimal amountPaid, BigDecimal changeAmount, String receiptNumber,
                                       String idempotencyKey, String fingerprint, String methodLabel, StaffUserEntity receivedBy,
                                       CashShiftEntity cashShift) {
        PaymentEntity payment = new PaymentEntity();
        payment.tenant = tenant;
        payment.outlet = outlet;
        payment.salesOrder = order;
        payment.method = method;
        payment.amountPaid = amountPaid;
        payment.changeAmount = changeAmount;
        payment.receiptNumber = receiptNumber;
        payment.idempotencyKey = idempotencyKey;
        payment.requestFingerprint = fingerprint;
        payment.methodLabel = methodLabel;
        payment.receivedBy = receivedBy;
        payment.cashShift = cashShift;
        payment.paidAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return payment;
    }
}
