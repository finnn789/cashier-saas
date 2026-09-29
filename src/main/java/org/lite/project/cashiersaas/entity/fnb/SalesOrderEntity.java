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
import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Entity
@Table(name="sales_orders", uniqueConstraints={@UniqueConstraint(name="uk_sales_orders_outlet_order_number",columnNames={"outlet_id","order_number"}),@UniqueConstraint(name="uk_sales_orders_tenant_idempotency",columnNames={"tenant_id","idempotency_key"})})
@NoArgsConstructor
public class SalesOrderEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id",nullable=false)
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id",nullable=false)
    private OutletEntity outlet;
    @ManyToOne(fetch=FetchType.LAZY)
    @JoinColumn(name="dining_table_id")
    private DiningTableEntity diningTable;
    @Column(name="order_number",nullable=false,length=32)
    private String orderNumber;
    @Column(name="idempotency_key",nullable=false,length=128)
    private String idempotencyKey;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=24)
    private OrderSource source;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=24)
    private ServiceType serviceType;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=24)
    private OrderStatus status = OrderStatus.NEW;
    @Enumerated(EnumType.STRING)
    @Column(nullable=false,length=24)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;
    @Column(length=80)
    private String customerLabel;
    @Column(precision=19,scale=2,nullable=false)
    private BigDecimal subtotal;
    @Column(precision=19,scale=2,nullable=false)
    private BigDecimal taxAmount;
    @Column(precision=19,scale=2,nullable=false)
    private BigDecimal serviceChargeAmount;
    @Column(precision=19,scale=2,nullable=false)
    private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(precision=19,scale=2,nullable=false)
    private BigDecimal total;
    @Column(length=500)
    private String cancellationReason;
    @Column(name="request_fingerprint",length=64)
    private String requestFingerprint;
    @Column(name="public_status_token_digest",length=64,unique=true)
    private String publicStatusTokenDigest;
    @Column(name="public_status_token_expires_at")
    private Instant publicStatusTokenExpiresAt;

    public record PublicQrDetails(DiningTableEntity table, String idempotencyKey, String fingerprint,
                                  String orderNumber, String statusTokenDigest, Instant statusTokenExpiry,
                                  String customerLabel) { }

    public record Charges(BigDecimal subtotal, BigDecimal tax, BigDecimal serviceCharge) { }

    public static SalesOrderEntity publicQr(TenantEntity tenant, OutletEntity outlet,
                                            PublicQrDetails details, Charges charges) {
        SalesOrderEntity order = new SalesOrderEntity();
        order.tenant = tenant; order.outlet = outlet; order.diningTable = details.table();
        order.idempotencyKey = details.idempotencyKey();
        order.requestFingerprint = details.fingerprint(); order.orderNumber = details.orderNumber();
        order.publicStatusTokenDigest = details.statusTokenDigest();
        order.publicStatusTokenExpiresAt = details.statusTokenExpiry();
        order.source = OrderSource.QR; order.serviceType = ServiceType.DINE_IN;
        order.customerLabel = details.customerLabel(); order.subtotal = charges.subtotal();
        order.taxAmount = charges.tax(); order.serviceChargeAmount = charges.serviceCharge();
        order.total = charges.subtotal().add(charges.tax()).add(charges.serviceCharge());
        return order;
    }
}
