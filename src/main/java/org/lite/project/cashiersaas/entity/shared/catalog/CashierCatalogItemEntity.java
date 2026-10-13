package org.lite.project.cashiersaas.entity.shared.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.lite.project.cashiersaas.entity.shared.MutableBaseEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.OutletEntity;
import org.lite.project.cashiersaas.entity.shared.merchant.TenantEntity;

import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "cashier_catalog_items", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "outlet_id", "name"}))
@NoArgsConstructor
public class CashierCatalogItemEntity extends MutableBaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "outlet_id", nullable = false)
    private OutletEntity outlet;
    @Column(nullable = false, length = 140)
    private String name;
    @Column(name = "billing_unit", nullable = false, length = 10)
    private String billingUnit;
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 2)
    private BigDecimal unitPrice;
    @Column(nullable = false)
    private boolean active = true;

    public static CashierCatalogItemEntity create(TenantEntity tenant, OutletEntity outlet,
                                                   String name, String billingUnit, BigDecimal unitPrice) {
        CashierCatalogItemEntity item = new CashierCatalogItemEntity();
        item.tenant = tenant;
        item.outlet = outlet;
        item.name = name;
        item.billingUnit = billingUnit;
        item.unitPrice = unitPrice;
        return item;
    }

    public void changePrice(BigDecimal newPrice) {
        unitPrice = newPrice;
    }

    public void archive() {
        active = false;
    }
}
