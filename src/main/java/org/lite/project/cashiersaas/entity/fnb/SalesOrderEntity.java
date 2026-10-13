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
import java.time.Instant;

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dining_table_id")
    private DiningTableEntity diningTable;
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
    private OrderStatus status = OrderStatus.NEW;
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
    @Column(name = "public_status_token_digest", length = 64, unique = true)
    private String publicStatusTokenDigest;
    @Column(name = "public_status_token_expires_at")
    private Instant publicStatusTokenExpiresAt;

    public record PublicQrDetails(DiningTableEntity table, String idempotencyKey, String fingerprint,
                                  String orderNumber, String statusTokenDigest, Instant statusTokenExpiry,
                                  String customerLabel) {
    }

    public record Charges(BigDecimal subtotal, BigDecimal tax, BigDecimal serviceCharge) {
    }

    public record PosDetails(DiningTableEntity table, String idempotencyKey, String fingerprint,
                             String orderNumber, ServiceType serviceType, String customerLabel) {
    }

    public static SalesOrderEntity publicQr(TenantEntity tenant, OutletEntity outlet,
                                            PublicQrDetails details, Charges charges) {
        SalesOrderEntity order = new SalesOrderEntity();
        order.tenant = tenant;
        order.outlet = outlet;
        order.diningTable = details.table();
        order.idempotencyKey = details.idempotencyKey();
        order.requestFingerprint = details.fingerprint();
        order.orderNumber = details.orderNumber();
        order.publicStatusTokenDigest = details.statusTokenDigest();
        order.publicStatusTokenExpiresAt = details.statusTokenExpiry();
        order.source = OrderSource.QR;
        order.serviceType = ServiceType.DINE_IN;
        order.customerLabel = details.customerLabel();
        order.subtotal = charges.subtotal();
        order.taxAmount = charges.tax();
        order.serviceChargeAmount = charges.serviceCharge();
        order.total = charges.subtotal().add(charges.tax()).add(charges.serviceCharge());
        return order;
    }

    public static SalesOrderEntity pos(TenantEntity tenant, OutletEntity outlet, PosDetails details, Charges charges) {
        if (details.serviceType() == null || (details.serviceType() == ServiceType.TAKEAWAY && details.table() != null)) {
            throw new InvalidOperationException("Tipe layanan dan meja tidak valid.");
        }
        SalesOrderEntity order = new SalesOrderEntity();
        order.tenant = tenant;
        order.outlet = outlet;
        order.diningTable = details.table();
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
        requireNewUnpaid();
        if (taxRateSnapshot != null || serviceChargeRateSnapshot != null)
            throw new InvalidOperationException("Snapshot tarif pesanan sudah tersedia.");
        requireNonNegative(taxRate, "Tarif pajak tidak valid.");
        requireNonNegative(serviceChargeRate, "Tarif biaya layanan tidak valid.");
        taxRateSnapshot = taxRate;
        serviceChargeRateSnapshot = serviceChargeRate;
    }

    public void selectPublicServiceType(ServiceType type) {
        if (type == null || (diningTable != null && type != ServiceType.DINE_IN))
            throw new InvalidOperationException("Tipe layanan tidak valid.");
        serviceType = type;
    }

    public void confirmPaid() {
        requireNewUnpaid();
        paymentStatus = PaymentStatus.PAID;
        status = OrderStatus.CONFIRMED;
    }

    public void startPreparing() {
        requirePaidStatus(OrderStatus.CONFIRMED);
        status = OrderStatus.PREPARING;
    }

    public void markReady() {
        requirePaidStatus(OrderStatus.PREPARING);
        status = OrderStatus.READY;
    }

    public void complete() {
        requirePaidStatus(OrderStatus.READY);
        status = OrderStatus.COMPLETED;
    }

    public void cancel(String reason) {
        requireNewUnpaid();
        if (reason == null || reason.isBlank()) throw new InvalidOperationException("Alasan pembatalan wajib diisi.");
        cancellationReason = reason.trim();
        status = OrderStatus.CANCELLED;
    }

    public void applyDiscount(BigDecimal amount) {
        requireNewUnpaid();
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

    private void requireNewUnpaid() {
        if (status != OrderStatus.NEW || paymentStatus != PaymentStatus.UNPAID)
            throw new InvalidOperationException("Perubahan hanya dapat dilakukan pada pesanan baru yang belum dibayar.");
    }

    private void requirePaidStatus(OrderStatus expected) {
        if (paymentStatus != PaymentStatus.PAID || status != expected)
            throw new InvalidOperationException("Transisi status pesanan tidak valid.");
    }

    private static void requireNonNegative(BigDecimal value, String message) {
        if (value == null || value.signum() < 0) throw new InvalidOperationException(message);
    }

    private static BigDecimal percentage(BigDecimal amount, BigDecimal rate) {
        return amount.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
}
