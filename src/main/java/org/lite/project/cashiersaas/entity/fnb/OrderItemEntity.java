package org.lite.project.cashiersaas.entity.fnb;

import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.catalog.ProductEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@Entity
@Table(name = "order_items")
@NoArgsConstructor
public class OrderItemEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_order_id", nullable = false)
    private SalesOrderEntity salesOrder;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private ProductEntity product;
    @Column(nullable = false, length = 160)
    private String productNameSnapshot;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPriceSnapshot;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal lineTotal;
    @Column(length = 500)
    private String note;
    @Column(name = "cost_amount", precision = 19, scale = 2)
    private BigDecimal costAmount;

    public void recordCost(BigDecimal cost) {
        this.costAmount = cost == null ? null : cost.setScale(2, RoundingMode.HALF_UP);
    }

    public static OrderItemEntity snapshot(org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity tenant,
                                           org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity outlet,
                                           SalesOrderEntity order, ProductEntity product, int quantity,
                                           BigDecimal lineTotal, String note) {
        OrderItemEntity item = new OrderItemEntity();
        item.tenant = tenant;
        item.outlet = outlet;
        item.salesOrder = order;
        item.product = product;
        item.productNameSnapshot = product.getName();
        item.unitPriceSnapshot = product.getPrice();
        item.quantity = quantity;
        item.lineTotal = lineTotal;
        item.note = note;
        return item;
    }
}
