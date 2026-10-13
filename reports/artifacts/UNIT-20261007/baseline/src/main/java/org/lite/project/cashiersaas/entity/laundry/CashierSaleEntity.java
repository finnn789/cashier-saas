package org.lite.project.cashiersaas.entity.laundry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Getter
@Entity
@Table(name = "cashier_sales", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"tenant_id", "outlet_id", "sale_number"}),
        @UniqueConstraint(columnNames = {"tenant_id", "idempotency_key"})})
@NoArgsConstructor
public class CashierSaleEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @Column(name = "sale_number", nullable = false, length = 50)
    private String saleNumber;
    @Column(name = "customer_name", nullable = false, length = 160)
    private String customerName;
    @Column(name = "subtotal_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotalAmount;
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalAmount;
    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;
    @Column(name = "payment_method", nullable = false, length = 16)
    private String paymentMethod;
    @Column(name = "tendered_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal tenderedAmount;
    @Column(name = "change_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal changeAmount;
    @Column(name = "payment_reference", length = 120)
    private String paymentReference;
    @Column(name = "received_by_membership_id", nullable = false)
    private UUID receivedByMembershipId;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;
    @Column(name = "paid_at", nullable = false)
    private Instant paidAt;

    public static CashierSaleEntity createPaidSale(TenantEntity tenant, OutletEntity outlet, String customerName,
                                                   BigDecimal total, String currencyCode,
                                                   PaymentDetails payment, CheckoutMetadata metadata) {
        CashierSaleEntity sale = new CashierSaleEntity();
        sale.tenant = tenant;
        sale.outlet = outlet;
        sale.saleNumber = "S-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        sale.customerName = customerName;
        sale.subtotalAmount = total;
        sale.totalAmount = total;
        sale.currencyCode = currencyCode;
        sale.paymentMethod = payment.getMethod();
        sale.tenderedAmount = payment.getTenderedAmount();
        sale.changeAmount = payment.getTenderedAmount().subtract(total);
        sale.paymentReference = payment.getReference();
        sale.receivedByMembershipId = metadata.getActorMembershipId();
        sale.idempotencyKey = metadata.getIdempotencyKey();
        sale.requestFingerprint = metadata.getRequestFingerprint();
        sale.paidAt = Instant.now();
        return sale;
    }

    @Getter
    public static final class PaymentDetails {
        private final String method;
        private final BigDecimal tenderedAmount;
        private final String reference;

        public PaymentDetails(String method, BigDecimal tenderedAmount, String reference) {
            this.method = method;
            this.tenderedAmount = tenderedAmount;
            this.reference = reference;
        }
    }

    @Getter
    public static final class CheckoutMetadata {
        private final UUID actorMembershipId;
        private final String idempotencyKey;
        private final String requestFingerprint;

        public CheckoutMetadata(UUID actorMembershipId, String idempotencyKey, String requestFingerprint) {
            this.actorMembershipId = actorMembershipId;
            this.idempotencyKey = idempotencyKey;
            this.requestFingerprint = requestFingerprint;
        }
    }
}
