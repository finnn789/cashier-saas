package org.lite.project.cashiersaas.entity.shared.sales;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.Instant;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

@Getter
@Entity
@Table(name="customer_invoices", uniqueConstraints={@UniqueConstraint(columnNames={"tenant_id","order_id"}),@UniqueConstraint(columnNames={"tenant_id","outlet_id","invoice_number"})})
@NoArgsConstructor
public class CustomerInvoiceEntity extends MutableBaseEntity {
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="tenant_id")
    private TenantEntity tenant;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="outlet_id")
    private OutletEntity outlet;
    @OneToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="order_id")
    private OrderEntity order;
    @Column(name="invoice_number",nullable=false,length=50)
    private String invoiceNumber;
    @Column(nullable=false,length=16)
    private String status;
    @Column(name="currency_code",nullable=false,length=3)
    private String currencyCode;
    @Column(name="subtotal_amount",nullable=false,precision=19,scale=2)
    private BigDecimal subtotalAmount;
    @Column(name="discount_amount",nullable=false,precision=19,scale=2)
    private BigDecimal discountAmount;
    @Column(name="fee_amount",nullable=false,precision=19,scale=2)
    private BigDecimal feeAmount;
    @Column(name="tax_amount",nullable=false,precision=19,scale=2)
    private BigDecimal taxAmount;
    @Column(name="total_amount",nullable=false,precision=19,scale=2)
    private BigDecimal totalAmount;
    @Column(name="issued_at",nullable=false)
    private Instant issuedAt;
    private Instant paidAt;
}
