package org.lite.project.cashiersaas.entity.laundry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.BaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "cashier_sale_items")
@NoArgsConstructor
public class CashierSaleItemEntity extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private CashierSaleEntity sale;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "catalog_item_id", nullable = false)
    private CashierCatalogItemEntity catalogItem;
    @Column(name = "name_snapshot", nullable = false, length = 140)
    private String nameSnapshot;
    @Column(name = "billing_unit_snapshot", nullable = false, length = 10)
    private String billingUnitSnapshot;
    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;
    @Column(name = "unit_price_snapshot", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPriceSnapshot;
    @Column(name = "line_total_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal lineTotalAmount;

    public static CashierSaleItemEntity snapshot(CashierSaleEntity sale, CashierCatalogItemEntity item,
                                                  BigDecimal quantity, BigDecimal lineTotal) {
        CashierSaleItemEntity line = new CashierSaleItemEntity();
        line.tenant = sale.getTenant();
        line.outlet = sale.getOutlet();
        line.sale = sale;
        line.catalogItem = item;
        line.nameSnapshot = item.getName();
        line.billingUnitSnapshot = item.getBillingUnit();
        line.quantity = quantity;
        line.unitPriceSnapshot = item.getUnitPrice();
        line.lineTotalAmount = lineTotal;
        return line;
    }
}
