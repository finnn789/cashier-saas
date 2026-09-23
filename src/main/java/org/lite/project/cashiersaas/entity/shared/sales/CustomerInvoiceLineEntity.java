package org.lite.project.cashiersaas.entity.shared.sales;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;

@Getter
@Entity
@Table(name="customer_invoice_lines")
@NoArgsConstructor
public class CustomerInvoiceLineEntity extends BaseEntity {
    @Column(name="tenant_id",nullable=false)
    private java.util.UUID tenantId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false)
    @JoinColumn(name="customer_invoice_id")
    private CustomerInvoiceEntity customerInvoice;
    @Column(name="laundry_order_item_id",nullable=false)
    private java.util.UUID laundryOrderItemId;
    @Column(name="description_snapshot",nullable=false,length=240)
    private String descriptionSnapshot;
    @Column(name="quantity_snapshot",nullable=false,precision=14,scale=3)
    private BigDecimal quantitySnapshot;
    @Column(name="unit_price_snapshot",nullable=false,precision=19,scale=2)
    private BigDecimal unitPriceSnapshot;
    @Column(name="line_total_amount",nullable=false,precision=19,scale=2)
    private BigDecimal lineTotalAmount;
}
