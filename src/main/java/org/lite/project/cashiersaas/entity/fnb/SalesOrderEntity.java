package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.enums.OrderSource;
import org.lite.project.cashiersaas.enums.OrderStatus;
import org.lite.project.cashiersaas.enums.PaymentStatus;
import org.lite.project.cashiersaas.enums.ServiceType;
import org.lite.project.cashiersaas.exception.runtime.InvalidOperationException;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@Entity
@Table(name = "sales_orders", uniqueConstraints = {@UniqueConstraint(name = "uk_sales_orders_outlet_order_number", columnNames = {"outlet_id", "order_number"}), @UniqueConstraint(name = "uk_sales_orders_tenant_idempotency", columnNames = {"tenant_id", "idempotency_key"})})
@NoArgsConstructor
public class SalesOrderEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;

    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OrderSource source;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ServiceType serviceType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private OrderStatus status = OrderStatus.OPEN;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;
    @Column(length = 80)
    private String customerLabel;
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal subtotal;
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal taxAmount;
    @Column(name = "tax_rate_snapshot", precision = 5, scale = 2, nullable = false)
    private BigDecimal taxRateSnapshot;
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal serviceChargeAmount;
    @Column(name = "service_charge_rate_snapshot", precision = 5, scale = 2, nullable = false)
    private BigDecimal serviceChargeRateSnapshot;
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(precision = 19, scale = 2, nullable = false)
    private BigDecimal total;
    @Column(length = 500)
    private String cancellationReason;
    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    public record Charges(BigDecimal subtotal, BigDecimal tax, BigDecimal serviceCharge) {
    }

    public record PosDetails(String idempotencyKey, String fingerprint,
                             String orderNumber, ServiceType serviceType, String customerLabel) {
    }

    public static SalesOrderEntity pos(TenantEntity tenant, OutletEntity outlet, PosDetails details, Charges charges) {
        if (details.serviceType() == null) throw new InvalidOperationException("Tipe layanan tidak valid.");
        SalesOrderEntity order = new SalesOrderEntity();
        order.tenant = tenant;
        order.outlet = outlet;
        order.idempotencyKey = details.idempotencyKey();
        order.requestFingerprint = details.fingerprint();
        order.orderNumber = details.orderNumber();
        order.source = OrderSource.POS;
        order.serviceType = details.serviceType();
        order.customerLabel = details.customerLabel();
        order.subtotal = charges.subtotal();
        order.taxAmount = charges.tax();
        order.serviceChargeAmount = charges.serviceCharge();
        order.total = charges.subtotal().add(charges.tax()).add(charges.serviceCharge());
        return order;
    }

    public void snapshotRates(BigDecimal taxRate, BigDecimal serviceChargeRate) {
        requireOpenUnpaid();
        if (taxRateSnapshot != null || serviceChargeRateSnapshot != null)
            throw new InvalidOperationException("Snapshot tarif pesanan sudah tersedia.");
        requireNonNegative(taxRate, "Tarif pajak tidak valid.");
        requireNonNegative(serviceChargeRate, "Tarif biaya layanan tidak valid.");
        taxRateSnapshot = taxRate;
        serviceChargeRateSnapshot = serviceChargeRate;
    }

    public void replaceLines(ServiceType serviceType, String customerLabel, Charges charges) {
        requireOpenUnpaid();
        if (discountAmount.signum() != 0)
            throw new InvalidOperationException("Pesanan sudah memiliki diskon; item tidak dapat diubah.");
        if (serviceType == null) throw new InvalidOperationException("Tipe layanan tidak valid.");
        this.serviceType = serviceType;
        this.customerLabel = customerLabel;
        subtotal = charges.subtotal();
        taxAmount = charges.tax();
        serviceChargeAmount = charges.serviceCharge();
        total = charges.subtotal().add(charges.tax()).add(charges.serviceCharge());
    }

    public void confirmPaid() {
        requireOpenUnpaid();
        paymentStatus = PaymentStatus.PAID;
        status = OrderStatus.COMPLETED;
    }

    public void cancel(String reason) {
        requireOpenUnpaid();
        if (reason == null || reason.isBlank()) throw new InvalidOperationException("Alasan pembatalan wajib diisi.");
        cancellationReason = reason.trim();
        status = OrderStatus.CANCELLED;
    }

    public void applyDiscount(BigDecimal amount) {
        requireOpenUnpaid();
        requireNonNegative(amount, "Diskon tidak valid.");
        if (amount.compareTo(subtotal) > 0) throw new InvalidOperationException("Diskon melebihi subtotal.");
        if (taxRateSnapshot == null || serviceChargeRateSnapshot == null)
            throw new InvalidOperationException("Snapshot tarif pesanan belum tersedia.");
        discountAmount = amount;
        BigDecimal discountedSubtotal = subtotal.subtract(amount);
        taxAmount = percentage(discountedSubtotal, taxRateSnapshot);
        serviceChargeAmount = percentage(discountedSubtotal, serviceChargeRateSnapshot);
        total = discountedSubtotal.add(taxAmount).add(serviceChargeAmount);
    }

    private void requireOpenUnpaid() {
        if (status != OrderStatus.OPEN || paymentStatus != PaymentStatus.UNPAID)
            throw new InvalidOperationException("Perubahan hanya dapat dilakukan pada pesanan terbuka yang belum dibayar.");
    }

    private static void requireNonNegative(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) throw new InvalidOperationException(message);
    }

    private static BigDecimal percentage(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
}
